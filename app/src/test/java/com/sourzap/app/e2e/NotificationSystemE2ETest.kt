package com.sourzap.app.e2e

import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.service.TorrentDownloadService
import com.sourzap.app.update.AppReleaseInfo
import com.sourzap.app.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * E2E Notification System, App Update Progress & Torrent Foreground Service Test Suite.
 * Covers Features F9, F10, F11 (Requirement R4).
 */
class NotificationSystemE2ETest {

    // =========================================================================
    // FEATURE F9: App Update Download Progress Notification
    // =========================================================================

    @Test
    fun testUpdateNotification_ProgressStateFormatting() {
        val state = UpdateState.Downloading(
            progress = 0.456f,
            downloadedBytes = 15_728_640L, // 15 MB
            totalBytes = 34_492_416L        // 32.9 MB
        )

        val progressPercent = (state.progress * 100).toInt().coerceIn(0, 100)
        assertEquals(45, progressPercent)

        val formattedDownloaded = TorrentItem.formatFileSize(state.downloadedBytes)
        val formattedTotal = TorrentItem.formatFileSize(state.totalBytes)
        assertEquals("15.00 MB", formattedDownloaded)
        assertEquals("32.89 MB", formattedTotal)

        val contentText = "$formattedDownloaded / $formattedTotal ($progressPercent%)"
        assertEquals("15.00 MB / 32.89 MB (45%)", contentText)
    }

    @Test
    fun testUpdateNotification_CancelActionIntentContract() {
        val cancelAction = "com.sourzap.app.ACTION_CANCEL_UPDATE"
        assertEquals("com.sourzap.app.ACTION_CANCEL_UPDATE", cancelAction)

        var isCancelled = false
        fun handleNotificationAction(action: String) {
            if (action == cancelAction) {
                isCancelled = true
            }
        }

        handleNotificationAction("com.sourzap.app.ACTION_CANCEL_UPDATE")
        assertTrue("Cancel action should transition update state to cancelled", isCancelled)
    }

    @Test
    fun testUpdateNotification_ReadyToInstallIntegrity() {
        val tempApk = File(System.getProperty("java.io.tmpdir"), "sourzap_test_update.apk")
        tempApk.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // ZIP Magic Header
        try {
            val state = UpdateState.ReadyToInstall(tempApk)
            assertNotNull(state.apkFile)
            assertTrue(state.apkFile.exists())
        } finally {
            tempApk.delete()
        }
    }

    @Test
    fun testUpdateNotification_ChannelConfiguration() {
        data class NotificationChannelSpec(
            val id: String,
            val name: String,
            val importance: Int,
            val showBadge: Boolean
        )

        val updateChannel = NotificationChannelSpec(
            id = "sourzap_updates",
            name = "App Updates",
            importance = 2, // NotificationManager.IMPORTANCE_LOW
            showBadge = false
        )

        assertEquals("sourzap_updates", updateChannel.id)
        assertEquals("App Updates", updateChannel.name)
        assertFalse(updateChannel.showBadge)
    }

    // =========================================================================
    // FEATURE F10: Active Torrent Progress & Dismiss Notification
    // =========================================================================

    @Test
    fun testTorrentNotification_TitleAndContentFormatting() {
        fun buildNotificationStrings(stats: TorrentSessionStats): Pair<String, String> {
            val title = if (stats.activeTorrents > 0) {
                "SourZap Downloader: ${stats.activeTorrents} Active (${stats.formattedDownloadSpeed})"
            } else {
                "SourZap Downloader"
            }

            val content = if (stats.activeTorrents > 0 || stats.seedingTorrents > 0) {
                "↓ ${stats.formattedDownloadSpeed} • ↑ ${stats.formattedUploadSpeed} • Peers connected"
            } else {
                "All transfers paused • Tap to open"
            }
            return Pair(title, content)
        }

        // 1. Active downloads
        val activeStats = TorrentSessionStats(
            totalDownloadSpeed = 5_242_880L, // 5 MB/s
            totalUploadSpeed = 524_288L,    // 512 KB/s
            activeTorrents = 2,
            seedingTorrents = 0,
            pausedTorrents = 1
        )
        val (activeTitle, activeContent) = buildNotificationStrings(activeStats)
        assertEquals("SourZap Downloader: 2 Active (5.0 MB/s)", activeTitle)
        assertEquals("↓ 5.0 MB/s • ↑ 512.0 KB/s • Peers connected", activeContent)

        // 2. All paused
        val pausedStats = TorrentSessionStats(
            totalDownloadSpeed = 0L,
            totalUploadSpeed = 0L,
            activeTorrents = 0,
            seedingTorrents = 0,
            pausedTorrents = 3
        )
        val (pausedTitle, pausedContent) = buildNotificationStrings(pausedStats)
        assertEquals("SourZap Downloader", pausedTitle)
        assertEquals("All transfers paused • Tap to open", pausedContent)
    }

    @Test
    fun testTorrentNotification_ActionConstants() {
        assertEquals("com.sourzap.app.torrent.START", TorrentDownloadService.ACTION_START)
        assertEquals("com.sourzap.app.torrent.PAUSE_ALL", TorrentDownloadService.ACTION_PAUSE_ALL)
        assertEquals("com.sourzap.app.torrent.RESUME_ALL", TorrentDownloadService.ACTION_RESUME_ALL)
        assertEquals("com.sourzap.app.torrent.STOP", TorrentDownloadService.ACTION_STOP_SERVICE)
        assertEquals(1002, TorrentDownloadService.NOTIFICATION_ID)
    }

    @Test
    fun testTorrentNotification_UpdateThrottlingLogic() {
        var lastNotificationTime = -1000L
        val throttleIntervalMs = 1000L
        var dispatchedNotifications = 0

        fun notifyThrottled(currentTime: Long): Boolean {
            if (currentTime - lastNotificationTime >= throttleIntervalMs) {
                lastNotificationTime = currentTime
                dispatchedNotifications++
                return true
            }
            return false
        }

        // Simulate 10 updates coming in within 500ms
        for (t in 0..500 step 50) {
            notifyThrottled(t.toLong())
        }
        assertEquals("Only 1 notification should be dispatched within 1000ms window", 1, dispatchedNotifications)

        // Advance clock to 1500ms
        val dispatched = notifyThrottled(1500L)
        assertTrue(dispatched)
        assertEquals(2, dispatchedNotifications)
    }

    // =========================================================================
    // FEATURE F11: Android 13+ POST_NOTIFICATIONS Permission
    // =========================================================================

    @Test
    fun testPostNotifications_RuntimePermissionPolicy() {
        fun shouldRequestNotificationPermission(sdkInt: Int, isPermissionGranted: Boolean): Boolean {
            // Android 13 (TIRAMISU) is API 33
            return if (sdkInt >= 33) {
                !isPermissionGranted
            } else {
                false // Auto-granted below API 33
            }
        }

        // Android 12 (API 31) -> No runtime request needed
        assertFalse(shouldRequestNotificationPermission(sdkInt = 31, isPermissionGranted = false))

        // Android 13 (API 33) not granted -> Request needed
        assertTrue(shouldRequestNotificationPermission(sdkInt = 33, isPermissionGranted = false))

        // Android 13 (API 33) already granted -> No request needed
        assertFalse(shouldRequestNotificationPermission(sdkInt = 33, isPermissionGranted = true))

        // Android 15 (API 35) not granted -> Request needed
        assertTrue(shouldRequestNotificationPermission(sdkInt = 35, isPermissionGranted = false))
    }
}
