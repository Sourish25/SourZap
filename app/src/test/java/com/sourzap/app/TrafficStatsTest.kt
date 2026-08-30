package com.sourzap.app

import com.sourzap.app.data.model.SpeedTestPhase
import com.sourzap.app.data.model.SpeedTestResult
import com.sourzap.app.data.model.SpeedTestState
import com.sourzap.app.data.model.TrafficStats
import com.sourzap.app.service.core.ByteArrayPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class TrafficStatsTest {

    @Test
    fun testFormatSpeed_Gbps() {
        // 1 Gbps = 125,000,000 bytes/s
        val oneGbps = 125_000_000L
        assertEquals("1.00 Gbps", TrafficStats.formatSpeed(oneGbps))

        // 2.5 Gbps = 312,500,000 bytes/s
        val twoPointFiveGbps = 312_500_000L
        assertEquals("2.50 Gbps", TrafficStats.formatSpeed(twoPointFiveGbps))

        // 10 Gbps = 1,250,000,000 bytes/s
        val tenGbps = 1_250_000_000L
        assertEquals("10.00 Gbps", TrafficStats.formatSpeed(tenGbps))

        // Extreme Long.MAX_VALUE speed
        val maxSpeed = TrafficStats.formatSpeed(Long.MAX_VALUE / 8)
        assertTrue(maxSpeed.endsWith("Gbps"))
    }

    @Test
    fun testFormatSpeed_Mbps() {
        // 100 Mbps = 12,500,000 bytes/s
        val hundredMbps = 12_500_000L
        assertEquals("100.0 Mbps", TrafficStats.formatSpeed(hundredMbps))

        // 1.5 Mbps = 187,500 bytes/s
        val onePointFiveMbps = 187_500L
        assertEquals("1.5 Mbps", TrafficStats.formatSpeed(onePointFiveMbps))

        // 54.2 Mbps = 6,775,000 bytes/s
        val speed54Mbps = 6_775_000L
        assertEquals("54.2 Mbps", TrafficStats.formatSpeed(speed54Mbps))
    }

    @Test
    fun testFormatSpeed_Kbps() {
        // 500 Kbps = 62,500 bytes/s
        val fiveHundredKbps = 62_500L
        assertEquals("500 Kbps", TrafficStats.formatSpeed(fiveHundredKbps))

        // 1 Kbps = 125 bytes/s
        val oneKbps = 125L
        assertEquals("1 Kbps", TrafficStats.formatSpeed(oneKbps))
    }

    @Test
    fun testFormatSpeed_BpsAndZero() {
        val zeroBytes = 0L
        assertEquals(" bps", TrafficStats.formatSpeed(zeroBytes))

        val smallBytes = 50L // 400 bits < 1000
        assertEquals(" bps", TrafficStats.formatSpeed(smallBytes))
    }

    @Test
    fun testFormatBytes_GB() {
        val oneGB = 1_073_741_824L
        assertEquals("1.00 GB", TrafficStats.formatBytes(oneGB))

        val twoPointFiveGB = 2_684_354_560L
        assertEquals("2.50 GB", TrafficStats.formatBytes(twoPointFiveGB))

        val maxBytes = TrafficStats.formatBytes(Long.MAX_VALUE)
        assertTrue(maxBytes.endsWith("GB"))
    }

    @Test
    fun testFormatBytes_MB() {
        val oneMB = 1_048_576L
        assertEquals("1.0 MB", TrafficStats.formatBytes(oneMB))

        val fiftyMB = 52_428_800L
        assertEquals("50.0 MB", TrafficStats.formatBytes(fiftyMB))

        val nearGB = 1_073_741_823L
        assertEquals("1024.0 MB", TrafficStats.formatBytes(nearGB))
    }

    @Test
    fun testFormatBytes_KB() {
        val oneKB = 1024L
        assertEquals("1 KB", TrafficStats.formatBytes(oneKB))

        val fiveHundredKB = 512_000L
        assertEquals("500 KB", TrafficStats.formatBytes(fiveHundredKB))

        val nearMB = 1_048_575L
        assertEquals("1024 KB", TrafficStats.formatBytes(nearMB))
    }

    @Test
    fun testFormatBytes_B() {
        val zeroB = 0L
        assertEquals(" B", TrafficStats.formatBytes(zeroB))

        val fiveHundredB = 500L
        assertEquals(" B", TrafficStats.formatBytes(fiveHundredB))

        val nearKB = 1023L
        assertEquals(" B", TrafficStats.formatBytes(nearKB))
    }

    @Test
    fun testTrafficStatsModelMethods() {
        val stats = TrafficStats(
            downloadSpeedBps = 12_500_000L, // 100 Mbps
            uploadSpeedBps = 187_500L,       // 1.5 Mbps
            sessionDownloadBytes = 52_428_800L, // 50 MB
            sessionUploadBytes = 1_048_576L,    // 1 MB
            totalDownloadBytes = 2_684_354_560L, // 2.5 GB
            totalUploadBytes = 536_870_912L,     // 512 MB
            activeConnections = 12,
            totalPacketsProcessed = 98450L,
            packetsPerSecond = 120
        )

        assertEquals("100.0 Mbps", stats.formattedDownloadSpeed())
        assertEquals("1.5 Mbps", stats.formattedUploadSpeed())
        assertEquals("50.0 MB", stats.formattedSessionDownload())
        assertEquals("1.0 MB", stats.formattedSessionUpload())
        assertEquals("2.50 GB", stats.formattedTotalDownload())
        assertEquals(12, stats.activeConnections)
        assertEquals(98450L, stats.totalPacketsProcessed)
        assertEquals(120, stats.packetsPerSecond)
    }

    @Test
    fun testPingAndJitterCalculation() {
        val pingSamples = listOf(14.2f, 16.8f, 15.0f, 18.2f)
        val avgPing = pingSamples.average().toFloat()
        assertEquals(16.05f, avgPing, 0.01f)

        // Jitter: mean of absolute differences between consecutive samples
        var diffSum = 0f
        for (i in 0 until pingSamples.size - 1) {
            diffSum += Math.abs(pingSamples[i] - pingSamples[i + 1])
        }
        val jitter = diffSum / (pingSamples.size - 1)
        // Diffs: |14.2 - 16.8| = 2.6, |16.8 - 15.0| = 1.8, |15.0 - 18.2| = 3.2. Sum = 7.6. / 3 = 2.533
        assertEquals(2.533f, jitter, 0.01f)

        // Constant ping -> jitter 0
        val constPings = listOf(20.0f, 20.0f, 20.0f, 20.0f)
        var constDiff = 0f
        for (i in 0 until constPings.size - 1) {
            constDiff += Math.abs(constPings[i] - constPings[i + 1])
        }
        val constJitter = constDiff / (constPings.size - 1)
        assertEquals(0.0f, constJitter, 0.001f)
    }

    @Test
    fun testSpeedTestResultAndPhases() {
        val result = SpeedTestResult(
            pingMs = 12.5f,
            jitterMs = 1.2f,
            downloadMbps = 185.4f,
            uploadMbps = 82.1f,
            serverName = "Cloudflare Global Edge",
            serverLocation = "Anycast CDN",
            strategyName = "Universal Smart Engine"
        )

        assertEquals(12.5f, result.pingMs, 0.01f)
        assertEquals(185.4f, result.downloadMbps, 0.01f)
        assertNotNull(result.formattedDate())

        val state = SpeedTestState(
            phase = SpeedTestPhase.DOWNLOAD,
            currentDownloadMbps = 150.0f,
            progress = 0.5f,
            statusMessage = "Testing download"
        )

        assertEquals(SpeedTestPhase.DOWNLOAD, state.phase)
        assertEquals(150.0f, state.currentDownloadMbps, 0.01f)
    }

    @Test
    fun testByteArrayPoolMechanics() {
        ByteArrayPool.clear()

        // 1. Stream buffers (64 KB)
        val b1 = ByteArrayPool.obtainStreamBuffer()
        assertEquals(ByteArrayPool.BUFFER_SIZE, b1.size)
        assertEquals(ByteArrayPool.BUFFER_64K, b1.size)
        assertEquals(65536, b1.size)

        ByteArrayPool.recycleStreamBuffer(b1)
        assertEquals(1, ByteArrayPool.getPoolSize64k())
        val b2 = ByteArrayPool.obtainStreamBuffer()
        assertEquals(ByteArrayPool.BUFFER_64K, b2.size)
        assertEquals(0, ByteArrayPool.getPoolSize64k())

        // Discard invalid buffer sizes
        val invalidBuf = ByteArray(1024)
        ByteArrayPool.recycleStreamBuffer(invalidBuf)
        assertEquals(0, ByteArrayPool.getPoolSize64k())

        // 2. Packet buffers (32 KB)
        val p1 = ByteArrayPool.obtainPacketBuffer()
        assertEquals(ByteArrayPool.PACKET_BUFFER_SIZE, p1.size)
        assertEquals(ByteArrayPool.BUFFER_32K, p1.size)
        assertEquals(32768, p1.size)

        ByteArrayPool.recyclePacketBuffer(p1)
        assertEquals(1, ByteArrayPool.getPoolSize32k())
        val p2 = ByteArrayPool.obtainPacketBuffer()
        assertEquals(ByteArrayPool.BUFFER_32K, p2.size)
        assertEquals(0, ByteArrayPool.getPoolSize32k())

        // 3. 16 KB Handshake buffers
        val h1 = ByteArrayPool.obtain16kBuffer()
        assertEquals(ByteArrayPool.BUFFER_16K, h1.size)
        assertEquals(16384, h1.size)

        ByteArrayPool.recycle16kBuffer(h1)
        assertEquals(1, ByteArrayPool.getPoolSize16k())
        val h2 = ByteArrayPool.obtain16kBuffer()
        assertEquals(ByteArrayPool.BUFFER_16K, h2.size)
        assertEquals(0, ByteArrayPool.getPoolSize16k())

        // 4. 4 KB Small / UDP buffers
        val s1 = ByteArrayPool.obtainSmallBuffer()
        assertEquals(ByteArrayPool.BUFFER_4K, s1.size)
        assertEquals(4096, s1.size)

        ByteArrayPool.recycleSmallBuffer(s1)
        assertEquals(1, ByteArrayPool.getPoolSize4k())
        val s2 = ByteArrayPool.obtainSmallBuffer()
        assertEquals(ByteArrayPool.BUFFER_4K, s2.size)
        assertEquals(0, ByteArrayPool.getPoolSize4k())

        // 5. Dynamic size routing
        val dyn4k = ByteArrayPool.obtain(2048)
        assertEquals(ByteArrayPool.BUFFER_4K, dyn4k.size)
        val dyn16k = ByteArrayPool.obtain(12000)
        assertEquals(ByteArrayPool.BUFFER_16K, dyn16k.size)
        val dyn32k = ByteArrayPool.obtain(25000)
        assertEquals(ByteArrayPool.BUFFER_32K, dyn32k.size)
        val dyn64k = ByteArrayPool.obtain(50000)
        assertEquals(ByteArrayPool.BUFFER_64K, dyn64k.size)

        ByteArrayPool.recycle(dyn4k)
        ByteArrayPool.recycle(dyn16k)
        ByteArrayPool.recycle(dyn32k)
        ByteArrayPool.recycle(dyn64k)

        assertEquals(1, ByteArrayPool.getPoolSize4k())
        assertEquals(1, ByteArrayPool.getPoolSize16k())
        assertEquals(1, ByteArrayPool.getPoolSize32k())
        assertEquals(1, ByteArrayPool.getPoolSize64k())

        ByteArrayPool.clear()
        assertEquals(0, ByteArrayPool.getPoolSize4k())
        assertEquals(0, ByteArrayPool.getPoolSize16k())
        assertEquals(0, ByteArrayPool.getPoolSize32k())
        assertEquals(0, ByteArrayPool.getPoolSize64k())
    }

    @Test
    fun testSpeedHistoryWaveBuffer() {
        val speedHistory = ArrayDeque<Float>(25)

        // Add 25 speed points (simulate 25 seconds)
        for (i in 1..25) {
            val rxSpeed = 1_000_000L // ~8 Mbps
            val txSpeed = 200_000L   // ~1.6 Mbps
            val speedKbps = ((rxSpeed + txSpeed) * 8f) / 1000f

            if (speedHistory.size >= 20) {
                speedHistory.removeFirst()
            }
            speedHistory.addLast(speedKbps)
        }

        assertEquals("Speed history wave buffer must maintain a max of 20 elements", 20, speedHistory.size)
        assertEquals(9600f, speedHistory.first(), 0.01f)

        // Heavy burst of 10,000 insertions
        for (i in 1..10000) {
            if (speedHistory.size >= 20) {
                speedHistory.removeFirst()
            }
            speedHistory.addLast(i.toFloat())
        }
        assertEquals(20, speedHistory.size)
        assertEquals(10000f, speedHistory.last(), 0.001f)
    }
}
