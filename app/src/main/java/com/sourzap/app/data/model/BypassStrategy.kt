package com.sourzap.app.data.model

data class BypassStrategy(
    val id: String,
    val name: String,
    val description: String,
    val tag: String,
    val iconEmoji: String,
    val tlsSplitOffset: Int = -1, // -1 means split at SNI start position
    val useMultisplit: Boolean = false,
    val fakeSni: String = "www.google.com",
    val fakeTtl: Int = 3,
    val useDisorder: Boolean = true,
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
            description = "Optimized for YouTube 4K/1080p unthrottling with Fake SNI, SNI splitting & QUIC blocking",
            tag = "YOUTUBE",
            iconEmoji = "⚡",
            tlsSplitOffset = -1,
            useMultisplit = true,
            fakeSni = "www.google.com",
            fakeTtl = 3,
            useDisorder = true,
            useOob = false,
            httpHostMod = true,
            blockQuic = true,
            dohProvider = DohProvider.CLOUDFLARE
        )

        val DISCORD_FIX = BypassStrategy(
            id = "discord_fix",
            name = "Discord & RTC Fix",
            description = "Unblocks Discord Gateway, WebSockets, API calls and voice RTC streams with TLS Split2 + Disorder",
            tag = "DISCORD",
            iconEmoji = "🎮",
            tlsSplitOffset = 2,
            useMultisplit = false,
            fakeSni = "discord.com",
            fakeTtl = 4,
            useDisorder = true,
            useOob = false,
            httpHostMod = false,
            blockQuic = true,
            dohProvider = DohProvider.CLOUDFLARE
        )

        val UNIVERSAL_DPI = BypassStrategy(
            id = "universal_dpi",
            name = "Universal DPI Bypass",
            description = "General-purpose anti-censorship preset using multisplit, low TTL fake packets, and HTTP Host casing",
            tag = "UNIVERSAL",
            iconEmoji = "🛡️",
            tlsSplitOffset = 1,
            useMultisplit = true,
            fakeSni = "cloudflare.com",
            fakeTtl = 3,
            useDisorder = true,
            useOob = false,
            httpHostMod = true,
            blockQuic = true,
            dohProvider = DohProvider.GOOGLE
        )

        val AGGRESSIVE_CENSOR = BypassStrategy(
            id = "aggressive_censor",
            name = "Aggressive Anti-Censor",
            description = "Multi-segment TLS fragmentation, OOB flags, fake payloads, and Quad9 DoH for heavily filtered networks",
            tag = "AGGRESSIVE",
            iconEmoji = "🔥",
            tlsSplitOffset = 2,
            useMultisplit = true,
            fakeSni = "microsoft.com",
            fakeTtl = 2,
            useDisorder = true,
            useOob = true,
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