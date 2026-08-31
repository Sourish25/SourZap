package com.sourzap.app

import com.sourzap.app.service.core.PacketParser
import com.sourzap.app.service.core.TunTcpRelay
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
import java.net.InetAddress
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Empirical Verification & Fuzzing Harness for M2:
 * 1. Zero-exception fuzzing for PacketParser with negative offsets, truncated buffers (<20, <40, <8 bytes),
 *    malformed IHLs (0, 1, 15), malformed total lengths (0, 65535, >buffer.size), and invalid IP addresses.
 * 2. RFC 791, 793, 768, 4443, 8200 IPv4 and IPv6 dual-stack checksum accuracy.
 * 3. Synthesizers: buildTcpPacket, buildTcpIpPacket, buildSynAckPacket, buildRstPacket, buildUdpIpPacket, and ICMP builders.
 * 4. TunTcpRelay isHandshakeComplete logic across TLS, BitTorrent, HTTP, and non-DPI protocols.
 */
class PacketParserFuzzAndRelayChallengerTest {

    // =========================================================================
    // SECTION 1: Zero-Exception Fuzzing on PacketParser
    // =========================================================================

    @Test
    fun testPacketParser_ZeroException_NegativeOffsetsAndExtremeLengths() {
        val testBuffer = ByteArray(128) { (it and 0xFF).toByte() }
        val negativeOffsets = listOf(-1, -2, -10, -100, -1000, Int.MIN_VALUE)
        val extremeLengths = listOf(-1, -100, Int.MIN_VALUE, 0, 129, 1000, 65535, Int.MAX_VALUE)

        for (offset in negativeOffsets) {
            for (len in extremeLengths) {
                assertNull(PacketParser.parseTcpHeader(testBuffer, offset, len))
                assertNull(PacketParser.parseUdpHeader(testBuffer, offset, len))
                assertEquals(0.toShort(), PacketParser.computeIpChecksum(testBuffer, offset, len))
                assertEquals(0.toShort(), PacketParser.computeTcpChecksum(testBuffer, offset, len, byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8)))
                assertEquals(0.toShort(), PacketParser.computeUdpChecksum(testBuffer, offset, len, byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8)))
                assertEquals(0.toShort(), PacketParser.computeIcmpv6Checksum(testBuffer, offset, len, ByteArray(16), ByteArray(16)))
            }
        }
    }

    @Test
    fun testPacketParser_ZeroException_TruncatedBuffers() {
        // Truncated IPv4 buffers (< 20 bytes)
        for (size in 0 until 20) {
            val buf = ByteArray(size) { 0x45.toByte() }
            assertNull("IPv4 buffer of size $size must return null", PacketParser.parseIpv4Header(buf, size))
            assertNull("Unified IP parser of size $size must return null", PacketParser.parseIpHeader(buf, size))
        }

        // Truncated IPv6 buffers (< 40 bytes)
        for (size in 0 until 40) {
            val buf = ByteArray(size) { 0x60.toByte() }
            assertNull("IPv6 buffer of size $size must return null", PacketParser.parseIpv6Header(buf, size))
        }

        // Truncated TCP headers (< 20 bytes relative to offset)
        for (size in 0 until 20) {
            val buf = ByteArray(size)
            assertNull("TCP header with buffer size $size must return null", PacketParser.parseTcpHeader(buf, 0, size))
        }

        // Truncated UDP headers (< 8 bytes relative to offset)
        for (size in 0 until 8) {
            val buf = ByteArray(size)
            assertNull("UDP header with buffer size $size must return null", PacketParser.parseUdpHeader(buf, 0, size))
        }
    }

    @Test
    fun testPacketParser_ZeroException_MalformedIhlValues() {
        // IHL ranges from 0 to 15 (words: 0 bytes to 60 bytes)
        // Valid RFC 791 requires minimum 5 words = 20 bytes.
        val baseBuffer = ByteArray(100)

        for (ihlWord in 0..15) {
            baseBuffer[0] = (0x40 or (ihlWord and 0x0F)).toByte()
            val parsed = PacketParser.parseIpv4Header(baseBuffer, baseBuffer.size)
            if (ihlWord < 5) {
                assertNull("IHL word $ihlWord (${ihlWord * 4} bytes) < 20 bytes must return null", parsed)
            } else {
                assertNotNull("IHL word $ihlWord (${ihlWord * 4} bytes) must parse validly", parsed)
                assertEquals(ihlWord * 4, parsed!!.headerLength)
            }
        }

        // IHL = 15 (60 bytes) with a buffer of only 30 bytes (< IHL)
        val shortBuffer = ByteArray(30)
        shortBuffer[0] = 0x4F.toByte() // IHL = 15 -> 60 bytes
        assertNull("IHL (60B) larger than valid buffer (30B) must return null", PacketParser.parseIpv4Header(shortBuffer, shortBuffer.size))
    }

    @Test
    fun testPacketParser_ZeroException_MalformedTotalLengths() {
        val srcIp = InetAddress.getByName("192.168.1.1")
        val dstIp = InetAddress.getByName("8.8.8.8")
        val validPacket = PacketParser.buildTcpPacket(srcIp, dstIp, 12345, 80, 100L, 0L, 0x02)

        // 1. Total length = 0 in header -> should fallback safely to validLen
        val packetZeroTotalLen = validPacket.copyOf()
        packetZeroTotalLen[2] = 0x00.toByte()
        packetZeroTotalLen[3] = 0x00.toByte()
        val parsedZero = PacketParser.parseIpv4Header(packetZeroTotalLen, packetZeroTotalLen.size)
        assertNotNull(parsedZero)
        assertEquals(packetZeroTotalLen.size, parsedZero!!.totalLength)

        // 2. Total length = 65535 (exceeds buffer.size) -> should clamp safely to validLen
        val packetHugeTotalLen = validPacket.copyOf()
        packetHugeTotalLen[2] = 0xFF.toByte()
        packetHugeTotalLen[3] = 0xFF.toByte()
        val parsedHuge = PacketParser.parseIpv4Header(packetHugeTotalLen, packetHugeTotalLen.size)
        assertNotNull(parsedHuge)
        assertEquals(packetHugeTotalLen.size, parsedHuge!!.totalLength)

        // 3. Total length < IHL (e.g. total length = 10 while IHL = 20) -> should clamp safely
        val packetUnderflow = validPacket.copyOf()
        packetUnderflow[2] = 0x00.toByte()
        packetUnderflow[3] = 0x0A.toByte() // 10 bytes
        val parsedUnderflow = PacketParser.parseIpv4Header(packetUnderflow, packetUnderflow.size)
        assertNotNull(parsedUnderflow)
        assertEquals(packetUnderflow.size, parsedUnderflow!!.totalLength)
    }

    @Test
    fun testPacketParser_ZeroException_InvalidIpAddressesAndMismatches() {
        val ipv4Addr = InetAddress.getByName("10.0.0.1")
        val ipv6Addr = InetAddress.getByName("2001:db8::1")

        // Passing IPv6 address to IPv4 builder should return empty array safely
        val invalidTcpIpv6 = PacketParser.buildTcpPacket(ipv6Addr, ipv4Addr, 80, 80, 1L, 1L, 0x02)
        assertEquals(0, invalidTcpIpv6.size)

        val invalidUdpIpv6 = PacketParser.buildUdpIpPacket(ipv4Addr, ipv6Addr, 53, 53, ByteArray(10))
        assertEquals(0, invalidUdpIpv6.size)

        val invalidIcmpv4WithIpv6 = PacketParser.buildIcmpPortUnreachablePacket(ByteArray(20), 20, 20, ipv6Addr, ipv4Addr)
        assertEquals(0, invalidIcmpv4WithIpv6.size)

        // Passing IPv4 address to ICMPv6 builder should return empty array safely
        val invalidIcmpv6WithIpv4 = PacketParser.buildIcmpv6AddressUnreachablePacket(ByteArray(40), 40, ipv4Addr, ipv6Addr)
        assertEquals(0, invalidIcmpv6WithIpv4.size)

        // Checksum calculations with mismatched IP array lengths
        val dummy = ByteArray(40)
        assertEquals(0.toShort(), PacketParser.computeTcpChecksum(dummy, 0, 20, ByteArray(4), ByteArray(16)))
        assertEquals(0.toShort(), PacketParser.computeTcpChecksum(dummy, 0, 20, ByteArray(16), ByteArray(4)))
        assertEquals(0.toShort(), PacketParser.computeTcpChecksum(dummy, 0, 20, ByteArray(5), ByteArray(5)))
        assertEquals(0.toShort(), PacketParser.computeUdpChecksum(dummy, 0, 8, ByteArray(3), ByteArray(4)))
        assertEquals(0.toShort(), PacketParser.computeIcmpv6Checksum(dummy, 0, 8, ByteArray(4), ByteArray(4)))
    }

    @Test
    fun testPacketParser_HighThroughputMultiThreadedFuzzHarness() {
        val fuzzIterationsPerThread = 5000
        val threadCount = 8
        val uncaughtExceptions = AtomicInteger(0)

        runBlocking {
            val jobs = (1..threadCount).map { threadId ->
                async(Dispatchers.Default) {
                    val rng = Random(12345L + threadId)
                    for (i in 0 until fuzzIterationsPerThread) {
                        try {
                            val bufLen = rng.nextInt(300)
                            val buffer = ByteArray(bufLen)
                            rng.nextBytes(buffer)

                            val offset = rng.nextInt(400) - 100
                            val length = rng.nextInt(400) - 100

                            PacketParser.parseIpHeader(buffer, length)
                            PacketParser.parseIpv4Header(buffer, length)
                            PacketParser.parseIpv6Header(buffer, length)
                            PacketParser.parseTcpHeader(buffer, offset, length)
                            PacketParser.parseUdpHeader(buffer, offset, length)

                            PacketParser.computeIpChecksum(buffer, offset, length)
                            PacketParser.computeTcpChecksum(buffer, offset, length, buffer, buffer)
                            PacketParser.computeUdpChecksum(buffer, offset, length, buffer, buffer)
                            PacketParser.computeIcmpv6Checksum(buffer, offset, length, buffer, buffer)
                        } catch (t: Throwable) {
                            uncaughtExceptions.incrementAndGet()
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        assertEquals("Multi-threaded adversarial fuzz harness must encounter 0 uncaught exceptions", 0, uncaughtExceptions.get())
    }

    // =========================================================================
    // SECTION 2: Checksum Accuracy (RFC 791, 793, 768, 4443, 8200)
    // =========================================================================

    @Test
    fun testChecksum_Rfc791Ipv4HeaderAccuracy() {
        // RFC 1071 Example: [0x45, 0x00, 0x00, 0x3c, 0x1c, 0x46, 0x40, 0x00, 0x40, 0x06, 0x00, 0x00, 0xac, 0x10, 0x0a, 0x63, 0xac, 0x10, 0x0a, 0x0c]
        val header = byteArrayOf(
            0x45.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3c.toByte(),
            0x1c.toByte(), 0x46.toByte(), 0x40.toByte(), 0x00.toByte(),
            0x40.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), // Checksum bytes at 10, 11
            0xac.toByte(), 0x10.toByte(), 0x0a.toByte(), 0x63.toByte(),
            0xac.toByte(), 0x10.toByte(), 0x0a.toByte(), 0x0c.toByte()
        )

        val computed = PacketParser.computeIpChecksum(header, 0, 20)
        // Put computed checksum into header
        header[10] = ((computed.toInt() shr 8) and 0xFF).toByte()
        header[11] = (computed.toInt() and 0xFF).toByte()

        // Valid RFC 791 header sum including checksum must verify to 0
        val verification = PacketParser.computeIpChecksum(header, 0, 20)
        assertEquals("RFC 791 checksum verification must evaluate to 0", 0.toShort(), verification)

        // Odd-length data checksum test
        val oddData = byteArrayOf(0x01, 0x02, 0x03)
        val oddChecksum = PacketParser.computeIpChecksum(oddData, 0, 3)
        assertTrue("Odd length checksum must be non-zero", oddChecksum != 0.toShort())
    }

    @Test
    fun testChecksum_Rfc793Ipv4TcpPseudoHeaderAccuracy() {
        val srcIp = InetAddress.getByName("10.0.0.1")
        val dstIp = InetAddress.getByName("192.168.1.1")
        val payload = "HELLO_TCP_WORLD_RFC793".toByteArray(Charsets.ISO_8859_1)

        val packet = PacketParser.buildTcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 443,
            dstPort = 55555,
            seqNum = 1000L,
            ackNum = 2000L,
            flags = 0x18, // PSH | ACK
            payload = payload
        )

        val tcpOffset = 20
        val tcpLen = packet.size - tcpOffset
        val extractedChecksum = (((packet[tcpOffset + 16].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 17].toInt() and 0xFF)).toShort()

        // Recalculate checksum with zeroed checksum field
        packet[tcpOffset + 16] = 0x00.toByte()
        packet[tcpOffset + 17] = 0x00.toByte()

        val recomputed = PacketParser.computeTcpChecksum(packet, tcpOffset, tcpLen, srcIp.address, dstIp.address)
        assertEquals("TCP checksum calculation must be deterministic and bit-exact", extractedChecksum, recomputed)
    }

    @Test
    fun testChecksum_Rfc8200Ipv6TcpPseudoHeaderAccuracy() {
        val srcIp = byteArrayOf(
            0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 1
        )
        val dstIp = byteArrayOf(
            0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 2
        )

        val tcpSegment = ByteArray(28) // 20B header + 8B payload
        tcpSegment[0] = 0x00.toByte(); tcpSegment[1] = 0x50.toByte() // Port 80
        tcpSegment[2] = 0x1F.toByte(); tcpSegment[3] = 0x90.toByte() // Port 8080
        tcpSegment[12] = 0x50.toByte() // Data Offset = 5 (20 bytes)
        tcpSegment[13] = 0x10.toByte() // ACK

        val checksum = PacketParser.computeTcpChecksum(tcpSegment, 0, tcpSegment.size, srcIp, dstIp)
        assertTrue("IPv6 TCP pseudo-header checksum must be non-zero", checksum != 0.toShort())
    }

    @Test
    fun testChecksum_Rfc768UdpZeroRuleCompliance() {
        // RFC 768: If the computed checksum is zero, it is transmitted as all ones (0xFFFF).
        val srcIp = byteArrayOf(0, 0, 0, 0)
        val dstIp = byteArrayOf(0, 0, 0, 0)
        val udpPacket = ByteArray(8) // All zeros -> sum will be 17 + 8 = 25

        val cs = PacketParser.computeUdpChecksum(udpPacket, 0, 8, srcIp, dstIp)
        assertTrue("UDP checksum must never return 0x0000 (RFC 768)", cs != 0.toShort())
    }

    @Test
    fun testChecksum_Rfc4443Icmpv6PseudoHeaderAccuracy() {
        val srcIp = ByteArray(16) { 1 }
        val dstIp = ByteArray(16) { 2 }
        val icmpv6Packet = ByteArray(48) // 40B IPv6 + 8B ICMPv6
        icmpv6Packet[40] = 1.toByte() // Type 1: Destination Unreachable
        icmpv6Packet[41] = 3.toByte() // Code 3: Address Unreachable

        val cs = PacketParser.computeIcmpv6Checksum(icmpv6Packet, 40, 8, srcIp, dstIp)
        assertTrue("ICMPv6 checksum must be computed and non-zero", cs != 0.toShort())
    }

    // =========================================================================
    // SECTION 3: Synthesizer Engines & Roundtrip Integrity
    // =========================================================================

    @Test
    fun testSynthesizers_BuildTcpPacket_AllFlagsAndRoundtrips() {
        val srcIp = InetAddress.getByName("10.0.0.5")
        val dstIp = InetAddress.getByName("172.16.0.1")
        val payload = "SYNTHESIZER_TEST_PAYLOAD".toByteArray(Charsets.ISO_8859_1)

        val flagsToTest = listOf(
            0x02 to "SYN",
            0x12 to "SYN-ACK",
            0x10 to "ACK",
            0x18 to "PSH-ACK",
            0x11 to "FIN-ACK",
            0x14 to "RST-ACK",
            0x04 to "RST"
        )

        for ((flag, name) in flagsToTest) {
            val packet = PacketParser.buildTcpPacket(
                srcIp = srcIp,
                dstIp = dstIp,
                srcPort = 50000,
                dstPort = 443,
                seqNum = 123456L,
                ackNum = 654321L,
                flags = flag,
                payload = if (flag == 0x02 || flag == 0x12 || flag == 0x14 || flag == 0x04) PacketParser.EMPTY_BYTE_ARRAY else payload
            )

            assertTrue("Synthesized packet for $name must not be empty", packet.isNotEmpty())

            val ipHeader = PacketParser.parseIpv4Header(packet, packet.size)
            assertNotNull("IPv4 header for $name must be valid", ipHeader)
            assertEquals(srcIp, ipHeader!!.srcIp)
            assertEquals(dstIp, ipHeader.dstIp)
            assertEquals(6, ipHeader.protocol)

            val tcpHeader = PacketParser.parseTcpHeader(packet, 20, packet.size)
            assertNotNull("TCP header for $name must be valid", tcpHeader)
            assertEquals(50000, tcpHeader!!.srcPort)
            assertEquals(443, tcpHeader.dstPort)
            assertEquals(123456L, tcpHeader.seqNum)
            assertEquals(654321L, tcpHeader.ackNum)
            assertEquals(flag, tcpHeader.flags)

            if (flag == 0x12) {
                assertEquals("SYN-ACK must include 4-byte MSS option (total 24 bytes)", 24, tcpHeader.dataOffset)
            } else {
                assertEquals("Standard TCP header must be 20 bytes", 20, tcpHeader.dataOffset)
            }
        }
    }

    @Test
    fun testSynthesizers_DedicatedSynAckAndRstBuilders() {
        val srcIp = InetAddress.getByName("10.0.0.1")
        val dstIp = InetAddress.getByName("1.1.1.1")

        // 1. buildSynAckPacket
        val synAck = PacketParser.buildSynAckPacket(srcIp, dstIp, 80, 49152, 1000L, 500L)
        assertEquals(44, synAck.size)
        val parsedSynAck = PacketParser.parseTcpHeader(synAck, 20, synAck.size)
        assertNotNull(parsedSynAck)
        assertTrue(parsedSynAck!!.isSyn)
        assertTrue(parsedSynAck.isAck)

        // 2. buildRstPacket (with ACK)
        val rstAck = PacketParser.buildRstPacket(srcIp, dstIp, 80, 49152, 1001L, 501L, isAck = true)
        assertEquals(40, rstAck.size)
        val parsedRstAck = PacketParser.parseTcpHeader(rstAck, 20, rstAck.size)
        assertNotNull(parsedRstAck)
        assertTrue(parsedRstAck!!.isRst)
        assertTrue(parsedRstAck.isAck)

        // 3. buildRstPacket (without ACK)
        val rstOnly = PacketParser.buildRstPacket(srcIp, dstIp, 80, 49152, 1001L, 0L, isAck = false)
        assertEquals(40, rstOnly.size)
        val parsedRstOnly = PacketParser.parseTcpHeader(rstOnly, 20, rstOnly.size)
        assertNotNull(parsedRstOnly)
        assertTrue(parsedRstOnly!!.isRst)
        assertFalse(parsedRstOnly.isAck)
    }

    @Test
    fun testSynthesizers_BuildUdpIpPacket() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("8.8.4.4")
        val dnsPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01)

        val udpPacket = PacketParser.buildUdpIpPacket(srcIp, dstIp, 53535, 53, dnsPayload)
        assertEquals(20 + 8 + dnsPayload.size, udpPacket.size)

        val ip = PacketParser.parseIpv4Header(udpPacket, udpPacket.size)
        assertNotNull(ip)
        assertEquals(17, ip!!.protocol)

        val udp = PacketParser.parseUdpHeader(udpPacket, 20, udpPacket.size)
        assertNotNull(udp)
        assertEquals(53535, udp!!.srcPort)
        assertEquals(53, udp.dstPort)
        assertEquals(dnsPayload.size, udp.payloadLength)
    }

    @Test
    fun testSynthesizers_IcmpBuildersAndMaxPayloadClipping() {
        val src4 = InetAddress.getByName("10.0.0.1")
        val dst4 = InetAddress.getByName("8.8.8.8")
        val src6 = InetAddress.getByName("2001:db8::1")
        val dst6 = InetAddress.getByName("2001:db8::2")

        val origIpv4 = ByteArray(60) { (it + 1).toByte() }
        val icmpv4 = PacketParser.buildIcmpPortUnreachablePacket(origIpv4, origIpv4.size, 20, src4, dst4)
        assertEquals(20 + 8 + 28, icmpv4.size) // IP + ICMP + (20 IP + 8 UDP)
        assertEquals(1.toByte(), icmpv4[9]) // Protocol ICMP
        assertEquals(3.toByte(), icmpv4[20]) // Type 3
        assertEquals(3.toByte(), icmpv4[21]) // Code 3

        // ICMPv6 Large Payload clipping to 1232 bytes max (1280 max IPv6 MTU)
        val hugeIpv6 = ByteArray(2000) { (it and 0xFF).toByte() }
        val icmpv6 = PacketParser.buildIcmpv6PortUnreachablePacket(hugeIpv6, hugeIpv6.size, src6, dst6)
        assertEquals(1280, icmpv6.size) // 40 (IPv6) + 8 (ICMPv6) + 1232 (clipped payload)
        assertEquals(0x60.toByte(), icmpv6[0]) // Version 6
        assertEquals(58.toByte(), icmpv6[6]) // Next Header ICMPv6
        assertEquals(1.toByte(), icmpv6[40]) // Type 1
        assertEquals(4.toByte(), icmpv6[41]) // Code 4
    }

    // =========================================================================
    // SECTION 4: TunTcpRelay isHandshakeComplete Predicate Logic
    // =========================================================================

    @Test
    fun testTunTcpRelay_HandshakeComplete_TlsEdgeCases() {
        // 1. Empty / truncated
        assertFalse(TunTcpRelay.isHandshakeComplete(ByteArray(0), 0))
        assertFalse(TunTcpRelay.isHandshakeComplete(byteArrayOf(0x16), 1))
        assertFalse(TunTcpRelay.isHandshakeComplete(byteArrayOf(0x16, 0x03), 2))
        assertFalse(TunTcpRelay.isHandshakeComplete(byteArrayOf(0x16, 0x03, 0x01, 0x00), 4))

        // 2. Exact TLS ClientHello length declared (e.g. 50 bytes)
        val tls50 = ByteArray(55)
        tls50[0] = 0x16.toByte()
        tls50[1] = 0x03.toByte()
        tls50[2] = 0x03.toByte()
        tls50[3] = 0x00.toByte()
        tls50[4] = 0x32.toByte() // 50 bytes record len -> 55 total

        assertFalse("TLS with 54 bytes (1 short of 55) must NOT complete", TunTcpRelay.isHandshakeComplete(tls50, 54))
        assertTrue("TLS with exactly 55 bytes must complete", TunTcpRelay.isHandshakeComplete(tls50, 55))

        // 3. Non-standard TLS version (0x16 0x02) -> immediate passthrough
        val nonStandardTls = byteArrayOf(0x16, 0x02, 0x01)
        assertTrue("Non-standard TLS record version must passthrough immediately", TunTcpRelay.isHandshakeComplete(nonStandardTls, nonStandardTls.size))
    }

    @Test
    fun testTunTcpRelay_HandshakeComplete_BitTorrentEdgeCases() {
        val btPrefix = byteArrayOf(0x13) + "BitTorrent protocol".toByteArray(Charsets.ISO_8859_1) // 20 bytes

        // Partial prefixes
        assertFalse("Single 0x13 byte must buffer", TunTcpRelay.isHandshakeComplete(byteArrayOf(0x13), 1))
        assertFalse("5 prefix bytes must buffer", TunTcpRelay.isHandshakeComplete(btPrefix.copyOfRange(0, 5), 5))
        assertFalse("Exact 20-byte prefix must buffer until full 68B handshake", TunTcpRelay.isHandshakeComplete(btPrefix, 20))

        // Complete 68-byte handshake
        val fullBt = ByteArray(68)
        System.arraycopy(btPrefix, 0, fullBt, 0, 20)
        assertTrue("Complete 68-byte BitTorrent handshake must complete", TunTcpRelay.isHandshakeComplete(fullBt, 68))

        // Mismatched prefix starting with 0x13 -> immediate passthrough
        val mismatched = byteArrayOf(0x13, 0x41, 0x42, 0x43)
        assertTrue("Mismatched 0x13 packet must passthrough immediately", TunTcpRelay.isHandshakeComplete(mismatched, mismatched.size))
    }

    @Test
    fun testTunTcpRelay_HandshakeComplete_HttpEdgeCases() {
        val validMethods = listOf("GET ", "POST ", "HEAD ", "OPTIONS ", "PUT ", "DELETE ", "CONNECT ", "TRACE ", "PATCH ")

        for (method in validMethods) {
            val partialReq = method.toByteArray(Charsets.ISO_8859_1)
            // Partial request without header end boundary < 2048B -> buffer
            assertFalse("Incomplete HTTP request $method must buffer", TunTcpRelay.isHandshakeComplete(partialReq, partialReq.size))

            // Completed request with \r\n\r\n
            val fullReq = "$method/index.html HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            assertTrue("HTTP request $method with boundary must complete", TunTcpRelay.isHandshakeComplete(fullReq, fullReq.size))

            // Completed request with \n\n
            val fullReqLf = "$method/api HTTP/1.1\nHost: example.com\n\n".toByteArray(Charsets.ISO_8859_1)
            assertTrue("HTTP request $method with LF boundary must complete", TunTcpRelay.isHandshakeComplete(fullReqLf, fullReqLf.size))
        }

        // Partial method prefix (< 8 bytes)
        val partialMethod = "GE".toByteArray(Charsets.ISO_8859_1)
        assertFalse("Partial HTTP method 'GE' must buffer", TunTcpRelay.isHandshakeComplete(partialMethod, partialMethod.size))
    }

    @Test
    fun testTunTcpRelay_HandshakeComplete_NonDpiPassthrough() {
        // SSH
        val sshBanner = "SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.6\r\n".toByteArray(Charsets.ISO_8859_1)
        assertTrue("SSH banner must complete immediately (0ms passthrough)", TunTcpRelay.isHandshakeComplete(sshBanner, sshBanner.size))

        // WhatsApp Noise Protocol
        val noisePacket = byteArrayOf(0x00, 0x00, 0x24, 0x00, 0x05, 0x02, 0x11, 0x22)
        assertTrue("Noise protocol must complete immediately", TunTcpRelay.isHandshakeComplete(noisePacket, noisePacket.size))

        // DNS Over TCP (2-byte length prefix + DNS query)
        val dnsTcp = byteArrayOf(0x00, 0x1E, 0x12, 0x34, 0x01, 0x00)
        assertTrue("DNS over TCP must complete immediately", TunTcpRelay.isHandshakeComplete(dnsTcp, dnsTcp.size))

        // Arbitrary binary
        val rawBytes = byteArrayOf(0x80.toByte(), 0x90.toByte(), 0xFF.toByte(), 0x00)
        assertTrue("Arbitrary raw binary must complete immediately", TunTcpRelay.isHandshakeComplete(rawBytes, rawBytes.size))
    }
}
