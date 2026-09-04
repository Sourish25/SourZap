package com.sourzap.app.torrent.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentHandle
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Native Kotlin BitTorrent BEP 15 UDP Tracker Announcer.
 *
 * Communicates directly with high-capacity UDP trackers over Android JVM DatagramSockets,
 * resolving tracker hostnames via encrypted Google/Cloudflare DNS-over-HTTPS (DoH).
 * Bypasses ISP DNS hijacking, Android native DNS resolution bugs, and libtorrent socket restrictions.
 * Discovered peers are immediately filtered against self/local IPs and injected directly into the swarm.
 */
object UdpTrackerAnnouncer {

    private const val TAG = "UdpTrackerAnnouncer"
    private const val MIN_ANNOUNCE_INTERVAL_MS = 45_000L // 45s cooldown between passes
    private const val PROTOCOL_ID = 0x41727101980L
    private const val ACTION_CONNECT = 0
    private const val ACTION_ANNOUNCE = 1
    private const val SOCKET_TIMEOUT_MS = 3500

    private val lastAnnounceTimes = ConcurrentHashMap<String, Long>()

    val WORKING_UDP_TRACKERS = listOf(
        "tracker.opentrackr.org" to 1337,
        "open.demonii.com" to 1337,
        "explodie.org" to 6969,
        "tracker.dler.org" to 6969,
        "tracker.torrent.eu.org" to 451,
        // Direct IPs (immune to DNS)
        "93.158.213.92" to 1337,
        "185.121.168.96" to 1337,
        "23.157.120.14" to 6969
    )

    suspend fun announceAndInjectPeers(
        handle: TorrentHandle,
        hexInfoHash: String,
        peerId: String = "-SZ2840-012345678901",
        force: Boolean = false
    ): Int = withContext(Dispatchers.IO) {
        val hashLower = hexInfoHash.lowercase()
        val now = System.currentTimeMillis()

        if (!force) {
            val lastTime = lastAnnounceTimes[hashLower] ?: 0L
            if (now - lastTime < MIN_ANNOUNCE_INTERVAL_MS) {
                return@withContext 0
            }
        }
        lastAnnounceTimes[hashLower] = now

        val hashBytes = hexStringToByteArray(hashLower)
        if (hashBytes.size != 20) return@withContext 0

        val peerIdBytes = peerId.toByteArray(Charsets.ISO_8859_1).copyOf(20)

        // Ensure public IP is refreshed to prevent self-connection
        try {
            NetworkIpHelper.refreshPublicIp()
        } catch (_: Throwable) {}

        val deferredAnnounces = WORKING_UDP_TRACKERS.map { (host, port) ->
            async(Dispatchers.IO) {
                try {
                    queryTracker(host, port, hashBytes, peerIdBytes)
                } catch (e: Throwable) {
                    Log.d(TAG, "UDP announce to $host:$port failed: ${e.message}")
                    emptyList()
                }
            }
        }

        val allDiscoveredPeers = deferredAnnounces.awaitAll().flatten().distinct()
        var injectedCount = 0

        if (!handle.isValid) return@withContext 0

        for ((ip, peerPort) in allDiscoveredPeers.take(35)) {
            if (NetworkIpHelper.isSelfOrLocal(ip)) {
                Log.d(TAG, "Skipping self/local peer $ip:$peerPort")
                continue
            }
            try {
                if (!handle.isValid) break
                val ep = TcpEndpoint(ip, peerPort)
                handle.swig().connect_peer(ep.swig())
                injectedCount++
            } catch (_: Throwable) {}
        }

        if (injectedCount > 0) {
            Log.i(TAG, "Successfully injected $injectedCount live peers from UDP trackers into torrent $hashLower")
        }
        injectedCount
    }

    suspend fun queryTracker(
        host: String,
        port: Int,
        infoHash: ByteArray,
        peerId: ByteArray
    ): List<Pair<String, Int>> = withContext(Dispatchers.IO) {
        // Resolve host via DoH
        val inetAddresses = DohTrackerResolver.resolveHost(host)
        if (inetAddresses.isEmpty()) return@withContext emptyList()
        val targetIp = inetAddresses.first()

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = SOCKET_TIMEOUT_MS

            // 1. BEP 15 Connect Request (16 bytes)
            val connectTransId = Random.nextInt()
            val connectReqBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            connectReqBuf.putLong(PROTOCOL_ID)
            connectReqBuf.putInt(ACTION_CONNECT)
            connectReqBuf.putInt(connectTransId)

            val connectReqPkt = DatagramPacket(connectReqBuf.array(), 16, targetIp, port)
            socket.send(connectReqPkt)

            // Connect Response (16 bytes)
            val connectResBytes = ByteArray(16)
            val connectResPkt = DatagramPacket(connectResBytes, 16)
            socket.receive(connectResPkt)

            val connectResBuf = ByteBuffer.wrap(connectResBytes).order(ByteOrder.BIG_ENDIAN)
            val action = connectResBuf.getInt()
            val resTransId = connectResBuf.getInt()
            val connectionId = connectResBuf.getLong()

            if (action != ACTION_CONNECT || resTransId != connectTransId) {
                return@withContext emptyList()
            }

            // 2. BEP 15 Announce Request (98 bytes)
            val announceTransId = Random.nextInt()
            val announceReqBuf = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN)
            announceReqBuf.putLong(connectionId)
            announceReqBuf.putInt(ACTION_ANNOUNCE)
            announceReqBuf.putInt(announceTransId)
            announceReqBuf.put(infoHash)
            announceReqBuf.put(peerId)
            announceReqBuf.putLong(0L) // downloaded
            announceReqBuf.putLong(1000000L) // left
            announceReqBuf.putLong(0L) // uploaded
            announceReqBuf.putInt(2) // event = 2 (started)
            announceReqBuf.putInt(0) // IP address = 0
            announceReqBuf.putInt(Random.nextInt()) // key
            announceReqBuf.putInt(100) // num_want = 100
            announceReqBuf.putShort(6881.toShort()) // port = 6881

            val announceReqPkt = DatagramPacket(announceReqBuf.array(), 98, targetIp, port)
            socket.send(announceReqPkt)

            // Announce Response (20 + 6*N bytes)
            val announceResBytes = ByteArray(2048)
            val announceResPkt = DatagramPacket(announceResBytes, announceResBytes.size)
            socket.receive(announceResPkt)

            val length = announceResPkt.length
            if (length < 20) return@withContext emptyList()

            parsePeersFromResponse(announceResBytes, length, announceTransId)
        } catch (e: Throwable) {
            Log.d(TAG, "Failed UDP query to $host:$port: ${e.message}")
            emptyList()
        } finally {
            socket?.close()
        }
    }

    fun parsePeersFromResponse(
        responseBytes: ByteArray,
        length: Int,
        expectedTransId: Int
    ): List<Pair<String, Int>> {
        if (length < 20) return emptyList()

        val buf = ByteBuffer.wrap(responseBytes, 0, length).order(ByteOrder.BIG_ENDIAN)
        val action = buf.getInt()
        val transId = buf.getInt()

        if (action != ACTION_ANNOUNCE || transId != expectedTransId) {
            return emptyList()
        }

        val interval = buf.getInt()
        val leechers = buf.getInt()
        val seeders = buf.getInt()

        val peers = mutableListOf<Pair<String, Int>>()
        while (buf.remaining() >= 6) {
            val b0 = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val b2 = buf.get().toInt() and 0xFF
            val b3 = buf.get().toInt() and 0xFF
            val ip = "$b0.$b1.$b2.$b3"
            val port = buf.getShort().toInt() and 0xFFFF

            if (port in 1..65535 && ip != "0.0.0.0") {
                peers.add(ip to port)
            }
        }
        return peers
    }

    fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
