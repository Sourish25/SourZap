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
import kotlinx.coroutines.selects.select
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
    private val scope: CoroutineScope,
    private val tunWriteQueue: Channel<ByteArray>? = null,
    private val tunPriorityWriteQueue: Channel<ByteArray>? = null
) {
    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val isRunning = AtomicBoolean(true)
    private val activeConnectingCount = AtomicInteger(0)

    private val tcpDispatcher = Dispatchers.IO.limitedParallelism(512)
    private var scavengerJob: Job? = null
    private val localTunWriteChannel = tunWriteQueue ?: Channel<ByteArray>(capacity = 8192, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val localTunPriorityChannel = tunPriorityWriteQueue ?: Channel<ByteArray>(capacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var tunWriterJob: Job? = null

    companion object {
        val EMPTY_BYTE_ARRAY = ByteArray(0)
        const val MAX_SEGMENT_SIZE = 1400 // Fits comfortably in standard 1500 MTU
        const val IDLE_TIMEOUT_MS = 120_000L // 2 minutes idle timeout
        const val GAMING_IDLE_TIMEOUT_MS = 600_000L // 10 minutes extended idle timeout for gaming sessions (Supercell Clash of Clans, etc.)
        const val GAMING_CONNECT_TIMEOUT_MS = 10_000 // 10 seconds connect timeout for gaming ports
        const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000 // 3 seconds standard connect timeout
        const val LINGER_TIMEOUT_MS = 3_000L // 3 seconds TIME_WAIT / CLOSED linger
        const val MAX_CONCURRENT_CONNECTING = 256
        const val MAX_SESSIONS = 8192

        fun isGamingPort(port: Int): Boolean =
            port == 9339 || port == 30000 || port in 27015..27030 || port in 7777..7780 || port == 10012

        fun isPriorityPort(port: Int): Boolean =
            isGamingPort(port) || port == 80 || port == 443 || port == 853 || port == 53

        fun isPacketPriority(packet: ByteArray): Boolean {
            if (packet.size < 24) return false
            val version = (packet[0].toInt() shr 4) and 0x0F
            if (version == 4) {
                val ihl = (packet[0].toInt() and 0x0F) * 4
                if (packet.size >= ihl + 4) {
                    val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                    val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                    return isGamingPort(srcPort) || isGamingPort(dstPort)
                }
            }
            return false
        }

        const val MAX_HANDSHAKE_BUFFER_SIZE = 4096
        const val HANDSHAKE_BUFFER_TIMEOUT_MS = 500L

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
        val sendQueue: Channel<ByteArray> = Channel(capacity = 512, onBufferOverflow = BufferOverflow.SUSPEND),
        var socket: Socket? = null,
        var upstreamOut: OutputStream? = null,
        var streamJob: Job? = null,
        var senderJob: Job? = null
    )

    init {
        if (tunWriteQueue == null) {
            tunWriterJob = scope.launch(Dispatchers.IO) {
                while (scope.isActive && isRunning.get()) {
                    // Strict QoS priority scheduling: drain all available priority packets first
                    var sentPriority = false
                    while (true) {
                        val priorityPacket = localTunPriorityChannel.tryReceive().getOrNull() ?: break
                        sentPriority = true
                        try {
                            vpnOutput.write(priorityPacket)
                        } catch (_: Exception) {}
                    }
                    if (sentPriority) continue

                    // When priority queue is empty, wait for next packet from either channel using select
                    try {
                        select<Unit> {
                            localTunPriorityChannel.onReceive { packet ->
                                try { vpnOutput.write(packet) } catch (_: Exception) {}
                            }
                            localTunWriteChannel.onReceive { packet ->
                                try { vpnOutput.write(packet) } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            }
        }

        scavengerJob = scope.launch(Dispatchers.Default) {
            while (isActive && isRunning.get()) {
                delay(5000)
                val now = System.currentTimeMillis()
                val iterator = sessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val session = entry.value
                    val timeout = if (isGamingPort(session.dstPort)) GAMING_IDLE_TIMEOUT_MS else IDLE_TIMEOUT_MS
                    val isExpired = if (session.state == TcpState.CLOSED || session.state == TcpState.SERVER_FIN_SENT) {
                        now - session.lastActivity > LINGER_TIMEOUT_MS
                    } else {
                        now - session.lastActivity > timeout
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

            val isPriority = isPriorityPort(dstPort)

            if (sessions.size >= MAX_SESSIONS) {
                // First pass: Evict closed/lingering sessions
                val iter = sessions.entries.iterator()
                while (iter.hasNext()) {
                    val s = iter.next().value
                    if (s.isClosed.get() || s.state == TcpState.CLOSED || s.state == TcpState.SERVER_FIN_SENT) {
                        iter.remove()
                    }
                }
                // Second pass: If still near capacity, evict oldest idle non-priority sessions
                if (sessions.size >= MAX_SESSIONS - 64) {
                    val now = System.currentTimeMillis()
                    val idleThreshold = now - 20_000L // Idle for 20s
                    val evictIter = sessions.entries.iterator()
                    var evicted = 0
                    while (evictIter.hasNext() && evicted < 256) {
                        val s = evictIter.next().value
                        if (!isPriorityPort(s.dstPort) && (s.lastActivity < idleThreshold || s.state != TcpState.ESTABLISHED)) {
                            closeSessionInternal(s, forceRemove = true)
                            evicted++
                        }
                    }
                }
            }

            // For priority ports (gaming, web browsing, DNS), never reject due to background connection saturation
            val rejectDueToLoad = if (isPriority) {
                sessions.size >= MAX_SESSIONS && activeConnectingCount.get() >= (MAX_CONCURRENT_CONNECTING * 2)
            } else {
                sessions.size >= MAX_SESSIONS || activeConnectingCount.get() >= MAX_CONCURRENT_CONNECTING
            }

            if (rejectDueToLoad) {
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

            // Lossless backpressure: safely enqueue before acknowledging to client OS
            val enqueued = session.sendQueue.trySend(payload).isSuccess
            if (enqueued) {
                // Safely enqueued in lossless FIFO queue: advance clientSeq and synthesize ACK
                session.clientSeq.updateAndGet { (seqNum + payloadLen) and 0xFFFFFFFFL }
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
            } else {
                // SendQueue saturated: do NOT advance clientSeq or acknowledge uncommitted bytes!
                // Synthesize RFC zero-window flow control ACK for current sequence to apply lossless backpressure
                val backpressureAck = PacketParser.buildTcpPacket(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seqNum = session.serverSeq.get(),
                    ackNum = session.clientSeq.get(),
                    flags = 0x10, // ACK
                    payload = EMPTY_BYTE_ARRAY,
                    windowSize = 0 // Zero-window backpressure
                )
                writeTunPacket(backpressureAck)
            }
        }
    }

    private fun startUpstreamConnection(session: TcpSession) {
        session.streamJob = scope.launch(tcpDispatcher) {
            val wasConnecting = AtomicBoolean(true)
            activeConnectingCount.incrementAndGet()
            TrafficMonitor.onConnectionOpened()
            var localSocket: Socket? = null
            val isGaming = isGamingPort(session.dstPort)
            try {
                val socket = Socket().apply {
                    receiveBufferSize = if (isGaming) 65536 else 131072 // 64KB for gaming (bufferbloat elimination) vs 128KB mobile default
                    sendBufferSize = 65536     // 64KB Standard Mobile Send Buffer
                    tcpNoDelay = true           // Disable Nagle's algorithm
                    keepAlive = true            // Enable socket keepalive
                    soTimeout = if (isGaming) 30000 else 10000           // 30s for gaming to prevent idling disconnects vs 10s default
                    if (isGaming) {
                        trafficClass = 0x10     // IPTOS_LOWDELAY
                        setPerformancePreferences(1, 2, 0) // connectionTime=1, latency=2, bandwidth=0
                    } else {
                        trafficClass = 0x08     // IPTOS_THROUGHPUT
                        setPerformancePreferences(0, 1, 2) // connectionTime=0, latency=1, bandwidth=2
                    }
                }
                localSocket = socket
                session.socket = socket

                if (session.isClosed.get() || !isRunning.get()) {
                    try { socket.close() } catch (_: Exception) {}
                    return@launch
                }

                vpnService.protect(socket)
                val connectTimeout = if (isGaming) GAMING_CONNECT_TIMEOUT_MS else DEFAULT_CONNECT_TIMEOUT_MS
                socket.connect(InetSocketAddress(session.dstIp, session.dstPort), connectTimeout)

                if (session.isClosed.get() || !isRunning.get()) {
                    try { socket.close() } catch (_: Exception) {}
                    return@launch
                }

                val upstreamOut = socket.getOutputStream()
                session.upstreamOut = upstreamOut
                session.isConnected.set(true)
                session.state = TcpState.ESTABLISHED
                if (wasConnecting.compareAndSet(true, false)) {
                    activeConnectingCount.decrementAndGet()
                }

                // Dedicated sequential sender loop (FIFO order) with Multi-Chunk Handshake Buffering
                session.senderJob = launch(tcpDispatcher) {
                    try {
                        var handshakeBuffer: ByteArrayOutputStream? = ByteArrayOutputStream(1024)

                        while (scope.isActive && isRunning.get() && session.isConnected.get()) {
                            if (isGaming) {
                                // Gaming traffic (Supercell Clash of Clans port 9339, etc.):
                                // Pure zero-latency stream passthrough with zero buffering delay
                                val payload = session.sendQueue.receiveCatching().getOrNull() ?: break
                                session.lastActivity = System.currentTimeMillis()
                                upstreamOut.write(payload)
                                upstreamOut.flush()
                                if (!session.isHandshakeDesynced.get()) {
                                    session.isHandshakeDesynced.set(true)
                                    TrafficMonitor.addConnectionLog(
                                        ConnectionLog(
                                            domain = session.dstIp.hostAddress ?: "Gaming Server",
                                            port = session.dstPort,
                                            protocol = "GAMING",
                                            technique = "ZERO_LATENCY_PASSTHROUGH",
                                            bytesTransferred = payload.size.toLong()
                                        )
                                    )
                                }
                                continue
                            }

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

                // Downstream reader loop - High-throughput wire-speed streaming
                val input = socket.getInputStream()
                val readBuffer = ByteArrayPool.obtain32k()
                try {
                    while (scope.isActive && session.isConnected.get() && isRunning.get()) {
                        val bytesRead = try {
                            input.read(readBuffer)
                        } catch (_: java.net.SocketTimeoutException) {
                            val timeout = if (isGaming) GAMING_IDLE_TIMEOUT_MS else IDLE_TIMEOUT_MS
                            if (!session.isConnected.get() || System.currentTimeMillis() - session.lastActivity > timeout) {
                                -1
                            } else {
                                continue
                            }
                        }

                        if (bytesRead <= 0) {
                            break // Upstream EOF reached
                        }

                        session.lastActivity = System.currentTimeMillis()
                        TrafficMonitor.recordRxBytes(bytesRead.toLong())

                        var offset = 0
                        while (offset < bytesRead) {
                            val chunkLen = minOf(bytesRead - offset, MAX_SEGMENT_SIZE)
                            val currentSeq = session.serverSeq.getAndUpdate { (it + chunkLen) and 0xFFFFFFFFL }
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
                            writeTunPacket(dataPacket, isGaming)
                            offset += chunkLen
                        }
                    }
                } finally {
                    ByteArrayPool.recycle32k(readBuffer)
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
                    writeTunPacket(finAck, isGaming)
                }
            } catch (_: Exception) {
                try { localSocket?.close() } catch (_: Exception) {}
                if (wasConnecting.compareAndSet(true, false)) {
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
                writeTunPacket(rstPacket, isGaming)
            } finally {
                if (wasConnecting.compareAndSet(true, false)) {
                    activeConnectingCount.decrementAndGet()
                }
                closeSessionInternal(session, forceRemove = false)
                TrafficMonitor.onConnectionClosed()
            }
        }
    }

    private fun writeTunPacket(packet: ByteArray, isPriority: Boolean? = null) {
        val priority = isPriority ?: isPacketPriority(packet)
        if (priority) {
            val queued = localTunPriorityChannel.trySend(packet).isSuccess
            if (!queued) {
                localTunWriteChannel.trySend(packet)
            }
        } else {
            localTunWriteChannel.trySend(packet)
        }
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
        tunWriterJob?.cancel()
        sessions.values.forEach { closeSessionInternal(it, forceRemove = true) }
        sessions.clear()
    }
}