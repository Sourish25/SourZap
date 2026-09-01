package com.sourzap.app

import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.service.TorrentDownloadService
import com.sourzap.app.update.UpdateManager
import com.sourzap.app.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Comprehensive Unit Test Suite for Milestone 3 (Features F9, F10, F11):
 * Rich Interactive Notifications, Notification Channels, Update Progress & Android 13+ Permissions.
 */
class RichNotificationAndPermissionsTest {

    // =========================================================================
    // FEATURE F9: Notification Channels & App Update Progress Notifications
    // =========================================================================

    @Test
    fun testUpdateChannelStringResourcesInStringsXml() {
        val stringsXml = File("app/src/main/res/values/strings.xml").let {
            if (it.exists()) it.readText() else File("src/main/res/values/strings.xml").readText()
        }

        assertTrue(
            "strings.xml must contain update_channel_id",
            stringsXml.contains("<string name=\"update_channel_id\">sourzap_update_channel</string>")
        )
        assertTrue(
            "strings.xml must contain update_channel_name",
            stringsXml.contains("<string name=\"update_channel_name\">SourZap App Updates</string>")
        )
        assertTrue(
            "strings.xml must contain update_channel_desc",
            stringsXml.contains("name=\"update_channel_desc\"")
        )
    }

    @Test
    fun testUpdateManagerNotificationConstants() {
        assertEquals("com.sourzap.app.ACTION_CANCEL_UPDATE", UpdateManager.ACTION_CANCEL_UPDATE)
        assertEquals(1003, UpdateManager.NOTIFICATION_ID_UPDATE)
    }

    @Test
    fun testUpdateNotificationProgressFormatting() {
        val downloadedBytes = 25_165_824L // 24 MB
        val totalBytes = 50_331_648L      // 48 MB
        val progress = downloadedBytes.toFloat() / totalBytes.toFloat()

        val progressPercent = (progress * 100).toInt().coerceIn(0, 100)
        assertEquals(50, progressPercent)

        val formattedDownloaded = TorrentItem.formatFileSize(downloadedBytes)
        val formattedTotal = TorrentItem.formatFileSize(totalBytes)
        assertEquals("24.00 MB", formattedDownloaded)
        assertEquals("48.00 MB", formattedTotal)

        val contentText = "$formattedDownloaded / $formattedTotal ($progressPercent%)"
        assertEquals("24.00 MB / 48.00 MB (50%)", contentText)
    }

    @Test
    fun testUpdateNotificationStreamingThrottling() {
        var lastNotifyTime = 0L
        var lastNotifyProgress = -1f
        var notifyCount = 0

        fun shouldNotify(time: Long, progress: Float): Boolean {
            if (time - lastNotifyTime >= 500L || abs(progress - lastNotifyProgress) >= 0.01f) {
                lastNotifyTime = time
                lastNotifyProgress = progress
                notifyCount++
                return true
            }
            return false
        }

        // Rapid updates within 50ms with less than 1% delta should be throttled
        assertTrue("Initial notification should fire", shouldNotify(0L, 0.001f))
        assertFalse("Sub-1% update within 50ms should be throttled", shouldNotify(50L, 0.003f))
        assertFalse("Sub-1% update within 100ms should be throttled", shouldNotify(100L, 0.005f))

        // Update with >= 1% delta should fire immediately even before 500ms
        assertTrue("1% delta update should fire immediately", shouldNotify(120L, 0.015f))

        // Update after 500ms should fire regardless of delta
        assertTrue("Update after 500ms should fire", shouldNotify(650L, 0.018f))
        assertEquals(3, notifyCount)
    }

    @Test
    fun testUpdateCancelReceiverInManifest() {
        val manifest = File("app/src/main/AndroidManifest.xml").let {
            if (it.exists()) it.readText() else File("src/main/AndroidManifest.xml").readText()
        }

        assertTrue(
            "AndroidManifest must declare UpdateCancelReceiver",
            manifest.contains(".update.UpdateCancelReceiver")
        )
        assertTrue(
            "UpdateCancelReceiver must filter com.sourzap.app.ACTION_CANCEL_UPDATE",
            manifest.contains("com.sourzap.app.ACTION_CANCEL_UPDATE")
        )
    }

    // =========================================================================
    // FEATURE F10: Active Torrent Progress & Dismiss Notification
    // =========================================================================

    @Test
    fun testTorrentNotificationConstantsAndActions() {
        assertEquals("com.sourzap.app.torrent.START", TorrentDownloadService.ACTION_START)
        assertEquals("com.sourzap.app.torrent.PAUSE_ALL", TorrentDownloadService.ACTION_PAUSE_ALL)
        assertEquals("com.sourzap.app.torrent.RESUME_ALL", TorrentDownloadService.ACTION_RESUME_ALL)
        assertEquals("com.sourzap.app.torrent.STOP", TorrentDownloadService.ACTION_STOP_SERVICE)
        assertEquals(1002, TorrentDownloadService.NOTIFICATION_ID)
    }

    @Test
    fun testTorrentSessionStatsAggregateProgress() {
        val stats = TorrentSessionStats(
            totalDownloadSpeed = 10_485_760L,
            totalUploadSpeed = 1_048_576L,
            totalDownloadedBytes = 500_000_000L,
            totalUploadedBytes = 100_000_000L,
            activeTorrents = 3,
            pausedTorrents = 1,
            seedingTorrents = 1,
            dhtNodes = 128L,
            totalBytes = 1_000_000_000L,
            aggregateProgress = 0.5f
        )

        assertEquals(50, stats.progressPercent)
        assertEquals("50.0%", stats.formattedProgress)
        assertEquals("10.0 MB/s", stats.formattedDownloadSpeed)
        assertEquals("1.0 MB/s", stats.formattedUploadSpeed)
        assertEquals(1_000_000_000L, stats.totalBytes)
        assertEquals(0.5f, stats.aggregateProgress, 0.001f)
    }

    @Test
    fun testTorrentSessionStatsDefaultValues() {
        val defaultStats = TorrentSessionStats()
        assertEquals(0L, defaultStats.totalBytes)
        assertEquals(0.0f, defaultStats.aggregateProgress, 0.001f)
        assertEquals(0, defaultStats.progressPercent)
        assertEquals("0.0%", defaultStats.formattedProgress)
    }

    @Test
    fun testTorrentNotificationThrottlingWindow() {
        var lastNotify = 0L
        var dispatched = 0

        fun notifyThrottled(time: Long): Boolean {
            if (time - lastNotify >= 1000L) {
                lastNotify = time
                dispatched++
                return true
            }
            return false
        }

        assertTrue("First emission should pass", notifyThrottled(1000L))
        assertFalse("Second emission at 1200ms should be throttled", notifyThrottled(1200L))
        assertFalse("Third emission at 1900ms should be throttled", notifyThrottled(1900L))
        assertTrue("Fourth emission at 2000ms should pass", notifyThrottled(2000L))
        assertEquals(2, dispatched)
    }

    // =========================================================================
    // FEATURE F11: Android 13+ POST_NOTIFICATIONS Permission
    // =========================================================================

    @Test
    fun testMainActivityShouldRequestNotificationPermissionHelper() {
        // Below API 33: Never request runtime permission (auto-granted by system)
        assertFalse(MainActivity.shouldRequestNotificationPermission(sdkInt = 26, isPermissionGranted = false))
        assertFalse(MainActivity.shouldRequestNotificationPermission(sdkInt = 29, isPermissionGranted = false))
        assertFalse(MainActivity.shouldRequestNotificationPermission(sdkInt = 31, isPermissionGranted = false))
        assertFalse(MainActivity.shouldRequestNotificationPermission(sdkInt = 32, isPermissionGranted = false))

        // API 33+ (Android 13+ / TIRAMISU): Request when NOT granted
        assertTrue(MainActivity.shouldRequestNotificationPermission(sdkInt = 33, isPermissionGranted = false))
        assertTrue(MainActivity.shouldRequestNotificationPermission(sdkInt = 34, isPermissionGranted = false))
        assertTrue(MainActivity.shouldRequestNotificationPermission(sdkInt = 35, isPermissionGranted = false))

        // API 33+: Do NOT request when ALREADY granted
        assertFalse(MainActivity.shouldRequestNotificationPermission(sdkInt = 33, isPermissionGranted = true))
        assertFalse(MainActivity.shouldRequestNotificationPermission(sdkInt = 34, isPermissionGranted = true))
        assertFalse(MainActivity.shouldRequestNotificationPermission(sdkInt = 35, isPermissionGranted = true))
    }

    @Test
    fun testPostNotificationsDeclaredInManifest() {
        val manifest = File("app/src/main/AndroidManifest.xml").let {
            if (it.exists()) it.readText() else File("src/main/AndroidManifest.xml").readText()
        }
        assertTrue(
            "AndroidManifest must declare POST_NOTIFICATIONS permission",
            manifest.contains("<uses-permission android:name=\"android.permission.POST_NOTIFICATIONS\" />")
        )
    }

    @Test
    fun testForegroundServiceTypesInManifest() {
        val manifest = File("app/src/main/AndroidManifest.xml").let {
            if (it.exists()) it.readText() else File("src/main/AndroidManifest.xml").readText()
        }
        assertTrue(
            "AndroidManifest must declare FOREGROUND_SERVICE_DATA_SYNC permission",
            manifest.contains("<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_DATA_SYNC\" />")
        )
        assertTrue(
            "TorrentDownloadService must specify foregroundServiceType dataSync",
            manifest.contains("android:foregroundServiceType=\"dataSync\"")
        )
    }
}
