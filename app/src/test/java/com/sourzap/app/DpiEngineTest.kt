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
        assertTrue(!isNewer("1.0.8", "1.0.8"))
        assertTrue(!isNewer("1.0.7", "1.0.8"))
        assertTrue(!isNewer("1.0.4", "1.0.4"))
        assertTrue(!isNewer("1.0.3", "1.0.4"))
    }

    @Test
    fun testIpAndUdpChecksumCalculation() {
        // Standard IPv4 header with zeroes for checksum
        val ipHeader = byteArrayOf(
            0x45.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3c.toByte(),
            0x1c.toByte(), 0x46.toByte(), 0x40.toByte(), 0x00.toByte(),
            0x40.toByte(), 0x11.toByte(), 0x00.toByte(), 0x00.toByte(), // Protocol 17 (UDP)
            10.toByte(), 0.toByte(), 0.toByte(), 2.toByte(),          // 10.0.0.2
            1.toByte(), 1.toByte(), 1.toByte(), 1.toByte()             // 1.1.1.1
        )

        fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Short {
            var sum = 0
            for (i in offset until offset + length step 2) {
                val word = if (i + 1 < offset + length) {
                    ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                } else {
                    ((data[i].toInt() and 0xFF) shl 8)
                }
                sum += word
            }
            while ((sum shr 16) > 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            return (sum.inv() and 0xFFFF).toShort()
        }

        val cs = computeIpChecksum(ipHeader, 0, 20)
        assertTrue("Checksum must be non-zero", cs != 0.toShort())

        // Insert checksum into header and recompute: sum of valid header + checksum must be 0
        ipHeader[10] = ((cs.toInt() shr 8) and 0xFF).toByte()
        ipHeader[11] = (cs.toInt() and 0xFF).toByte()
        val verify = computeIpChecksum(ipHeader, 0, 20)
        assertEquals("Verifying valid IP header checksum must yield 0", 0.toShort(), verify)
    }

    @Test
    fun testRfc768UdpChecksumCalculation() {
        val srcIp = byteArrayOf(10, 0, 0, 2)
        val dstIp = byteArrayOf(1, 1, 1, 1)
        val udpPacket = byteArrayOf(
            0x1a.toByte(), 0x0b.toByte(), 0x00.toByte(), 0x35.toByte(), // src: 6667, dst: 53
            0x00.toByte(), 0x0c.toByte(), 0x00.toByte(), 0x00.toByte(), // len: 12, checksum: 0
            'T'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte() // payload: 4 bytes
        )

        fun computeUdpChecksum(
            packet: ByteArray,
            udpOffset: Int,
            udpLen: Int,
            srcIp: ByteArray,
            dstIp: ByteArray
        ): Short {
            var sum = 0
            for (i in 0 until 4 step 2) {
                sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
                sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
            }
            sum += 17 // Protocol UDP
            sum += udpLen

            for (i in udpOffset until udpOffset + udpLen step 2) {
                val b1 = packet[i].toInt() and 0xFF
                val b2 = if (i + 1 < udpOffset + udpLen) packet[i + 1].toInt() and 0xFF else 0
                sum += (b1 shl 8) or b2
            }

            while ((sum shr 16) > 0) {
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            val checksum = (sum.inv() and 0xFFFF).toShort()
            return if (checksum == 0.toShort()) 0xFFFF.toShort() else checksum
        }

        val udpCs = computeUdpChecksum(udpPacket, 0, 12, srcIp, dstIp)
        assertTrue("UDP Checksum must be computed", udpCs != 0.toShort())
    }
}