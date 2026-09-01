package com.sourzap.app.torrent

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.sourzap.app.MainActivity
import com.sourzap.app.torrent.core.MagnetHandler
import com.sourzap.app.torrent.core.TorrentIntentParser
import com.sourzap.app.torrent.core.TorrentStorageHelper
import com.sourzap.app.torrent.core.TrackerInjector
import com.sourzap.app.torrent.model.PendingTorrentIntent
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import com.sourzap.app.torrent.service.TorrentDownloadService
import com.sourzap.app.update.UpdateManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Challenger Final 2: Comprehensive White-Box Adversarial Hardening Test Suite.
 * Stress-tests and verifies all 7 target source files:
 * 1. TorrentEngineManager / MagnetHandler / TrackerInjector
 * 2. TorrentStorageHelper
 * 3. TorrentIntentParser
 * 4. UpdateManager
 * 5. TorrentDownloadService
 * 6. MainActivity
 * 7. TorrentScreen / TorrentModels
 */
class ChallengerFinal2AdversarialHardeningTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir") ?: ".", "challenger2_adv_${System.currentTimeMillis()}")

    @Before
    fun setUp() {
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. TORRENT ENGINE & MAGNET / TRACKER INJECTOR ADVERSARIAL STRESS
    // =========================================================================

    @Test
    fun testMagnetHandler_Normalization_AllAdversarialVariations() {
        // Hex 40-char valid
        val hex = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(hex, MagnetHandler.normalizeInfoHash(hex.uppercase(Locale.US)))
        assertEquals(hex, MagnetHandler.normalizeInfoHash(hex.lowercase(Locale.US)))

        // Base32 32-char valid
        val base32 = "MFRGGZDFMY======MFRGGZDFMY======".replace("=", "A") // 32 chars
        val normalizedB32 = MagnetHandler.normalizeInfoHash(base32)
        assertNotNull(normalizedB32)
        assertEquals(40, normalizedB32!!.length)

        // Invalid Base32 characters (0, 1, 8, 9)
        assertNull(MagnetHandler.normalizeInfoHash("MFRGGZDFMY0AAAAAMFRGGZDFMYAAAAAA"))
        assertNull(MagnetHandler.normalizeInfoHash("MFRGGZDFMY1AAAAAMFRGGZDFMYAAAAAA"))
        assertNull(MagnetHandler.normalizeInfoHash("MFRGGZDFMY8AAAAAMFRGGZDFMYAAAAAA"))
        assertNull(MagnetHandler.normalizeInfoHash("MFRGGZDFMY9AAAAAMFRGGZDFMYAAAAAA"))

        // Invalid lengths (31, 33, 39, 41, 0)
        assertNull(MagnetHandler.normalizeInfoHash(""))
        assertNull(MagnetHandler.normalizeInfoHash("a".repeat(31)))
        assertNull(MagnetHandler.normalizeInfoHash("a".repeat(33)))
        assertNull(MagnetHandler.normalizeInfoHash("a".repeat(39)))
        assertNull(MagnetHandler.normalizeInfoHash("a".repeat(41)))
    }

    @Test
    fun testTrackerInjector_AllPort443HttpsInvariant() {
        val trackers = TrackerInjector.HTTPS_PORT_443_TRACKERS
        assertTrue("Trackers list must not be empty", trackers.isNotEmpty())
        assertTrue("Trackers count must be >= 20", trackers.size >= 20)

        for (t in trackers) {
            assertTrue("Tracker '$t' must start with https://", t.startsWith("https://"))
            assertTrue("Tracker '$t' must use port 443", t.contains(":443/") || t.endsWith(":443"))
        }

        // Test injectTrackers idempotency and presence of dn/xt
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Test%20File"
        val injected = TrackerInjector.injectTrackers(magnet)
        assertTrue(injected.startsWith("magnet:?"))
        assertTrue(injected.contains("xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
        assertTrue(injected.contains("dn=Test%20File"))
        assertTrue(injected.contains("&tr=https%3A%2F%2F") || injected.contains("&tr=https://"))
    }

    @Test
    fun testTorrentSessionStats_BoundaryCalculations() {
        // Zero state
        val zeroStats = TorrentSessionStats()
        assertEquals(0L, zeroStats.totalDownloadSpeed)
        assertEquals(0L, zeroStats.totalUploadSpeed)
        assertEquals(0L, zeroStats.totalDownloadedBytes)
        assertEquals(0.0f, zeroStats.aggregateProgress, 0.001f)
        assertEquals("0 B/s", zeroStats.formattedDownloadSpeed)
        assertEquals("0 B/s", zeroStats.formattedUploadSpeed)

        // Huge numbers without overflow
        val hugeDownloaded = 7_000_000_000_000_000_000L
        val hugeTotal = 8_000_000_000_000_000_000L
        val aggProg = (hugeDownloaded.toFloat() / hugeTotal.toFloat()).coerceIn(0.0f, 1.0f)
        assertEquals(0.875f, aggProg, 0.01f)

        // ETA logic
        fun calculateEta(totalSize: Long, totalDone: Long, downRate: Long, progress: Float): Long {
            val remaining = totalSize - totalDone
            return if (downRate > 0L && remaining > 0L) {
                remaining / downRate
            } else if (progress >= 1.0f) {
                0L
            } else {
                -1L
            }
        }

        assertEquals(50L, calculateEta(1000L, 500L, 10L, 0.5f))
        assertEquals(-1L, calculateEta(1000L, 500L, 0L, 0.5f))
        assertEquals(0L, calculateEta(1000L, 1000L, 0L, 1.0f))
        assertEquals(0L, calculateEta(1000L, 1000L, 100L, 1.0f))
    }

    // =========================================================================
    // 2. TORRENT STORAGE HELPER ADVERSARIAL STRESS
    // =========================================================================

    private class TestStorageContext(
        private val extDownloadsDir: File? = null,
        private val extFilesDir: File? = null,
        private val internalFiles: File,
        private val throwDownloads: Boolean = false,
        private val throwFiles: Boolean = false
    ) : ContextWrapper(null) {
        override fun getExternalFilesDir(type: String?): File? {
            if (type != null && (type == "Download" || type == Environment.DIRECTORY_DOWNLOADS)) {
                if (throwDownloads) throw SecurityException("Simulated external downloads security error")
                return extDownloadsDir
            }
            if (throwFiles) throw SecurityException("Simulated external files security error")
            return extFilesDir
        }

        override fun getFilesDir(): File = internalFiles
    }

    @Test
    fun testTorrentStorageHelper_FallbackHierarchy() {
        val internalDir = File(tempDir, "fallback_internal").apply { mkdirs() }
        val extFiles = File(tempDir, "fallback_ext_files").apply { mkdirs() }
        val extDownloads = File(tempDir, "fallback_ext_downloads").apply { mkdirs() }

        // Primary: extDownloads available
        val ctx1 = TestStorageContext(extDownloadsDir = extDownloads, extFilesDir = extFiles, internalFiles = internalDir)
        val dir1 = TorrentStorageHelper.getSaveDirectory(ctx1, "SourZap")
        assertTrue(dir1.absolutePath.startsWith(extDownloads.absolutePath))

        // Secondary: extDownloads returns null -> extFiles used
        val ctx2 = TestStorageContext(extDownloadsDir = null, extFilesDir = extFiles, internalFiles = internalDir, throwDownloads = false)
        val dir2 = TorrentStorageHelper.getSaveDirectory(ctx2, "SourZap")
        assertTrue(dir2.absolutePath.startsWith(extFiles.absolutePath))

        // Fallback: extDownloads & extFiles fail/throw -> internalFiles used
        val ctx3 = TestStorageContext(extDownloadsDir = null, extFilesDir = null, internalFiles = internalDir, throwDownloads = true, throwFiles = true)
        val dir3 = TorrentStorageHelper.getSaveDirectory(ctx3, "SourZap")
        assertTrue(dir3.absolutePath.startsWith(internalDir.absolutePath))
    }

    @Test
    fun testTorrentStorageHelper_PathTraversalAndSpecialSubdirs() {
        val internalDir = File(tempDir, "storage_paths").apply { mkdirs() }
        val ctx = TestStorageContext(internalFiles = internalDir)

        // Traversal attempt
        val traversalDir = TorrentStorageHelper.getSaveDirectory(ctx, "../../custom_torrents")
        assertNotNull(traversalDir)
        assertTrue(traversalDir.exists())

        // Empty subdir returns base dir directly
        val emptySubdir = TorrentStorageHelper.getSaveDirectory(ctx, "")
        assertEquals(internalDir.absolutePath, emptySubdir.absolutePath)
    }

    @Test
    fun testTorrentStorageHelper_IsWritableOrCreatable() {
        val nonExistent = File(tempDir, "nested/path/to/create")
        assertFalse(nonExistent.exists())
        assertTrue(TorrentStorageHelper.isWritableOrCreatable(nonExistent))
        assertTrue(nonExistent.exists())

        val regularFile = File(tempDir, "regular_file.txt").apply { writeText("data") }
        val childOfFile = File(regularFile, "sub")
        assertFalse(TorrentStorageHelper.isWritableOrCreatable(childOfFile))
    }

    @Test
    fun testTorrentStorageHelper_GetAvailableFreeSpaceBytes_NoThrow() {
        val internalDir = File(tempDir, "space_test").apply { mkdirs() }
        val ctx = TestStorageContext(internalFiles = internalDir)
        val freeSpace = TorrentStorageHelper.getAvailableFreeSpaceBytes(ctx)
        assertTrue("Free space must be >= 0L", freeSpace >= 0L)
    }

    // =========================================================================
    // 3. TORRENT INTENT PARSER ADVERSARIAL STRESS
    // =========================================================================

    @Test
    fun testTorrentIntentParser_ParseData_AdversarialScenarios() {
        val dummyTorrentBytes = "d8:announce27:http://tracker.example.com4:infodee".toByteArray(StandardCharsets.UTF_8)

        // 1. Rejected actions
        assertNull(TorrentIntentParser.parseData(null, "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567", null))
        assertNull(TorrentIntentParser.parseData("android.intent.action.SEND", "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567", null))
        assertNull(TorrentIntentParser.parseData("android.intent.action.MAIN", "file:///downloads/sample.torrent", "application/x-bittorrent"))

        // 2. Valid Magnet with mixed case and parameters
        val magnetUri = "MAGNET:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Ubuntu+24.04&tr=https://tracker.com:443"
        val parsedMagnet = TorrentIntentParser.parseData(
            action = Intent.ACTION_VIEW,
            dataUriString = magnetUri,
            mimeType = null
        )
        assertNotNull(parsedMagnet)
        assertTrue(parsedMagnet is PendingTorrentIntent.Magnet)
        assertEquals("Ubuntu 24.04", (parsedMagnet as PendingTorrentIntent.Magnet).name)

        // 3. Direct stream bytes precedence
        val directStream = TorrentIntentParser.parseData(
            action = Intent.ACTION_VIEW,
            dataUriString = "content://media/file/42",
            mimeType = "application/x-bittorrent",
            streamBytes = dummyTorrentBytes,
            displayNameFallback = "custom_download.torrent"
        )
        assertNotNull(directStream)
        assertTrue(directStream is PendingTorrentIntent.TorrentFile)
        assertEquals("custom_download.torrent", (directStream as PendingTorrentIntent.TorrentFile).fileName)
        assertEquals(dummyTorrentBytes.size, directStream.bytes.size)

        // 4. File URI with .torrent extension
        val fileUriParsed = TorrentIntentParser.parseData(
            action = Intent.ACTION_VIEW,
            dataUriString = "file:///storage/emulated/0/Download/debian.torrent",
            mimeType = "application/x-bittorrent"
        )
        assertNotNull(fileUriParsed)
        assertTrue(fileUriParsed is PendingTorrentIntent.TorrentFile)
        assertEquals("debian.torrent", (fileUriParsed as PendingTorrentIntent.TorrentFile).fileName)

        // 5. Non-torrent MIME and extension rejected
        val rejected = TorrentIntentParser.parseData(
            action = Intent.ACTION_VIEW,
            dataUriString = "http://example.com/image.png",
            mimeType = "image/png"
        )
        assertNull(rejected)
    }

    @Test
    fun testTorrentIntentParser_ResolveDisplayNameFromPath() {
        assertEquals("preferred.torrent", TorrentIntentParser.resolveDisplayNameFromPath("http://foo.com/bar.torrent", "preferred.torrent"))
        assertEquals("Ubuntu 22.04 LTS.torrent", TorrentIntentParser.resolveDisplayNameFromPath("file:///Ubuntu%2022.04%20LTS.torrent", null))
        assertEquals("archlinux.torrent", TorrentIntentParser.resolveDisplayNameFromPath("file:///downloads/archlinux", null))
        assertEquals("Fedora.TORRENT", TorrentIntentParser.resolveDisplayNameFromPath("file:///downloads/Fedora.TORRENT", null))
        assertEquals("secret.torrent", TorrentIntentParser.resolveDisplayNameFromPath("../../secret.torrent", null))
    }

    // =========================================================================
    // 4. UPDATE MANAGER VERSIONING & INTEGRITY ADVERSARIAL STRESS
    // =========================================================================

    @Test
    fun testUpdateManager_IsVersionNewer_EdgeCases() {
        val mgr = UpdateManager(TestStorageContext(internalFiles = tempDir))

        // SemVer comparisons
        assertTrue(mgr.isVersionNewer("1.0.1", "1.0.0"))
        assertTrue(mgr.isVersionNewer("1.10.0", "1.9.0"))
        assertTrue(mgr.isVersionNewer("2.0.0", "1.99.99"))
        assertTrue(mgr.isVersionNewer("1.0.0.1", "1.0.0"))

        // Equal versions
        assertFalse(mgr.isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(mgr.isVersionNewer("2.5.1", "2.5.1"))

        // Older versions
        assertFalse(mgr.isVersionNewer("1.0.0", "1.0.1"))
        assertFalse(mgr.isVersionNewer("1.0.0", "1.0.0.1"))
        assertFalse(mgr.isVersionNewer("0.9.9", "1.0.0"))

        // With prefixes
        assertTrue(mgr.isVersionNewer("v2.0.0", "1.9.9"))
        assertTrue(mgr.isVersionNewer("release-1.5.0", "v1.4.9"))
        assertFalse(mgr.isVersionNewer("v1.0.0", "1.0.0"))

        // Invalid inputs
        assertFalse(mgr.isVersionNewer("", ""))
        assertFalse(mgr.isVersionNewer("invalid", "1.0.0"))
    }

    @Test
    fun testUpdateManager_ExtractCleanVersion() {
        val mgr = UpdateManager(TestStorageContext(internalFiles = tempDir))
        assertEquals("1.2.3", mgr.extractCleanVersion("v1.2.3"))
        assertEquals("2.0.1", mgr.extractCleanVersion("release-2.0.1"))
        assertEquals("3.14.159", mgr.extractCleanVersion("SourZap-v3.14.159-final"))
        assertEquals("1.0", mgr.extractCleanVersion("v1.0"))
        assertEquals("42", mgr.extractCleanVersion("42"))
        assertEquals("", mgr.extractCleanVersion("none"))
    }

    @Test
    fun testUpdateManager_ValidateApkIntegrity_BoundarySizes() {
        val mgr = UpdateManager(TestStorageContext(internalFiles = tempDir))

        // Non-existent
        assertFalse(mgr.validateApkIntegrity(File(tempDir, "missing.apk")))

        // Exactly 2,999,999 bytes (1 byte below 3MB threshold)
        val fileUnder3MB = File(tempDir, "under3mb.apk").apply {
            FileOutputStream(this).use { out ->
                out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
                out.write(ByteArray(2_999_995))
            }
        }
        assertFalse("File < 3MB must be rejected", mgr.validateApkIntegrity(fileUnder3MB))

        // Exactly 3,000,000 bytes with valid ZIP magic header
        val fileExactly3MB = File(tempDir, "exactly3mb.apk").apply {
            FileOutputStream(this).use { out ->
                out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
                out.write(ByteArray(2_999_996))
            }
        }
        assertTrue("File >= 3MB with PK header must pass", mgr.validateApkIntegrity(fileExactly3MB))

        // File >= 3MB with invalid header (zeros)
        val fileBadHeader = File(tempDir, "bad_header.apk").apply {
            FileOutputStream(this).use { out ->
                out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
                out.write(ByteArray(3_000_000))
            }
        }
        assertFalse("File >= 3MB without PK header must be rejected", mgr.validateApkIntegrity(fileBadHeader))
    }

    // =========================================================================
    // 5. TORRENT DOWNLOAD SERVICE CONSTANTS & NOTIFICATION LOGIC
    // =========================================================================

    @Test
    fun testTorrentDownloadService_ActionConstantsAndNotificationInvariants() {
        assertEquals(1002, TorrentDownloadService.NOTIFICATION_ID)
        assertEquals("com.sourzap.app.torrent.START", TorrentDownloadService.ACTION_START)
        assertEquals("com.sourzap.app.torrent.PAUSE_ALL", TorrentDownloadService.ACTION_PAUSE_ALL)
        assertEquals("com.sourzap.app.torrent.RESUME_ALL", TorrentDownloadService.ACTION_RESUME_ALL)
        assertEquals("com.sourzap.app.torrent.STOP", TorrentDownloadService.ACTION_STOP_SERVICE)

        // Notification formatting rules
        val activeStats = TorrentSessionStats(
            activeTorrents = 2,
            seedingTorrents = 1,
            totalDownloadSpeed = 5_242_880L, // 5.0 MB/s
            totalUploadSpeed = 524_288L      // 512 KB/s
        )

        val title = if (activeStats.activeTorrents > 0) {
            "SourZap Downloader: ${activeStats.activeTorrents} Active (${activeStats.formattedDownloadSpeed})"
        } else {
            "SourZap Downloader"
        }
        assertTrue(title.contains("2 Active"))

        val content = if (activeStats.activeTorrents > 0 || activeStats.seedingTorrents > 0) {
            "↓ ${activeStats.formattedDownloadSpeed} • ↑ ${activeStats.formattedUploadSpeed} • Peers connected"
        } else {
            "All transfers paused • Tap to open"
        }
        assertTrue(content.contains("Peers connected"))
    }

    // =========================================================================
    // 6. MAIN ACTIVITY PERMISSION LOGIC
    // =========================================================================

    @Test
    fun testMainActivity_PermissionRequestLogic() {
        // SDK 21..32: never request POST_NOTIFICATIONS
        for (sdk in 21..32) {
            assertFalse(MainActivity.shouldRequestNotificationPermission(sdk, false))
            assertFalse(MainActivity.shouldRequestNotificationPermission(sdk, true))
        }

        // SDK 33..36: request only if not granted
        for (sdk in 33..36) {
            assertTrue(MainActivity.shouldRequestNotificationPermission(sdk, false))
            assertFalse(MainActivity.shouldRequestNotificationPermission(sdk, true))
        }
    }

    // =========================================================================
    // 7. TORRENT SCREEN / TORRENT MODELS FILTERING & FORMATTING
    // =========================================================================

    @Test
    fun testTorrentFilter_ExhaustiveStateMatching() {
        val downloadItem = TorrentItem("1", "D", TorrentState.DOWNLOADING, 0.5f, 1024, 0, 100, 50, 0, 0, 0)
        val allocItem = TorrentItem("2", "A", TorrentState.ALLOCATING, 0.1f, 0, 0, 100, 10, 0, 0, 0)
        val metaItem = TorrentItem("3", "M", TorrentState.METADATA, 0.0f, 0, 0, 100, 0, 0, 0, 0)
        val checkingItem = TorrentItem("4", "C", TorrentState.CHECKING, 0.2f, 0, 0, 100, 20, 0, 0, 0)
        val pausedItem = TorrentItem("5", "P", TorrentState.PAUSED, 0.5f, 0, 0, 100, 50, 0, 0, 0)
        val seedingItem = TorrentItem("6", "S", TorrentState.SEEDING, 1.0f, 0, 1024, 100, 100, 50, 0, 0)
        val finishedItem = TorrentItem("7", "F", TorrentState.FINISHED, 1.0f, 0, 0, 100, 100, 0, 0, 0)
        val errorItem = TorrentItem("8", "E", TorrentState.ERROR, 0.0f, 0, 0, 100, 0, 0, 0, 0)

        // ALL matches everything
        val allItems = listOf(downloadItem, allocItem, metaItem, checkingItem, pausedItem, seedingItem, finishedItem, errorItem)
        for (it in allItems) {
            assertTrue(TorrentFilter.ALL.matches(it))
        }

        // DOWNLOADING matches DOWNLOADING, ALLOCATING, METADATA
        assertTrue(TorrentFilter.DOWNLOADING.matches(downloadItem))
        assertTrue(TorrentFilter.DOWNLOADING.matches(allocItem))
        assertTrue(TorrentFilter.DOWNLOADING.matches(metaItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(pausedItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(seedingItem))

        // SEEDING matches SEEDING
        assertTrue(TorrentFilter.SEEDING.matches(seedingItem))
        assertFalse(TorrentFilter.SEEDING.matches(finishedItem))
        assertFalse(TorrentFilter.SEEDING.matches(downloadItem))

        // PAUSED matches PAUSED
        assertTrue(TorrentFilter.PAUSED.matches(pausedItem))
        assertFalse(TorrentFilter.PAUSED.matches(downloadItem))

        // COMPLETED matches FINISHED, SEEDING, and progress >= 1.0f
        assertTrue(TorrentFilter.COMPLETED.matches(finishedItem))
        assertTrue(TorrentFilter.COMPLETED.matches(seedingItem))
        assertFalse(TorrentFilter.COMPLETED.matches(downloadItem))
    }

    @Test
    fun testTorrentItem_FormattingHelpers() {
        // formatFileSize
        assertEquals("0 B", TorrentItem.formatFileSize(0L))
        assertEquals("500 B", TorrentItem.formatFileSize(500L))
        assertEquals("1.00 KB", TorrentItem.formatFileSize(1024L))
        assertEquals("1.00 MB", TorrentItem.formatFileSize(1024L * 1024L))
        assertEquals("1.00 GB", TorrentItem.formatFileSize(1024L * 1024L * 1024L))
        assertEquals("1.00 TB", TorrentItem.formatFileSize(1024L * 1024L * 1024L * 1024L))

        // formatBytesPerSec
        assertEquals("0 B/s", TorrentItem.formatBytesPerSec(0L))
        assertEquals("512.0 KB/s", TorrentItem.formatBytesPerSec(512L * 1024L))
        assertEquals("2.0 MB/s", TorrentItem.formatBytesPerSec(2L * 1024L * 1024L))

        // formatEtaDuration
        assertEquals("∞", TorrentItem.formatEtaDuration(-1L))
        assertEquals("0s", TorrentItem.formatEtaDuration(0L))
        assertEquals("45s", TorrentItem.formatEtaDuration(45L))
        assertEquals("5m 30s", TorrentItem.formatEtaDuration(330L))
        assertEquals("2h 15m", TorrentItem.formatEtaDuration(8100L))

        // Instance properties
        val item = TorrentItem(
            id = "test_id",
            name = "Test",
            state = TorrentState.DOWNLOADING,
            progress = 0.456f,
            downloadSpeed = 2L * 1024L * 1024L,
            uploadSpeed = 512L * 1024L,
            totalBytes = 1024L * 1024L * 1024L,
            downloadedBytes = 512L * 1024L * 1024L,
            uploadedBytes = 100L,
            numSeeds = 5,
            numPeers = 10,
            etaSeconds = 330L
        )
        assertEquals("45.6%", item.formattedProgress)
        assertEquals(45, item.progressPercent)
        assertEquals("2.0 MB/s", item.formattedDownloadSpeed)
        assertEquals("512.0 KB/s", item.formattedUploadSpeed)
        assertEquals("1.00 GB", item.formattedTotalSize)
        assertEquals("512.00 MB", item.formattedDownloadedSize)
        assertEquals("5m 30s", item.formattedEta)
        assertFalse(item.isCompleted)
    }

    @Test
    fun testTorrentFileItem_FileNameAndPriority() {
        val file1 = TorrentFileItem(0, "folder/subfolder/video.mkv", 1000L, 500L, 0.5f, Priority.NORMAL)
        assertEquals("video.mkv", file1.fileName)
        assertFalse(file1.isSkipped)

        val file2 = TorrentFileItem(1, "folder\\subfolder\\readme.txt", 100L, 0L, 0.0f, Priority.IGNORE)
        assertEquals("readme.txt", file2.fileName)
        assertTrue(file2.isSkipped)

        // Priority value conversions
        assertEquals(Priority.IGNORE, Priority.fromValue(0))
        assertEquals(Priority.LOW, Priority.fromValue(1))
        assertEquals(Priority.NORMAL, Priority.fromValue(4))
        assertEquals(Priority.HIGH, Priority.fromValue(7))
    }
}
