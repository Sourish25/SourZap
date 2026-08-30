package com.sourzap.app.data.model

data class BypassStrategy(
    val id: String = "universal_engine",
    val name: String = "Universal Smart Engine",
    val description: String = "Universal zero-configuration DPI evasion engine automatically optimized for streaming, browsing, gaming, and P2P BitTorrent.",
    val tlsSplitOffset: Int = 2,
    val useMultisplit: Boolean = false,
    val fakeSni: String = "",
    val fakeTtl: Int = 3,
    val useDisorder: Boolean = false,
    val useOob: Boolean = false,
    val httpHostMod: Boolean = true,
    val blockQuic: Boolean = true,
    val dohProvider: DohProvider = DohProvider.CLOUDFLARE,
    val isCustom: Boolean = false
) {
    companion object {
        val AUTO_PILOT = BypassStrategy(
            id = "auto_pilot",
            name = "Universal Smart Engine",
            description = "Universal zero-configuration DPI evasion engine automatically optimized for streaming, browsing, gaming, and P2P BitTorrent.",
            tlsSplitOffset = 2,
            useMultisplit = false,
            fakeSni = "",
            fakeTtl = 3,
            useDisorder = false,
            useOob = false,
            httpHostMod = true,
            blockQuic = true,
            dohProvider = DohProvider.CLOUDFLARE
        )

        val STREAMING_TURBO = AUTO_PILOT
        val GAMING_VOICE = AUTO_PILOT
        val STRICT_FIREWALL = AUTO_PILOT
        val DEFAULT_PRESETS = listOf(AUTO_PILOT)
    }
}