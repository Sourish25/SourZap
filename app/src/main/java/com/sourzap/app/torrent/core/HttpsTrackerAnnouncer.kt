package com.sourzap.app.torrent.core

import android.util.Log
import com.sourzap.app.service.core.DohResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentHandle
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Encrypted DNS-over-HTTPS & Open-Port (443) BitTorrent Tracker Announcer.
 * Completely bypasses ISP port filtering, DNS hijacking, and DPI packet inspection
 * by resolving tracker domains over encrypted Google/Cloudflare DoH, communicating over HTTPS (port 443),
 * parsing compact peer responses, and directly injecting connected peers into the libtorrent swarm.
 */
object HttpsTrackerAnnouncer {

    private const val TAG = "HttpsTrackerAnnouncer"
    private const val MIN_ANNOUNCE_INTERVAL_MS = 60_000L // 60s cooldown to prevent tracker rate-limit bans

    private val lastAnnounceTimes = ConcurrentHashMap<String, Long>()

    val WORKING_TRACKERS = (listOf(
        // HTTPS Port 443 (Immune to DPI and Port blocks)
        "https://tracker.pmman.tech:443/announce",
        "https://tracker.nekomi.cn:443/announce",
        "https://tracker.leechshield.link:443/announce",
        "https://tracker.7471.top:443/announce",
        "https://open.ftorrent.com:443/announce"
    ) + TrackerInjector.HTTPS_PORT_443_TRACKERS).distinct()

    private val dohDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return try {
                val resolved = runBlocking(Dispatchers.IO) {
                    DohResolver.resolve(hostname)
                }
                if (resolved.isNotEmpty()) resolved else Dns.SYSTEM.lookup(hostname)
            } catch (_: Throwable) {
                try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(dohDns)
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

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

        val urlEncodedHash = urlEncodeBytes(hashBytes)
        val port = 6881

        val status = try { if (handle.isValid) handle.status() else null } catch (_: Throwable) { null }
        val leftBytes = try {
            val total = status?.totalWanted() ?: 0L
            val done = status?.totalWantedDone() ?: 0L
            if (total > done) total - done else 8948197785L
        } catch (_: Throwable) { 8948197785L }
        val downloadedBytes = try { status?.allTimeDownload() ?: 0L } catch (_: Throwable) { 0L }
        val uploadedBytes = try { status?.allTimeUpload() ?: 0L } catch (_: Throwable) { 0L }

        try {
            NetworkIpHelper.refreshPublicIp()
        } catch (_: Throwable) {}

        val deferredAnnounces = WORKING_TRACKERS.map { trackerUrl ->
            async {
                try {
                    val announceUrl = "$trackerUrl?info_hash=$urlEncodedHash&peer_id=$peerId&port=$port&uploaded=$uploadedBytes&downloaded=$downloadedBytes&left=$leftBytes&compact=1"
                    val request = Request.Builder()
                        .url(announceUrl)
                        .header("User-Agent", "SourZap/2.9.0")
                        .header("Accept", "*/*")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyBytes = response.body?.bytes() ?: return@use emptyList<Pair<String, Int>>()
                            parseCompactPeers(bodyBytes)
                        } else {
                            emptyList()
                        }
                    }
                } catch (e: Throwable) {
                    Log.d(TAG, "Announce to $trackerUrl failed: ${e.message}")
                    emptyList()
                }
            }
        }

        val allDiscoveredPeers = deferredAnnounces.awaitAll().flatten().distinct()
        var injectedCount = 0

        if (!handle.isValid) return@withContext 0

        withContext(TorrentEngineManager.torrentWorkerDispatcher) {
            for ((ip, peerPort) in allDiscoveredPeers.take(35)) {
                if (NetworkIpHelper.isSelfOrLocal(ip)) {
                    Log.d(TAG, "Skipping self/local peer $ip:$peerPort")
                    continue
                }
                try {
                    if (!handle.isValid) break
                    if (TorrentEngineManager.injectPeerSafely(handle, ip, peerPort)) {
                        injectedCount++
                    }
                } catch (_: Throwable) {}
            }
        }

        if (injectedCount > 0) {
            Log.i(TAG, "Successfully injected $injectedCount peers via DoH HTTPS trackers into $hashLower")
        }
        injectedCount
    }

    fun parseCompactPeers(responseBytes: ByteArray): List<Pair<String, Int>> {
        val peers = mutableListOf<Pair<String, Int>>()
        val latinStr = String(responseBytes, Charsets.ISO_8859_1)
        val keyIdx = latinStr.indexOf("5:peers")
        if (keyIdx < 0) return peers

        // 1. BEP 23 / BEP 3 Binary Compact Peer String (5:peers<len>:<bytes>)
        val afterPeers = latinStr.substring(keyIdx + 7, minOf(latinStr.length, keyIdx + 8))
        if (afterPeers.isNotEmpty() && afterPeers[0].isDigit()) {
            val colonIdx = latinStr.indexOf(":", keyIdx + 7)
            if (colonIdx in (keyIdx + 7)..(keyIdx + 16)) {
                val lenStr = latinStr.substring(keyIdx + 7, colonIdx)
                val peerBytesLen = lenStr.toIntOrNull()
                if (peerBytesLen != null && peerBytesLen > 0) {
                    val peerStart = colonIdx + 1
                    if (peerStart + peerBytesLen <= responseBytes.size) {
                        var i = 0
                        while (i + 6 <= peerBytesLen) {
                            val offset = peerStart + i
                            val ip = "${responseBytes[offset].toInt() and 0xFF}.${responseBytes[offset+1].toInt() and 0xFF}.${responseBytes[offset+2].toInt() and 0xFF}.${responseBytes[offset+3].toInt() and 0xFF}"
                            val port = ((responseBytes[offset+4].toInt() and 0xFF) shl 8) or (responseBytes[offset+5].toInt() and 0xFF)
                            if (port in 1..65535 && ip != "0.0.0.0") {
                                peers.add(ip to port)
                            }
                            i += 6
                        }
                        if (peers.isNotEmpty()) return peers
                    }
                }
            }
        }

        // 2. BEP 3 Dictionary Peer List (5:peersld...ee...e)
        val listStart = latinStr.indexOf("5:peersl", keyIdx)
        if (listStart >= 0) {
            var pos = listStart + "5:peersl".length
            while (pos < responseBytes.size && responseBytes[pos] == 'd'.code.toByte()) {
                pos++ // skip 'd'
                var ip: String? = null
                var port: Int? = null

                while (pos < responseBytes.size && responseBytes[pos] != 'e'.code.toByte()) {
                    // Parse key string: <len>:<key>
                    val colonPos = latinStr.indexOf(':', pos)
                    if (colonPos < 0) break
                    val keyLen = latinStr.substring(pos, colonPos).toIntOrNull() ?: break
                    val keyStart = colonPos + 1
                    val key = latinStr.substring(keyStart, minOf(latinStr.length, keyStart + keyLen))
                    pos = keyStart + keyLen

                    if (pos >= responseBytes.size) break
                    // Parse value based on type indicator
                    when (latinStr[pos]) {
                        'i' -> {
                            val endInt = latinStr.indexOf('e', pos)
                            if (endInt < 0) break
                            val num = latinStr.substring(pos + 1, endInt).toIntOrNull()
                            if (key == "port") port = num
                            pos = endInt + 1
                        }
                        in '0'..'9' -> {
                            val cPos = latinStr.indexOf(':', pos)
                            if (cPos < 0) break
                            val strLen = latinStr.substring(pos, cPos).toIntOrNull() ?: break
                            val strStart = cPos + 1
                            val strVal = latinStr.substring(strStart, minOf(latinStr.length, strStart + strLen))
                            if (key == "ip") ip = strVal
                            pos = strStart + strLen
                        }
                        'l', 'd' -> {
                            var depth = 1
                            pos++
                            while (pos < responseBytes.size && depth > 0) {
                                if (responseBytes[pos] == 'l'.code.toByte() || responseBytes[pos] == 'd'.code.toByte()) depth++
                                else if (responseBytes[pos] == 'e'.code.toByte()) depth--
                                pos++
                            }
                        }
                        else -> pos++
                    }
                }

                // Skip dictionary closing 'e'
                if (pos < responseBytes.size && responseBytes[pos] == 'e'.code.toByte()) {
                    pos++
                }

                if (!ip.isNullOrBlank() && port != null && port in 1..65535 && ip != "0.0.0.0") {
                    peers.add(ip to port)
                }
            }
        }

        return peers
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun urlEncodeBytes(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(String.format("%02x", c))
            }
        }
        return sb.toString()
    }
}
