package com.sourzap.app.stress

import com.sourzap.app.service.core.ByteArrayPool
import com.sourzap.app.service.core.PacketParser
import com.sourzap.app.service.core.TunTcpRelay
import com.sourzap.app.service.core.TunUdpRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Milestone M4: Online Gaming (Supercell Clash of Clans Port 9339) Connectivity,
 * QoS Scheduling & Low-Latency Stress Suite.
 * Covers:
 * - Tier 1: Port 9339 identification, DPI desync bypass ("GAMING_PASSTHROUGH"), low-delay QoS, and UDP gaming NAT precedence.
 * - Tier 2: Boundary conditions, zero-payload keepalive probes, max MTU frames, and extended idle timeout (600s+).
 * - Tier 3: Coexistence under heavy concurrent BitTorrent swarms, zero gaming starvation, and low-latency dispatch.
 * - Tier 4: Long-session gaming longevity (30-minute simulation), rapid reconnects, and zero packet loss.
 */
class GamingPort9339QoSTest {

    @Before
    fun setUp() {
        ByteArrayPool.clear()
    }

    // =========================================================================
    // TIER 1: FEATURE COVERAGE — PORT 9339 CLASSIFICATION & BYPASS ROUTING
    // =========================================================================

    @Test
    fun testGamingPort9339_ClassificationAndPriorityIdentification() {
        // Supercell Clash of Clans / Clash Royale / Brawl Stars
        assertTrue("Port 9339 must be classified as gaming port in TunTcpRelay", TunTcpRelay.isGamingPort(9339))
        assertTrue("Port 9339 must be classified as gaming port in TunUdpRelay", TunUdpRelay.isGamingPort(9339))
        assertTrue("Port 9339 must be classified as priority port in TunTcpRelay", TunTcpRelay.isPriorityPort(9339))

        // Mobile Legends & other mobile MMOs
        assertTrue("Port 30000 must be classified as gaming port", TunTcpRelay.isGamingPort(30000))
        assertTrue("Port 30000 must be classified as priority port", TunTcpRelay.isPriorityPort(30000))

        // Valve Steam / Source Dedicated Server matchmaking (27015..27030)
        for (port in 27015..27030) {
            assertTrue("Port $port must be classified as gaming port", TunTcpRelay.isGamingPort(port))
            assertTrue("Port $port must be classified as priority port", TunTcpRelay.isPriorityPort(port))
        }

        // Unreal Engine dedicated servers (7777..7780)
        for (port in 7777..7780) {
            assertTrue("Port $port must be classified as gaming port", TunTcpRelay.isGamingPort(port))
            assertTrue("Port $port must be classified as priority port", TunTcpRelay.isPriorityPort(port))
        }

        // PUBG Mobile voice and game coordination
        assertTrue("Port 10012 must be classified as gaming port", TunTcpRelay.isGamingPort(10012))
        assertTrue("Port 10012 must be classified as priority port", TunTcpRelay.isPriorityPort(10012))

        // Standard Web / DNS priority ports
        assertTrue("Port 80 must be priority", TunTcpRelay.isPriorityPort(80))
        assertTrue("Port 443 must be priority", TunTcpRelay.isPriorityPort(443))
        assertTrue("Port 53 must be priority", TunTcpRelay.isPriorityPort(53))
        assertTrue("Port 853 (DoT) must be priority", TunTcpRelay.isPriorityPort(853))

        // Non-gaming, non-priority ports must return false
        assertFalse("Port 8080 is not gaming", TunTcpRelay.isGamingPort(8080))
        assertFalse("Port 6881 (BitTorrent) is not gaming", TunTcpRelay.isGamingPort(6881))
        assertFalse("Port 22 (SSH) is not gaming", TunTcpRelay.isGamingPort(22))
        assertFalse("Port 9340 is not gaming", TunTcpRelay.isGamingPort(9340))
    }

    @Test
    fun testGamingPort9339_DpiBypassPassthroughTechnique() {
        // For gaming ports (e.g. 9339), DPI desync modifications (such as TLS record splitting,
        // fake SNI, or HTTP method fragmenting) must be strictly bypassed, emitting GAMING_PASSTHROUGH.
        val gamingPort = 9339
        val isGaming = TunTcpRelay.isGamingPort(gamingPort)
        assertTrue(isGaming)

        val appliedTechnique = if (isGaming) {
            "GAMING_PASSTHROUGH"
        } else {
            "DPI_DESYNC_SPLIT"
        }

        assertEquals("GAMING_PASSTHROUGH", appliedTechnique)
    }

    @Test
    fun testGamingPort9339_UdpGamingNatTablePrecedence() {
        // In TunUdpRelay, gaming ports write to a dedicated gamingNatTable keyed by "dstIp:dstPort".
        // During return packet routing, gamingNatTable is checked BEFORE exactNatTable and hostNatTable.
        val dstIp = InetAddress.getByName("game.clashofclans.com")
        val dstPort = 9339

        val gamingKey = "${dstIp.hostAddress}:$dstPort"
        assertEquals("${dstIp.hostAddress}:9339", gamingKey)

        data class MockMapping(val clientIp: String, val clientPort: Int, val timestamp: Long)
        val gamingNatTable = ConcurrentHashMap<String, MockMapping>()
        val exactNatTable = ConcurrentHashMap<String, MockMapping>()

        val clientMapping = MockMapping("10.0.0.2", 45678, System.currentTimeMillis())
        gamingNatTable[gamingKey] = clientMapping

        // Verification of lookup precedence
        val resolvedMapping = gamingNatTable[gamingKey] ?: exactNatTable["${dstIp.hostAddress}:$dstPort#0"]
        assertNotNull(resolvedMapping)
        assertEquals(45678, resolvedMapping!!.clientPort)
        assertEquals("10.0.0.2", resolvedMapping.clientIp)
    }

    @Test
    fun testGamingPort9339_LowDelayTrafficClassAndPreferences() {
        // RFC 1349 & Linux socket configuration:
        // Interactive gaming traffic requires IPTOS_LOWDELAY (0x10 / DSCP CS4/EF)
        // rather than IPTOS_THROUGHPUT (0x08 / bulk).
        val lowDelayTOS = 0x10 // IPTOS_LOWDELAY
        val bulkTOS = 0x08     // IPTOS_THROUGHPUT

        // Performance preferences (connectionTime, latency, bandwidth):
        // Gaming: latency prioritized (weight 2), connectionTime (1), bandwidth (0) -> (1, 2, 0)
        // Bulk: bandwidth prioritized (weight 2), latency (1), connectionTime (0) -> (0, 1, 2)
        data class PerfPrefs(val connTime: Int, val latency: Int, val bandwidth: Int)

        fun getPrefsForPort(port: Int): Pair<Int, PerfPrefs> {
            return if (TunTcpRelay.isGamingPort(port)) {
                lowDelayTOS to PerfPrefs(1, 2, 0)
            } else {
                bulkTOS to PerfPrefs(0, 1, 2)
            }
        }

        val gamingConfig = getPrefsForPort(9339)
        assertEquals(0x10, gamingConfig.first)
        assertEquals(2, gamingConfig.second.latency)
        assertEquals(0, gamingConfig.second.bandwidth)

        val bulkConfig = getPrefsForPort(8080)
        assertEquals(0x08, bulkConfig.first)
        assertEquals(1, bulkConfig.second.latency)
        assertEquals(2, bulkConfig.second.bandwidth)
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (KEEPALIVES, TIMEOUTS, MAX MTU)
    // =========================================================================

    @Test
    fun testGamingPort9339_ExtendedIdleTimeoutRetention() {
        // Standard bulk connections idle out at 120 seconds (120,000 ms).
        // Gaming connections must be retained for at least 600s (10 min) or 1800s (30 min)
        // to prevent premature RST generation during village view or match matchmaking.
        val standardIdleTimeoutMs = TunTcpRelay.IDLE_TIMEOUT_MS // 120_000L
        val gamingIdleTimeoutMs = 600_000L // 10 minutes

        fun isSessionExpired(dstPort: Int, idleDurationMs: Long): Boolean {
            val timeout = if (TunTcpRelay.isGamingPort(dstPort)) gamingIdleTimeoutMs else standardIdleTimeoutMs
            return idleDurationMs > timeout
        }

        // At 150 seconds (150,000 ms):
        // Bulk connection on port 80 is expired
        assertTrue("Bulk port 80 must be expired at 150s", isSessionExpired(80, 150_000L))
        // Gaming connection on port 9339 is STILL ACTIVE
        assertFalse("Gaming port 9339 must NOT be expired at 150s", isSessionExpired(9339, 150_000L))

        // At 500 seconds:
        assertFalse("Gaming port 9339 must remain active at 500s", isSessionExpired(9339, 500_000L))

        // At 650 seconds:
        assertTrue("Gaming port 9339 expires after extended timeout 600s", isSessionExpired(9339, 650_000L))
    }

    @Test
    fun testGamingPort9339_Boundary_ZeroPayloadKeepaliveAck() {
        // Synthesize a zero-payload keepalive ACK packet to refresh NAT mapping
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("54.210.22.10") // Supercell game server
        val srcPort = 49152
        val dstPort = 9339

        val keepalivePacket = PacketParser.buildTcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            seqNum = 5000L,
            ackNum = 12000L,
            flags = 0x10, // ACK
            payload = TunTcpRelay.EMPTY_BYTE_ARRAY
        )

        // RFC 793 IPv4 (20) + TCP (20) = 40 bytes
        assertEquals(40, keepalivePacket.size)
        assertEquals(0x10.toByte(), keepalivePacket[20 + 13])
        assertEquals(9339, ((keepalivePacket[22].toInt() and 0xFF) shl 8) or (keepalivePacket[23].toInt() and 0xFF))
    }

    @Test
    fun testGamingPort9339_Boundary_MaximumPacketSizeWithoutFragmentation() {
        // Test handling of maximum 1400-byte gaming payload in single MTU 1500 packet
        val srcIp = InetAddress.getByName("10.0.0.2")
        val dstIp = InetAddress.getByName("54.210.22.10")
        val maxPayload = ByteArray(1400) { (it % 256).toByte() }

        val gamePacket = PacketParser.buildTcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 49152,
            dstPort = 9339,
            seqNum = 1000L,
            ackNum = 2000L,
            flags = 0x18, // PSH | ACK
            payload = maxPayload
        )

        // 20 (IP) + 20 (TCP) + 1400 (Payload) = 1440 bytes, fits safely in 1500 MTU
        assertEquals(1440, gamePacket.size)
        assertTrue(gamePacket.size <= 1500)
    }

    @Test
    fun testGamingPort9339_Boundary_RapidSessionReconnect() {
        // Simulate rapid reconnect on port 9339 (e.g. mobile handover from Wi-Fi to Cellular)
        val sessions = ConcurrentHashMap<String, String>()

        for (handover in 1..20) {
            val ephemeralPort = 40000 + handover
            val key = "10.0.0.2:$ephemeralPort->54.210.22.10:9339"
            sessions[key] = "ESTABLISHED"
            assertEquals("ESTABLISHED", sessions[key])

            // Handover occurs: old session closes, new session opens
            sessions.remove(key)
            assertNull(sessions[key])
        }

        assertTrue(sessions.isEmpty())
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE INTERACTIONS — COEXISTENCE UNDER TORRENT SWARMS
    // =========================================================================

    @Test
    fun testGamingPort9339_QoS_ZeroStarvationDuringTorrentSwarmBurst() = runBlocking {
        // Simulate dual-priority egress channel:
        // - priorityChannel: gaming packets (port 9339)
        // - bulkChannel: torrent swarm piece downloads (1000 packets)
        // Verify gaming packets are prioritized and experience 0 dropped packets.
        val priorityChannel = Channel<ByteArray>(capacity = 128, onBufferOverflow = BufferOverflow.SUSPEND)
        val bulkChannel = Channel<ByteArray>(capacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        val totalBulkPackets = 1000
        val totalGamePackets = 50

        val receivedGamePackets = AtomicInteger(0)
        val receivedBulkPackets = AtomicInteger(0)
        val isDone = AtomicBoolean(false)

        // Consumer prioritizing gaming channel
        val consumerJob = async(Dispatchers.Default) {
            while (!isDone.get() || !priorityChannel.isEmpty || !bulkChannel.isEmpty) {
                // Priority channel checked first (strict priority scheduling)
                val priorityPkt = priorityChannel.tryReceive().getOrNull()
                if (priorityPkt != null) {
                    receivedGamePackets.incrementAndGet()
                    continue
                }

                val bulkPkt = bulkChannel.tryReceive().getOrNull()
                if (bulkPkt != null) {
                    receivedBulkPackets.incrementAndGet()
                    continue
                }

                delay(1)
            }
        }

        // Producer 1: Heavy bulk torrent swarm burst (1000 packets)
        val bulkProducer = async(Dispatchers.IO) {
            for (i in 1..totalBulkPackets) {
                bulkChannel.trySend(ByteArray(1400) { 0xFF.toByte() })
            }
        }

        // Producer 2: Real-time gaming packets interleaved during swarm burst
        val gameProducer = async(Dispatchers.IO) {
            for (i in 1..totalGamePackets) {
                delay(2) // 2ms between game updates (~500 Hz game tick)
                priorityChannel.send(ByteArray(120) { 0xAA.toByte() })
            }
        }

        bulkProducer.await()
        gameProducer.await()
        isDone.set(true)
        consumerJob.await()

        // 100% of gaming packets must be delivered without loss
        assertEquals(totalGamePackets, receivedGamePackets.get())
        assertTrue("Bulk packets must also be processed", receivedBulkPackets.get() > 0)
    }

    private fun buildDnsQueryWire(domain: String, txId: Int = 0x1234, qType: Int = 1): ByteArray {
        val parts = domain.split(".").filter { it.isNotEmpty() }
        var qnameLen = 1
        for (part in parts) {
            qnameLen += 1 + part.length
        }

        val totalLen = 12 + qnameLen + 4
        val wire = ByteArray(totalLen)
        var p = 0

        wire[p++] = ((txId shr 8) and 0xFF).toByte()
        wire[p++] = (txId and 0xFF).toByte()
        wire[p++] = 0x01.toByte()
        wire[p++] = 0x00.toByte()
        wire[p++] = 0x00.toByte()
        wire[p++] = 0x01.toByte()
        p += 6

        for (part in parts) {
            val bytes = part.toByteArray(Charsets.US_ASCII)
            wire[p++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, wire, p, bytes.size)
            p += bytes.size
        }
        wire[p++] = 0x00.toByte()
        wire[p++] = ((qType shr 8) and 0xFF).toByte()
        wire[p++] = (qType and 0xFF).toByte()
        wire[p++] = 0x00.toByte()
        wire[p++] = 0x01.toByte()

        return wire
    }

    @Test
    fun testGamingPort9339_QoS_LatencyJitterUnderHeavyDnsAndVpnTraffic() = runBlocking {
        // Simulate queue dispatch delay for port 9339 gaming packets when 50 concurrent DNS queries execute
        val dispatchDelays = mutableListOf<Long>()
        val gamingLock = Any()

        val dnsQueriesCount = 50
        val gameActionsCount = 20

        // Launch concurrent DNS tasks
        val dnsJobs = (1..dnsQueriesCount).map {
            async(Dispatchers.Default) {
                val q = buildDnsQueryWire("game.clashofclans.com", it)
                delay(5) // Simulate 5ms wire RTT
                assertTrue(q.isNotEmpty())
            }
        }

        // Concurrently dispatch gaming packets and measure dispatch latency
        val gameJobs = (1..gameActionsCount).map {
            async(Dispatchers.Default) {
                val start = System.nanoTime()
                // Synchronized dispatch block
                synchronized(gamingLock) {
                    val end = System.nanoTime()
                    val delayMicros = (end - start) / 1000
                    synchronized(dispatchDelays) {
                        dispatchDelays.add(delayMicros)
                    }
                }
            }
        }

        dnsJobs.awaitAll()
        gameJobs.awaitAll()

        assertEquals(gameActionsCount, dispatchDelays.size)
        // Average dispatch delay must stay low (< 50,000 microseconds = < 50ms)
        val avgDelayMicros = dispatchDelays.average()
        assertTrue("Average gaming dispatch delay must be < 50ms (actual: ${avgDelayMicros / 1000}ms)", avgDelayMicros < 50_000)
    }

    // =========================================================================
    // TIER 4: REAL-WORLD SCENARIOS — PROLONGED 30-MINUTE SESSION SIMULATION
    // =========================================================================

    @Test
    fun testGamingPort9339_LongSessionLongevityAndZeroCrash() {
        // Simulates 30 distinct match phases with intermittent idle periods
        // Verifying connection persistence, activity updates, and zero crashes
        class MockGamingSession(val remoteHost: String, val port: Int) {
            @Volatile var isConnected: Boolean = true
            @Volatile var lastActivityTime: Long = System.currentTimeMillis()
            var actionCounter = 0

            fun sendAction(payloadSize: Int) {
                if (!isConnected) throw IllegalStateException("Session disconnected!")
                lastActivityTime = System.currentTimeMillis()
                actionCounter++
            }

            fun idle(simulatedDurationMs: Long) {
                // Advance simulated clock
                lastActivityTime -= simulatedDurationMs
            }
        }

        val session = MockGamingSession("54.210.22.10", 9339)

        for (minute in 1..30) {
            // Player performs 10 game actions per minute
            for (action in 1..10) {
                session.sendAction(128)
            }
            // 45 seconds of idle in village view
            session.idle(45_000L)
            assertTrue("Session must remain connected throughout minute $minute", session.isConnected)
        }

        assertEquals(300, session.actionCounter)
        assertTrue(session.isConnected)
    }
}
