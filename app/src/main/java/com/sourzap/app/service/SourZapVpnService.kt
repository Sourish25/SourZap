package com.sourzap.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.sourzap.app.MainActivity
import com.sourzap.app.R
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.service.core.DohResolver
import com.sourzap.app.service.core.LocalDpiProxyServer
import com.sourzap.app.service.core.TunTcpRelay
import com.sourzap.app.service.core.TunUdpRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress

class SourZapVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var serviceJob = Job()
    private var serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var notificationJob: Job? = null
    private var isRunning = false

    private var proxyServer: LocalDpiProxyServer? = null
    private var tcpRelay: TunTcpRelay? = null
    private var udpRelay: TunUdpRelay? = null

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
        if (serviceJob.isCancelled || serviceJob.isCompleted) {
            serviceJob = Job()
            serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
        }

        isRunning = true
        TrafficMonitor.startMonitoring()

        val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value
        startForeground(NOTIFICATION_ID, buildNotification("SourZap: ${strategy.name}", "⚡ Smart DPI Bypass Engine Active"))

        // Initialize DohResolver with protected socket factory
        DohResolver.init(this)

        serviceScope.launch {
            try {
                // 1. Start Local Zapret Transparent Proxy Server on 127.0.0.1
                val proxy = LocalDpiProxyServer(this@SourZapVpnService, serviceScope)
                val proxyPort = proxy.start()
                proxyServer = proxy

                val settingsRepo = SourZapApp.instance.settingsRepository
                val builder = Builder().apply {
                    setSession("SourZap Turbo DPI")
                    addAddress("10.0.0.2", 24)
                    addRoute("0.0.0.0", 0)
                    addDnsServer("1.1.1.1")
                    addDnsServer("8.8.8.8")
                    setMtu(1500)
                    setBlocking(true)

                    // Intercept IPv6 to prevent ISP DPI leaks over 5G/Wi-Fi
                    try {
                        addAddress("fd00::1", 128)
                        addRoute("::", 0)
                    } catch (_: Exception) {}

                    // Set Local Direct Proxy on Android API 21+ for instant browser & app traffic interception
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", proxyPort))
                    }

                    // Exclude SourZap's own app to prevent internal download / updater recursive loops
                    try {
                        addDisallowedApplication(packageName)
                    } catch (_: Exception) {}

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
        proxyServer?.stop()
        tcpRelay?.closeAll()
        udpRelay?.closeAll()
        serviceJob.cancel()

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

        tcpRelay = TunTcpRelay(this, outputStream, serviceScope)
        udpRelay = TunUdpRelay(this, outputStream, serviceScope)

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
        if (version != 4) {
            // IPv6 packets are intercepted and gracefully suppressed so Android RFC 6555 Happy Eyeballs
            // immediately falls back to ultra-fast desynced IPv4 in 0ms without ISP leaks
            return
        }

        val ipHeaderLen = (buffer[0].toInt() and 0x0F) * 4
        val protocol = buffer[9].toInt() and 0xFF

        val srcIp = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
        val dstIp = InetAddress.getByAddress(buffer.copyOfRange(16, 20))

        if (protocol == 6) { // TCP
            TrafficMonitor.recordTxBytes(length.toLong())
            tcpRelay?.handleTcpPacket(buffer, length, ipHeaderLen, srcIp, dstIp)
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
                                    technique = "RAM_CACHED_DOH",
                                    bytesTransferred = responseWire.size.toLong()
                                )
                            )
                        }
                    }
                } else if (dstPort == 443 && strategy.blockQuic) {
                    // Instantly reject QUIC with ICMP Port Unreachable (RFC 792 Type 3 Code 3).
                    // This causes Google Chrome & YouTube to immediately fallback to fast TCP in 0ms with zero timeout delay!
                    val icmpPacket = buildIcmpPortUnreachablePacket(
                        originalBuffer = buffer,
                        originalLength = length,
                        ipHeaderLen = ipHeaderLen,
                        srcIp = srcIp,
                        dstIp = dstIp
                    )
                    try {
                        synchronized(vpnOutput) {
                            vpnOutput.write(icmpPacket)
                            vpnOutput.flush()
                        }
                    } catch (_: Exception) {}

                    TrafficMonitor.addConnectionLog(
                        ConnectionLog(
                            domain = dstIp.hostAddress ?: "QUIC Endpoint",
                            port = 443,
                            protocol = "QUIC",
                            technique = "ICMP_FAST_REJECT",
                            bytesTransferred = length.toLong()
                        )
                    )
                } else if (udpPayloadLen > 0) {
                    // Forward general UDP traffic (BitTorrent DHT/uTP, WhatsApp Calling, WebRTC, STUN/TURN, Telegram)
                    val udpPayload = buffer.copyOfRange(udpPayloadOffset, length)
                    udpRelay?.handleUdpPacket(srcIp, dstIp, srcPort, dstPort, udpPayload)
                }
            }
        }
    }

    /**
     * Synthesizes an RFC 792 compliant ICMP Destination Unreachable (Port Unreachable: Type 3, Code 3) packet.
     */
    private fun buildIcmpPortUnreachablePacket(
        originalBuffer: ByteArray,
        originalLength: Int,
        ipHeaderLen: Int,
        srcIp: InetAddress,
        dstIp: InetAddress
    ): ByteArray {
        val includedOriginalLen = (ipHeaderLen + 8).coerceAtMost(originalLength)
        val ipTotalLen = 20 + 8 + includedOriginalLen
        val packet = ByteArray(ipTotalLen)

        // 1. IPv4 Header (20 bytes)
        packet[0] = 0x45.toByte() // IPv4, IHL = 5
        packet[1] = 0x00.toByte() // TOS
        packet[2] = ((ipTotalLen shr 8) and 0xFF).toByte()
        packet[3] = (ipTotalLen and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 1.toByte()    // Protocol = 1 (ICMP)
        packet[10] = 0x00.toByte()
        packet[11] = 0x00.toByte()

        System.arraycopy(dstIp.address, 0, packet, 12, 4)
        System.arraycopy(srcIp.address, 0, packet, 16, 4)

        val ipChecksum = computeChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // 2. ICMP Header (8 bytes)
        packet[20] = 3.toByte() // Type 3: Destination Unreachable
        packet[21] = 3.toByte() // Code 3: Port Unreachable
        packet[22] = 0x00.toByte()
        packet[23] = 0x00.toByte()
        packet[24] = 0x00.toByte() // 4 unused bytes
        packet[25] = 0x00.toByte()
        packet[26] = 0x00.toByte()
        packet[27] = 0x00.toByte()

        // 3. ICMP Data (Original IP Header + first 8 bytes of original UDP payload)
        System.arraycopy(originalBuffer, 0, packet, 28, includedOriginalLen)

        // ICMP Checksum
        val icmpLen = 8 + includedOriginalLen
        val icmpChecksum = computeChecksum(packet, 20, icmpLen)
        packet[22] = ((icmpChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[23] = (icmpChecksum.toInt() and 0xFF).toByte()

        return packet
    }

    /**
     * Synthesizes an RFC 1035 compliant IPv4 UDP packet with IP header checksum.
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
        val udpOffset = 20
        packet[udpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        packet[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
        packet[udpOffset + 5] = (udpLen and 0xFF).toByte()
        packet[udpOffset + 6] = 0x00.toByte()
        packet[udpOffset + 7] = 0x00.toByte()

        // Payload
        System.arraycopy(payload, 0, packet, 28, payload.size)

        // Compute RFC 768 UDP Checksum with IPv4 Pseudo-Header
        val udpChecksum = computeUdpChecksum(packet, udpOffset, udpLen, srcIp.address, dstIp.address)
        packet[udpOffset + 6] = ((udpChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[udpOffset + 7] = (udpChecksum.toInt() and 0xFF).toByte()

        return packet
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

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Short {
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