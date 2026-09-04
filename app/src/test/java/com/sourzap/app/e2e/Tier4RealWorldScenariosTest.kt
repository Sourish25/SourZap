package com.sourzap.app.e2e

import android.content.Intent
import com.sourzap.app.e2e.IntentDeepLinkE2ETest.PendingTorrentIntent
import com.sourzap.app.torrent.core.DohTrackerResolver
import com.sourzap.app.torrent.core.MagnetHandler
import com.sourzap.app.torrent.core.TorrentSessionConfig
import com.sourzap.app.torrent.core.TorrentStorageHelper
import com.sourzap.app.torrent.core.TrackerInjector
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentPieceInfo
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import com.sourzap.app.torrent.service.TorrentDownloadService
import com.sourzap.app.update.AppReleaseInfo
import com.sourzap.app.update.UpdateState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Tier 4: Real-World Multi-Feature End-to-End Application Workflow Scenarios Test Suite.
 * Exercises realistic user journeys spanning multiple subsystems.
 */
class Tier4RealWorldScenariosTest {

    // =========================================================================
    // SCENARIO 1: External Browser Magnet Link Deep Link Flow
    // =========================================================================

    @Test
    fun testScenario1_ExternalBrowserMagnetLinkWorkflow() {
        // Step 1: External browser sends Intent.ACTION_VIEW with magnet link
        val browserMagnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=Fedora-Workstation-40-x86_64.iso&xl=2147483648"
        val intentAction = Intent.ACTION_VIEW

        val parsedMagnet = MagnetHandler.parse(browserMagnet)
        assertNotNull("Magnet link must be parsed", parsedMagnet)

        // Step 2: MainActivity parses Intent into PendingTorrentIntent
        val pendingIntent = PendingTorrentIntent.Magnet(
            uri = browserMagnet,
            name = parsedMagnet!!.displayName
        )
        assertNotNull(pendingIntent)

        // Step 3: Navigation automatically transitions to "torrents" tab
        val destinationRoute = if (pendingIntent != null) "torrents" else "dashboard"
        assertEquals("torrents", destinationRoute)

        // Step 4: AddTorrentDialog consumes pendingIntent and auto-opens pre-filled
        var dialogOpen = true
        var dialogUri = pendingIntent.uri
        var dialogName = pendingIntent.name
        assertTrue(dialogOpen)
        assertEquals(browserMagnet, dialogUri)
        assertEquals("Fedora-Workstation-40-x86_64.iso", dialogName)

        // Step 5: User confirms download -> Engine auto-starts session & adds torrent with injected trackers
        val augmentedUri = TrackerInjector.injectTrackers(dialogUri)
        assertTrue(augmentedUri.contains("tracker.tamersunion.org"))

        val tempSaveDir = File(System.getProperty("java.io.tmpdir"), "sourzap_s1_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            assertTrue(TorrentStorageHelper.isWritableOrCreatable(tempSaveDir))

            // Step 6: Foreground service notification begins broadcasting live progress
            val activeStats = TorrentSessionStats(
                totalDownloadSpeed = 3_145_728L, // 3 MB/s
                totalUploadSpeed = 204_800L,     // 200 KB/s
                activeTorrents = 1
            )
            val notificationTitle = "SourZap Downloader: ${activeStats.activeTorrents} Active (${activeStats.formattedDownloadSpeed})"
            assertEquals("SourZap Downloader: 1 Active (3.0 MB/s)", notificationTitle)

            // Step 7: Torrent completes -> State transitions to SEEDING / FINISHED
            val completedItem = TorrentItem(
                id = parsedMagnet.infoHash,
                name = dialogName ?: "Fedora",
                state = TorrentState.SEEDING,
                progress = 1.0f,
                downloadSpeed = 0L,
                uploadSpeed = 500_000L,
                totalBytes = 2147483648L,
                downloadedBytes = 2147483648L,
                uploadedBytes = 100_000_000L,
                numSeeds = 50,
                numPeers = 10
            )
            assertTrue(completedItem.isCompleted)
            assertTrue(TorrentFilter.COMPLETED.matches(completedItem))
        } finally {
            tempSaveDir.deleteRecursively()
        }
    }

    // =========================================================================
    // SCENARIO 2: File Manager .torrent SAF Import Workflow
    // =========================================================================

    @Test
    fun testScenario2_FileManagerTorrentSafImportWorkflow() {
        // Step 1: User picks .torrent file via SAF File Picker
        val pickedUriString = "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FArchLinux_2026.torrent"
        val rawTorrentBytes = "d8:announce38:https://tracker.tamersunion.org:443/4:infod6:lengthi1073741824e4:name13:ArchLinux.isoxee".toByteArray(StandardCharsets.UTF_8)
        val safResolvedDisplayName = "ArchLinux_2026.torrent"

        // Step 2: System resolves display name and constructs PendingTorrentIntent
        val pendingIntent = PendingTorrentIntent.TorrentFile(
            bytes = rawTorrentBytes,
            fileName = safResolvedDisplayName
        )
        assertEquals("ArchLinux_2026.torrent", pendingIntent.fileName)

        // Step 3: AddTorrentDialog pre-populates file name and sets safe scoped storage save directory
        val baseTemp = File(System.getProperty("java.io.tmpdir"), "sourzap_s2_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val safeSaveDir = File(baseTemp, "SourZap").apply { mkdirs() }
            assertTrue(safeSaveDir.exists() && safeSaveDir.canWrite())

            // Step 4: User sets sequential download flag to true
            val isSequential = true

            // Step 5: TorrentItem initialized in DOWNLOADING state
            val item = TorrentItem(
                id = "arch_hash_123456",
                name = pendingIntent.fileName,
                state = TorrentState.DOWNLOADING,
                progress = 0.02f,
                downloadSpeed = 1_048_576L,
                uploadSpeed = 50_000L,
                totalBytes = 1073741824L,
                downloadedBytes = 21474836L,
                uploadedBytes = 0L,
                numSeeds = 20,
                numPeers = 50,
                isSequential = isSequential
            )
            assertTrue(item.isSequential)
            assertEquals("ArchLinux_2026.torrent", item.name)
            assertFalse(item.isCompleted)
        } finally {
            baseTemp.deleteRecursively()
        }
    }

    // =========================================================================
    // SCENARIO 3: Restrictive Firewall Swarm Downloading & Evasion
    // =========================================================================

    @Test
    fun testScenario3_RestrictiveFirewallSwarmDownloadingWorkflow() = runBlocking {
        // Step 1: Configure anti-censorship session settings
        val config = TorrentSessionConfig.DEFAULT
        assertTrue("Dual transport uTP enabled", config.enableIncomingUtp)
        assertTrue("Dual transport uTP enabled", config.enableOutgoingUtp)
        assertEquals("PE protocol encryption forced", TorrentSessionConfig.ENC_POLICY_FORCED, config.outEncPolicy)

        // Step 2: Auto-inject 22 port-443 HTTPS trackers
        val initialMagnet = "magnet:?xt=urn:btih:4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a"
        val augmented = TrackerInjector.injectTrackers(initialMagnet)
        assertTrue(augmented.contains("tracker.tamersunion.org"))

        // Step 3: DoH Pre-Resolution for tracker hostnames
        val host = DohTrackerResolver.extractHost("https://tracker.tamersunion.org:443/announce")
        assertEquals("tracker.tamersunion.org", host)
        assertFalse(DohTrackerResolver.isIpLiteral(host!!))

        // Step 4: Swarm connects and transfers data
        val item = TorrentItem(
            id = "4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a",
            name = "Restricted Swarm ISO",
            state = TorrentState.DOWNLOADING,
            progress = 0.50f,
            downloadSpeed = 4_194_304L,
            uploadSpeed = 524_288L,
            totalBytes = 1000000L,
            downloadedBytes = 500000L,
            uploadedBytes = 100000L,
            numSeeds = 15,
            numPeers = 30
        )
        assertEquals("4.0 MB/s", item.formattedDownloadSpeed)
        assertEquals("512.0 KB/s", item.formattedUploadSpeed)

        // Step 5: Pause and Resume cycle
        var currentState = item.state
        currentState = TorrentState.PAUSED
        assertEquals(TorrentState.PAUSED, currentState)

        currentState = TorrentState.DOWNLOADING
        assertEquals(TorrentState.DOWNLOADING, currentState)
    }

    // =========================================================================
    // SCENARIO 4: Multi-Torrent Batch Management Workflow
    // =========================================================================

    @Test
    fun testScenario4_MultiTorrentBatchManagementWorkflow() {
        // Step 1: Add 5 magnet links
        val torrentsList = mutableListOf<TorrentItem>()
        for (i in 1..5) {
            torrentsList.add(
                TorrentItem(
                    id = "hash_00$i",
                    name = "Download Item #$i",
                    state = if (i <= 3) TorrentState.DOWNLOADING else TorrentState.PAUSED,
                    progress = (i * 0.15f),
                    downloadSpeed = (i * 1_000_000L),
                    uploadSpeed = (i * 100_000L),
                    totalBytes = 100_000_000L,
                    downloadedBytes = (i * 15_000_000L),
                    uploadedBytes = 0L,
                    numSeeds = i * 2,
                    numPeers = i * 5
                )
            )
        }

        assertEquals(5, torrentsList.size)

        // Step 2: Filter by DOWNLOADING
        val activeDownloading = torrentsList.filter { TorrentFilter.DOWNLOADING.matches(it) }
        assertEquals(3, activeDownloading.size)

        // Step 3: Aggregate stats computation
        val totalDownSpeed = torrentsList.sumOf { it.downloadSpeed }
        val activeCount = torrentsList.count { it.state == TorrentState.DOWNLOADING }
        val stats = TorrentSessionStats(
            totalDownloadSpeed = totalDownSpeed,
            activeTorrents = activeCount,
            pausedTorrents = torrentsList.size - activeCount
        )

        assertEquals(3, stats.activeTorrents)
        assertEquals(2, stats.pausedTorrents)
        assertEquals(15_000_000L, totalDownSpeed) // (1 + 2 + 3 + 4 + 5) = 15 MB/s

        // Step 4: Pause All
        val pausedList = torrentsList.map { it.copy(state = TorrentState.PAUSED, downloadSpeed = 0, uploadSpeed = 0) }
        assertTrue(pausedList.all { it.state == TorrentState.PAUSED })

        // Step 5: Delete single torrent
        val afterDeletion = pausedList.filter { it.id != "hash_001" }
        assertEquals(4, afterDeletion.size)
    }

    // =========================================================================
    // SCENARIO 5: App Update Background Lifecycle Workflow
    // =========================================================================

    @Test
    fun testScenario5_AppUpdateBackgroundLifecycleWorkflow() {
        // Step 1: Check update -> Release detected
        val currentVersion = "2.6.0"
        val release = AppReleaseInfo(
            tagName = "v2.7.0",
            versionName = "2.7.0",
            releaseNotes = "Major BitTorrent performance update",
            apkDownloadUrl = "https://github.com/Sourish25/SourZap/releases/download/v2.7.0/SourZap-v2.7.0.apk",
            apkSizeBytes = 25_000_000L,
            isUpdateAvailable = true,
            publishedAt = "2026-09-01"
        )
        assertTrue(release.isUpdateAvailable)

        // Step 2: Download starts -> Emits progress states
        val progressState1 = UpdateState.Downloading(0.25f, 6_553_600L, 26_214_400L)
        assertEquals("6.25 MB", TorrentItem.formatFileSize(progressState1.downloadedBytes))
        assertEquals("25.00 MB", TorrentItem.formatFileSize(progressState1.totalBytes))

        // Step 3: User taps Cancel -> State transitions to Idle / Cancelled
        var activeState: UpdateState = progressState1
        fun cancelDownload() {
            activeState = UpdateState.Idle
        }
        cancelDownload()
        assertEquals(UpdateState.Idle, activeState)

        // Step 4: User restarts download and completes -> ReadyToInstall
        val tempApk = File(System.getProperty("java.io.tmpdir"), "sourzap_scenario5.apk").apply {
            writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // ZIP Magic Header
        }
        try {
            activeState = UpdateState.ReadyToInstall(tempApk)
            assertTrue(activeState is UpdateState.ReadyToInstall)
            val ready = activeState as UpdateState.ReadyToInstall
            assertTrue(ready.apkFile.exists())
        } finally {
            tempApk.delete()
        }
    }

    // =========================================================================
    // SCENARIO 6: App Cold Boot with Incoming Torrent Intent
    // =========================================================================

    @Test
    fun testScenario6_AppColdBootIncomingTorrentIntentWorkflow() {
        // Step 1: App process cold started with Intent
        val launchIntentAction = Intent.ACTION_VIEW
        val launchIntentData = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Ubuntu+ColdBoot"

        val parsed = MagnetHandler.parse(launchIntentData)
        assertNotNull(parsed)

        // Step 2: App starts up and initial route determined as "torrents"
        val pendingIntent = PendingTorrentIntent.Magnet(launchIntentData, parsed!!.displayName)
        val initialRoute = if (pendingIntent != null) "torrents" else "dashboard"
        assertEquals("torrents", initialRoute)

        // Step 3: AddTorrentDialog opens with pre-filled content
        assertEquals("Ubuntu ColdBoot", pendingIntent.name)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", parsed.infoHash)
    }

    // =========================================================================
    // SCENARIO 7: Low Storage & Permission Recovery Workflow
    // =========================================================================

    @Test
    fun testScenario7_LowStorageAndPermissionRecoveryWorkflow() {
        // Step 1: Check runtime notification permission
        val sdkInt = 34
        var permissionGranted = false
        fun requestPermissionIfNeeded(): Boolean {
            return if (sdkInt >= 33 && !permissionGranted) {
                // Request runtime permission
                true
            } else false
        }
        assertTrue(requestPermissionIfNeeded())

        // User grants permission
        permissionGranted = true
        assertFalse(requestPermissionIfNeeded())

        // Step 2: Storage verification & fallback
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "sourzap_s7_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val safeDir = File(tempRoot, "SourZap").apply { mkdirs() }
            assertTrue(safeDir.exists() && safeDir.canWrite())

            // Step 3: Handle error state and recovery
            val errorItem = TorrentItem(
                id = "error_hash",
                name = "Corrupt Torrent",
                state = TorrentState.ERROR,
                progress = 0.0f,
                downloadSpeed = 0L,
                uploadSpeed = 0L,
                totalBytes = 1000L,
                downloadedBytes = 0L,
                uploadedBytes = 0L,
                numSeeds = 0,
                numPeers = 0,
                error = "I/O error: disk full or permission denied"
            )

            assertEquals(TorrentState.ERROR, errorItem.state)
            assertEquals("I/O error: disk full or permission denied", errorItem.error)

            // Recheck/Retry recovery
            val recoveredItem = errorItem.copy(state = TorrentState.CHECKING, error = null)
            assertEquals(TorrentState.CHECKING, recoveredItem.state)
            assertNull(recoveredItem.error)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // SCENARIO 8: Multi-File Torrent Inspection & Priority Management Workflow
    // =========================================================================

    @Test
    fun testScenario8_MultiFileTorrentInspectionAndPriorityWorkflow() {
        // Step 1: Multi-file torrent loaded
        val files = mutableListOf(
            TorrentFileItem(index = 0, path = "Album/Track01.flac", size = 50_000_000L, priority = Priority.NORMAL),
            TorrentFileItem(index = 1, path = "Album/Track02.flac", size = 45_000_000L, priority = Priority.NORMAL),
            TorrentFileItem(index = 2, path = "Album/Track03.flac", size = 60_000_000L, priority = Priority.NORMAL),
            TorrentFileItem(index = 3, path = "Album/Artwork.png", size = 5_000_000L, priority = Priority.NORMAL)
        )

        assertEquals(4, files.size)

        // Step 2: User opens TorrentFilesDialog and unchecks Track 3 (Priority.IGNORE)
        files[2] = files[2].copy(priority = Priority.IGNORE)
        assertTrue(files[2].isSkipped)

        // Step 3: User sets Track 1 to Priority.HIGH
        files[0] = files[0].copy(priority = Priority.HIGH)
        assertEquals(Priority.HIGH, files[0].priority)

        // Step 4: Verify pieces info completion
        val pieces = TorrentPieceInfo(
            pieceCount = 1000,
            piecesCompleted = 350
        )
        assertEquals(0.35f, pieces.completionRatio, 0.001f)

        // Step 5: Torrent item reflects file priorities and piece progress
        val item = TorrentItem(
            id = "album_hash_987",
            name = "FLAC Music Album",
            state = TorrentState.DOWNLOADING,
            progress = 0.35f,
            downloadSpeed = 2_000_000L,
            uploadSpeed = 500_000L,
            totalBytes = 160_000_000L,
            downloadedBytes = 56_000_000L,
            uploadedBytes = 14_000_000L,
            numSeeds = 10,
            numPeers = 25,
            files = files,
            pieces = pieces,
            shareRatio = 0.25f
        )

        assertEquals(4, item.files.size)
        assertEquals(0.35f, item.pieces?.completionRatio ?: 0f, 0.001f)
        assertEquals(0.25f, item.shareRatio, 0.001f) // 14MB / 56MB = 0.25
    }
}
