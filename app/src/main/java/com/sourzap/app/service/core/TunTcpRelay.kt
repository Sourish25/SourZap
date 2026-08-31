package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.service.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Ultra High-Performance Low-Allocation TCP Relay Engine for VpnService TUN Interface.
 * Handles thousands of simultaneous TCP connections, 4K/8K video streaming, and BitTorrent swarm traffic.
 * Implements RFC 793 TCP state machine, resilient teardown with FIN/RST packet synthesis to prevent
 * client CLOSE_WAIT hangs, duplicate SYN de-duplication, multi-chunk handshake buffering, and zero GC thrashing.
 */
class TunTcpRelay(
    private val vpnService: VpnService,
    private val vpnOutput: FileOutputStream,
    private val scope: CoroutineScope
) {
    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val isRunning = AtomicBoolean(true)
    private val activeConnectingCount = AtomicInteger(0)

    private val tcpDispatcher = Dispatchers.IO.limitedParallelism(64)
    private var scavengerJob: Job? = null

    companion object {
        val EMPTY_BYTE_ARRAY = ByteArray(0)
        const val MAX_SEGMENT_SIZE = 1400 // Fits comfortably in standard 1500 MTU
        const val IDLE_TIMEOUT_MS = 120_000L // 2 minutes idle timeout
        const val LINGER_TIMEOUT_MS = 15_000L // 15 seconds TIME_WAIT / CLOSED linger
        const val MAX_CONCURRENT_CONNECTING = 64
        const val MAX_SESSIONS = 4096

        const val MAX_HANDSHAKE_BUFFER_SIZE = 4096
        const val HANDSHAKE_BUFFER_TIMEOUT_MS = 150L

        /**
         * Determines if an accumulated TCP payload contains a complete handshake structure
         * (TLS ClientHello, BitTorrent Peer Wire handshake, or HTTP request headers).
         * Returns true immediately for non-DPI protocols (SSH, Noise, raw TCP) to guarantee 0ms passthrough.
         */
        fun isHandshakeComplete(buffer: ByteArray, length: Int): Boolean {
            if (length <= 0) return false
            val safeLen = minOf(buffer.size, length)
            if (safeLen <= 0) return false

            val b0 = buffer[0].toInt() and 0xFF

            // 1. TLS Handshake (0x16 0x03)
            if (b0 == 0x16) {
                if (safeLen < 2) return false
                val b1 = buffer[1].toInt() and 0xFF
                if (b1 == 0x03) {
                    if (safeLen < 5) return false
                    val recordLen = ((buffer[3].toInt() and 0xFF) shl 8) or (buffer[4].toInt() and 0xFF)
                    val fullLen = (5 + recordLen).coerceAtMost(MAX_HANDSHAKE_BUFFER_SIZE)
                    return safeLen >= fullLen
                }
                return true // Non-standard TLS record version -> proceed
            }

            // 2. BitTorrent Handshake (0x13 "BitTorrent protocol")
            if (b0 == 0x13) {
                val expectedPrefix = DpiEngine.BT_PROTOCOL_BYTES
                val checkLen = minOf(safeLen, expectedPrefix.size)
                for (i in 1 until checkLen) {
                    if (buffer[i] != expectedPrefix[i]) {
                        return true // Mismatched prefix, not BitTorrent -> proceed
                    }
                }
                if (safeLen < expectedPrefix.size) {
                    return false // Matches prefix so far, wait for at least 20 bytes
                }
                return safeLen >= DpiEngine.MIN_BT_HANDSHAKE_LEN // Wait for 68-byte handshake
            }

            // 3. HTTP Request
            val httpMethods = listOf("GET ", "POST ", "HEAD ", "OPTIONS ", "PUT ", "DELETE ", "CONNECT ", "TRACE ", "PATCH ")
            val startStr = String(buffer, 0, minOf(safeLen, 8), Charsets.ISO_8859_1)
            val matchesMethod = httpMethods.any { method ->
                if (safeLen >= method.length) startStr.startsWith(method)
                else method.startsWith(startStr)
            }

            if (matchesMethod) {
                val hasFullMethod = httpMethods.any { startStr.startsWith(it) }
                if (!hasFullMethod && safeLen < 8) return false

                // Check for end of HTTP headers
                if (HttpParser.findHeaderBoundary(buffer, safeLen) != null) {
                    return true
                }
                return safeLen >= 2048 // Header inspection bound
            }

            // 4. Non-DPI protocols (SSH, Noise, DNS, Raw TCP) -> immediate completion
            return true
        }
    }

    enum class TcpState {
        SYN_RECEIVED,
        ESTABLISHED,
        SERVER_FIN_SENT,
        CLIENT_FIN_RECEIVED,
        CLOSED
    }

    data class TcpSession(
        val key: String,
        val srcIp: InetAddress,
        val dstIp: InetAddress,
        val srcPort: Int,
        val dstPort: Int,
        val clientSeq: AtomicLong,
        val serverSeq: AtomicLong,
        @Volatile var clientAck: Long = 0L,
        @Volatile var lastActivity: Long = System.currentTimeMillis(),
        @Volatile var state: TcpState = TcpState.SYN_RECEIVED,
        val isConnected: AtomicBoolean = AtomicBoolean(false),
        val isHandshakeDesynced: AtomicBoolean = AtomicBoolean(false),
        val isClosed: AtomicBoolean = AtomicBoolean(false),
        val sendQueue: Channel<ByteArray> = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST),
        var socket: Socket? = null,
        var upstreamOut: OutputStream? = null,
        var streamJob: Job? = null,
        var senderJob: Job? = null
    )

    init {
        scavengerJob = scope.launch(tcpDispatcher) {
            while (isActive && isRunning.get()) {
                delay(15000)
                val now = System.currentTimeMillis()
                val iterator = sessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val session = entry.value
                    val isExpired = if (session.state == TcpState.CLOSED || session.state == TcpState.SERVER_FIN_SENT) {
                        now - session.lastActivity > LINGER_TIMEOUT_MS
                    } else {
                        now - session.lastActivity > IDLE_TIMEOUT_MS
                    }
                    if (isExpired) {
                        closeSessionInternal(session, forceRemove = true)
                    }
                }
            }
        }
    }

    fun handleTcpPacket(
        buffer: ByteArray,
        length: Int,
        ipHeaderLen: Int,
        srcIp: InetAddress,
        dstIp: InetAddress
    ) {
        if (length < ipHeaderLen + 20 || !isRunning.get()) return

        val tcpHeader = PacketParser.parseTcpHeader(buffer, ipHeaderLen, length) ?: return

        val srcPort = tcpHeader.srcPort
        val dstPort = tcpHeader.dstPort
        val seqNum = tcpHeader.seqNum
        val ackNum = tcpHeader.ackNum
        val isSyn = tcpHeader.isSyn
        val isAck = tcpHeader.isAck
        val isFin = tcpHeader.isFin
        val isRst = tcpHeader.isRst
        val payloadOffset = tcpHeader.payloadOffset
        val payloadLen = tcpHeader.payloadLength

        val sessionKey = "$srcIp:$srcPort->$dstIp:$dstPort"

        if (isRst) {
            val session = sessions.remove(sessionKey)
            if (session != null) {
                closeSessionInternal(session, forceRemove = false)
            }
            return
        }

        if (isSyn && !isAck) {
            // Handle SYN: Check for duplicate SYN or new connection
            val existing = sessions[sessionKey]
            if (existing != null) {
                if (existing.state == TcpState.SYN_RECEIVED || existing.state == TcpState.ESTABLISHED) {
                    // Duplicate SYN retransmission from client
                    val synAckPacket = PacketParser.buildTcpPacket(
                        srcIp = dstIp,
                        dstIp = srcIp,
                        srcPort = dstPort,
                        dstPort = srcPort,
                        seqNum = (existing.serverSeq.get() - 1) and 0xFFFFFFFFL,
                        ackNum = existing.clientSeq.get(),
                        flags = 0x12, // SYN | ACK
                        payload = EMPTY_BYTE_ARRAY
                    )
                    writeTunPacket(synAckPacket)
                    return
                } else {
                    // Previous session was closed/lingering, remove and re-create
                    closeSessionInternal(existing, forceRemove = true)
                }
            }

            if (sessions.size >= MAX_SESSIONS || activeConnectingCount.get() >= MAX_CONCURRENT_CONNECTING) {
                val rstPacket = PacketParser.buildTcpPacket(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seqNum = 0L,
                    ackNum = (seqNum + 1) and 0xFFFFFFFFL,
                    flags = 0x14, // RST | ACK
                    payload = EMPTY_BYTE_ARRAY
                )
                writeTunPacket(rstPacket)
                return
            }

            val initialServerSeq = (System.nanoTime() and 0x7FFFFFFF)
            val session = TcpSession(
                key = sessionKey,
                srcIp = srcIp,
                dstIp = dstIp,
                srcPort = srcPort,
                dstPort = dstPort,
                clientSeq = AtomicLong((seqNum + 1) and 0xFFFFFFFFL),
                serverSeq = AtomicLong((initialServerSeq + 1) and 0xFFFFFFFFL),
                state = TcpState.SYN_RECEIVED
            )

            val previous = sessions.putIfAbsent(sessionKey, session)
            if (previous != null) {
                // Race condition: another thread created session simultaneously
                val synAckPacket = PacketParser.buildTcpPacket(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seqNum = (previous.serverSeq.get() - 1) and 0xFFFFFFFFL,
                    ackNum = previous.clientSeq.get(),
                    flags = 0x12, // SYN | ACK
                    payload = EMPTY_BYTE_ARRAY
                )
                writeTunPacket(synAckPacket)
                return
            }

            // Synthesize RFC 793 SYN-ACK packet back to client app via TUN interface
            val synAckPacket = PacketParser.buildTcpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seqNum = initialServerSeq,
                ackNum = (seqNum + 1) and 0xFFFFFFFFL,
                flags = 0x12, // SYN | ACK
                payload = EMPTY_BYTE_ARRAY
            )
            writeTunPacket(synAckPacket)

            // Asynchronously connect to remote upstream socket in background coroutine
            startUpstreamConnection(session)
            return
        }

        val session = sessions[sessionKey]
        if (session == null) {
            if (isAck && payloadLen == 0) {
                // Stray pure ACK from old/closed session, drop silently to avoid RST loops
                return
            }
            // Out-of-state packet for non-existent session, reject with RST
            val rstPacket = PacketParser.buildTcpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seqNum = ackNum,
                ackNum = (seqNum + payloadLen) and 0xFFFFFFFFL,
                flags = 0x14, // RST | ACK
                payload = EMPTY_BYTE_ARRAY
            )
            writeTunPacket(rstPacket)
            return
        }

        session.lastActivity = System.currentTimeMillis()

        if (isFin) {
            // Client is closing connection (Half-Close or Teardown)
            session.state = TcpState.CLIENT_FIN_RECEIVED
            session.clientSeq.updateAndGet { (seqNum + payloadLen + 1) and 0xFFFFFFFFL }

            // ACK client's FIN immediately
            val ackPacket = PacketParser.buildTcpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seqNum = session.serverSeq.get(),
                ackNum = session.clientSeq.get(),
                flags = 0x10, // ACK
                payload = EMPTY_BYTE_ARRAY
            )
            writeTunPacket(ackPacket)

            // Send server FIN to close downstream TUN side
            val finAck = PacketParser.buildTcpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seqNum = session.serverSeq.get(),
                ackNum = session.clientSeq.get(),
                flags = 0x11, // FIN | ACK
                payload = EMPTY_BYTE_ARRAY
            )
            session.serverSeq.updateAndGet { (it + 1) and 0xFFFFFFFFL }
            writeTunPacket(finAck)

            // Gracefully half-close upstream write side so remote server can finish responding
            try {
                session.socket?.shutdownOutput()
            } catch (_: Exception) {}

            closeSessionInternal(session, forceRemove = false)
            return
        }

        if (isAck && payloadLen == 0) {
            session.clientAck = ackNum
            if (session.state == TcpState.SYN_RECEIVED) {
                session.state = TcpState.ESTABLISHED
            } else if (session.state == TcpState.SERVER_FIN_SENT) {
                // Client ACK'd server FIN
                session.state = TcpState.CLOSED
            }
        }

        if (payloadLen > 0) {
            // Data Payload received from App
            val payload = buffer.copyOfRange(payloadOffset, length)
            session.clientSeq.updateAndGet { (seqNum + payloadLen) and 0xFFFFFFFFL }

            // Send immediate ACK back to app so its TCP window stays wide open
            val ackPacket = PacketParser.buildTcpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seqNum = session.serverSeq.get(),
                ackNum = session.clientSeq.get(),
                flags = 0x10, // ACK
                payload = EMPTY_BYTE_ARRAY
            )
            writeTunPacket(ackPacket)

            // Enqueue in sequential FIFO channel
            session.sendQueue.trySend(payload)
        }
    }

    private fun startUpstreamConnection(session: TcpSession) {
        session.streamJob = scope.launch(tcpDispatcher) {
            activeConnectingCount.incrementAndGet()
            TrafficMonitor.onConnectionOpened()
            var socketConnected = false
            var localSocket: Socket? = null
            try {
                val socket = Socket().apply {
                    receiveBufferSize = 2097152 // 2MB Turbo Receive Buffer
                    sendBufferSize = 1048576    // 1MB Send Buffer
                    tcpNoDelay = true           // Disable Nagle's algorithm
                    keepAlive = true
                    soTimeout = 0               // Persistent keepalive
                    trafficClass = 0x08         // IPTOS_THROUGHPUT
                    setPerformancePreferences(0, 1, 2)
                }
                localSocket = socket
                session.socket = socket

                if (session.isClosed.get() || !isRunning.get()) {
                    try { socket.close() } catch (_: Exception) {}
                    return@launch
                }

                vpnService.protect(socket)
                socket.connect(InetSocketAddress(session.dstIp, session.dstPort), 3000)

                if (session.isClosed.get() || !isRunning.get()) {
                    try { socket.close() } catch (_: Exception) {}
                    return@launch
                }

                val upstreamOut = socket.getOutputStream()
                session.upstreamOut = upstreamOut
                session.isConnected.set(true)
                session.state = TcpState.ESTABLISHED
                socketConnected = true
                activeConnectingCount.decrementAndGet()

                // Dedicated sequential sender loop (FIFO order) with Multi-Chunk Handshake Buffering
                session.senderJob = launch(tcpDispatcher) {
                    try {
                        var handshakeBuffer: ByteArrayOutputStream? = ByteArrayOutputStream(1024)

                        while (scope.isActive && isRunning.get() && session.isConnected.get()) {
                            if (!session.isHandshakeDesynced.get()) {
                                val currentBufSize = handshakeBuffer?.size() ?: 0

                                var channelClosed = false
                                val payload = if (currentBufSize == 0) {
                                    // Wait for first chunk without timeout
                                    val recvRes = session.sendQueue.receiveCatching()
                                    if (recvRes.isClosed) channelClosed = true
                                    recvRes.getOrNull()
                                } else {
                                    // Buffer subsequent chunks with timeout
                                    withTimeoutOrNull(HANDSHAKE_BUFFER_TIMEOUT_MS) {
                                        val recvRes = session.sendQueue.receiveCatching()
                                        if (recvRes.isClosed) channelClosed = true
                                        recvRes.getOrNull()
                                    }
                                }

                                if (payload != null) {
                                    session.lastActivity = System.currentTimeMillis()
                                    handshakeBuffer?.write(payload)
                                }

                                val currentBuf = handshakeBuffer?.toByteArray() ?: EMPTY_BYTE_ARRAY
                                val complete = (currentBufSize > 0 && payload == null) ||
                                        isHandshakeComplete(currentBuf, currentBuf.size) ||
                                        currentBuf.size >= MAX_HANDSHAKE_BUFFER_SIZE

                                if (complete && currentBuf.isNotEmpty()) {
                                    session.isHandshakeDesynced.set(true)
                                    val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value
                                    var appliedTechnique = "DIRECT"

                                    val isBt = DpiEngine.isBitTorrentHandshake(currentBuf, currentBuf.size)
                                    val sniResult = if (!isBt) TlsParser.parseClientHello(currentBuf, currentBuf.size) else TlsParser.SniResult(null, -1, -1, false)

                                    val protocolName = when {
                                        isBt -> "BitTorrent"
                                        sniResult.isClientHello || session.dstPort == 443 -> "TLS"
                                        HttpParser.parseHttpRequest(currentBuf, currentBuf.size).isHttp -> "HTTP"
                                        else -> "TCP"
                                    }

                                    val logDomain = when {
                                        isBt -> "BitTorrent Swarm"
                                        sniResult.hostname != null -> sniResult.hostname
                                        else -> session.dstIp.hostAddress ?: "Socket"
                                    }

                                    DpiEngine.desyncAndSend(
                                        socket = socket,
                                        outputStream = upstreamOut,
                                        payload = currentBuf,
                                        length = currentBuf.size,
                                        strategy = strategy,
                                        onTechniqueApplied = { appliedTechnique = it }
                                    )

                                    TrafficMonitor.addConnectionLog(
                                        ConnectionLog(
                                            domain = logDomain,
                                            port = session.dstPort,
                                            protocol = protocolName,
                                            technique = appliedTechnique,
                                            bytesTransferred = currentBuf.size.toLong()
                                        )
                                    )

                                    handshakeBuffer = null // Deallocate transient buffer
                                }

                                if (payload == null && (channelClosed || session.isClosed.get())) {
                                    break
                                }
                            } else {
                                // Post-handshake high-speed streaming phase: direct write
                                val payload = session.sendQueue.receiveCatching().getOrNull() ?: break
                                session.lastActivity = System.currentTimeMillis()
                                upstreamOut.write(payload)
                                upstreamOut.flush()
                            }
                        }
                    } catch (_: Exception) {
                        closeSessionInternal(session, forceRemove = false)
                    }
                }

                // Downstream reader loop - Zero-allocation packet synthesis directly from pooled buffer
                val input = socket.getInputStream()
                val readBuffer = ByteArrayPool.obtainStreamBuffer()
                try {
                    var bytesRead = input.read(readBuffer)
                    while (scope.isActive && bytesRead != -1 && session.isConnected.get() && isRunning.get()) {
                        if (bytesRead > 0) {
                            session.lastActivity = System.currentTimeMillis()
                            TrafficMonitor.recordRxBytes(bytesRead.toLong())

                            var offset = 0
                            while (offset < bytesRead) {
                                val chunkLen = minOf(bytesRead - offset, MAX_SEGMENT_SIZE)
                                val currentSeq = session.serverSeq.get()
                                val dataPacket = PacketParser.buildTcpPacket(
                                    srcIp = session.dstIp,
                                    dstIp = session.srcIp,
                                    srcPort = session.dstPort,
                                    dstPort = session.srcPort,
                                    seqNum = currentSeq,
                                    ackNum = session.clientSeq.get(),
                                    flags = 0x18, // PSH | ACK
                                    payload = readBuffer,
                                    payloadOffset = offset,
                                    payloadLen = chunkLen
                                )
                                session.serverSeq.updateAndGet { (it + chunkLen) and 0xFFFFFFFFL }
                                writeTunPacket(dataPacket)
                                offset += chunkLen
                            }
                        }
                        bytesRead = input.read(readBuffer)
                    }
                } finally {
                    ByteArrayPool.recycleStreamBuffer(readBuffer)
                }

                // Upstream EOF reached: send FIN-ACK to client app TUN interface
                if (session.isConnected.get() && session.state != TcpState.CLOSED) {
                    session.state = TcpState.SERVER_FIN_SENT
                    val finAck = PacketParser.buildTcpPacket(
                        srcIp = session.dstIp,
                        dstIp = session.srcIp,
                        srcPort = session.dstPort,
                        dstPort = session.srcPort,
                        seqNum = session.serverSeq.get(),
                        ackNum = session.clientSeq.get(),
                        flags = 0x11, // FIN | ACK
                        payload = EMPTY_BYTE_ARRAY
                    )
                    session.serverSeq.updateAndGet { (it + 1) and 0xFFFFFFFFL }
                    writeTunPacket(finAck)
                }
            } catch (_: Exception) {
                try { localSocket?.close() } catch (_: Exception) {}
                if (!socketConnected) {
                    activeConnectingCount.decrementAndGet()
                }
                // Send RST | ACK on upstream connect or runtime socket failures so client apps never hang in CLOSE_WAIT
                val rstPacket = PacketParser.buildTcpPacket(
                    srcIp = session.dstIp,
                    dstIp = session.srcIp,
                    srcPort = session.dstPort,
                    dstPort = session.srcPort,
                    seqNum = session.serverSeq.get(),
                    ackNum = session.clientSeq.get(),
                    flags = 0x14, // RST | ACK
                    payload = EMPTY_BYTE_ARRAY
                )
                writeTunPacket(rstPacket)
            } finally {
                closeSessionInternal(session, forceRemove = false)
                TrafficMonitor.onConnectionClosed()
            }
        }
    }

    private fun writeTunPacket(packet: ByteArray) {
        try {
            synchronized(vpnOutput) {
                vpnOutput.write(packet)
            }
        } catch (_: Exception) {}
    }

    private fun closeSessionInternal(session: TcpSession, forceRemove: Boolean) {
        if (session.isClosed.compareAndSet(false, true)) {
            session.isConnected.set(false)
            session.state = TcpState.CLOSED
            session.sendQueue.close()
            session.senderJob?.cancel()
            session.streamJob?.cancel()
            try {
                session.socket?.close()
            } catch (_: Exception) {}
        }
        if (forceRemove) {
            sessions.remove(session.key)
        }
    }

    fun closeAll() {
        isRunning.set(false)
        scavengerJob?.cancel()
        sessions.values.forEach { closeSessionInternal(it, forceRemove = true) }
        sessions.clear()
    }
}