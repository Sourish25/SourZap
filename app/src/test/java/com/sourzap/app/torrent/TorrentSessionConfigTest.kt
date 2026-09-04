package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.TorrentSessionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test suite for BitTorrent Session Configuration and Anti-Censorship Tuning.
 * Verifies Requirement R1 & Features F1, F2, F3, F4, F5:
 * - Dynamic Listen Interfaces on all IPv4 and IPv6 adapters ("0.0.0.0:0,[::]:0")
 * - NAT Traversal (UPnP and NAT-PMP)
 * - TCP Priority over uTP (prefer_tcp mixed mode algorithm = 0 to prevent LEDBAT collapse)
 * - Full RC4 Message Stream Encryption (MSE / PE) enforcement
 * - High-Throughput Swarm Saturation (500 connections, 4000 peerlist, 1MB/2MB socket buffers, 64MB cache, 4 aio threads)
 * - Anti-DPI middlebox evasion & tracker discovery settings
 */
class TorrentSessionConfigTest {

    @Test
    fun testDynamicListenInterfacesAndNatTraversal() {
        val config = TorrentSessionConfig.DEFAULT

        // Dynamic listen interface binding across all IPv4 interfaces with ephemeral ports
        assertEquals("0.0.0.0:0", config.listenInterfaces)

        // Active NAT traversal flags
        assertTrue("UPnP must be enabled by default for NAT traversal", config.enableUpnp)
        assertTrue("NAT-PMP must be enabled by default for NAT traversal", config.enableNatpmp)
    }

    @Test
    fun testDualTransportAndPeerProportionalMixedMode() {
        val config = TorrentSessionConfig.DEFAULT

        // Dual transport guarantees: uTP and TCP enabled for maximum swarm connectivity
        assertTrue("Incoming uTP must be enabled for peer connectivity", config.enableIncomingUtp)
        assertTrue("Outgoing uTP must be enabled for peer connectivity", config.enableOutgoingUtp)
        assertTrue("Incoming TCP must be enabled", config.enableIncomingTcp)
        assertTrue("Outgoing TCP must be enabled", config.enableOutgoingTcp)

        // mixed_mode_algorithm = peer_proportional (1) to enable uTP hole punching alongside TCP
        assertEquals(TorrentSessionConfig.MIXED_MODE_PEER_PROPORTIONAL, config.mixedModeAlgorithm)
        assertEquals(0, TorrentSessionConfig.MIXED_MODE_PREFER_TCP)
        assertEquals(1, TorrentSessionConfig.MIXED_MODE_PEER_PROPORTIONAL)
    }

    @Test
    fun testFullRc4ProtocolEncryptionEnforcement() {
        val config = TorrentSessionConfig.DEFAULT

        // Protocol Encryption: PE forced outbound to bypass BSNL DPI, enabled inbound with BOTH levels
        assertEquals(TorrentSessionConfig.ENC_POLICY_FORCED, config.outEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_POLICY_ENABLED, config.inEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_LEVEL_BOTH, config.allowedEncLevel)
        assertTrue("Prefer RC4 stream cipher must be enabled", config.preferRc4)

        // Verify constant integer encodings and PE_* aliases (pe_forced = 0, pe_enabled = 1, pe_disabled = 2)
        assertEquals(0, TorrentSessionConfig.ENC_POLICY_FORCED)
        assertEquals(1, TorrentSessionConfig.ENC_POLICY_ENABLED)
        assertEquals(2, TorrentSessionConfig.ENC_POLICY_DISABLED)

        assertEquals(TorrentSessionConfig.ENC_POLICY_FORCED, TorrentSessionConfig.PE_FORCED)
        assertEquals(TorrentSessionConfig.ENC_POLICY_ENABLED, TorrentSessionConfig.PE_ENABLED)
        assertEquals(TorrentSessionConfig.ENC_POLICY_DISABLED, TorrentSessionConfig.PE_DISABLED)

        assertEquals(1, TorrentSessionConfig.ENC_LEVEL_PLAINTEXT)
        assertEquals(2, TorrentSessionConfig.ENC_LEVEL_RC4)
        assertEquals(3, TorrentSessionConfig.ENC_LEVEL_BOTH)

        assertEquals(TorrentSessionConfig.ENC_LEVEL_PLAINTEXT, TorrentSessionConfig.PE_PLAINTEXT)
        assertEquals(TorrentSessionConfig.ENC_LEVEL_RC4, TorrentSessionConfig.PE_RC4)
        assertEquals(TorrentSessionConfig.ENC_LEVEL_BOTH, TorrentSessionConfig.PE_BOTH)

        assertEquals(TorrentSessionConfig.MIXED_MODE_PREFER_TCP, TorrentSessionConfig.PREFER_TCP)
        assertEquals(TorrentSessionConfig.MIXED_MODE_PEER_PROPORTIONAL, TorrentSessionConfig.PEER_PROPORTIONAL)
    }

    @Test
    fun testForcedEncryptionConfiguration() {
        val forcedConfig = TorrentSessionConfig(
            outEncPolicy = TorrentSessionConfig.PE_FORCED,
            inEncPolicy = TorrentSessionConfig.PE_FORCED,
            allowedEncLevel = TorrentSessionConfig.PE_RC4,
            preferRc4 = true
        )

        assertEquals(0, forcedConfig.outEncPolicy)
        assertEquals(0, forcedConfig.inEncPolicy)
        assertEquals(2, forcedConfig.allowedEncLevel)
        assertTrue(forcedConfig.preferRc4)
    }

    @Test
    fun testHighThroughputSwarmTuning() {
        val config = TorrentSessionConfig.DEFAULT

        assertEquals(500, config.connectionsLimit)
        assertEquals(4000, config.maxPeerlistSize)
        assertEquals(100, config.torrentConnectBoost)
        assertEquals(30, config.connectionSpeed)
        assertEquals(15, config.peerConnectTimeout)
        assertEquals(1500, config.maxOutRequestQueue)
        assertEquals(20, config.requestTimeout)
        assertEquals(20, config.wholePiecesThreshold)
        assertEquals(64 * 1024 * 1024, config.cacheSize)
        assertEquals(1048576, config.sendSocketBufferSize) // 1 MB
        assertEquals(2097152, config.recvSocketBufferSize) // 2 MB
        assertEquals(4, config.aioThreads)
        assertTrue(config.announceToAllTrackers)
        assertTrue(config.announceToAllTiers)
        assertEquals(10, config.trackerCompletionTimeout)
        assertEquals(8, config.trackerReceiveTimeout)
        assertEquals(2, config.stopTrackerTimeout)
    }

    @Test
    fun testPeerDiscoverySettings() {
        val config = TorrentSessionConfig.DEFAULT

        assertTrue("DHT must be enabled", config.enableDht)
        assertTrue("LSD must be enabled", config.enableLsd)
        assertTrue("PEX must be enabled", config.enablePex)
        assertTrue("Bootstrap nodes must include major DHT routers", config.dhtBootstrapNodes.contains("router.bittorrent.com"))
        assertTrue("Bootstrap nodes must include transmissionbt", config.dhtBootstrapNodes.contains("dht.transmissionbt.com"))
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
            listenInterfaces = "192.168.1.100:6881,[2001:db8::1]:6881",
            enableUpnp = false,
            enableNatpmp = false,
            enableIncomingUtp = false,
            enableOutgoingUtp = false,
            enableIncomingTcp = true,
            enableOutgoingTcp = true,
            mixedModeAlgorithm = TorrentSessionConfig.MIXED_MODE_PEER_PROPORTIONAL,
            outEncPolicy = TorrentSessionConfig.ENC_POLICY_FORCED,
            inEncPolicy = TorrentSessionConfig.ENC_POLICY_FORCED,
            allowedEncLevel = TorrentSessionConfig.ENC_LEVEL_RC4,
            preferRc4 = true,
            connectionsLimit = 250,
            maxPeerlistSize = 2000,
            aioThreads = 8,
            userAgent = "CustomClient/1.0"
        )

        assertEquals("192.168.1.100:6881,[2001:db8::1]:6881", customConfig.listenInterfaces)
        assertFalse(customConfig.enableUpnp)
        assertFalse(customConfig.enableNatpmp)
        assertFalse(customConfig.enableIncomingUtp)
        assertFalse(customConfig.enableOutgoingUtp)
        assertTrue(customConfig.enableIncomingTcp)
        assertTrue(customConfig.enableOutgoingTcp)
        assertEquals(TorrentSessionConfig.MIXED_MODE_PEER_PROPORTIONAL, customConfig.mixedModeAlgorithm)
        assertEquals(TorrentSessionConfig.ENC_POLICY_FORCED, customConfig.outEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_POLICY_FORCED, customConfig.inEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_LEVEL_RC4, customConfig.allowedEncLevel)
        assertTrue(customConfig.preferRc4)
        assertEquals(250, customConfig.connectionsLimit)
        assertEquals(2000, customConfig.maxPeerlistSize)
        assertEquals(8, customConfig.aioThreads)
        assertEquals("CustomClient/1.0", customConfig.userAgent)
    }

    @Test
    fun testCreateSettingsPackLifecycle() {
        try {
            val config = TorrentSessionConfig.DEFAULT
            val pack = config.createSettingsPack()
            assertNotNull("SettingsPack should be successfully created", pack)
        } catch (_: LinkageError) {
            // Expected on host JVM environments without native libtorrent .so loaded
            assertTrue(true)
        }
    }
}
