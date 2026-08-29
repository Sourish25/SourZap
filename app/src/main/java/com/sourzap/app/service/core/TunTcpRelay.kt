package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.service.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-Throughput Rootless Userspace TCP Stack for Android VpnService.
 * Seamlessly accepts TCP connections from all Android apps and browsers,
 * applies Zapret DPI evasion upstream, and streams full-duplex responses back to the TUN interface.
 */
class TunTcpRelay(
    private val vpnService: VpnService,
    private val vpnOutput: FileOutputStream,
    private val scope: CoroutineScope
) {
    private val sessions = ConcurrentHashMap<String, TcpSession>()

    data class TcpSession(
        val key: String,
        val srcIp: InetAddress,
        val dstIp: InetAddress,
        val srcPort: Int,
        val dstPort: Int,
        val clientSeq: AtomicLong,
        val serverSeq: AtomicLong,
        var clientAck: Long = 0L,
        var isConnected: AtomicBoolean = AtomicBoolean(false),
        var isHandshakeDesynced: AtomicBoolean = AtomicBoolean(false),
        var socket: Socket? = null,
        var upstreamOut: OutputStream? = null,
        var streamJob: Job? = null
    )

    fun handleTcpPacket(
        buffer: ByteArray,
        length: Int,
        ipHeaderLen: Int,
        srcIp: InetAddress,
        dstIp: InetAddress
    ) {
        if (length < ipHeaderLen + 20) return

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
            // 1. New TCP Connection Initiation (SYN)
            val session = TcpSession(
                key = sessionKey,
                srcIp = srcIp,
                dstIp = dstIp,
                srcPort = srcPort,
                dstPort = dstPort,
                clientSeq = AtomicLong((seqNum + 1) and 0xFFFFFFFFL),
                serverSeq = AtomicLong(1000000L)
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

            // Connect upstream socket asynchronously
            startUpstreamConnection(session)
            return
        }

        val session = sessions[sessionKey] ?: return

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

            // Forward to upstream socket
            scope.launch(Dispatchers.IO) {
                try {
                    // Wait up to 5000ms if socket is still connecting
                    var retries = 0
                    while (!session.isConnected.get() && retries < 200 && scope.isActive) {
                        kotlinx.coroutines.delay(25)
                        retries++
                    }

                    val socket = session.socket
                    val out = session.upstreamOut
                    if (socket == null || out == null || !session.isConnected.get()) {
                        closeSession(sessionKey)
                        return@launch
                    }

                    if (!session.isHandshakeDesynced.getAndSet(true)) {
                        // Apply Zapret Desync on Initial Handshake Payload
                        val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value
                        var appliedTechnique = "DIRECT"

                        val sniResult = TlsParser.parseClientHello(payload, payload.size)
                        val logDomain = sniResult.hostname ?: dstIp.hostAddress ?: "Stream"

                        DpiEngine.desyncAndSend(
                            socket = socket,
                            outputStream = out,
                            payload = payload,
                            length = payload.size,
                            strategy = strategy,
                            onTechniqueApplied = { appliedTechnique = it }
                        )

                        TrafficMonitor.addConnectionLog(
                            ConnectionLog(
                                domain = logDomain,
                                port = dstPort,
                                protocol = if (dstPort == 443) "TLS" else "HTTP",
                                technique = appliedTechnique,
                                bytesTransferred = payload.size.toLong()
                            )
                        )
                    } else {
                        // Subsequent stream data
                        out.write(payload)
                        out.flush()
                    }
                } catch (_: Exception) {
                    closeSession(sessionKey)
                }
            }
        }
    }

    private fun startUpstreamConnection(session: TcpSession) {
        session.streamJob = scope.launch(Dispatchers.IO) {
            TrafficMonitor.onConnectionOpened()
            try {
                val socket = Socket().apply {
                    receiveBufferSize = 1048576 // 1MB Turbo Receive Buffer for 4K/8K Video
                    sendBufferSize = 524288     // 512KB Send Buffer
                    tcpNoDelay = true
                    keepAlive = true
                    trafficClass = 0x08         // IPTOS_THROUGHPUT
                    setPerformancePreferences(0, 1, 2)
                }

                vpnService.protect(socket)
                socket.connect(InetSocketAddress(session.dstIp, session.dstPort), 5000)

                session.socket = socket
                session.upstreamOut = socket.getOutputStream()
                session.isConnected.set(true)

                val input = socket.getInputStream()
                val readBuffer = ByteArray(1400) // MTU size chunks

                var bytesRead = input.read(readBuffer)
                while (scope.isActive && bytesRead != -1 && session.isConnected.get()) {
                    if (bytesRead > 0) {
                        TrafficMonitor.recordRxBytes(bytesRead.toLong())

                        val responseData = if (bytesRead == readBuffer.size) readBuffer else readBuffer.copyOfRange(0, bytesRead)
                        val dataPacket = buildTcpPacket(
                            srcIp = session.dstIp,
                            dstIp = session.srcIp,
                            srcPort = session.dstPort,
                            dstPort = session.srcPort,
                            seqNum = session.serverSeq.get(),
                            ackNum = session.clientSeq.get(),
                            flags = 0x18, // PSH | ACK
                            payload = responseData
                        )
                        session.serverSeq.addAndGet(bytesRead.toLong())
                        writeTunPacket(dataPacket)
                    }
                    bytesRead = input.read(readBuffer)
                }

                // Upstream reached EOF: send FIN-ACK to client app
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
                // Connection failed: send RST to client app so it retries cleanly
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
        session.streamJob?.cancel()
        try {
            session.socket?.close()
        } catch (_: Exception) {}
    }

    fun closeAll() {
        sessions.keys().toList().forEach { closeSession(it) }
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
        val tcpHeaderLen = 20
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

        // --- TCP Header (20 bytes) ---
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

        // Data Offset (5 words = 20 bytes) & Reserved
        packet[tcpOffset + 12] = (5 shl 4).toByte()
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
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
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