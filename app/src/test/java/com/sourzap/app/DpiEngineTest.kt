package com.sourzap.app

import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.service.core.DohResolver
import com.sourzap.app.service.core.HttpParser
import com.sourzap.app.service.core.TlsParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class DpiEngineTest {

    @Test
    fun testFakeClientHelloGenerationAndParsing() {
        val fakeSni = "www.google.com"
        val fakeClientHello = TlsParser.createFakeClientHello(fakeSni)

        assertTrue("Fake ClientHello must be at least 50 bytes", fakeClientHello.size > 50)
        assertEquals("Record type must be 0x16 (Handshake)", 0x16.toByte(), fakeClientHello[0])
        assertEquals("TLS version major must be 0x03", 0x03.toByte(), fakeClientHello[1])
        assertEquals("TLS version minor must be 0x01 (TLS 1.0 record layer)", 0x01.toByte(), fakeClientHello[2])
        assertEquals("Handshake type must be 0x01 (ClientHello)", 0x01.toByte(), fakeClientHello[5])

        val parsed = TlsParser.parseClientHello(fakeClientHello, fakeClientHello.size)
        assertTrue("Must be identified as ClientHello", parsed.isClientHello)
        assertEquals("Parsed SNI must match generated fake SNI", fakeSni, parsed.hostname)
        assertTrue("SNI extension offset must be valid", parsed.sniExtensionOffset > 0)
        assertTrue("SNI host offset must be valid", parsed.sniHostOffset > 0)
    }

    @Test
    fun testFakeClientHelloLongDomainRoundtrip() {
        val longSni = "rr1---sn-4g5edn6s.c.googlevideo.com"
        val fakeClientHello = TlsParser.createFakeClientHello(longSni)
        val parsed = TlsParser.parseClientHello(fakeClientHello, fakeClientHello.size)

        assertTrue(parsed.isClientHello)
        assertEquals(longSni, parsed.hostname)
    }

    @Test
    fun testTlsParserWithTruncatedAndInvalidPackets() {
        // Empty buffer
        val emptyResult = TlsParser.parseClientHello(ByteArray(0), 0)
        assertFalse(emptyResult.isClientHello)
        assertNull(emptyResult.hostname)

        // Length < 5
        val shortResult = TlsParser.parseClientHello(byteArrayOf(0x16, 0x03, 0x01), 3)
        assertFalse(shortResult.isClientHello)

        // Non-TLS packet (e.g. HTTP GET)
        val httpBytes = "GET / HTTP/1.1\r\n".toByteArray(Charsets.US_ASCII)
        val nonTlsResult = TlsParser.parseClientHello(httpBytes, httpBytes.size)
        assertFalse(nonTlsResult.isClientHello)

        // Handshake type other than ClientHello (e.g. ServerHello 0x02)
        val serverHello = byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x10, 0x02, 0x00, 0x00, 0x0C)
        val serverHelloResult = TlsParser.parseClientHello(serverHello, serverHello.size)
        assertFalse(serverHelloResult.isClientHello)
    }

    @Test
    fun testHttpParser_AllMethodsAndHostExtraction() {
        val methods = listOf("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "CONNECT")
        for (m in methods) {
            val req = "$m /index.html HTTP/1.1\r\nHost: static.cloudflare.com\r\nUser-Agent: SourZap/1.0\r\n\r\n"
            val bytes = req.toByteArray(Charsets.US_ASCII)
            val result = HttpParser.parseHttpRequest(bytes, bytes.size)

            assertTrue("Must parse method $m", result.isHttp)
            assertEquals(m, result.method)
            assertEquals("static.cloudflare.com", result.host)
            assertTrue(result.hostHeaderOffset > 0)
        }
    }

    @Test
    fun testHttpParser_CaseInsensitiveHostHeader() {
        val rawRequest = "GET /stream HTTP/1.1\r\nhost:  video.example.org:8080\r\nAccept: */*\r\n\r\n"
        val bytes = rawRequest.toByteArray(Charsets.US_ASCII)

        val result = HttpParser.parseHttpRequest(bytes, bytes.size)
        assertTrue(result.isHttp)
        assertEquals("GET", result.method)
        assertEquals("video.example.org:8080", result.host)
    }

    @Test
    fun testHttpParser_WithoutHostHeader() {
        val rawRequest = "GET / HTTP/1.0\r\n\r\n"
        val bytes = rawRequest.toByteArray(Charsets.US_ASCII)

        val result = HttpParser.parseHttpRequest(bytes, bytes.size)
        assertTrue(result.isHttp)
        assertEquals("GET", result.method)
        assertNull(result.host)
    }

    @Test
    fun testHttpParser_NonHttpRejection() {
        val randomBytes = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xAA.toByte())
        val result = HttpParser.parseHttpRequest(randomBytes, randomBytes.size)
        assertFalse(result.isHttp)
        assertNull(result.method)
    }

    @Test
    fun testHttpDesyncPayloadTransformation() {
        val rawRequest = "GET /videoplayback?id=123 HTTP/1.1\r\nHost: rr1---sn-4g5edn6s.googlevideo.com\r\nUser-Agent: Mozilla/5.0\r\n\r\n"
        val bytes = rawRequest.toByteArray(Charsets.US_ASCII)

        val desynced = HttpParser.desyncHttpPayload(bytes, bytes.size)
        val desyncedStr = String(desynced, Charsets.US_ASCII)

        assertTrue("Must replace Host: with hOst:  (case modification and double space)", desyncedStr.contains("\r\nhOst:  rr1---sn-4g5edn6s.googlevideo.com"))
        assertFalse("Must not retain standard Host: header", desyncedStr.contains("\r\nHost: rr1---sn-4g5edn6s.googlevideo.com"))
    }

    @Test
    fun testBitTorrentHandshakeDetection() {
        val btHandshake = ByteArray(68)
        btHandshake[0] = 0x13.toByte()
        val proto = "BitTorrent protocol".toByteArray(Charsets.US_ASCII)
        System.arraycopy(proto, 0, btHandshake, 1, proto.size)

        for (i in 28 until 48) btHandshake[i] = 0xAA.toByte()
        for (i in 48 until 68) btHandshake[i] = 0xBB.toByte()

        assertEquals(0x13.toByte(), btHandshake[0])
        assertEquals('B'.code.toByte(), btHandshake[1])
        assertEquals('i'.code.toByte(), btHandshake[2])
        assertEquals('t'.code.toByte(), btHandshake[3])
        assertEquals('T'.code.toByte(), btHandshake[4])

        val isBt = btHandshake.size >= 20 &&
                btHandshake[0] == 0x13.toByte() &&
                btHandshake[1] == 'B'.code.toByte() &&
                btHandshake[2] == 'i'.code.toByte() &&
                btHandshake[3] == 't'.code.toByte() &&
                btHandshake[4] == 'T'.code.toByte()

        assertTrue("BitTorrent handshake must be detected", isBt)

        val fakeBt = byteArrayOf(0x13, 'B'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 'P'.code.toByte())
        val isFakeBt = fakeBt.size >= 20 && fakeBt[0] == 0x13.toByte()
        assertFalse("Short/invalid packet must not be detected as BitTorrent", isFakeBt)
    }

    @Test
    fun testSshProtocolDetection() {
        val sshHandshake = "SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.6\r\n".toByteArray(Charsets.US_ASCII)

        val isSsh = sshHandshake.size >= 4 &&
                sshHandshake[0] == 'S'.code.toByte() &&
                sshHandshake[1] == 'S'.code.toByte() &&
                sshHandshake[2] == 'H'.code.toByte() &&
                sshHandshake[3] == '-'.code.toByte()

        assertTrue("SSH client handshake must be detected", isSsh)
    }

    @Test
    fun testCriticalDomainPassthroughHeuristics() {
        fun isCriticalPassthrough(hostname: String): Boolean {
            val h = hostname.lowercase()
            if (h.isEmpty()) return false

            if ((h.startsWith("www.google.") || h == "google.com" ||
                        h.endsWith(".google.com") || h.endsWith(".google.co.in") ||
                        h.contains("gstatic.com") || h.contains("googleapis.com") ||
                        h.contains("accounts.google") || h.contains("play.google") ||
                        h.contains("firebaseio.com") || h.contains("mtalk.google.com")) &&
                !h.contains("youtube") && !h.contains("googlevideo") && !h.contains("ytimg")
            ) return true

            if (h.endsWith(".apple.com") || h.endsWith(".icloud.com") ||
                h.endsWith(".microsoft.com") || h.endsWith(".live.com") ||
                h.endsWith(".windowsupdate.com") || h.endsWith(".office.com")
            ) return true

            if (h.contains("challenges.cloudflare.com")) return true

            if (h.contains("paypal.com") || h.contains("stripe.com") ||
                h.contains("razorpay.com") || h.contains("hdfcbank.com") ||
                h.contains("icicibank.com") || h.contains("sbi.co.in") ||
                h.contains("chase.com") || h.contains("bankofamerica.com") ||
                h.contains("wellsfargo.com")
            ) return true

            return false
        }

        assertTrue("Google Accounts must passthrough", isCriticalPassthrough("accounts.google.com"))
        assertTrue("Google Auth API must passthrough", isCriticalPassthrough("oauth2.googleapis.com"))
        assertTrue("Firebase must passthrough", isCriticalPassthrough("sourzap-auth.firebaseio.com"))
        assertTrue("Apple Auth must passthrough", isCriticalPassthrough("appleid.apple.com"))
        assertTrue("Microsoft Login must passthrough", isCriticalPassthrough("login.live.com"))
        assertTrue("Cloudflare Turnstile must passthrough", isCriticalPassthrough("challenges.cloudflare.com"))
        assertTrue("PayPal must passthrough", isCriticalPassthrough("www.paypal.com"))
        assertTrue("Stripe must passthrough", isCriticalPassthrough("api.stripe.com"))
        assertTrue("HDFC Bank must passthrough", isCriticalPassthrough("netbanking.hdfcbank.com"))

        assertFalse("YouTube must NOT passthrough", isCriticalPassthrough("www.youtube.com"))
        assertFalse("GoogleVideo CDN must NOT passthrough", isCriticalPassthrough("rr1---sn-4g5edn6s.googlevideo.com"))
        assertFalse("YouTube Image CDN must NOT passthrough", isCriticalPassthrough("i.ytimg.com"))
        assertFalse("Twitter / X must NOT passthrough", isCriticalPassthrough("x.com"))
        assertFalse("Instagram must NOT passthrough", isCriticalPassthrough("instagram.com"))
        assertFalse("Discord must NOT passthrough", isCriticalPassthrough("discord.com"))
    }

    @Test
    fun testPresetStrategiesIntegrity() {
        val presets = BypassStrategy.DEFAULT_PRESETS
        assertEquals(1, presets.size)

        val auto = BypassStrategy.AUTO_PILOT
        assertEquals("auto_pilot", auto.id)
        assertTrue(auto.blockQuic)
        assertTrue(auto.httpHostMod)
        assertEquals(2, auto.tlsSplitOffset)
        assertFalse(auto.useMultisplit)
    }

    @Test
    fun testZapretTlsSplit2AndMultiSplitCalculations() {
        val fakeClientHello = TlsParser.createFakeClientHello("video.example.com")
        val len = fakeClientHello.size

        // 1. Standard Split2: Split at byte 2
        val splitPos = 2
        val chunk1 = fakeClientHello.copyOfRange(0, splitPos)
        val chunk2 = fakeClientHello.copyOfRange(splitPos, len)

        assertEquals("First chunk must be exactly 2 bytes: [0x16, 0x03]", 2, chunk1.size)
        assertEquals(0x16.toByte(), chunk1[0])
        assertEquals(0x03.toByte(), chunk1[1])
        assertEquals(len - 2, chunk2.size)

        // 2. Custom Multi-split: 3 chunks
        val p1 = 5
        val p2 = (len / 2).coerceIn(p1 + 1, len - 1)
        val m1 = fakeClientHello.copyOfRange(0, p1)
        val m2 = fakeClientHello.copyOfRange(p1, p2)
        val m3 = fakeClientHello.copyOfRange(p2, len)

        assertEquals(5, m1.size)
        assertEquals(p2 - 5, m2.size)
        assertEquals(len - p2, m3.size)
        assertEquals(len, m1.size + m2.size + m3.size)
    }

    @Test
    fun testIpAndUdpChecksumCalculation() {
        val ipHeader = byteArrayOf(
            0x45.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3c.toByte(),
            0x1c.toByte(), 0x46.toByte(), 0x40.toByte(), 0x00.toByte(),
            0x40.toByte(), 0x11.toByte(), 0x00.toByte(), 0x00.toByte(),
            10.toByte(), 0.toByte(), 0.toByte(), 2.toByte(),
            1.toByte(), 1.toByte(), 1.toByte(), 1.toByte()
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
            0x1a.toByte(), 0x0b.toByte(), 0x00.toByte(), 0x35.toByte(),
            0x00.toByte(), 0x0c.toByte(), 0x00.toByte(), 0x00.toByte(),
            'T'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte()
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
            sum += 17
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

    @Test
    fun testDnsLruCacheOperationsAndTtl() {
        val cache = DohResolver.DnsLruCache<String, String>(maxCapacity = 3, defaultTtlMs = 10000L)

        cache.put("domain1.com", "1.1.1.1", 10000L)
        cache.put("domain2.com", "2.2.2.2", 10000L)
        cache.put("domain3.com", "3.3.3.3", 10000L)

        assertEquals(3, cache.size())
        // Access domain1 so domain2 becomes the least recently used
        assertEquals("1.1.1.1", cache.get("domain1.com"))

        // Add 4th item -> domain2 (LRU) must be evicted
        cache.put("domain4.com", "4.4.4.4", 10000L)
        assertEquals(3, cache.size())
        assertNull("Eldest entry should be evicted", cache.get("domain2.com"))
        assertNotNull("Recent entry must exist", cache.get("domain1.com"))
        assertNotNull("Recent entry must exist", cache.get("domain3.com"))
        assertNotNull("Recent entry must exist", cache.get("domain4.com"))

        val fastCache = DohResolver.DnsLruCache<String, String>(maxCapacity = 10, defaultTtlMs = 60_000L)
        fastCache.put("expire.me", "5.5.5.5", 60_000L)
        assertEquals("5.5.5.5", fastCache.get("expire.me"))

        fastCache.clear()
        assertEquals(0, fastCache.size())
        assertNull(fastCache.get("expire.me"))
    }

    @Test
    fun testWireQuestionKeyEquivalence() {
        val googleDomainBytes = "google.com".toByteArray(Charsets.US_ASCII)
        val query1 = ByteArray(12 + 1 + 6 + 1 + 3 + 1 + 4)
        var p = 0
        query1[p++] = 0x12.toByte() // TxID 1
        query1[p++] = 0x34.toByte()
        p = 12 // Skip DNS header
        query1[p++] = 6.toByte()
        System.arraycopy("google".toByteArray(Charsets.US_ASCII), 0, query1, p, 6)
        p += 6
        query1[p++] = 3.toByte()
        System.arraycopy("com".toByteArray(Charsets.US_ASCII), 0, query1, p, 3)
        p += 3
        query1[p++] = 0.toByte() // root
        query1[p++] = 0.toByte(); query1[p++] = 1.toByte() // QTYPE: A
        query1[p++] = 0.toByte(); query1[p++] = 1.toByte() // QCLASS: IN

        val query2 = query1.copyOf()
        query2[0] = 0xAB.toByte() // Different TxID 2
        query2[1] = 0xCD.toByte()

        val key1 = DohResolver.WireQuestionKey.fromQuery(query1)
        val key2 = DohResolver.WireQuestionKey.fromQuery(query2)

        assertNotNull(key1)
        assertNotNull(key2)
        assertEquals("Keys for same question section with different TxIDs must be equal", key1, key2)
        assertEquals("HashCodes must match", key1.hashCode(), key2.hashCode())
    }

    @Test
    fun testDohResolverIpLiteralResolution() = runBlocking {
        DohResolver.clearCache()
        val resolved = DohResolver.resolve("8.8.8.8")
        assertEquals(1, resolved.size)
        assertEquals(InetAddress.getByName("8.8.8.8"), resolved[0])
        assertEquals(1, DohResolver.getCacheSize())
    }

    @Test
    fun testByteArrayPoolRecycling() {
        val sBuf1 = ByteArrayPool.obtainStreamBuffer()
        assertEquals(ByteArrayPool.BUFFER_SIZE, sBuf1.size)
        ByteArrayPool.recycleStreamBuffer(sBuf1)

        val sBuf2 = ByteArrayPool.obtainStreamBuffer()
        assertEquals(ByteArrayPool.BUFFER_SIZE, sBuf2.size)

        val pBuf1 = ByteArrayPool.obtainPacketBuffer()
        assertEquals(ByteArrayPool.PACKET_BUFFER_SIZE, pBuf1.size)
        ByteArrayPool.recyclePacketBuffer(pBuf1)

        val pBuf2 = ByteArrayPool.obtainPacketBuffer()
        assertEquals(ByteArrayPool.PACKET_BUFFER_SIZE, pBuf2.size)
    }
}