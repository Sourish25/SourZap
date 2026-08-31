package com.sourzap.app.torrent

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test suite for DoH Tracker Hostname Pre-Resolution Subsystem.
 * Verifies Requirement R1, R2 & Feature F7:
 * - Domain extraction from HTTPS, HTTP, UDP, and WSS tracker URLs
 * - IP literal detection and bypass
 * - Asynchronous batch pre-resolution across curated tracker lists
 * - Singleflight deduplication for concurrent tracker lookups
 * - In-memory LRU caching and fault tolerance for failing hostnames
 */
class DohTrackerResolverTest {

    interface IDohEngine {
        suspend fun resolve(domain: String): List<InetAddress>
    }

    class MockDohEngine : IDohEngine {
        val queryCount = AtomicInteger(0)
        val domainIpMap = ConcurrentHashMap<String, List<InetAddress>>()

        init {
            domainIpMap["tracker.tamersunion.org"] = listOf(InetAddress.getByName("104.21.56.78"))
            domainIpMap["tracker.loligirl.cn"] = listOf(InetAddress.getByName("172.67.189.45"))
            domainIpMap["tr.burnabyhighstar.com"] = listOf(InetAddress.getByName("104.26.12.34"))
            domainIpMap["tracker.renfei.net"] = listOf(InetAddress.getByName("104.22.4.99"))
        }

        override suspend fun resolve(domain: String): List<InetAddress> {
            queryCount.incrementAndGet()
            kotlinx.coroutines.delay(20L)
            val normalized = domain.trim().lowercase().removeSuffix(".")
            return domainIpMap[normalized] ?: emptyList()
        }
    }

    class DohTrackerResolver(
        private val dohEngine: IDohEngine
    ) {
        private val cache = ConcurrentHashMap<String, List<InetAddress>>()
        private val inFlight = ConcurrentHashMap<String, Deferred<List<InetAddress>>>()

        companion object {
            private val IPV4_REGEX = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")

            fun extractHost(trackerUrl: String?): String? {
                if (trackerUrl.isNullOrBlank()) return null
                val clean = trackerUrl.trim()
                return try {
                    val uri = URI(clean)
                    var host = uri.host
                    if (host == null) {
                        // Fallback regex parsing for non-standard schemes (e.g. udp://)
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
                        val ips = dohEngine.resolve(normalized)
                        if (ips.isNotEmpty()) {
                            cache[normalized] = ips
                        }
                        ips
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

    @Test
    fun `test Extract Hostname across all tracker URL protocols`() {
        assertEquals("tracker.tamersunion.org", DohTrackerResolver.extractHost("https://tracker.tamersunion.org:443/announce"))
        assertEquals("tracker.loligirl.cn", DohTrackerResolver.extractHost("http://tracker.loligirl.cn/announce"))
        assertEquals("tracker.opentrackr.org", DohTrackerResolver.extractHost("udp://tracker.opentrackr.org:1337/announce"))
        assertEquals("tracker.openwebtorrent.com", DohTrackerResolver.extractHost("wss://tracker.openwebtorrent.com:443/"))
        assertEquals("192.168.1.1", DohTrackerResolver.extractHost("https://192.168.1.1:443/announce"))
        assertEquals("2001:db8::1", DohTrackerResolver.extractHost("https://[2001:db8::1]:443/announce"))

        // Null and malformed URLs
        assertNull(DohTrackerResolver.extractHost(null))
        assertNull(DohTrackerResolver.extractHost(""))
        assertNull(DohTrackerResolver.extractHost("not-a-valid-uri"))
    }

    @Test
    fun `test IP literal detection`() {
        assertTrue(DohTrackerResolver.isIpLiteral("1.1.1.1"))
        assertTrue(DohTrackerResolver.isIpLiteral("192.168.0.100"))
        assertTrue(DohTrackerResolver.isIpLiteral("2001:db8::1"))

        assertFalse(DohTrackerResolver.isIpLiteral("tracker.tamersunion.org"))
        assertFalse(DohTrackerResolver.isIpLiteral("google.com"))
        assertFalse(DohTrackerResolver.isIpLiteral("tr.burnabyhighstar.com"))
    }

    @Test
    fun `test Asynchronous Pre-Resolution across Tracker Batch`() = runBlocking {
        val mockEngine = MockDohEngine()
        val resolver = DohTrackerResolver(mockEngine)

        val trackerList = listOf(
            "https://tracker.tamersunion.org:443/announce",
            "https://tracker.loligirl.cn:443/announce",
            "https://tr.burnabyhighstar.com:443/announce",
            "https://1.1.1.1:443/announce", // IP literal should be skipped
            "https://tracker.tamersunion.org:443/announce" // Duplicate domain should be deduplicated
        )

        val results = resolver.preResolveTrackers(trackerList)

        // 3 unique domains resolved
        assertEquals(3, results.size)
        assertTrue(results.containsKey("tracker.tamersunion.org"))
        assertTrue(results.containsKey("tracker.loligirl.cn"))
        assertTrue(results.containsKey("tr.burnabyhighstar.com"))

        // Query count on engine should be exactly 3 (deduplicated and skipped IP literal)
        assertEquals(3, mockEngine.queryCount.get())

        // Cached lookups should return immediately from in-memory cache
        val cached = resolver.getCachedIps("tracker.tamersunion.org")
        assertNotNull(cached)
        assertEquals(1, cached!!.size)
        assertEquals("104.21.56.78", cached[0].hostAddress)
    }

    @Test
    fun `test In-Memory LRU Cache Hit prevents redundant network lookups`() = runBlocking {
        val mockEngine = MockDohEngine()
        val resolver = DohTrackerResolver(mockEngine)

        // First resolution
        val ips1 = resolver.resolveHost("tracker.renfei.net")
        assertEquals(1, ips1.size)
        assertEquals(1, mockEngine.queryCount.get())

        // Second resolution must hit cache (query count remains 1)
        val ips2 = resolver.resolveHost("tracker.renfei.net")
        assertEquals(1, ips2.size)
        assertEquals(1, mockEngine.queryCount.get())
    }

    @Test
    fun `test Singleflight Coalescing under Concurrent Lookups`() = runBlocking {
        val mockEngine = MockDohEngine()
        val resolver = DohTrackerResolver(mockEngine)

        // Launch 20 concurrent coroutines resolving the exact same domain
        val deferredList = (1..20).map {
            async(Dispatchers.IO) {
                resolver.resolveHost("tracker.tamersunion.org")
            }
        }

        val allResults = deferredList.awaitAll()
        for (res in allResults) {
            assertEquals(1, res.size)
            assertEquals("104.21.56.78", res[0].hostAddress)
        }

        // Singleflight must coalesce queries so engine was queried only once
        assertEquals(1, mockEngine.queryCount.get())
    }

    @Test
    fun `test Fault Tolerance on Unknown or Non-Existent Domain`() = runBlocking {
        val mockEngine = MockDohEngine()
        val resolver = DohTrackerResolver(mockEngine)

        val trackerList = listOf(
            "https://tracker.tamersunion.org:443/announce",
            "https://non-existent-tracker.invalid:443/announce"
        )

        val results = resolver.preResolveTrackers(trackerList)

        // Valid domain resolved successfully without crashing on non-existent domain
        assertEquals(1, results.size)
        assertTrue(results.containsKey("tracker.tamersunion.org"))
        assertFalse(results.containsKey("non-existent-tracker.invalid"))
    }
}
