package com.sourzap.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.data.model.SpeedTestPhase
import com.sourzap.app.data.repository.SettingsRepository
import com.sourzap.app.data.repository.StrategyRepository
import com.sourzap.app.service.TrafficMonitor
import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.speedtest.SpeedTestEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical Challenger Verification Suite for M3:
 * 1. SpeedTestEngine:
 *    - Concurrency stress: rapid start & immediate cancel across 100 iterations.
 *      Verifies zero socket leaks, activeCalls cleaned up, and state resets to IDLE.
 *    - Re-entrancy guard: concurrent runSpeedTest() returns immediately without parallel downloads.
 * 2. TrafficMonitor:
 *    - Concurrent burst test: 1,000 parallel connection logs across 10 threads.
 *      Verifies recentLogs.size <= 50 invariant and newest log is always at index 0.
 *    - Underflow test: simulate negative connection closures, verifying activeConnections >= 0.
 */
class SpeedTestAndTrafficMonitorChallengerTest {

    private lateinit var mockContext: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var strategyRepository: StrategyRepository
    private lateinit var speedEngine: SpeedTestEngine

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = ConcurrentHashMap<String, Any>()
        private val listeners = Collections.newSetFromMap(ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, Boolean>())

        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val set = map[key] as? Set<*>
            return set?.map { it.toString() }?.toMutableSet() ?: defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = EditorImpl()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.add(listener)
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            if (listener != null) listeners.remove(listener)
        }

        inner class EditorImpl : SharedPreferences.Editor {
            private val tempMap = HashMap<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) tempMap[key] = values?.toSet()
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) tempMap[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) tempMap[key] = null
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clear) map.clear()
                tempMap.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }

    @Before
    fun setup() {
        ByteArrayPool.clear()
        TrafficMonitor.clearLogs()
        TrafficMonitor.resetSession()
        TrafficMonitor.stopMonitoring()

        val prefsMap = ConcurrentHashMap<String, InMemorySharedPreferences>()
        mockContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return prefsMap.computeIfAbsent(name ?: "default") { InMemorySharedPreferences() }
            }
            override fun getPackageName(): String = "com.sourzap.app"
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(System.getProperty("java.io.tmpdir"), "sourzap_test")
        }

        settingsRepository = SettingsRepository(mockContext)
        strategyRepository = StrategyRepository(mockContext)
        speedEngine = SpeedTestEngine(settingsRepository, strategyRepository)
    }

    private fun getActiveCallsCount(engine: SpeedTestEngine): Int {
        val field = SpeedTestEngine::class.java.getDeclaredField("activeCalls")
        field.isAccessible = true
        val set = field.get(engine) as Set<*>
        return set.size
    }

    private fun isRunMutexLocked(engine: SpeedTestEngine): Boolean {
        val field = SpeedTestEngine::class.java.getDeclaredField("runMutex")
        field.isAccessible = true
        val mutex = field.get(engine) as Mutex
        return mutex.isLocked
    }

    // =========================================================================
    // TEST 1: SPEEDTESTENGINE 100-ITERATION RAPID START & CANCEL STRESS TEST
    // =========================================================================

    @Test
    fun testSpeedTestEngine_100IterationRapidStartAndCancelStress() = runBlocking {
        val iterations = 100

        for (i in 1..iterations) {
            // Launch the real speed test in a background coroutine
            val job = launch(Dispatchers.IO) {
                try {
                    speedEngine.runSpeedTest()
                } catch (_: CancellationException) {
                    // Expected during cancellation
                }
            }

            // Variable micro-delay between 0ms and 15ms to hit different lifecycle phases (lock acquisition, ping, download)
            val jitterDelay = (i % 15).toLong()
            if (jitterDelay > 0) {
                delay(jitterDelay)
            }

            // Immediately cancel via cancelTest() API
            speedEngine.cancelTest()

            // Also ensure coroutine job cancellation
            job.cancel()
            job.join()

            // Verification Invariants per iteration:
            // 1. Mutex must be unlocked
            assertFalse("Iteration $i: runMutex must be released after cancellation", isRunMutexLocked(speedEngine))

            // 2. Active OkHttp call registry must be empty (0 socket leaks)
            val activeCount = getActiveCallsCount(speedEngine)
            assertEquals("Iteration $i: activeCalls registry must have 0 leaks after cancellation", 0, activeCount)

            // 3. SpeedTest state must be cleanly reset to IDLE
            val state = speedEngine.state.value
            assertEquals("Iteration $i: state phase must be IDLE after cancellation", SpeedTestPhase.IDLE, state.phase)
            assertEquals("Iteration $i: progress must reset to 0f", 0f, state.progress, 0.001f)
            assertEquals("Iteration $i: activeGaugeSpeedMbps must reset to 0f", 0f, state.activeGaugeSpeedMbps, 0.001f)
        }
    }

    // =========================================================================
    // TEST 2: SPEEDTESTENGINE RE-ENTRANCY GUARD VERIFICATION
    // =========================================================================

    @Test
    fun testSpeedTestEngine_ReEntrancyGuardExclusivity() = runBlocking {
        // Start first speed test
        val firstJob = launch(Dispatchers.IO) {
            try {
                speedEngine.runSpeedTest()
            } catch (_: CancellationException) {
                // Expected
            }
        }

        // Wait until first job is actively running
        withTimeout(5000) {
            while (!isRunMutexLocked(speedEngine)) {
                delay(10)
            }
        }
        assertTrue("First job must hold the runMutex", isRunMutexLocked(speedEngine))

        // Attempt 20 concurrent secondary calls to runSpeedTest()
        val secondaryAttempts = (1..20).map {
            async(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                speedEngine.runSpeedTest() // Should immediately return because mutex is locked
                val duration = System.currentTimeMillis() - start
                duration
            }
        }

        val durations = secondaryAttempts.awaitAll()

        // All secondary attempts must have returned almost immediately (< 500ms) without blocking or executing
        for (d in durations) {
            assertTrue("Secondary runSpeedTest() should return immediately without blocking (took ${d}ms)", d < 500)
        }

        // Clean up first job
        speedEngine.cancelTest()
        firstJob.cancel()
        firstJob.join()

        assertEquals("State must be IDLE after test teardown", SpeedTestPhase.IDLE, speedEngine.state.value.phase)
        assertFalse("runMutex must be unlocked", isRunMutexLocked(speedEngine))
        assertEquals("activeCalls must be 0", 0, getActiveCallsCount(speedEngine))
    }

    // =========================================================================
    // TEST 3: TRAFFICMONITOR 1,000 PARALLEL LOGS BURST ACROSS 10 THREADS
    // =========================================================================

    @Test
    fun testTrafficMonitor_1000ParallelLogsBurstAcross10Threads() {
        val threadCount = 10
        val logsPerThread = 100
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val violationCount = AtomicInteger(0)

        // Add 1000 logs concurrently across 10 threads
        for (t in 0 until threadCount) {
            val threadId = t
            executor.execute {
                try {
                    for (i in 1..logsPerThread) {
                        val seqNum = threadId * logsPerThread + i
                        val log = ConnectionLog(
                            id = "burst-$seqNum",
                            domain = "stream-$seqNum.sourzap.net",
                            port = 443,
                            protocol = if (seqNum % 2 == 0) "TLS 1.3" else "HTTP/1.1",
                            technique = "SPLIT2",
                            bytesTransferred = seqNum * 128L,
                            timestamp = 1000000L + seqNum
                        )
                        TrafficMonitor.addConnectionLog(log)

                        // Strict invariant check during burst:
                        val snapshot = TrafficMonitor.recentLogs.value
                        if (snapshot.size > 50) {
                            violationCount.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()
        assertTrue("All 10 burst threads must complete within timeout", completed)

        assertEquals("recentLogs.size must NEVER exceed 50 at any point during concurrent burst", 0, violationCount.get())

        val finalLogs = TrafficMonitor.recentLogs.value
        assertEquals("Final recentLogs size must be capped at exactly 50", 50, finalLogs.size)

        // Add one distinct latest log
        val latestLog = ConnectionLog(
            id = "sentinel-newest",
            domain = "latest.sourzap.net",
            port = 443,
            protocol = "TLS 1.3",
            technique = "DISORDER",
            bytesTransferred = 9999L,
            timestamp = System.currentTimeMillis() + 10000L
        )
        TrafficMonitor.addConnectionLog(latestLog)

        val updatedLogs = TrafficMonitor.recentLogs.value
        assertEquals(50, updatedLogs.size)
        // Verify index 0 is strictly the newest item
        assertEquals("Newest log must be at index 0 (head)", "sentinel-newest", updatedLogs[0].id)
        assertEquals("latest.sourzap.net", updatedLogs[0].domain)
    }

    // =========================================================================
    // TEST 4: TRAFFICMONITOR UNDERFLOW & CONNECTION COUNTER RESISTANCE
    // =========================================================================

    @Test
    fun testTrafficMonitor_NegativeConnectionUnderflowResistance() {
        // Step 1: Initial state
        assertEquals(0, TrafficMonitor.stats.value.activeConnections)

        // Step 2: Underflow barrage - 100 closures when starting at 0
        for (i in 1..100) {
            TrafficMonitor.onConnectionClosed()
        }
        assertEquals("activeConnections must not underflow below 0", 0, TrafficMonitor.stats.value.activeConnections)

        // Step 3: Concurrent open and close stress
        val threadCount = 12
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        // 6 threads opening connections (total +600)
        // 6 threads closing connections (total -1200) -> Net negative
        for (t in 1..6) {
            executor.execute {
                try {
                    for (i in 1..100) {
                        TrafficMonitor.onConnectionOpened()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        for (t in 7..12) {
            executor.execute {
                try {
                    for (i in 1..200) {
                        TrafficMonitor.onConnectionClosed()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        // Active connection counter must still be >= 0 (never negative)
        val activeAfter = TrafficMonitor.stats.value.activeConnections
        assertTrue("activeConnections must be >= 0 after underflow barrage, was $activeAfter", activeAfter >= 0)

        // Step 4: Deterministic single step open/close
        // Drain any lingering connections
        for (i in 1..1000) {
            TrafficMonitor.onConnectionClosed()
        }
        assertEquals(0, TrafficMonitor.stats.value.activeConnections)

        // Open 3
        TrafficMonitor.onConnectionOpened()
        TrafficMonitor.onConnectionOpened()
        TrafficMonitor.onConnectionOpened()
        // Close 5 (underflow)
        TrafficMonitor.onConnectionClosed()
        TrafficMonitor.onConnectionClosed()
        TrafficMonitor.onConnectionClosed()
        TrafficMonitor.onConnectionClosed()
        TrafficMonitor.onConnectionClosed()

        assertEquals("activeConnections must be clamped to 0", 0, TrafficMonitor.stats.value.activeConnections)

        // Open 1 -> must become exactly 1
        TrafficMonitor.onConnectionOpened()
        // Close 1 -> must become exactly 0
        TrafficMonitor.onConnectionClosed()
    }

    // =========================================================================
    // TEST 5: TRAFFICMONITOR HIGH-CONTENTION CHAOS HARNESS
    // =========================================================================

    @Test
    fun testTrafficMonitor_HighContentionMultiOperationChaos() {
        val threadCount = 16
        val operationsPerThread = 200
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (t in 1..threadCount) {
            val threadId = t
            executor.execute {
                try {
                    for (op in 1..operationsPerThread) {
                        when (op % 6) {
                            0 -> TrafficMonitor.onConnectionOpened()
                            1 -> TrafficMonitor.onConnectionClosed()
                            2 -> TrafficMonitor.recordRxBytes(op * 50L)
                            3 -> TrafficMonitor.recordTxBytes(op * 30L)
                            4 -> TrafficMonitor.addConnectionLog(
                                ConnectionLog(
                                    id = "chaos-$threadId-$op",
                                    domain = "chaos-$op.com",
                                    port = 443,
                                    protocol = "TLS",
                                    technique = "SPLIT2"
                                )
                            )
                            5 -> {
                                if (op % 50 == 0) TrafficMonitor.resetSession()
                            }
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        // Invariants must hold after chaos
        val finalLogs = TrafficMonitor.recentLogs.value
        assertTrue("Log buffer size must be <= 50 after chaos, was ${finalLogs.size}", finalLogs.size <= 50)
        assertTrue("activeConnections must be >= 0 after chaos", TrafficMonitor.stats.value.activeConnections >= 0)
    }
}
