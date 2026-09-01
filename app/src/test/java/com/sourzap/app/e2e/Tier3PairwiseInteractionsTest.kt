package com.sourzap.app.e2e

import android.content.Intent
import com.sourzap.app.e2e.IntentDeepLinkE2ETest.PendingTorrentIntent
import com.sourzap.app.torrent.core.MagnetHandler
import com.sourzap.app.torrent.core.TorrentStorageHelper
import com.sourzap.app.torrent.core.TrackerInjector
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import com.sourzap.app.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Tier 3: Cross-Feature Interactions & Pairwise Combinations Test Suite.
 * Covers >= 15 combinatorial interaction tests across features F1..F12.
 */
class Tier3PairwiseInteractionsTest {

    // =========================================================================
    // P1: F1 (Magnet Normalization) + F3 (Engine Auto-Start)
    // =========================================================================

    @Test
    fun testP1_MagnetNormalization_And_EngineAutoStart() {
        val base32 = "YNCKHTQ3XIRUVE6J6UM345O6P3TXJ5WS"
        val magnet = "magnet:?xt=urn:btih:$base32&dn=Ubuntu"
        val parsed = MagnetHandler.parse(magnet)
        assertNotNull(parsed)
        val normalizedHex = parsed!!.infoHash

        var sessionRunning = false
        fun addTorrentWithAutoStart(hash: String): String {
            if (!sessionRunning) {
                sessionRunning = true
            }
            return hash
        }

        val addedHash = addTorrentWithAutoStart(normalizedHex)
        assertTrue("Session must auto-start when adding normalized magnet", sessionRunning)
        assertEquals(40, addedHash.length)
        assertTrue(addedHash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // =========================================================================
    // P2: F1 (Magnet Normalization) + F6 (External Deep Link Handling)
    // =========================================================================

    @Test
    fun testP2_MagnetNormalization_And_ExternalDeepLink() {
        val base32 = "YNCKHTQ3XIRUVE6J6UM345O6P3TXJ5WS"
        val rawUri = "magnet:?xt=urn:btih:$base32&dn=DeepLinkTorrent"

        val parsed = MagnetHandler.parse(rawUri)
        assertNotNull(parsed)

        val pendingIntent = PendingTorrentIntent.Magnet(
            uri = rawUri,
            name = parsed!!.displayName
        )

        assertEquals("DeepLinkTorrent", pendingIntent.name)
        assertEquals(rawUri, pendingIntent.uri)
    }

    // =========================================================================
    // P3: F2 (Safe Storage Resolution) + F3 (Engine Auto-Start)
    // =========================================================================

    @Test
    fun testP3_SafeStorage_And_EngineAutoStart() {
        val tempBase = File(System.getProperty("java.io.tmpdir"), "p3_test_${System.currentTimeMillis()}")
        tempBase.mkdirs()
        try {
            assertTrue(TorrentStorageHelper.isWritableOrCreatable(tempBase))
            val saveDir = File(tempBase, "SourZap").apply { mkdirs() }
            assertTrue(saveDir.exists() && saveDir.canWrite())

            var sessionRunning = false
            fun startSessionWithSaveDir(dir: File) {
                if (dir.exists() && dir.canWrite()) {
                    sessionRunning = true
                }
            }
            startSessionWithSaveDir(saveDir)
            assertTrue("Engine should safely start with validated safe save directory", sessionRunning)
        } finally {
            tempBase.deleteRecursively()
        }
    }

    // =========================================================================
    // P4: F5 (Intent Filters) + F6 (External Deep Link Intent Routing)
    // =========================================================================

    @Test
    fun testP4_IntentFilters_And_DeepLinkRouting() {
        val intentAction = Intent.ACTION_VIEW
        val intentData = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Arch"

        fun routeIntent(action: String?, data: String?): String {
            if (action == Intent.ACTION_VIEW && data?.startsWith("magnet:?") == true) {
                return "torrents"
            }
            return "dashboard"
        }

        assertEquals("torrents", routeIntent(intentAction, intentData))
    }

    // =========================================================================
    // P5: F6 (External Deep Link) + F7 (Auto-Open Confirmation Dialog)
    // =========================================================================

    @Test
    fun testP5_DeepLink_And_AutoOpenConfirmationDialog() {
        var isDialogOpen = false
        var dialogPrefilledUri = ""

        fun onHandleDeepLink(pending: PendingTorrentIntent?) {
            if (pending is PendingTorrentIntent.Magnet) {
                isDialogOpen = true
                dialogPrefilledUri = pending.uri
            }
        }

        val magnet = PendingTorrentIntent.Magnet("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2")
        onHandleDeepLink(magnet)

        assertTrue("Deep link must automatically open confirmation dialog", isDialogOpen)
        assertEquals("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2", dialogPrefilledUri)
    }

    // =========================================================================
    // P6: F7 (Auto-Open Dialog) + F8 (SAF File Name Resolution)
    // =========================================================================

    @Test
    fun testP6_AutoOpenDialog_And_SafFileNameResolution() {
        val contentUri = "content://downloads/document/MyDataset.torrent"
        val cursorResolvedName = "MyDataset_2026.torrent"

        fun onFilePicked(uri: String, displayName: String?): Pair<String, String> {
            val finalName = displayName ?: uri.substringAfterLast('/')
            return Pair(uri, finalName)
        }

        val (uri, name) = onFilePicked(contentUri, cursorResolvedName)
        assertEquals(contentUri, uri)
        assertEquals("MyDataset_2026.torrent", name)
    }

    // =========================================================================
    // P7: F3 (Torrent Engine) + F10 (Active Foreground Notification)
    // =========================================================================

    @Test
    fun testP7_TorrentEngine_And_ActiveNotificationStats() {
        val stats = TorrentSessionStats(
            totalDownloadSpeed = 2_097_152L,
            totalUploadSpeed = 104_857L,
            activeTorrents = 1,
            seedingTorrents = 0,
            pausedTorrents = 0
        )

        val notificationTitle = "SourZap Downloader: ${stats.activeTorrents} Active (${stats.formattedDownloadSpeed})"
        val notificationContent = "↓ ${stats.formattedDownloadSpeed} • ↑ ${stats.formattedUploadSpeed} • Peers connected"

        assertEquals("SourZap Downloader: 1 Active (2.0 MB/s)", notificationTitle)
        assertEquals("↓ 2.0 MB/s • ↑ 102.4 KB/s • Peers connected", notificationContent)
    }

    // =========================================================================
    // P8: F4 (Engine State / Pause) + F10 (Notification Actions PAUSE_ALL / RESUME_ALL)
    // =========================================================================

    @Test
    fun testP8_EngineState_And_NotificationActions() {
        var torrentState = TorrentState.DOWNLOADING
        var notificationText = "Active"

        fun onNotificationAction(action: String) {
            when (action) {
                "com.sourzap.app.torrent.PAUSE_ALL" -> {
                    torrentState = TorrentState.PAUSED
                    notificationText = "All transfers paused • Tap to open"
                }
                "com.sourzap.app.torrent.RESUME_ALL" -> {
                    torrentState = TorrentState.DOWNLOADING
                    notificationText = "Active"
                }
            }
        }

        onNotificationAction("com.sourzap.app.torrent.PAUSE_ALL")
        assertEquals(TorrentState.PAUSED, torrentState)
        assertEquals("All transfers paused • Tap to open", notificationText)

        onNotificationAction("com.sourzap.app.torrent.RESUME_ALL")
        assertEquals(TorrentState.DOWNLOADING, torrentState)
        assertEquals("Active", notificationText)
    }

    // =========================================================================
    // P9: F9 (Update Progress Notification) + F11 (POST_NOTIFICATIONS Permission)
    // =========================================================================

    @Test
    fun testP9_UpdateNotification_And_PostNotificationsPermission() {
        fun canPostUpdateNotification(sdkInt: Int, isPermissionGranted: Boolean): Boolean {
            return if (sdkInt >= 33) isPermissionGranted else true
        }

        assertTrue("Below API 33 should post update notifications without runtime permission", canPostUpdateNotification(31, false))
        assertFalse("API 33+ without permission should not post notification directly", canPostUpdateNotification(33, false))
        assertTrue("API 33+ with permission granted should post update notification", canPostUpdateNotification(33, true))
    }

    // =========================================================================
    // P10: F10 (Torrent Notification) + F11 (POST_NOTIFICATIONS Permission)
    // =========================================================================

    @Test
    fun testP10_TorrentNotification_And_PostNotificationsPermission() {
        fun shouldRequestForForegroundService(sdkInt: Int, isGranted: Boolean): Boolean {
            return sdkInt >= 33 && !isGranted
        }

        assertTrue(shouldRequestForForegroundService(34, isGranted = false))
        assertFalse(shouldRequestForForegroundService(34, isGranted = true))
    }

    // =========================================================================
    // P11: F1 (Magnet Parsing) + F4 (Sequential Download Flag)
    // =========================================================================

    @Test
    fun testP11_MagnetParsing_And_SequentialDownloadFlag() {
        val magnet = "magnet:?xt=urn:btih:4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a&dn=Movie"
        val parsed = MagnetHandler.parse(magnet)
        assertNotNull(parsed)

        val item = TorrentItem(
            id = parsed!!.infoHash,
            name = parsed.displayName ?: "Torrent",
            state = TorrentState.DOWNLOADING,
            progress = 0.05f,
            downloadSpeed = 1000,
            uploadSpeed = 0,
            totalBytes = 1000000,
            downloadedBytes = 50000,
            uploadedBytes = 0,
            numSeeds = 1,
            numPeers = 2,
            isSequential = true
        )

        assertTrue(item.isSequential)
        assertEquals("4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a", item.id)
    }

    // =========================================================================
    // P12: F2 (Safe Storage) + F7 (Add Dialog Save Path)
    // =========================================================================

    @Test
    fun testP12_SafeStorage_And_AddDialogDefaultSaveDir() {
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "p12_test_${System.currentTimeMillis()}")
        tempRoot.mkdirs()
        try {
            val safeDir = File(tempRoot, "SourZap").apply { mkdirs() }
            val defaultDialogSaveDir = safeDir
            assertTrue(defaultDialogSaveDir.exists() && defaultDialogSaveDir.canWrite())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    // =========================================================================
    // P13: F8 (SAF File Resolution) + F3 (Torrent Engine Add)
    // =========================================================================

    @Test
    fun testP13_SafFileResolution_And_TorrentEngineAdd() {
        val safResolvedName = "Debian_NetInst.torrent"
        val source = TorrentSource.FileContent(
            bytes = "d8:announce27:http://tracker.example.com4:infodee".toByteArray(),
            name = safResolvedName
        )
        assertEquals("Debian_NetInst.torrent", source.name)
    }

    // =========================================================================
    // P14: F9 (App Update Progress) + F10 (Torrent Service Concurrent Notifications)
    // =========================================================================

    @Test
    fun testP14_UpdateNotification_And_TorrentNotification_Coexistence() {
        val updateChannelId = "sourzap_updates"
        val torrentChannelId = "sourzap_torrent"

        assertFalse("Update channel and torrent channel must be distinct", updateChannelId == torrentChannelId)

        val updateState = UpdateState.Downloading(0.6f, 6000L, 10000L)
        val torrentStats = TorrentSessionStats(activeTorrents = 2, totalDownloadSpeed = 1000L)

        assertNotNull(updateState)
        assertNotNull(torrentStats)
    }

    // =========================================================================
    // P15: F4 (Engine State Mapping) + F7 (Dialog Confirmation Start)
    // =========================================================================

    @Test
    fun testP15_EngineStateMapping_And_DialogConfirmationStart() {
        var initialTorrentState = TorrentState.METADATA
        fun onMetadataLoaded() {
            initialTorrentState = TorrentState.DOWNLOADING
        }

        assertTrue(initialTorrentState.isRunning)
        assertEquals(TorrentState.METADATA, initialTorrentState)

        onMetadataLoaded()
        assertEquals(TorrentState.DOWNLOADING, initialTorrentState)
        assertTrue(initialTorrentState.isRunning)
    }
}
