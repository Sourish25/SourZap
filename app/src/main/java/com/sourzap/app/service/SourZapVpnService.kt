package com.sourzap.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.sourzap.app.MainActivity
import com.sourzap.app.R
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.service.core.DohResolver
import com.sourzap.app.service.core.DpiEngine
import com.sourzap.app.service.core.TlsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class SourZapVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var notificationJob: Job? = null
    private var isRunning = false

    private val activeStreams = ConcurrentHashMap<String, Boolean>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        isRunning = true
        TrafficMonitor.startMonitoring()

        startForeground(NOTIFICATION_ID, buildNotification("SourZap Turbo Active", "DPI Bypass Engine Running"))

        serviceScope.launch {
            try {
                val settingsRepo = SourZapApp.instance.settingsRepository
                val builder = Builder().apply {
                    setSession("SourZap Turbo DPI")
                    addAddress("10.0.0.2", 24)
                    addRoute("0.0.0.0", 0)
                    addDnsServer("10.0.0.1")
                    setMtu(1500)
                    setBlocking(true)

                    // Per-App Split Tunneling
                    val disallowed = settingsRepo.disallowedPackages.value
                    disallowed.forEach { pkg ->
                        try {
                            addDisallowedApplication(pkg)
                        } catch (_: Exception) {}
                    }
                }

                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    stopVpn()
                    return@launch
                }

                startNotificationUpdates()
                runPacketLoop(vpnInterface!!)
            } catch (e: Exception) {
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        TrafficMonitor.stopMonitoring()
        notificationJob?.cancel()
        serviceScope.cancel()
        activeStreams.clear()

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runPacketLoop(vpnPfd: ParcelFileDescriptor) {
        val inputStream = FileInputStream(vpnPfd.fileDescriptor)
        val outputStream = FileOutputStream(vpnPfd.fileDescriptor)
        val packetBuffer = ByteArrayPool.obtainPacketBuffer()

        try {
            while (serviceScope.isActive && isRunning) {
                try {
                    val length = inputStream.read(packetBuffer)
                    if (length > 0) {
                        processPacket(packetBuffer, length, outputStream)
                    }
                } catch (e: Exception) {
                    if (!isRunning) break
                    delay(10)
                }
            }
        } finally {
            ByteArrayPool.recyclePacketBuffer(packetBuffer)
        }
    }

    private fun processPacket(buffer: ByteArray, length: Int, vpnOutput: FileOutputStream) {
        if (length < 20) return
        val version = (buffer[0].toInt() shr 4) and 0x0F
        if (version != 4) return

        val ipHeaderLen = (buffer[0].toInt() and 0x0F) * 4
        val protocol = buffer[9].toInt() and 0xFF

        val srcIp = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
        val dstIp = InetAddress.getByAddress(buffer.copyOfRange(16, 20))

        if (protocol == 6) { // TCP
            if (length >= ipHeaderLen + 20) {
                val srcPort = ((buffer[ipHeaderLen].toInt() and 0xFF) shl 8) or (buffer[ipHeaderLen + 1].toInt() and 0xFF)
                val dstPort = ((buffer[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (buffer[ipHeaderLen + 3].toInt() and 0xFF)
                val tcpHeaderLen = ((buffer[ipHeaderLen + 12].toInt() shr 4) and 0x0F) * 4
                val payloadOffset = ipHeaderLen + tcpHeaderLen
                val payloadLen = length - payloadOffset

                TrafficMonitor.recordTxBytes(length.toLong())

                if (payloadLen > 0 && (dstPort == 443 || dstPort == 80)) {
                    val payload = buffer.copyOfRange(payloadOffset, length)
                    val connectionKey = "${srcIp.hostAddress}:$srcPort->${dstIp.hostAddress}:$dstPort"

                    if (!activeStreams.containsKey(connectionKey)) {
                        activeStreams[connectionKey] = true
                        handleTurboTcpStream(connectionKey, dstIp.hostAddress ?: "", dstPort, payload)
                    }
                }
            }
        } else if (protocol == 17) { // UDP
            if (length >= ipHeaderLen + 8) {
                val srcPort = ((buffer[ipHeaderLen].toInt() and 0xFF) shl 8) or (buffer[ipHeaderLen + 1].toInt() and 0xFF)
                val dstPort = ((buffer[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or (buffer[ipHeaderLen + 3].toInt() and 0xFF)
                val udpPayloadOffset = ipHeaderLen + 8
                val udpPayloadLen = length - udpPayloadOffset

                TrafficMonitor.recordTxBytes(length.toLong())
                val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value

                if (dstPort == 53 && udpPayloadLen > 0) { // DNS Query
                    val queryBytes = buffer.copyOfRange(udpPayloadOffset, length)
                    serviceScope.launch {
                        val responseWire = DohResolver.resolveWireQuery(queryBytes, strategy.dohProvider)
                        if (responseWire != null) {
                            TrafficMonitor.recordRxBytes(responseWire.size.toLong())

                            // Synthesize IPv4 UDP DNS response packet and write back to TUN interface
                            val replyPacket = buildUdpIpPacket(
                                srcIp = dstIp,
                                dstIp = srcIp,
                                srcPort = dstPort,
                                dstPort = srcPort,
                                payload = responseWire
                            )

                            try {
                                synchronized(vpnOutput) {
                                    vpnOutput.write(replyPacket)
                                    vpnOutput.flush()
                                }
                            } catch (_: Exception) {}

                            TrafficMonitor.addConnectionLog(
                                ConnectionLog(
                                    domain = "DNS Resolution (DoH)",
                                    port = 53,
                                    protocol = "UDP",
                                    technique = "DOH_WIRE",
                                    bytesTransferred = responseWire.size.toLong()
                                )
                            )
                        }
                    }
                } else if (dstPort == 443 && strategy.blockQuic) {
                    // Instantly drop QUIC packet to force browser/YouTube/Discord into TCP mode
                    TrafficMonitor.addConnectionLog(
                        ConnectionLog(
                            domain = dstIp.hostAddress ?: "QUIC Endpoint",
                            port = 443,
                            protocol = "QUIC",
                            technique = "BLOCK_QUIC",
                            bytesTransferred = length.toLong()
                        )
                    )
                }
            }
        }
    }

    /**
     * Synthesizes a standard IPv4 UDP packet
     */
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
        packet[0] = 0x45.toByte() // IPv4, Header Length = 5 words (20 bytes)
        packet[1] = 0x00.toByte() // TOS
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte() // Identification
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Flags: Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 17.toByte()   // Protocol: UDP (17)
        packet[10] = 0x00.toByte() // Checksum placeholder
        packet[11] = 0x00.toByte()

        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)

        // Compute IP Header Checksum
        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // UDP Header (8 bytes)
        val udpLen = 8 + payload.size
        packet[20] = ((srcPort shr 8) and 0xFF).toByte()
        packet[21] = (srcPort and 0xFF).toByte()
        packet[22] = ((dstPort shr 8) and 0xFF).toByte()
        packet[23] = (dstPort and 0xFF).toByte()
        packet[24] = ((udpLen shr 8) and 0xFF).toByte()
        packet[25] = (udpLen and 0xFF).toByte()
        packet[26] = 0x00.toByte() // UDP Checksum optional in IPv4
        packet[27] = 0x00.toByte()

        // Payload
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

    private fun handleTurboTcpStream(connectionKey: String, dstHost: String, dstPort: Int, initialPayload: ByteArray) {
        val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value

        serviceScope.launch {
            TrafficMonitor.onConnectionOpened()
            var socket: Socket? = null
            val streamBuffer = ByteArrayPool.obtainStreamBuffer()

            try {
                socket = Socket().apply {
                    receiveBufferSize = 524288 // 512 KB Receive Buffer
                    sendBufferSize = 524288    // 512 KB Send Buffer
                    tcpNoDelay = true          // Disable Nagle algorithm
                    keepAlive = true
                    trafficClass = 0x08        // IPTOS_THROUGHPUT
                    setPerformancePreferences(0, 1, 2)
                }

                protect(socket)
                socket.connect(InetSocketAddress(dstHost, dstPort), 3500)

                val out = socket.getOutputStream()
                var appliedTechnique = "DIRECT"

                val sniResult = TlsParser.parseClientHello(initialPayload, initialPayload.size)
                val logDomain = sniResult.hostname ?: dstHost

                // Apply Zapret Desync on Initial Handshake
                DpiEngine.desyncAndSend(
                    socket = socket,
                    outputStream = out,
                    payload = initialPayload,
                    length = initialPayload.size,
                    strategy = strategy,
                    onTechniqueApplied = { appliedTechnique = it }
                )

                TrafficMonitor.addConnectionLog(
                    ConnectionLog(
                        domain = logDomain,
                        port = dstPort,
                        protocol = if (dstPort == 443) "TLS" else "HTTP",
                        technique = appliedTechnique,
                        bytesTransferred = initialPayload.size.toLong()
                    )
                )

                val input = socket.getInputStream()
                var bytesRead = input.read(streamBuffer)
                var accumulatedBytes = 0L

                while (isRunning && bytesRead != -1) {
                    accumulatedBytes += bytesRead

                    if (accumulatedBytes >= 65536) {
                        TrafficMonitor.recordRxBytes(accumulatedBytes)
                        accumulatedBytes = 0L
                    }

                    bytesRead = input.read(streamBuffer)
                }

                if (accumulatedBytes > 0) {
                    TrafficMonitor.recordRxBytes(accumulatedBytes)
                }
            } catch (_: Exception) {
            } finally {
                activeStreams.remove(connectionKey)
                ByteArrayPool.recycleStreamBuffer(streamBuffer)
                TrafficMonitor.onConnectionClosed()
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startNotificationUpdates() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            while (isActive && isRunning) {
                delay(2000)
                val stats = TrafficMonitor.stats.value
                val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value
                val notification = buildNotification(
                    title = "SourZap: ${strategy.name}",
                    content = "${stats.formattedDownloadSpeed()} DL • ${stats.formattedUploadSpeed()} UL"
                )
                val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SourZapVpnService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, getString(R.string.vpn_channel_id))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 9001
        const val ACTION_START = "com.sourzap.app.START_VPN"
        const val ACTION_STOP = "com.sourzap.app.STOP_VPN"
    }
}