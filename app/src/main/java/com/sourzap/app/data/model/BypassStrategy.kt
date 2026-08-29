package com.sourzap.app.data.model

data class BypassStrategy(
    val id: String,
    val name: String,
    val description: String,
    val tag: String,
    val iconEmoji: String,
    val tlsSplitOffset: Int = -1, // -1 means split at SNI start position
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
        val YOUTUBE_TURBO = BypassStrategy(
            id = "youtube_turbo",
            name = "YouTube Turbo Fix",
            description = "Optimized for YouTube 4K/1080p unthrottling with TLS SNI boundary splitting and QUIC drop",
            tag = "YOUTUBE",
            iconEmoji = "⚡",
            tlsSplitOffset = -1,
            useMultisplit = false,
            fakeSni = "",
            fakeTtl = 3,
            useDisorder = false,
            useOob = false,
            httpHostMod = true,
            blockQuic = true,
            dohProvider = DohProvider.CLOUDFLARE
        )

        val DISCORD_FIX = BypassStrategy(
            id = "discord_fix",
            name = "Discord & RTC Fix",
            description = "Unblocks Discord Gateway, WebSockets, API calls and voice RTC streams with TLS Split at Offset 2",
            tag = "DISCORD",
            iconEmoji = "🎮",
            tlsSplitOffset = 2,
            useMultisplit = false,
            fakeSni = "",
            fakeTtl = 4,
            useDisorder = false,
            useOob = false,
            httpHostMod = false,
            blockQuic = true,
            dohProvider = DohProvider.CLOUDFLARE
        )

        val UNIVERSAL_DPI = BypassStrategy(
            id = "universal_dpi",
            name = "Universal DPI Bypass",
            description = "General-purpose anti-censorship preset using micro-multisplit and HTTP Host casing desync",
            tag = "UNIVERSAL",
            iconEmoji = "🛡️",
            tlsSplitOffset = 1,
            useMultisplit = true,
            fakeSni = "",
            fakeTtl = 3,
            useDisorder = false,
            useOob = false,
            httpHostMod = true,
            blockQuic = true,
            dohProvider = DohProvider.GOOGLE
        )

        val AGGRESSIVE_CENSOR = BypassStrategy(
            id = "aggressive_censor",
            name = "Aggressive Anti-Censor",
            description = "Multi-segment TLS fragmentation, 3-way packet micro-splitting, and Quad9 DoH for heavily filtered networks",
            tag = "AGGRESSIVE",
            iconEmoji = "🔥",
            tlsSplitOffset = 2,
            useMultisplit = true,
            fakeSni = "",
            fakeTtl = 2,
            useDisorder = false,
            useOob = false,
            httpHostMod = true,
            blockQuic = true,
            dohProvider = DohProvider.QUAD9
        )

        val DEFAULT_PRESETS = listOf(
            YOUTUBE_TURBO,
            DISCORD_FIX,
            UNIVERSAL_DPI,
            AGGRESSIVE_CENSOR
        )
    }
}