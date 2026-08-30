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
import com.sourzap.app.service.core.LocalDpiProxyServer
import com.sourzap.app.service.core.PacketParser
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

        startForeground(NOTIFICATION_ID, buildNotification("SourZap", "Smart DPI Bypass Engine Active"))

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

        if (version == 6) {
            // IPv6 packets: Synthesize RFC 4443 ICMPv6 Destination Unreachable (Address Unreachable)
            // so Android RFC 6555 Happy Eyeballs immediately falls back to IPv4 in 0ms without waiting for timeouts
            val ipv6Header = PacketParser.parseIpv6Header(buffer, length)
            if (ipv6Header != null) {
                val icmpv6Packet = PacketParser.buildIcmpv6AddressUnreachablePacket(
                    originalBuffer = buffer,
                    originalLength = length,
                    srcIp = ipv6Header.srcIp,
                    dstIp = ipv6Header.dstIp
                )
                try {
                    synchronized(vpnOutput) {
                        vpnOutput.write(icmpv6Packet)
                        vpnOutput.flush()
                    }
                } catch (_: Exception) {}
            }
            return
        }

        val ipHeader = PacketParser.parseIpv4Header(buffer, length) ?: return
        val protocol = ipHeader.protocol
        val ipHeaderLen = ipHeader.headerLength
        val srcIp = ipHeader.srcIp
        val dstIp = ipHeader.dstIp

        if (protocol == 6) { // TCP
            TrafficMonitor.recordTxBytes(length.toLong())
            tcpRelay?.handleTcpPacket(buffer, length, ipHeaderLen, srcIp, dstIp)
        } else if (protocol == 17) { // UDP
            val udpHeader = PacketParser.parseUdpHeader(buffer, ipHeaderLen, length) ?: return
            val srcPort = udpHeader.srcPort
            val dstPort = udpHeader.dstPort
            val udpPayloadOffset = udpHeader.payloadOffset
            val udpPayloadLen = udpHeader.payloadLength

            TrafficMonitor.recordTxBytes(length.toLong())
            val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value

            if (dstPort == 53 && udpPayloadLen > 0) { // DNS Query
                val queryBytes = buffer.copyOfRange(udpPayloadOffset, udpPayloadOffset + udpPayloadLen)
                serviceScope.launch {
                    val responseWire = DohResolver.resolveWireQuery(queryBytes, strategy.dohProvider)
                    if (responseWire != null) {
                        TrafficMonitor.recordRxBytes(responseWire.size.toLong())

                        val replyPacket = PacketParser.buildUdpIpPacket(
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
                // Instantly reject QUIC with RFC 792 ICMP Destination Unreachable (Port Unreachable: Type 3 Code 3)
                // Google Chrome & YouTube immediately fallback to fast TCP in 0ms without delay
                val icmpPacket = PacketParser.buildIcmpPortUnreachablePacket(
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
                val udpPayload = buffer.copyOfRange(udpPayloadOffset, udpPayloadOffset + udpPayloadLen)
                udpRelay?.handleUdpPacket(srcIp, dstIp, srcPort, dstPort, udpPayload)
            }
        }
    }

    private fun startNotificationUpdates() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            while (isActive && isRunning) {
                delay(2000)
                val stats = TrafficMonitor.stats.value
                val notification = buildNotification(
                    title = "SourZap Active",
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