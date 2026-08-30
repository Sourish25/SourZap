package com.sourzap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

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
        return (sum.inv() and 0xFFFF).toShort()
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
}
