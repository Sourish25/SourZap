package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.service.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
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

class TunTcpRelay(
    private val vpnService: VpnService,
    private val vpnOutput: FileOutputStream,
    private val scope: CoroutineScope
) {
    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val isRunning = AtomicBoolean(true)
    private val activeConnectingCount = AtomicInteger(0)
    private val MAX_CONCURRENT_CONNECTING = 48
    private val MAX_SESSIONS = 2048

    private val tcpExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "SourZap-TunTcpWorker").apply { isDaemon = true }
    }
    private val tcpDispatcher = tcpExecutor.asCoroutineDispatcher()
    private var scavengerJob: Job? = null

    data class TcpSession(
        val key: String,
        val srcIp: InetAddress,
        val dstIp: InetAddress,
        val srcPort: Int,
        val dstPort: Int,
        val clientSeq: AtomicLong,
        val serverSeq: AtomicLong,
        var clientAck: Long = 0L,
        var lastActivity: Long = System.currentTimeMillis(),
        val isConnected: AtomicBoolean = AtomicBoolean(false),
        val isHandshakeDesynced: AtomicBoolean = AtomicBoolean(false),
        val sendQueue: Channel<ByteArray> = Channel(Channel.UNLIMITED),
        var socket: Socket? = null,
        var upstreamOut: OutputStream? = null,
        var streamJob: Job? = null
    )

    init {
        scavengerJob = scope.launch(tcpDispatcher) {
            while (isActive && isRunning.get()) {
                delay(30000)
                val now = System.currentTimeMillis()
                val iterator = sessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val session = entry.value
                    if (now - session.lastActivity > 120000) { // 2 minutes idle
                        closeSession(session.key)
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

        val tcpOffset = ipHeaderLen
        val srcPort = ((buffer[tcpOffset].toInt() and 0xFF) shl 8) or (buffer[tcpOffset + 1].toInt() and 0xFF)
        val dstPort = ((buffer[tcpOffset + 2].toInt() and 0xFF) shl 8) or (buffer[tcpOffset + 3].toInt() and 0xFF)

        val seqNum = (((buffer[tcpOffset + 4].toLong() and 0xFF) shl 24) or
                ((buffer[tcpOffset + 5].toLong() and 0xFF) shl 16) or
                ((buffer[tcpOffset + 6].toLong() and 0xFF) shl 8) or
                (buffer[tcpOffset + 7].toLong() and 0xFF)) and 0xFFFFFFFFL

        val ackNum = (((buffer[tcpOffset + 8].toLong() and 0xFF) shl 24) or
                ((buffer[tcpOffset + 9].toLong() and 0xFF) shl 16) or
                ((buffer[tcpOffset + 10].toLong() and 0xFF) shl 8) or
                (buffer[tcpOffset + 11].toLong() and 0xFF)) and 0xFFFFFFFFL

        val dataOffset = ((buffer[tcpOffset + 12].toInt() shr 4) and 0x0F) * 4
        val flags = buffer[tcpOffset + 13].toInt() and 0xFF

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val payloadOffset = tcpOffset + dataOffset
        val payloadLen = length - payloadOffset

        val sessionKey = "${srcIp.hostAddress}:$srcPort->${dstIp.hostAddress}:$dstPort"

        if (isSyn && !isAck) {
            // Guard against torrent swarm socket flood
            if (activeConnectingCount.get() >= MAX_CONCURRENT_CONNECTING || sessions.size >= MAX_SESSIONS) {
                val rstPacket = buildTcpPacket(
                    srcIp = dstIp,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seqNum = 0L,
                    ackNum = (seqNum + 1) and 0xFFFFFFFFL,
                    flags = 0x14, // RST | ACK
                    payload = ByteArray(0)
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
                lastActivity = System.currentTimeMillis()
            )
            sessions[sessionKey] = session

            // Reply with SYN-ACK immediately to complete handshake with client app (<1ms)
            val synAckPacket = buildTcpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seqNum = session.serverSeq.get(),
                ackNum = session.clientSeq.get(),
                flags = 0x12, // SYN | ACK
                payload = ByteArray(0)
            )
            session.serverSeq.incrementAndGet()
            writeTunPacket(synAckPacket)

            // Connect upstream socket asynchronously on isolated thread pool
            startUpstreamConnection(session)
            return
        }

        val session = sessions[sessionKey] ?: return
        session.lastActivity = System.currentTimeMillis()

        if (isRst) {
            closeSession(sessionKey)
            return
        }

        if (isFin) {
            // Client is closing connection
            val finAck = buildTcpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seqNum = session.serverSeq.get(),
                ackNum = (seqNum + 1) and 0xFFFFFFFFL,
                flags = 0x11, // FIN | ACK
                payload = ByteArray(0)
            )
            writeTunPacket(finAck)
            closeSession(sessionKey)
            return
        }

        if (isAck && payloadLen == 0) {
            session.clientAck = ackNum
        }

        if (payloadLen > 0) {
            // Data Payload received from App
            val payload = buffer.copyOfRange(payloadOffset, length)
            session.clientSeq.set((seqNum + payloadLen) and 0xFFFFFFFFL)

            // Send immediate ACK back to app so its TCP window stays open
            val ackPacket = buildTcpPacket(
                srcIp = dstIp,
                dstIp = srcIp,
                srcPort = dstPort,
                dstPort = srcPort,
                seqNum = session.serverSeq.get(),
                ackNum = session.clientSeq.get(),
                flags = 0x10, // ACK
                payload = ByteArray(0)
            )
            writeTunPacket(ackPacket)

            // Enqueue in sequential FIFO channel (guarantees perfect in-order delivery)
            session.sendQueue.trySend(payload)
        }
    }

    private fun startUpstreamConnection(session: TcpSession) {
        session.streamJob = scope.launch(tcpDispatcher) {
            activeConnectingCount.incrementAndGet()
            TrafficMonitor.onConnectionOpened()
            try {
                val socket = Socket().apply {
                    receiveBufferSize = 2097152 // 2MB Turbo Receive Buffer
                    sendBufferSize = 1048576    // 1MB Send Buffer
                    tcpNoDelay = true
                    keepAlive = true
                    soTimeout = 0               // Persistent keepalive
                    trafficClass = 0x08         // IPTOS_THROUGHPUT
                    setPerformancePreferences(0, 1, 2)
                }

                vpnService.protect(socket)
                socket.connect(InetSocketAddress(session.dstIp, session.dstPort), 2500)

                session.socket = socket
                val upstreamOut = socket.getOutputStream()
                session.upstreamOut = upstreamOut
                session.isConnected.set(true)
                activeConnectingCount.decrementAndGet()

                // Dedicated sequential sender loop (FIFO order) - launched AFTER socket is connected
                val senderJob = scope.launch(tcpDispatcher) {
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
                        closeSession(session.key)
                    }
                }

                // Downstream reader loop
                val input = socket.getInputStream()
                val readBuffer = ByteArrayPool.obtainStreamBuffer()
                try {
                    var bytesRead = input.read(readBuffer)
                    while (scope.isActive && bytesRead != -1 && session.isConnected.get() && isRunning.get()) {
                        if (bytesRead > 0) {
                            session.lastActivity = System.currentTimeMillis()
                            TrafficMonitor.recordRxBytes(bytesRead.toLong())

                            var offset = 0
                            val maxSegment = 1400 // Fits within 1500 MTU (20 IP + 20 TCP + 1400 payload = 1440 bytes)
                            while (offset < bytesRead) {
                                val chunkLen = minOf(bytesRead - offset, maxSegment)
                                val chunk = readBuffer.copyOfRange(offset, offset + chunkLen)
                                val dataPacket = buildTcpPacket(
                                    srcIp = session.dstIp,
                                    dstIp = session.srcIp,
                                    srcPort = session.dstPort,
                                    dstPort = session.srcPort,
                                    seqNum = session.serverSeq.get(),
                                    ackNum = session.clientSeq.get(),
                                    flags = 0x18, // PSH | ACK
                                    payload = chunk
                                )
                                session.serverSeq.addAndGet(chunkLen.toLong())
                                writeTunPacket(dataPacket)
                                offset += chunkLen
                            }
                        }
                        bytesRead = input.read(readBuffer)
                    }
                } finally {
                    ByteArrayPool.recycleStreamBuffer(readBuffer)
                }

                // Upstream EOF: send FIN-ACK to client
                if (session.isConnected.get()) {
                    val finAck = buildTcpPacket(
                        srcIp = session.dstIp,
                        dstIp = session.srcIp,
                        srcPort = session.dstPort,
                        dstPort = session.srcPort,
                        seqNum = session.serverSeq.get(),
                        ackNum = session.clientSeq.get(),
                        flags = 0x11, // FIN | ACK
                        payload = ByteArray(0)
                    )
                    writeTunPacket(finAck)
                }
            } catch (_: Exception) {
                val rstPacket = buildTcpPacket(
                    srcIp = session.dstIp,
                    dstIp = session.srcIp,
                    srcPort = session.dstPort,
                    dstPort = session.srcPort,
                    seqNum = session.serverSeq.get(),
                    ackNum = session.clientSeq.get(),
                    flags = 0x04, // RST
                    payload = ByteArray(0)
                )
                writeTunPacket(rstPacket)
            } finally {
                closeSession(session.key)
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

    private fun closeSession(key: String) {
        val session = sessions.remove(key) ?: return
        session.isConnected.set(false)
        session.sendQueue.close()
        session.streamJob?.cancel()
        try {
            session.socket?.close()
        } catch (_: Exception) {}
    }

    fun closeAll() {
        isRunning.set(false)
        scavengerJob?.cancel()
        sessions.keys().toList().forEach { closeSession(it) }
        try {
            tcpExecutor.shutdownNow()
        } catch (_: Exception) {}
    }

    /**
     * Synthesizes a 100% RFC-Compliant IPv4 + TCP packet with checksums.
     */
    private fun buildTcpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val isSynAck = (flags == 0x12)
        val tcpHeaderLen = if (isSynAck) 24 else 20
        val totalLength = ipHeaderLen + tcpHeaderLen + payload.size
        val packet = ByteArray(totalLength)

        // --- IPv4 Header (20 bytes) ---
        packet[0] = 0x45.toByte() // IPv4, IHL = 5
        packet[1] = 0x00.toByte() // TOS
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte() // ID
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 6.toByte()    // Protocol: TCP (6)
        packet[10] = 0x00.toByte() // Checksum placeholder
        packet[11] = 0x00.toByte()

        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)

        // IP Checksum
        val ipChecksum = computeIpChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ipChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // --- TCP Header (20 or 24 bytes) ---
        val tcpOffset = ipHeaderLen
        packet[tcpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[tcpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 3] = (dstPort and 0xFF).toByte()

        // Sequence Number (4 bytes)
        packet[tcpOffset + 4] = ((seqNum shr 24) and 0xFF).toByte()
        packet[tcpOffset + 5] = ((seqNum shr 16) and 0xFF).toByte()
        packet[tcpOffset + 6] = ((seqNum shr 8) and 0xFF).toByte()
        packet[tcpOffset + 7] = (seqNum and 0xFF).toByte()

        // Acknowledgment Number (4 bytes)
        packet[tcpOffset + 8] = ((ackNum shr 24) and 0xFF).toByte()
        packet[tcpOffset + 9] = ((ackNum shr 16) and 0xFF).toByte()
        packet[tcpOffset + 10] = ((ackNum shr 8) and 0xFF).toByte()
        packet[tcpOffset + 11] = (ackNum and 0xFF).toByte()

        // Data Offset (5 or 6 words) & Reserved
        packet[tcpOffset + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
        // Flags
        packet[tcpOffset + 13] = flags.toByte()

        // Window Size (65535 bytes max standard window)
        packet[tcpOffset + 14] = 0xFF.toByte()
        packet[tcpOffset + 15] = 0xFF.toByte()

        // Checksum placeholder
        packet[tcpOffset + 16] = 0x00.toByte()
        packet[tcpOffset + 17] = 0x00.toByte()

        // Urgent Pointer
        packet[tcpOffset + 18] = 0x00.toByte()
        packet[tcpOffset + 19] = 0x00.toByte()

        // TCP Options: MSS 1400 (Kind 2, Len 4, Value 1400 [0x05, 0x78]) for SYN-ACK
        if (isSynAck) {
            packet[tcpOffset + 20] = 0x02.toByte()
            packet[tcpOffset + 21] = 0x04.toByte()
            packet[tcpOffset + 22] = 0x05.toByte()
            packet[tcpOffset + 23] = 0x78.toByte()
        }

        // --- Payload ---
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, tcpOffset + tcpHeaderLen, payload.size)
        }

        // Compute TCP Checksum with IPv4 Pseudo-Header
        val tcpLen = tcpHeaderLen + payload.size
        val tcpChecksum = computeTcpChecksum(packet, tcpOffset, tcpLen, srcIp.address, dstIp.address)
        packet[tcpOffset + 16] = ((tcpChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[tcpOffset + 17] = (tcpChecksum.toInt() and 0xFF).toByte()

        return packet
    }

    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        for (i in offset until offset + length step 2) {
            val word = if (i + 1 < offset + length) {
                ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            } else {
                ((data[i].toInt() and 0xFF) shl 8)
            }
            sum += word
        }
        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }

    private fun computeTcpChecksum(
        packet: ByteArray,
        tcpOffset: Int,
        tcpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Short {
        var sum = 0

        // Pseudo Header
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 6 // Protocol TCP
        sum += tcpLen

        // TCP Header and Payload
        for (i in tcpOffset until tcpOffset + tcpLen step 2) {
            val b1 = packet[i].toInt() and 0xFF
            val b2 = if (i + 1 < tcpOffset + tcpLen) packet[i + 1].toInt() and 0xFF else 0
            sum += (b1 shl 8) or b2
        }

        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }
}