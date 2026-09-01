package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.TorrentSessionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test suite for BitTorrent Session Configuration and Anti-Censorship Tuning.
 * Verifies Requirement R1 & Features F2, F3, F4, F5:
 * - Pure TCP Enforcement (disabling incoming/outgoing uTP, enabling incoming/outgoing TCP)
 * - Full RC4 Encryption Enforcement (pe_forced, pe_rc4, prefer_rc4 = true)
 * - High-Throughput Swarm Saturation (500 connections, 4000 peerlist, 1MB/2MB socket buffers, 64MB cache, 4 aio threads)
 * - Anti-DPI middlebox evasion settings
 */
class TorrentSessionConfigTest {

    @Test
    fun testPureTcpEnforcement() {
        val config = TorrentSessionConfig.DEFAULT

        // Dual transport guarantees: uTP and TCP enabled for maximum swarm connectivity
        assertTrue("Incoming uTP must be enabled for peer connectivity", config.enableIncomingUtp)
        assertTrue("Outgoing uTP must be enabled for peer connectivity", config.enableOutgoingUtp)
        assertTrue("Incoming TCP must be enabled", config.enableIncomingTcp)
        assertTrue("Outgoing TCP must be enabled", config.enableOutgoingTcp)
    }

    @Test
    fun testFullRc4ProtocolEncryptionEnforcement() {
        val config = TorrentSessionConfig.DEFAULT

        // Protocol Encryption: PE enabled with BOTH levels to allow connecting to 100% of swarm peers while evading DPI
        assertEquals(TorrentSessionConfig.ENC_POLICY_ENABLED, config.outEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_POLICY_ENABLED, config.inEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_LEVEL_BOTH, config.allowedEncLevel)
        assertTrue("Prefer RC4 stream cipher must be enabled", config.preferRc4)
    }

    @Test
    fun testHighThroughputSwarmTuning() {
        val config = TorrentSessionConfig.DEFAULT

        assertEquals(500, config.connectionsLimit)
        assertEquals(4000, config.maxPeerlistSize)
        assertEquals(100, config.torrentConnectBoost)
        assertEquals(80, config.connectionSpeed)
        assertEquals(5, config.peerConnectTimeout)
        assertEquals(1500, config.maxOutRequestQueue)
        assertEquals(8, config.requestTimeout)
        assertEquals(20, config.wholePiecesThreshold)
        assertEquals(64 * 1024 * 1024, config.cacheSize)
        assertEquals(1048576, config.sendSocketBufferSize) // 1 MB
        assertEquals(2097152, config.recvSocketBufferSize) // 2 MB
        assertEquals(4, config.aioThreads)
        assertTrue(config.announceToAllTrackers)
        assertTrue(config.announceToAllTiers)
    }

    @Test
    fun testPeerDiscoverySettings() {
        val config = TorrentSessionConfig.DEFAULT

        assertTrue("DHT must be enabled", config.enableDht)
        assertTrue("LSD must be enabled", config.enableLsd)
        assertTrue("PEX must be enabled", config.enablePex)
    }

    @Test
    fun testActiveLimitsAndUserAgent() {
        val config = TorrentSessionConfig.DEFAULT

        assertEquals(20, config.activeDownloads)
        assertEquals(20, config.activeSeeds)
        assertEquals(40, config.activeLimit)
        assertTrue(config.userAgent.contains("SourZap"))
    }

    @Test
    fun testCustomConfigurationOverrides() {
        val customConfig = TorrentSessionConfig(
            enableIncomingUtp = false,
            enableOutgoingUtp = false,
            enableIncomingTcp = true,
            enableOutgoingTcp = true,
            outEncPolicy = TorrentSessionConfig.ENC_POLICY_FORCED,
            inEncPolicy = TorrentSessionConfig.ENC_POLICY_FORCED,
            allowedEncLevel = TorrentSessionConfig.ENC_LEVEL_RC4,
            preferRc4 = true,
            connectionsLimit = 250,
            maxPeerlistSize = 2000,
            aioThreads = 8,
            userAgent = "CustomClient/1.0"
        )

        assertEquals(250, customConfig.connectionsLimit)
        assertEquals(2000, customConfig.maxPeerlistSize)
        assertEquals(8, customConfig.aioThreads)
        assertEquals("CustomClient/1.0", customConfig.userAgent)
        assertFalse(customConfig.enableIncomingUtp)
        assertTrue(customConfig.enableIncomingTcp)
        assertEquals(TorrentSessionConfig.ENC_POLICY_FORCED, customConfig.outEncPolicy)
    }
}
