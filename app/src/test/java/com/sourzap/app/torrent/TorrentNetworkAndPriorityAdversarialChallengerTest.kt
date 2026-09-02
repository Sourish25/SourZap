package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.DohTrackerResolver
import com.sourzap.app.torrent.core.TorrentSessionConfig
import com.sourzap.app.torrent.core.TrackerInjector
import com.sourzap.app.torrent.model.PreDownloadFileItem
import com.sourzap.app.torrent.model.PreDownloadState
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Empirical Adversarial Challenger Test Suite: Network Configuration & Priority Mapping.
 *
 * Exhaustively stress-tests:
 * 1. Dynamic Port Binding & IPv6 Bracket Syntax:
 *    - RFC 3986 / libtorrent listen_interfaces grammar validation (`<ip>:<port>` and `[<ipv6>]:<port>`)
 *    - Ephemeral dynamic port (0) and port range boundaries (1..65535)
 *    - Multi-interface combinations and malformed string handling
 * 2. SettingsPack Flags & Anti-Censorship Transport Policies:
 *    - UPnP and NAT-PMP traversal enablement
 *    - TCP priority over uTP (`prefer_tcp` mixed mode algorithm)
 *    - Message Stream Encryption (MSE / PE) RC4 ciphers and policy enforcement
 *    - Socket buffer sizes, AIO worker threads, cache sizes, and announce-to-all-tiers flags
 * 3. Priority Array Transformations & Boundary Conditions:
 *    - Priority enum round-trip mappings (IGNORE=0, LOW=1, NORMAL=4, HIGH=7)
 *    - Boundary inputs (negative values, extreme values, out-of-range integers)
 *    - All-ignored (100% IGNORE), single-file ignored, single-file selected, alternating priority arrays
 *    - PreDownloadState state transitions, selection toggles, and 64-bit size overflow resistance (> 2 TB)
 * 4. Tracker URL Sanitation, Deduplication & Announce Tier Injection:
 *    - Port-443 HTTPS catalog integrity (22+ verified endpoints, strict HTTPS + port 443 + /announce)
 *    - Magnet URI injection idempotence, case-insensitivity, trailing slash stripping, and URL encoding
 *    - Dirty input sanitization, whitespace trimming, and DoH hostname extraction
 */
class TorrentNetworkAndPriorityAdversarialChallengerTest {

    // =========================================================================
    // SECTION 1: DYNAMIC PORT BINDING & IPv6 BRACKET SYNTAX PROBES
    // =========================================================================

    private val LISTEN_INTERFACE_REGEX = Regex(
        """^(?:(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)|\[(?:[0-9a-fA-F]{0,4}:){1,7}[0-9a-fA-F]{0,4}(?:%[a-zA-Z0-9_-]+)?\]):(?:6553[0-5]|655[0-2]\d|65[0-4]\d{2}|6[0-4]\d{3}|[1-5]\d{4}|[1-9]\d{0,3}|0)$"""
    )

    private fun parseAndValidateListenInterfaces(interfacesStr: String): List<Pair<String, Int>> {
        val tokens = interfacesStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<Pair<String, Int>>()

        for (token in tokens) {
            assertTrue("Token '$token' must match valid listen interface format", LISTEN_INTERFACE_REGEX.matches(token))
            val colonIdx = token.lastIndexOf(':')
            assertTrue("Token must contain colon port separator", colonIdx > 0)
            val ipPart = token.substring(0, colonIdx)
            val portPart = token.substring(colonIdx + 1)
            val port = portPart.toIntOrNull()
            assertNotNull("Port '$portPart' must be a valid integer", port)
            assertTrue("Port $port must be in valid TCP/UDP range 0..65535", port!! in 0..65535)

            if (ipPart.startsWith("[")) {
                assertTrue("IPv6 address must end with ']': $ipPart", ipPart.endsWith("]"))
                val innerIpv6 = ipPart.substring(1, ipPart.length - 1)
                assertTrue("IPv6 literal must not be empty", innerIpv6.isNotEmpty())
            }
            result.add(ipPart to port)
        }
        return result
    }

    @Test
    fun probe_DefaultListenInterfaces_MatchesIPv4AndIPv6DynamicBindings() {
        val config = TorrentSessionConfig.DEFAULT
        assertEquals("0.0.0.0:0,[::]:0", config.listenInterfaces)

        val parsed = parseAndValidateListenInterfaces(config.listenInterfaces)
        assertEquals(2, parsed.size)

        // IPv4 dynamic all-interfaces
        assertEquals("0.0.0.0", parsed[0].first)
        assertEquals(0, parsed[0].second)

        // IPv6 dynamic all-interfaces with bracket notation
        assertEquals("[::]", parsed[1].first)
        assertEquals(0, parsed[1].second)
    }

    @Test
    fun probe_CustomIPv4AndIPv6ListenInterfaceCombinations() {
        val customConfigs = listOf(
            "127.0.0.1:6881,[::1]:6881",
            "0.0.0.0:51413,[::]:51413",
            "192.168.1.100:0,[2001:db8::1]:0",
            "10.0.0.5:8080,[fe80::1%wlan0]:8080",
            "0.0.0.0:65535,[::]:65535",
            "172.16.0.2:1,[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:1"
        )

        for (custom in customConfigs) {
            val cfg = TorrentSessionConfig(listenInterfaces = custom)
            assertEquals(custom, cfg.listenInterfaces)
            val parsed = parseAndValidateListenInterfaces(cfg.listenInterfaces)
            assertEquals(2, parsed.size)
        }
    }

    @Test
    fun probe_MultiInterfaceCommaSeparatedLists() {
        val multiInterfaceStr = "0.0.0.0:0,[::]:0,192.168.1.50:6881,[fe80::cafe:babe]:6881,10.10.10.10:50000"
        val cfg = TorrentSessionConfig(listenInterfaces = multiInterfaceStr)
        val parsed = parseAndValidateListenInterfaces(cfg.listenInterfaces)
        assertEquals(5, parsed.size)
        assertEquals("0.0.0.0", parsed[0].first)
        assertEquals(0, parsed[0].second)
        assertEquals("[::]", parsed[1].first)
        assertEquals(0, parsed[1].second)
        assertEquals("192.168.1.50", parsed[2].first)
        assertEquals(6881, parsed[2].second)
        assertEquals("[fe80::cafe:babe]", parsed[3].first)
        assertEquals(6881, parsed[3].second)
        assertEquals("10.10.10.10", parsed[4].first)
        assertEquals(50000, parsed[4].second)
    }

    @Test
    fun probe_MalformedListenInterfaceSyntaxDetection() {
        val malformedStrings = listOf(
            "0.0.0.0",          // missing port
            "::1:6881",         // unbracketed IPv6 with port
            "2001:db8::1:6881", // unbracketed IPv6
            "0.0.0.0:65536",    // port overflow > 65535
            "0.0.0.0:-1",       // negative port
            "0.0.0.0:port",     // non-numeric port
            "[::]:",            // empty port
            "[::",              // unclosed bracket
            "999.999.999.999:0" // invalid IPv4 octet
        )

        for (bad in malformedStrings) {
            assertFalse("Malformed string '$bad' must fail validation", LISTEN_INTERFACE_REGEX.matches(bad))
        }
    }

    // =========================================================================
    // SECTION 2: SETTINGSPACK FLAGS & TRANSPORT MATRIX PROBES
    // =========================================================================

    @Test
    fun probe_DefaultSettingsPackAntiCensorshipFlags() {
        val config = TorrentSessionConfig.DEFAULT

        // NAT Traversal
        assertTrue("UPnP must be enabled", config.enableUpnp)
        assertTrue("NAT-PMP must be enabled", config.enableNatpmp)

        // Transport: TCP prioritized to defeat LEDBAT 0 B/s stall
        assertTrue("Incoming TCP enabled", config.enableIncomingTcp)
        assertTrue("Outgoing TCP enabled", config.enableOutgoingTcp)
        assertTrue("Incoming uTP enabled", config.enableIncomingUtp)
        assertTrue("Outgoing uTP enabled", config.enableOutgoingUtp)
        assertEquals(TorrentSessionConfig.MIXED_MODE_PREFER_TCP, config.mixedModeAlgorithm)
        assertEquals(0, TorrentSessionConfig.MIXED_MODE_PREFER_TCP)

        // Protocol Encryption (MSE / PE)
        assertEquals(TorrentSessionConfig.ENC_POLICY_ENABLED, config.outEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_POLICY_ENABLED, config.inEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_LEVEL_BOTH, config.allowedEncLevel)
        assertTrue("Prefer RC4 must be enabled", config.preferRc4)

        // Parallel Tracker Announcement
        assertTrue("Announce to all trackers must be true", config.announceToAllTrackers)
        assertTrue("Announce to all tiers must be true", config.announceToAllTiers)
        assertEquals(10, config.trackerCompletionTimeout)
        assertEquals(8, config.trackerReceiveTimeout)
        assertEquals(2, config.stopTrackerTimeout)

        // Swarm saturation buffers & concurrency
        assertEquals(500, config.connectionsLimit)
        assertEquals(4000, config.maxPeerlistSize)
        assertEquals(100, config.torrentConnectBoost)
        assertEquals(80, config.connectionSpeed)
        assertEquals(5, config.peerConnectTimeout)
        assertEquals(1500, config.maxOutRequestQueue)
        assertEquals(8, config.requestTimeout)
        assertEquals(20, config.wholePiecesThreshold)
        assertEquals(64 * 1024 * 1024, config.cacheSize)
        assertEquals(1048576, config.sendSocketBufferSize) // 1 MB
        assertEquals(2097152, config.recvSocketBufferSize) // 2 MB
        assertEquals(4, config.aioThreads)

        // Discovery
        assertTrue("DHT enabled", config.enableDht)
        assertTrue("LSD enabled", config.enableLsd)
        assertTrue("PEX enabled", config.enablePex)
        assertTrue("Bootstrap nodes non-empty", config.dhtBootstrapNodes.isNotBlank())
    }

    @Test
    fun probe_TransportSwitchingCombinationsMatrix() {
        val transportCombinations = listOf(
            // inUtp, outUtp, inTcp, outTcp, mixedMode
            listOf(true, true, true, true, TorrentSessionConfig.MIXED_MODE_PREFER_TCP),     // Dual prefer TCP (default)
            listOf(false, false, true, true, TorrentSessionConfig.MIXED_MODE_PREFER_TCP),   // Pure TCP mode
            listOf(true, true, false, false, TorrentSessionConfig.MIXED_MODE_PREFER_TCP),   // Pure uTP mode
            listOf(true, true, true, true, TorrentSessionConfig.MIXED_MODE_PEER_PROPORTIONAL), // Peer proportional
            listOf(false, false, false, false, TorrentSessionConfig.MIXED_MODE_PREFER_TCP)  // Blocked mode
        )

        for ((inUtp, outUtp, inTcp, outTcp, mm) in transportCombinations) {
            val cfg = TorrentSessionConfig(
                enableIncomingUtp = inUtp as Boolean,
                enableOutgoingUtp = outUtp as Boolean,
                enableIncomingTcp = inTcp as Boolean,
                enableOutgoingTcp = outTcp as Boolean,
                mixedModeAlgorithm = mm as Int
            )
            assertEquals(inUtp, cfg.enableIncomingUtp)
            assertEquals(outUtp, cfg.enableOutgoingUtp)
            assertEquals(inTcp, cfg.enableIncomingTcp)
            assertEquals(outTcp, cfg.enableOutgoingTcp)
            assertEquals(mm, cfg.mixedModeAlgorithm)
        }
    }

    @Test
    fun probe_EncryptionPolicyCombinationsMatrix() {
        val encMatrix = listOf(
            // outEnc, inEnc, allowedLevel, preferRc4
            listOf(TorrentSessionConfig.ENC_POLICY_DISABLED, TorrentSessionConfig.ENC_POLICY_DISABLED, TorrentSessionConfig.ENC_LEVEL_PLAINTEXT, false),
            listOf(TorrentSessionConfig.ENC_POLICY_ENABLED, TorrentSessionConfig.ENC_POLICY_ENABLED, TorrentSessionConfig.ENC_LEVEL_BOTH, true),
            listOf(TorrentSessionConfig.ENC_POLICY_FORCED, TorrentSessionConfig.ENC_POLICY_FORCED, TorrentSessionConfig.ENC_LEVEL_RC4, true),
            listOf(TorrentSessionConfig.PE_FORCED, TorrentSessionConfig.PE_FORCED, TorrentSessionConfig.PE_RC4, true),
            listOf(TorrentSessionConfig.PE_ENABLED, TorrentSessionConfig.PE_ENABLED, TorrentSessionConfig.PE_BOTH, true)
        )

        for ((outE, inE, level, rc4) in encMatrix) {
            val cfg = TorrentSessionConfig(
                outEncPolicy = outE as Int,
                inEncPolicy = inE as Int,
                allowedEncLevel = level as Int,
                preferRc4 = rc4 as Boolean
            )
            assertEquals(outE, cfg.outEncPolicy)
            assertEquals(inE, cfg.inEncPolicy)
            assertEquals(level, cfg.allowedEncLevel)
            assertEquals(rc4, cfg.preferRc4)
        }
    }

    // =========================================================================
    // SECTION 3: PRIORITY ARRAY TRANSFORMATIONS & BOUNDARY CONDITIONS
    // =========================================================================

    @Test
    fun probe_PriorityEnumMapping_ValuesAndBoundaryConversions() {
        assertEquals(0, Priority.IGNORE.value)
        assertEquals(1, Priority.LOW.value)
        assertEquals(4, Priority.NORMAL.value)
        assertEquals(7, Priority.HIGH.value)

        // fromValue boundary mappings
        assertEquals(Priority.IGNORE, Priority.fromValue(0))
        assertEquals(Priority.IGNORE, Priority.fromValue(-1))
        assertEquals(Priority.IGNORE, Priority.fromValue(-999))
        assertEquals(Priority.IGNORE, Priority.fromValue(Int.MIN_VALUE))

        assertEquals(Priority.LOW, Priority.fromValue(1))
        assertEquals(Priority.LOW, Priority.fromValue(2))
        assertEquals(Priority.LOW, Priority.fromValue(3))

        assertEquals(Priority.NORMAL, Priority.fromValue(4))
        assertEquals(Priority.NORMAL, Priority.fromValue(5))
        assertEquals(Priority.NORMAL, Priority.fromValue(6))

        assertEquals(Priority.HIGH, Priority.fromValue(7))
        assertEquals(Priority.HIGH, Priority.fromValue(8))
        assertEquals(Priority.HIGH, Priority.fromValue(100))
        assertEquals(Priority.HIGH, Priority.fromValue(Int.MAX_VALUE))
    }

    @Test
    fun probe_Priority_LibtorrentSwigConversionRoundTrip() {
        for (p in Priority.values()) {
            val libP = p.toLibtorrentPriority()
            assertNotNull("Libtorrent priority must not be null for $p", libP)
            val back = Priority.fromLibtorrent(libP)
            assertEquals("Round-trip libtorrent conversion failed for $p", p, back)
        }
    }

    @Test
    fun probe_PriorityArrayTransformation_AllIgnoredPattern() {
        val fileCount = 1000
        val files = (0 until fileCount).map { i ->
            PreDownloadFileItem(
                index = i,
                path = "video_series/episode_${String.format(Locale.US, "%04d", i)}.mp4",
                size = 100_000_000L, // 100 MB each
                isSelected = false
            )
        }

        val state = PreDownloadState.create(
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
            name = "Season 1",
            files = files,
            targetDirectory = File("C:/Downloads"),
            availableDiskSpace = 500_000_000_000L
        )

        assertEquals(fileCount, state.totalCount)
        assertEquals(0, state.selectedCount)
        assertTrue(state.noneSelected)
        assertFalse(state.allSelected)
        assertFalse(state.isDownloadEnabled)
        assertEquals(0L, state.selectedSize)
        assertEquals(100_000_000_000L, state.totalSize) // 100 GB

        val priorities = state.toPriorities()
        assertEquals(fileCount, priorities.size)
        assertTrue("All priorities must be IGNORE", priorities.all { it == Priority.IGNORE })
    }

    @Test
    fun probe_PriorityArrayTransformation_SingleFileIgnoredPattern() {
        val fileCount = 50
        val ignoredIndex = 17
        val files = (0 until fileCount).map { i ->
            PreDownloadFileItem(
                index = i,
                path = "album/track_$i.flac",
                size = 20_000_000L,
                isSelected = (i != ignoredIndex)
            )
        }

        val state = PreDownloadState.create(
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
            name = "FLAC Album",
            files = files,
            targetDirectory = File("C:/Downloads"),
            availableDiskSpace = 10_000_000_000L
        )

        assertEquals(49, state.selectedCount)
        assertEquals(50, state.totalCount)
        assertFalse(state.noneSelected)
        assertFalse(state.allSelected)
        assertTrue(state.isDownloadEnabled)
        assertEquals(49 * 20_000_000L, state.selectedSize)

        val priorities = state.toPriorities()
        assertEquals(50, priorities.size)
        for (i in 0 until 50) {
            if (i == ignoredIndex) {
                assertEquals("File at index $i must be IGNORE", Priority.IGNORE, priorities[i])
            } else {
                assertEquals("File at index $i must be NORMAL", Priority.NORMAL, priorities[i])
            }
        }
    }

    @Test
    fun probe_PriorityArrayTransformation_AlternatingPattern() {
        val fileCount = 200
        val files = (0 until fileCount).map { i ->
            PreDownloadFileItem(
                index = i,
                path = "docs/file_$i.pdf",
                size = 1_000_000L,
                isSelected = (i % 2 == 0) // Even files selected, odd files ignored
            )
        }

        val state = PreDownloadState.create(
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
            name = "Documents",
            files = files,
            targetDirectory = File("C:/Downloads"),
            availableDiskSpace = 10_000_000_000L
        )

        assertEquals(100, state.selectedCount)
        assertEquals(200, state.totalCount)
        assertEquals(100 * 1_000_000L, state.selectedSize)
        assertEquals(200 * 1_000_000L, state.totalSize)

        val priorities = state.toPriorities()
        for (i in 0 until 200) {
            val expected = if (i % 2 == 0) Priority.NORMAL else Priority.IGNORE
            assertEquals("Priority mismatch at index $i", expected, priorities[i])
        }
    }

    @Test
    fun probe_PreDownloadState_LargeScaleSizeCalculationNo64BitOverflow() {
        // 50 files of 100 GB each = 5,000 GB (5 TB), exceeds 32-bit integer capacity (2 GB)
        val fileCount = 50
        val singleFileSize = 100L * 1024L * 1024L * 1024L // 100 GB
        val files = (0 until fileCount).map { i ->
            PreDownloadFileItem(
                index = i,
                path = "large_dataset/part_${i}.tar",
                size = singleFileSize,
                isSelected = true
            )
        }

        val expectedTotal = fileCount * singleFileSize // 5,368,709,120,000 bytes (5 TB)
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
            name = "Big Data",
            files = files,
            targetDirectory = File("C:/BigDisk"),
            availableDiskSpace = 10L * 1024L * 1024L * 1024L * 1024L // 10 TB free
        )

        assertEquals(expectedTotal, state.totalSize)
        assertEquals(expectedTotal, state.selectedSize)
        assertFalse(state.hasInsufficientSpace)
        assertEquals("4.88 TB", state.formattedTotalSize)
        assertEquals("4.88 TB", state.formattedSelectedSize)

        // Deselect half the files
        var toggledState = state
        for (i in 0 until 25) {
            toggledState = toggledState.toggleFile(i)
        }
        assertEquals(25, toggledState.selectedCount)
        assertEquals(25 * singleFileSize, toggledState.selectedSize)
        assertEquals("2.44 TB", toggledState.formattedSelectedSize)

        // Deselect all
        val deselectedState = toggledState.deselectAll()
        assertEquals(0, deselectedState.selectedCount)
        assertEquals(0L, deselectedState.selectedSize)
        assertTrue(deselectedState.noneSelected)
        assertFalse(deselectedState.isDownloadEnabled)

        // Select all
        val reselectedState = deselectedState.selectAll()
        assertEquals(50, reselectedState.selectedCount)
        assertEquals(expectedTotal, reselectedState.selectedSize)
        assertTrue(reselectedState.allSelected)
        assertTrue(reselectedState.isDownloadEnabled)
    }

    @Test
    fun probe_PreDownloadState_EmptyFileListBoundary() {
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
            name = "Empty Torrent",
            files = emptyList(),
            targetDirectory = File("C:/Downloads"),
            availableDiskSpace = 1000L
        )

        assertEquals(0, state.totalCount)
        assertEquals(0, state.selectedCount)
        assertFalse(state.allSelected)
        assertTrue(state.noneSelected)
        assertFalse(state.isDownloadEnabled)
        assertEquals(0L, state.totalSize)
        assertEquals(0L, state.selectedSize)
        assertEquals("0 B", state.formattedTotalSize)
        assertEquals("0 B", state.formattedSelectedSize)
        assertTrue(state.toPriorities().isEmpty())
    }

    @Test
    fun probe_TorrentFileItem_FileNameAndSkipStatus() {
        val normalItem = TorrentFileItem(0, "dir/subdir/movie.mp4", 1000L, 500L, 0.5f, Priority.NORMAL)
        assertEquals("movie.mp4", normalItem.fileName)
        assertFalse(normalItem.isSkipped)

        val lowItem = TorrentFileItem(1, "dir\\subdir\\sample.mp4", 100L, 0L, 0.0f, Priority.LOW)
        assertEquals("sample.mp4", lowItem.fileName)
        assertFalse(lowItem.isSkipped)

        val highItem = TorrentFileItem(2, "dir/subdir/subtitles.srt", 10L, 10L, 1.0f, Priority.HIGH)
        assertEquals("subtitles.srt", highItem.fileName)
        assertFalse(highItem.isSkipped)

        val ignoredItem = TorrentFileItem(3, "dir/subdir/bonus.iso", 5000L, 0L, 0.0f, Priority.IGNORE)
        assertEquals("bonus.iso", ignoredItem.fileName)
        assertTrue(ignoredItem.isSkipped)
    }

    // =========================================================================
    // SECTION 4: TRACKER URL SANITATION, DEDUPLICATION & INJECTION PROBES
    // =========================================================================

    @Test
    fun probe_CuratedTrackersCatalog_StrictPort443HttpsRequirements() {
        val trackers = TrackerInjector.HTTPS_PORT_443_TRACKERS

        assertTrue("Curated list must have at least 20 trackers", trackers.size >= 20)
        assertEquals(22, trackers.size)

        val uniqueSet = mutableSetOf<String>()
        for (tr in trackers) {
            assertTrue("Tracker must start with https://: $tr", tr.startsWith("https://"))
            assertTrue("Tracker must target port 443: $tr", tr.contains(":443/") || tr.endsWith(":443"))
            assertTrue("Tracker must have announce path: $tr", tr.contains("/announce"))

            val normalized = tr.trim().lowercase().removeSuffix("/")
            assertTrue("Curated list must not contain duplicate: $tr", uniqueSet.add(normalized))
        }
    }

    @Test
    fun probe_MagnetTrackerInjection_IdempotencyAndNoDuplicates() {
        val baseMagnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=Linux+Distro"

        val pass1 = TrackerInjector.injectTrackers(baseMagnet)
        val pass2 = TrackerInjector.injectTrackers(pass1)
        val pass3 = TrackerInjector.injectTrackers(pass2)

        assertEquals("Multiple injection passes must produce identical string", pass1, pass2)
        assertEquals("Multiple injection passes must produce identical string", pass2, pass3)

        // Verify each curated tracker appears exactly once
        for (tr in TrackerInjector.HTTPS_PORT_443_TRACKERS) {
            val enc = URLEncoder.encode(tr, StandardCharsets.UTF_8.name())
            val count = pass1.split("&tr=").count { it.startsWith(enc) }
            assertEquals("Tracker $tr must appear exactly once", 1, count)
        }
    }

    @Test
    fun probe_MagnetTrackerInjection_MixedCaseAndTrailingSlashesDeduplication() {
        // Pre-populate magnet with dirty variants of curated trackers (uppercase, trailing slashes)
        val dirtyTracker1 = "HTTPS://TRACKER.TAMERSUNION.ORG:443/ANNOUNCE/"
        val dirtyTracker2 = "https://tracker.loligirl.cn:443/announce/"

        val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2" +
                "&tr=" + URLEncoder.encode(dirtyTracker1, StandardCharsets.UTF_8.name()) +
                "&tr=" + URLEncoder.encode(dirtyTracker2, StandardCharsets.UTF_8.name())

        val injected = TrackerInjector.injectTrackers(magnet)

        // Deduplication should ensure no second copy of tamersunion or loligirl is injected
        val tamersUnionEnc = URLEncoder.encode("https://tracker.tamersunion.org:443/announce", StandardCharsets.UTF_8.name())
        val count1 = injected.split("&tr=").count { it.lowercase().contains("tamersunion") }
        assertEquals("tamersunion must not be duplicated", 1, count1)

        val count2 = injected.split("&tr=").count { it.lowercase().contains("loligirl") }
        assertEquals("loligirl must not be duplicated", 1, count2)
    }

    @Test
    fun probe_GetAugmentedTrackers_SanitizationAndOrderPreservation() {
        val dirtyCustomTrackers = listOf(
            "   udp://tracker.openbittorrent.com:6969/announce   ",
            "UDP://TRACKER.OPENTRACKR.ORG:1337/ANNOUNCE",
            "https://tracker.tamersunion.org:443/announce/", // in curated list with trailing slash
            "",
            "   ",
            "udp://tracker.openbittorrent.com:6969/announce" // duplicate custom
        )

        val augmented = TrackerInjector.getAugmentedTrackers(dirtyCustomTrackers)

        // Custom trackers must come first, trimmed and deduplicated
        assertEquals("udp://tracker.openbittorrent.com:6969/announce", augmented[0])
        assertEquals("UDP://TRACKER.OPENTRACKR.ORG:1337/ANNOUNCE", augmented[1])
        assertEquals("https://tracker.tamersunion.org:443/announce/", augmented[2])

        // Total unique = 3 (custom) + 21 (remaining curated minus tamersunion) = 24
        assertEquals(24, augmented.size)

        // All 22 curated trackers represented
        val normalizedAugmented = augmented.map { it.trim().lowercase().removeSuffix("/") }.toSet()
        for (curated in TrackerInjector.HTTPS_PORT_443_TRACKERS) {
            val normCurated = curated.trim().lowercase().removeSuffix("/")
            assertTrue("Curated tracker $curated must be in augmented list", normalizedAugmented.contains(normCurated))
        }
    }

    @Test
    fun probe_DohTrackerResolver_HostExtractionAcrossProtocols() {
        val testCases = listOf(
            "https://tracker.tamersunion.org:443/announce" to "tracker.tamersunion.org",
            "udp://tracker.opentrackr.org:1337/announce" to "tracker.opentrackr.org",
            "http://tracker.renfei.net/announce" to "tracker.renfei.net",
            "https://[2001:db8::1]:443/announce" to "2001:db8::1",
            "udp://192.168.1.1:6881/announce" to "192.168.1.1",
            "   https://TRACKER.LOLIGIRL.CN:443/announce   " to "tracker.loligirl.cn"
        )

        for ((url, expectedHost) in testCases) {
            val host = DohTrackerResolver.extractHost(url)
            assertEquals("Host extraction mismatch for $url", expectedHost, host)
        }

        assertNull(DohTrackerResolver.extractHost(null))
        assertNull(DohTrackerResolver.extractHost(""))
        assertNull(DohTrackerResolver.extractHost("   "))
    }

    @Test
    fun probe_DohTrackerResolver_IpLiteralDetection() {
        assertTrue(DohTrackerResolver.isIpLiteral("127.0.0.1"))
        assertTrue(DohTrackerResolver.isIpLiteral("192.168.1.100"))
        assertTrue(DohTrackerResolver.isIpLiteral("10.0.0.1"))
        assertTrue(DohTrackerResolver.isIpLiteral("2001:db8::1"))
        assertTrue(DohTrackerResolver.isIpLiteral("::1"))

        assertFalse(DohTrackerResolver.isIpLiteral("tracker.example.com"))
        assertFalse(DohTrackerResolver.isIpLiteral("open.tracker.ink"))
        assertFalse(DohTrackerResolver.isIpLiteral("sourzap.org"))
    }

    @Test
    fun probe_ConcurrentTrackerAugmentationUnderStress() {
        runBlocking {
            val trials = 100
            val errors = AtomicInteger(0)

            val jobs = (0 until trials).map { i ->
                async(Dispatchers.Default) {
                    try {
                        val input = listOf("udp://custom_$i.tracker.com:6881/announce", "  https://tracker.tamersunion.org:443/announce  ")
                        val augmented = TrackerInjector.getAugmentedTrackers(input)
                        assertEquals(23, augmented.size)
                        assertTrue(augmented[0].contains("custom_$i"))
                    } catch (e: Throwable) {
                        errors.incrementAndGet()
                    }
                }
            }

            jobs.awaitAll()
            assertEquals("Zero errors allowed in concurrent tracker augmentation", 0, errors.get())
        }
    }
}
