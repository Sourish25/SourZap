package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.service.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TunUdpRelay(
    private val vpnService: VpnService,
    private val vpnOutput: FileOutputStream,
    private val scope: CoroutineScope
) {
    private val POOL_SIZE = 8
    private val sockets = ArrayList<DatagramSocket>(POOL_SIZE)
    private val isRunning = AtomicBoolean(true)

    private val udpExecutor = Executors.newFixedThreadPool(POOL_SIZE + 1) { r ->
        Thread(r, "SourZap-TunUdpWorker").apply { isDaemon = true }
    }
    private val udpDispatcher = udpExecutor.asCoroutineDispatcher()

    private data class ClientMapping(
        val clientIp: InetAddress,
        val clientPort: Int,
        var lastSeen: Long
    )

    // Key: "RemoteHost:RemotePort#SocketIndex" -> ClientMapping
    private val natTable = ConcurrentHashMap<String, ClientMapping>()
    private var cleanerJob: Job? = null

    init {
        for (i in 0 until POOL_SIZE) {
            try {
                val s = DatagramSocket()
                vpnService.protect(s)
                s.receiveBufferSize = 2097152 // 2MB UDP Receive Buffer
                s.sendBufferSize = 1048576    // 1MB UDP Send Buffer
                s.soTimeout = 0 // Blocking receive in IO coroutine
                sockets.add(s)

                val socketIndex = i
                scope.launch(udpDispatcher) {
                    runReceiverLoop(s, socketIndex)
                }
            } catch (_: Exception) {}
        }

        // Background NAT table scavenger
        cleanerJob = scope.launch(udpDispatcher) {
            while (isActive && isRunning.get()) {
                delay(30000)
                val now = System.currentTimeMillis()
                val iterator = natTable.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastSeen > 60000) { // 60s idle NAT entry
                        iterator.remove()
                    }
                }
            }
        }
    }

    fun handleUdpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ) {
        if (sockets.isEmpty() || !isRunning.get()) return

        val socketIndex = (srcPort and 0x7FFFFFFF) % sockets.size
        val socket = sockets[socketIndex]

        val mapping = ClientMapping(srcIp, srcPort, System.currentTimeMillis())
        val natKeyExact = "${dstIp.hostAddress}:$dstPort#$socketIndex"
        val natKeyHost = "${dstIp.hostAddress}#$socketIndex"

        natTable[natKeyExact] = mapping
        natTable[natKeyHost] = mapping

        try {
            val sendPacket = DatagramPacket(payload, payload.size, dstIp, dstPort)
            socket.send(sendPacket)
            TrafficMonitor.recordTxBytes(payload.size.toLong())
        } catch (_: Exception) {}
    }

    private fun runReceiverLoop(socket: DatagramSocket, socketIndex: Int) {
        val recvBuf = ByteArray(65535)
        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

        while (scope.isActive && isRunning.get()) {
            try {
                // Crucial: Must reset length to full buffer size before every receive call.
                // Otherwise Java DatagramSocket permanently mutates packet length to previous message size,
                // causing tracker announce responses and DHT responses to be truncated or dropped!
                recvPacket.length = recvBuf.size
                socket.receive(recvPacket)
                val len = recvPacket.length
                val remoteAddress = recvPacket.address ?: continue
                val remotePort = recvPacket.port

                if (len > 0) {
                    val natKeyExact = "${remoteAddress.hostAddress}:$remotePort#$socketIndex"
                    val natKeyHost = "${remoteAddress.hostAddress}#$socketIndex"

                    val client = natTable[natKeyExact]
                        ?: natTable[natKeyHost]
                        ?: natTable.entries.firstOrNull { it.key.startsWith("${remoteAddress.hostAddress}:") }?.value
                        ?: natTable.values.maxByOrNull { it.lastSeen }

                    if (client != null) {
                        client.lastSeen = System.currentTimeMillis()
                        TrafficMonitor.recordRxBytes(len.toLong())

                        val responseData = if (len == recvBuf.size) recvBuf else recvBuf.copyOfRange(0, len)
                        val replyIpPacket = buildUdpIpPacket(
                            srcIp = remoteAddress,
                            dstIp = client.clientIp,
                            srcPort = remotePort,
                            dstPort = client.clientPort,
                            payload = responseData
                        )

                        synchronized(vpnOutput) {
                            vpnOutput.write(replyIpPacket)
                            vpnOutput.flush()
                        }
                    }
                }
            } catch (_: Exception) {
                if (!isRunning.get()) break
            }
        }
    }

    private fun buildUdpIpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)

        // IPv4 Header (20 bytes)
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 17.toByte()   // UDP (17)

        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)

        val ipChecksum = computeIpChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // UDP Header (8 bytes)
        val udpLen = 8 + payload.size
        val udpOffset = 20
        packet[udpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        packet[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
        packet[udpOffset + 5] = (udpLen and 0xFF).toByte()
        packet[udpOffset + 6] = 0x00.toByte()
        packet[udpOffset + 7] = 0x00.toByte()

        System.arraycopy(payload, 0, packet, 28, payload.size)

        // Compute RFC 768 UDP Checksum with IPv4 Pseudo-Header
        val udpChecksum = computeUdpChecksum(packet, udpOffset, udpLen, srcIp.address, dstIp.address)
        packet[udpOffset + 6] = ((udpChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[udpOffset + 7] = (udpChecksum.toInt() and 0xFF).toByte()

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

    private fun computeUdpChecksum(
        packet: ByteArray,
        udpOffset: Int,
        udpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Short {
        var sum = 0

        // Pseudo Header
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 17 // Protocol UDP
        sum += udpLen

        // UDP Header and Payload
        for (i in udpOffset until udpOffset + udpLen step 2) {
            val b1 = packet[i].toInt() and 0xFF
            val b2 = if (i + 1 < udpOffset + udpLen) packet[i + 1].toInt() and 0xFF else 0
            sum += (b1 shl 8) or b2
        }

        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv() and 0xFFFF).toShort()
        return if (checksum == 0.toShort()) 0xFFFF.toShort() else checksum
    }

    fun closeAll() {
        isRunning.set(false)
        cleanerJob?.cancel()
        sockets.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        sockets.clear()
        natTable.clear()
        try {
            udpExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}