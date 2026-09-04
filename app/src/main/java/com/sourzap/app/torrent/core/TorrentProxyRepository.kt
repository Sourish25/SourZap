package com.sourzap.app.torrent.core

import android.content.Context
import android.content.SharedPreferences
import com.sourzap.app.torrent.model.ProxyType
import com.sourzap.app.torrent.model.TorrentProxyConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TorrentProxyRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<TorrentProxyConfig> = _config.asStateFlow()

    fun saveConfig(newConfig: TorrentProxyConfig) {
        prefs.edit().apply {
            putBoolean(KEY_ENABLED, newConfig.enabled)
            putString(KEY_TYPE, newConfig.type.name)
            putString(KEY_HOST, newConfig.host.trim())
            putInt(KEY_PORT, newConfig.port)
            putString(KEY_USERNAME, newConfig.username.trim())
            putString(KEY_PASSWORD, newConfig.password)
            putBoolean(KEY_PROXY_PEERS, newConfig.proxyPeers)
            putBoolean(KEY_PROXY_TRACKERS, newConfig.proxyTrackers)
            putBoolean(KEY_PROXY_HOSTNAMES, newConfig.proxyHostnames)
            apply()
        }
        _config.value = newConfig
    }

    private fun loadConfig(): TorrentProxyConfig {
        val typeStr = prefs.getString(KEY_TYPE, ProxyType.SOCKS5.name) ?: ProxyType.SOCKS5.name
        val type = try {
            ProxyType.valueOf(typeStr)
        } catch (_: Exception) {
            ProxyType.SOCKS5
        }

        return TorrentProxyConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            type = type,
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PORT, 1080),
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            proxyPeers = prefs.getBoolean(KEY_PROXY_PEERS, true),
            proxyTrackers = prefs.getBoolean(KEY_PROXY_TRACKERS, true),
            proxyHostnames = prefs.getBoolean(KEY_PROXY_HOSTNAMES, true)
        )
    }

    companion object {
        private const val PREFS_NAME = "sourzap_torrent_proxy_prefs"
        private const val KEY_ENABLED = "proxy_enabled"
        private const val KEY_TYPE = "proxy_type"
        private const val KEY_HOST = "proxy_host"
        private const val KEY_PORT = "proxy_port"
        private const val KEY_USERNAME = "proxy_username"
        private const val KEY_PASSWORD = "proxy_password"
        private const val KEY_PROXY_PEERS = "proxy_peers"
        private const val KEY_PROXY_TRACKERS = "proxy_trackers"
        private const val KEY_PROXY_HOSTNAMES = "proxy_hostnames"
    }
}
