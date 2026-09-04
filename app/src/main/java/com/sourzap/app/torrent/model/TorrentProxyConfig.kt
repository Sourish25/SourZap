package com.sourzap.app.torrent.model

enum class ProxyType(val value: Int, val displayName: String) {
    NONE(0, "Direct (No Proxy)"),
    SOCKS5(2, "SOCKS5"),
    HTTP(4, "HTTP")
}

data class TorrentProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.SOCKS5,
    val host: String = "127.0.0.1",
    val port: Int = 9050,
    val username: String = "",
    val password: String = "",
    val proxyPeers: Boolean = true,
    val proxyTrackers: Boolean = true,
    val proxyHostnames: Boolean = true,
    val isSnowflakePreset: Boolean = false
) {
    companion object {
        val DEFAULT = TorrentProxyConfig()

        val SNOWFLAKE_ORBOT = TorrentProxyConfig(
            enabled = true,
            type = ProxyType.SOCKS5,
            host = "127.0.0.1",
            port = 9050,
            proxyPeers = true,
            proxyTrackers = true,
            proxyHostnames = true,
            isSnowflakePreset = true
        )
    }
}
