package com.sourzap.app.torrent.model

enum class ProxyType {
    SOCKS5,
    HTTP,
    NONE
}

data class TorrentProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.SOCKS5,
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val proxyPeers: Boolean = true,
    val proxyTrackers: Boolean = true,
    val proxyHostnames: Boolean = true
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && port in 1..65535

    companion object {
        val DEFAULT = TorrentProxyConfig()
    }
}
