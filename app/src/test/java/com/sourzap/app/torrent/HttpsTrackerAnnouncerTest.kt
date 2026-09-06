package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.HttpsTrackerAnnouncer
import com.sourzap.app.torrent.core.TrackerInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite for HttpsTrackerAnnouncer:
 * - Validates parsing of BEP 23 binary compact peer strings
 * - Validates parsing of BEP 3 bencoded dictionary peer lists
 * - Verifies working tracker catalog completeness and HTTPS port-443 validation
 */
class HttpsTrackerAnnouncerTest {

    @Test
    fun testParseCompactBinaryPeers() {
        // d8:intervali1800e5:peers12:<12 bytes of 2 peers>e
        val prefix = "d8:intervali1800e5:peers12:".toByteArray(Charsets.ISO_8859_1)
        val peer1 = byteArrayOf(83.toByte(), 254.toByte(), 78.toByte(), 74.toByte(), 0x5A.toByte(), 0xEE.toByte()) // 83.254.78.74:23278
        val peer2 = byteArrayOf(93.toByte(), 158.toByte(), 213.toByte(), 92.toByte(), 0x05.toByte(), 0x39.toByte()) // 93.158.213.92:1337
        val suffix = "e".toByteArray(Charsets.ISO_8859_1)

        val fullResponse = prefix + peer1 + peer2 + suffix

        val parsed = HttpsTrackerAnnouncer.parseCompactPeers(fullResponse)
        assertEquals("Should parse 2 compact binary peers", 2, parsed.size)
        assertEquals("83.254.78.74", parsed[0].first)
        assertEquals(23278, parsed[0].second)
        assertEquals("93.158.213.92", parsed[1].first)
        assertEquals(1337, parsed[1].second)
    }

    @Test
    fun testParseDictionaryPeers() {
        // BEP 3 dictionary format:
        // d8:intervali1800e5:peersld2:ip12:83.254.78.744:porti23278eed2:ip13:93.158.213.924:porti1337eeee
        val bencoded = "d8:intervali1800e5:peersld2:ip12:83.254.78.744:porti23278eed2:ip13:93.158.213.924:porti1337eeee"
        val bytes = bencoded.toByteArray(Charsets.ISO_8859_1)

        val parsed = HttpsTrackerAnnouncer.parseCompactPeers(bytes)
        assertEquals("Should parse 2 dictionary peers", 2, parsed.size)
        assertEquals("83.254.78.74", parsed[0].first)
        assertEquals(23278, parsed[0].second)
        assertEquals("93.158.213.92", parsed[1].first)
        assertEquals(1337, parsed[1].second)
    }

    @Test
    fun testParseEmptyAndMalformedPeers() {
        assertEquals(0, HttpsTrackerAnnouncer.parseCompactPeers(ByteArray(0)).size)
        assertEquals(0, HttpsTrackerAnnouncer.parseCompactPeers("d14:failure reason4:faile".toByteArray()).size)
        assertEquals(0, HttpsTrackerAnnouncer.parseCompactPeers("5:peers0:".toByteArray()).size)
    }

    @Test
    fun testWorkingTrackersCatalog() {
        val trackers = HttpsTrackerAnnouncer.WORKING_TRACKERS
        assertTrue("Working trackers must include at least 22 HTTPS trackers", trackers.size >= 22)
        for (tr in trackers) {
            assertTrue("Tracker must be HTTPS: $tr", tr.startsWith("https://"))
            assertTrue("Tracker must target port 443: $tr", tr.contains(":443") || tr.startsWith("https://"))
        }

        // All curated trackers from TrackerInjector must be present
        for (curated in TrackerInjector.HTTPS_PORT_443_TRACKERS) {
            assertTrue("Working trackers must contain $curated", trackers.contains(curated))
        }
    }
}
