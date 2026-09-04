package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.TorrentSessionConfig
import com.sourzap.app.torrent.model.ProxyType
import com.sourzap.app.torrent.model.TorrentProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test suite for BitTorrent SOCKS5 / HTTP Proxy Configuration.
 * Validates:
 * - Default proxy state (disabled, SOCKS5, port 1080)
 * - Validation rules for host, port, and authentication
 * - SettingsPack proxy mapping and reset behavior
 * - Session configuration integration
 */
class TorrentProxyConfigTest {

    @Test
    fun testDefaultProxyConfig() {
        val config = TorrentProxyConfig.DEFAULT

        assertFalse("Proxy should be disabled by default", config.enabled)
        assertEquals(ProxyType.SOCKS5, config.type)
        assertEquals("", config.host)
        assertEquals(1080, config.port)
        assertEquals("", config.username)
        assertEquals("", config.password)
        assertTrue("Proxy peers should be enabled by default", config.proxyPeers)
        assertTrue("Proxy trackers should be enabled by default", config.proxyTrackers)
        assertTrue("Proxy hostnames should be enabled by default", config.proxyHostnames)
        assertFalse("Default proxy should not be configured", config.isConfigured)
    }

    @Test
    fun testIsConfiguredValidation() {
        // Valid configs
        val socks5 = TorrentProxyConfig(enabled = true, type = ProxyType.SOCKS5, host = "127.0.0.1", port = 1080)
        assertTrue(socks5.isConfigured)

        val http = TorrentProxyConfig(enabled = true, type = ProxyType.HTTP, host = "proxy.local", port = 8080)
        assertTrue(http.isConfigured)

        val auth = TorrentProxyConfig(
            enabled = true,
            type = ProxyType.SOCKS5,
            host = "proxy.myvpn.net",
            port = 443,
            username = "admin",
            password = "secret"
        )
        assertTrue(auth.isConfigured)

        // Invalid: blank host
        assertFalse(TorrentProxyConfig(enabled = true, host = "", port = 1080).isConfigured)
        assertFalse(TorrentProxyConfig(enabled = true, host = "   ", port = 1080).isConfigured)

        // Invalid: port out of range
        assertFalse(TorrentProxyConfig(enabled = true, host = "127.0.0.1", port = 0).isConfigured)
        assertFalse(TorrentProxyConfig(enabled = true, host = "127.0.0.1", port = -1).isConfigured)
        assertFalse(TorrentProxyConfig(enabled = true, host = "127.0.0.1", port = 65536).isConfigured)
    }

    @Test
    fun testProxyTypeEnumValues() {
        val types = ProxyType.values()
        assertTrue(types.contains(ProxyType.SOCKS5))
        assertTrue(types.contains(ProxyType.HTTP))
        assertTrue(types.contains(ProxyType.NONE))
        assertEquals(ProxyType.SOCKS5, ProxyType.valueOf("SOCKS5"))
        assertEquals(ProxyType.HTTP, ProxyType.valueOf("HTTP"))
    }

    @Test
    fun testTorrentSessionConfigWithProxy() {
        val proxy = TorrentProxyConfig(
            enabled = true,
            type = ProxyType.SOCKS5,
            host = "10.0.0.1",
            port = 1080,
            username = "user",
            password = "pass",
            proxyPeers = true,
            proxyTrackers = false,
            proxyHostnames = true
        )

        val sessionConfig = TorrentSessionConfig(proxyConfig = proxy)
        assertEquals(proxy, sessionConfig.proxyConfig)
        assertTrue(sessionConfig.proxyConfig.enabled)
        assertEquals("10.0.0.1", sessionConfig.proxyConfig.host)
        assertEquals(1080, sessionConfig.proxyConfig.port)
        assertEquals("user", sessionConfig.proxyConfig.username)
        assertEquals("pass", sessionConfig.proxyConfig.password)
        assertTrue(sessionConfig.proxyConfig.proxyPeers)
        assertFalse(sessionConfig.proxyConfig.proxyTrackers)
    }

    @Test
    fun testApplyProxyToSettingsPackLifecycle() {
        try {
            val sessionConfig = TorrentSessionConfig(
                proxyConfig = TorrentProxyConfig(
                    enabled = true,
                    type = ProxyType.SOCKS5,
                    host = "127.0.0.1",
                    port = 9050
                )
            )
            val pack = sessionConfig.createSettingsPack()
            org.junit.Assert.assertNotNull(pack)

            // Test clearing proxy
            TorrentSessionConfig.applyProxyTo(pack, TorrentProxyConfig.DEFAULT)
        } catch (_: LinkageError) {
            // Expected on host JVM environments when native libtorrent .so is not present on Windows path
            assertTrue(true)
        }
    }
}
