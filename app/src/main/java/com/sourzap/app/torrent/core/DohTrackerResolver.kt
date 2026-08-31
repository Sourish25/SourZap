package com.sourzap.app.torrent.core

import com.sourzap.app.service.core.DohResolver
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * DoH Tracker Hostname Pre-Resolution Subsystem.
 * Resolves tracker domain names over encrypted DNS-over-HTTPS before connecting,
 * bypassing ISP UDP port 53 blocks, DNS hijacking, and censorship.
 */
object DohTrackerResolver {

    private val IPV4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()
    private val inFlight = ConcurrentHashMap<String, Deferred<List<InetAddress>>>()

    fun extractHost(trackerUrl: String?): String? {
        if (trackerUrl.isNullOrBlank()) return null
        val clean = trackerUrl.trim()
        return try {
            val uri = URI(clean)
            var host = uri.host
            if (host == null) {
                val match = Regex("""^[a-zA-Z0-9]+://([^:/]+)""").find(clean)
                host = match?.groupValues?.get(1)
            }
            host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase()
        } catch (_: Exception) {
            val match = Regex("""^[a-zA-Z0-9]+://([^:/]+)""").find(clean)
            match?.groupValues?.get(1)?.trim()?.lowercase()
        }
    }

    fun isIpLiteral(host: String): Boolean {
        if (IPV4_REGEX.matches(host)) return true
        if (host.contains(":") && !host.contains(".")) return true // IPv6 literal
        return false
    }

    suspend fun resolveHost(host: String): List<InetAddress> = withContext(Dispatchers.IO) {
        val normalized = host.trim().lowercase().removeSuffix(".")
        if (normalized.isEmpty()) return@withContext emptyList()

        // 1. IP literal check
        if (isIpLiteral(normalized)) {
            return@withContext runCatching { listOf(InetAddress.getByName(normalized)) }.getOrDefault(emptyList())
        }

        // 2. In-memory cache check
        cache[normalized]?.let { return@withContext it }

        // 3. Singleflight coalescing
        val deferred = inFlight.computeIfAbsent(normalized) {
            async(Dispatchers.IO) {
                try {
                    val ips = DohResolver.resolve(normalized)
                    if (ips.isNotEmpty()) {
                        cache[normalized] = ips
                    }
                    ips
                } catch (_: Exception) {
                    emptyList()
                } finally {
                    inFlight.remove(normalized)
                }
            }
        }

        deferred.await()
    }

    suspend fun preResolveTrackers(trackers: List<String>): Map<String, List<InetAddress>> = coroutineScope {
        val uniqueHosts = trackers
            .mapNotNull { extractHost(it) }
            .filter { it.isNotEmpty() && !isIpLiteral(it) }
            .distinct()

        val resultMap = ConcurrentHashMap<String, List<InetAddress>>()

        val tasks = uniqueHosts.map { host ->
            async(Dispatchers.IO) {
                try {
                    val ips = resolveHost(host)
                    if (ips.isNotEmpty()) {
                        resultMap[host] = ips
                    }
                } catch (_: Exception) {
                    // Error tolerance: individual host failures do not abort batch
                }
            }
        }

        tasks.awaitAll()
        resultMap
    }

    fun getCachedIps(host: String): List<InetAddress>? {
        return cache[host.trim().lowercase().removeSuffix(".")]
    }

    fun clearCache() {
        cache.clear()
        inFlight.clear()
    }
}
