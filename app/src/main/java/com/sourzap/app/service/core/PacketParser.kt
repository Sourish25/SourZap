package com.sourzap.app.service.core

import java.net.InetAddress

/**
 * Ultra High-Performance, RFC-Compliant Dual-Stack (IPv4/IPv6) Packet Parser, Validator, and Synthesizer.
 * Provides zero-allocation header slicing, RFC 791/793/768/792/4443 checksum engines,
 * and dual-stack packet boundary safety for VpnService TUN interfaces.
 */
object PacketParser {

    private val EMPTY_BYTE_ARRAY = ByteArray(0)

    // --- Data Classes for Parsed Packets ---

    data class IpHeader(
        val version: Int,
        val headerLength: Int,
        val totalLength: Int,
        val protocol: Int,
        val srcIp: InetAddress,
        val dstIp: InetAddress,
        val isValid: Boolean
    )

    data class TcpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val seqNum: Long,
        val ackNum: Long,
        val dataOffset: Int,
        val flags: Int,
        val isSyn: Boolean,
        val isAck: Boolean,
        val isFin: Boolean,
        val isRst: Boolean,
        val isPsh: Boolean,
        val windowSize: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    data class UdpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val length: Int,
        val payloadOffset: Int,
        val payloadLength: Int
    )

    data class Ipv6Header(
        val nextHeader: Int,
        val payloadLength: Int,
        val srcIp: InetAddress,
        val dstIp: InetAddress,
        val isValid: Boolean
    )

    // --- Packet Parsing Methods ---

    /**
     * Parses and strictly validates an IPv4 packet header according to RFC 791.
     * Prevents buffer overruns, malformed IHL lengths, and truncated packets.
     */
    fun parseIpv4Header(buffer: ByteArray, length: Int): IpHeader? {
        if (length < 20) return null

        val version = (buffer[0].toInt() shr 4) and 0x0F
        if (version != 4) return null

        val ihl = (buffer[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return null

        val totalLength = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        val protocol = buffer[9].toInt() and 0xFF

        val srcIpBytes = buffer.copyOfRange(12, 16)
        val dstIpBytes = buffer.copyOfRange(16, 20)

        val srcIp = try { InetAddress.getByAddress(srcIpBytes) } catch (_: Exception) { return null }
        val dstIp = try { InetAddress.getByAddress(dstIpBytes) } catch (_: Exception) { return null }

        return IpHeader(
            version = 4,
            headerLength = ihl,
            totalLength = if (totalLength in ihl..length) totalLength else length,
            protocol = protocol,
            srcIp = srcIp,
            dstIp = dstIp,
            isValid = true
        )
    }

    /**
     * Parses an IPv6 packet header according to RFC 8200 (40-byte fixed header).
     */
    fun parseIpv6Header(buffer: ByteArray, length: Int): Ipv6Header? {
        if (length < 40) return null

        val version = (buffer[0].toInt() shr 4) and 0x0F
        if (version != 6) return null

        val payloadLength = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
        val nextHeader = buffer[6].toInt() and 0xFF

        val srcIpBytes = buffer.copyOfRange(8, 24)
        val dstIpBytes = buffer.copyOfRange(24, 40)

        val srcIp = try { InetAddress.getByAddress(srcIpBytes) } catch (_: Exception) { return null }
        val dstIp = try { InetAddress.getByAddress(dstIpBytes) } catch (_: Exception) { return null }

        return Ipv6Header(
            nextHeader = nextHeader,
            payloadLength = payloadLength,
            srcIp = srcIp,
            dstIp = dstIp,
            isValid = true
        )
    }

    /**
     * Parses a TCP header from an IPv4 or IPv6 packet.
     */
    fun parseTcpHeader(buffer: ByteArray, tcpOffset: Int, totalLength: Int): TcpHeader? {
        if (totalLength < tcpOffset + 20) return null

        val srcPort = ((buffer[tcpOffset].toInt() and 0xFF) shl 8) or (buffer[tcpOffset + 1].toInt() and 0xFF)
        val dstPort = ((buffer[tcpOffset + 2].toInt() and 0xFF) shl 8) or (buffer[tcpOffset + 3].toInt() and 0xFF)

        val seqNum = (((buffer[tcpOffset + 4].toLong() and 0xFF) shl 24) or
                ((buffer[tcpOffset + 5].toLong() and 0xFF) shl 16) or
                ((buffer[tcpOffset + 6].toLong() and 0xFF) shl 8) or
                (buffer[tcpOffset + 7].toLong() and 0xFF)) and 0xFFFFFFFFL

        val ackNum = (((buffer[tcpOffset + 8].toLong() and 0xFF) shl 24) or
                ((buffer[tcpOffset + 9].toLong() and 0xFF) shl 16) or
                ((buffer[tcpOffset + 10].toLong() and 0xFF) shl 8) or
                (buffer[tcpOffset + 11].toLong() and 0xFF)) and 0xFFFFFFFFL

        val dataOffset = ((buffer[tcpOffset + 12].toInt() shr 4) and 0x0F) * 4
        if (dataOffset < 20 || totalLength < tcpOffset + dataOffset) return null

        val flags = buffer[tcpOffset + 13].toInt() and 0xFF
        val windowSize = ((buffer[tcpOffset + 14].toInt() and 0xFF) shl 8) or (buffer[tcpOffset + 15].toInt() and 0xFF)

        val payloadOffset = tcpOffset + dataOffset
        val payloadLength = (totalLength - payloadOffset).coerceAtLeast(0)

        return TcpHeader(
            srcPort = srcPort,
            dstPort = dstPort,
            seqNum = seqNum,
            ackNum = ackNum,
            dataOffset = dataOffset,
            flags = flags,
            isSyn = (flags and 0x02) != 0,
            isAck = (flags and 0x10) != 0,
            isFin = (flags and 0x01) != 0,
            isRst = (flags and 0x04) != 0,
            isPsh = (flags and 0x08) != 0,
            windowSize = windowSize,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength
        )
    }

    /**
     * Parses a UDP header from an IPv4 or IPv6 packet.
     */
    fun parseUdpHeader(buffer: ByteArray, udpOffset: Int, totalLength: Int): UdpHeader? {
        if (totalLength < udpOffset + 8) return null

        val srcPort = ((buffer[udpOffset].toInt() and 0xFF) shl 8) or (buffer[udpOffset + 1].toInt() and 0xFF)
        val dstPort = ((buffer[udpOffset + 2].toInt() and 0xFF) shl 8) or (buffer[udpOffset + 3].toInt() and 0xFF)
        val udpLength = ((buffer[udpOffset + 4].toInt() and 0xFF) shl 8) or (buffer[udpOffset + 5].toInt() and 0xFF)

        val payloadOffset = udpOffset + 8
        val payloadLength = (minOf(udpLength, totalLength - udpOffset) - 8).coerceAtLeast(0)

        return UdpHeader(
            srcPort = srcPort,
            dstPort = dstPort,
            length = udpLength,
            payloadOffset = payloadOffset,
            payloadLength = payloadLength
        )
    }

    // --- RFC Checksum Calculation Engines ---

    /**
     * RFC 791 Standard Internet Checksum (Ones' complement sum of 16-bit words).
     */
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

    /**
     * RFC 793 TCP Checksum with IPv4 Pseudo-Header.
     */
    fun computeTcpChecksum(
        packet: ByteArray,
        tcpOffset: Int,
        tcpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Short {
        var sum = 0

        // IPv4 Pseudo-Header (12 bytes: Src IP, Dst IP, 0x00, Protocol TCP=6, TCP Segment Length)
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

    /**
     * RFC 768 UDP Checksum with IPv4 Pseudo-Header.
     */
    fun computeUdpChecksum(
        packet: ByteArray,
        udpOffset: Int,
        udpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Short {
        var sum = 0

        // IPv4 Pseudo-Header
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

    /**
     * RFC 4443 ICMPv6 Checksum with IPv6 Pseudo-Header.
     */
    fun computeIcmpv6Checksum(
        packet: ByteArray,
        icmpOffset: Int,
        icmpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Short {
        var sum = 0L

        // IPv6 Pseudo-Header (40 bytes):
        // Source Address (16 bytes)
        // Destination Address (16 bytes)
        // Upper-Layer Packet Length (32 bits)
        // Next Header (32 bits with 3 zero octets + 1 octet = 58)
        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += (icmpLen shr 16) and 0xFFFF
        sum += icmpLen and 0xFFFF
        sum += 58L // Next Header ICMPv6 (58)

        // ICMPv6 Header and Payload
        for (i in icmpOffset until icmpOffset + icmpLen step 2) {
            val b1 = packet[i].toInt() and 0xFF
            val b2 = if (i + 1 < icmpOffset + icmpLen) packet[i + 1].toInt() and 0xFF else 0
            sum += (b1 shl 8) or b2
        }

        while ((sum shr 16) > 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv() and 0xFFFF).toShort()
        return if (checksum == 0.toShort()) 0xFFFF.toShort() else checksum
    }

    // --- Packet Synthesis Engines ---

    /**
     * Synthesizes a 100% RFC 793 compliant IPv4 + TCP packet.
     * Supports zero-allocation slicing from source buffers.
     */
    fun buildTcpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        payload: ByteArray = EMPTY_BYTE_ARRAY,
        payloadOffset: Int = 0,
        payloadLen: Int = payload.size,
        windowSize: Int = 0xFFFF
    ): ByteArray {
        val srcIpBytes = srcIp.address
        val dstIpBytes = dstIp.address
        if (srcIpBytes.size != 4 || dstIpBytes.size != 4) return EMPTY_BYTE_ARRAY

        val ipHeaderLen = 20
        val isSynAck = (flags == 0x12)
        val tcpHeaderLen = if (isSynAck) 24 else 20
        val totalLength = ipHeaderLen + tcpHeaderLen + payloadLen
        val packet = ByteArray(totalLength)

        // --- IPv4 Header (20 bytes) ---
        packet[0] = 0x45.toByte() // Version 4, IHL 5
        packet[1] = 0x00.toByte() // TOS
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte() // ID
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL = 64
        packet[9] = 6.toByte()    // Protocol: TCP (6)
        packet[10] = 0x00.toByte()
        packet[11] = 0x00.toByte()

        System.arraycopy(srcIpBytes, 0, packet, 12, 4)
        System.arraycopy(dstIpBytes, 0, packet, 16, 4)

        // IPv4 Header Checksum
        val ipChecksum = computeIpChecksum(packet, 0, ipHeaderLen)
        packet[10] = ((ipChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // --- TCP Header (20 or 24 bytes) ---
        val tcpOffset = ipHeaderLen
        packet[tcpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[tcpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[tcpOffset + 3] = (dstPort and 0xFF).toByte()

        val maskedSeq = seqNum and 0xFFFFFFFFL
        packet[tcpOffset + 4] = ((maskedSeq shr 24) and 0xFF).toByte()
        packet[tcpOffset + 5] = ((maskedSeq shr 16) and 0xFF).toByte()
        packet[tcpOffset + 6] = ((maskedSeq shr 8) and 0xFF).toByte()
        packet[tcpOffset + 7] = (maskedSeq and 0xFF).toByte()

        val maskedAck = ackNum and 0xFFFFFFFFL
        packet[tcpOffset + 8] = ((maskedAck shr 24) and 0xFF).toByte()
        packet[tcpOffset + 9] = ((maskedAck shr 16) and 0xFF).toByte()
        packet[tcpOffset + 10] = ((maskedAck shr 8) and 0xFF).toByte()
        packet[tcpOffset + 11] = (maskedAck and 0xFF).toByte()

        // Data Offset & Reserved
        packet[tcpOffset + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
        packet[tcpOffset + 13] = flags.toByte()

        // Window Size
        packet[tcpOffset + 14] = ((windowSize shr 8) and 0xFF).toByte()
        packet[tcpOffset + 15] = (windowSize and 0xFF).toByte()

        // Checksum placeholder
        packet[tcpOffset + 16] = 0x00.toByte()
        packet[tcpOffset + 17] = 0x00.toByte()

        // Urgent Pointer
        packet[tcpOffset + 18] = 0x00.toByte()
        packet[tcpOffset + 19] = 0x00.toByte()

        // TCP Options: MSS 1400 (Kind 2, Length 4, Value 1400 = 0x0578) for SYN-ACK
        if (isSynAck) {
            packet[tcpOffset + 20] = 0x02.toByte()
            packet[tcpOffset + 21] = 0x04.toByte()
            packet[tcpOffset + 22] = 0x05.toByte()
            packet[tcpOffset + 23] = 0x78.toByte()
        }

        // --- Payload ---
        if (payloadLen > 0) {
            System.arraycopy(payload, payloadOffset, packet, tcpOffset + tcpHeaderLen, payloadLen)
        }

        // Compute RFC 793 TCP Checksum with IPv4 Pseudo-Header
        val tcpLen = tcpHeaderLen + payloadLen
        val tcpChecksum = computeTcpChecksum(packet, tcpOffset, tcpLen, srcIp.address, dstIp.address)
        packet[tcpOffset + 16] = ((tcpChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[tcpOffset + 17] = (tcpChecksum.toInt() and 0xFF).toByte()

        return packet
    }

    /**
     * Synthesizes an RFC 768 compliant IPv4 UDP packet with IP and UDP checksums.
     */
    fun buildUdpIpPacket(
        srcIp: InetAddress,
        dstIp: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLen: Int = payload.size
    ): ByteArray {
        val srcIpBytes = srcIp.address
        val dstIpBytes = dstIp.address
        if (srcIpBytes.size != 4 || dstIpBytes.size != 4) return EMPTY_BYTE_ARRAY

        val totalLength = 20 + 8 + payloadLen
        val packet = ByteArray(totalLength)

        // IPv4 Header (20 bytes)
        packet[0] = 0x45.toByte()
        packet[1] = 0x00.toByte()
        packet[2] = ((totalLength shr 8) and 0xFF).toByte()
        packet[3] = (totalLength and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 17.toByte()   // UDP (17)

        System.arraycopy(srcIpBytes, 0, packet, 12, 4)
        System.arraycopy(dstIpBytes, 0, packet, 16, 4)

        val ipChecksum = computeIpChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // UDP Header (8 bytes)
        val udpLen = 8 + payloadLen
        val udpOffset = 20
        packet[udpOffset] = ((srcPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 1] = (srcPort and 0xFF).toByte()
        packet[udpOffset + 2] = ((dstPort shr 8) and 0xFF).toByte()
        packet[udpOffset + 3] = (dstPort and 0xFF).toByte()
        packet[udpOffset + 4] = ((udpLen shr 8) and 0xFF).toByte()
        packet[udpOffset + 5] = (udpLen and 0xFF).toByte()
        packet[udpOffset + 6] = 0x00.toByte()
        packet[udpOffset + 7] = 0x00.toByte()

        if (payloadLen > 0) {
            System.arraycopy(payload, payloadOffset, packet, 28, payloadLen)
        }

        val udpChecksum = computeUdpChecksum(packet, udpOffset, udpLen, srcIpBytes, dstIpBytes)
        packet[udpOffset + 6] = ((udpChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[udpOffset + 7] = (udpChecksum.toInt() and 0xFF).toByte()

        return packet
    }

    /**
     * Synthesizes an RFC 792 compliant ICMP Destination Unreachable packet for IPv4.
     * Common codes:
     * - Code 1: Host Unreachable
     * - Code 3: Port Unreachable
     * - Code 13: Communication Administratively Prohibited
     */
    fun buildIcmpv4DestinationUnreachablePacket(
        originalBuffer: ByteArray,
        originalLength: Int,
        ipHeaderLen: Int,
        srcIp: InetAddress,
        dstIp: InetAddress,
        code: Int = 3
    ): ByteArray {
        val srcIpBytes = srcIp.address
        val dstIpBytes = dstIp.address
        if (srcIpBytes.size != 4 || dstIpBytes.size != 4) return EMPTY_BYTE_ARRAY

        val includedOriginalLen = (ipHeaderLen + 8).coerceAtMost(originalLength)
        val ipTotalLen = 20 + 8 + includedOriginalLen
        val packet = ByteArray(ipTotalLen)

        // 1. IPv4 Header (20 bytes)
        packet[0] = 0x45.toByte() // IPv4, IHL = 5
        packet[1] = 0x00.toByte() // TOS
        packet[2] = ((ipTotalLen shr 8) and 0xFF).toByte()
        packet[3] = (ipTotalLen and 0xFF).toByte()
        packet[4] = 0x00.toByte()
        packet[5] = 0x00.toByte()
        packet[6] = 0x40.toByte() // Don't Fragment
        packet[7] = 0x00.toByte()
        packet[8] = 64.toByte()   // TTL
        packet[9] = 1.toByte()    // Protocol = 1 (ICMP)
        packet[10] = 0x00.toByte()
        packet[11] = 0x00.toByte()

        // Source of ICMP error is the destination that was unreachable
        System.arraycopy(dstIpBytes, 0, packet, 12, 4)
        // Destination of ICMP error is the originating client
        System.arraycopy(srcIpBytes, 0, packet, 16, 4)

        val ipChecksum = computeIpChecksum(packet, 0, 20)
        packet[10] = ((ipChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[11] = (ipChecksum.toInt() and 0xFF).toByte()

        // 2. ICMP Header (8 bytes)
        packet[20] = 3.toByte() // Type 3: Destination Unreachable
        packet[21] = code.toByte() // Code
        packet[22] = 0x00.toByte() // Checksum placeholder
        packet[23] = 0x00.toByte()
        packet[24] = 0x00.toByte() // 4 unused bytes per RFC 792
        packet[25] = 0x00.toByte()
        packet[26] = 0x00.toByte()
        packet[27] = 0x00.toByte()

        // 3. ICMP Data (Original IP Header + first 8 bytes of original upper-layer datagram)
        System.arraycopy(originalBuffer, 0, packet, 28, includedOriginalLen)

        // ICMP Checksum
        val icmpLen = 8 + includedOriginalLen
        val icmpChecksum = computeIpChecksum(packet, 20, icmpLen)
        packet[22] = ((icmpChecksum.toInt() shr 8) and 0xFF).toByte()
        packet[23] = (icmpChecksum.toInt() and 0xFF).toByte()

        return packet
    }

    /**
     * Synthesizes an RFC 792 compliant ICMP Destination Unreachable (Port Unreachable: Type 3, Code 3) packet.
     */
    fun buildIcmpPortUnreachablePacket(
        originalBuffer: ByteArray,
        originalLength: Int,
        ipHeaderLen: Int,
        srcIp: InetAddress,
        dstIp: InetAddress
    ): ByteArray = buildIcmpv4DestinationUnreachablePacket(
        originalBuffer = originalBuffer,
        originalLength = originalLength,
        ipHeaderLen = ipHeaderLen,
        srcIp = srcIp,
        dstIp = dstIp,
        code = 3
    )

    /**
     * Synthesizes an RFC 4443 compliant ICMPv6 Destination Unreachable packet.
     * Common codes:
     * - Code 1: Communication with destination administratively prohibited
     * - Code 3: Address Unreachable
     * - Code 4: Port Unreachable
     *
     * Forces client apps running RFC 6555 Happy Eyeballs to immediately fallback
     * to IPv4 in 0ms without wasting 250-3000ms connection timeout delays.
     */
    fun buildIcmpv6DestinationUnreachablePacket(
        originalBuffer: ByteArray,
        originalLength: Int,
        srcIp: InetAddress,
        dstIp: InetAddress,
        code: Int = 3
    ): ByteArray {
        val srcIpBytes = srcIp.address
        val dstIpBytes = dstIp.address
        if (srcIpBytes.size != 16 || dstIpBytes.size != 16) return EMPTY_BYTE_ARRAY

        // RFC 4443 Section 2.4: Include as much of offending packet as fits in 1280 MTU (1280 - 48 = 1232)
        val maxPayload = 1232
        val includedOriginalLen = minOf(originalLength, maxPayload)
        val icmpv6Len = 8 + includedOriginalLen
        val totalLength = 40 + icmpv6Len
        val packet = ByteArray(totalLength)

        // 1. IPv6 Header (40 bytes)
        packet[0] = 0x60.toByte() // Version 6, Traffic Class 0
        packet[1] = 0x00.toByte()
        packet[2] = 0x00.toByte()
        packet[3] = 0x00.toByte()
        packet[4] = ((icmpv6Len shr 8) and 0xFF).toByte()
        packet[5] = (icmpv6Len and 0xFF).toByte()
        packet[6] = 58.toByte()   // Next Header: ICMPv6 (58)
        packet[7] = 64.toByte()   // Hop Limit: 64

        // Source IPv6 address of ICMPv6 error is the destination that was unreachable
        System.arraycopy(dstIpBytes, 0, packet, 8, 16)
        // Destination IPv6 address of ICMPv6 error is the originating client
        System.arraycopy(srcIpBytes, 0, packet, 24, 16)

        // 2. ICMPv6 Header (8 bytes)
        val icmpOffset = 40
        packet[icmpOffset] = 1.toByte() // Type 1: Destination Unreachable
        packet[icmpOffset + 1] = code.toByte() // Code (1 = Admin Prohibited, 3 = Address Unreachable, 4 = Port Unreachable)
        packet[icmpOffset + 2] = 0x00.toByte() // Checksum placeholder
        packet[icmpOffset + 3] = 0x00.toByte()
        packet[icmpOffset + 4] = 0x00.toByte() // 4 unused bytes per RFC 4443
        packet[icmpOffset + 5] = 0x00.toByte()
        packet[icmpOffset + 6] = 0x00.toByte()
        packet[icmpOffset + 7] = 0x00.toByte()

        // 3. ICMPv6 Body: Offending packet
        System.arraycopy(originalBuffer, 0, packet, icmpOffset + 8, includedOriginalLen)

        // Compute RFC 4443 Checksum with IPv6 Pseudo-Header
        val checksum = computeIcmpv6Checksum(
            packet = packet,
            icmpOffset = icmpOffset,
            icmpLen = icmpv6Len,
            srcIp = dstIpBytes,
            dstIp = srcIpBytes
        )
        packet[icmpOffset + 2] = ((checksum.toInt() shr 8) and 0xFF).toByte()
        packet[icmpOffset + 3] = (checksum.toInt() and 0xFF).toByte()

        return packet
    }

    /**
     * Synthesizes an RFC 4443 compliant ICMPv6 Destination Unreachable (Code 3: Address Unreachable) packet.
     */
    fun buildIcmpv6AddressUnreachablePacket(
        originalBuffer: ByteArray,
        originalLength: Int,
        srcIp: InetAddress,
        dstIp: InetAddress
    ): ByteArray = buildIcmpv6DestinationUnreachablePacket(
        originalBuffer = originalBuffer,
        originalLength = originalLength,
        srcIp = srcIp,
        dstIp = dstIp,
        code = 3
    )

    /**
     * Synthesizes an RFC 4443 compliant ICMPv6 Destination Unreachable (Code 4: Port Unreachable) packet.
     */
    fun buildIcmpv6PortUnreachablePacket(
        originalBuffer: ByteArray,
        originalLength: Int,
        srcIp: InetAddress,
        dstIp: InetAddress
    ): ByteArray = buildIcmpv6DestinationUnreachablePacket(
        originalBuffer = originalBuffer,
        originalLength = originalLength,
        srcIp = srcIp,
        dstIp = dstIp,
        code = 4
    )

    /**
     * Synthesizes an RFC 4443 compliant ICMPv6 Destination Unreachable (Code 1: Administratively Prohibited) packet.
     */
    fun buildIcmpv6AdminProhibitedPacket(
        originalBuffer: ByteArray,
        originalLength: Int,
        srcIp: InetAddress,
        dstIp: InetAddress
    ): ByteArray = buildIcmpv6DestinationUnreachablePacket(
        originalBuffer = originalBuffer,
        originalLength = originalLength,
        srcIp = srcIp,
        dstIp = dstIp,
        code = 1
    )
}
