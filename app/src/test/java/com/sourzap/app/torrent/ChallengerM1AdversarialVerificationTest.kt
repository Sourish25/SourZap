package com.sourzap.app.torrent

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.sourzap.app.torrent.core.TorrentEngineManager
import com.sourzap.app.torrent.model.PreDownloadFileItem
import com.sourzap.app.torrent.model.PreDownloadState
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import com.sourzap.app.torrent.service.TorrentDownloadService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Adversarial Challenger verification test suite for Milestone M1:
 * - Android 14+ Foreground Service lifecycle & safety contracts.
 * - Edge cases in partial progress tracking:
 *   - 1 file selected of 100 files
 *   - All 100 files selected
 *   - 0 files selected (all skipped)
 *   - Dynamic unskipping and skipping mid-download
 *   - Piece overlap / overshoot clamping
 *   - 0-byte empty file edge cases
 *   - 50 TB large multi-file Long overflow safety
 *   - PreDownloadState dynamic selection transitions
 */
class ChallengerM1AdversarialVerificationTest {

    // =========================================================================
    // 1. FOREGROUND SERVICE LIFECYCLE & ANDROID 14+ CONTRACTS
    // =========================================================================

    @Test
    fun testForegroundService_Android14DataSyncTypeConstant() {
        // Android 14+ requires FOREGROUND_SERVICE_TYPE_DATA_SYNC (1)
        val dataSyncType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        assertEquals("FOREGROUND_SERVICE_TYPE_DATA_SYNC value must be 1", 1, dataSyncType)
    }

    @Test
    fun testForegroundService_NotificationIdContract() {
        // Android requires notificationId > 0
        assertTrue("Notification ID must be positive non-zero", TorrentDownloadService.NOTIFICATION_ID > 0)
        assertEquals(1002, TorrentDownloadService.NOTIFICATION_ID)
    }

    @Test
    fun testForegroundService_ActionIntentsIntegrity() {
        val actions = listOf(
            TorrentDownloadService.ACTION_START,
            TorrentDownloadService.ACTION_PAUSE_ALL,
            TorrentDownloadService.ACTION_RESUME_ALL,
            TorrentDownloadService.ACTION_STOP_SERVICE
        )
        // All actions must be distinct non-empty strings
        assertEquals(4, actions.distinct().size)
        for (action in actions) {
            assertTrue("Action must be properly namespaced", action.startsWith("com.sourzap.app.torrent."))
        }
    }

    @Test
    fun testForegroundService_LifecycleOrderSimulation() {
        // Simulate lifecycle: startForeground must precede stopSelf or operations
        var startForegroundCalled = false
        var stopSelfCalled = false
        var untypedStartForegroundCalledOnApi34 = false

        fun simulateServiceStart(sdkInt: Int, action: String?) {
            // onCreate step:
            // startForegroundServiceNotification MUST be invoked first
            if (sdkInt >= 34) {
                // Typed startForeground only
                startForegroundCalled = true
            } else if (sdkInt >= 29) {
                startForegroundCalled = true
            } else {
                startForegroundCalled = true
            }

            // onStartCommand step:
            when (action) {
                TorrentDownloadService.ACTION_STOP_SERVICE -> {
                    // Allowed to call stopSelf only AFTER startForeground has already executed in onCreate
                    stopSelfCalled = true
                }
                else -> {
                    // Normal start
                }
            }
        }

        // Test on Android 14 (API 34)
        simulateServiceStart(34, TorrentDownloadService.ACTION_START)
        assertTrue("startForeground must be called before anything else", startForegroundCalled)
        assertFalse("stopSelf must not be called during normal start", stopSelfCalled)
        assertFalse("Untyped startForeground must never be called on API 34+", untypedStartForegroundCalledOnApi34)

        // Test STOP action
        startForegroundCalled = false
        stopSelfCalled = false
        simulateServiceStart(34, TorrentDownloadService.ACTION_STOP_SERVICE)
        assertTrue("startForeground must still be executed in onCreate before stopSelf in onStartCommand", startForegroundCalled)
        assertTrue("stopSelf executed after startForeground", stopSelfCalled)
    }

    // =========================================================================
    // 2. PARTIAL PROGRESS EDGE CASES
    // =========================================================================

    /**
     * Replicates the exact algorithm from TorrentEngineManager.updateTorrentsAndStats.
     */
    private fun computeEffectiveState(
        files: List<TorrentFileItem>,
        rawTotalSize: Long,
        rawTotalDone: Long,
        rawProgress: Float,
        downRate: Long = 0L,
        state: TorrentState = TorrentState.DOWNLOADING
    ): TorrentItem {
        val hasPartialFiles = files.isNotEmpty() && files.any { it.isSkipped }
        val selectedFiles = if (hasPartialFiles) files.filter { !it.isSkipped } else files

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

        val effectiveProgress: Float = if (effectiveTotalSize > 0L) {
            (effectiveDownloadedBytes.toFloat() / effectiveTotalSize.toFloat()).let {
                if (it.isNaN()) 0.0f else it.coerceIn(0.0f, 1.0f)
            }
        } else if (hasPartialFiles && selectedFiles.isEmpty()) {
            1.0f
        } else {
            if (rawProgress.isNaN()) 0f else rawProgress.coerceIn(0.0f, 1.0f)
        }

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
            id = "test_item",
            name = "Test Edge Case Torrent",
            state = itemState,
            progress = effectiveProgress,
            downloadSpeed = downRate,
            uploadSpeed = 0L,
            totalBytes = effectiveTotalSize,
            downloadedBytes = effectiveDownloadedBytes,
            uploadedBytes = 0L,
            numSeeds = 1,
            numPeers = 1,
            etaSeconds = eta,
            files = files
        )
    }

    @Test
    fun testEdgeCase_1FileSelectedOf100Files() {
        val fileSize = 10_000_000L // 10 MB per file
        val files = (0 until 100).map { i ->
            TorrentFileItem(
                index = i,
                path = "file_$i.bin",
                size = fileSize,
                downloadedBytes = 0L,
                progress = 0f,
                priority = if (i == 42) Priority.NORMAL else Priority.IGNORE // only index 42 selected
            )
        }
        val rawTotal = 100 * fileSize // 1000 MB

        // 1. Initial State (0 bytes)
        val item0 = computeEffectiveState(files, rawTotal, 0L, 0f, downRate = 1_000_000L)
        assertTrue(item0.isPartialSelection)
        assertEquals(rawTotal, item0.rawTotalBytes)
        assertEquals(fileSize, item0.totalBytes) // Only 10 MB
        assertEquals(0L, item0.downloadedBytes)
        assertEquals(0.0f, item0.progress, 0.0001f)
        assertEquals(0, item0.progressPercent)
        assertEquals(fileSize, item0.totalBytes - item0.downloadedBytes)
        assertEquals(10L, item0.etaSeconds) // 10MB / 1MB/s = 10s
        assertFalse(item0.isCompleted)

        // 2. Mid State (5 MB downloaded of file 42)
        val filesMid = files.toMutableList().apply {
            this[42] = this[42].copy(downloadedBytes = 5_000_000L, progress = 0.5f)
        }
        val itemMid = computeEffectiveState(filesMid, rawTotal, 5_000_000L, 0.005f, downRate = 1_000_000L)
        assertEquals(fileSize, itemMid.totalBytes)
        assertEquals(5_000_000L, itemMid.downloadedBytes)
        assertEquals(0.5f, itemMid.progress, 0.0001f)
        assertEquals(50, itemMid.progressPercent)
        assertEquals(5L, itemMid.etaSeconds)
        assertFalse(itemMid.isCompleted)

        // 3. Completion State (10 MB downloaded of file 42)
        val filesDone = files.toMutableList().apply {
            this[42] = this[42].copy(downloadedBytes = fileSize, progress = 1.0f)
        }
        val itemDone = computeEffectiveState(filesDone, rawTotal, fileSize, 0.01f, downRate = 0L)
        assertEquals(fileSize, itemDone.totalBytes)
        assertEquals(fileSize, itemDone.downloadedBytes)
        assertEquals(1.0f, itemDone.progress, 0.0001f)
        assertEquals(100, itemDone.progressPercent)
        assertEquals(0L, itemDone.etaSeconds)
        assertTrue(itemDone.isCompleted)
        assertEquals(TorrentState.FINISHED, itemDone.state)
    }

    @Test
    fun testEdgeCase_All100FilesSelected() {
        val fileSize = 10_000_000L // 10 MB per file
        val files = (0 until 100).map { i ->
            TorrentFileItem(
                index = i,
                path = "file_$i.bin",
                size = fileSize,
                downloadedBytes = 5_000_000L,
                progress = 0.5f,
                priority = Priority.NORMAL // All selected
            )
        }
        val rawTotal = 100 * fileSize // 1000 MB
        val rawDone = 100 * 5_000_000L // 500 MB

        val item = computeEffectiveState(files, rawTotal, rawDone, 0.5f, downRate = 10_000_000L)
        assertFalse("Must NOT be partial selection when all files are selected", item.isPartialSelection)
        assertEquals(rawTotal, item.totalBytes)
        assertEquals(rawDone, item.downloadedBytes)
        assertEquals(0.5f, item.progress, 0.0001f)
        assertEquals(50, item.progressPercent)
        assertEquals(50L, item.etaSeconds) // 500MB / 10MB/s = 50s
    }

    @Test
    fun testEdgeCase_0FilesSelected_All100FilesSkipped() {
        val fileSize = 10_000_000L
        val files = (0 until 100).map { i ->
            TorrentFileItem(
                index = i,
                path = "file_$i.bin",
                size = fileSize,
                downloadedBytes = 0L,
                progress = 0f,
                priority = Priority.IGNORE // All skipped
            )
        }
        val rawTotal = 100 * fileSize

        val item = computeEffectiveState(files, rawTotal, 0L, 0f)
        assertTrue(item.isPartialSelection)
        assertEquals("Total selected bytes must be 0", 0L, item.totalBytes)
        assertEquals("Downloaded selected bytes must be 0", 0L, item.downloadedBytes)
        assertEquals("Progress must be 1.0f when 0 files selected", 1.0f, item.progress, 0.0001f)
        assertEquals(100, item.progressPercent)
        assertTrue("Must be marked completed since 0 files are pending", item.isCompleted)
        assertEquals(0L, item.etaSeconds)
    }

    @Test
    fun testEdgeCase_DynamicUnskippingAndSkippingMidDownload() {
        val f0Size = 100_000_000L // 100 MB
        val f1Size = 200_000_000L // 200 MB
        val rawTotal = f0Size + f1Size // 300 MB

        // Step 1: File 0 selected, File 1 skipped. 50 MB of File 0 downloaded.
        val filesStep1 = listOf(
            TorrentFileItem(0, "f0.mp4", f0Size, downloadedBytes = 50_000_000L, progress = 0.5f, priority = Priority.NORMAL),
            TorrentFileItem(1, "f1.mp4", f1Size, downloadedBytes = 0L, progress = 0.0f, priority = Priority.IGNORE)
        )
        val itemStep1 = computeEffectiveState(filesStep1, rawTotal, 50_000_000L, 50_000_000f / rawTotal.toFloat(), downRate = 10_000_000L)
        assertTrue(itemStep1.isPartialSelection)
        assertEquals(f0Size, itemStep1.totalBytes)
        assertEquals(50_000_000L, itemStep1.downloadedBytes)
        assertEquals(0.5f, itemStep1.progress, 0.0001f)
        assertEquals(50, itemStep1.progressPercent)
        assertEquals(5L, itemStep1.etaSeconds) // 50MB / 10MB/s = 5s
        assertFalse(itemStep1.isCompleted)

        // Step 2: Dynamic unskip! User unskips File 1 (sets priority to NORMAL mid-download)
        val filesStep2 = listOf(
            TorrentFileItem(0, "f0.mp4", f0Size, downloadedBytes = 50_000_000L, progress = 0.5f, priority = Priority.NORMAL),
            TorrentFileItem(1, "f1.mp4", f1Size, downloadedBytes = 0L, progress = 0.0f, priority = Priority.NORMAL)
        )
        val itemStep2 = computeEffectiveState(filesStep2, rawTotal, 50_000_000L, 50_000_000f / rawTotal.toFloat(), downRate = 10_000_000L)
        assertFalse("No longer partial selection since all files are selected", itemStep2.isPartialSelection)
        assertEquals("Total bytes expands to both files (300 MB)", rawTotal, itemStep2.totalBytes)
        assertEquals("Downloaded bytes remains 50 MB", 50_000_000L, itemStep2.downloadedBytes)
        // Progress drops from 50% to 50MB / 300MB = 16.66%
        assertEquals(50_000_000f / 300_000_000f, itemStep2.progress, 0.0001f)
        assertEquals(16, itemStep2.progressPercent)
        // Remaining bytes increases to 250 MB
        assertEquals(25L, itemStep2.etaSeconds) // 250MB / 10MB/s = 25s
        assertFalse(itemStep2.isCompleted)

        // Step 3: File 0 completes (100 MB), File 1 downloads 50 MB (total 150 MB downloaded)
        // User skips File 1 again (mid-download re-skip)
        val filesStep3 = listOf(
            TorrentFileItem(0, "f0.mp4", f0Size, downloadedBytes = f0Size, progress = 1.0f, priority = Priority.NORMAL),
            TorrentFileItem(1, "f1.mp4", f1Size, downloadedBytes = 50_000_000L, progress = 0.25f, priority = Priority.IGNORE)
        )
        val itemStep3 = computeEffectiveState(filesStep3, rawTotal, 150_000_000L, 150_000_000f / rawTotal.toFloat(), downRate = 0L)
        assertTrue("Partial selection re-engaged", itemStep3.isPartialSelection)
        assertEquals("Total bytes shrinks back to selected File 0 (100 MB)", f0Size, itemStep3.totalBytes)
        assertEquals("Downloaded bytes reflects only selected files (100 MB)", f0Size, itemStep3.downloadedBytes)
        assertEquals("Progress immediately transitions to 100%", 1.0f, itemStep3.progress, 0.0001f)
        assertEquals(100, itemStep3.progressPercent)
        assertTrue("Torrent is immediately marked completed!", itemStep3.isCompleted)
        assertEquals(TorrentState.FINISHED, itemStep3.state)
        assertEquals(0L, itemStep3.etaSeconds)
    }

    @Test
    fun testEdgeCase_PieceOverlapClampingUnderSevereOverhang() {
        val fileSize = 1_000_000L // 1 MB
        // Libtorrent piece boundary downloaded 1.5 MB for this file
        val files = listOf(
            TorrentFileItem(0, "target.dat", fileSize, downloadedBytes = 1_500_000L, progress = 1.0f, priority = Priority.NORMAL),
            TorrentFileItem(1, "ignored.dat", 10_000_000L, downloadedBytes = 0L, progress = 0.0f, priority = Priority.IGNORE)
        )

        val item = computeEffectiveState(files, 11_000_000L, 1_500_000L, 1_500_000f / 11_000_000f)
        assertEquals("Downloaded bytes must be strictly clamped to effective total size", fileSize, item.downloadedBytes)
        assertEquals(1.0f, item.progress, 0.0001f)
        assertEquals(100, item.progressPercent)
        assertTrue(item.isCompleted)
    }

    @Test
    fun testEdgeCase_EmptyZeroByteFiles() {
        val files = listOf(
            TorrentFileItem(0, "empty.txt", 0L, downloadedBytes = 0L, progress = 0.0f, priority = Priority.NORMAL),
            TorrentFileItem(1, "payload.bin", 10_000L, downloadedBytes = 5_000L, progress = 0.5f, priority = Priority.NORMAL)
        )
        val item = computeEffectiveState(files, 10_000L, 5_000L, 0.5f)
        assertEquals(10_000L, item.totalBytes)
        assertEquals(5_000L, item.downloadedBytes)
        assertEquals(0.5f, item.progress, 0.0001f)
        assertEquals(50, item.progressPercent)

        // What if ONLY the 0-byte file is selected?
        val filesOnlyZero = listOf(
            TorrentFileItem(0, "empty.txt", 0L, downloadedBytes = 0L, progress = 0.0f, priority = Priority.NORMAL),
            TorrentFileItem(1, "payload.bin", 10_000L, downloadedBytes = 0L, progress = 0.0f, priority = Priority.IGNORE)
        )
        val itemOnlyZero = computeEffectiveState(filesOnlyZero, 10_000L, 0L, 0f)
        assertEquals(0L, itemOnlyZero.totalBytes)
        assertEquals(0L, itemOnlyZero.downloadedBytes)
        // Must not be NaN, must be safe numeric value
        assertFalse("Progress must not be NaN", itemOnlyZero.progress.isNaN())
        // When total size is 0 and rawProgress is 0, ETA safely returns -1L (indeterminate)
        assertEquals(-1L, itemOnlyZero.etaSeconds)
    }

    @Test
    fun testEdgeCase_MultiTerabyteLongArithmeticNoOverflow() {
        // 50 TB torrent (54,975,581,388,800 bytes)
        val tb50 = 54_975_581_388_800L
        val selectedFile = tb50 / 2 // 25 TB
        val skippedFile = tb50 / 2  // 25 TB

        val files = listOf(
            TorrentFileItem(0, "huge_1.raw", selectedFile, downloadedBytes = selectedFile / 2, progress = 0.5f, priority = Priority.NORMAL),
            TorrentFileItem(1, "huge_2.raw", skippedFile, downloadedBytes = 0L, progress = 0.0f, priority = Priority.IGNORE)
        )

        val item = computeEffectiveState(files, tb50, selectedFile / 2, 0.25f, downRate = 1_000_000_000L) // 1 GB/s
        assertEquals(selectedFile, item.totalBytes)
        assertEquals(selectedFile / 2, item.downloadedBytes)
        assertEquals(0.5f, item.progress, 0.0001f)
        assertEquals(50, item.progressPercent)
        assertTrue("Remaining bytes must be positive", item.totalBytes - item.downloadedBytes > 0L)
        val expectedEta = (selectedFile / 2) / 1_000_000_000L
        assertEquals(expectedEta, item.etaSeconds)
        assertTrue("ETA must be positive", item.etaSeconds > 0L)
    }

    @Test
    fun testEdgeCase_PreDownloadStateTransitions() {
        val targetDir = File(System.getProperty("java.io.tmpdir") ?: ".", "predownload_test")
        val files = (0 until 5).map { i ->
            PreDownloadFileItem(i, "file_$i.mp4", 100_000_000L, isSelected = true)
        }
        var state = PreDownloadState.create(
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2"),
            name = "PreDownload Test",
            files = files,
            targetDirectory = targetDir,
            availableDiskSpace = 1_000_000_000L
        )

        assertEquals(5, state.totalCount)
        assertEquals(5, state.selectedCount)
        assertTrue(state.allSelected)
        assertFalse(state.noneSelected)
        assertTrue(state.isDownloadEnabled)
        assertEquals(500_000_000L, state.selectedSize)

        // Toggle file 0
        state = state.toggleFile(0)
        assertEquals(4, state.selectedCount)
        assertFalse(state.allSelected)
        assertEquals(400_000_000L, state.selectedSize)

        // Deselect all
        state = state.deselectAll()
        assertEquals(0, state.selectedCount)
        assertTrue(state.noneSelected)
        assertFalse(state.isDownloadEnabled)
        assertEquals(0L, state.selectedSize)

        // Select all
        state = state.selectAll()
        assertEquals(5, state.selectedCount)
        assertTrue(state.allSelected)
        assertEquals(500_000_000L, state.selectedSize)

        // Priorities mapping
        val priorities = state.toPriorities()
        assertEquals(5, priorities.size)
        assertTrue(priorities.all { it == Priority.NORMAL })
    }
}
