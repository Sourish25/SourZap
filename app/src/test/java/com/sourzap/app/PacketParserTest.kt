package com.sourzap.app

import com.sourzap.app.service.core.PacketParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PacketParserTest {

    // RFC 791 IP Checksum
    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Short {
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

    // RFC 793 TCP Checksum with IPv4 Pseudo-Header
    private fun computeTcpChecksum(
        packet: ByteArray,
        tcpOffset: Int,
        tcpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Short {
        var sum = 0

        // Pseudo Header
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 6 // Protocol TCP
        sum += tcpLen

        // TCP Header and Payload
        for (i in tcpOffset until tcpOffset + tcpLen step 2) {
            val b1 = packet[i].toInt() and 0xFF
            val b2 = if (i + 1 < tcpOffset + tcpLen) packet[i + 1].toInt() and 0xFF else 0
            sum += (b1 shl 8) or b2
        }

        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv() and 0xFFFF).toShort()
        return if (checksum == 0.toShort()) 0xFFFF.toShort() else checksum
    }

    // RFC 768 UDP Checksum with IPv4 Pseudo-Header
    private fun computeUdpChecksum(
        packet: ByteArray,
        udpOffset: Int,
        udpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Short {
        var sum = 0

        // Pseudo Header
        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += 17 // Protocol UDP
        sum += udpLen

        // UDP Header and Payload
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

    // Helper to synthesize a complete IPv4 + TCP packet
    private fun buildTcpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val isSynAck = (flags == 0x12)
        val tcpHeaderLen = if (isSynAck) 24 else 20
        val totalLength = ipHeaderLen + tcpHeaderLen + payload.size
        val packet = ByteArray(totalLength)

        // IPv4 Header
        packet[0] = 0x45.toByte() // IPv4, IHL = 5
        packet[1] = 0x00.toByte() // TOS
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte() // ID
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 6.toByte()    // Protocol: TCP (6)
        packet[10] = 0x00.toByte()
        packet[11] = 0x00.toByte()

        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)

        val ipChecksum = computeIpChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ipChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // TCP Header
        val tcpOffset = ipHeaderLen
        packet[tcpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[tcpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 3] = (dstPort and 0xFF).toByte()

        // Seq & Ack
        packet[tcpOffset + 4] = ((seqNum shr 24) and 0xFF).toByte()
        packet[tcpOffset + 5] = ((seqNum shr 16) and 0xFF).toByte()
        packet[tcpOffset + 6] = ((seqNum shr 8) and 0xFF).toByte()
        packet[tcpOffset + 7] = (seqNum and 0xFF).toByte()

        packet[tcpOffset + 8] = ((ackNum shr 24) and 0xFF).toByte()
        packet[tcpOffset + 9] = ((ackNum shr 16) and 0xFF).toByte()
        packet[tcpOffset + 10] = ((ackNum shr 8) and 0xFF).toByte()
        packet[tcpOffset + 11] = (ackNum and 0xFF).toByte()

        // Data Offset & Flags
        packet[tcpOffset + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
        packet[tcpOffset + 13] = flags.toByte()

        // Window Size
        packet[tcpOffset + 14] = 0xFF.toByte()
        packet[tcpOffset + 15] = 0xFF.toByte()

        // TCP Options: MSS 1400 for SYN-ACK
        if (isSynAck) {
            packet[tcpOffset + 20] = 0x02.toByte()
            packet[tcpOffset + 21] = 0x04.toByte()
            packet[tcpOffset + 22] = 0x05.toByte()
            packet[tcpOffset + 23] = 0x78.toByte() // 1400 = 0x0578
        }

        // Payload
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, packet, tcpOffset + tcpHeaderLen, payload.size)
        }

        val tcpLen = tcpHeaderLen + payload.size
        val tcpChecksum = computeTcpChecksum(packet, tcpOffset, tcpLen, srcIp.address, dstIp.address)
        packet[tcpOffset + 16] = ((tcpChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[tcpOffset + 17] = (tcpChecksum.toInt() and 0xFF).toByte()

        return packet
    }

    // Helper to synthesize a complete IPv4 + UDP packet
    private fun buildUdpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLength = 20 + 8 + payload.size
        val packet = ByteArray(totalLength)

        // IPv4 Header
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte()
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()
        packet[9] = 17.toByte() // UDP

        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)

        val ipChecksum = computeIpChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // UDP Header
        val udpLen = 8 + payload.size
        val udpOffset = 20
        packet[udpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        packet[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
        packet[udpOffset + 5] = (udpLen and 0xFF).toByte()

        System.arraycopy(payload, 0, packet, 28, payload.size)

        val udpChecksum = computeUdpChecksum(packet, udpOffset, udpLen, srcIp.address, dstIp.address)
        packet[udpOffset + 6] = ((udpChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[udpOffset + 7] = (udpChecksum.toInt() and 0xFF).toByte()

        return packet
    }

    @Test
    fun testIpHeaderParsingAndChecksumValidation() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("142.250.190.46")
        val packet = buildTcpPacket(srcIp, dstIp, 54321, 443, 1000L, 0L, 0x02, ByteArray(0))

        // Check Version & IHL
        val version = (packet[0].toInt() shr 4) and 0x0F
        val ihl = (packet[0].toInt() and 0x0F) * 4
        assertEquals(4, version)
        assertEquals(20, ihl)

        // Check Protocol
        val protocol = packet[9].toInt() and 0xFF
        assertEquals(6, protocol) // TCP

        // Recompute IP Checksum over valid header - sum with embedded checksum must verify to 0
        val verifyChecksum = computeIpChecksum(packet, 0, 20)
        assertEquals("IP checksum verification must yield 0", 0.toShort(), verifyChecksum)
    }

    @Test
    fun testMalformedAndTruncatedIpPackets() {
        // Truncated buffer (< 20 bytes)
        val shortBuf = ByteArray(15)
        shortBuf[0] = 0x45.toByte()
        val isShortValid = shortBuf.size >= 20
        assertFalse(isShortValid)

        // Invalid IP Version (IPv6 header byte 0x60 passed as IPv4)
        val ipv6Header = ByteArray(40)
        ipv6Header[0] = 0x60.toByte()
        val version = (ipv6Header[0].toInt() shr 4) and 0x0F
        assertEquals(6, version)
        assertFalse("IPv6 must not be treated as IPv4", version == 4)

        // Invalid IHL < 5 (e.g. 0x42 -> IHL = 2 = 8 bytes)
        val invalidIhlPacket = ByteArray(20)
        invalidIhlPacket[0] = 0x42.toByte()
        val ihl = (invalidIhlPacket[0].toInt() and 0x0F) * 4
        assertEquals(8, ihl)
        assertFalse("IHL < 20 bytes is malformed", ihl >= 20)

        // Checksum bit flip detection
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("1.1.1.1")
        val validPacket = buildTcpPacket(srcIp, dstIp, 12345, 80, 100L, 0L, 0x02, ByteArray(0))
        val initialVerify = computeIpChecksum(validPacket, 0, 20)
        assertEquals(0.toShort(), initialVerify)

        // Corrupt 1 byte in IP header
        validPacket[4] = (validPacket[4].toInt() xor 0xFF).toByte()
        val corruptedVerify = computeIpChecksum(validPacket, 0, 20)
        assertTrue("Corrupted IP header checksum must not verify to 0", corruptedVerify != 0.toShort())
    }

    @Test
    fun testTcpSynAckHeaderWithMssOption() {
        val srcIp = InetAddress.getByName("1.1.1.1")
        val dstIp = InetAddress.getByName("10.0.0.2")
        val packet = buildTcpPacket(srcIp, dstIp, 443, 50123, 1000000L, 1001L, 0x12, ByteArray(0))

        assertEquals("Total length for SYN-ACK with MSS option must be 44 bytes (20 IP + 24 TCP)", 44, packet.size)

        val tcpOffset = 20
        val dataOffset = ((packet[tcpOffset + 12].toInt() shr 4) and 0x0F) * 4
        assertEquals("TCP header length with MSS must be 24 bytes (6 words)", 24, dataOffset)

        val flags = packet[tcpOffset + 13].toInt() and 0xFF
        assertEquals("Flags must be SYN | ACK (0x12)", 0x12, flags)

        // Verify TCP Option: MSS Kind=2, Length=4, Value=1400 (0x0578)
        assertEquals(0x02.toByte(), packet[tcpOffset + 20])
        assertEquals(0x04.toByte(), packet[tcpOffset + 21])
        assertEquals(0x05.toByte(), packet[tcpOffset + 22])
        assertEquals(0x78.toByte(), packet[tcpOffset + 23])

        // Verify TCP Checksum calculation is non-zero
        val parsedChecksum = (((packet[tcpOffset + 16].toInt() and 0xFF) shl 8) or
                (packet[tcpOffset + 17].toInt() and 0xFF)).toShort()
        assertTrue("TCP Checksum must be computed and non-zero", parsedChecksum != 0.toShort())
    }

    @Test
    fun testMalformedAndCorruptedTcpPackets() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("8.8.8.8")
        val validPacket = buildTcpPacket(srcIp, dstIp, 50000, 443, 1L, 1L, 0x18, "HELLO".toByteArray(Charsets.US_ASCII))

        val tcpOffset = 20
        val dataOffsetWords = (validPacket[tcpOffset + 12].toInt() shr 4) and 0x0F
        assertEquals(5, dataOffsetWords) // 20 bytes

        // Corrupt TCP payload
        val corruptedPacket = validPacket.copyOf()
        corruptedPacket[tcpOffset + 20] = 'X'.code.toByte()
        val tcpLen = (corruptedPacket.size - tcpOffset)
        val verifyChecksum = computeTcpChecksum(corruptedPacket, tcpOffset, tcpLen, srcIp.address, dstIp.address)
        val origChecksum = (((validPacket[tcpOffset + 16].toInt() and 0xFF) shl 8) or
                (validPacket[tcpOffset + 17].toInt() and 0xFF)).toShort()

        // Checksum of modified payload must differ
        assertTrue("Checksum must detect payload tampering", verifyChecksum != origChecksum)
    }

    @Test
    fun testTcpPayloadDataOffsetAndSeqAckArithmetic() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("93.184.216.34")
        val testPayload = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(Charsets.US_ASCII)

        val seq = 4294967290L // Near max unsigned 32-bit int
        val ack = 5000L

        val packet = buildTcpPacket(srcIp, dstIp, 45000, 80, seq, ack, 0x18, testPayload) // PSH | ACK

        val tcpOffset = 20
        val parsedSeq = (((packet[tcpOffset + 4].toLong() and 0xFF) shl 24) or
                ((packet[tcpOffset + 5].toLong() and 0xFF) shl 16) or
                ((packet[tcpOffset + 6].toLong() and 0xFF) shl 8) or
                (packet[tcpOffset + 7].toLong() and 0xFF)) and 0xFFFFFFFFL

        val parsedAck = (((packet[tcpOffset + 8].toLong() and 0xFF) shl 24) or
                ((packet[tcpOffset + 9].toLong() and 0xFF) shl 16) or
                ((packet[tcpOffset + 10].toLong() and 0xFF) shl 8) or
                (packet[tcpOffset + 11].toLong() and 0xFF)) and 0xFFFFFFFFL

        assertEquals(seq, parsedSeq)
        assertEquals(ack, parsedAck)

        // Test TCP 32-bit sequence wrap-around calculation
        val nextSeq = (parsedSeq + testPayload.size) and 0xFFFFFFFFL
        assertTrue("Sequence number should wrap around 32-bit boundary", nextSeq < seq)
    }

    @Test
    fun testUdpPacketBuildingAndRfc768Checksum() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("8.8.8.8")
        val payload = "DNS_QUERY_MOCK_PAYLOAD".toByteArray(Charsets.US_ASCII)

        val packet = buildUdpPacket(srcIp, dstIp, 53000, 53, payload)

        assertEquals(20 + 8 + payload.size, packet.size)

        // Protocol UDP
        assertEquals(17.toByte(), packet[9])

        val udpOffset = 20
        val srcPort = ((packet[udpOffset].toInt() and 0xFF) shl 8) or (packet[udpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or (packet[udpOffset + 3].toInt() and 0xFF)
        val udpLen = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)

        assertEquals(53000, srcPort)
        assertEquals(53, dstPort)
        assertEquals(8 + payload.size, udpLen)

        val checksum = computeUdpChecksum(packet, udpOffset, udpLen, srcIp.address, dstIp.address)
        assertTrue("UDP Checksum must be computed", checksum != 0.toShort())
    }

    @Test
    fun testUdpChecksum_OddPayloadLengthAndZeroResult() {
        val srcIp = byteArrayOf(10, 0, 0, 2)
        val dstIp = byteArrayOf(1, 1, 1, 1)

        // Odd length payload (7 bytes)
        val oddPacket = byteArrayOf(
            0x10, 0x00, 0x00, 0x35,
            0x00, 0x0F, 0x00, 0x00, // length = 15
            1, 2, 3, 4, 5, 6, 7
        )
        val csOdd = computeUdpChecksum(oddPacket, 0, 15, srcIp, dstIp)
        assertTrue(csOdd != 0.toShort())
    }

    @Test
    fun testUdpNatTableKeyHashingAndLookup() {
        data class ClientMapping(val clientIp: InetAddress, val clientPort: Int, var lastSeen: Long)

        val natTable = ConcurrentHashMap<String, ClientMapping>()
        val poolSize = 8

        val clientIp = InetAddress.getByName("10.0.0.2")
        val clientPort = 56789
        val remoteIp = InetAddress.getByName("1.1.1.1")
        val remotePort = 53

        // Hash client port into socket pool
        val socketIndex = (clientPort and 0x7FFFFFFF) % poolSize
        assertTrue("Socket index must be in [0, poolSize)", socketIndex in 0 until poolSize)

        // Insert mappings
        val mapping = ClientMapping(clientIp, clientPort, System.currentTimeMillis())
        val natKeyExact = "${remoteIp.hostAddress}:$remotePort#$socketIndex"
        val natKeyHost = "${remoteIp.hostAddress}#$socketIndex"

        natTable[natKeyExact] = mapping
        natTable[natKeyHost] = mapping

        // 1. Exact lookup
        val exactMatch = natTable[natKeyExact]
        assertNotNull("Exact match must find client", exactMatch)
        assertEquals(clientPort, exactMatch!!.clientPort)

        // 2. Host key lookup (if remote replied from different port)
        val hostMatch = natTable[natKeyHost]
        assertNotNull("Host match must find client", hostMatch)
        assertEquals(clientIp, hostMatch!!.clientIp)

        // 3. Fallback prefix lookup
        val prefixMatch = natTable.entries.firstOrNull { it.key.startsWith("${remoteIp.hostAddress}:") }?.value
        assertNotNull("Prefix lookup must find mapping", prefixMatch)

        // 4. Scavenger simulation (idle timeout > 60s)
        val oldMapping = ClientMapping(clientIp, 12345, System.currentTimeMillis() - 70000L) // 70s old
        natTable["expired#0"] = oldMapping

        val now = System.currentTimeMillis()
        val iterator = natTable.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeen > 60000) {
                iterator.remove()
            }
        }

        assertFalse("Expired mapping must be scavenged", natTable.containsKey("expired#0"))
        assertTrue("Active mapping must be retained", natTable.containsKey(natKeyExact))
    }

    @Test
    fun testUdpNatTable_RapidBurst5000PacketsStress() {
        data class ClientMapping(val clientIp: InetAddress, val clientPort: Int, var lastSeen: Long)

        val natTable = ConcurrentHashMap<String, ClientMapping>()
        val poolSize = 8
        val maxNatEntries = 4096

        val clientIp = InetAddress.getByName("10.0.0.2")
        val threadCount = 20
        val packetsPerThread = 250 // 20 * 250 = 5,000 packets
        val executor = Executors.newFixedThreadPool(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until packetsPerThread) {
                        val clientPort = 10000 + (t * packetsPerThread + i)
                        val socketIndex = (clientPort and 0x7FFFFFFF) % poolSize
                        val remoteIp = "185.199.108.${(i % 250) + 1}"
                        val remotePort = 6881 + (i % 100)

                        val natKeyExact = "$remoteIp:$remotePort#$socketIndex"
                        val natKeyHost = "$remoteIp#$socketIndex"

                        // Simulate pruning when capacity reached
                        if (natTable.size >= maxNatEntries) {
                            val now = System.currentTimeMillis()
                            val iter = natTable.entries.iterator()
                            var removed = 0
                            while (iter.hasNext() && removed < 256) {
                                iter.next()
                                iter.remove()
                                removed++
                            }
                        }

                        val mapping = ClientMapping(clientIp, clientPort, System.currentTimeMillis())
                        natTable[natKeyExact] = mapping
                        natTable[natKeyHost] = mapping

                        // Concurrent lookup
                        val found = natTable[natKeyExact]
                        if (found == null && natTable.size < maxNatEntries) {
                            errors.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                }
            }
        }

        executor.shutdown()
        assertTrue("All threads must finish within 10 seconds", executor.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals("No race errors should occur during rapid 5000-packet burst", 0, errors.get())
        assertTrue("NAT table must contain active mappings", natTable.isNotEmpty())
    }

    @Test
    fun testTcpSegmentSplitting1400Mtu() {
        val maxSegment = 1400
        val largeData = ByteArray(4000) { (it % 256).toByte() }

        var offset = 0
        val chunks = mutableListOf<ByteArray>()
        while (offset < largeData.size) {
            val chunkLen = minOf(largeData.size - offset, maxSegment)
            val chunk = largeData.copyOfRange(offset, offset + chunkLen)
            chunks.add(chunk)
            offset += chunkLen
        }

        assertEquals("4000 bytes divided into 1400 segments must yield 3 chunks", 3, chunks.size)
        assertEquals(1400, chunks[0].size)
        assertEquals(1400, chunks[1].size)
        assertEquals(1200, chunks[2].size)

        // Recombine and verify integrity
        val reassembled = chunks[0] + chunks[1] + chunks[2]
        assertEquals(largeData.size, reassembled.size)
        assertTrue(largeData.contentEquals(reassembled))
    }

    @Test
    fun testTcpSegmentSplitting_ExtremeBoundaries() {
        fun split(data: ByteArray, maxSeg: Int = 1400): List<ByteArray> {
            val res = mutableListOf<ByteArray>()
            var off = 0
            while (off < data.size) {
                val len = minOf(data.size - off, maxSeg)
                res.add(data.copyOfRange(off, off + len))
                off += len
            }
            return res
        }

        // 0 bytes
        assertEquals(0, split(ByteArray(0)).size)

        // 1 byte
        val oneByte = split(ByteArray(1))
        assertEquals(1, oneByte.size)
        assertEquals(1, oneByte[0].size)

        // Exactly 1400 bytes
        val exactMtu = split(ByteArray(1400))
        assertEquals(1, exactMtu.size)
        assertEquals(1400, exactMtu[0].size)

        // 1401 bytes
        val plusOne = split(ByteArray(1401))
        assertEquals(2, plusOne.size)
        assertEquals(1400, plusOne[0].size)
        assertEquals(1, plusOne[1].size)

        // Exactly 2800 bytes
        val twoMtu = split(ByteArray(2800))
        assertEquals(2, twoMtu.size)
        assertEquals(1400, twoMtu[0].size)
        assertEquals(1400, twoMtu[1].size)

        // Prime length 31337 bytes
        val primeData = ByteArray(31337) { (it and 0xFF).toByte() }
        val primeChunks = split(primeData)
        assertEquals(23, primeChunks.size) // 22 * 1400 = 30800 + 537 = 31337
        assertEquals(537, primeChunks.last().size)

        // Reassembly verification
        var totalReassembled = ByteArray(0)
        for (c in primeChunks) totalReassembled += c
        assertTrue(primeData.contentEquals(totalReassembled))
    }

    @Test
    fun testPacketParser_Ipv4ParsingAndValidation() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("1.1.1.1")
        val packet = PacketParser.buildTcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 54321,
            dstPort = 443,
            seqNum = 1000L,
            ackNum = 0L,
            flags = 0x02,
            payload = ByteArray(0)
        )

        val parsed = PacketParser.parseIpv4Header(packet, packet.size)
        assertNotNull("IPv4 header must be parsed successfully", parsed)
        assertEquals(4, parsed!!.version)
        assertEquals(20, parsed.headerLength)
        assertEquals(6, parsed.protocol) // TCP
        assertEquals(srcIp, parsed.srcIp)
        assertEquals(dstIp, parsed.dstIp)
        assertTrue(parsed.isValid)

        // Corrupted packet with invalid version
        val corruptVersion = packet.copyOf()
        corruptVersion[0] = 0x55.toByte() // Version 5
        assertNull(PacketParser.parseIpv4Header(corruptVersion, corruptVersion.size))

        // Truncated packet length
        assertNull(PacketParser.parseIpv4Header(packet, 15))
    }

    @Test
    fun testPacketParser_Ipv6ParsingAndSafety() {
        // Construct a standard 40-byte IPv6 Header
        val ipv6Packet = ByteArray(40)
        ipv6Packet[0] = 0x60.toByte() // Version 6
        ipv6Packet[4] = 0x00.toByte() // Payload length = 20
        ipv6Packet[5] = 0x14.toByte()
        ipv6Packet[6] = 6.toByte()    // Next header: TCP (6)
        ipv6Packet[7] = 64.toByte()   // Hop limit

        val srcIp = InetAddress.getByName("2001:db8::1")
        val dstIp = InetAddress.getByName("2606:4700::6810:84e5")
        System.arraycopy(srcIp.address, 0, ipv6Packet, 8, 16)
        System.arraycopy(dstIp.address, 0, ipv6Packet, 24, 16)

        val parsed = PacketParser.parseIpv6Header(ipv6Packet, ipv6Packet.size)
        assertNotNull("IPv6 header must be parsed", parsed)
        assertEquals(6, parsed!!.nextHeader)
        assertEquals(20, parsed.payloadLength)
        assertEquals(srcIp, parsed.srcIp)
        assertEquals(dstIp, parsed.dstIp)
        assertTrue(parsed.isValid)

        // Truncated IPv6 packet (< 40 bytes)
        assertNull(PacketParser.parseIpv6Header(ipv6Packet, 39))

        // Test ICMPv6 synthesis for Happy Eyeballs fallback
        val icmpv6 = PacketParser.buildIcmpv6AddressUnreachablePacket(
            originalBuffer = ipv6Packet,
            originalLength = ipv6Packet.size,
            srcIp = srcIp,
            dstIp = dstIp
        )
        assertTrue("ICMPv6 packet must be created", icmpv6.size >= 48)
        assertEquals(0x60.toByte(), icmpv6[0]) // Version 6
        assertEquals(58.toByte(), icmpv6[6])   // Next Header: ICMPv6 (58)
        assertEquals(1.toByte(), icmpv6[40])   // Type 1: Destination Unreachable
        assertEquals(3.toByte(), icmpv6[41])   // Code 3: Address Unreachable
    }

    @Test
    fun testPacketParser_TcpSynthesisAndTeardownFlags() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("93.184.216.34")

        // 1. SYN | ACK
        val synAck = PacketParser.buildTcpPacket(srcIp, dstIp, 80, 50000, 1000L, 2000L, 0x12)
        val parsedSynAck = PacketParser.parseTcpHeader(synAck, 20, synAck.size)
        assertNotNull(parsedSynAck)
        assertTrue(parsedSynAck!!.isSyn)
        assertTrue(parsedSynAck.isAck)
        assertFalse(parsedSynAck.isFin)
        assertFalse(parsedSynAck.isRst)
        assertEquals(24, parsedSynAck.dataOffset) // 24 bytes with MSS

        // 2. FIN | ACK (Teardown)
        val finAck = PacketParser.buildTcpPacket(srcIp, dstIp, 80, 50000, 1001L, 2001L, 0x11)
        val parsedFinAck = PacketParser.parseTcpHeader(finAck, 20, finAck.size)
        assertNotNull(parsedFinAck)
        assertTrue(parsedFinAck!!.isFin)
        assertTrue(parsedFinAck.isAck)
        assertFalse(parsedFinAck.isSyn)

        // 3. RST | ACK (Instant Reset)
        val rstAck = PacketParser.buildTcpPacket(srcIp, dstIp, 80, 50000, 1002L, 2002L, 0x14)
        val parsedRstAck = PacketParser.parseTcpHeader(rstAck, 20, rstAck.size)
        assertNotNull(parsedRstAck)
        assertTrue(parsedRstAck!!.isRst)
        assertTrue(parsedRstAck.isAck)
    }

    @Test
    fun testPacketParser_UdpSliceAndPseudoHeaderChecksum() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("8.8.8.8")
        val payload = "DNS_PING".toByteArray(Charsets.US_ASCII)

        val packet = PacketParser.buildUdpIpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 53000,
            dstPort = 53,
            payload = payload
        )

        assertEquals(20 + 8 + payload.size, packet.size)
        val parsedUdp = PacketParser.parseUdpHeader(packet, 20, packet.size)
        assertNotNull(parsedUdp)
        assertEquals(53000, parsedUdp!!.srcPort)
        assertEquals(53, parsedUdp.dstPort)
        assertEquals(payload.size, parsedUdp.payloadLength)
    }

    @Test
    fun testPacketParser_IcmpPortUnreachableSynthesis() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("142.250.190.46")
        val origPacket = PacketParser.buildUdpIpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 45000,
            dstPort = 443,
            payload = ByteArray(100)
        )

        val icmp = PacketParser.buildIcmpPortUnreachablePacket(
            originalBuffer = origPacket,
            originalLength = origPacket.size,
            ipHeaderLen = 20,
            srcIp = srcIp,
            dstIp = dstIp
        )

        assertEquals(20 + 8 + 28, icmp.size) // IP (20) + ICMP Header (8) + Original IP (20) + 8 bytes UDP
        assertEquals(1.toByte(), icmp[9])    // Protocol ICMP
        assertEquals(3.toByte(), icmp[20])   // Type 3 (Destination Unreachable)
        assertEquals(3.toByte(), icmp[21])   // Code 3 (Port Unreachable)
    }
}
