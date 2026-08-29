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

        // 1. Clean Passthrough for Google Search, Google APIs & Apple/MS infra
        val isCleanInfra = (hostname.startsWith("www.google.") || hostname == "google.com" ||
                hostname.endsWith(".google.com") || hostname.endsWith(".google.co.in") ||
                hostname.contains("gstatic.com") || hostname.contains("googleapis.com") ||
                hostname.contains("accounts.google") || hostname.contains("play.google") ||
                hostname.contains("apple.com") || hostname.contains("microsoft.com")) &&
                !hostname.contains("youtube") && !hostname.contains("googlevideo") && !hostname.contains("ytimg")

        if (strategy.id == "auto_pilot" && isCleanInfra) {
            outputStream.write(payload, 0, length)
            outputStream.flush()
            onTechniqueApplied("CLEAN_PASSTHROUGH")
            return
        }

        // 2. Zapret Gold Standard Split2 (Offset 2):
        // Splits the 5-byte TLS Record Header (0x16 0x03 | 0x01 ...) across two TCP segments.
        // - DPI engines fail to match the TLS handshake header (bypasses ISP throttle & censorship)
        // - Web servers (Cloudflare, Fast.com, Netflix, Akamai, AWS) reassemble in 0ms with standard JA3 fingerprint
        // - ZERO bot detection / CAPTCHA flags, full line speed for web browsing and speed tests
        val isAutoPilot = strategy.id == "auto_pilot"

        val splitPos = if (isAutoPilot || strategy.tlsSplitOffset == 2 || strategy.id == "streaming_turbo" || strategy.id == "gaming_voice") {
            2
        } else when {
            strategy.tlsSplitOffset > 0 -> strategy.tlsSplitOffset.coerceIn(1, length - 1)
            strategy.tlsSplitOffset == -1 && sniResult.sniExtensionOffset > 5 -> sniResult.sniExtensionOffset.coerceIn(2, length - 2)
            else -> 2
        }

        // Multi-split ONLY if explicitly requested in custom strategy and NOT in Auto-Pilot
        if (!isAutoPilot && strategy.useMultisplit && length > 12) {
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

            onTechniqueApplied("MULTISPLIT(5,$p2)")
        } else {
            // Standard Zapret Zero-Latency Split2 (Two immediate segments via TCP_NODELAY)
            val c1 = payload.copyOfRange(0, splitPos)
            val c2 = payload.copyOfRange(splitPos, length)

            outputStream.write(c1)
            outputStream.flush()

            outputStream.write(c2)
            outputStream.flush()

            onTechniqueApplied(if (isAutoPilot) "AUTO:SPLIT2" else "SPLIT($splitPos)")
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