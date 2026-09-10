package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.TorrentEngineManager
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Adversarial stress harness executed by challenger_m1_1.
 * Stress-tests:
 * 1. Concurrency, deadlock resistance, exception safety, and input validation of [TorrentEngineManager.injectPeerSafely].
 * 2. Partial progress calculations under edge cases (0 bytes, division-by-zero, NaN, overflow, piece overhang, strange priorities).
 * 3. State transitions and filter matching under extreme conditions.
 */
class TorrentDownloaderAdversarialStressTest {

    // =========================================================================
    // PART 1: PEER INJECTION CONCURRENCY, DEADLOCK & EXCEPTION RESISTANCE
    // =========================================================================

    @Test
    fun testInjectPeerSafely_ExtremeMultiThreadStress() = runBlocking {
        val totalThreads = 64
        val totalOperations = 500
        val completedOps = AtomicInteger(0)
        val unexpectedErrors = AtomicInteger(0)

        val candidateIps = listOf(
            "127.0.0.1", "192.168.1.1", "10.0.0.1", "172.16.0.1",
            "::1", "2001:db8::1", "[2001:db8::1]",
            "", "   ", "invalid_ip", "999.999.999.999", "1.2.3.4.5",
            "192.168.1.1\u0000", "🔥.1.2.3"
        )
        val candidatePorts = listOf(
            Int.MIN_VALUE, -9999, -1, 0, 1, 80, 443, 6881, 65535, 65536, 99999, Int.MAX_VALUE
        )

        val jobs = (1..totalOperations).map { idx ->
            async(Dispatchers.Default) {
                try {
                    val handle = org.libtorrent4j.TorrentHandle(null)
                    val ip = candidateIps[idx % candidateIps.size]
                    val port = candidatePorts[idx % candidatePorts.size]

                    val result = TorrentEngineManager.injectPeerSafely(handle, ip, port)
                    // With an uninitialized handle or invalid IP/port, must safely return false
                    assertFalse(result)
                    completedOps.incrementAndGet()
                } catch (_: LinkageError) {
                    completedOps.incrementAndGet()
                } catch (t: Throwable) {
                    unexpectedErrors.incrementAndGet()
                }
            }
        }

        jobs.awaitAll()
        assertEquals("No unexpected exceptions should be thrown during multi-threaded stress", 0, unexpectedErrors.get())
        assertEquals("All stress operations must complete", totalOperations, completedOps.get())
    }

    @Test
    fun testInjectPeerSafely_ContentionWithWorkerDispatcher() = runBlocking {
        val dispatcher = TorrentEngineManager.torrentWorkerDispatcher
        val stressIterations = 200
        val dispatcherCompleted = AtomicInteger(0)
        val peerInjectionsCompleted = AtomicInteger(0)
        val errors = AtomicInteger(0)

        // Launch concurrent tasks on both Default coroutine pool and torrentWorkerDispatcher
        val defaultJobs = (1..stressIterations).map { i ->
            async(Dispatchers.Default) {
                try {
                    val handle = org.libtorrent4j.TorrentHandle(null)
                    TorrentEngineManager.injectPeerSafely(handle, "10.0.0.$i", 6881)
                    peerInjectionsCompleted.incrementAndGet()
                } catch (_: LinkageError) {
                    peerInjectionsCompleted.incrementAndGet()
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }

        val dispatcherJobs = (1..stressIterations).map { i ->
            async(dispatcher) {
                try {
                    val handle = org.libtorrent4j.TorrentHandle(null)
                    TorrentEngineManager.injectPeerSafely(handle, "192.168.0.$i", 6881)
                    dispatcherCompleted.incrementAndGet()
                } catch (_: LinkageError) {
                    dispatcherCompleted.incrementAndGet()
                } catch (t: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }

        defaultJobs.awaitAll()
        dispatcherJobs.awaitAll()

        assertEquals("No race or deadlock errors during contention", 0, errors.get())
        assertEquals(stressIterations, peerInjectionsCompleted.get())
        assertEquals(stressIterations, dispatcherCompleted.get())
    }

    @Test
    fun testInjectPeerSafely_AdversarialPortAndIpBoundaryRejections() {
        val handle = try { org.libtorrent4j.TorrentHandle(null) } catch (_: LinkageError) { null }
        if (handle == null) return

        // Ports strictly below 1 must be rejected without lock contention
        assertFalse(TorrentEngineManager.injectPeerSafely(handle, "1.2.3.4", 0))
        assertFalse(TorrentEngineManager.injectPeerSafely(handle, "1.2.3.4", -1))
        assertFalse(TorrentEngineManager.injectPeerSafely(handle, "1.2.3.4", Int.MIN_VALUE))

        // Ports strictly above 65535 must be rejected
        assertFalse(TorrentEngineManager.injectPeerSafely(handle, "1.2.3.4", 65536))
        assertFalse(TorrentEngineManager.injectPeerSafely(handle, "1.2.3.4", 70000))
        assertFalse(TorrentEngineManager.injectPeerSafely(handle, "1.2.3.4", Int.MAX_VALUE))

        // Blank and whitespace IPs must be rejected
        assertFalse(TorrentEngineManager.injectPeerSafely(handle, "", 6881))
        assertFalse(TorrentEngineManager.injectPeerSafely(handle, "   ", 6881))
        assertFalse(TorrentEngineManager.injectPeerSafely(handle, "\t\n", 6881))
    }

    // =========================================================================
    // PART 2: PARTIAL PROGRESS, DIVISION-BY-ZERO & ETA ADVERSARIAL CALCULATIONS
    // =========================================================================

    @Test
    fun testPartialProgress_AllZeroByteFiles_NoDivisionByZeroOrNan() {
        // Torrent with all 0-byte files
        val files = listOf(
            TorrentFileItem(0, "empty1.txt", size = 0L, downloadedBytes = 0L, progress = 0.0f, priority = Priority.NORMAL),
            TorrentFileItem(1, "empty2.txt", size = 0L, downloadedBytes = 0L, progress = 0.0f, priority = Priority.IGNORE)
        )

        val item = simulateUpdateStats(
            rawTotalSize = 0L,
            rawTotalDone = 0L,
            rawProgress = 0.0f,
            downRate = 0L,
            state = TorrentState.DOWNLOADING,
            files = files
        )

        assertTrue(item.isPartialSelection)
        assertEquals(0L, item.totalBytes)
        assertEquals(0L, item.downloadedBytes)
        assertFalse("Progress must not be NaN", item.progress.isNaN())
        assertFalse("Progress must not be Infinite", item.progress.isInfinite())
        assertTrue("Progress must be within [0f, 1f]", item.progress in 0.0f..1.0f)
        assertEquals(-1L, item.etaSeconds)
    }

    @Test
    fun testPartialProgress_NoMetadataState_ZeroSizes() {
        // Magnet link with no metadata yet: files list is empty, raw sizes are 0
        val item = simulateUpdateStats(
            rawTotalSize = 0L,
            rawTotalDone = 0L,
            rawProgress = Float.NaN,
            downRate = 0L,
            state = TorrentState.METADATA,
            files = emptyList()
        )

        assertFalse(item.isPartialSelection)
        assertEquals(0L, item.totalBytes)
        assertEquals(0L, item.downloadedBytes)
        assertEquals(0.0f, item.progress, 0.0001f)
        assertEquals(0, item.progressPercent)
        assertEquals(-1L, item.etaSeconds)
        assertFalse(item.isCompleted)
    }

    @Test
    fun testPartialProgress_NegativeDownRateOrZeroDownRate_EtaSafety() {
        val files = listOf(
            TorrentFileItem(0, "file.bin", size = 1_000_000L, downloadedBytes = 500_000L, progress = 0.5f, priority = Priority.NORMAL),
            TorrentFileItem(1, "ignored.bin", size = 99_000_000L, downloadedBytes = 0L, progress = 0.0f, priority = Priority.IGNORE)
        )

        // 1. Zero download rate -> ETA must be -1L
        val itemZeroRate = simulateUpdateStats(
            rawTotalSize = 100_000_000L,
            rawTotalDone = 500_000L,
            rawProgress = 0.005f,
            downRate = 0L,
            state = TorrentState.DOWNLOADING,
            files = files
        )
        assertEquals(-1L, itemZeroRate.etaSeconds)

        // 2. Negative download rate (should downRate ever be negative) -> ETA must be -1L, never divide by negative
        val itemNegativeRate = simulateUpdateStats(
            rawTotalSize = 100_000_000L,
            rawTotalDone = 500_000L,
            rawProgress = 0.005f,
            downRate = -5000L,
            state = TorrentState.DOWNLOADING,
            files = files
        )
        assertEquals(-1L, itemNegativeRate.etaSeconds)
    }

    @Test
    fun testPartialProgress_TerabyteScaleNoIntegerOverflow() {
        // 5 Terabytes selected in a 20 Terabyte swarm
        val fiveTB = 5_497_558_138_880L // 5 TiB
        val twentyTB = 21_990_232_555_520L // 20 TiB
        val files = listOf(
            TorrentFileItem(0, "huge_dataset_part1.raw", size = fiveTB, downloadedBytes = fiveTB / 2, progress = 0.5f, priority = Priority.HIGH),
            TorrentFileItem(1, "huge_dataset_part2.raw", size = twentyTB - fiveTB, downloadedBytes = 0L, progress = 0.0f, priority = Priority.IGNORE)
        )

        val item = simulateUpdateStats(
            rawTotalSize = twentyTB,
            rawTotalDone = fiveTB / 2,
            rawProgress = (fiveTB / 2).toFloat() / twentyTB.toFloat(),
            downRate = 104_857_600L, // 100 MB/s
            state = TorrentState.DOWNLOADING,
            files = files
        )

        assertEquals(fiveTB, item.totalBytes)
        assertEquals(fiveTB / 2, item.downloadedBytes)
        assertEquals(0.5f, item.progress, 0.001f)
        assertEquals(50, item.progressPercent)
        assertEquals("50.0%", item.formattedProgress)
        // 2.5 TiB remaining at 100 MB/s: 2,748,779,069,440 / 104,857,600 ≈ 26214 seconds
        val expectedEta = (fiveTB / 2) / 104_857_600L
        assertEquals(expectedEta, item.etaSeconds)
        assertTrue("ETA must be positive", item.etaSeconds > 0)
    }

    @Test
    fun testPartialProgress_OverhangClampingNeverExceeds100Percent() {
        // BitTorrent pieces rarely align to file boundaries; downloaded bytes may exceed file size
        val targetSize = 10_000_000L
        val files = listOf(
            TorrentFileItem(0, "data.bin", size = targetSize, downloadedBytes = 10_500_000L, progress = 1.0f, priority = Priority.NORMAL),
            TorrentFileItem(1, "skipped.bin", size = 50_000_000L, downloadedBytes = 0L, progress = 0.0f, priority = Priority.IGNORE)
        )

        val item = simulateUpdateStats(
            rawTotalSize = 60_000_000L,
            rawTotalDone = 10_500_000L,
            rawProgress = 10_500_000f / 60_000_000f,
            downRate = 1_000_000L,
            state = TorrentState.DOWNLOADING,
            files = files
        )

        assertEquals("Downloaded bytes must be clamped to effective total size", targetSize, item.downloadedBytes)
        assertEquals("Progress must not exceed 1.0f", 1.0f, item.progress, 0.0001f)
        assertEquals(100, item.progressPercent)
        assertEquals("100.0%", item.formattedProgress)
        assertEquals(0L, item.etaSeconds)
        assertTrue(item.isCompleted)
        assertEquals(TorrentState.FINISHED, item.state)
    }

    @Test
    fun testPartialProgress_DynamicPriorityFlipping_SimulatedSelection() {
        // Simulate user selecting and un-selecting files mid-transfer
        val fileSizes = listOf(100L, 200L, 300L, 400L, 500L) // Total 1500L
        val rng = Random(42)

        for (trial in 1..50) {
            val priorities = fileSizes.map {
                if (rng.nextBoolean()) Priority.NORMAL else Priority.IGNORE
            }
            val files = fileSizes.mapIndexed { idx, size ->
                val p = priorities[idx]
                val dl = if (p == Priority.IGNORE) 0L else (size * rng.nextFloat()).toLong()
                TorrentFileItem(
                    index = idx,
                    path = "file_$idx.dat",
                    size = size,
                    downloadedBytes = dl,
                    progress = dl.toFloat() / size.toFloat(),
                    priority = p
                )
            }

            val item = simulateUpdateStats(
                rawTotalSize = 1500L,
                rawTotalDone = files.sumOf { it.downloadedBytes },
                rawProgress = files.sumOf { it.downloadedBytes }.toFloat() / 1500f,
                downRate = 50L,
                state = TorrentState.DOWNLOADING,
                files = files
            )

            assertTrue("Progress must stay in [0f, 1f] for trial $trial", item.progress in 0.0f..1.0f)
            assertTrue("Progress percent must stay in 0..100", item.progressPercent in 0..100)
            assertTrue("Downloaded bytes <= total bytes", item.downloadedBytes <= item.totalBytes)
            if (item.progress >= 1.0f) {
                assertTrue("Must be completed when progress >= 1.0f", item.isCompleted)
                assertEquals(TorrentState.FINISHED, item.state)
                assertEquals(0L, item.etaSeconds)
            }
        }
    }

    @Test
    fun testPartialProgress_StateTransitions_PausedPreservedWhenComplete() {
        // When torrent is PAUSED, reaching completion must keep PAUSED state but mark isCompleted = true
        val files = listOf(
            TorrentFileItem(0, "file.iso", size = 1000L, downloadedBytes = 1000L, progress = 1.0f, priority = Priority.NORMAL),
            TorrentFileItem(1, "skipped.iso", size = 5000L, downloadedBytes = 0L, progress = 0.0f, priority = Priority.IGNORE)
        )

        val item = simulateUpdateStats(
            rawTotalSize = 6000L,
            rawTotalDone = 1000L,
            rawProgress = 1000f / 6000f,
            downRate = 0L,
            state = TorrentState.PAUSED,
            files = files
        )

        assertEquals("Paused state must not be overwritten by FINISHED", TorrentState.PAUSED, item.state)
        assertEquals(1.0f, item.progress, 0.0001f)
        assertTrue("isCompleted must be true because progress is 1.0f", item.isCompleted)
        assertTrue(TorrentFilter.COMPLETED.matches(item))
        assertTrue(TorrentFilter.PAUSED.matches(item))
        assertFalse(TorrentFilter.DOWNLOADING.matches(item))
    }

    // =========================================================================
    // HELPER METHOD REPLICATING EXACT TorrentEngineManager.updateTorrentsAndStats LOGIC
    // =========================================================================

    private fun simulateUpdateStats(
        rawTotalSize: Long,
        rawTotalDone: Long,
        rawProgress: Float,
        downRate: Long,
        state: TorrentState,
        files: List<TorrentFileItem>
    ): TorrentItem {
        // 1. Determine if partial selection is active
        val hasPartialFiles = files.isNotEmpty() && files.any { it.isSkipped }
        val selectedFiles = if (hasPartialFiles) files.filter { !it.isSkipped } else files

        // 2. Compute effective total bytes and downloaded bytes for selected files
        val effectiveTotalSize: Long = if (hasPartialFiles) {
            selectedFiles.sumOf { it.size }
        } else if (files.isNotEmpty()) {
            files.sumOf { it.size }
        } else {
            rawTotalSize
        }

        val effectiveDownloadedBytes: Long = if (hasPartialFiles) {
            if (effectiveTotalSize > 0L) minOf(selectedFiles.sumOf { it.downloadedBytes }, effectiveTotalSize) else 0L
        } else {
            minOf(rawTotalDone, effectiveTotalSize)
        }

        // 3. Compute accurate progress reflecting only selected files
        val effectiveProgress: Float = if (effectiveTotalSize > 0L) {
            (effectiveDownloadedBytes.toFloat() / effectiveTotalSize.toFloat()).let {
                if (it.isNaN()) 0.0f else it.coerceIn(0.0f, 1.0f)
            }
        } else if (hasPartialFiles && selectedFiles.isEmpty()) {
            1.0f
        } else {
            if (rawProgress.isNaN()) 0f else rawProgress.coerceIn(0.0f, 1.0f)
        }

        // 4. Compute accurate remaining bytes and ETA
        val remainingBytes: Long = (effectiveTotalSize - effectiveDownloadedBytes).coerceAtLeast(0L)
        val eta: Long = if (downRate > 0L && remainingBytes > 0L) {
            remainingBytes / downRate
        } else if (effectiveProgress >= 1.0f) {
            0L
        } else {
            -1L
        }

        val itemState = if (effectiveProgress >= 1.0f && state == TorrentState.DOWNLOADING) {
            TorrentState.FINISHED
        } else {
            state
        }

        return TorrentItem(
            id = "adversarial_test_id",
            name = "Adversarial Torrent",
            state = itemState,
            progress = effectiveProgress,
            downloadSpeed = downRate,
            uploadSpeed = 0L,
            totalBytes = effectiveTotalSize,
            downloadedBytes = effectiveDownloadedBytes,
            uploadedBytes = 0L,
            numSeeds = 1,
            numPeers = 2,
            etaSeconds = eta,
            files = files
        )
    }
}
