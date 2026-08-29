package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.service.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * High-Speed UDP Relay for Android VpnService.
 * Handles WhatsApp Voice/Video Calls, WebRTC, STUN/TURN, Telegram MTProto, and Gaming UDP packets.
 */
class TunUdpRelay(
    private val vpnService: VpnService,
    private val vpnOutput: FileOutputStream,
    private val scope: CoroutineScope
) {
    private val udpSockets = ConcurrentHashMap<String, DatagramSocket>()

    fun handleUdpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ) {
        val key = "${srcIp.hostAddress}:$srcPort->${dstIp.hostAddress}:$dstPort"

        val socket = udpSockets.computeIfAbsent(key) {
            val s = DatagramSocket()
            vpnService.protect(s)
            s.soTimeout = 60000 // 60s idle timeout for active VoIP/RTC streams

            scope.launch(Dispatchers.IO) {
                val recvBuf = ByteArray(2048)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

                while (scope.isActive) {
                    try {
                        s.receive(recvPacket)
                        val len = recvPacket.length
                        if (len > 0) {
                            TrafficMonitor.recordRxBytes(len.toLong())
                            val responseData = recvBuf.copyOfRange(0, len)
                            val replyIpPacket = buildUdpIpPacket(
                                srcIp = dstIp,
                                dstIp = srcIp,
                                srcPort = dstPort,
                                dstPort = srcPort,
                                payload = responseData
                            )
                            synchronized(vpnOutput) {
                                vpnOutput.write(replyIpPacket)
                                vpnOutput.flush()
                            }
                        }
                    } catch (_: Exception) {
                        break
                    }
                }
                udpSockets.remove(key)
                try { s.close() } catch (_: Exception) {}
            }
            s
        }

        scope.launch(Dispatchers.IO) {
            try {
                val sendPacket = DatagramPacket(payload, payload.size, dstIp, dstPort)
                socket.send(sendPacket)
                TrafficMonitor.recordTxBytes(payload.size.toLong())
            } catch (_: Exception) {
                udpSockets.remove(key)
                try { socket.close() } catch (_: Exception) {}
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

        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()
        packet[9] = 17.toByte() // UDP

        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)

        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum.toInt() and 0xFF).toByte()

        val udpLen = 8 + payload.size
        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLen shr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0x00.toByte()
        packet[27] = 0x00.toByte()

        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Short {
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

    fun closeAll() {
        udpSockets.values.forEach { try { it.close() } catch (_: Exception) {} }
        udpSockets.clear()
    }
}