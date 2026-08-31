package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.service.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-Performance Low-Allocation UDP Relay Engine for VpnService TUN Interface.
 * Employs a multi-socket DatagramSocket pool, O(1) stateful NAT table, and zero-allocation
 * packet assembly for high-throughput BitTorrent DHT/uTP, STUN/TURN, WebRTC, WhatsApp Calls, and Gaming.
 */
class TunUdpRelay(
    private val vpnService: VpnService,
    private val vpnOutput: FileOutputStream,
    private val scope: CoroutineScope
) {
    private val POOL_SIZE = 8
    private val sockets = ArrayList<DatagramSocket>(POOL_SIZE)
    private val isRunning = AtomicBoolean(true)

    private val udpExecutor = Executors.newFixedThreadPool(POOL_SIZE + 2) { r ->
        Thread(r, "SourZap-TunUdpWorker").apply { isDaemon = true }
    }
    private val udpDispatcher = udpExecutor.asCoroutineDispatcher()

    companion object {
        private const val MAX_NAT_ENTRIES = 4096
        private const val NAT_IDLE_TIMEOUT_MS = 60_000L // 60 seconds NAT entry timeout
    }

    private data class ClientMapping(
        val clientIp: InetAddress,
        val clientPort: Int,
        @Volatile var lastSeen: Long
    )

    private data class OutgoingUdpPacket(
        val socketIndex: Int,
        val dstIp: InetAddress,
        val dstPort: Int,
        val payload: ByteArray
    )

    private val sendChannel = Channel<OutgoingUdpPacket>(
        capacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Primary exact match: "RemoteHost:RemotePort#SocketIndex" -> ClientMapping
    private val exactNatTable = ConcurrentHashMap<String, ClientMapping>()
    // Secondary host match: "RemoteHost#SocketIndex" -> ClientMapping
    private val hostNatTable = ConcurrentHashMap<String, ClientMapping>()

    private var cleanerJob: Job? = null
    private var senderJob: Job? = null

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

        // Non-blocking UDP sender worker
        senderJob = scope.launch(udpDispatcher) {
            for (packet in sendChannel) {
                if (!scope.isActive || !isRunning.get()) break
                try {
                    val socket = sockets.getOrNull(packet.socketIndex) ?: continue
                    val sendPacket = DatagramPacket(packet.payload, packet.payload.size, packet.dstIp, packet.dstPort)
                    socket.send(sendPacket)
                    TrafficMonitor.recordTxBytes(packet.payload.size.toLong())
                } catch (_: Exception) {}
            }
        }

        // Background NAT table scavenger
        cleanerJob = scope.launch(udpDispatcher) {
            while (isActive && isRunning.get()) {
                delay(15000)
                val now = System.currentTimeMillis()
                val exactIter = exactNatTable.entries.iterator()
                while (exactIter.hasNext()) {
                    val entry = exactIter.next()
                    if (now - entry.value.lastSeen > NAT_IDLE_TIMEOUT_MS) {
                        exactIter.remove()
                    }
                }
                val hostIter = hostNatTable.entries.iterator()
                while (hostIter.hasNext()) {
                    val entry = hostIter.next()
                    if (now - entry.value.lastSeen > NAT_IDLE_TIMEOUT_MS) {
                        hostIter.remove()
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

        val mapping = ClientMapping(srcIp, srcPort, System.currentTimeMillis())
        val natKeyExact = "${dstIp.hostAddress}:$dstPort#$socketIndex"
        val natKeyHost = "${dstIp.hostAddress}#$socketIndex"

        // Guard NAT table against unbounded memory growth during massive torrent DHT swarms
        if (exactNatTable.size >= MAX_NAT_ENTRIES) {
            pruneOldestNatEntries()
        }

        exactNatTable[natKeyExact] = mapping
        hostNatTable[natKeyHost] = mapping

        // Non-blocking enqueue to prevent TUN reader loop stall
        sendChannel.trySend(OutgoingUdpPacket(socketIndex, dstIp, dstPort, payload))
    }

    private fun pruneOldestNatEntries() {
        val now = System.currentTimeMillis()
        val threshold = NAT_IDLE_TIMEOUT_MS / 2
        var removed = 0
        val exactIter = exactNatTable.entries.iterator()
        while (exactIter.hasNext() && removed < 512) {
            val entry = exactIter.next()
            if (now - entry.value.lastSeen > threshold) {
                exactIter.remove()
                removed++
            }
        }
        val hostIter = hostNatTable.entries.iterator()
        while (hostIter.hasNext()) {
            val entry = hostIter.next()
            if (now - entry.value.lastSeen > threshold) {
                hostIter.remove()
            }
        }
    }

    private fun runReceiverLoop(socket: DatagramSocket, socketIndex: Int) {
        val recvBuf = ByteArrayPool.obtainStreamBuffer()
        val recvPacket = DatagramPacket(recvBuf, recvBuf.size)

        try {
            while (scope.isActive && isRunning.get()) {
                try {
                    // Reset length to full buffer size before every receive call.
                    recvPacket.length = recvBuf.size
                    socket.receive(recvPacket)
                    val len = recvPacket.length
                    val remoteAddress = recvPacket.address ?: continue
                    val remotePort = recvPacket.port

                    if (len > 0) {
                        val natKeyExact = "${remoteAddress.hostAddress}:$remotePort#$socketIndex"
                        val natKeyHost = "${remoteAddress.hostAddress}#$socketIndex"

                        // O(1) Collision-Free Lookups (Exact port match -> Host IP fallback)
                        val client = exactNatTable[natKeyExact] ?: hostNatTable[natKeyHost]

                        if (client != null) {
                            client.lastSeen = System.currentTimeMillis()
                            TrafficMonitor.recordRxBytes(len.toLong())

                            // Zero-allocation slice building directly from receive buffer using PacketParser
                            val replyIpPacket = PacketParser.buildUdpIpPacket(
                                srcIp = remoteAddress,
                                dstIp = client.clientIp,
                                srcPort = remotePort,
                                dstPort = client.clientPort,
                                payload = recvBuf,
                                payloadOffset = 0,
                                payloadLen = len
                            )

                            synchronized(vpnOutput) {
                                vpnOutput.write(replyIpPacket)
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (!isRunning.get()) break
                }
            }
        } finally {
            ByteArrayPool.recycleStreamBuffer(recvBuf)
        }
    }

    fun closeAll() {
        isRunning.set(false)
        cleanerJob?.cancel()
        senderJob?.cancel()
        sendChannel.close()
        sockets.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        sockets.clear()
        exactNatTable.clear()
        hostNatTable.clear()
        try {
            udpExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}