package com.sourzap.app.service.core

import com.sourzap.app.data.model.BypassStrategy
import java.io.OutputStream
import java.net.Socket

object DpiEngine {

    /**
     * Applies Zapret DPI circumvention techniques on the initial TLS/HTTP handshake stream.
     * In Auto-Pilot mode, dynamically detects destination domain and tunes packet desync on the fly.
     */
    fun desyncAndSend(
        socket: Socket,
        outputStream: OutputStream,
        payload: ByteArray,
        length: Int,
        strategy: BypassStrategy,
        onTechniqueApplied: (String) -> Unit
    ) {
        try {
            socket.tcpNoDelay = true

            val sniResult = TlsParser.parseClientHello(payload, length)
            if (sniResult.isClientHello) {
                applyTlsDesync(outputStream, payload, length, strategy, sniResult, onTechniqueApplied)
                return
            }

            val httpResult = HttpParser.parseHttpRequest(payload, length)
            if (httpResult.isHttp) {
                applyHttpDesync(outputStream, payload, length, strategy, onTechniqueApplied)
                return
            }

            // Passthrough for non-TLS/HTTP
            outputStream.write(payload, 0, length)
            outputStream.flush()
            onTechniqueApplied("PASSTHROUGH")
        } catch (e: Exception) {
            outputStream.write(payload, 0, length)
            outputStream.flush()
            onTechniqueApplied("DIRECT_FALLBACK")
        }
    }

    private fun applyTlsDesync(
        outputStream: OutputStream,
        payload: ByteArray,
        length: Int,
        strategy: BypassStrategy,
        sniResult: TlsParser.SniResult,
        onTechniqueApplied: (String) -> Unit
    ) {
        val hostname = (sniResult.hostname ?: "").lowercase()

        // 1. Clean Passthrough for Google Search, Play Services, and Cloud infrastructure
        // to prevent Google Frontend bot-detection / "Unusual Traffic" CAPTCHA
        val isGoogleSearchOrInfra = (hostname.startsWith("www.google.") || hostname == "google.com" ||
                hostname.endsWith(".google.com") || hostname.endsWith(".google.co.in") ||
                hostname.contains("gstatic.com") || hostname.contains("googleapis.com") ||
                hostname.contains("accounts.google") || hostname.contains("play.google") ||
                hostname.contains("cloudflare.com") || hostname.contains("apple.com") ||
                hostname.contains("microsoft.com")) &&
                !hostname.contains("youtube") && !hostname.contains("googlevideo") && !hostname.contains("ytimg")

        if (strategy.id == "auto_pilot" && isGoogleSearchOrInfra) {
            outputStream.write(payload, 0, length)
            outputStream.flush()
            onTechniqueApplied("CLEAN_PASSTHROUGH")
            return
        }

        // 2. Auto-Pilot Dynamic Intelligence: Inspect domain target
        val isStreamingMedia = hostname.contains("googlevideo") || hostname.contains("youtube") ||
                hostname.contains("ytimg") || hostname.contains("twitch") ||
                hostname.contains("netflix") || hostname.contains("instagram") ||
                hostname.contains("fbcdn") || hostname.contains("tiktok") ||
                hostname.contains("twitter") || hostname.contains("x.com") ||
                hostname.contains("reddit")

        val effectiveStrategy = if (strategy.id == "auto_pilot") {
            when {
                // Streaming & Video Services -> Low-overhead Split 2 for full 4K line speed
                isStreamingMedia -> BypassStrategy.STREAMING_TURBO

                // Voice, Gaming & RTC -> Low-latency Split Offset 2
                hostname.contains("discord") || hostname.contains("gateway") ||
                hostname.contains("voice") || hostname.contains("rtc") ||
                hostname.contains("telegram") || hostname.contains("t.me") -> {
                    BypassStrategy.GAMING_VOICE
                }

                // Strict Censorship / General Blocked Hosts -> Deep Universal Split
                else -> BypassStrategy.STRICT_FIREWALL
            }
        } else {
            strategy
        }

        // Calculate Split Position (Use Split 2 for Video CDNs to maximize hardware buffer throughput)
        val splitPos = if (isStreamingMedia || effectiveStrategy.id == "streaming_turbo" || effectiveStrategy.id == "gaming_voice") {
            2 // Zapret standard split2: splits TLS Record Header (0x16 0x03 | 0x01 ...) with 0ms buffering
        } else when {
            effectiveStrategy.tlsSplitOffset == -1 && sniResult.sniExtensionOffset > 5 -> {
                sniResult.sniExtensionOffset.coerceIn(2, length - 2)
            }
            effectiveStrategy.tlsSplitOffset > 0 -> {
                effectiveStrategy.tlsSplitOffset.coerceIn(1, length - 1)
            }
            else -> {
                if (sniResult.sniExtensionOffset > 0) sniResult.sniExtensionOffset.coerceIn(2, length - 2) else 2
            }
        }

        if (effectiveStrategy.useMultisplit && length > 12 && !isStreamingMedia) {
            val p1 = 5.coerceAtMost(splitPos - 1)
            val p2 = splitPos.coerceIn(p1 + 1, length - 1)

            val c1 = payload.copyOfRange(0, p1)
            val c2 = payload.copyOfRange(p1, p2)
            val c3 = payload.copyOfRange(p2, length)

            outputStream.write(c1)
            outputStream.flush()

            outputStream.write(c2)
            outputStream.flush()

            outputStream.write(c3)
            outputStream.flush()

            onTechniqueApplied(if (strategy.id == "auto_pilot") "AUTO:MULTISPLIT" else "MULTISPLIT(5,$p2)")
        } else {
            // Turbo Split (0ms latency, separate TCP segments via TCP_NODELAY)
            val c1 = payload.copyOfRange(0, splitPos)
            val c2 = payload.copyOfRange(splitPos, length)

            outputStream.write(c1)
            outputStream.flush()

            outputStream.write(c2)
            outputStream.flush()

            onTechniqueApplied(if (strategy.id == "auto_pilot") "TURBO_SPLIT($splitPos)" else "SNI_SPLIT($splitPos)")
        }
    }

    private fun applyHttpDesync(
        outputStream: OutputStream,
        payload: ByteArray,
        length: Int,
        strategy: BypassStrategy,
        onTechniqueApplied: (String) -> Unit
    ) {
        if (strategy.httpHostMod) {
            val desynced = HttpParser.desyncHttpPayload(payload, length)
            val splitPos = (desynced.size / 2).coerceIn(1, desynced.size - 1)
            val c1 = desynced.copyOfRange(0, splitPos)
            val c2 = desynced.copyOfRange(splitPos, desynced.size)

            outputStream.write(c1)
            outputStream.flush()

            outputStream.write(c2)
            outputStream.flush()
            onTechniqueApplied("HTTP_SPLIT+CASE_MOD")
        } else {
            outputStream.write(payload, 0, length)
            outputStream.flush()
            onTechniqueApplied("HTTP_PASSTHROUGH")
        }
    }
}