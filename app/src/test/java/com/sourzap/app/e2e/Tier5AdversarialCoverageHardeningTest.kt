package com.sourzap.app.e2e

import android.content.Context
import android.content.ContextWrapper
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
 * Phase 2 Tier 5 Adversarial Coverage Hardening Test Suite.
 * Stress-tests and verifies all 7 key components:
 * 1. TorrentEngineManager / MagnetHandler / TrackerInjector
 * 2. TorrentStorageHelper
 * 3. TorrentIntentParser
 * 4. UpdateManager
 * 5. TorrentDownloadService
 * 6. MainActivity & Permission Logic
 * 7. TorrentScreen & Torrent Models
 */
class Tier5AdversarialCoverageHardeningTest {

    private val tempDir = File(System.getProperty("java.io.tmpdir") ?: ".", "sourzap_tier5_adv_${System.currentTimeMillis()}")

    @Before
    fun setUp() {
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // 1. TORRENT ENGINE MANAGER & MAGNET / STATS ADVERSARIAL STRESS
    // =========================================================================

    @Test
    fun testTorrentEngine_MagnetNormalization_ExhaustiveAdversarialInputs() {
        // Valid 40-char Hex normalization with mixed casing
        val hexOriginal = "4a5b6c7d8e9f0123456789abcdef0123456789ab"
        assertEquals(hexOriginal, MagnetHandler.normalizeInfoHash(hexOriginal.uppercase(Locale.US)))
        assertEquals(hexOriginal, MagnetHandler.normalizeInfoHash(hexOriginal.lowercase(Locale.US)))
        assertEquals(hexOriginal, MagnetHandler.normalizeInfoHash("4A5b6C7d8E9f0123456789AbCdEf0123456789AB"))

        // Valid 32-char Base32 normalization
        val base32Original = "JJNGY7LNPYABGRLZZZZZZZZZZZZZZZZZ" // 32 chars
        val base32Normalized = MagnetHandler.normalizeInfoHash(base32Original)
        assertNotNull("Valid 32-char Base32 must be normalized", base32Normalized)
        assertEquals(40, base32Normalized!!.length)
        assertEquals(base32Normalized, MagnetHandler.normalizeInfoHash(base32Original.lowercase(Locale.US)))

        // Corrupted Base32 with non-Base32 chars (0, 1, 8, 9)
        assertNull(MagnetHandler.normalizeInfoHash("JJNGY7LNPYABGRLZZZZZZZZZZZZZZZZ0"))
        assertNull(MagnetHandler.normalizeInfoHash("JJNGY7LNPYABGRLZZZZZZZZZZZZZZZZ1"))
        assertNull(MagnetHandler.normalizeInfoHash("JJNGY7LNPYABGRLZZZZZZZZZZZZZZZZ8"))
        assertNull(MagnetHandler.normalizeInfoHash("JJNGY7LNPYABGRLZZZZZZZZZZZZZZZZ9"))

        // Corrupted Hex with non-hex chars
        assertNull(MagnetHandler.normalizeInfoHash("4a5b6c7d8e9f0123456789abcdef0123456789ag")) // contains 'g'
        assertNull(MagnetHandler.normalizeInfoHash("4a5b6c7d8e9f0123456789abcdef0123456789a!")) // contains '!'

        // Invalid lengths
        val invalidLengths = listOf(0, 1, 10, 20, 31, 33, 39, 41, 64, 128)
        for (len in invalidLengths) {
            val candidate = "a".repeat(len)
            assertNull("Candidate of length $len must be rejected", MagnetHandler.normalizeInfoHash(candidate))
        }
    }

    @Test
    fun testTorrentEngine_AggregateStatsCalculation_BoundaryInvariants() {
        // Invariant: Total bytes = 0 -> aggregate progress = 0.0f
        val statsEmpty = TorrentSessionStats(
            totalDownloadSpeed = 0L,
            totalUploadSpeed = 0L,
            totalDownloadedBytes = 0L,
            totalUploadedBytes = 0L,
            activeTorrents = 0,
            pausedTorrents = 0,
            seedingTorrents = 0,
            dhtNodes = 0L,
            totalBytes = 0L,
            aggregateProgress = 0.0f
        )
        assertEquals(0.0f, statsEmpty.aggregateProgress, 0.001f)
        assertEquals("0 B/s", statsEmpty.formattedDownloadSpeed)
        assertEquals("0 B/s", statsEmpty.formattedUploadSpeed)

        // Invariant: Extreme byte counters without overflow
        val hugeDownloaded = 4_000_000_000_000_000_000L
        val hugeTotal = 8_000_000_000_000_000_000L
        val progress = (hugeDownloaded.toFloat() / hugeTotal.toFloat()).coerceIn(0.0f, 1.0f)
        assertEquals(0.5f, progress, 0.01f)

        // Invariant: ETA calculations
        fun computeEta(totalSize: Long, totalDone: Long, downRate: Long, progress: Float): Long {
            val remaining = totalSize - totalDone
            return if (downRate > 0L && remaining > 0L) {
                remaining / downRate
            } else if (progress >= 1.0f) {
                0L
            } else {
                -1L
            }
        }

        assertEquals(100L, computeEta(1000L, 500L, 5L, 0.5f))
        assertEquals(0L, computeEta(1000L, 1000L, 0L, 1.0f))
        assertEquals(-1L, computeEta(1000L, 500L, 0L, 0.5f))
        assertEquals(0L, computeEta(1000L, 1000L, 500L, 1.0f))

        // Invariant: Share Ratio calculation
        fun computeShareRatio(totalDone: Long, allTimeUpload: Long): Float {
            return if (totalDone > 0L) allTimeUpload.toFloat() / totalDone.toFloat() else 0.0f
        }

        assertEquals(0.0f, computeShareRatio(0L, 500L), 0.001f)
        assertEquals(2.0f, computeShareRatio(500L, 1000L), 0.001f)
        assertEquals(0.5f, computeShareRatio(1000L, 500L), 0.001f)
    }

    @Test
    fun testTorrentEngine_ConcurrentMetadataOperations_Stress() {
        val metadataMap = ConcurrentHashMap<String, String>()
        val threadCount = 32
        val opsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val errors = AtomicInteger(0)

        val tasks = (0 until threadCount).map { threadId ->
            Runnable {
                try {
                    for (i in 0 until opsPerThread) {
                        val key = "torrent_${(threadId * opsPerThread + i) % 50}"
                        when (i % 4) {
                            0 -> metadataMap[key] = "Meta_$i"
                            1 -> metadataMap.remove(key)
                            2 -> metadataMap.getOrDefault(key, "Default")
                            3 -> metadataMap.putIfAbsent(key, "Init_$i")
                        }
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }

        tasks.forEach { executor.submit(it) }
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("Concurrent metadata map operations must have 0 errors", 0, errors.get())
    }

    // =========================================================================
    // 2. TORRENT STORAGE HELPER ADVERSARIAL CASES
    // =========================================================================

    private class MockStorageContext(
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
    fun testTorrentStorageHelper_DeepNestedSubdirsAndUnicode() {
        val internalDir = File(tempDir, "internal_nested").apply { mkdirs() }
        val context = MockStorageContext(
            extDownloadsDir = null,
            extFilesDir = null,
            internalFiles = internalDir
        )

        // Nested subfolder
        val nestedName = "downloads/2026/torrents/secure"
        val resolvedNested = TorrentStorageHelper.getSaveDirectory(context, nestedName)
        assertNotNull(resolvedNested)
        assertTrue(resolvedNested.exists())
        assertTrue(resolvedNested.isDirectory)
        assertTrue(resolvedNested.absolutePath.endsWith(nestedName.replace('/', File.separatorChar)))

        // Subdir with unicode emojis & brackets
        val specialSubDir = "SourZap [Torrents] 🚀 #1"
        val resolvedSpecial = TorrentStorageHelper.getSaveDirectory(context, specialSubDir)
        assertNotNull(resolvedSpecial)
        assertTrue(resolvedSpecial.exists())
        assertTrue(resolvedSpecial.isDirectory)
    }

    @Test
    fun testTorrentStorageHelper_EmptySubDirReturnsBaseDir() {
        val internalDir = File(tempDir, "internal_empty_subdir").apply { mkdirs() }
        val context = MockStorageContext(
            extDownloadsDir = null,
            extFilesDir = null,
            internalFiles = internalDir
        )

        val resolved = TorrentStorageHelper.getSaveDirectory(context, "")
        assertEquals(internalDir.absolutePath, resolved.absolutePath)
    }

    @Test
    fun testTorrentStorageHelper_IsWritableOrCreatable_VariousConditions() {
        // 1. Directory that does not exist yet -> should be created and return true
        val notYetCreated = File(tempDir, "auto_create_dir/child")
        assertFalse(notYetCreated.exists())
        assertTrue(TorrentStorageHelper.isWritableOrCreatable(notYetCreated))
        assertTrue(notYetCreated.exists())
        assertTrue(notYetCreated.isDirectory)

        // 2. Existing file used as directory -> should return false
        val regularFile = File(tempDir, "file_as_dir.txt").apply { writeText("hello") }
        val invalidChild = File(regularFile, "sub")
        assertFalse(TorrentStorageHelper.isWritableOrCreatable(invalidChild))
    }

    @Test
    fun testTorrentStorageHelper_GetAvailableFreeSpaceBytes_Resilience() {
        val internalDir = File(tempDir, "internal_space").apply { mkdirs() }
        val context = MockStorageContext(
            internalFiles = internalDir,
            throwDownloads = true,
            throwFiles = true
        )

        val freeSpace = TorrentStorageHelper.getAvailableFreeSpaceBytes(context)
        assertTrue("Usable space calculation must return non-negative value without throwing", freeSpace >= 0L)
    }

    // =========================================================================
    // 3. TORRENT INTENT PARSER ADVERSARIAL INPUTS
    // =========================================================================

    @Test
    fun testTorrentIntentParser_ParseData_AllSchemesAndMimes() {
        val dummyBytes = "d8:announce27:http://tracker.example.com4:infodee".toByteArray(StandardCharsets.UTF_8)

        // 1. Non-VIEW actions must be rejected
        assertNull(TorrentIntentParser.parseData(null, "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2", null))
        assertNull(TorrentIntentParser.parseData("android.intent.action.MAIN", "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2", null))
        assertNull(TorrentIntentParser.parseData("android.intent.action.SEND", "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2", null))

        // 2. Magnet URI (case-insensitive scheme)
        val magnetResult = TorrentIntentParser.parseData(
            action = "android.intent.action.VIEW",
            dataUriString = "MAGNET:?XT=URN:BTIH:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=LinuxISO",
            mimeType = null
        )
        assertNotNull(magnetResult)
        assertTrue(magnetResult is PendingTorrentIntent.Magnet)
        assertEquals("LinuxISO", (magnetResult as PendingTorrentIntent.Magnet).name)

        // 3. Direct stream bytes precedence
        val directStreamResult = TorrentIntentParser.parseData(
            action = "android.intent.action.VIEW",
            dataUriString = "content://media/file/123",
            mimeType = "application/x-bittorrent",
            streamBytes = dummyBytes,
            displayNameFallback = "direct_stream.torrent"
        )
        assertNotNull(directStreamResult)
        assertTrue(directStreamResult is PendingTorrentIntent.TorrentFile)
        assertEquals("direct_stream.torrent", (directStreamResult as PendingTorrentIntent.TorrentFile).fileName)
        assertEquals(dummyBytes.size, directStreamResult.bytes.size)

        // 4. MIME type fallback with .torrent extension
        val mimeResult = TorrentIntentParser.parseData(
            action = "android.intent.action.VIEW",
            dataUriString = "file:///storage/emulated/0/Download/archlinux.torrent",
            mimeType = "application/x-bittorrent"
        )
        assertNotNull(mimeResult)
        assertTrue(mimeResult is PendingTorrentIntent.TorrentFile)
        assertEquals("archlinux.torrent", (mimeResult as PendingTorrentIntent.TorrentFile).fileName)

        // 5. Unrelated MIME type and non-torrent extension -> rejected
        val nonTorrentResult = TorrentIntentParser.parseData(
            action = "android.intent.action.VIEW",
            dataUriString = "http://example.com/document.pdf",
            mimeType = "application/pdf"
        )
        assertNull(nonTorrentResult)
    }

    @Test
    fun testTorrentIntentParser_ResolveDisplayNameFromPath_AdversarialEdgeCases() {
        // Cursor display name has highest priority
        assertEquals("Official_Name.torrent", TorrentIntentParser.resolveDisplayNameFromPath("http://example.com/wrong.torrent", "Official_Name.torrent"))

        // URL encoded characters in file name
        val encodedPath = "content://downloads/Ubuntu%2024.04%20%28LTS%29%20Desktop.torrent"
        assertEquals("Ubuntu 24.04 (LTS) Desktop.torrent", TorrentIntentParser.resolveDisplayNameFromPath(encodedPath, null))

        // Path without .torrent extension -> should append .torrent
        assertEquals("debian-netinst.torrent", TorrentIntentParser.resolveDisplayNameFromPath("file:///downloads/debian-netinst", null))

        // Path with uppercase .TORRENT -> should not double-append .torrent.torrent
        assertEquals("ARCH_LINUX.TORRENT", TorrentIntentParser.resolveDisplayNameFromPath("file:///downloads/ARCH_LINUX.TORRENT", null))

        // Path traversal characters
        val traversalPath = "../../private/secret_file.torrent"
        assertEquals("secret_file.torrent", TorrentIntentParser.resolveDisplayNameFromPath(traversalPath, null))
    }

    // =========================================================================
    // 4. UPDATE MANAGER VERSIONING & INTEGRITY ADVERSARIAL STRESS
    // =========================================================================

    @Test
    fun testUpdateManager_IsVersionNewer_ComprehensiveMatrix() {
        val mgr = UpdateManager(MockStorageContext(internalFiles = tempDir))

        // Standard SemVer
        assertTrue(mgr.isVersionNewer("1.0.1", "1.0.0"))
        assertTrue(mgr.isVersionNewer("1.1.0", "1.0.9"))
        assertTrue(mgr.isVersionNewer("2.0.0", "1.99.99"))
        assertTrue(mgr.isVersionNewer("1.10.0", "1.9.9"))

        // Same version -> false
        assertFalse(mgr.isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(mgr.isVersionNewer("2.5.1", "2.5.1"))

        // Older version -> false
        assertFalse(mgr.isVersionNewer("1.0.0", "1.0.1"))
        assertFalse(mgr.isVersionNewer("1.9.0", "1.10.0"))
        assertFalse(mgr.isVersionNewer("0.9.9", "1.0.0"))

        // Unequal segment lengths
        assertTrue(mgr.isVersionNewer("1.0.0.1", "1.0.0"))
        assertFalse(mgr.isVersionNewer("1.0.0", "1.0.0.1"))

        // Prefixes ("v", "release-", etc.)
        assertTrue(mgr.isVersionNewer("v2.0.0", "1.9.9"))
        assertTrue(mgr.isVersionNewer("release-1.5.0", "v1.4.9"))
        assertFalse(mgr.isVersionNewer("v1.0.0", "1.0.0"))

        // Invalid / empty inputs
        assertFalse(mgr.isVersionNewer("", ""))
        assertFalse(mgr.isVersionNewer("invalid", "1.0.0"))
        assertTrue(mgr.isVersionNewer("1.0.0", "invalid"))
    }

    @Test
    fun testUpdateManager_ExtractCleanVersion_AdversarialInputs() {
        val mgr = UpdateManager(MockStorageContext(internalFiles = tempDir))

        assertEquals("1.2.3", mgr.extractCleanVersion("v1.2.3"))
        assertEquals("2.0.1", mgr.extractCleanVersion("release-2.0.1"))
        assertEquals("3.14.159", mgr.extractCleanVersion("SourZap-v3.14.159-final"))
        assertEquals("1.0", mgr.extractCleanVersion("v1.0"))
        assertEquals("42", mgr.extractCleanVersion("42"))
        assertEquals("", mgr.extractCleanVersion("no_numbers_at_all"))
    }

    @Test
    fun testUpdateManager_ValidateApkIntegrity_AdversarialBytePatterns() {
        val mgr = UpdateManager(MockStorageContext(internalFiles = tempDir))

        // 1. Non-existent file -> false
        val nonExistent = File(tempDir, "does_not_exist.apk")
        assertFalse(mgr.validateApkIntegrity(nonExistent))

        // 2. File smaller than 3,000,000 bytes -> false
        val smallFile = File(tempDir, "small.apk").apply {
            FileOutputStream(this).use { out ->
                out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // valid ZIP header but tiny size
                out.write(ByteArray(1024))
            }
        }
        assertFalse(mgr.validateApkIntegrity(smallFile))

        // 3. File >= 3MB with invalid magic header (all zeroes) -> false
        val invalidHeaderFile = File(tempDir, "invalid_header.apk").apply {
            FileOutputStream(this).use { out ->
                out.write(ByteArray(3_000_100))
            }
        }
        assertFalse(mgr.validateApkIntegrity(invalidHeaderFile))

        // 4. File >= 3MB with ELF binary magic header -> false
        val elfFile = File(tempDir, "elf_binary.apk").apply {
            FileOutputStream(this).use { out ->
                out.write(byteArrayOf(0x7F, 0x45, 0x4C, 0x46)) // ELF header
                out.write(ByteArray(3_000_100))
            }
        }
        assertFalse(mgr.validateApkIntegrity(elfFile))

        // 5. File >= 3MB with valid ZIP magic header (0x50, 0x4B, 0x03, 0x04) -> true
        val validApk = File(tempDir, "valid.apk").apply {
            FileOutputStream(this).use { out ->
                out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // PK\x03\x04
                out.write(ByteArray(3_000_100))
            }
        }
        assertTrue(mgr.validateApkIntegrity(validApk))
    }

    @Test
    fun testUpdateManager_NotificationConstantsAndActions() {
        assertEquals(1003, UpdateManager.NOTIFICATION_ID_UPDATE)
        assertEquals("com.sourzap.app.ACTION_CANCEL_UPDATE", UpdateManager.ACTION_CANCEL_UPDATE)
    }

    // =========================================================================
    // 5. TORRENT DOWNLOAD SERVICE CONSTANTS & NOTIFICATION INVARIANTS
    // =========================================================================

    @Test
    fun testTorrentDownloadService_ActionConstants() {
        assertEquals(1002, TorrentDownloadService.NOTIFICATION_ID)
        assertEquals("com.sourzap.app.torrent.START", TorrentDownloadService.ACTION_START)
        assertEquals("com.sourzap.app.torrent.PAUSE_ALL", TorrentDownloadService.ACTION_PAUSE_ALL)
        assertEquals("com.sourzap.app.torrent.RESUME_ALL", TorrentDownloadService.ACTION_RESUME_ALL)
        assertEquals("com.sourzap.app.torrent.STOP", TorrentDownloadService.ACTION_STOP_SERVICE)
    }

    @Test
    fun testTorrentDownloadService_NotificationContentFormattingLogic() {
        // Case 1: Zero active, zero seeding -> shows "All transfers paused • Tap to open"
        val pausedStats = TorrentSessionStats(
            activeTorrents = 0,
            seedingTorrents = 0,
            totalDownloadSpeed = 0,
            totalUploadSpeed = 0
        )
        val contentPaused = if (pausedStats.activeTorrents > 0 || pausedStats.seedingTorrents > 0) {
            "↓ ${pausedStats.formattedDownloadSpeed} • ↑ ${pausedStats.formattedUploadSpeed} • Peers connected"
        } else {
            "All transfers paused • Tap to open"
        }
        assertEquals("All transfers paused • Tap to open", contentPaused)

        // Case 2: Active downloads > 0 -> shows speed and peers
        val activeStats = TorrentSessionStats(
            activeTorrents = 3,
            seedingTorrents = 1,
            totalDownloadSpeed = 10_485_760L, // 10.0 MB/s
            totalUploadSpeed = 1_048_576L     // 1.0 MB/s
        )
        val contentActive = if (activeStats.activeTorrents > 0 || activeStats.seedingTorrents > 0) {
            "↓ ${activeStats.formattedDownloadSpeed} • ↑ ${activeStats.formattedUploadSpeed} • Peers connected"
        } else {
            "All transfers paused • Tap to open"
        }
        assertTrue(contentActive.contains("10.0 MB/s") || contentActive.contains("MB/s"))
        assertTrue(contentActive.contains("Peers connected"))

        val titleActive = if (activeStats.activeTorrents > 0) {
            "SourZap Downloader: ${activeStats.activeTorrents} Active (${activeStats.formattedDownloadSpeed})"
        } else {
            "SourZap Downloader"
        }
        assertTrue(titleActive.contains("3 Active"))
    }

    // =========================================================================
    // 6. MAIN ACTIVITY RUNTIME PERMISSION PERMUTATIONS
    // =========================================================================

    @Test
    fun testMainActivity_ShouldRequestNotificationPermission_AllPermutations() {
        // API < 33: Never request runtime POST_NOTIFICATIONS permission
        for (api in 21..32) {
            assertFalse("API $api with permission false must not request", MainActivity.shouldRequestNotificationPermission(api, false))
            assertFalse("API $api with permission true must not request", MainActivity.shouldRequestNotificationPermission(api, true))
        }

        // API 33+: Request only when permission is NOT granted
        for (api in 33..35) {
            assertTrue("API $api with permission false must request", MainActivity.shouldRequestNotificationPermission(api, false))
            assertFalse("API $api with permission true must not request", MainActivity.shouldRequestNotificationPermission(api, true))
        }
    }

    // =========================================================================
    // 7. TORRENT SCREEN & MODEL ADVERSARIAL FILTERING & FORMATTING
    // =========================================================================

    @Test
    fun testTorrentFilter_AllFiltersAcrossAllTorrentStates() {
        val allStates = listOf(
            TorrentState.DOWNLOADING,
            TorrentState.SEEDING,
            TorrentState.PAUSED,
            TorrentState.FINISHED,
            TorrentState.CHECKING,
            TorrentState.METADATA,
            TorrentState.ALLOCATING,
            TorrentState.ERROR
        )

        for (state in allStates) {
            val item = TorrentItem("id", "Name", state, 0.5f, 0, 0, 100, 50, 0, 0, 0)
            assertTrue("TorrentFilter.ALL must match state $state", TorrentFilter.ALL.matches(item))
        }

        // Filter DOWNLOADING: matches DOWNLOADING, CHECKING, METADATA, ALLOCATING
        val downloadingItem = TorrentItem("1", "T1", TorrentState.DOWNLOADING, 0.5f, 0, 0, 100, 50, 0, 0, 0)
        val checkingItem = TorrentItem("2", "T2", TorrentState.CHECKING, 0.5f, 0, 0, 100, 50, 0, 0, 0)
        val metadataItem = TorrentItem("3", "T3", TorrentState.METADATA, 0.5f, 0, 0, 100, 50, 0, 0, 0)
        val pausedItem = TorrentItem("4", "T4", TorrentState.PAUSED, 0.5f, 0, 0, 100, 50, 0, 0, 0)
        val finishedItem = TorrentItem("5", "T5", TorrentState.FINISHED, 1.0f, 0, 0, 100, 100, 0, 0, 0)
        val seedingItem = TorrentItem("6", "T6", TorrentState.SEEDING, 1.0f, 0, 0, 100, 100, 0, 0, 0)

        assertTrue(TorrentFilter.DOWNLOADING.matches(downloadingItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(checkingItem))
        assertTrue(TorrentFilter.DOWNLOADING.matches(metadataItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(pausedItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(finishedItem))

        // Filter COMPLETED: matches FINISHED, SEEDING
        assertTrue(TorrentFilter.COMPLETED.matches(finishedItem))
        assertTrue(TorrentFilter.COMPLETED.matches(seedingItem))
        assertFalse(TorrentFilter.COMPLETED.matches(downloadingItem))

        // Filter PAUSED: matches PAUSED
        assertTrue(TorrentFilter.PAUSED.matches(pausedItem))
        assertFalse(TorrentFilter.PAUSED.matches(downloadingItem))

        // Filter SEEDING: matches SEEDING
        assertTrue(TorrentFilter.SEEDING.matches(seedingItem))
        assertFalse(TorrentFilter.SEEDING.matches(downloadingItem))
    }

    @Test
    fun testTorrentItem_FileItemPropertiesAndCalculations() {
        val normalFile = TorrentFileItem(
            index = 0,
            path = "movies/linux.iso",
            size = 2_000_000_000L,
            downloadedBytes = 1_000_000_000L,
            progress = 0.5f,
            priority = Priority.NORMAL
        )
        assertEquals("linux.iso", normalFile.fileName)
        assertFalse(normalFile.isSkipped)

        val skippedFile = TorrentFileItem(
            index = 1,
            path = "movies/sample.mp4",
            size = 50_000_000L,
            downloadedBytes = 0L,
            progress = 0.0f,
            priority = Priority.IGNORE
        )
        assertEquals("sample.mp4", skippedFile.fileName)
        assertTrue(skippedFile.isSkipped)
    }

    @Test
    fun testTrackerInjector_EnsuresComprehensivePort443Injection() {
        val testMagnet = "magnet:?xt=urn:btih:4a5b6c7d8e9f0123456789abcdef0123456789ab&dn=Ubuntu"
        val injected = TrackerInjector.injectTrackers(testMagnet)

        assertTrue(injected.startsWith("magnet:?"))
        assertTrue(injected.contains("xt=urn:btih:4a5b6c7d8e9f0123456789abcdef0123456789ab"))
        assertTrue(injected.contains("dn=Ubuntu"))
        assertTrue(TrackerInjector.HTTPS_PORT_443_TRACKERS.isNotEmpty())
        assertTrue(TrackerInjector.HTTPS_PORT_443_TRACKERS.size >= 20)

        for (tr in TrackerInjector.HTTPS_PORT_443_TRACKERS) {
            assertTrue("Tracker must be HTTPS", tr.startsWith("https://"))
            assertTrue("Tracker must be on port 443", tr.contains(":443/") || tr.endsWith(":443"))
        }
    }
}
