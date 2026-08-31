package com.sourzap.app

import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.service.core.DpiEngine
import com.sourzap.app.service.core.HttpParser
import com.sourzap.app.service.core.LocalDpiProxyServer
import com.sourzap.app.service.core.PacketParser
import com.sourzap.app.service.core.TlsParser
import com.sourzap.app.service.core.TunTcpRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Empirical Challenge Suite for Milestone M2 (BitTorrent & P2P DPI Evasion Resilience).
 * Stress-tests:
 * 1. BitTorrent BEP 0003 handshake detection, BT_SPLIT(1)/BT_SPLIT(2) segment splitting with TCP_NODELAY.
 * 2. Multi-chunk / fragmented handshake buffering in TunTcpRelay with bounded 4096B limit and 0ms passthrough.
 * 3. Binary-safe HttpParser isolating headers via boundary detection and preserving raw binary 0x80..0xFF payloads.
 * 4. LocalDpiProxyServer URI path and IPv6 authority normalization resilient against regex crash vectors.
 * 5. PacketParser zero-exception fuzzing, dual-stack RFC checksum engines, and RFC synthesizers.
 */
class M2EmpiricalChallengeTest {

    // =========================================================================
    // 1. BitTorrent Detection & BT_SPLIT DPI Evasion Stress Tests
    // =========================================================================

    @Test
    fun testBitTorrent_CompleteHandshakeDetectionAndSplitEvasion() {
        val btHandshake = ByteArray(68)
        btHandshake[0] = 0x13.toByte()
        val proto = "BitTorrent protocol".toByteArray(Charsets.ISO_8859_1)
        System.arraycopy(proto, 0, btHandshake, 1, proto.size)

        // 8 reserved bytes
        for (i in 20 until 28) btHandshake[i] = 0x00.toByte()
        // 20-byte SHA-1 info_hash
        for (i in 28 until 48) btHandshake[i] = (i and 0xFF).toByte()
        // 20-byte peer_id
        for (i in 48 until 68) btHandshake[i] = (0xA0 + (i - 48)).toByte()

        // Verify BEP 0003 prefix detection
        assertTrue("Standard 68B handshake must be detected", DpiEngine.isBitTorrentHandshake(btHandshake, 68))
        assertTrue("20B prefix must be detected", DpiEngine.isBitTorrentHandshake(btHandshake, 20))
        assertFalse("19B truncated prefix must NOT be detected", DpiEngine.isBitTorrentHandshake(btHandshake, 19))

        // Corrupted prefix byte
        val corrupted = btHandshake.copyOf()
        corrupted[1] = 'b'.code.toByte() // lowercase 'b'
        assertFalse("Mismatched prefix must NOT be detected", DpiEngine.isBitTorrentHandshake(corrupted, 68))

        // Test BT_SPLIT(1) execution
        val strategy1 = BypassStrategy(
            id = "bt_split_1",
            name = "BT Split 1",
            description = "",
            tlsSplitOffset = 1,
            useMultisplit = false,
            httpHostMod = false,
            blockQuic = true
        )
        val outStream1 = ByteArrayOutputStream()
        var appliedTechnique1 = ""
        val mockSocket = Socket()
        DpiEngine.desyncAndSend(
            socket = mockSocket,
            outputStream = outStream1,
            payload = btHandshake,
            length = btHandshake.size,
            strategy = strategy1,
            onTechniqueApplied = { appliedTechnique1 = it }
        )
        assertEquals("BT_SPLIT(1)", appliedTechnique1)
        assertArrayEquals("Output must reconstruct exact 68-byte handshake", btHandshake, outStream1.toByteArray())

        // Test BT_SPLIT(2) execution
        val strategy2 = BypassStrategy(
            id = "bt_split_2",
            name = "BT Split 2",
            description = "",
            tlsSplitOffset = 2,
            useMultisplit = false,
            httpHostMod = false,
            blockQuic = true
        )
        val outStream2 = ByteArrayOutputStream()
        var appliedTechnique2 = ""
        DpiEngine.desyncAndSend(
            socket = mockSocket,
            outputStream = outStream2,
            payload = btHandshake,
            length = btHandshake.size,
            strategy = strategy2,
            onTechniqueApplied = { appliedTechnique2 = it }
        )
        assertEquals("BT_SPLIT(2)", appliedTechnique2)
        assertArrayEquals("Output must reconstruct exact 68-byte handshake", btHandshake, outStream2.toByteArray())
    }

    // =========================================================================
    // 2. Fragmented Handshake Buffering & Protocol Decision Engine
    // =========================================================================

    @Test
    fun testTunTcpRelay_FragmentedHandshakeBufferingPredicates() {
        // TLS ClientHello fragmentation
        val tlsHeader = byteArrayOf(0x16, 0x03, 0x03, 0x01, 0x00) // Record len = 256, full = 261
        assertFalse("Incomplete TLS record must wait for remaining bytes", TunTcpRelay.isHandshakeComplete(tlsHeader, 5))

        val tlsFull = ByteArray(261)
        System.arraycopy(tlsHeader, 0, tlsFull, 0, 5)
        assertTrue("Complete TLS record must trigger immediate desync", TunTcpRelay.isHandshakeComplete(tlsFull, 261))

        // Large TLS Record capped at MAX_HANDSHAKE_BUFFER_SIZE (4096)
        val tlsHugeHeader = byteArrayOf(0x16, 0x03, 0x03, 0xFF.toByte(), 0xFF.toByte()) // 65535 bytes declared
        val tls4096 = ByteArray(4096)
        System.arraycopy(tlsHugeHeader, 0, tls4096, 0, 5)
        assertTrue("Buffer reaching 4096B limit must complete handshake immediately", TunTcpRelay.isHandshakeComplete(tls4096, 4096))

        // BitTorrent multi-chunk progression
        val btChunk1 = byteArrayOf(0x13, 'B'.code.toByte(), 'i'.code.toByte(), 't'.code.toByte())
        assertFalse("Partial BT prefix must buffer", TunTcpRelay.isHandshakeComplete(btChunk1, btChunk1.size))

        val bt20 = ByteArray(20)
        bt20[0] = 0x13.toByte()
        System.arraycopy("BitTorrent protocol".toByteArray(Charsets.ISO_8859_1), 0, bt20, 1, 19)
        assertFalse("20B prefix must wait for full 68B handshake", TunTcpRelay.isHandshakeComplete(bt20, 20))

        val bt68 = ByteArray(68)
        System.arraycopy(bt20, 0, bt68, 0, 20)
        assertTrue("Complete 68B BT handshake must trigger desync", TunTcpRelay.isHandshakeComplete(bt68, 68))

        // Immediate 0ms passthrough for non-DPI protocols
        val ssh = "SSH-2.0-OpenSSH_8.9\r\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue("SSH must complete in 0ms", TunTcpRelay.isHandshakeComplete(ssh, ssh.size))

        val noiseHandshake = byteArrayOf(0x00, 0x00, 0x18, 0x00, 0x05, 0x02)
        assertTrue("WhatsApp Noise stream must complete in 0ms", TunTcpRelay.isHandshakeComplete(noiseHandshake, noiseHandshake.size))
    }

    // =========================================================================
    // 3. Binary-Safe HttpParser & Header Boundary Tests
    // =========================================================================

    @Test
    fun testHttpParser_AllDelimiterBoundariesAndBinaryBodyIntegrity() {
        val delimiters = listOf(
            "\r\n\r\n" to 4,
            "\n\n" to 2,
            "\r\n\n" to 3,
            "\n\r\n" to 3
        )

        for ((delim, expectedLen) in delimiters) {
            val reqStr = "POST /api/upload HTTP/1.1\r\nHost: upload.server.com$delim"
            val reqBytes = reqStr.toByteArray(Charsets.ISO_8859_1)
            val boundary = HttpParser.findHeaderBoundary(reqBytes, reqBytes.size)

            assertNotNull("Must find boundary for delimiter $delim", boundary)
            assertEquals("Delimiter length must match", expectedLen, boundary!!.second)
        }

        // Test Binary Body Safety: POST request with 1024 raw binary bytes (0x80..0xFF)
        val header = "POST /announce HTTP/1.1\r\nHost: tracker.opentrackr.org:1337\r\nContent-Length: 1024\r\n\r\n"
        val headerBytes = header.toByteArray(Charsets.ISO_8859_1)
        val binaryBody = ByteArray(1024) { (it % 256).toByte() } // Contains 0x00..0xFF full range

        val fullRequest = ByteArray(headerBytes.size + binaryBody.size)
        System.arraycopy(headerBytes, 0, fullRequest, 0, headerBytes.size)
        System.arraycopy(binaryBody, 0, fullRequest, headerBytes.size, binaryBody.size)

        val desynced = HttpParser.desyncHttpPayload(fullRequest, fullRequest.size)
        val desyncedHeaderStr = String(desynced, 0, desynced.size - 1024, Charsets.ISO_8859_1)

        assertTrue("Host header must be case-modified", desyncedHeaderStr.contains("hOst:  tracker.opentrackr.org:1337"))
        assertFalse("Original Host header must be replaced", desyncedHeaderStr.contains("Host: tracker.opentrackr.org:1337"))

        val preservedBody = desynced.copyOfRange(desynced.size - 1024, desynced.size)
        assertArrayEquals("1024 binary body bytes must be 100% bit-exact untouched", binaryBody, preservedBody)
    }

    // =========================================================================
    // 4. LocalDpiProxyServer URI & IPv6 Normalization Tests
    // =========================================================================

    @Test
    fun testLocalDpiProxyServer_RobustUriAndHostNormalization() {
        // Tracker URLs with unescaped 8-bit bytes, brackets, plus signs, parenthesized peer IDs
        val rawTrackerUrl = "http://tracker.torrent.org:6969/announce?info_hash=%80%9F%A1%B2&peer_id=-SZ0001-[v2]+(test)*"
        val normalizedTracker = LocalDpiProxyServer.normalizeUriPath(rawTrackerUrl)
        assertEquals("/announce?info_hash=%80%9F%A1%B2&peer_id=-SZ0001-[v2]+(test)*", normalizedTracker)

        // Bracketed IPv6 proxy URLs
        val ipv6UrlWithPort = "http://[2001:db8:85a3::8a2e:370:7334]:8080/stats?view=summary"
        assertEquals("/stats?view=summary", LocalDpiProxyServer.normalizeUriPath(ipv6UrlWithPort))

        val ipv6UrlNoPath = "http://[2001:db8:85a3::8a2e:370:7334]:8080"
        assertEquals("/", LocalDpiProxyServer.normalizeUriPath(ipv6UrlNoPath))

        val ipv6UrlQueryOnly = "http://[2001:db8::1]?filter=active"
        assertEquals("/?filter=active", LocalDpiProxyServer.normalizeUriPath(ipv6UrlQueryOnly))

        // parseHostAndPort dual-stack tests
        assertEquals(Pair("2001:db8::1", 8080), LocalDpiProxyServer.parseHostAndPort("[2001:db8::1]:8080", 80))
        assertEquals(Pair("2001:db8::1", 80), LocalDpiProxyServer.parseHostAndPort("[2001:db8::1]", 80))
        assertEquals(Pair("2001:db8::1", 443), LocalDpiProxyServer.parseHostAndPort("2001:db8::1", 443))
        assertEquals(Pair("::1", 80), LocalDpiProxyServer.parseHostAndPort("::1", 80))
        assertEquals(Pair("tracker.example.com", 6969), LocalDpiProxyServer.parseHostAndPort("tracker.example.com:6969", 80))
        assertEquals(Pair("tracker.example.com", 80), LocalDpiProxyServer.parseHostAndPort("tracker.example.com", 80))
    }

    // =========================================================================
    // 5. PacketParser Zero-Exception Fuzzing & RFC Synthesizers
    // =========================================================================

    @Test
    fun testPacketParser_ZeroExceptionAdversarialFuzzing() {
        val runs = 5000
        val errors = AtomicInteger(0)

        runBlocking {
            val jobs = (1..10).map {
                async(Dispatchers.Default) {
                    val rng = java.util.Random(42L + it)
                    for (i in 0 until (runs / 10)) {
                        try {
                            val size = rng.nextInt(100)
                            val randomBytes = ByteArray(size)
                            rng.nextBytes(randomBytes)

                            val offset = rng.nextInt(200) - 50 // -50 to 150
                            val length = rng.nextInt(200) - 50

                            PacketParser.parseIpHeader(randomBytes, length)
                            PacketParser.parseIpv4Header(randomBytes, length)
                            PacketParser.parseIpv6Header(randomBytes, length)
                            PacketParser.parseTcpHeader(randomBytes, offset, length)
                            PacketParser.parseUdpHeader(randomBytes, offset, length)

                            PacketParser.computeIpChecksum(randomBytes, offset, length)
                            PacketParser.computeTcpChecksum(randomBytes, offset, length, randomBytes, randomBytes)
                            PacketParser.computeUdpChecksum(randomBytes, offset, length, randomBytes, randomBytes)
                            PacketParser.computeIcmpv6Checksum(randomBytes, offset, length, randomBytes, randomBytes)
                        } catch (_: Throwable) {
                            errors.incrementAndGet()
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        assertEquals("PacketParser must throw ZERO uncaught exceptions under fuzzing", 0, errors.get())
    }

    @Test
    fun testPacketParser_DedicatedSynthesizersRFCCompliance() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("142.250.190.46")

        // 1. buildIpHeader
        val ipHdr = PacketParser.buildIpHeader(srcIp, dstIp, protocol = 6, payloadLen = 32)
        assertEquals(20, ipHdr.size)
        assertEquals(0x45.toByte(), ipHdr[0])
        assertEquals(0.toShort(), PacketParser.computeIpChecksum(ipHdr, 0, 20))

        // 2. buildSynAckPacket
        val synAck = PacketParser.buildSynAckPacket(srcIp, dstIp, srcPort = 443, dstPort = 50000, seqNum = 1000L, ackNum = 500L)
        assertEquals(44, synAck.size) // 20 IP + 24 TCP (MSS option)
        val tcp = PacketParser.parseTcpHeader(synAck, 20, synAck.size)
        assertNotNull(tcp)
        assertTrue(tcp!!.isSyn)
        assertTrue(tcp.isAck)
        assertEquals(24, tcp.dataOffset)

        // 3. buildRstPacket
        val rstAck = PacketParser.buildRstPacket(srcIp, dstIp, srcPort = 443, dstPort = 50000, seqNum = 1001L, ackNum = 501L, isAck = true)
        assertEquals(40, rstAck.size)
        val tcpRst = PacketParser.parseTcpHeader(rstAck, 20, rstAck.size)
        assertNotNull(tcpRst)
        assertTrue(tcpRst!!.isRst)
        assertTrue(tcpRst.isAck)
        assertEquals(0, tcpRst.windowSize)

        // 4. buildUdpIpPacket
        val udpPacket = PacketParser.buildUdpIpPacket(srcIp, dstIp, srcPort = 53, dstPort = 54321, payload = "DNS".toByteArray(Charsets.ISO_8859_1))
        assertEquals(20 + 8 + 3, udpPacket.size)
        val udp = PacketParser.parseUdpHeader(udpPacket, 20, udpPacket.size)
        assertNotNull(udp)
        assertEquals(53, udp!!.srcPort)
        assertEquals(54321, udp.dstPort)
        assertEquals(3, udp.payloadLength)
    }
}
