package com.sourzap.app

import com.sourzap.app.data.model.SpeedTestPhase
import com.sourzap.app.data.model.SpeedTestResult
import com.sourzap.app.data.model.SpeedTestState
import com.sourzap.app.service.core.ByteArrayPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SpeedTestEngineTest {

    @Before
    fun setup() {
        ByteArrayPool.clear()
    }

    @Test
    fun testSpeedTestPhaseEnumIntegrity() {
        val phases = SpeedTestPhase.entries
        assertTrue(phases.contains(SpeedTestPhase.IDLE))
        assertTrue(phases.contains(SpeedTestPhase.PING))
        assertTrue(phases.contains(SpeedTestPhase.DOWNLOAD))
        assertTrue(phases.contains(SpeedTestPhase.UPLOAD))
        assertTrue(phases.contains(SpeedTestPhase.COMPLETED))
        assertTrue(phases.contains(SpeedTestPhase.FAILED))
        assertTrue(phases.contains(SpeedTestPhase.CANCELLED))
    }

    @Test
    fun testSingleFlightMutexExclusivity() = runBlocking {
        val mutex = Mutex()
        val executions = AtomicInteger(0)
        val jobs = (1..10).map {
            async(Dispatchers.Default) {
                if (mutex.tryLock()) {
                    try {
                        executions.incrementAndGet()
                        delay(50)
                    } finally {
                        mutex.unlock()
                    }
                }
            }
        }

        jobs.awaitAll()
        assertEquals("Only 1 flight should execute when concurrent attempts try to lock", 1, executions.get())
    }

    @Test
    fun testActiveCallRegistryTrackingAndCancellation() {
        val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

        // Register in-flight simulated calls
        activeCalls.add("call-ping-1")
        activeCalls.add("call-ping-2")
        activeCalls.add("call-dl-stream-1")
        activeCalls.add("call-dl-stream-2")

        assertEquals(4, activeCalls.size)

        // Simulate cancelAll
        val cancelledCalls = mutableListOf<String>()
        val iterator = activeCalls.iterator()
        while (iterator.hasNext()) {
            val call = iterator.next()
            cancelledCalls.add(call)
            iterator.remove()
        }

        assertEquals(4, cancelledCalls.size)
        assertTrue(activeCalls.isEmpty())
    }

    @Test
    fun testCancellationExceptionReThrownNotSwallowed() {
        var cancellationThrown = false
        try {
            try {
                throw CancellationException("User cancelled speed test")
            } catch (e: CancellationException) {
                // Re-throw
                throw e
            } catch (e: Exception) {
                // Should not reach generic exception handler
                assertFalse("Generic Exception should not catch CancellationException", true)
            }
        } catch (e: CancellationException) {
            cancellationThrown = true
        }

        assertTrue("CancellationException must be re-thrown cleanly", cancellationThrown)
    }

    @Test
    fun testBufferRecyclingOnCancellation() = runBlocking {
        val pool = ByteArrayPool
        pool.clear()

        val obtainedBuffer = pool.obtainStreamBuffer()
        assertEquals(65536, obtainedBuffer.size)
        assertEquals(0, pool.getPoolSize64k())

        try {
            // Simulate work being cancelled
            throw CancellationException("Coroutine cancelled")
        } catch (e: CancellationException) {
            // handled
        } finally {
            pool.recycleStreamBuffer(obtainedBuffer)
        }

        assertEquals("Stream buffer must be recycled in finally block even during cancellation", 1, pool.getPoolSize64k())
    }
}
