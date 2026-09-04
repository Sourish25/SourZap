package com.sourzap.app.torrent.core

import android.util.Log
import com.sourzap.app.service.core.DohResolver
import kotlinx.coroutines.Dispatchers
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
 * Encrypted DNS-over-HTTPS & Open-Port (443/80) BitTorrent Tracker Announcer.
 * Completely bypasses ISP port filtering, DNS hijacking, and DPI packet inspection
 * by resolving tracker domains over encrypted Google/Cloudflare DoH, communicating over HTTPS (port 443)
 * and HTTP (port 80), parsing compact peer responses, and directly injecting connected peers into the libtorrent swarm.
 */
object HttpsTrackerAnnouncer {

    private const val TAG = "HttpsTrackerAnnouncer"
    private const val MIN_ANNOUNCE_INTERVAL_MS = 60_000L // 60s cooldown to prevent tracker rate-limit bans

    private val lastAnnounceTimes = ConcurrentHashMap<String, Long>()

    val WORKING_TRACKERS = listOf(
        // HTTPS Port 443 (Immune to DPI and Port blocks)
        "https://tracker.pmman.tech:443/announce",
        "https://tracker.nekomi.cn:443/announce",
        "https://004430.xyz:443/announce",
        "https://tracker.leechshield.link:443/announce",
        "https://tracker.7471.top:443/announce",
        "https://tr.nyacat.pw:443/announce",
        "https://t.213891.xyz:443/announce",
        "https://open.ftorrent.com:443/announce",
        // HTTP Port 80 (Standard Web Port - Open on ISP firewalls)
        "http://open.trackerlist.xyz:80/announce",
        "http://tracker2.dler.org:80/announce",
        "http://tracker.zhuqiy.com:80/announce",
        "http://004430.xyz:80/announce",
        "http://tr.nyacat.pw:80/announce",
        "http://1337.abcvg.info:80/announce"
    )

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
        peerId: String = "-SZ2810-012345678901",
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
        var totalInjected = 0

        for (trackerUrl in WORKING_TRACKERS) {
            try {
                val announceUrl = "$trackerUrl?info_hash=$urlEncodedHash&peer_id=$peerId&port=6881&uploaded=0&downloaded=0&left=8948197785&compact=1"
                val request = Request.Builder()
                    .url(announceUrl)
                    .header("User-Agent", "SourZap/2.8.1")
                    .header("Accept", "*/*")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bodyBytes = response.body?.bytes() ?: return@use
                        val peers = parseCompactPeers(bodyBytes)
                        var injectedCount = 0
                        for ((ip, port) in peers) {
                            try {
                                val ep = TcpEndpoint(ip, port)
                                handle.swig().connect_peer(ep.swig())
                                injectedCount++
                            } catch (_: Throwable) {}
                        }
                        if (injectedCount > 0) {
                            Log.i(TAG, "Successfully injected $injectedCount peers from $trackerUrl via DoH")
                            totalInjected += injectedCount
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.d(TAG, "Announce to $trackerUrl failed: ${e.message}")
            }
        }
        totalInjected
    }

    fun parseCompactPeers(responseBytes: ByteArray): List<Pair<String, Int>> {
        val peers = mutableListOf<Pair<String, Int>>()
        val latinStr = String(responseBytes, Charsets.ISO_8859_1)
        val keyIdx = latinStr.indexOf("5:peers")
        if (keyIdx < 0) return peers

        val colonIdx = latinStr.indexOf(":", keyIdx + 7)
        if (colonIdx < 0) return peers

        val lenStr = latinStr.substring(keyIdx + 7, colonIdx)
        val peerBytesLen = lenStr.toIntOrNull() ?: return peers
        val peerStart = colonIdx + 1

        if (peerStart + peerBytesLen > responseBytes.size) return peers

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
