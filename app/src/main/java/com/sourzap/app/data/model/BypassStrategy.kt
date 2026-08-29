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
        val AUTO_PILOT = BypassStrategy(
            id = "auto_pilot",
            name = "⚡ Smart Auto-Pilot",
            description = "Automatically detects whether you are streaming YouTube 4K, chatting on Discord, gaming or browsing, and tunes the connection on the fly.",
            tag = "RECOMMENDED",
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

        val STREAMING_TURBO = BypassStrategy(
            id = "streaming_turbo",
            name = "🎬 4K Streaming & Media",
            description = "Unthrottles YouTube 4K/1080p, Twitch 60fps, Instagram Reels and video CDNs for instant loading without buffering.",
            tag = "STREAMING",
            iconEmoji = "🎬",
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

        val GAMING_VOICE = BypassStrategy(
            id = "gaming_voice",
            name = "🎮 Gaming & Voice RTC",
            description = "Ultra-low latency connection tuned for Discord voice channels, WebSockets, in-game voice chat and multiplayer games.",
            tag = "GAMING",
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

        val STRICT_FIREWALL = BypassStrategy(
            id = "strict_firewall",
            name = "🛡️ Strict Firewall Bypass",
            description = "Deep multi-segment packet fragmentation designed to bypass aggressive censorship, school/office Wi-Fi firewalls and strict ISP filters.",
            tag = "MAX EVASION",
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

        val DEFAULT_PRESETS = listOf(
            AUTO_PILOT,
            STREAMING_TURBO,
            GAMING_VOICE,
            STRICT_FIREWALL
        )
    }
}