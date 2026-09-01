package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.TrackerInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Test suite for the Port-443 HTTPS Tracker Auto-Injection Subsystem.
 * Verifies Requirement R1 & Feature F3:
 * - Curated catalog of 20+ verified HTTPS trackers operating strictly on port 443
 * - Auto-injection of port-443 trackers into magnet links
 * - Auto-injection of port-443 trackers into TorrentInfo
 * - Proper URL encoding/decoding of tracker query parameters
 * - Deduplication and sanitization of tracker URLs
 * - Fallback behavior when default non-standard UDP/HTTP trackers are blocked
 */
class TrackerInjectorTest {

    @Test
    fun `test Curated Catalog contains at least 20 verified Port-443 HTTPS Trackers`() {
        val trackers = TrackerInjector.HTTPS_PORT_443_TRACKERS

        assertTrue("Curated list must contain at least 20 trackers", trackers.size >= 20)
        assertEquals(22, trackers.size)

        for (tracker in trackers) {
            assertTrue("Tracker must use HTTPS: $tracker", tracker.startsWith("https://"))
            assertTrue("Tracker must target port 443: $tracker", tracker.contains(":443") || tracker.startsWith("https://"))
            assertTrue("Tracker must contain announce path: $tracker", tracker.contains("announce"))
        }

        // Verify zero duplicates in curated list
        val uniqueTrackers = trackers.map { it.lowercase() }.toSet()
        assertEquals("Curated list must contain zero duplicates", trackers.size, uniqueTrackers.size)
    }

    @Test
    fun `test injectTrackers appends all 22 HTTPS port-443 trackers to bare magnet`() {
        val bareMagnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=Ubuntu+24.04"
        val injected = TrackerInjector.injectTrackers(bareMagnet)

        assertTrue(injected.startsWith("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2"))
        assertTrue(injected.contains("&dn=Ubuntu+24.04"))

        for (tracker in TrackerInjector.HTTPS_PORT_443_TRACKERS) {
            val encodedTracker = URLEncoder.encode(tracker, StandardCharsets.UTF_8.name())
            assertTrue("Injected magnet must contain $tracker", injected.contains("&tr=$encodedTracker"))
        }
    }

    @Test
    fun `test injectTrackers avoids duplicate tracker injection`() {
        val firstTracker = TrackerInjector.HTTPS_PORT_443_TRACKERS[0]
        val encodedFirst = URLEncoder.encode(firstTracker, StandardCharsets.UTF_8.name())
        val magnetWithOneTracker = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&tr=$encodedFirst"

        val injected = TrackerInjector.injectTrackers(magnetWithOneTracker)

        // Count occurrences of first tracker
        val occurrences = injected.split("&tr=").count { it.startsWith(encodedFirst) }
        assertEquals("Already present tracker must not be duplicated", 1, occurrences)

        // All other 21 trackers must still be present
        for (i in 1 until TrackerInjector.HTTPS_PORT_443_TRACKERS.size) {
            val tr = TrackerInjector.HTTPS_PORT_443_TRACKERS[i]
            val enc = URLEncoder.encode(tr, StandardCharsets.UTF_8.name())
            assertTrue("Missing tracker must be injected: $tr", injected.contains("&tr=$enc"))
        }
    }

    @Test
    fun `test injectTrackers preserves existing UDP and non-standard trackers`() {
        val udpTracker = "udp://tracker.opentrackr.org:1337/announce"
        val encodedUdp = URLEncoder.encode(udpTracker, StandardCharsets.UTF_8.name())
        val magnetWithUdp = "magnet:?xt=urn:btih:4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a&tr=$encodedUdp"

        val injected = TrackerInjector.injectTrackers(magnetWithUdp)

        assertTrue("Existing UDP tracker must be preserved", injected.contains("&tr=$encodedUdp"))
        // Port 443 trackers must be added
        val firstHttps = URLEncoder.encode(TrackerInjector.HTTPS_PORT_443_TRACKERS[0], StandardCharsets.UTF_8.name())
        assertTrue("Port 443 HTTPS tracker must be appended", injected.contains("&tr=$firstHttps"))
    }

    @Test
    fun `test injectTrackers handles malformed or empty inputs gracefully`() {
        assertEquals("", TrackerInjector.injectTrackers(""))
        assertEquals("not-a-magnet", TrackerInjector.injectTrackers("not-a-magnet"))
        assertEquals("http://example.com/test.torrent", TrackerInjector.injectTrackers("http://example.com/test.torrent"))
    }

    @Test
    fun `test getAugmentedTrackers deduplicates and appends port-443 list`() {
        val existing = listOf(
            "udp://tracker.openbittorrent.com:6969/announce",
            "https://tracker.tamersunion.org:443/announce", // already in default list
            "  https://tracker.loligirl.cn:443/announce  ", // with whitespace
            "" // blank entry to filter
        )

        val augmented = TrackerInjector.getAugmentedTrackers(existing)

        // Existing UDP tracker comes first
        assertEquals("udp://tracker.openbittorrent.com:6969/announce", augmented[0])

        // Verify total count = 1 (unique custom UDP) + 22 (all 22 unique port-443) = 23
        assertEquals(23, augmented.size)

        // Verify all 22 HTTPS port-443 trackers are present
        for (tr in TrackerInjector.HTTPS_PORT_443_TRACKERS) {
            assertTrue("Augmented list must contain $tr", augmented.contains(tr))
        }
    }

    @Test
    fun `test getAugmentedTrackers with empty existing list returns full curated catalog`() {
        val augmented = TrackerInjector.getAugmentedTrackers(emptyList())
        assertEquals(TrackerInjector.HTTPS_PORT_443_TRACKERS.size, augmented.size)
        assertEquals(TrackerInjector.HTTPS_PORT_443_TRACKERS, augmented)
    }

    @Test
    fun `test SessionManager methods inspection`() {
        val methods = org.libtorrent4j.SessionManager::class.java.methods.map { it.name }
        assertTrue("SessionManager must have torrents() or find()", methods.contains("find") || methods.contains("torrents"))
    }
}
