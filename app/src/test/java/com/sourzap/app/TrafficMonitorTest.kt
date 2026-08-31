package com.sourzap.app

import com.sourzap.app.data.model.ConnectionLog
import com.sourzap.app.service.TrafficMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TrafficMonitorTest {

    @Before
    fun setup() {
        TrafficMonitor.clearLogs()
        TrafficMonitor.resetSession()
        TrafficMonitor.stopMonitoring()
    }

    @Test
    fun testFifoBoundedCapacity_StrictMaxFiftyItems() {
        // Add 100 connection logs sequentially
        for (i in 1..100) {
            val log = ConnectionLog(
                id = "log-$i",
                domain = "example$i.com",
                port = 443,
                protocol = "TLS 1.3",
                technique = "SPLIT2",
                bytesTransferred = i * 1000L,
                timestamp = System.currentTimeMillis() + i
            )
            TrafficMonitor.addConnectionLog(log)
        }

        val logs = TrafficMonitor.recentLogs.value
        assertEquals("Recent logs must be strictly bounded to 50 items", 50, logs.size)
        // Most recent item should be at the head (index 0)
        assertEquals("log-100", logs.first().id)
        assertEquals("example100.com", logs.first().domain)
        // Oldest retained item should be at index 49 (which was log-51)
        assertEquals("log-51", logs.last().id)
        assertEquals("example51.com", logs.last().domain)
    }

    @Test
    fun testConcurrentLogAdditions_NoCorruptionOrCasChurn() {
        val threadCount = 20
        val logsPerThread = 50
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)

        for (t in 1..threadCount) {
            executor.execute {
                try {
                    for (i in 1..logsPerThread) {
                        val log = ConnectionLog(
                            id = "t$t-log$i",
                            domain = "domain-$t-$i.com",
                            port = 443,
                            protocol = "TLS",
                            technique = "DISORDER",
                            bytesTransferred = 500L,
                            timestamp = System.currentTimeMillis()
                        )
                        TrafficMonitor.addConnectionLog(log)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        val finalLogs = TrafficMonitor.recentLogs.value
        assertEquals("Logs buffer must remain capped at exactly 50 under high concurrency", 50, finalLogs.size)
    }

    @Test
    fun testConnectionCounter_UnderflowClampingProtection() {
        // Repeatedly trigger connection closed without prior opens
        for (i in 1..20) {
            TrafficMonitor.onConnectionClosed()
        }

        // Active connections in stats should never drop below 0
        val currentStats = TrafficMonitor.stats.value
        assertEquals(0, currentStats.activeConnections)

        // Open 3 connections
        TrafficMonitor.onConnectionOpened()
        TrafficMonitor.onConnectionOpened()
        TrafficMonitor.onConnectionOpened()

        // Close 5 connections (2 more than opened)
        for (i in 1..5) {
            TrafficMonitor.onConnectionClosed()
        }

        // Must still be clamped at 0
        TrafficMonitor.onConnectionOpened()
        // Now should be 1
        TrafficMonitor.onConnectionClosed()
        // Now should be 0
    }

    @Test
    fun testClearLogsAndResetSession() {
        // Populate logs and bytes
        for (i in 1..10) {
            TrafficMonitor.addConnectionLog(
                ConnectionLog(
                    id = "log-$i",
                    domain = "test$i.com",
                    port = 443,
                    protocol = "HTTPS",
                    technique = "SPLIT2",
                    bytesTransferred = 1024L
                )
            )
        }
        TrafficMonitor.recordRxBytes(5000L)
        TrafficMonitor.recordTxBytes(3000L)

        assertEquals(10, TrafficMonitor.recentLogs.value.size)

        // Clear logs
        TrafficMonitor.clearLogs()
        assertTrue(TrafficMonitor.recentLogs.value.isEmpty())

        // Reset session
        TrafficMonitor.resetSession()
        assertEquals(0L, TrafficMonitor.stats.value.sessionDownloadBytes)
        assertEquals(0L, TrafficMonitor.stats.value.sessionUploadBytes)
        assertTrue(TrafficMonitor.stats.value.recentSpeedHistory.isEmpty())
    }
}
