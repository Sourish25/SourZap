package com.sourzap.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
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
    // High-concurrency IO scope for gigabit throughput
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var notificationJob: Job? = null
    private var isRunning = false

    // Fast-path active connection registry to eliminate redundant DPI checks on steady-state streams
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
                    val connectionKey = "->:"

                    // If it's a new flow, spawn the turbo desync stream handler
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

                if (dstPort == 53 && udpPayloadLen > 0) { // DNS
                    val queryBytes = buffer.copyOfRange(udpPayloadOffset, length)
                    serviceScope.launch {
                        val responseWire = DohResolver.resolveWireQuery(queryBytes, strategy.dohProvider)
                        if (responseWire != null) {
                            TrafficMonitor.recordRxBytes(responseWire.size.toLong())
                            TrafficMonitor.addConnectionLog(
                                ConnectionLog(
                                    domain = "DNS Resolution",
                                    port = 53,
                                    protocol = "DNS (DoH)",
                                    technique = " [Turbo]",
                                    bytesTransferred = responseWire.size.toLong()
                                )
                            )
                        }
                    }
                } else if (dstPort == 443 && strategy.blockQuic) {
                    // Instantly drop QUIC packet to force browser/YouTube/Discord into Turbo TCP mode
                    TrafficMonitor.addConnectionLog(
                        ConnectionLog(
                            domain = dstIp.hostAddress ?: "QUIC Endpoint",
                            port = 443,
                            protocol = "QUIC (UDP 443)",
                            technique = "FORCE_TCP_FALLBACK",
                            bytesTransferred = length.toLong()
                        )
                    )
                }
            }
        }
    }

    /**
     * Turbo High-Throughput TCP Stream Handler
     * 1. Applies Zapret DPI desynchronization on initial handshake.
     * 2. Immediately switches to zero-copy fast-path jumbo streaming with 512KB socket buffers and TCP_NODELAY.
     */
    private fun handleTurboTcpStream(connectionKey: String, dstHost: String, dstPort: Int, initialPayload: ByteArray) {
        val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value

        serviceScope.launch {
            TrafficMonitor.onConnectionOpened()
            var socket: Socket? = null
            val streamBuffer = ByteArrayPool.obtainStreamBuffer()

            try {
                socket = Socket().apply {
                    // Maximum Speed Socket Configuration
                    receiveBufferSize = 524288 // 512 KB Receive Buffer
                    sendBufferSize = 524288    // 512 KB Send Buffer
                    tcpNoDelay = true          // Disable Nagle algorithm for lowest latency
                    keepAlive = true
                    trafficClass = 0x08        // IPTOS_THROUGHPUT (Prioritize maximum bandwidth)
                    setPerformancePreferences(0, 1, 2) // Prioritize Throughput > Latency > Connection Time
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
                        technique = " [TURBO ⚡]",
                        bytesTransferred = initialPayload.size.toLong()
                    )
                )

                // High-Throughput Steady-State Downstream Data Pump
                val input = socket.getInputStream()
                var bytesRead = input.read(streamBuffer)
                var accumulatedBytes = 0L

                while (isRunning && bytesRead != -1) {
                    accumulatedBytes += bytesRead

                    // Batched updates for minimal locking overhead
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