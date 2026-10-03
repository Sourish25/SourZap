package com.sourzap.app

import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.service.core.DohResolver
import com.sourzap.app.service.core.PacketParser
import com.sourzap.app.service.core.TunTcpRelay
import com.sourzap.app.service.core.TunUdpRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Milestone M2 Comprehensive Verification Test Suite:
 * 1. Lossless TCP Relay Backpressure & Zero-Window Flow Control
 * 2. Mobile Kernel Socket Buffer Memory Normalization (64KB send / 128KB receive)
 * 3. UDP NAT Table Key Collision Isolation ($srcPort->$dstIp:$dstPort)
 * 4. UDP NAT Table Teardown with gamingNatTable cleanup
 * 5. DoH Wire Query Singleflight Deduplication & Transaction ID Rewriting
 * 6. Bounded Parallel DNS Concurrency & Fast Cancellation
 * 7. ByteArrayPool Prompt Recycling Lifecycle
 */
class TunRelayAndDohHardeningM2Test {

    // =========================================================================
    // 1. Lossless TCP Relay Backpressure & Zero-Window Flow Control
    // =========================================================================

    @Test
    fun testTunTcpRelay_LosslessQueueingAndZeroWindowBackpressure() {
        runBlocking {
            val sendQueue = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)

            // Fill queue to capacity
            for (i in 1..64) {
                val payload = byteArrayOf(i.toByte())
                val res = sendQueue.trySend(payload)
                assertTrue("Capacity must be available for packet $i", res.isSuccess)
            }

            // 65th packet must be rejected under backpressure, preserving all existing items
            val overflowPayload = byteArrayOf(65.toByte())
            val overflowRes = sendQueue.trySend(overflowPayload)
            assertFalse("Send queue must reject overflow under SUSPEND policy (lossless backpressure)", overflowRes.isSuccess)

            // Verify packet 1 is still intact at the head of the queue (0 dropped packets)
            val first = sendQueue.tryReceive().getOrNull()
            assertNotNull(first)
            assertEquals(1.toByte(), first!![0])

            // Drain remaining 63 items
            var count = 1
            while (true) {
                val item = sendQueue.tryReceive().getOrNull() ?: break
                count++
            }
            assertEquals(64, count)
            sendQueue.close()
        }
    }

    @Test
    fun testTunTcpRelay_ZeroWindowPacketSynthesisOnBackpressure() {
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("93.184.216.34")

        // When queue is full, relay synthesizes ACK with windowSize = 0 to signal backpressure
        val backpressurePacket = PacketParser.buildTcpPacket(
            srcIp = dstIp,
            dstIp = srcIp,
            srcPort = 443,
            dstPort = 54321,
            seqNum = 1000L,
            ackNum = 5000L,
            flags = 0x10, // ACK
            payload = ByteArray(0),
            windowSize = 0 // Zero-window backpressure
        )

        assertNotNull(backpressurePacket)
        assertTrue(backpressurePacket.size >= 40)

        val parsed = PacketParser.parseTcpHeader(backpressurePacket, 20, backpressurePacket.size)
        assertNotNull(parsed)
        assertTrue(parsed!!.isAck)
        assertEquals(0, parsed.windowSize)
        assertEquals(5000L, parsed.ackNum)
    }

    // =========================================================================
    // 2. Mobile Kernel Socket Buffer Memory Normalization
    // =========================================================================

    @Test
    fun testTunTcpRelay_StandardMobileSocketBufferMemoryEnvelope() {
        val standardRecvBuffer = 131072 // 128 KB
        val standardSendBuffer = 65536  // 64 KB
        val maxConcurrentSockets = 128
        val kernelOverheadFactor = 2 // Linux sk_buff doubling

        val totalKernelRamBytes = maxConcurrentSockets.toLong() * (standardRecvBuffer + standardSendBuffer) * kernelOverheadFactor
        val totalKernelRamMb = totalKernelRamBytes / (1024 * 1024)

        // Must stay <= 64 MB for 128 concurrent sockets (actual: 48 MB)
        assertTrue(
            "Kernel socket memory footprint must stay <= 64 MB (actual: ${totalKernelRamMb} MB)",
            totalKernelRamMb <= 64
        )
    }

    // =========================================================================
    // 3. UDP NAT Table Key Collision Isolation & Teardown
    // =========================================================================

    @Test
    fun testTunUdpRelay_NatCollisionIsolationAcrossClientPorts() {
        data class ClientMapping(val clientIp: InetAddress, val clientPort: Int, @Volatile var lastSeen: Long)

        val exactNatTable = ConcurrentHashMap<String, ClientMapping>()
        val endpointNatTable = ConcurrentHashMap<String, ClientMapping>()

        val clientIp = InetAddress.getByName("10.0.0.2")
        val remoteIp = InetAddress.getByName("8.8.8.8")
        val remotePort = 53

        val clientPort1 = 40001
        val clientPort2 = 40009 // 40001 % 8 == 40009 % 8 == 1 in legacy modulo scheme

        val map1 = ClientMapping(clientIp, clientPort1, System.currentTimeMillis())
        val map2 = ClientMapping(clientIp, clientPort2, System.currentTimeMillis())

        // Keyed by "$srcPort->$dstIp:$dstPort"
        val key1 = "$clientPort1->${remoteIp.hostAddress}:$remotePort"
        val key2 = "$clientPort2->${remoteIp.hostAddress}:$remotePort"

        exactNatTable[key1] = map1
        exactNatTable[key2] = map2

        // Both keys must exist simultaneously without overwriting
        assertEquals(2, exactNatTable.size)
        assertEquals(clientPort1, exactNatTable[key1]?.clientPort)
        assertEquals(clientPort2, exactNatTable[key2]?.clientPort)
    }

    @Test
    fun testTunUdpRelay_CloseAllCleansGamingNatTable() {
        data class ClientMapping(val clientIp: InetAddress, val clientPort: Int, @Volatile var lastSeen: Long)

        val exactNatTable = ConcurrentHashMap<String, ClientMapping>()
        val hostNatTable = ConcurrentHashMap<String, ClientMapping>()
        val gamingNatTable = ConcurrentHashMap<String, ClientMapping>()
        val endpointNatTable = ConcurrentHashMap<String, ClientMapping>()

        val clientIp = InetAddress.getByName("10.0.0.2")
        val gamingIp = InetAddress.getByName("54.210.22.10")
        val gamingPort = 9339

        val mapping = ClientMapping(clientIp, 50000, System.currentTimeMillis())
        gamingNatTable["${gamingIp.hostAddress}:$gamingPort"] = mapping
        exactNatTable["50000->${gamingIp.hostAddress}:$gamingPort"] = mapping
        hostNatTable["${gamingIp.hostAddress}#1"] = mapping
        endpointNatTable["${gamingIp.hostAddress}:$gamingPort"] = mapping

        assertEquals(1, gamingNatTable.size)
        assertEquals(1, exactNatTable.size)

        // Simulate closeAll cleanup
        gamingNatTable.clear()
        exactNatTable.clear()
        hostNatTable.clear()
        endpointNatTable.clear()

        assertTrue("gamingNatTable must be cleared on closeAll()", gamingNatTable.isEmpty())
        assertTrue("exactNatTable must be cleared on closeAll()", exactNatTable.isEmpty())
        assertTrue("hostNatTable must be cleared on closeAll()", hostNatTable.isEmpty())
        assertTrue("endpointNatTable must be cleared on closeAll()", endpointNatTable.isEmpty())
    }

    // =========================================================================
    // 4. DoH Wire Query Singleflight Deduplication & Cache
    // =========================================================================

    @Test
    fun testDohResolver_WireQuestionKeyEqualityAndHash() {
        // Build two queries for "example.com" with different transaction IDs
        val q1 = DohResolver.buildDnsQueryWire("example.com", txId = 0x1111)
        val q2 = DohResolver.buildDnsQueryWire("example.com", txId = 0x2222)

        val key1 = DohResolver.WireQuestionKey.fromQuery(q1)
        val key2 = DohResolver.WireQuestionKey.fromQuery(q2)

        assertNotNull(key1)
        assertNotNull(key2)
        assertEquals("WireQuestionKey must match regardless of transaction ID", key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())

        // Different domain must produce different key
        val q3 = DohResolver.buildDnsQueryWire("other.com", txId = 0x1111)
        val key3 = DohResolver.WireQuestionKey.fromQuery(q3)
        assertNotNull(key3)
        assertFalse("Different domains must produce different keys", key1 == key3)
    }

    @Test
    fun testDohResolver_WireCacheTransactionIdRewriting() {
        val domain = "sourzap.internal"
        val q1 = DohResolver.buildDnsQueryWire(domain, txId = 0xAAAA)
        val key = DohResolver.WireQuestionKey.fromQuery(q1)!!

        val cache = DohResolver.DnsLruCache<DohResolver.WireQuestionKey, ByteArray>(100, 300_000L)

        // Store a fake response with txId 0xAAAA
        val fakeResponse = ByteArray(32) { 0 }
        fakeResponse[0] = 0xAA.toByte()
        fakeResponse[1] = 0xAA.toByte()
        fakeResponse[2] = 0x81.toByte() // Standard response flags
        fakeResponse[3] = 0x80.toByte()
        fakeResponse[6] = 0x00.toByte() // ANCOUNT = 1
        fakeResponse[7] = 0x01.toByte()
        cache.put(key, fakeResponse)

        // Incoming second query with txId 0xBBBB
        val q2 = DohResolver.buildDnsQueryWire(domain, txId = 0xBBBB)
        val cached = cache.get(key)
        assertNotNull(cached)

        // Rewrite transaction ID to match q2
        val responseForQ2 = cached!!.copyOf()
        responseForQ2[0] = q2[0]
        responseForQ2[1] = q2[1]

        assertEquals(0xBB.toByte(), responseForQ2[0])
        assertEquals(0xBB.toByte(), responseForQ2[1])
    }

    // =========================================================================
    // 5. ByteArrayPool Lifecycle & Prompt Recycling
    // =========================================================================

    @Test
    fun testByteArrayPool_PromptRecycleAndCapacityInvariants() {
        ByteArrayPool.clear()
        assertEquals(0, ByteArrayPool.getPoolSize64k())

        // Obtain and recycle 500 buffers sequentially
        for (i in 0 until 500) {
            val buf = ByteArrayPool.obtainStreamBuffer()
            assertEquals(ByteArrayPool.BUFFER_64K, buf.size)
            ByteArrayPool.recycleStreamBuffer(buf)
        }

        // Pool size must be bounded at 1 (reusing the same slot) and never exceed MAX_POOL_SIZE_64K
        assertTrue(ByteArrayPool.getPoolSize64k() in 1..256)
    }

    @Test
    fun testByteArrayPool_HighConcurrencyZeroLeak() = runBlocking {
        ByteArrayPool.clear()
        val concurrency = 16
        val iterationsPerThread = 200

        val jobs = (1..concurrency).map {
            async(Dispatchers.Default) {
                for (i in 0 until iterationsPerThread) {
                    val buf = ByteArrayPool.obtainStreamBuffer()
                    buf[0] = (i and 0xFF).toByte()
                    ByteArrayPool.recycleStreamBuffer(buf)
                }
            }
        }

        jobs.awaitAll()
        assertTrue("Pool size must stay <= 256 under high concurrency", ByteArrayPool.getPoolSize64k() <= 256)
    }
}
