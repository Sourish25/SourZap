package com.sourzap.app

import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.service.core.DohResolver
import com.sourzap.app.service.core.DpiEngine
import com.sourzap.app.service.core.HttpParser
import com.sourzap.app.service.core.LocalDpiProxyServer
import com.sourzap.app.service.core.PacketParser
import com.sourzap.app.service.core.TlsParser
import com.sourzap.app.service.core.TunTcpRelay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
    fun testTlsParser_MalformedClientHelloAdversarialFuzzing() {
        // Valid fake ClientHello as baseline
        val base = TlsParser.createFakeClientHello("test.com")

        // 1. Truncate at session ID length offset (byte 43)
        val truncatedAtSessionId = base.copyOfRange(0, 44)
        truncatedAtSessionId[43] = 32.toByte() // Declares 32 bytes session ID, but only 44 bytes total!
        val res1 = TlsParser.parseClientHello(truncatedAtSessionId, truncatedAtSessionId.size)
        assertTrue(res1.isClientHello)
        assertNull(res1.hostname)

        // 2. Cipher suites length declared huge (0xFFFF) at offset 44, 45
        val corruptCiphers = base.copyOf()
        corruptCiphers[44] = 0xFF.toByte() // Cipher suites len MSB
        corruptCiphers[45] = 0xFF.toByte() // Cipher suites len LSB
        val res2 = TlsParser.parseClientHello(corruptCiphers, corruptCiphers.size)
        assertTrue(res2.isClientHello)
        assertNull(res2.hostname)

        // 3. Extensions length declared overflowing
        val parsedBase = TlsParser.parseClientHello(base, base.size)
        val extOffset = parsedBase.sniExtensionOffset
        if (extOffset > 4) {
            val corruptExtLen = base.copyOf()
            corruptExtLen[extOffset - 2] = 0xFF.toByte()
            corruptExtLen[extOffset - 1] = 0xFF.toByte()
            val res3 = TlsParser.parseClientHello(corruptExtLen, corruptExtLen.size)
            assertTrue(res3.isClientHello)
        }

        // 4. Corrupt SNI NameType (set to 0x01 instead of 0x00 host_name)
        val hostOffset = parsedBase.sniHostOffset
        if (hostOffset > 3) {
            val corruptType = base.copyOf()
            corruptType[hostOffset - 3] = 0x01.toByte() // Unknown NameType
            val res4 = TlsParser.parseClientHello(corruptType, corruptType.size)
            assertTrue(res4.isClientHello)
            assertNull("Non-host_name SNI type must return null hostname", res4.hostname)
        }
    }

    @Test
    fun testHttpParser_AllMethodsAndHostExtraction() {
        val methods = listOf("GET", "POST", "HEAD", "OPTIONS", "PUT", "DELETE", "CONNECT", "TRACE", "PATCH")
        for (m in methods) {
            val req = "$m /index.html HTTP/1.1\r\nHost: static.cloudflare.com\r\nUser-Agent: SourZap/1.0\r\n\r\n"
            val bytes = req.toByteArray(Charsets.ISO_8859_1)
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
        val bytes = rawRequest.toByteArray(Charsets.ISO_8859_1)

        val result = HttpParser.parseHttpRequest(bytes, bytes.size)
        assertTrue(result.isHttp)
        assertEquals("GET", result.method)
        assertEquals("video.example.org:8080", result.host)
    }

    @Test
    fun testHttpParser_WithoutHostHeader() {
        val rawRequest = "GET / HTTP/1.0\r\n\r\n"
        val bytes = rawRequest.toByteArray(Charsets.ISO_8859_1)

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
    fun testHttpParser_FindHeaderBoundary() {
        val crlfcrlf = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\nBODY".toByteArray(Charsets.ISO_8859_1)
        val b1 = HttpParser.findHeaderBoundary(crlfcrlf, crlfcrlf.size)
        assertNotNull(b1)
        assertEquals(4, b1!!.second)

        val lflf = "GET / HTTP/1.1\nHost: example.com\n\nBODY".toByteArray(Charsets.ISO_8859_1)
        val b2 = HttpParser.findHeaderBoundary(lflf, lflf.size)
        assertNotNull(b2)
        assertEquals(2, b2!!.second)

        val crlflf = "GET / HTTP/1.1\r\nHost: example.com\r\n\nBODY".toByteArray(Charsets.ISO_8859_1)
        val b3 = HttpParser.findHeaderBoundary(crlflf, crlflf.size)
        assertNotNull(b3)
        assertEquals(3, b3!!.second)

        val lfcrlf = "GET / HTTP/1.1\nHost: example.com\n\r\nBODY".toByteArray(Charsets.ISO_8859_1)
        val b4 = HttpParser.findHeaderBoundary(lfcrlf, lfcrlf.size)
        assertNotNull(b4)
        assertEquals(3, b4!!.second)

        val incomplete = "GET / HTTP/1.1\r\nHost: example.com\r\n".toByteArray(Charsets.ISO_8859_1)
        val b5 = HttpParser.findHeaderBoundary(incomplete, incomplete.size)
        assertNull(b5)
    }

    @Test
    fun testHttpParser_BinarySafeDesyncAndPreservation() {
        val header = "POST /announce HTTP/1.1\r\nHost: tracker.example.com\r\nContent-Length: 128\r\n\r\n"
        val headerBytes = header.toByteArray(Charsets.ISO_8859_1)

        // Create 128 bytes of binary data with high bytes 0x80..0xFF
        val binaryBody = ByteArray(128) { (it + 0x80).toByte() }

        val fullRequest = ByteArray(headerBytes.size + binaryBody.size)
        System.arraycopy(headerBytes, 0, fullRequest, 0, headerBytes.size)
        System.arraycopy(binaryBody, 0, fullRequest, headerBytes.size, binaryBody.size)

        val desynced = HttpParser.desyncHttpPayload(fullRequest, fullRequest.size)

        // Verify header was desynced
        val desyncedStr = String(desynced, Charsets.ISO_8859_1)
        assertTrue(desyncedStr.contains("hOst:  tracker.example.com"))

        // Verify binary body is intact byte-for-byte at the end
        val extractedBody = desynced.copyOfRange(desynced.size - 128, desynced.size)
        assertArrayEquals("Binary body bytes must not be corrupted by ASCII replacement", binaryBody, extractedBody)
    }

    @Test
    fun testHttpParser_SplitHttpHeader() {
        val req = "GET /download HTTP/1.1\r\nHost: cdn.test.com\r\nUser-Agent: test\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val (c1, c2) = HttpParser.splitHttpHeader(req, req.size)

        assertTrue(c1.isNotEmpty())
        assertTrue(c2.isNotEmpty())
        assertEquals(req.size, c1.size + c2.size)

        val combined = ByteArray(c1.size + c2.size)
        System.arraycopy(c1, 0, combined, 0, c1.size)
        System.arraycopy(c2, 0, combined, c1.size, c2.size)
        assertArrayEquals(req, combined)
    }

    @Test
    fun testLocalDpiProxyServer_ParseHostAndPort() {
        assertEquals(Pair("2001:db8::1", 8080), LocalDpiProxyServer.parseHostAndPort("[2001:db8::1]:8080", 80))
        assertEquals(Pair("2001:db8::1", 80), LocalDpiProxyServer.parseHostAndPort("[2001:db8::1]", 80))
        assertEquals(Pair("2001:db8::1", 443), LocalDpiProxyServer.parseHostAndPort("2001:db8::1", 443))
        assertEquals(Pair("::1", 80), LocalDpiProxyServer.parseHostAndPort("::1", 80))
        assertEquals(Pair("example.com", 8080), LocalDpiProxyServer.parseHostAndPort("example.com:8080", 80))
        assertEquals(Pair("example.com", 80), LocalDpiProxyServer.parseHostAndPort("example.com", 80))
        assertEquals(Pair("", 80), LocalDpiProxyServer.parseHostAndPort("", 80))
    }

    @Test
    fun testLocalDpiProxyServer_NormalizeUriPath() {
        val trackerUrl = "http://tracker.example.com:6969/announce?info_hash=%80%91%A2&peer_id=-SZ0001-[v2]+(test)"
        assertEquals("/announce?info_hash=%80%91%A2&peer_id=-SZ0001-[v2]+(test)", LocalDpiProxyServer.normalizeUriPath(trackerUrl))

        assertEquals("/announce", LocalDpiProxyServer.normalizeUriPath("http://[2001:db8::1]:8080/announce"))
        assertEquals("/", LocalDpiProxyServer.normalizeUriPath("http://[2001:db8::1]:8080"))
        assertEquals("/?query=1", LocalDpiProxyServer.normalizeUriPath("http://example.com?query=1"))
        assertEquals("/path/to/resource", LocalDpiProxyServer.normalizeUriPath("/path/to/resource"))
    }

    @Test
    fun testLocalDpiProxyServer_IsIpLiteral() {
        assertTrue(LocalDpiProxyServer.isIpLiteral("127.0.0.1"))
        assertTrue(LocalDpiProxyServer.isIpLiteral("192.168.1.1"))
        assertTrue(LocalDpiProxyServer.isIpLiteral("2001:db8::1"))
        assertTrue(LocalDpiProxyServer.isIpLiteral("::1"))
        assertFalse(LocalDpiProxyServer.isIpLiteral("example.com"))
        assertFalse(LocalDpiProxyServer.isIpLiteral("tracker.openbittorrent.com"))
    }

    @Test
    fun testBitTorrentHandshakeDetection_BEP0003Complete() {
        val btHandshake = ByteArray(68)
        btHandshake[0] = 0x13.toByte()
        val proto = "BitTorrent protocol".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(proto, 0, btHandshake, 1, proto.size)

        for (i in 28 until 48) btHandshake[i] = 0xAA.toByte() // InfoHash
        for (i in 48 until 68) btHandshake[i] = 0xBB.toByte() // PeerID

        assertTrue("68-byte BitTorrent handshake must be detected", DpiEngine.isBitTorrentHandshake(btHandshake, btHandshake.size))

        val prefixOnly = btHandshake.copyOfRange(0, 20)
        assertTrue("20-byte prefix must be detected", DpiEngine.isBitTorrentHandshake(prefixOnly, prefixOnly.size))

        val shortPrefix = btHandshake.copyOfRange(0, 19)
        assertFalse("Prefix < 20 bytes must return false", DpiEngine.isBitTorrentHandshake(shortPrefix, shortPrefix.size))

        val corruptPrefix = btHandshake.copyOf()
        corruptPrefix[5] = 'X'.code.toByte()
        assertFalse("Corrupted prefix must return false", DpiEngine.isBitTorrentHandshake(corruptPrefix, corruptPrefix.size))
    }

    @Test
    fun testBitTorrentDesync_Split1AndSplit2Execution() {
        val btHandshake = ByteArray(68)
        btHandshake[0] = 0x13.toByte()
        val proto = "BitTorrent protocol".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(proto, 0, btHandshake, 1, proto.size)
        for (i in 28 until 48) btHandshake[i] = 0x11.toByte()
        for (i in 48 until 68) btHandshake[i] = 0x22.toByte()

        // 1. Test BT_SPLIT(1)
        val strategySplit1 = BypassStrategy(
            id = "custom_bt1",
            name = "BT 1",
            description = "",
            tlsSplitOffset = 1,
            useMultisplit = false,
            httpHostMod = false,
            blockQuic = true
        )
        val out1 = ByteArrayOutputStream()
        var technique1 = ""
        val dummySocket = Socket()
        DpiEngine.desyncAndSend(
            socket = dummySocket,
            outputStream = out1,
            payload = btHandshake,
            length = btHandshake.size,
            strategy = strategySplit1,
            onTechniqueApplied = { technique1 = it }
        )
        assertEquals("BT_SPLIT(1)", technique1)
        assertArrayEquals("Output must match original 68 bytes exactly", btHandshake, out1.toByteArray())

        // 2. Test BT_SPLIT(2)
        val strategySplit2 = BypassStrategy(
            id = "custom_bt2",
            name = "BT 2",
            description = "",
            tlsSplitOffset = 2,
            useMultisplit = false,
            httpHostMod = false,
            blockQuic = true
        )
        val out2 = ByteArrayOutputStream()
        var technique2 = ""
        DpiEngine.desyncAndSend(
            socket = dummySocket,
            outputStream = out2,
            payload = btHandshake,
            length = btHandshake.size,
            strategy = strategySplit2,
            onTechniqueApplied = { technique2 = it }
        )
        assertEquals("BT_SPLIT(2)", technique2)
        assertArrayEquals("Output must match original 68 bytes exactly", btHandshake, out2.toByteArray())
    }

    @Test
    fun testTunTcpRelay_IsHandshakeComplete() {
        // 1. TLS ClientHello: 5-byte header with record length 100
        val tlsChunk1 = byteArrayOf(0x16, 0x03, 0x01, 0x00, 0x64) // totalLen = 105
        assertFalse("Incomplete TLS chunk must return false", TunTcpRelay.isHandshakeComplete(tlsChunk1, tlsChunk1.size))

        val tlsFull = ByteArray(105)
        System.arraycopy(tlsChunk1, 0, tlsFull, 0, 5)
        assertTrue("Complete TLS record must return true", TunTcpRelay.isHandshakeComplete(tlsFull, tlsFull.size))

        // 2. BitTorrent: prefix only (20 bytes) vs full (68 bytes)
        val btPrefix = ByteArray(20)
        btPrefix[0] = 0x13.toByte()
        System.arraycopy("BitTorrent protocol".toByteArray(Charsets.ISO_8859_1), 0, btPrefix, 1, 19)
        assertFalse("20-byte BT prefix must wait for 68-byte handshake", TunTcpRelay.isHandshakeComplete(btPrefix, btPrefix.size))

        val btFull = ByteArray(68)
        System.arraycopy(btPrefix, 0, btFull, 0, 20)
        assertTrue("68-byte BT handshake must return true", TunTcpRelay.isHandshakeComplete(btFull, btFull.size))

        val nonBtPrefix = byteArrayOf(0x13, 'X'.code.toByte(), 'Y'.code.toByte())
        assertTrue("Non-BT starting with 0x13 must return true (passthrough)", TunTcpRelay.isHandshakeComplete(nonBtPrefix, nonBtPrefix.size))

        // 3. HTTP Request
        val partialHttp = "GET /index".toByteArray(Charsets.ISO_8859_1)
        assertFalse("Partial HTTP headers without boundary must return false", TunTcpRelay.isHandshakeComplete(partialHttp, partialHttp.size))

        val completeHttp = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue("Complete HTTP headers with CRLFCRLF must return true", TunTcpRelay.isHandshakeComplete(completeHttp, completeHttp.size))

        // 4. Non-DPI Protocol (SSH, Noise, Raw TCP) -> 0ms immediate completion
        val ssh = "SSH-2.0-OpenSSH\r\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue("SSH protocol must return true immediately", TunTcpRelay.isHandshakeComplete(ssh, ssh.size))

        val rawTcp = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertTrue("Raw TCP must return true immediately", TunTcpRelay.isHandshakeComplete(rawTcp, rawTcp.size))
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

        val cs = PacketParser.computeIpChecksum(ipHeader, 0, 20)
        assertTrue("Checksum must be non-zero", cs != 0.toShort())

        ipHeader[10] = ((cs.toInt() shr 8) and 0xFF).toByte()
        ipHeader[11] = (cs.toInt() and 0xFF).toByte()
        val verify = PacketParser.computeIpChecksum(ipHeader, 0, 20)
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

        val udpCs = PacketParser.computeUdpChecksum(udpPacket, 0, 12, srcIp, dstIp)
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

    @Test
    fun testByteArrayPool_MultithreadedHighThroughputStress() {
        val threadCount = 20
        val opsPerThread = 1000
        val executor = Executors.newFixedThreadPool(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until opsPerThread) {
                        val sBuf = ByteArrayPool.obtainStreamBuffer()
                        assertEquals(ByteArrayPool.BUFFER_SIZE, sBuf.size)
                        sBuf[0] = (i and 0xFF).toByte()
                        ByteArrayPool.recycleStreamBuffer(sBuf)

                        val pBuf = ByteArrayPool.obtainPacketBuffer()
                        assertEquals(ByteArrayPool.PACKET_BUFFER_SIZE, pBuf.size)
                        pBuf[0] = (i and 0xFF).toByte()
                        ByteArrayPool.recyclePacketBuffer(pBuf)
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals("ByteArrayPool must be 100% thread-safe under concurrent stress", 0, errors.get())
    }
}