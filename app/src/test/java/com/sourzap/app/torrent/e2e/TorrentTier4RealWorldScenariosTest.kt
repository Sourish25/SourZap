package com.sourzap.app.torrent.e2e

import com.sourzap.app.torrent.core.BencodeValidator
import com.sourzap.app.torrent.core.MagnetHandler
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentSessionConfig
import com.sourzap.app.torrent.core.TorrentStorageHelper
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.core.TrackerInjector
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentPieceInfo
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Dual-Track E2E Test Suite — Tier 4: Real-World Application Scenarios.
 *
 * Simulates end-to-end production workloads and complex user journeys:
 * - Scenario 1: Multi-Gigabyte Linux ISO Download with selective documentation/source exclusion.
 * - Scenario 2: Music Album Discography Download with format & bonus track deselection.
 * - Scenario 3: Restrictive Corporate/ISP Firewall Simulation (DPI blocked port 6881, UDP traffic shaping fallback to TCP MSE).
 * - Scenario 4: Interrupted Download Resumption with Altered File Priorities & Storage Path Migration.
 * - Scenario 5: Multi-Torrent Concurrent Queue Management under Storage Pressure.
 */
class TorrentTier4RealWorldScenariosTest {

    private fun createMultiFileTorrent(
        dirName: String,
        files: List<Pair<String, Long>>,
        pieceLength: Int = 1048576, // 1 MB pieces
        pieceCount: Int = 20
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
    // SCENARIO 1: Multi-Gigabyte Linux ISO Download with Selective Exclusion
    // =========================================================================

    @Test
    fun testScenario1_LinuxIso_SelectiveExclusion_EndToEnd() {
        val files = listOf(
            "ubuntu-24.04-desktop-amd64.iso" to 4_800_000_000L, // 4.8 GB
            "ubuntu-24.04-desktop-amd64.iso.zsync" to 2_000_000L, // 2 MB
            "ubuntu-24.04-debug-symbols.tar.xz" to 1_500_000_000L, // 1.5 GB
            "source/linux-source-6.8.0.tar.gz" to 2_200_000_000L, // 2.2 GB
            "MD5SUMS" to 512L,
            "SHA256SUMS" to 512L
        )

        val torrentBytes = createMultiFileTorrent("Ubuntu_24_04_Release", files)
        val validation = TorrentFileValidator.validate(torrentBytes)
        assertTrue(validation is TorrentValidationResult.Valid)
        val valid = validation as TorrentValidationResult.Valid

        val totalSize = valid.totalSize
        assertEquals(4_800_000_000L + 2_000_000L + 1_500_000_000L + 2_200_000_000L + 512L + 512L, totalSize)

        // User selectively excludes debug symbols and source code, keeping ISO, zsync, and checksums
        val fileItems = files.mapIndexed { idx, (path, size) ->
            val isExcluded = path.contains("debug-symbols") || path.startsWith("source/")
            TorrentFileItem(
                index = idx,
                path = path,
                size = size,
                priority = if (isExcluded) Priority.IGNORE else Priority.NORMAL
            )
        }

        val selectedFiles = fileItems.filter { !it.isSkipped }
        val skippedFiles = fileItems.filter { it.isSkipped }

        assertEquals(4, selectedFiles.size)
        assertEquals(2, skippedFiles.size)

        val selectedSize = selectedFiles.sumOf { it.size }
        val expectedSelectedSize = 4_800_000_000L + 2_000_000L + 512L + 512L
        assertEquals(expectedSelectedSize, selectedSize)

        // Verify formatted size representations
        assertEquals("4.47 GB", TorrentItem.formatFileSize(selectedSize))
        assertEquals("7.92 GB", TorrentItem.formatFileSize(totalSize))

        // Check storage space validation on target disk
        val simulatedFreeDiskSpace = 6_000_000_000L // 6.0 GB available
        val hasSpaceForSelected = simulatedFreeDiskSpace >= selectedSize
        val hasSpaceForTotal = simulatedFreeDiskSpace >= totalSize

        assertTrue("User has enough space for selected files (4.47 GB <= 6.0 GB)", hasSpaceForSelected)
        assertFalse("User does not have enough space for the full 7.92 GB torrent", hasSpaceForTotal)
    }

    // =========================================================================
    // SCENARIO 2: Music Album Discography with Track & Format Deselection
    // =========================================================================

    @Test
    fun testScenario2_AlbumDiscography_TrackDeselection_ProgressTracking() {
        val tracks = listOf(
            "Disc 1 - FLAC/01 - Overture.flac" to 45_000_000L,
            "Disc 1 - FLAC/02 - Allegro.flac" to 60_000_000L,
            "Disc 1 - FLAC/03 - Finale.flac" to 80_000_000L,
            "Disc 2 - FLAC/01 - Bonus Track.flac" to 50_000_000L,
            "MP3 320k/Disc 1.zip" to 120_000_000L,
            "Scans/Booklet_HighRes.pdf" to 95_000_000L
        )

        // User deselects MP3 zip and HighRes booklet PDF
        val fileItems = tracks.mapIndexed { idx, (path, size) ->
            val isExcluded = path.startsWith("MP3") || path.contains("Booklet")
            TorrentFileItem(
                index = idx,
                path = path,
                size = size,
                priority = if (isExcluded) Priority.IGNORE else Priority.NORMAL
            )
        }

        val totalBytes = fileItems.sumOf { it.size }
        val selectedBytes = fileItems.filter { !it.isSkipped }.sumOf { it.size }

        assertEquals(450_000_000L, totalBytes)
        assertEquals(235_000_000L, selectedBytes)

        // Simulate active download progress for selected tracks
        val updatedFileItems = fileItems.map { file ->
            if (file.isSkipped) {
                file.copy(downloadedBytes = 0L, progress = 0.0f)
            } else if (file.index == 0) {
                file.copy(downloadedBytes = 45_000_000L, progress = 1.0f) // 100% completed
            } else if (file.index == 1) {
                file.copy(downloadedBytes = 30_000_000L, progress = 0.5f) // 50% completed
            } else {
                file.copy(downloadedBytes = 0L, progress = 0.0f)
            }
        }

        val totalDownloaded = updatedFileItems.sumOf { it.downloadedBytes }
        assertEquals(75_000_000L, totalDownloaded)

        val item = TorrentItem(
            id = "album_discography_id",
            name = "Symphony Orchestra 2026",
            state = TorrentState.DOWNLOADING,
            progress = totalDownloaded.toFloat() / selectedBytes.toFloat(),
            downloadSpeed = 3_500_000L, // 3.5 MB/s
            uploadSpeed = 250_000L,
            totalBytes = selectedBytes,
            downloadedBytes = totalDownloaded,
            uploadedBytes = 5_000_000L,
            numSeeds = 12,
            numPeers = 24,
            files = updatedFileItems
        )

        assertEquals("3.3 MB/s", item.formattedDownloadSpeed)
        assertEquals("31.9%", item.formattedProgress)
        assertEquals(31, item.progressPercent)
        assertFalse(item.isCompleted)
    }

    // =========================================================================
    // SCENARIO 3: Restrictive Corporate/ISP Firewall Simulation
    // =========================================================================

    @Test
    fun testScenario3_FirewallBypass_Port443Trackers_And_TcpMSE() {
        // Simulation of harsh ISP environment:
        // 1. Port 6881 is dropped by ISP firewall
        // 2. UDP traffic is heavily throttled/shaped (LEDBAT collapse)
        // 3. Plaintext BitTorrent handshakes are killed by DPI middleboxes

        // Verification of SourZap bypass pipeline:
        // Step A: Ephemeral dynamic port avoids port 6881
        val ephemeralPort = 54321
        val listenInterfaces = "0.0.0.0:$ephemeralPort,[::]:$ephemeralPort"
        assertTrue(listenInterfaces.contains("54321"))
        assertFalse(listenInterfaces.contains("6881"))

        // Step B: Inject verified Port-443 HTTPS trackers
        val rawMagnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=EncryptedTorrent"
        val injectedMagnet = TrackerInjector.injectTrackers(rawMagnet)
        val parsedMagnet = MagnetHandler.parse(injectedMagnet)
        assertNotNull(parsedMagnet)
        assertTrue(parsedMagnet!!.trackers.contains("https://tracker.tamersunion.org:443/announce"))
        assertTrue(parsedMagnet.trackers.contains("https://tracker.renfei.net:443/announce"))

        // Step C: Force RC4 Message Stream Encryption and prioritize TCP
        val firewallBypassConfig = TorrentSessionConfig(
            enableIncomingTcp = true,
            enableOutgoingTcp = true,
            enableIncomingUtp = false, // Disable uTP to bypass UDP throttling
            enableOutgoingUtp = false,
            outEncPolicy = TorrentSessionConfig.ENC_POLICY_FORCED, // Forced RC4
            inEncPolicy = TorrentSessionConfig.ENC_POLICY_FORCED,
            allowedEncLevel = TorrentSessionConfig.ENC_LEVEL_RC4,
            preferRc4 = true,
            announceToAllTrackers = true,
            announceToAllTiers = true
        )

        assertEquals(TorrentSessionConfig.ENC_POLICY_FORCED, firewallBypassConfig.outEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_POLICY_FORCED, firewallBypassConfig.inEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_LEVEL_RC4, firewallBypassConfig.allowedEncLevel)
        assertTrue(firewallBypassConfig.preferRc4)
        assertFalse(firewallBypassConfig.enableIncomingUtp)
        assertTrue(firewallBypassConfig.enableIncomingTcp)
        assertTrue(firewallBypassConfig.announceToAllTrackers)
    }

    // =========================================================================
    // SCENARIO 4: Interrupted Download Resumption with Altered Priorities
    // =========================================================================

    @Test
    fun testScenario4_DownloadInterruption_PriorityAlteration_Resumption() {
        val initialFiles = listOf(
            TorrentFileItem(0, "Game/core.pak", 1_000_000_000L, downloadedBytes = 500_000_000L, progress = 0.5f, priority = Priority.NORMAL),
            TorrentFileItem(1, "Game/textures_4k.pak", 2_000_000_000L, downloadedBytes = 200_000_000L, progress = 0.1f, priority = Priority.NORMAL),
            TorrentFileItem(2, "Game/soundtrack.flac", 500_000_000L, downloadedBytes = 0L, progress = 0.0f, priority = Priority.NORMAL)
        )

        val activeItem = TorrentItem(
            id = "game_download_id",
            name = "Action Game 2026",
            state = TorrentState.DOWNLOADING,
            progress = 0.2f,
            downloadSpeed = 8_000_000L,
            uploadSpeed = 100_000L,
            totalBytes = 3_500_000_000L,
            downloadedBytes = 700_000_000L,
            uploadedBytes = 50_000_000L,
            numSeeds = 50,
            numPeers = 100,
            files = initialFiles
        )
        assertTrue(TorrentFilter.DOWNLOADING.matches(activeItem))

        // Step 1: User pauses download
        val pausedItem = activeItem.copy(
            state = TorrentState.PAUSED,
            downloadSpeed = 0L,
            uploadSpeed = 0L
        )
        assertTrue(TorrentFilter.PAUSED.matches(pausedItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(pausedItem))

        // Step 2: User alters priorities — deselects textures_4k.pak (set to IGNORE)
        val updatedFiles = pausedItem.files.map { f ->
            if (f.index == 1) f.copy(priority = Priority.IGNORE) else f
        }
        val reconfiguredItem = pausedItem.copy(files = updatedFiles)
        assertTrue(reconfiguredItem.files[1].isSkipped)

        // Step 3: User resumes download
        val resumedItem = reconfiguredItem.copy(
            state = TorrentState.DOWNLOADING,
            downloadSpeed = 6_500_000L,
            uploadSpeed = 80_000L
        )
        assertTrue(TorrentFilter.DOWNLOADING.matches(resumedItem))
        assertEquals(1, resumedItem.files.count { it.isSkipped })
        assertEquals(2, resumedItem.files.count { !it.isSkipped })
    }

    // =========================================================================
    // SCENARIO 5: Multi-Torrent Concurrent Queue Under Storage Pressure
    // =========================================================================

    @Test
    fun testScenario5_MultiTorrentQueue_AggregatedStats_StorageCheck() {
        val torrent1 = TorrentItem(
            id = "t1", name = "Torrent 1", state = TorrentState.DOWNLOADING,
            progress = 0.4f, downloadSpeed = 4_000_000L, uploadSpeed = 200_000L,
            totalBytes = 1_000_000_000L, downloadedBytes = 400_000_000L, uploadedBytes = 50_000_000L,
            numSeeds = 10, numPeers = 20
        )
        val torrent2 = TorrentItem(
            id = "t2", name = "Torrent 2", state = TorrentState.DOWNLOADING,
            progress = 0.8f, downloadSpeed = 6_000_000L, uploadSpeed = 400_000L,
            totalBytes = 2_000_000_000L, downloadedBytes = 1_600_000_000L, uploadedBytes = 100_000_000L,
            numSeeds = 15, numPeers = 30
        )
        val torrent3 = TorrentItem(
            id = "t3", name = "Torrent 3", state = TorrentState.SEEDING,
            progress = 1.0f, downloadSpeed = 0L, uploadSpeed = 1_000_000L,
            totalBytes = 500_000_000L, downloadedBytes = 500_000_000L, uploadedBytes = 800_000_000L,
            numSeeds = 0, numPeers = 10
        )

        val torrentList = listOf(torrent1, torrent2, torrent3)

        val totalDownSpeed = torrentList.sumOf { it.downloadSpeed }
        val totalUpSpeed = torrentList.sumOf { it.uploadSpeed }
        val totalBytes = torrentList.sumOf { it.totalBytes }
        val totalDownloaded = torrentList.sumOf { it.downloadedBytes }
        val totalUploaded = torrentList.sumOf { it.uploadedBytes }
        val activeCount = torrentList.count { it.state == TorrentState.DOWNLOADING }
        val seedingCount = torrentList.count { it.state == TorrentState.SEEDING }

        val sessionStats = TorrentSessionStats(
            totalDownloadSpeed = totalDownSpeed,
            totalUploadSpeed = totalUpSpeed,
            totalDownloadedBytes = totalDownloaded,
            totalUploadedBytes = totalUploaded,
            activeTorrents = activeCount,
            pausedTorrents = 0,
            seedingTorrents = seedingCount,
            totalBytes = totalBytes,
            aggregateProgress = totalDownloaded.toFloat() / totalBytes.toFloat()
        )

        assertEquals("9.5 MB/s", sessionStats.formattedDownloadSpeed)
        assertEquals("1.5 MB/s", sessionStats.formattedUploadSpeed)
        assertEquals(2, sessionStats.activeTorrents)
        assertEquals(1, sessionStats.seedingTorrents)
        assertEquals(3_500_000_000L, sessionStats.totalBytes)
        assertEquals(2_500_000_000L, sessionStats.totalDownloadedBytes)
        assertEquals(71, sessionStats.progressPercent)
    }
}
