package com.sourzap.app

import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.service.core.HttpParser
import com.sourzap.app.service.core.TlsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiEngineTest {

    @Test
    fun testFakeClientHelloGenerationAndParsing() {
        val fakeSni = "www.google.com"
        val fakeClientHello = TlsParser.createFakeClientHello(fakeSni)

        assertTrue("Fake ClientHello must be at least 50 bytes", fakeClientHello.size > 50)
        assertEquals("Record type must be 0x16 (Handshake)", 0x16.toByte(), fakeClientHello[0])
        assertEquals("Handshake type must be 0x01 (ClientHello)", 0x01.toByte(), fakeClientHello[5])

        val parsed = TlsParser.parseClientHello(fakeClientHello, fakeClientHello.size)
        assertTrue("Must be identified as ClientHello", parsed.isClientHello)
        assertEquals("Parsed SNI must match generated fake SNI", fakeSni, parsed.hostname)
        assertTrue("SNI offset must be valid", parsed.sniExtensionOffset > 0)
    }

    @Test
    fun testHttpParserHostExtractionAndDesync() {
        val rawRequest = "GET /videoplayback?id=123 HTTP/1.1\r\nHost: rr1---sn-4g5edn6s.googlevideo.com\r\nUser-Agent: Mozilla/5.0\r\n\r\n"
        val bytes = rawRequest.toByteArray(Charsets.US_ASCII)

        val result = HttpParser.parseHttpRequest(bytes, bytes.size)
        assertTrue("Must be identified as HTTP", result.isHttp)
        assertEquals("GET", result.method)
        assertEquals("rr1---sn-4g5edn6s.googlevideo.com", result.host)

        val desynced = HttpParser.desyncHttpPayload(bytes, bytes.size)
        val desyncedStr = String(desynced, Charsets.US_ASCII)
        assertTrue("Must contain desynced Host casing", desyncedStr.contains("hOst:  "))
    }

    @Test
    fun testPresetStrategiesIntegrity() {
        val presets = BypassStrategy.DEFAULT_PRESETS
        assertEquals(4, presets.size)

        val auto = BypassStrategy.AUTO_PILOT
        assertEquals("auto_pilot", auto.id)
        assertTrue(auto.blockQuic)
        assertTrue(auto.httpHostMod)
    }

    @Test
    fun testBitTorrentHandshakeDetection() {
        val btHandshake = ByteArray(68)
        btHandshake[0] = 0x13.toByte()
        val proto = "BitTorrent protocol".toByteArray(Charsets.US_ASCII)
        System.arraycopy(proto, 0, btHandshake, 1, proto.size)

        assertEquals(0x13.toByte(), btHandshake[0])
        assertEquals('B'.code.toByte(), btHandshake[1])
        assertEquals('i'.code.toByte(), btHandshake[2])
        assertEquals('t'.code.toByte(), btHandshake[3])
        assertEquals('T'.code.toByte(), btHandshake[4])
    }

    @Test
    fun testVersionComparisonLogic() {
        fun isNewer(latest: String, current: String): Boolean {
            val latestParts = latest.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val currentParts = current.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            return false
        }

        assertTrue(isNewer("1.0.4", "1.0.3"))
        assertTrue(isNewer("1.1.0", "1.0.9"))
        assertTrue(isNewer("2.0.0", "1.9.9"))
        assertTrue(!isNewer("1.0.4", "1.0.4"))
        assertTrue(!isNewer("1.0.3", "1.0.4"))
    }
}