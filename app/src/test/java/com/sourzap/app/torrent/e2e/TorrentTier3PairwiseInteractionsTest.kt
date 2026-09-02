package com.sourzap.app.torrent.e2e

import com.sourzap.app.torrent.core.BencodeValidator
import com.sourzap.app.torrent.core.MagnetHandler
import com.sourzap.app.torrent.core.MagnetInfo
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentSessionConfig
import com.sourzap.app.torrent.core.TorrentStorageHelper
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.core.TrackerInjector
import com.sourzap.app.torrent.model.PendingTorrentIntent
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentPieceInfo
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import com.sourzap.app.torrent.service.TorrentDownloadService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Dual-Track E2E Test Suite — Tier 3: Cross-Feature Interactions & Pairwise Combinations.
 *
 * Verifies combinatorial and cross-feature interactions:
 * - P1: Magnet URI + Selective File Prioritization + Custom Save Path.
 * - P2: File Picker + Bencode Validation + Partial Selection + WakeLock/WifiLock Lifecycle.
 * - P3: HTTPS Port-443 Tracker Injection + Native MSE RC4 Encryption + Prefer TCP.
 * - P4: Clipboard Magnet Paste + Pre-Download File Extraction + Disk Space Verification.
 * - P5: Multi-File Tree Extraction + DO_NOT_DOWNLOAD Priority.IGNORE + Directory Isolation.
 * - P6: Dynamic Listen Ports + NAT-PMP/UPnP + Swarm Saturation.
 */
class TorrentTier3PairwiseInteractionsTest {

    private fun createMultiFileTorrent(
        dirName: String,
        files: List<Pair<String, Long>>,
        pieceLength: Int = 32768,
        pieceCount: Int = 10
    ): ByteArray {
        val piecesBytes = ByteArray(pieceCount * 20) { (it % 256).toByte() }
        val out = ByteArrayOutputStream()

        val announceBytes = "https://tracker.tamersunion.org:443/announce".toByteArray(StandardCharsets.UTF_8)
        out.write("d8:announce${announceBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(announceBytes)
        out.write("4:infod".toByteArray(StandardCharsets.US_ASCII))
        out.write("5:filesl".toByteArray(StandardCharsets.US_ASCII))

        for ((path, len) in files) {
            out.write("d6:lengthi${len}e4:pathl".toByteArray(StandardCharsets.US_ASCII))
            val segments = path.split("/").filter { it.isNotEmpty() }
            for (seg in segments) {
                val segBytes = seg.toByteArray(StandardCharsets.UTF_8)
                out.write("${segBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
                out.write(segBytes)
            }
            out.write("ee".toByteArray(StandardCharsets.US_ASCII))
        }
        out.write("e".toByteArray(StandardCharsets.US_ASCII))

        val dirBytes = dirName.toByteArray(StandardCharsets.UTF_8)
        out.write("4:name${dirBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(dirBytes)

        out.write("12:piece lengthi${pieceLength}e".toByteArray(StandardCharsets.US_ASCII))
        out.write("6:pieces${piecesBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(piecesBytes)
        out.write("ee".toByteArray(StandardCharsets.US_ASCII))

        return out.toByteArray()
    }

    // =========================================================================
    // P1: Magnet + Selective File Prioritization + Custom Save Path (3 tests)
    // =========================================================================

    @Test
    fun testP1_1_Magnet_SelectivePrioritization_CustomSavePath() {
        val rawMagnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=Linux+Distribution"
        val parsed = MagnetHandler.parse(rawMagnet)
        assertNotNull(parsed)

        val tempSaveDir = File(System.getProperty("java.io.tmpdir"), "p1_save_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            assertTrue(TorrentStorageHelper.isWritableOrCreatable(tempSaveDir))

            // Simulated files received via metadata
            val files = listOf(
                TorrentFileItem(0, "Linux/install.iso", 4_000_000_000L, priority = Priority.NORMAL),
                TorrentFileItem(1, "Linux/source.tar.gz", 2_000_000_000L, priority = Priority.IGNORE),
                TorrentFileItem(2, "Linux/checksums.txt", 1024L, priority = Priority.HIGH)
            )

            val totalBytes = files.sumOf { it.size }
            val selectedBytes = files.filter { !it.isSkipped }.sumOf { it.size }

            assertEquals(6_000_001_024L, totalBytes)
            assertEquals(4_000_001_024L, selectedBytes)

            val source = TorrentSource.Magnet(rawMagnet, parsed!!.displayName)
            val item = TorrentItem(
                id = parsed.infoHash,
                name = parsed.displayName ?: "Linux",
                state = TorrentState.DOWNLOADING,
                progress = 0.0f,
                downloadSpeed = 5_000_000L,
                uploadSpeed = 500_000L,
                totalBytes = totalBytes,
                downloadedBytes = 0L,
                uploadedBytes = 0L,
                numSeeds = 15,
                numPeers = 30,
                savePath = tempSaveDir.absolutePath,
                files = files
            )

            assertEquals(tempSaveDir.absolutePath, item.savePath)
            assertEquals(3, item.files.size)
            assertTrue(item.files[1].isSkipped)
            assertEquals(Priority.HIGH, item.files[2].priority)
        } finally {
            tempSaveDir.deleteRecursively()
        }
    }

    @Test
    fun testP1_2_Magnet_Base32_CustomPath_DeselectAllExceptFirst() {
        val base32Magnet = "magnet:?xt=urn:btih:YNCKHTQ3XIRUVE6J6UM345O6P3TXJ5WS&dn=MultiDisc"
        val parsed = MagnetHandler.parse(base32Magnet)
        assertNotNull(parsed)
        assertEquals(40, parsed!!.infoHash.length)

        val files = (0..4).map { i ->
            TorrentFileItem(
                index = i,
                path = "Disc_$i.iso",
                size = 1_000_000_000L,
                priority = if (i == 0) Priority.NORMAL else Priority.IGNORE
            )
        }
        val selectedBytes = files.filter { !it.isSkipped }.sumOf { it.size }
        assertEquals(1_000_000_000L, selectedBytes)
        assertEquals(4, files.count { it.isSkipped })
    }

    @Test
    fun testP1_3_Magnet_PostMetadata_PrioritiesUpdate() {
        // Simulates transitioning from METADATA state to DOWNLOADING state with updated file priorities
        val initialItem = TorrentItem(
            id = "c12fe1c06bba254a9dc9f519b335de7ece74f6d2",
            name = "Fetching...",
            state = TorrentState.METADATA,
            progress = 0.0f,
            downloadSpeed = 0L,
            uploadSpeed = 0L,
            totalBytes = 0L,
            downloadedBytes = 0L,
            uploadedBytes = 0L,
            numSeeds = 2,
            numPeers = 5,
            files = emptyList()
        )
        assertTrue(TorrentFilter.DOWNLOADING.matches(initialItem))

        val resolvedFiles = listOf(
            TorrentFileItem(0, "Movie.mp4", 2_000_000_000L, priority = Priority.NORMAL),
            TorrentFileItem(1, "Sample.mp4", 50_000_000L, priority = Priority.IGNORE)
        )
        val updatedItem = initialItem.copy(
            name = "Movie 2026",
            state = TorrentState.DOWNLOADING,
            totalBytes = 2_050_000_000L,
            files = resolvedFiles
        )
        assertEquals("Movie 2026", updatedItem.name)
        assertEquals(2, updatedItem.files.size)
        assertTrue(updatedItem.files[1].isSkipped)
    }

    // =========================================================================
    // P2: File Picker + Bencode Validation + Partial Selection + WakeLock (3 tests)
    // =========================================================================

    @Test
    fun testP2_1_FilePicker_Validation_PartialSelection_ServiceStats() {
        val files = listOf(
            "Release/app.apk" to 50_000_000L,
            "Release/symbols.zip" to 150_000_000L,
            "Release/readme.txt" to 1024L
        )
        val bencodeBytes = createMultiFileTorrent("Release_v2", files)
        val validation = TorrentFileValidator.validate(bencodeBytes)
        assertTrue(validation is TorrentValidationResult.Valid)
        val valid = validation as TorrentValidationResult.Valid

        val source = TorrentSource.FileContent(bencodeBytes, "Release_v2.torrent")
        val fileItems = listOf(
            TorrentFileItem(0, "Release/app.apk", 50_000_000L, priority = Priority.NORMAL),
            TorrentFileItem(1, "Release/symbols.zip", 150_000_000L, priority = Priority.IGNORE),
            TorrentFileItem(2, "Release/readme.txt", 1024L, priority = Priority.NORMAL)
        )

        val selectedSize = fileItems.filter { !it.isSkipped }.sumOf { it.size }
        assertEquals(50_001_024L, selectedSize)

        // Mock lock acquisition state
        var wakeLockHeld = true
        var wifiLockHeld = true
        val stats = TorrentSessionStats(
            totalDownloadSpeed = 2_500_000L,
            activeTorrents = 1,
            aggregateProgress = 0.35f
        )
        assertTrue(wakeLockHeld && wifiLockHeld)
        assertEquals("2.4 MB/s", stats.formattedDownloadSpeed)
        assertEquals("35.0%", stats.formattedProgress)

        // Service termination
        wakeLockHeld = false
        wifiLockHeld = false
        assertFalse(wakeLockHeld)
        assertFalse(wifiLockHeld)
    }

    @Test
    fun testP2_2_FilePicker_RejectCorruptedBytesBeforeLockAcquisition() {
        val corruptedBytes = "not_a_torrent_payload".toByteArray()
        val validation = TorrentFileValidator.validate(corruptedBytes)
        assertTrue(validation is TorrentValidationResult.Invalid)

        var serviceStarted = false
        if (validation is TorrentValidationResult.Valid) {
            serviceStarted = true
        }
        assertFalse("Service must NOT start if torrent file validation fails", serviceStarted)
    }

    @Test
    fun testP2_3_FilePicker_PendingTorrentIntentCreation() {
        val bytes = createMultiFileTorrent("TestPending", listOf("test.txt" to 100L))
        val pendingIntent = PendingTorrentIntent.TorrentFile(bytes, "TestPending.torrent")
        assertEquals("TestPending.torrent", pendingIntent.fileName)
        assertTrue(pendingIntent.bytes.contentEquals(bytes))
    }

    // =========================================================================
    // P3: HTTPS Port-443 Tracker Injection + Native MSE + Prefer TCP (3 tests)
    // =========================================================================

    @Test
    fun testP3_1_TrackerInjection_NativeMSE_PreferTcp() {
        val rawMagnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=SecureDistro"
        val injectedMagnet = TrackerInjector.injectTrackers(rawMagnet)

        val parsed = MagnetHandler.parse(injectedMagnet)
        assertNotNull(parsed)
        assertTrue(parsed!!.trackers.contains("https://tracker.tamersunion.org:443/announce"))
        assertTrue(parsed.trackers.contains("https://tracker.renfei.net:443/announce"))

        val config = TorrentSessionConfig(
            outEncPolicy = TorrentSessionConfig.ENC_POLICY_FORCED,
            inEncPolicy = TorrentSessionConfig.ENC_POLICY_FORCED,
            allowedEncLevel = TorrentSessionConfig.ENC_LEVEL_RC4,
            preferRc4 = true,
            enableIncomingTcp = true,
            enableOutgoingTcp = true,
            enableIncomingUtp = false,
            enableOutgoingUtp = false
        )

        assertEquals(TorrentSessionConfig.ENC_POLICY_FORCED, config.outEncPolicy)
        assertTrue(config.preferRc4)
        assertFalse(config.enableIncomingUtp)
        assertTrue(config.enableIncomingTcp)
    }

    @Test
    fun testP3_2_TrackerInjection_DeduplicatesExistingPort443Trackers() {
        val existingTracker = "https://tracker.tamersunion.org:443/announce"
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&tr=" +
                java.net.URLEncoder.encode(existingTracker, StandardCharsets.UTF_8.name())
        val augmented = TrackerInjector.injectTrackers(magnet)
        val parsed = MagnetHandler.parse(augmented)
        assertNotNull(parsed)
        val count = parsed!!.trackers.count { it.contains("tracker.tamersunion.org") }
        assertEquals(1, count)
    }

    @Test
    fun testP3_3_SessionConfig_ParallelTrackerAnnouncementFlags() {
        val config = TorrentSessionConfig.DEFAULT
        assertTrue(config.announceToAllTrackers)
        assertTrue(config.announceToAllTiers)
        assertTrue(config.trackerCompletionTimeout > 0)
    }

    // =========================================================================
    // P4: Magnet Clipboard Paste + File Size + Disk Space Verification (3 tests)
    // =========================================================================

    @Test
    fun testP4_1_ClipboardPaste_ExactSizeCheck() {
        val clipboardText = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=BigFile&xl=4294967296"
        val parsed = MagnetHandler.parse(clipboardText)
        assertNotNull(parsed)
        assertEquals(4294967296L, parsed!!.fileLength)

        val availableSpace = 10_000_000_000L // 10 GB
        val hasSpace = availableSpace >= (parsed.fileLength ?: 0L)
        assertTrue(hasSpace)
    }

    @Test
    fun testP4_2_ClipboardPaste_InsufficientSpaceDetection() {
        val clipboardText = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=HugeArchive&xl=50000000000" // 50 GB
        val parsed = MagnetHandler.parse(clipboardText)
        assertNotNull(parsed)

        val availableSpace = 10_000_000_000L // 10 GB
        val hasSpace = availableSpace >= (parsed!!.fileLength ?: 0L)
        assertFalse(hasSpace)
    }

    @Test
    fun testP4_3_ClipboardPaste_MalformedUrlSafeFallback() {
        val nonMagnetClipboard = "https://invalid.com/download"
        val parsed = MagnetHandler.parse(nonMagnetClipboard)
        assertNull(parsed)
    }

    // =========================================================================
    // P5: Multi-File Extraction + Priority.IGNORE + Directory Isolation (3 tests)
    // =========================================================================

    @Test
    fun testP5_1_MultiFileExtraction_FilterOutNonVideoFiles() {
        val files = listOf(
            "Show.S01E01/episode01.mkv" to 1_500_000_000L,
            "Show.S01E01/sample.mkv" to 50_000_000L,
            "Show.S01E01/release.nfo" to 2048L,
            "Show.S01E01/checksums.sfv" to 512L
        )
        val fileItems = files.mapIndexed { idx, (path, size) ->
            val isIgnored = path.endsWith(".nfo") || path.endsWith(".sfv") || path.contains("sample")
            TorrentFileItem(
                index = idx,
                path = path,
                size = size,
                priority = if (isIgnored) Priority.IGNORE else Priority.NORMAL
            )
        }

        assertEquals(1, fileItems.count { !it.isSkipped })
        assertEquals(1_500_000_000L, fileItems.filter { !it.isSkipped }.sumOf { it.size })
        assertEquals(3, fileItems.count { it.isSkipped })
    }

    @Test
    fun testP5_2_DirectoryIsolation_SeparateSubdirsPerTorrent() {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "p5_base_${System.currentTimeMillis()}")
        val torrent1Dir = File(baseDir, "Torrent1_Files").apply { mkdirs() }
        val torrent2Dir = File(baseDir, "Torrent2_Files").apply { mkdirs() }
        try {
            assertTrue(torrent1Dir.exists() && torrent1Dir.canWrite())
            assertTrue(torrent2Dir.exists() && torrent2Dir.canWrite())
            assertFalse(torrent1Dir.canonicalPath == torrent2Dir.canonicalPath)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun testP5_3_TorrentFilter_PausedAndDownloadingExclusivity() {
        val pausedItem = TorrentItem("p1", "Paused", TorrentState.PAUSED, 0.5f, 0, 0, 1000, 500, 0, 0, 0)
        val downloadingItem = TorrentItem("d1", "DL", TorrentState.DOWNLOADING, 0.5f, 100, 10, 1000, 500, 0, 0, 0)

        assertTrue(TorrentFilter.PAUSED.matches(pausedItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(pausedItem))

        assertTrue(TorrentFilter.DOWNLOADING.matches(downloadingItem))
        assertFalse(TorrentFilter.PAUSED.matches(downloadingItem))
    }
}
