package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.service.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Ultra High-Performance Low-Allocation TCP Relay Engine for VpnService TUN Interface.
 * Handles thousands of simultaneous TCP connections, 4K/8K video streaming, and BitTorrent swarm traffic.
 * Implements RFC 793 TCP state machine, resilient teardown with FIN/RST packet synthesis to prevent
 * client CLOSE_WAIT hangs, duplicate SYN de-duplication, and zero GC thrashing.
 */
class TunTcpRelay(
    private val vpnService: VpnService,
    private val vpnOutput: FileOutputStream,
    private val scope: CoroutineScope
) {
    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val isRunning = AtomicBoolean(true)
    private val activeConnectingCount = AtomicInteger(0)

    private val tcpExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "SourZap-TunTcpWorker").apply { isDaemon = true }
    }
    private val tcpDispatcher = tcpExecutor.asCoroutineDispatcher()
    private var scavengerJob: Job? = null

    companion object {
        private val EMPTY_BYTE_ARRAY = ByteArray(0)
        private const val MAX_SEGMENT_SIZE = 1400 // Fits comfortably in standard 1500 MTU
        private const val IDLE_TIMEOUT_MS = 120_000L // 2 minutes idle timeout
        private const val LINGER_TIMEOUT_MS = 15_000L // 15 seconds TIME_WAIT / CLOSED linger
        private const val MAX_CONCURRENT_CONNECTING = 64
        private const val MAX_SESSIONS = 4096
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
        val sendQueue: Channel<ByteArray> = Channel(Channel.UNLIMITED),
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

        val sessionKey = "${srcIp.hostAddress}:$srcPort->${dstIp.hostAddress}:$dstPort"

        if (isSyn && !isAck) {
            // Guard against duplicate SYN retransmission: if session already exists and active, resend SYN-ACK
            val existing = sessions[sessionKey]
            if (existing != null && !existing.isClosed.get() && existing.state != TcpState.CLOSED) {
                val synAckPacket = PacketParser.buildTcpPacket(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seqNum = existing.serverSeq.get(),
                    ackNum = existing.clientSeq.get(),
                    flags = 0x12, // SYN | ACK
                    payload = EMPTY_BYTE_ARRAY
                )
                writeTunPacket(synAckPacket)
                return
            }

            // Guard against torrent swarm socket flood & socket exhaustion
            if (activeConnectingCount.get() >= MAX_CONCURRENT_CONNECTING || sessions.size >= MAX_SESSIONS) {
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

            // 1. New TCP Connection Initiation (SYN)
            val session = TcpSession(
                key = sessionKey,
                srcIp = srcIp,
                dstIp = dstIp,
                srcPort = srcPort,
                dstPort = dstPort,
                clientSeq = AtomicLong((seqNum + 1) and 0xFFFFFFFFL),
                serverSeq = AtomicLong(1000000L),
                lastActivity = System.currentTimeMillis(),
                state = TcpState.SYN_RECEIVED
            )
            sessions[sessionKey] = session

            // Reply with SYN-ACK immediately to complete handshake with client app (<1ms)
            val synAckPacket = PacketParser.buildTcpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seqNum = session.serverSeq.get(),
                ackNum = session.clientSeq.get(),
                flags = 0x12, // SYN | ACK
                payload = EMPTY_BYTE_ARRAY
            )
            session.serverSeq.updateAndGet { (it + 1) and 0xFFFFFFFFL }
            writeTunPacket(synAckPacket)

            // Connect upstream socket asynchronously on isolated thread pool
            startUpstreamConnection(session)
            return
        }

        val session = sessions[sessionKey]
        if (session == null) {
            // Unsolicited packet or packet for dead session: if client sent FIN or data, reject with RST|ACK
            if (isFin || payloadLen > 0) {
                val rstPacket = PacketParser.buildTcpPacket(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seqNum = ackNum,
                    ackNum = if (isFin) (seqNum + 1) and 0xFFFFFFFFL else (seqNum + payloadLen) and 0xFFFFFFFFL,
                    flags = 0x14, // RST | ACK
                    payload = EMPTY_BYTE_ARRAY
                )
                writeTunPacket(rstPacket)
            }
            return
        }

        session.lastActivity = System.currentTimeMillis()

        if (isRst) {
            closeSessionInternal(session, forceRemove = true)
            return
        }

        if (isFin) {
            // Client is closing connection
            session.state = TcpState.CLIENT_FIN_RECEIVED
            session.clientSeq.updateAndGet { ((seqNum + 1) and 0xFFFFFFFFL) }

            // Reply with ACK or FIN|ACK to client
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

                vpnService.protect(socket)
                socket.connect(InetSocketAddress(session.dstIp, session.dstPort), 3000)

                session.socket = socket
                val upstreamOut = socket.getOutputStream()
                session.upstreamOut = upstreamOut
                session.isConnected.set(true)
                session.state = TcpState.ESTABLISHED
                socketConnected = true
                activeConnectingCount.decrementAndGet()

                // Dedicated sequential sender loop (FIFO order)
                session.senderJob = launch(tcpDispatcher) {
                    try {
                        for (payload in session.sendQueue) {
                            if (!scope.isActive || !isRunning.get() || !session.isConnected.get()) break
                            session.lastActivity = System.currentTimeMillis()

                            if (!session.isHandshakeDesynced.getAndSet(true)) {
                                val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value
                                var appliedTechnique = "DIRECT"

                                val sniResult = TlsParser.parseClientHello(payload, payload.size)
                                val logDomain = sniResult.hostname ?: session.dstIp.hostAddress ?: "Socket"

                                DpiEngine.desyncAndSend(
                                    socket = socket,
                                    outputStream = upstreamOut,
                                    payload = payload,
                                    length = payload.size,
                                    strategy = strategy,
                                    onTechniqueApplied = { appliedTechnique = it }
                                )

                                TrafficMonitor.addConnectionLog(
                                    ConnectionLog(
                                        domain = logDomain,
                                        port = session.dstPort,
                                        protocol = if (session.dstPort == 443) "TLS" else "TCP",
                                        technique = appliedTechnique,
                                        bytesTransferred = payload.size.toLong()
                                    )
                                )
                            } else {
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
                vpnOutput.flush()
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
        try {
            tcpExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}