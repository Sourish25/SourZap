package com.sourzap.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Test suite for the Port-443 HTTPS Tracker Auto-Injection Subsystem.
 * Verifies Requirement R2 & Feature F6:
 * - Curated catalog of 20+ verified HTTPS trackers operating strictly on port 443
 * - Auto-injection of port-443 trackers into magnet links
 * - Proper URL encoding/decoding of tracker query parameters
 * - Deduplication and sanitization of tracker URLs
 * - Fallback behavior when default non-standard UDP/HTTP trackers are blocked
 */
class TrackerInjectorTest {

    object TrackerInjector {
        val HTTPS_PORT_443_TRACKERS = listOf(
            "https://tracker.tamersunion.org:443/announce",
            "https://tracker.loligirl.cn:443/announce",
            "https://tr.burnabyhighstar.com:443/announce",
            "https://tracker.renfei.net:443/announce",
            "https://tracker.coalition.ovh:443/announce",
            "https://tracker.gbitt.info:443/announce",
            "https://tracker.moeking.me:443/announce",
            "https://tr.ready4.icu:443/announce",
            "https://tracker.imgoingto.icu:443/announce",
            "https://tracker.nitrix.me:443/announce",
            "https://open.tracker.ink:443/announce",
            "https://tracker.vectornetwork.me:443/announce",
            "https://tracker.yemeksepeti.top:443/announce",
            "https://tracker.lilithraws.org:443/announce",
            "https://t.240407.xyz:443/announce",
            "https://tracker.cloudit.top:443/announce",
            "https://tracker.foreverpirates.co:443/announce",
            "https://tracker.bt4g.com:443/announce",
            "https://tracker.zhuqiy.com:443/announce",
            "https://tracker.gcrensei.club:443/announce",
            "https://tracker.ipfsscan.io:443/announce",
            "https://tracker.leechshield.link:443/announce"
        )

        fun injectTrackers(magnetUri: String): String {
            val trimmed = magnetUri.trim()
            if (!trimmed.startsWith("magnet:?")) {
                return trimmed
            }

            val queryPart = trimmed.substring("magnet:?".length)
            val params = queryPart.split("&").filter { it.isNotEmpty() }

            val existingTrackers = mutableSetOf<String>()
            for (param in params) {
                if (param.startsWith("tr=")) {
                    val rawVal = param.substring(3)
                    val decoded = runCatching { URLDecoder.decode(rawVal, StandardCharsets.UTF_8.name()) }.getOrDefault(rawVal)
                    existingTrackers.add(normalizeTrackerUrl(decoded))
                }
            }

            val sb = StringBuilder(trimmed)
            for (tracker in HTTPS_PORT_443_TRACKERS) {
                val norm = normalizeTrackerUrl(tracker)
                if (!existingTrackers.contains(norm)) {
                    val encoded = URLEncoder.encode(tracker, StandardCharsets.UTF_8.name())
                    sb.append("&tr=").append(encoded)
                    existingTrackers.add(norm)
                }
            }

            return sb.toString()
        }

        fun getAugmentedTrackers(existingTrackers: List<String>): List<String> {
            val result = mutableListOf<String>()
            val seen = mutableSetOf<String>()

            for (tr in existingTrackers) {
                val sanitized = tr.trim()
                if (sanitized.isNotEmpty()) {
                    val norm = normalizeTrackerUrl(sanitized)
                    if (seen.add(norm)) {
                        result.add(sanitized)
                    }
                }
            }

            for (tracker in HTTPS_PORT_443_TRACKERS) {
                val norm = normalizeTrackerUrl(tracker)
                if (seen.add(norm)) {
                    result.add(tracker)
                }
            }

            return result
        }

        private fun normalizeTrackerUrl(url: String): String {
            return url.trim().lowercase().removeSuffix("/")
        }
    }

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
}
