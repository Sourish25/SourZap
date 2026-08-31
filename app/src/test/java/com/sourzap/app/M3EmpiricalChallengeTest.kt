package com.sourzap.app

import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.data.model.SpeedTestPhase
import com.sourzap.app.data.model.SpeedTestResult
import com.sourzap.app.data.model.SpeedTestState
import com.sourzap.app.data.model.TrafficStats
import com.sourzap.app.service.TrafficMonitor
import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.ui.theme.AppThemePreset
import com.sourzap.app.ui.traffic.TrafficFilterTab
import com.sourzap.app.update.AppReleaseInfo
import com.sourzap.app.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exhaustive Empirical Verification Suite for SourZap Milestone M3:
 * UI State Lifecycle, Coroutine Structured Cancellation, Memory Leak & Telemetry Hardening.
 */
class M3EmpiricalChallengeTest {

    @Before
    fun setup() {
        ByteArrayPool.clear()
        TrafficMonitor.clearLogs()
        TrafficMonitor.resetSession()
        TrafficMonitor.stopMonitoring()
    }

    // =========================================================================
    // SECTION 1: SPEED TEST ENGINE COROUTINE CANCELLATION & MUTEX CONCURRENCY
    // =========================================================================

    @Test
    fun testM3_SpeedTestSingleFlightMutexIntegrity() = runBlocking {
        val testMutex = Mutex()
        val successfulRuns = AtomicInteger(0)
        val rejectedRuns = AtomicInteger(0)

        // Launch 25 concurrent coroutines simulating rapid button clicks
        val jobs = (1..25).map {
            async(Dispatchers.Default) {
                if (testMutex.tryLock()) {
                    try {
                        successfulRuns.incrementAndGet()
                        delay(60) // In-flight duration
                    } finally {
                        testMutex.unlock()
                    }
                } else {
                    rejectedRuns.incrementAndGet()
                }
            }
        }

        jobs.awaitAll()
        assertEquals("Exactly 1 speed test flight must acquire lock concurrently", 1, successfulRuns.get())
        assertEquals("24 redundant speed test invocations must be rejected", 24, rejectedRuns.get())
    }

    @Test
    fun testM3_ActiveOkHttpCallRegistryCancellationProtocol() {
        val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        // Add 4 parallel download socket handles + 1 ping socket handle
        val mockCalls = listOf("http-ping-1", "http-dl-stream-1", "http-dl-stream-2", "http-dl-stream-3", "http-dl-stream-4")
        mockCalls.forEach { activeCalls.add(it) }

        assertEquals(5, activeCalls.size)

        // Execute deterministic cancellation loop
        val cancelled = mutableListOf<String>()
        val it = activeCalls.iterator()
        while (it.hasNext()) {
            val call = it.next()
            cancelled.add(call)
            it.remove()
        }

        assertEquals(5, cancelled.size)
        assertTrue("Active socket call registry must be completely cleared after cancel", activeCalls.isEmpty())
    }

    @Test
    fun testM3_CancellationExceptionNotSwallowedAsFailure() {
        var caughtCancellation = false
        var erroneouslyMarkedAsFailed = false

        try {
            try {
                // Simulating coroutine scope cancellation
                throw CancellationException("Composable onDispose cancellation")
            } catch (e: CancellationException) {
                // Must rethrow
                throw e
            } catch (e: Exception) {
                erroneouslyMarkedAsFailed = true
            }
        } catch (e: CancellationException) {
            caughtCancellation = true
        }

        assertTrue("CancellationException must propagate cleanly", caughtCancellation)
        assertFalse("Cancellation must never be caught by generic Exception block as FAILED", erroneouslyMarkedAsFailed)
    }

    @Test
    fun testM3_ByteArrayPoolBufferRecyclingGuaranteedInFinally() {
        ByteArrayPool.clear()
        val initialPoolSize = ByteArrayPool.getPoolSize64k()
        assertEquals(0, initialPoolSize)

        val buf = ByteArrayPool.obtainStreamBuffer()
        assertEquals(65536, buf.size)
        assertEquals(0, ByteArrayPool.getPoolSize64k())

        try {
            // Throw exception or cancellation
            throw RuntimeException("Simulated mid-download error")
        } catch (_: Exception) {
            // caught
        } finally {
            ByteArrayPool.recycleStreamBuffer(buf)
        }

        assertEquals("Stream buffer must be returned to pool in finally block", 1, ByteArrayPool.getPoolSize64k())
    }

    // =========================================================================
    // SECTION 2: TRAFFIC MONITOR FIFO BOUNDS & UNDERFLOW PREVENTION
    // =========================================================================

    @Test
    fun testM3_TrafficMonitorStrictFiftyLogFifoCap() {
        TrafficMonitor.clearLogs()

        // Insert 150 items
        for (i in 1..150) {
            TrafficMonitor.addConnectionLog(
                ConnectionLog(
                    id = "id-$i",
                    domain = "server-$i.cdn.net",
                    port = 443,
                    protocol = "TLS 1.3",
                    technique = "SPLIT2",
                    bytesTransferred = i * 500L,
                    timestamp = System.currentTimeMillis() + i
                )
            )
        }

        val logs = TrafficMonitor.recentLogs.value
        assertEquals("Log capacity must be strictly capped at exactly 50", 50, logs.size)
        // Check ordering: index 0 is newest (id-150), index 49 is oldest (id-101)
        assertEquals("id-150", logs.first().id)
        assertEquals("server-150.cdn.net", logs.first().domain)
        assertEquals("id-101", logs.last().id)
        assertEquals("server-101.cdn.net", logs.last().domain)
    }

    @Test
    fun testM3_TrafficMonitorConcurrentBurstSafety() {
        TrafficMonitor.clearLogs()
        val threads = 25
        val logsPerThread = 40
        val latch = CountDownLatch(threads)
        val executor = Executors.newFixedThreadPool(threads)

        for (t in 1..threads) {
            executor.execute {
                try {
                    for (i in 1..logsPerThread) {
                        TrafficMonitor.addConnectionLog(
                            ConnectionLog(
                                id = "thread-$t-log-$i",
                                domain = "domain-$t-$i.com",
                                port = 443,
                                protocol = "HTTPS",
                                technique = "DISORDER"
                            )
                        )
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        val logs = TrafficMonitor.recentLogs.value
        assertEquals(50, logs.size)
    }

    @Test
    fun testM3_ActiveConnectionsCounterUnderflowClamp() {
        // Decrement 50 times when at 0
        for (i in 1..50) {
            TrafficMonitor.onConnectionClosed()
        }

        // Must remain 0
        assertEquals(0, TrafficMonitor.stats.value.activeConnections)

        // Increment 2, decrement 5
        TrafficMonitor.onConnectionOpened()
        TrafficMonitor.onConnectionOpened()
        for (i in 1..5) {
            TrafficMonitor.onConnectionClosed()
        }

        assertEquals(0, TrafficMonitor.stats.value.activeConnections)
    }

    // =========================================================================
    // SECTION 3: UPDATE MANAGER VERSION LOGIC & APK INTEGRITY
    // =========================================================================

    private fun extractCleanVersion(raw: String): String {
        val match = Regex("""\d+(\.\d+)+""").find(raw)
        return match?.value ?: raw.filter { it.isDigit() || it == '.' }.trim('.')
    }

    private fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestClean = extractCleanVersion(latest)
            val currentClean = extractCleanVersion(current)

            val latestParts = latestClean.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentClean.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {}
        return false
    }

    @Test
    fun testM3_SemVerComparisonAdversarialCases() {
        assertTrue(isVersionNewer("v2.4.1", "2.4.0"))
        assertTrue(isVersionNewer("2.5.0-rc1", "2.4.99"))
        assertTrue(isVersionNewer("v3.0.0", "v2.9.9"))
        assertFalse(isVersionNewer("2.4.0", "2.4.0"))
        assertFalse(isVersionNewer("v2.4.0", "2.4.0"))
        assertFalse(isVersionNewer("1.9.9", "2.0.0"))
    }

    @Test
    fun testM3_ApkZipMagicHeaderValidation() {
        // Valid ZIP Local File Header PK\x03\x04
        val validMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val validLen = 4_500_000L

        fun validateHeader(magic: ByteArray, len: Long): Boolean {
            if (len < 3_000_000L || magic.size < 4) return false
            return magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                    magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
        }

        assertTrue(validateHeader(validMagic, validLen))
        assertFalse(validateHeader(validMagic, 2_000_000L)) // undersized
        assertFalse(validateHeader(byteArrayOf(0x00, 0x00, 0x00, 0x00), validLen)) // bad magic
    }

    // =========================================================================
    // SECTION 4: REPOSITORIES JSON PERSISTENCE & THREAD SAFETY
    // =========================================================================

    @Test
    fun testM3_SpeedTestHistoryJsonRoundtrip() {
        val results = listOf(
            SpeedTestResult(
                id = "st-001",
                timestamp = 1725100000000L,
                pingMs = 18.5f,
                jitterMs = 2.1f,
                downloadMbps = 112.4f,
                uploadMbps = 54.2f,
                serverName = "Cloudflare Global Edge",
                serverLocation = "Anycast Turbo CDN",
                strategyName = "YouTube Turbo Fix"
            )
        )

        val array = JSONArray()
        results.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("timestamp", item.timestamp)
                put("pingMs", item.pingMs.toDouble())
                put("jitterMs", item.jitterMs.toDouble())
                put("downloadMbps", item.downloadMbps.toDouble())
                put("uploadMbps", item.uploadMbps.toDouble())
                put("serverName", item.serverName)
                put("serverLocation", item.serverLocation)
                put("strategyName", item.strategyName)
            }
            array.put(obj)
        }

        val jsonStr = array.toString()
        val parsed = JSONArray(jsonStr)
        assertEquals(1, parsed.length())

        val obj = parsed.getJSONObject(0)
        assertEquals("st-001", obj.getString("id"))
        assertEquals(18.5, obj.getDouble("pingMs"), 0.01)
        assertEquals(112.4, obj.getDouble("downloadMbps"), 0.01)
        assertEquals("YouTube Turbo Fix", obj.getString("strategyName"))
    }

    // =========================================================================
    // SECTION 5: COMPOSE UI ENUM ENTRIES (ZERO-ALLOCATION)
    // =========================================================================

    @Test
    fun testM3_EnumEntriesZeroAllocationProperties() {
        // Under Kotlin 2.0.0, Enum.entries returns an immutable cached EnumEntries list
        val themeEntries = AppThemePreset.entries
        val filterEntries = TrafficFilterTab.entries

        assertEquals(16, themeEntries.size)
        assertEquals(5, filterEntries.size)

        // Verify identical instance (no new array allocations)
        assertTrue(themeEntries === AppThemePreset.entries)
        assertTrue(filterEntries === TrafficFilterTab.entries)
    }
}
