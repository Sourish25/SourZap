package com.sourzap.app.torrent.core

import android.content.Context
import android.content.SharedPreferences
import com.sourzap.app.torrent.model.ProxyType
import com.sourzap.app.torrent.model.TorrentProxyConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TorrentProxyRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("torrent_proxy_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<TorrentProxyConfig> = _config.asStateFlow()

    private fun loadConfig(): TorrentProxyConfig {
        val enabled = prefs.getBoolean("proxy_enabled", false)
        val typeStr = prefs.getString("proxy_type", "SOCKS5") ?: "SOCKS5"
        val type = try { ProxyType.valueOf(typeStr) } catch (_: Exception) { ProxyType.SOCKS5 }
        val host = prefs.getString("proxy_host", "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt("proxy_port", 9050)
        val username = prefs.getString("proxy_username", "") ?: ""
        val password = prefs.getString("proxy_password", "") ?: ""
        val peers = prefs.getBoolean("proxy_peers", true)
        val trackers = prefs.getBoolean("proxy_trackers", true)
        val hostnames = prefs.getBoolean("proxy_hostnames", true)
        val isSnowflake = prefs.getBoolean("is_snowflake_preset", false)

        return TorrentProxyConfig(
            enabled = enabled,
            type = type,
            host = host,
            port = port,
            username = username,
            password = password,
            proxyPeers = peers,
            proxyTrackers = trackers,
            proxyHostnames = hostnames,
            isSnowflakePreset = isSnowflake
        )
    }

    fun saveConfig(newConfig: TorrentProxyConfig) {
        prefs.edit()
            .putBoolean("proxy_enabled", newConfig.enabled)
            .putString("proxy_type", newConfig.type.name)
            .putString("proxy_host", newConfig.host.trim())
            .putInt("proxy_port", newConfig.port)
            .putString("proxy_username", newConfig.username)
            .putString("proxy_password", newConfig.password)
            .putBoolean("proxy_peers", newConfig.proxyPeers)
            .putBoolean("proxy_trackers", newConfig.proxyTrackers)
            .putBoolean("proxy_hostnames", newConfig.proxyHostnames)
            .putBoolean("is_snowflake_preset", newConfig.isSnowflakePreset)
            .apply()
        _config.value = newConfig
    }

    fun enableSnowflakePreset() {
        saveConfig(TorrentProxyConfig.SNOWFLAKE_ORBOT)
    }

    fun disableProxy() {
        val current = _config.value
        saveConfig(current.copy(enabled = false, isSnowflakePreset = false))
    }
}
