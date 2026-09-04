package com.sourzap.app.torrent.core

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Subsystem to detect local interface IPs and WAN public IP.
 * Prevents "Ghost Peer" self-connection loops where trackers return the device's
 * own external or LAN IP addresses, saturating connection slots with doomed self-connections.
 */
object NetworkIpHelper {

    private const val TAG = "NetworkIpHelper"
    private val cachedPublicIp = AtomicReference<String?>(null)
    private val localIps = ConcurrentHashMap.newKeySet<String>()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .followRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    init {
        refreshLocalIps()
    }

    /**
     * Refreshes all active IPv4 addresses from device network interfaces.
     */
    fun refreshLocalIps(): Set<String> {
        val discovered = mutableSetOf("127.0.0.1", "0.0.0.0", "localhost")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return discovered
            for (nif in interfaces) {
                try {
                    if (!nif.isUp) continue
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank()) {
                                discovered.add(host.trim())
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error refreshing network interfaces: ${t.message}")
        }
        localIps.clear()
        localIps.addAll(discovered)
        return localIps
    }

    /**
     * Queries external lightweight IP discovery endpoints to cache the WAN IP.
     */
    suspend fun refreshPublicIp(): String? = withContext(Dispatchers.IO) {
        val endpoints = listOf(
            "https://api.ipify.org",
            "https://1.1.1.1/cdn-cgi/trace",
            "https://checkip.amazonaws.com"
        )

        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "SourZap/2.8.4")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()?.trim() ?: ""
                        val extractedIp = if (url.contains("cdn-cgi/trace")) {
                            body.lines().firstOrNull { it.startsWith("ip=") }?.substringAfter("ip=")?.trim()
                        } else {
                            body
                        }
                        if (extractedIp != null && isValidIpv4(extractedIp)) {
                            cachedPublicIp.set(extractedIp)
                            Log.i(TAG, "Device WAN Public IP identified: $extractedIp")
                            return@withContext extractedIp
                        }
                    }
                }
            } catch (_: Throwable) {
                // Try next endpoint
            }
        }
        cachedPublicIp.get()
    }

    /**
     * Manually records the public IP if learned from external protocols (e.g. STUN / DoH / Trackers).
     */
    fun setPublicIp(ip: String) {
        val clean = ip.trim()
        if (isValidIpv4(clean)) {
            cachedPublicIp.set(clean)
        }
    }

    fun getPublicIp(): String? = cachedPublicIp.get()

    /**
     * Checks if the given IP address is our own device (WAN or LAN) or an unroutable bogon IP.
     * Returns TRUE if the IP must NOT be connected to as a BitTorrent peer.
     */
    fun isSelfOrLocal(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return true
        val clean = ip.trim()

        // 1. Loopback and unspecified
        if (clean == "0.0.0.0" || clean == "127.0.0.1" || clean.startsWith("127.")) return true

        // 2. Bogon / APIPA
        if (clean.startsWith("169.254.") || clean.startsWith("0.")) return true

        // 3. Private IPv4 addresses (RFC 1918)
        // 10.0.0.0/8
        if (clean.startsWith("10.")) return true
        // 192.168.0.0/16
        if (clean.startsWith("192.168.")) return true
        // 172.16.0.0/12
        if (clean.startsWith("172.")) {
            val parts = clean.split(".")
            if (parts.size >= 2) {
                val second = parts[1].toIntOrNull()
                if (second != null && second in 16..31) return true
            }
        }

        // 4. Matches cached device WAN public IP
        val pubIp = cachedPublicIp.get()
        if (pubIp != null && clean == pubIp) return true

        // 5. Matches any device network interface IP
        if (localIps.contains(clean)) return true

        return false
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all {
            val num = it.toIntOrNull()
            num != null && num in 0..255
        }
    }
}
