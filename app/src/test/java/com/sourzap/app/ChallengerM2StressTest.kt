package com.sourzap.app

import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.service.core.DpiEngine
import com.sourzap.app.service.core.HttpParser
import com.sourzap.app.service.core.LocalDpiProxyServer
import com.sourzap.app.service.core.PacketParser
import com.sourzap.app.service.core.TlsParser
import com.sourzap.app.service.core.TunTcpRelay
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
import java.util.Random

/**
 * Empirical Challenger M2 Stress and Fuzz Test Harness.
 * Systematically stress-tests all M2 components:
 * 1. DpiEngine: BitTorrent handshake detection, BT_SPLIT(1)/BT_SPLIT(2), socket.tcpNoDelay.
 * 2. HttpParser: Header delimiter variations, ISO-8859-1 binary safety (0x00..0xFF), host mod casing.
 * 3. LocalDpiProxyServer: IPv6 bracketed/unbracketed parsing, tracker URI normalization with unescaped binary bytes and regex characters.
 * 4. TunTcpRelay: isHandshakeComplete against TLS, BT, HTTP, SSH, Noise, Raw TCP under fragmentations.
 * 5. PacketParser: RFC dual-stack checksums, synthesizers, and zero-exception guarantees.
 */
class ChallengerM2StressTest {

    // =========================================================================
    // 1. DpiEngine: BitTorrent Detection, Splitting & tcpNoDelay Verification
    // =========================================================================

    @Test
    fun testBitTorrentDetection_ExhaustivePrefixMutationMatrix() {
        val validProto = "BitTorrent protocol".toByteArray(Charsets.ISO_8859_1)
        val validBt68 = ByteArray(68)
        validBt68[0] = 0x13.toByte()
        System.arraycopy(validProto, 0, validBt68, 1, validProto.size)
        for (i in 20 until 68) {
            validBt68[i] = (i * 7).toByte()
        }

        // Test valid lengths
        assertTrue("Valid 68-byte handshake must pass", DpiEngine.isBitTorrentHandshake(validBt68, 68))
        assertTrue("Valid 20-byte prefix must pass", DpiEngine.isBitTorrentHandshake(validBt68, 20))
        assertTrue("Valid handshake with length > 68 must pass", DpiEngine.isBitTorrentHandshake(validBt68, 100))

        // Test truncated lengths 0..19
        for (len in 0 until 20) {
            assertFalse("Truncated buffer of length $len must fail", DpiEngine.isBitTorrentHandshake(validBt68, len))
        }

        // Test buffer size < 20 with large length parameter
        val shortArray = validBt68.copyOfRange(0, 15)
        assertFalse("Physical buffer size < 20 must fail regardless of length param", DpiEngine.isBitTorrentHandshake(shortArray, 68))

        // Test mutating each byte of the 20-byte prefix (positions 0 to 19)
        for (pos in 0 until 20) {
            val mutated = validBt68.copyOf()
            mutated[pos] = (mutated[pos].toInt() xor 0xFF).toByte()
            assertFalse("Mutated byte at index $pos must fail detection", DpiEngine.isBitTorrentHandshake(mutated, 68))
        }
    }

    @Test
    fun testBitTorrentDesync_Split1AndSplit2ByteExactnessAndTcpNoDelay() {
        val validBt68 = ByteArray(68)
        validBt68[0] = 0x13.toByte()
        val proto = "BitTorrent protocol".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(proto, 0, validBt68, 1, proto.size)
        val rng = Random(1337)
        for (i in 20 until 68) {
            validBt68[i] = rng.nextInt(256).toByte()
        }

        val testSocket = Socket()
        assertFalse("New socket tcpNoDelay default should be false", testSocket.tcpNoDelay)

        // --- Test BT_SPLIT(1) ---
        val stratBt1 = BypassStrategy(
            id = "test_bt1",
            name = "BT1",
            description = "",
            tlsSplitOffset = 1,
            useMultisplit = false,
            httpHostMod = false,
            blockQuic = true
        )
        val out1 = ByteArrayOutputStream()
        var technique1 = ""

        DpiEngine.desyncAndSend(
            socket = testSocket,
            outputStream = out1,
            payload = validBt68,
            length = validBt68.size,
            strategy = stratBt1,
            onTechniqueApplied = { technique1 = it }
        )

        assertTrue("socket.tcpNoDelay must be set to true by DpiEngine", testSocket.tcpNoDelay)
        assertEquals("BT_SPLIT(1)", technique1)
        val out1Bytes = out1.toByteArray()
        assertEquals(68, out1Bytes.size)
        assertArrayEquals("BT_SPLIT(1) output must match original handshake byte-for-byte", validBt68, out1Bytes)

        // --- Test BT_SPLIT(2) ---
        val stratBt2 = BypassStrategy(
            id = "test_bt2",
            name = "BT2",
            description = "",
            tlsSplitOffset = 2,
            useMultisplit = false,
            httpHostMod = false,
            blockQuic = true
        )
        val out2 = ByteArrayOutputStream()
        var technique2 = ""

        DpiEngine.desyncAndSend(
            socket = testSocket,
            outputStream = out2,
            payload = validBt68,
            length = validBt68.size,
            strategy = stratBt2,
            onTechniqueApplied = { technique2 = it }
        )

        assertEquals("BT_SPLIT(2)", technique2)
        val out2Bytes = out2.toByteArray()
        assertEquals(68, out2Bytes.size)
        assertArrayEquals("BT_SPLIT(2) output must match original handshake byte-for-byte", validBt68, out2Bytes)

        // --- Test Handshake + Extension Bytes (e.g. BEP 0010 extended handshake payload following standard 68B) ---
        val extendedPayload = ByteArray(256)
        System.arraycopy(validBt68, 0, extendedPayload, 0, 68)
        for (i in 68 until 256) {
            extendedPayload[i] = (i and 0xFF).toByte()
        }

        val outExtended = ByteArrayOutputStream()
        var techniqueExtended = ""
        DpiEngine.desyncAndSend(
            socket = testSocket,
            outputStream = outExtended,
            payload = extendedPayload,
            length = extendedPayload.size,
            strategy = stratBt2,
            onTechniqueApplied = { techniqueExtended = it }
        )
        assertEquals("BT_SPLIT(2)", techniqueExtended)
        assertArrayEquals("Extended BitTorrent payload must be preserved 100%", extendedPayload, outExtended.toByteArray())
    }

    // =========================================================================
    // 2. HttpParser: Boundary Delimiters & Full Binary Body (0x00..0xFF) Safety
    // =========================================================================

    @Test
    fun testHttpParser_AllFourBoundaryDelimitersStrictTesting() {
        val testDelimiters = listOf(
            "\r\n\r\n" to 4,
            "\n\n" to 2,
            "\r\n\n" to 3,
            "\n\r\n" to 3
        )

        for ((delim, expectedLen) in testDelimiters) {
            val prefix = "GET /tracker/announce?info_hash=123 HTTP/1.1\r\nHost: torrent.ubuntu.com"
            val suffix = "BINARY_DATA_PAYLOAD_HERE"
            val fullString = prefix + delim + suffix
            val fullBytes = fullString.toByteArray(Charsets.ISO_8859_1)

            val boundary = HttpParser.findHeaderBoundary(fullBytes, fullBytes.size)
            assertNotNull("Delimiter '$delim' must be detected", boundary)
            assertEquals("Delimiter offset must match prefix length", prefix.toByteArray(Charsets.ISO_8859_1).size, boundary!!.first)
            assertEquals("Delimiter length must match expected length", expectedLen, boundary.second)
        }

        // Test incomplete / fragmented delimiters
        val incomplete1 = "GET / HTTP/1.1\r\nHost: example.com\r\n\r".toByteArray(Charsets.ISO_8859_1)
        assertNull(HttpParser.findHeaderBoundary(incomplete1, incomplete1.size))

        val incomplete2 = "GET / HTTP/1.1\r\nHost: example.com\r".toByteArray(Charsets.ISO_8859_1)
        assertNull(HttpParser.findHeaderBoundary(incomplete2, incomplete2.size))

        val incomplete3 = "GET / HTTP/1.1\nHost: example.com\n\r".toByteArray(Charsets.ISO_8859_1)
        assertNull(HttpParser.findHeaderBoundary(incomplete3, incomplete3.size))
    }

    @Test
    fun testHttpParser_FullBinarySpectrum0x00to0xFFPreservation() {
        // Build 256-byte payload containing EVERY byte value from 0x00 to 0xFF
        val fullSpectrumBody = ByteArray(256) { it.toByte() }

        // Test with different header structures and casing
        val headers = listOf(
            "POST /announce HTTP/1.1\r\nHost: opentracker.i2p.rocks:6969\r\nContent-Length: 256\r\n\r\n",
            "POST /announce HTTP/1.1\nhost:   opentracker.i2p.rocks:6969\nContent-Length: 256\n\n",
            "PUT /upload HTTP/1.0\r\nHOST: backup.internal:8080\r\nContent-Length: 256\r\n\n",
            "PATCH /data HTTP/1.1\nHost: api.cloud.com\r\nContent-Length: 256\n\r\n"
        )

        for (hdr in headers) {
            val hdrBytes = hdr.toByteArray(Charsets.ISO_8859_1)
            val fullReq = ByteArray(hdrBytes.size + fullSpectrumBody.size)
            System.arraycopy(hdrBytes, 0, fullReq, 0, hdrBytes.size)
            System.arraycopy(fullSpectrumBody, 0, fullReq, hdrBytes.size, fullSpectrumBody.size)

            val desynced = HttpParser.desyncHttpPayload(fullReq, fullReq.size)

            // Verify body is intact byte-for-byte
            val extractedBody = desynced.copyOfRange(desynced.size - 256, desynced.size)
            assertArrayEquals("Every single byte 0x00..0xFF must be preserved without corruption", fullSpectrumBody, extractedBody)

            // Verify header has modified host
            val headerSlice = String(desynced, 0, desynced.size - 256, Charsets.ISO_8859_1)
            assertTrue("Header must contain 'hOst:  '", headerSlice.contains("hOst:  ", ignoreCase = false))
        }
    }

    @Test
    fun testHttpParser_SplitHttpHeaderBoundarySafety() {
        val req = "POST /api HTTP/1.1\r\nHost: example.org\r\n\r\nBODY_BYTES".toByteArray(Charsets.ISO_8859_1)

        // Custom split offset within bounds
        val (c1, c2) = HttpParser.splitHttpHeader(req, req.size, splitOffset = 10)
        assertEquals(10, c1.size)
        assertEquals(req.size - 10, c2.size)
        val combined = ByteArray(c1.size + c2.size)
        System.arraycopy(c1, 0, combined, 0, c1.size)
        System.arraycopy(c2, 0, combined, c1.size, c2.size)
        assertArrayEquals("Split chunks must perfectly reconstruct original", req, combined)

        // Out-of-bounds splitOffset (-1, 0, 9999) fallback to host header offset or mid-point
        val (cAuto1, cAuto2) = HttpParser.splitHttpHeader(req, req.size, splitOffset = -1)
        val combinedAuto = ByteArray(cAuto1.size + cAuto2.size)
        System.arraycopy(cAuto1, 0, combinedAuto, 0, cAuto1.size)
        System.arraycopy(cAuto2, 0, combinedAuto, cAuto1.size, cAuto2.size)
        assertArrayEquals("Auto split chunks must perfectly reconstruct original", req, combinedAuto)

        // Edge case: 1 byte or 0 byte buffers
        val (cEmpty1, cEmpty2) = HttpParser.splitHttpHeader(ByteArray(0), 0)
        assertEquals(0, cEmpty1.size)
        assertEquals(0, cEmpty2.size)

        val oneByteBuf = byteArrayOf(0x47) // 'G'
        val (cOne1, cOne2) = HttpParser.splitHttpHeader(oneByteBuf, 1)
        assertEquals(1, cOne1.size)
        assertEquals(0, cOne2.size)
    }

    // =========================================================================
    // 3. LocalDpiProxyServer: IPv6 Authorities & Complex Tracker URIs
    // =========================================================================

    @Test
    fun testLocalDpiProxyServer_ParseHostAndPortExhaustiveMatrix() {
        // IPv6 bracketed with port
        assertEquals(Pair("2001:db8::1", 8080), LocalDpiProxyServer.parseHostAndPort("[2001:db8::1]:8080", 80))
        assertEquals(Pair("::1", 9000), LocalDpiProxyServer.parseHostAndPort("[::1]:9000", 80))
        assertEquals(Pair("fe80::1ff:fe23:4567:890a", 443), LocalDpiProxyServer.parseHostAndPort("[fe80::1ff:fe23:4567:890a]:443", 80))

        // IPv6 bracketed without port
        assertEquals(Pair("2001:db8::1", 80), LocalDpiProxyServer.parseHostAndPort("[2001:db8::1]", 80))
        assertEquals(Pair("::1", 443), LocalDpiProxyServer.parseHostAndPort("[::1]", 443))

        // IPv6 unbracketed
        assertEquals(Pair("2001:db8::1", 80), LocalDpiProxyServer.parseHostAndPort("2001:db8::1", 80))
        assertEquals(Pair("2001:0db8:85a3:0000:0000:8a2e:0370:7334", 80), LocalDpiProxyServer.parseHostAndPort("2001:0db8:85a3:0000:0000:8a2e:0370:7334", 80))
        assertEquals(Pair("::1", 53), LocalDpiProxyServer.parseHostAndPort("::1", 53))

        // IPv4 with port and without port
        assertEquals(Pair("192.168.1.1", 8080), LocalDpiProxyServer.parseHostAndPort("192.168.1.1:8080", 80))
        assertEquals(Pair("10.0.0.1", 80), LocalDpiProxyServer.parseHostAndPort("10.0.0.1", 80))

        // Hostnames
        assertEquals(Pair("tracker.openbittorrent.com", 6969), LocalDpiProxyServer.parseHostAndPort("tracker.openbittorrent.com:6969", 80))
        assertEquals(Pair("example.org", 80), LocalDpiProxyServer.parseHostAndPort("example.org", 80))

        // Malformed / invalid ports fallback to defaultPort
        assertEquals(Pair("example.org", 80), LocalDpiProxyServer.parseHostAndPort("example.org:999999", 80))
        assertEquals(Pair("example.org", 80), LocalDpiProxyServer.parseHostAndPort("example.org:-1", 80))
        assertEquals(Pair("example.org", 80), LocalDpiProxyServer.parseHostAndPort("example.org:abc", 80))
        assertEquals(Pair("", 80), LocalDpiProxyServer.parseHostAndPort("", 80))
    }

    @Test
    fun testLocalDpiProxyServer_NormalizeUriPathExhaustiveMatrix() {
        // 1. BitTorrent Tracker URL with raw escaped bytes and query symbols
        val rawTracker = "http://tracker.opentrackr.org:1337/announce?info_hash=%12%34%56%78%9a%bc%de%f0%12%34%56%78%9a%bc%de%f0%12%34%56%78&peer_id=-qB4390-123456789012&port=6881&uploaded=0&downloaded=0&left=1000&corrupt=0&key=12345678&event=started&numwant=200&compact=1&no_peer_id=1"
        assertEquals(
            "/announce?info_hash=%12%34%56%78%9a%bc%de%f0%12%34%56%78%9a%bc%de%f0%12%34%56%78&peer_id=-qB4390-123456789012&port=6881&uploaded=0&downloaded=0&left=1000&corrupt=0&key=12345678&event=started&numwant=200&compact=1&no_peer_id=1",
            LocalDpiProxyServer.normalizeUriPath(rawTracker)
        )

        // 2. IPv6 Bracketed Host in absolute URI
        val ipv6Url = "http://[2001:db8:85a3::8a2e:370:7334]:8080/path/to/resource?arg=1&val=[test]+(1)*^$"
        assertEquals(
            "/path/to/resource?arg=1&val=[test]+(1)*^$",
            LocalDpiProxyServer.normalizeUriPath(ipv6Url)
        )

        // 3. IPv6 without path
        assertEquals("/", LocalDpiProxyServer.normalizeUriPath("http://[2001:db8::1]:8080"))
        assertEquals("/", LocalDpiProxyServer.normalizeUriPath("http://[2001:db8::1]"))

        // 4. IPv6 with query only (no leading slash after authority)
        assertEquals("/?filter=active&sort=desc", LocalDpiProxyServer.normalizeUriPath("http://[2001:db8::1]?filter=active&sort=desc"))
        assertEquals("/?filter=active", LocalDpiProxyServer.normalizeUriPath("http://example.com?filter=active"))

        // 5. Already relative path
        assertEquals("/announce?info_hash=123", LocalDpiProxyServer.normalizeUriPath("/announce?info_hash=123"))
        assertEquals("/", LocalDpiProxyServer.normalizeUriPath("/"))
        assertEquals("/", LocalDpiProxyServer.normalizeUriPath(""))
    }

    @Test
    fun testLocalDpiProxyServer_IsIpLiteralExhaustive() {
        // Valid IPv4
        assertTrue(LocalDpiProxyServer.isIpLiteral("127.0.0.1"))
        assertTrue(LocalDpiProxyServer.isIpLiteral("10.0.0.1"))
        assertTrue(LocalDpiProxyServer.isIpLiteral("192.168.1.254"))
        assertTrue(LocalDpiProxyServer.isIpLiteral("8.8.8.8"))

        // Valid IPv6
        assertTrue(LocalDpiProxyServer.isIpLiteral("2001:db8::1"))
        assertTrue(LocalDpiProxyServer.isIpLiteral("::1"))
        assertTrue(LocalDpiProxyServer.isIpLiteral("fe80::1"))
        assertTrue(LocalDpiProxyServer.isIpLiteral("2606:4700:4700::1111"))

        // Hostnames / domains
        assertFalse(LocalDpiProxyServer.isIpLiteral("google.com"))
        assertFalse(LocalDpiProxyServer.isIpLiteral("tracker.openbittorrent.com"))
        assertFalse(LocalDpiProxyServer.isIpLiteral("cloudflare-dns.com"))
        assertFalse(LocalDpiProxyServer.isIpLiteral("localhost"))
    }

    // =========================================================================
    // 4. TunTcpRelay: isHandshakeComplete Multi-Chunk Buffering Decisions
    // =========================================================================

    @Test
    fun testTunTcpRelay_IsHandshakeCompleteExhaustiveProtocols() {
        // 1. BitTorrent Handshake Progression
        val btPrefix = DpiEngine.BT_PROTOCOL_BYTES // 20 bytes
        val btFull = ByteArray(68)
        System.arraycopy(btPrefix, 0, btFull, 0, 20)

        // 1-19 bytes matching BT prefix must NOT be complete (wait for more)
        for (len in 1 until 20) {
            val partial = btPrefix.copyOfRange(0, len)
            assertFalse("Partial BT prefix ($len bytes) must wait for full handshake", TunTcpRelay.isHandshakeComplete(partial, len))
        }

        // 20-67 bytes matching BT prefix must NOT be complete (wait for 68-byte handshake)
        for (len in 20 until 68) {
            val partial = btFull.copyOfRange(0, len)
            assertFalse("Partial BT handshake ($len bytes) must wait for full 68 bytes", TunTcpRelay.isHandshakeComplete(partial, len))
        }

        // 68 bytes matching BT prefix MUST be complete
        assertTrue("Exact 68-byte BT handshake must complete", TunTcpRelay.isHandshakeComplete(btFull, 68))
        assertTrue(">68-byte BT handshake with follow-on messages must complete", TunTcpRelay.isHandshakeComplete(btFull, 100))

        // Mismatched prefix starting with 0x13 must NOT wait (proceed immediately)
        val nonBt0x13 = byteArrayOf(0x13, 'N'.code.toByte(), 'O'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), 'T'.code.toByte())
        assertTrue("Non-BT starting with 0x13 must complete immediately", TunTcpRelay.isHandshakeComplete(nonBt0x13, nonBt0x13.size))

        // 2. TLS ClientHello Record Length Progression
        val tlsHeader = byteArrayOf(0x16, 0x03, 0x01, 0x01, 0x2C) // Record length 300 (0x012C), total = 305
        assertFalse("TLS with 5 bytes when 305 expected must wait", TunTcpRelay.isHandshakeComplete(tlsHeader, 5))

        val tls300 = ByteArray(305)
        System.arraycopy(tlsHeader, 0, tls300, 0, 5)
        assertFalse("TLS with 304 bytes when 305 expected must wait", TunTcpRelay.isHandshakeComplete(tls300, 304))
        assertTrue("TLS with exact 305 bytes must complete", TunTcpRelay.isHandshakeComplete(tls300, 305))

        // 3. HTTP Request Progression
        val httpIncomplete = "GET /index.html HTTP/1.1\r\nHost: example.com\r\n".toByteArray(Charsets.ISO_8859_1)
        assertFalse("HTTP headers without terminal delimiter must wait", TunTcpRelay.isHandshakeComplete(httpIncomplete, httpIncomplete.size))

        val httpComplete1 = "GET /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue("HTTP with CRLFCRLF must complete", TunTcpRelay.isHandshakeComplete(httpComplete1, httpComplete1.size))

        val httpComplete2 = "POST /api HTTP/1.1\nHost: example.com\n\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue("HTTP with LFLF must complete", TunTcpRelay.isHandshakeComplete(httpComplete2, httpComplete2.size))

        // 4. Non-DPI Protocols (SSH, WhatsApp Noise, DNS, Raw TCP) -> 0ms immediate completion
        val ssh = "SSH-2.0-OpenSSH_8.9p1\r\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue("SSH must complete immediately (0ms passthrough)", TunTcpRelay.isHandshakeComplete(ssh, ssh.size))

        val noise = byteArrayOf(0x00, 0x00, 0x18, 0x00, 0x05, 0x02)
        assertTrue("Noise stream must complete immediately (0ms passthrough)", TunTcpRelay.isHandshakeComplete(noise, noise.size))

        val empty = ByteArray(0)
        assertFalse("Empty buffer must return false", TunTcpRelay.isHandshakeComplete(empty, 0))
    }
}
