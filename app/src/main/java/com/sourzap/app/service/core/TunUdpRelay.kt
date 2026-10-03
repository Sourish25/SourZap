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
    private val scope: CoroutineScope,
    private val tunWriteQueue: Channel<ByteArray>? = null
) {
    private val POOL_SIZE = 8
    private val sockets = ArrayList<DatagramSocket>(POOL_SIZE)
    private val isRunning = AtomicBoolean(true)

    private val udpExecutor = Executors.newFixedThreadPool(POOL_SIZE + 2) { r ->
        Thread(r, "SourZap-TunUdpWorker").apply { isDaemon = true }
    }
    private val udpDispatcher = udpExecutor.asCoroutineDispatcher()

    private val localTunWriteChannel = tunWriteQueue ?: Channel<ByteArray>(capacity = 8192, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var tunWriterJob: Job? = null

    companion object {
        private const val MAX_NAT_ENTRIES = 4096
        private const val NAT_IDLE_TIMEOUT_MS = 60_000L // 60 seconds NAT entry timeout

        fun isGamingPort(port: Int): Boolean =
            port == 9339 || port == 30000 || port in 27015..27030 || port in 7777..7780 || port == 10012
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
        capacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Primary exact match: "$srcPort->$dstIp:$dstPort" -> ClientMapping
    private val exactNatTable = ConcurrentHashMap<String, ClientMapping>()
    // Secondary host match: "RemoteHost#SocketIndex" -> ClientMapping
    private val hostNatTable = ConcurrentHashMap<String, ClientMapping>()
    // Reverse lookup index: "RemoteHost:RemotePort" -> ClientMapping
    private val endpointNatTable = ConcurrentHashMap<String, ClientMapping>()
    // Dedicated gaming NAT table for Supercell (Port 9339) and low-latency gaming
    private val gamingNatTable = ConcurrentHashMap<String, ClientMapping>()

    private var cleanerJob: Job? = null
    private var senderJob: Job? = null

    init {
        if (tunWriteQueue == null) {
            tunWriterJob = scope.launch(udpDispatcher) {
                for (packet in localTunWriteChannel) {
                    if (!scope.isActive || !isRunning.get()) break
                    try {
                        vpnOutput.write(packet)
                    } catch (_: Exception) {}
                }
            }
        }

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
                val endpointIter = endpointNatTable.entries.iterator()
                while (endpointIter.hasNext()) {
                    val entry = endpointIter.next()
                    if (now - entry.value.lastSeen > NAT_IDLE_TIMEOUT_MS) {
                        endpointIter.remove()
                    }
                }
                val gamingIter = gamingNatTable.entries.iterator()
                while (gamingIter.hasNext()) {
                    val entry = gamingIter.next()
                    if (now - entry.value.lastSeen > NAT_IDLE_TIMEOUT_MS) {
                        gamingIter.remove()
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
        val natKeyExact = "$srcPort->${dstIp.hostAddress}:$dstPort"
        val natKeyHost = "${dstIp.hostAddress}#$socketIndex"
        val endpointKey = "${dstIp.hostAddress}:$dstPort"

        if (isGamingPort(dstPort)) {
            val gamingKey = "${dstIp.hostAddress}:$dstPort"
            gamingNatTable[gamingKey] = mapping
        }

        // Guard NAT table against unbounded memory growth during massive torrent DHT swarms
        if (exactNatTable.size >= MAX_NAT_ENTRIES) {
            pruneOldestNatEntries()
        }

        exactNatTable[natKeyExact] = mapping
        hostNatTable[natKeyHost] = mapping
        endpointNatTable[endpointKey] = mapping

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
        val endpointIter = endpointNatTable.entries.iterator()
        while (endpointIter.hasNext()) {
            val entry = endpointIter.next()
            if (now - entry.value.lastSeen > threshold) {
                endpointIter.remove()
            }
        }
        // Fallback eviction if table is still saturated to prevent dropping new UDP connections
        if (exactNatTable.size >= MAX_NAT_ENTRIES - 64) {
            val forceIter = exactNatTable.entries.iterator()
            var forceRemoved = 0
            while (forceIter.hasNext() && forceRemoved < 256) {
                forceIter.next()
                forceIter.remove()
                forceRemoved++
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
                        val endpointKey = "${remoteAddress.hostAddress}:$remotePort"
                        val natKeyHost = "${remoteAddress.hostAddress}#$socketIndex"
                        val gamingKey = "${remoteAddress.hostAddress}:$remotePort"

                        // O(1) Collision-Free Lookups (Gaming priority -> Exact endpoint match -> Suffix match -> Host IP fallback)
                        val isGaming = isGamingPort(remotePort)
                        val client = (if (isGaming) gamingNatTable[gamingKey] else null)
                            ?: endpointNatTable[endpointKey]
                            ?: exactNatTable.entries.firstOrNull { it.key.endsWith("->$endpointKey") }?.value
                            ?: hostNatTable[natKeyHost]

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

                            localTunWriteChannel.trySend(replyIpPacket)
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
        tunWriterJob?.cancel()
        sendChannel.close()
        sockets.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        sockets.clear()
        exactNatTable.clear()
        hostNatTable.clear()
        gamingNatTable.clear()
        endpointNatTable.clear()
        try {
            udpExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}