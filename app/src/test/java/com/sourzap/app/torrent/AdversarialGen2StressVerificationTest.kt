package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.BencodeValidator
import com.sourzap.app.torrent.core.DohTrackerResolver
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentSessionConfig
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.core.TrackerInjector
import com.sourzap.app.torrent.model.PreDownloadFileItem
import com.sourzap.app.torrent.model.PreDownloadState
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.libtorrent4j.swig.settings_pack
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * Adversarial Empirical Verification & Stress Test Suite (Gen2 Challenger).
 *
 * Exhaustively stress-tests:
 * 1. R1: Firewall & ISP bypass implementation:
 *    - TorrentSessionConfig evasion robustness (MSE RC4, TCP preference, dynamic ports, NAT-PMP/UPnP, HTTPS trackers).
 *    - SettingsPack SWIG parameter mappings (where JNI is available).
 *    - DoH tracker hostname resolution resilience.
 * 2. R2: Pre-download file prioritization & DO_NOT_DOWNLOAD (Priority 0):
 *    - Priority.IGNORE (value 0) libtorrent SWIG mappings.
 *    - Edge cases: 0 files selected, all files selected, single-file torrents, deeply nested directories, extreme file sizes, 0-byte files.
 *    - State transitions, size arithmetic, and priority list generation.
 * 3. R3: Scanner removal & robust file picker error handling:
 *    - Total elimination of DownloadsTorrentScanner (verified via reflection).
 *    - Corrupted, HTML, JSON, and truncated bencode resilience (no crashes).
 */
class AdversarialGen2StressVerificationTest {

    // =========================================================================
    // SECTION 1: R1 FIREWALL / ISP BYPASS & ANTI-DPI EVASION STRESS TESTS
    // =========================================================================

    @Test
    fun r1_sessionConfig_defaultEvasionSettings_areOptimalForHarshNetworks() {
        val config = TorrentSessionConfig.DEFAULT

        // Dynamic listen interfaces on IPv4 and IPv6 to bypass port filtering
        assertEquals("0.0.0.0:0,[::]:0", config.listenInterfaces)

        // UPnP and NAT-PMP enabled for NAT traversal
        assertTrue("UPnP must be enabled for NAT traversal", config.enableUpnp)
        assertTrue("NAT-PMP must be enabled for NAT traversal", config.enableNatpmp)

        // Mixed mode: PREFER_TCP to defeat LEDBAT 0 B/s congestion collapse
        assertEquals(TorrentSessionConfig.MIXED_MODE_PREFER_TCP, config.mixedModeAlgorithm)
        assertTrue(config.enableIncomingTcp)
        assertTrue(config.enableOutgoingTcp)
        assertTrue(config.enableIncomingUtp)
        assertTrue(config.enableOutgoingUtp)

        // Protocol encryption (MSE / PE) with RC4 cipher preference
        assertEquals(TorrentSessionConfig.ENC_POLICY_ENABLED, config.outEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_POLICY_ENABLED, config.inEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_LEVEL_BOTH, config.allowedEncLevel)
        assertTrue("RC4 cipher must be preferred to obfuscate BT wire headers", config.preferRc4)

        // Aggressive Swarm Saturation
        assertTrue("Connections limit must be >= 200", config.connectionsLimit >= 200)
        assertTrue("Connection speed must be >= 50", config.connectionSpeed >= 50)
        assertTrue("AIO threads must be >= 2", config.aioThreads >= 2)
        assertTrue("Announce to all trackers must be true", config.announceToAllTrackers)
        assertTrue("Announce to all tiers must be true", config.announceToAllTiers)
        assertTrue("DHT must be enabled", config.enableDht)
        assertTrue("PEX must be enabled", config.enablePex)
        assertTrue("LSD must be enabled", config.enableLsd)
    }

    @Test
    fun r1_sessionConfig_createSettingsPack_appliesAllAntiCensorshipSwigOptions() {
        val config = TorrentSessionConfig(
            listenInterfaces = "0.0.0.0:0,[::]:0",
            enableUpnp = true,
            enableNatpmp = true,
            mixedModeAlgorithm = TorrentSessionConfig.MIXED_MODE_PREFER_TCP,
            outEncPolicy = TorrentSessionConfig.ENC_POLICY_FORCED,
            inEncPolicy = TorrentSessionConfig.ENC_POLICY_FORCED,
            allowedEncLevel = TorrentSessionConfig.ENC_LEVEL_RC4,
            preferRc4 = true,
            announceToAllTrackers = true,
            announceToAllTiers = true,
            connectionsLimit = 600,
            aioThreads = 8
        )

        assertEquals("0.0.0.0:0,[::]:0", config.listenInterfaces)
        assertTrue(config.enableUpnp)
        assertTrue(config.enableNatpmp)
        assertEquals(0, config.mixedModeAlgorithm)
        assertEquals(2, config.outEncPolicy)
        assertEquals(2, config.inEncPolicy)
        assertEquals(2, config.allowedEncLevel)
        assertTrue(config.preferRc4)
        assertTrue(config.announceToAllTrackers)
        assertTrue(config.announceToAllTiers)
        assertEquals(600, config.connectionsLimit)
        assertEquals(8, config.aioThreads)

        // If native SWIG JNI is present in JVM test environment, test direct SettingsPack conversion
        try {
            val pack = config.createSettingsPack()
            assertNotNull("SettingsPack must not be null", pack)
            assertEquals("0.0.0.0:0,[::]:0", pack.getString(settings_pack.string_types.listen_interfaces.swigValue()))
            assertTrue(pack.getBoolean(settings_pack.bool_types.enable_upnp.swigValue()))
            assertTrue(pack.getBoolean(settings_pack.bool_types.enable_natpmp.swigValue()))
            assertEquals(0, pack.getInteger(settings_pack.int_types.mixed_mode_algorithm.swigValue()))
        } catch (_: LinkageError) {
            // Expected when running on standard JVM unit test runners without native JNI .dll/.so loaded
        }
    }

    @Test
    fun r1_trackerInjector_httpsTrackers_allUsePort443AndStrictHttps() {
        val trackers = TrackerInjector.HTTPS_PORT_443_TRACKERS
        assertTrue("Must provide at least 20 curated HTTPS trackers", trackers.size >= 20)

        for (tracker in trackers) {
            assertTrue("Tracker '$tracker' must start with https://", tracker.startsWith("https://"))
            assertTrue("Tracker '$tracker' must explicitly specify port 443", tracker.contains(":443/"))
            assertTrue("Tracker '$tracker' must end with /announce", tracker.endsWith("/announce"))

            val uri = URI(tracker)
            assertEquals("https", uri.scheme)
            assertEquals(443, uri.port)
            assertEquals("/announce", uri.path)
            assertNotNull("Host must not be null for $tracker", uri.host)
        }
    }

    @Test
    fun r1_trackerInjector_injectTrackers_intoMagnet_preservesIntegrityAndAvoidsDuplicates() {
        val rawMagnet = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=TestDownload&xl=1048576"
        val injected = TrackerInjector.injectTrackers(rawMagnet)

        assertTrue(injected.startsWith("magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709"))
        assertTrue(injected.contains("&dn=TestDownload"))
        assertTrue(injected.contains("&xl=1048576"))

        // Check that all 22 HTTPS trackers were appended
        for (tr in TrackerInjector.HTTPS_PORT_443_TRACKERS) {
            val encodedTr = java.net.URLEncoder.encode(tr, StandardCharsets.UTF_8.name())
            assertTrue("Injected magnet must contain tracker $tr", injected.contains(encodedTr) || injected.contains(tr))
        }

        // Idempotency: Re-injecting should not duplicate trackers
        val reInjected = TrackerInjector.injectTrackers(injected)
        assertEquals(injected.length, reInjected.length)
    }

    @Test
    fun r1_dohTrackerResolver_hostnameExtractionAndIpLiteralDetection() {
        assertEquals("tracker.tamersunion.org", DohTrackerResolver.extractHost("https://tracker.tamersunion.org:443/announce"))
        assertEquals("192.168.1.1", DohTrackerResolver.extractHost("http://192.168.1.1:6881/announce"))
        assertEquals("2001:db8::1", DohTrackerResolver.extractHost("http://[2001:db8::1]:6881/announce"))
        assertNull(DohTrackerResolver.extractHost(null))
        assertNull(DohTrackerResolver.extractHost(""))
        assertNull(DohTrackerResolver.extractHost("   "))

        assertTrue(DohTrackerResolver.isIpLiteral("1.1.1.1"))
        assertTrue(DohTrackerResolver.isIpLiteral("127.0.0.1"))
        assertTrue(DohTrackerResolver.isIpLiteral("2001:db8::1"))
        assertFalse(DohTrackerResolver.isIpLiteral("tracker.example.com"))
        assertFalse(DohTrackerResolver.isIpLiteral("cloudflare-dns.com"))
    }

    // =========================================================================
    // SECTION 2: R2 PRE-DOWNLOAD FILE SELECTION & PRIORITY 0 ENFORCEMENT
    // =========================================================================

    @Test
    fun r2_priorityEnum_ignoreValueIsZero_andMapsCorrectlyToLibtorrent() {
        assertEquals(0, Priority.IGNORE.value)
        assertEquals(1, Priority.LOW.value)
        assertEquals(4, Priority.NORMAL.value)
        assertEquals(7, Priority.HIGH.value)

        // Reverse mappings
        assertEquals(Priority.IGNORE, Priority.fromValue(0))
        assertEquals(Priority.IGNORE, Priority.fromValue(-1))
        assertEquals(Priority.IGNORE, Priority.fromValue(-999))
        assertEquals(Priority.LOW, Priority.fromValue(1))
        assertEquals(Priority.LOW, Priority.fromValue(2))
        assertEquals(Priority.LOW, Priority.fromValue(3))
        assertEquals(Priority.NORMAL, Priority.fromValue(4))
        assertEquals(Priority.NORMAL, Priority.fromValue(5))
        assertEquals(Priority.NORMAL, Priority.fromValue(6))
        assertEquals(Priority.HIGH, Priority.fromValue(7))
        assertEquals(Priority.HIGH, Priority.fromValue(100))

        try {
            val libIgnore = Priority.IGNORE.toLibtorrentPriority()
            assertEquals(0, libIgnore.swig().toInt())
            val libNormal = Priority.NORMAL.toLibtorrentPriority()
            assertEquals(4, libNormal.swig().toInt())
            assertEquals(Priority.IGNORE, Priority.fromLibtorrent(org.libtorrent4j.Priority.IGNORE))
            assertEquals(Priority.NORMAL, Priority.fromLibtorrent(org.libtorrent4j.Priority.DEFAULT))
        } catch (_: LinkageError) {
            // JVM environment without native shared lib
        }
    }

    @Test
    fun r2_preDownloadState_edgeCase_zeroFilesSelected() {
        val files = listOf(
            PreDownloadFileItem(0, "video.mkv", 1000000000L, isSelected = false),
            PreDownloadFileItem(1, "subs.srt", 50000L, isSelected = false),
            PreDownloadFileItem(2, "sample.mkv", 20000000L, isSelected = false)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/path/to/test.torrent"),
            name = "TestShow",
            files = files,
            targetDirectory = File("/tmp/downloads")
        )

        assertEquals(0, state.selectedCount)
        assertEquals(3, state.totalCount)
        assertEquals(0L, state.selectedSize)
        assertEquals(1020050000L, state.totalSize)
        assertTrue(state.noneSelected)
        assertFalse(state.allSelected)
        assertFalse("Download must be disabled when 0 files are selected", state.isDownloadEnabled)

        val priorities = state.toPriorities()
        assertEquals(3, priorities.size)
        assertTrue("All priorities must be IGNORE (0)", priorities.all { it == Priority.IGNORE })
    }

    @Test
    fun r2_preDownloadState_edgeCase_allFilesSelected() {
        val files = listOf(
            PreDownloadFileItem(0, "file1.dat", 100L, isSelected = true),
            PreDownloadFileItem(1, "file2.dat", 200L, isSelected = true),
            PreDownloadFileItem(2, "file3.dat", 300L, isSelected = true)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/path/to/test.torrent"),
            name = "TestPack",
            files = files,
            targetDirectory = File("/tmp/downloads")
        )

        assertEquals(3, state.selectedCount)
        assertEquals(3, state.totalCount)
        assertEquals(600L, state.selectedSize)
        assertEquals(600L, state.totalSize)
        assertTrue(state.allSelected)
        assertFalse(state.noneSelected)
        assertTrue("Download must be enabled", state.isDownloadEnabled)

        val priorities = state.toPriorities()
        assertEquals(3, priorities.size)
        assertTrue("All priorities must be NORMAL (4)", priorities.all { it == Priority.NORMAL })
    }

    @Test
    fun r2_preDownloadState_edgeCase_singleFileTorrent() {
        val files = listOf(
            PreDownloadFileItem(0, "ubuntu-24.04-desktop-amd64.iso", 5000000000L, isSelected = true)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/path/to/ubuntu.torrent"),
            name = "ubuntu-24.04-desktop-amd64.iso",
            files = files,
            targetDirectory = File("/tmp/downloads")
        )

        assertEquals(1, state.selectedCount)
        assertEquals(1, state.totalCount)
        assertEquals(5000000000L, state.selectedSize)
        assertTrue(state.allSelected)
        assertTrue(state.isDownloadEnabled)

        // Deselect the single file
        val deselectedState = state.toggleFile(0)
        assertEquals(0, deselectedState.selectedCount)
        assertEquals(0L, deselectedState.selectedSize)
        assertTrue(deselectedState.noneSelected)
        assertFalse(deselectedState.isDownloadEnabled)
        assertEquals(listOf(Priority.IGNORE), deselectedState.toPriorities())

        // Re-select via selectAll()
        val reselectedState = deselectedState.selectAll()
        assertEquals(1, reselectedState.selectedCount)
        assertEquals(5000000000L, reselectedState.selectedSize)
        assertTrue(reselectedState.isDownloadEnabled)
        assertEquals(listOf(Priority.NORMAL), reselectedState.toPriorities())
    }

    @Test
    fun r2_preDownloadState_edgeCase_deeplyNestedDirectories() {
        val deepPath = "level1/level2/level3/level4/level5/level6/level7/deep_secret.bin"
        val files = listOf(
            PreDownloadFileItem(0, deepPath, 1234567L, isSelected = true)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/path/to/deep.torrent"),
            name = "DeepArchive",
            files = files,
            targetDirectory = File("/tmp/downloads")
        )

        assertEquals(deepPath, state.files[0].path)
        assertEquals("deep_secret.bin", state.files[0].fileName)
        assertEquals(1234567L, state.selectedSize)
    }

    @Test
    fun r2_preDownloadState_edgeCase_zeroByteFiles() {
        val files = listOf(
            PreDownloadFileItem(0, ".empty_marker", 0L, isSelected = true),
            PreDownloadFileItem(1, "normal_file.txt", 1024L, isSelected = true),
            PreDownloadFileItem(2, "another_zero_byte.bin", 0L, isSelected = false)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/path/to/test.torrent"),
            name = "ZeroByteContainer",
            files = files,
            targetDirectory = File("/tmp/downloads")
        )

        assertEquals(1024L, state.totalSize)
        assertEquals(1024L, state.selectedSize)
        assertEquals(2, state.selectedCount)
        assertEquals("0 B", state.files[0].formattedSize)
        assertEquals("1.00 KB", state.files[1].formattedSize)

        // TorrentFileItem progress with 0-byte file must not throw ArithmeticException / NaN
        val fileItem0 = TorrentFileItem(
            index = 0,
            path = ".empty_marker",
            size = 0L,
            downloadedBytes = 0L,
            progress = 0.0f,
            priority = Priority.NORMAL
        )
        assertEquals(0.0f, fileItem0.progress, 0.001f)
        assertFalse(fileItem0.isSkipped)

        val skippedItem2 = TorrentFileItem(
            index = 2,
            path = "another_zero_byte.bin",
            size = 0L,
            downloadedBytes = 0L,
            progress = 0.0f,
            priority = Priority.IGNORE
        )
        assertTrue(skippedItem2.isSkipped)
    }

    @Test
    fun r2_preDownloadState_edgeCase_extreme64BitFileSizes() {
        // Multi-TB files
        val size1 = 1_500_000_000_000L // 1.5 TB
        val size2 = 2_800_000_000_000L // 2.8 TB
        val totalExpected = 4_300_000_000_000L // 4.3 TB

        val files = listOf(
            PreDownloadFileItem(0, "huge_dataset_part1.raw", size1, isSelected = true),
            PreDownloadFileItem(1, "huge_dataset_part2.raw", size2, isSelected = true)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/path/to/huge.torrent"),
            name = "HugeDataset",
            files = files,
            targetDirectory = File("/tmp/downloads")
        )

        assertEquals(totalExpected, state.totalSize)
        assertEquals(totalExpected, state.selectedSize)
        assertEquals("3.91 TB", state.formattedTotalSize) // 4.3e12 / 1024^4

        // Toggle part 1 off
        val updated = state.toggleFile(0)
        assertEquals(size2, updated.selectedSize)
        assertEquals("2.55 TB", updated.formattedSelectedSize)

        // Space check
        val spaceLimitedState = state.copy(availableDiskSpace = 2_000_000_000_000L) // 2 TB free < 4.3 TB needed
        assertTrue("Must detect insufficient disk space", spaceLimitedState.hasInsufficientSpace)
    }

    @Test
    fun r2_preDownloadState_partialSelection_andPriorityMapping() {
        val files = listOf(
            PreDownloadFileItem(0, "Track01.mp3", 5_000_000L, isSelected = true),
            PreDownloadFileItem(1, "Track02.mp3", 6_000_000L, isSelected = false),
            PreDownloadFileItem(2, "Track03.mp3", 7_000_000L, isSelected = true),
            PreDownloadFileItem(3, "Track04.mp3", 8_000_000L, isSelected = false)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/music/album.torrent"),
            name = "GreatAlbum",
            files = files,
            targetDirectory = File("/music/downloads")
        )

        assertEquals(2, state.selectedCount)
        assertEquals(12_000_000L, state.selectedSize)
        assertEquals(26_000_000L, state.totalSize)

        val priorities = state.toPriorities()
        assertEquals(
            listOf(Priority.NORMAL, Priority.IGNORE, Priority.NORMAL, Priority.IGNORE),
            priorities
        )
    }

    // =========================================================================
    // SECTION 3: R3 SCANNER REMOVAL & CORRUPTED FILE PICKER ROBUSTNESS
    // =========================================================================

    @Test
    fun r3_downloadsTorrentScanner_isCompletelyEliminatedFromClasspath() {
        try {
            Class.forName("com.sourzap.app.torrent.core.DownloadsTorrentScanner")
            fail("DownloadsTorrentScanner class MUST NOT exist in classpath! It was supposed to be completely removed.")
        } catch (e: ClassNotFoundException) {
            // Expected: Class is completely removed
            assertTrue(true)
        }
    }

    @Test
    fun r3_filePicker_fuzzWithCorruptedAndMaliciousBuffers_doesNotCrash() {
        val corruptedPayloads = listOf(
            // 1. HTML error pages (Cloudflare DDoS, 404, 503)
            "<!DOCTYPE html><html><head><title>503 Service Unavailable</title></head><body>Cloudflare DDoS Protection</body></html>".toByteArray(StandardCharsets.UTF_8),
            "<html><body><h1>404 Not Found</h1></body></html>".toByteArray(StandardCharsets.UTF_8),
            "<html lang=\"en\"><head><meta charset=\"utf-8\"></head></html>".toByteArray(StandardCharsets.UTF_8),

            // 2. JSON error responses
            "{\"error\": \"Unauthorized\", \"message\": \"API key invalid\", \"status\": 401}".toByteArray(StandardCharsets.UTF_8),
            "{\"status\": \"error\", \"code\": 500}".toByteArray(StandardCharsets.UTF_8),

            // 3. XML responses
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><error><code>AccessDenied</code><message>Access Denied</message></error>".toByteArray(StandardCharsets.UTF_8),

            // 4. Empty / 0-byte buffer
            ByteArray(0),

            // 5. Truncated bencode buffers
            "d".toByteArray(StandardCharsets.US_ASCII),
            "d4:info".toByteArray(StandardCharsets.US_ASCII),
            "d8:announce15:http://tr.com/a4:infod6:lengthi1000e4:name".toByteArray(StandardCharsets.US_ASCII),
            "d8:announce15:http://tr.com/a4:infod6:lengthi1000e4:name8:test.isoe".toByteArray(StandardCharsets.US_ASCII), // missing pieces & piece length

            // 6. Invalid piece length / pieces not divisible by 20
            "d8:announce15:http://tr.com/a4:infod6:lengthi1000e4:name8:test.iso12:piece lengthi16384e6:pieces15:123456789012345ee".toByteArray(StandardCharsets.US_ASCII),

            // 7. Non-dictionary root
            "l4:item14:item2e".toByteArray(StandardCharsets.US_ASCII),
            "i123456e".toByteArray(StandardCharsets.US_ASCII),
            "5:hello".toByteArray(StandardCharsets.US_ASCII),

            // 8. Random garbage binary byte sequences
            ByteArray(512) { Random.nextInt(0, 256).toByte() },
            ByteArray(4096) { 0xFF.toByte() },
            ByteArray(2048) { 0x00.toByte() }
        )

        for ((index, payload) in corruptedPayloads.withIndex()) {
            val result = TorrentFileValidator.validate(payload)
            assertTrue("Corrupted payload #$index must return TorrentValidationResult.Invalid", result is TorrentValidationResult.Invalid)
            val invalid = result as TorrentValidationResult.Invalid
            assertTrue("Detailed error message must not be blank", invalid.detailedMessage.isNotBlank())
        }
    }

    @Test
    fun r3_bencodeValidator_extractFilesFromCorruptedInput_returnsEmptyListWithoutCrash() {
        val badPayloads = listOf(
            ByteArray(0),
            "random garbage text".toByteArray(StandardCharsets.UTF_8),
            "<!DOCTYPE html><html></html>".toByteArray(StandardCharsets.UTF_8),
            "d4:infoi123ee".toByteArray(StandardCharsets.US_ASCII)
        )

        for (bad in badPayloads) {
            val files = BencodeValidator.extractFiles(bad)
            assertTrue("Corrupted payload must return empty list of files without throwing", files.isEmpty())
        }
    }
}
