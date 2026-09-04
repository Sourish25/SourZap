package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.TorrentSessionConfig
import com.sourzap.app.torrent.model.ProxyType
import com.sourzap.app.torrent.model.TorrentProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentProxyTest {

    @Test
    fun defaultProxyConfig_isDisabled() {
        val config = TorrentProxyConfig.DEFAULT
        assertFalse(config.enabled)
        assertEquals(ProxyType.SOCKS5, config.type)
        assertEquals("127.0.0.1", config.host)
        assertEquals(9050, config.port)
        assertTrue(config.proxyPeers)
        assertTrue(config.proxyTrackers)
        assertTrue(config.proxyHostnames)
        assertFalse(config.isSnowflakePreset)
    }

    @Test
    fun snowflakeOrbotPreset_isProperlyConfigured() {
        val config = TorrentProxyConfig.SNOWFLAKE_ORBOT
        assertTrue(config.enabled)
        assertEquals(ProxyType.SOCKS5, config.type)
        assertEquals("127.0.0.1", config.host)
        assertEquals(9050, config.port)
        assertTrue(config.proxyPeers)
        assertTrue(config.proxyTrackers)
        assertTrue(config.proxyHostnames)
        assertTrue(config.isSnowflakePreset)
    }

    @Test
    fun customProxyConfig_preservesCustomFields() {
        val config = TorrentProxyConfig(
            enabled = true,
            type = ProxyType.HTTP,
            host = "proxy.example.com",
            port = 8080,
            username = "user",
            password = "pwd",
            proxyPeers = false,
            proxyTrackers = true,
            proxyHostnames = false,
            isSnowflakePreset = false
        )
        assertTrue(config.enabled)
        assertEquals(ProxyType.HTTP, config.type)
        assertEquals("proxy.example.com", config.host)
        assertEquals(8080, config.port)
        assertEquals("user", config.username)
        assertEquals("pwd", config.password)
        assertFalse(config.proxyPeers)
        assertTrue(config.proxyTrackers)
        assertFalse(config.proxyHostnames)
        assertFalse(config.isSnowflakePreset)
    }

    @Test
    fun sessionConfig_containsDefaultProxyConfig() {
        val sessionConfig = TorrentSessionConfig()
        assertEquals(TorrentProxyConfig.DEFAULT, sessionConfig.proxyConfig)
    }

    @Test
    fun sessionConfig_withSnowflakeProxyConfig_preservesConfiguration() {
        val sessionConfig = TorrentSessionConfig(proxyConfig = TorrentProxyConfig.SNOWFLAKE_ORBOT)
        assertTrue(sessionConfig.proxyConfig.enabled)
        assertTrue(sessionConfig.proxyConfig.isSnowflakePreset)
        assertEquals(9050, sessionConfig.proxyConfig.port)
    }
}
