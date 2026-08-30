package com.sourzap.app.service.core

import android.net.VpnService
import com.sourzap.app.service.TrafficMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
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

    private val udpExecutor = Executors.newFixedThreadPool(POOL_SIZE + 1) { r ->
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
                delay(15000)
                val now = System.currentTimeMillis()
                val iterator = natTable.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastSeen > NAT_IDLE_TIMEOUT_MS) {
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

        // Guard NAT table against unbounded memory growth during massive torrent DHT swarms
        if (natTable.size >= MAX_NAT_ENTRIES) {
            pruneOldestNatEntries()
        }

        natTable[natKeyExact] = mapping
        natTable[natKeyHost] = mapping

        try {
            val sendPacket = DatagramPacket(payload, payload.size, dstIp, dstPort)
            socket.send(sendPacket)
            TrafficMonitor.recordTxBytes(payload.size.toLong())
        } catch (_: Exception) {}
    }

    private fun pruneOldestNatEntries() {
        val now = System.currentTimeMillis()
        val iterator = natTable.entries.iterator()
        var removed = 0
        while (iterator.hasNext() && removed < 512) {
            val entry = iterator.next()
            if (now - entry.value.lastSeen > (NAT_IDLE_TIMEOUT_MS / 2)) {
                iterator.remove()
                removed++
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

                        val client = natTable[natKeyExact]
                            ?: natTable[natKeyHost]
                            ?: natTable.entries.firstOrNull { it.key.startsWith("${remoteAddress.hostAddress}:") }?.value

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
                                vpnOutput.flush()
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