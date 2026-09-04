package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.UdpTrackerAnnouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for BitTorrent BEP 15 UDP Tracker protocol encoding and decoding.
 */
class UdpTrackerProtocolTest {

    @Test
    fun testParsePeersFromResponseValid() {
        val transId = 0x12345678
        val interval = 1800
        val leechers = 5
        val seeders = 42

        // Build 20-byte header + two 6-byte peers (total 32 bytes)
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(1) // action = announce
        buf.putInt(transId) // transaction_id
        buf.putInt(interval)
        buf.putInt(leechers)
        buf.putInt(seeders)

        // Peer 1: 83.254.78.74:23278
        buf.put(83.toByte())
        buf.put(254.toByte())
        buf.put(78.toByte())
        buf.put(74.toByte())
        buf.putShort(23278.toShort())

        // Peer 2: 93.158.213.92:1337
        buf.put(93.toByte())
        buf.put(158.toByte())
        buf.put(213.toByte())
        buf.put(92.toByte())
        buf.putShort(1337.toShort())

        val bytes = buf.array()
        val peers = UdpTrackerAnnouncer.parsePeersFromResponse(bytes, bytes.size, transId)

        assertEquals("Should parse 2 peers", 2, peers.size)
        assertEquals("Peer 1 IP", "83.254.78.74", peers[0].first)
        assertEquals("Peer 1 port", 23278, peers[0].second)
        assertEquals("Peer 2 IP", "93.158.213.92", peers[1].first)
        assertEquals("Peer 2 port", 1337, peers[1].second)
    }

    @Test
    fun testParsePeersMismatchedTransactionId() {
        val transId = 0x12345678
        val buf = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(1) // action = announce
        buf.putInt(0x99999999.toInt()) // wrong transId
        buf.putInt(1800)
        buf.putInt(1)
        buf.putInt(1)
        buf.put(83.toByte()).put(254.toByte()).put(78.toByte()).put(74.toByte())
        buf.putShort(23278.toShort())

        val peers = UdpTrackerAnnouncer.parsePeersFromResponse(buf.array(), 26, transId)
        assertTrue("Mismatched transaction ID must be rejected", peers.isEmpty())
    }

    @Test
    fun testParsePeersTruncatedBuffer() {
        val transId = 0x12345678
        val buf = ByteArray(18) // Less than 20 bytes header
        val peers = UdpTrackerAnnouncer.parsePeersFromResponse(buf, buf.size, transId)
        assertTrue("Truncated buffer must return empty list", peers.isEmpty())
    }

    @Test
    fun testHexStringToByteArray() {
        val hex = "3074db771497684f8c6e0052ce35abf31b9eb9a5"
        val bytes = UdpTrackerAnnouncer.hexStringToByteArray(hex)
        assertEquals("InfoHash byte array must be 20 bytes", 20, bytes.size)
        assertEquals("First byte", 0x30.toByte(), bytes[0])
        assertEquals("Last byte", 0xa5.toByte(), bytes[19])
    }
}
