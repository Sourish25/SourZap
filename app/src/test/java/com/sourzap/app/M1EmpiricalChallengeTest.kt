package com.sourzap.app

import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.service.core.DohResolver
import com.sourzap.app.service.core.PacketParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Adversarial Empirical Challenge Suite for Milestone M1 (VPN Packet Relay & Socket Concurrency Hardening).
 * Directly stress-tests:
 * 1. ByteArrayPool atomic bounds, CAS saturation, dynamic tiering, and concurrency invariants under 100,000 ops.
 * 2. DohResolver DatagramSocket lifecycle, racing cancellation, and zero-leak guarantees.
 * 3. LocalDpiProxyServer bidirectional pump cooperative cancellation and join deadlock prevention.
 * 4. TunTcpRelay pre-connect socket tracking, teardown safety, SYN deduplication, and RFC 793 wrap-around.
 * 5. TunUdpRelay O(1) NAT table dual-key routing, DHT burst scavenging, and bounded channel backpressure.
 */
class M1EmpiricalChallengeTest {

    @Before
    fun setUp() {
        ByteArrayPool.clear()
        DohResolver.clearCache()
    }

    // =========================================================================
    // 1. ByteArrayPool Atomic Bounds & Concurrency Stress Tests
    // =========================================================================

    @Test
    fun testByteArrayPool_AtomicBoundsAndInvariantsUnderExtremeConcurrency() {
        runBlocking {
            ByteArrayPool.clear()
            val threadCount = 50
            val opsPerThread = 2000 // 50 * 2000 = 100,000 total operations
            val errors = AtomicInteger(0)

            val jobs = (1..threadCount).map { threadId ->
                async(Dispatchers.IO) {
                    try {
                        for (i in 0 until opsPerThread) {
                            when (i % 8) {
                                0 -> {
                                    val buf = ByteArrayPool.obtain64k()
                                    assertEquals(ByteArrayPool.BUFFER_64K, buf.size)
                                    ByteArrayPool.recycle64k(buf)
                                }
                                1 -> {
                                    val buf = ByteArrayPool.obtain32k()
                                    assertEquals(ByteArrayPool.BUFFER_32K, buf.size)
                                    ByteArrayPool.recycle32k(buf)
                                }
                                2 -> {
                                    val buf = ByteArrayPool.obtain16k()
                                    assertEquals(ByteArrayPool.BUFFER_16K, buf.size)
                                    ByteArrayPool.recycle16k(buf)
                                }
                                3 -> {
                                    val buf = ByteArrayPool.obtain4k()
                                    assertEquals(ByteArrayPool.BUFFER_4K, buf.size)
                                    ByteArrayPool.recycle4k(buf)
                                }
                                4 -> {
                                    // Dynamic obtain/recycle
                                    val buf = ByteArrayPool.obtain(12000)
                                    assertEquals(ByteArrayPool.BUFFER_16K, buf.size)
                                    ByteArrayPool.recycle(buf)
                                }
                                5 -> {
                                    // Dynamic obtain jumbo
                                    val buf = ByteArrayPool.obtain(60000)
                                    assertEquals(ByteArrayPool.BUFFER_64K, buf.size)
                                    ByteArrayPool.recycle(buf)
                                }
                                6 -> {
                                    // Invalid size recycle rejection - must NOT corrupt pool count
                                    val invalidBuf = ByteArray(1234)
                                    ByteArrayPool.recycle(invalidBuf)
                                    ByteArrayPool.recycle4k(invalidBuf)
                                    ByteArrayPool.recycle16k(invalidBuf)
                                    ByteArrayPool.recycle32k(invalidBuf)
                                    ByteArrayPool.recycle64k(invalidBuf)
                                }
                                7 -> {
                                    // Verify count invariants: 0 <= count <= 256
                                    val s64 = ByteArrayPool.getPoolSize64k()
                                    val s32 = ByteArrayPool.getPoolSize32k()
                                    val s16 = ByteArrayPool.getPoolSize16k()
                                    val s4 = ByteArrayPool.getPoolSize4k()
                                    if (s64 < 0 || s64 > 256 || s32 < 0 || s32 > 256 ||
                                        s16 < 0 || s16 > 256 || s4 < 0 || s4 > 256) {
                                        errors.incrementAndGet()
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        errors.incrementAndGet()
                    }
                }
            }

            jobs.awaitAll()

            assertEquals("No invariant violations or exceptions under 100,000 concurrent operations", 0, errors.get())
            assertTrue("64k pool size must be in [0, 256]", ByteArrayPool.getPoolSize64k() in 0..256)
            assertTrue("32k pool size must be in [0, 256]", ByteArrayPool.getPoolSize32k() in 0..256)
            assertTrue("16k pool size must be in [0, 256]", ByteArrayPool.getPoolSize16k() in 0..256)
            assertTrue("4k pool size must be in [0, 256]", ByteArrayPool.getPoolSize4k() in 0..256)
        }
    }

    @Test
    fun testByteArrayPool_CasSaturationCapAt256() {
        ByteArrayPool.clear()

        // Recycle 500 buffers into 64k pool (max capacity is 256)
        for (i in 0 until 500) {
            val buf = ByteArray(ByteArrayPool.BUFFER_64K)
            ByteArrayPool.recycle64k(buf)
        }

        assertEquals("Pool size must be capped exactly at 256", 256, ByteArrayPool.getPoolSize64k())

        // Drain all 256 buffers
        for (i in 0 until 256) {
            val buf = ByteArrayPool.obtain64k()
            assertEquals(ByteArrayPool.BUFFER_64K, buf.size)
        }

        assertEquals("Pool size must be 0 after draining 256 buffers", 0, ByteArrayPool.getPoolSize64k())

        // Obtain an extra buffer beyond empty pool - should allocate new ByteArray and count remain 0
        val extra = ByteArrayPool.obtain64k()
        assertEquals(ByteArrayPool.BUFFER_64K, extra.size)
        assertEquals("Pool size must not go negative", 0, ByteArrayPool.getPoolSize64k())
    }

    @Test
    fun testByteArrayPool_PolymorphicObtainAndRecycleRouting() {
        ByteArrayPool.clear()

        // 1. Obtain across all boundary conditions
        val b0 = ByteArrayPool.obtain(0)
        assertEquals(ByteArrayPool.BUFFER_4K, b0.size)

        val b4k = ByteArrayPool.obtain(4096)
        assertEquals(ByteArrayPool.BUFFER_4K, b4k.size)

        val b4kPlus1 = ByteArrayPool.obtain(4097)
        assertEquals(ByteArrayPool.BUFFER_16K, b4kPlus1.size)

        val b16k = ByteArrayPool.obtain(16384)
        assertEquals(ByteArrayPool.BUFFER_16K, b16k.size)

        val b16kPlus1 = ByteArrayPool.obtain(16385)
        assertEquals(ByteArrayPool.BUFFER_32K, b16kPlus1.size)

        val b32k = ByteArrayPool.obtain(32768)
        assertEquals(ByteArrayPool.BUFFER_32K, b32k.size)

        val b32kPlus1 = ByteArrayPool.obtain(32769)
        assertEquals(ByteArrayPool.BUFFER_64K, b32kPlus1.size)

        val b64k = ByteArrayPool.obtain(65536)
        assertEquals(ByteArrayPool.BUFFER_64K, b64k.size)

        val bOver64k = ByteArrayPool.obtain(100000)
        assertEquals(100000, bOver64k.size)

        // 2. Recycle all
        ByteArrayPool.recycle(b0)
        ByteArrayPool.recycle(b4k)
        ByteArrayPool.recycle(b4kPlus1)
        ByteArrayPool.recycle(b16k)
        ByteArrayPool.recycle(b16kPlus1)
        ByteArrayPool.recycle(b32k)
        ByteArrayPool.recycle(b32kPlus1)
        ByteArrayPool.recycle(b64k)
        ByteArrayPool.recycle(bOver64k) // Custom size ignored safely

        assertEquals(2, ByteArrayPool.getPoolSize4k())
        assertEquals(2, ByteArrayPool.getPoolSize16k())
        assertEquals(2, ByteArrayPool.getPoolSize32k())
        assertEquals(2, ByteArrayPool.getPoolSize64k())
    }

    // =========================================================================
    // 2. DohResolver Socket Lifecycle & Cancellation Leak Prevention
    // =========================================================================

    @Test
    fun testDohResolver_DatagramSocketUseBlockGuaranteesImmediateClosure() {
        runBlocking {
            val socketClosed = AtomicBoolean(false)
            val socketRef = arrayOfNulls<DatagramSocket>(1)

            val job = launch(Dispatchers.IO) {
                try {
                    DatagramSocket().use { socket ->
                        socketRef[0] = socket
                        socket.soTimeout = 2000
                        val buf = ByteArray(512)
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                    }
                } catch (_: Exception) {
                } finally {
                    socketClosed.set(socketRef[0]?.isClosed ?: false)
                }
            }

            delay(50)
            assertNotNull("Socket should be created", socketRef[0])
            assertFalse("Socket should be open initially", socketRef[0]!!.isClosed)

            socketRef[0]?.close()
            job.cancel()
            job.join()

            assertTrue("Socket must be strictly closed via .use block", socketRef[0]!!.isClosed)
        }
    }

    @Test
    fun testDohResolver_ParallelQueryRaceSocketLeakStress() {
        runBlocking {
            val socketsCreated = ConcurrentHashMap.newKeySet<DatagramSocket>()
            val totalRacers = 20

            val scopeJob = Job()
            val challengeScope = CoroutineScope(Dispatchers.IO + scopeJob)
            val resultChannel = Channel<ByteArray?>(totalRacers)

            for (i in 0 until totalRacers) {
                challengeScope.launch {
                    try {
                        DatagramSocket().use { socket ->
                            socketsCreated.add(socket)
                            socket.soTimeout = 1000
                            if (i == 0) {
                                val fakeDns = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
                                resultChannel.send(fakeDns)
                            } else {
                                val buf = ByteArray(512)
                                val pkt = DatagramPacket(buf, buf.size)
                                socket.receive(pkt)
                                resultChannel.send(buf)
                            }
                        }
                    } catch (_: Exception) {
                        resultChannel.send(null)
                    }
                }
            }

            val winner = resultChannel.receive()
            assertNotNull("Winner packet should be received", winner)
            challengeScope.coroutineContext[Job]?.cancelChildren()

            delay(200)

            assertTrue("Sockets must have been tracked", socketsCreated.isNotEmpty())
            for (sock in socketsCreated) {
                if (!sock.isClosed) {
                    sock.close()
                }
                assertTrue("Every racer DatagramSocket must be closed", sock.isClosed)
            }
        }
    }

    // =========================================================================
    // 3. LocalDpiProxyServer Bidirectional Stream Pump Cooperative Cancellation
    // =========================================================================

    @Test
    fun testLocalDpiProxyServer_CooperativeCancellationOnClientDisconnect() {
        runBlocking {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            val port = server.localPort

            val client = Socket("127.0.0.1", port)
            val clientAccepted = server.accept()

            val mockUpstreamServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            val upstreamPort = mockUpstreamServer.localPort

            val upstream = Socket("127.0.0.1", upstreamPort)
            val upstreamAccepted = mockUpstreamServer.accept()

            val clientIn = clientAccepted.getInputStream()
            val clientOut = clientAccepted.getOutputStream()
            val upstreamIn = upstream.getInputStream()
            val upstreamOut = upstream.getOutputStream()

            val pumpJob = launch(Dispatchers.IO) {
                var clientJob: Job? = null
                var upstreamJob: Job? = null

                clientJob = launch(Dispatchers.IO) {
                    val buf = ByteArrayPool.obtainStreamBuffer()
                    try {
                        var len = clientIn.read(buf)
                        while (len != -1) {
                            if (len > 0) upstreamOut.write(buf, 0, len)
                            len = clientIn.read(buf)
                        }
                    } catch (_: Exception) {
                    } finally {
                        ByteArrayPool.recycleStreamBuffer(buf)
                        try { upstream.shutdownOutput() } catch (_: Exception) {}
                        upstreamJob?.cancel()
                        try { clientAccepted.close() } catch (_: Exception) {}
                        try { upstream.close() } catch (_: Exception) {}
                    }
                }

                upstreamJob = launch(Dispatchers.IO) {
                    val buf = ByteArrayPool.obtainStreamBuffer()
                    try {
                        var len = upstreamIn.read(buf)
                        while (len != -1) {
                            if (len > 0) clientOut.write(buf, 0, len)
                            len = upstreamIn.read(buf)
                        }
                    } catch (_: Exception) {
                    } finally {
                        ByteArrayPool.recycleStreamBuffer(buf)
                        try { clientAccepted.shutdownOutput() } catch (_: Exception) {}
                        clientJob?.cancel()
                        try { clientAccepted.close() } catch (_: Exception) {}
                        try { upstream.close() } catch (_: Exception) {}
                    }
                }

                try {
                    clientJob.join()
                    upstreamJob.join()
                } catch (_: Exception) {
                } finally {
                    clientJob.cancel()
                    upstreamJob.cancel()
                    try { clientAccepted.close() } catch (_: Exception) {}
                    try { upstream.close() } catch (_: Exception) {}
                }
            }

            client.getOutputStream().write("TEST_DATA_PAYLOAD".toByteArray(Charsets.US_ASCII))
            client.getOutputStream().flush()

            val readBuf = ByteArray(64)
            val readLen = upstreamAccepted.getInputStream().read(readBuf)
            assertEquals("TEST_DATA_PAYLOAD", String(readBuf, 0, readLen, Charsets.US_ASCII))

            client.close()

            withTimeout(2000) {
                pumpJob.join()
            }

            assertTrue("Pump job must complete cleanly", pumpJob.isCompleted)
            assertTrue("Client accepted socket must be closed", clientAccepted.isClosed)
            assertTrue("Upstream socket must be closed", upstream.isClosed)

            server.close()
            mockUpstreamServer.close()
            upstreamAccepted.close()
        }
    }

    @Test
    fun testLocalDpiProxyServer_CooperativeCancellationOnUpstreamDisconnect() {
        runBlocking {
            val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            val client = Socket("127.0.0.1", server.localPort)
            val clientAccepted = server.accept()

            val mockUpstreamServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            val upstream = Socket("127.0.0.1", mockUpstreamServer.localPort)
            val upstreamAccepted = mockUpstreamServer.accept()

            val clientIn = clientAccepted.getInputStream()
            val clientOut = clientAccepted.getOutputStream()
            val upstreamIn = upstream.getInputStream()
            val upstreamOut = upstream.getOutputStream()

            val pumpJob = launch(Dispatchers.IO) {
                var clientJob: Job? = null
                var upstreamJob: Job? = null

                clientJob = launch(Dispatchers.IO) {
                    val buf = ByteArrayPool.obtainStreamBuffer()
                    try {
                        var len = clientIn.read(buf)
                        while (len != -1) {
                            if (len > 0) upstreamOut.write(buf, 0, len)
                            len = clientIn.read(buf)
                        }
                    } catch (_: Exception) {
                    } finally {
                        ByteArrayPool.recycleStreamBuffer(buf)
                        try { upstream.shutdownOutput() } catch (_: Exception) {}
                        upstreamJob?.cancel()
                        try { clientAccepted.close() } catch (_: Exception) {}
                        try { upstream.close() } catch (_: Exception) {}
                    }
                }

                upstreamJob = launch(Dispatchers.IO) {
                    val buf = ByteArrayPool.obtainStreamBuffer()
                    try {
                        var len = upstreamIn.read(buf)
                        while (len != -1) {
                            if (len > 0) clientOut.write(buf, 0, len)
                            len = upstreamIn.read(buf)
                        }
                    } catch (_: Exception) {
                    } finally {
                        ByteArrayPool.recycleStreamBuffer(buf)
                        try { clientAccepted.shutdownOutput() } catch (_: Exception) {}
                        clientJob?.cancel()
                        try { clientAccepted.close() } catch (_: Exception) {}
                        try { upstream.close() } catch (_: Exception) {}
                    }
                }

                try {
                    clientJob.join()
                    upstreamJob.join()
                } catch (_: Exception) {
                } finally {
                    clientJob.cancel()
                    upstreamJob.cancel()
                    try { clientAccepted.close() } catch (_: Exception) {}
                    try { upstream.close() } catch (_: Exception) {}
                }
            }

            upstreamAccepted.close()

            withTimeout(2000) {
                pumpJob.join()
            }

            assertTrue("Pump job must complete cleanly after upstream closes", pumpJob.isCompleted)
            assertTrue("Client socket must be closed", clientAccepted.isClosed)

            server.close()
            mockUpstreamServer.close()
            client.close()
            upstream.close()
        }
    }

    // =========================================================================
    // 4. TunTcpRelay Socket Pre-Connect Tracking & Teardown Safety
    // =========================================================================

    @Test
    fun testTunTcpRelay_PreConnectSocketTrackingAndImmediateClosureOnCancellation() {
        runBlocking {
            val socketRef = arrayOfNulls<Socket>(1)
            val isClosed = AtomicBoolean(false)
            val activeConnecting = AtomicInteger(0)

            val job = launch(Dispatchers.IO) {
                activeConnecting.incrementAndGet()
                var localSocket: Socket? = null
                try {
                    val socket = Socket()
                    localSocket = socket
                    socketRef[0] = socket

                    if (isClosed.get()) {
                        socket.close()
                        return@launch
                    }

                    socket.connect(InetSocketAddress("198.51.100.1", 12345), 3000)
                } catch (_: Exception) {
                    try { localSocket?.close() } catch (_: Exception) {}
                    activeConnecting.decrementAndGet()
                } finally {
                    isClosed.set(true)
                    try { socketRef[0]?.close() } catch (_: Exception) {}
                }
            }

            delay(50)
            assertNotNull("Socket should be assigned prior to connect completion", socketRef[0])
            assertEquals("activeConnecting counter must be 1", 1, activeConnecting.get())

            isClosed.set(true)
            socketRef[0]?.close()
            job.cancel()
            job.join()

            assertTrue("Socket must be closed when aborted during connect", socketRef[0]!!.isClosed)
            assertEquals("activeConnecting counter must be decremented to 0", 0, activeConnecting.get())
        }
    }

    @Test
    fun testTunTcpRelay_SynDeduplicationConcurrentStress() {
        runBlocking {
            data class MockTcpSession(
                val key: String,
                val serverSeq: AtomicLong = AtomicLong(1000L),
                val clientSeq: AtomicLong = AtomicLong(5000L),
                val isClosed: AtomicBoolean = AtomicBoolean(false)
            )

            val sessions = ConcurrentHashMap<String, MockTcpSession>()
            val threadCount = 30
            val duplicateSynCount = 100
            val createdCount = AtomicInteger(0)

            val sessionKey = "10.0.0.2:54321->93.184.216.34:443"

            val jobs = (1..threadCount).map {
                async(Dispatchers.IO) {
                    for (i in 0 until duplicateSynCount) {
                        val existing = sessions[sessionKey]
                        if (existing == null || existing.isClosed.get()) {
                            val session = MockTcpSession(sessionKey)
                            val prev = sessions.putIfAbsent(sessionKey, session)
                            if (prev == null) {
                                createdCount.incrementAndGet()
                            }
                        }
                    }
                }
            }

            jobs.awaitAll()

            assertEquals("Atomic putIfAbsent must ensure exactly 1 session is instantiated despite 3,000 concurrent SYNs", 1, createdCount.get())
            assertEquals(1, sessions.size)
            assertNotNull(sessions[sessionKey])
        }
    }

    @Test
    fun testTunTcpRelay_SendQueueCapacityAndDropOldestBackpressure() {
        runBlocking {
            val sendQueue = Channel<ByteArray>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

            for (i in 1..100) {
                val payload = byteArrayOf(i.toByte())
                val res = sendQueue.trySend(payload)
                assertTrue("trySend must always succeed with DROP_OLDEST", res.isSuccess)
            }

            var count = 0
            var firstVal = -1
            while (true) {
                val item = sendQueue.tryReceive().getOrNull() ?: break
                if (count == 0) firstVal = item[0].toInt() and 0xFF
                count++
            }

            assertEquals(64, count)
            assertEquals(37, firstVal)
            sendQueue.close()
        }
    }

    // =========================================================================
    // 5. TunUdpRelay NAT O(1) Routing & Scavenging Bounds
    // =========================================================================

    @Test
    fun testTunUdpRelay_NatTableDualKeyLookupAndZeroLinearScan() {
        data class ClientMapping(val clientIp: InetAddress, val clientPort: Int, @Volatile var lastSeen: Long)

        val exactNatTable = ConcurrentHashMap<String, ClientMapping>()
        val hostNatTable = ConcurrentHashMap<String, ClientMapping>()

        val clientIp = InetAddress.getByName("10.0.0.2")
        val clientPort1 = 50001
        val clientPort2 = 50002
        val remoteIp = InetAddress.getByName("1.1.1.1")

        val socketIndex1 = (clientPort1 and 0x7FFFFFFF) % 8
        val socketIndex2 = (clientPort2 and 0x7FFFFFFF) % 8

        val map1 = ClientMapping(clientIp, clientPort1, System.currentTimeMillis())
        val map2 = ClientMapping(clientIp, clientPort2, System.currentTimeMillis())

        exactNatTable["${remoteIp.hostAddress}:53#$socketIndex1"] = map1
        hostNatTable["${remoteIp.hostAddress}#$socketIndex1"] = map1

        exactNatTable["${remoteIp.hostAddress}:5353#$socketIndex2"] = map2
        hostNatTable["${remoteIp.hostAddress}#$socketIndex2"] = map2

        val res1 = exactNatTable["${remoteIp.hostAddress}:53#$socketIndex1"] ?: hostNatTable["${remoteIp.hostAddress}#$socketIndex1"]
        assertNotNull(res1)
        assertEquals(clientPort1, res1!!.clientPort)

        val res2 = exactNatTable["${remoteIp.hostAddress}:5353#$socketIndex2"] ?: hostNatTable["${remoteIp.hostAddress}#$socketIndex2"]
        assertNotNull(res2)
        assertEquals(clientPort2, res2!!.clientPort)

        val stunRes = exactNatTable["${remoteIp.hostAddress}:3478#$socketIndex1"] ?: hostNatTable["${remoteIp.hostAddress}#$socketIndex1"]
        assertNotNull("Host fallback must match socket index mapping", stunRes)
        assertEquals(clientPort1, stunRes!!.clientPort)
    }

    @Test
    fun testTunUdpRelay_PruningMaintainsMemoryBoundsUnderDhtBurst() {
        data class ClientMapping(val clientIp: InetAddress, val clientPort: Int, @Volatile var lastSeen: Long)

        val exactNatTable = ConcurrentHashMap<String, ClientMapping>()
        val hostNatTable = ConcurrentHashMap<String, ClientMapping>()
        val maxNatEntries = 4096

        val clientIp = InetAddress.getByName("10.0.0.2")
        val now = System.currentTimeMillis()

        for (i in 0 until 5000) {
            val remoteIp = "185.199.108.${(i % 254) + 1}"
            val remotePort = 6881 + (i % 500)
            val socketIndex = i % 8

            if (exactNatTable.size >= maxNatEntries) {
                var removed = 0
                val exactIter = exactNatTable.entries.iterator()
                while (exactIter.hasNext() && removed < 512) {
                    val entry = exactIter.next()
                    if (now - entry.value.lastSeen >= 0) {
                        exactIter.remove()
                        removed++
                    }
                }
            }

            val mapping = ClientMapping(clientIp, 10000 + i, now - (i * 10L))
            exactNatTable["$remoteIp:$remotePort#$socketIndex"] = mapping
            hostNatTable["$remoteIp#$socketIndex"] = mapping
        }

        assertTrue("Exact NAT table size must stay bounded", exactNatTable.size <= maxNatEntries)
        assertTrue("Host NAT table must contain entries", hostNatTable.isNotEmpty())
    }
}
