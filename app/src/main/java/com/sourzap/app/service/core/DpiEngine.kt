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

        // Auto-Pilot Dynamic Intelligence: Inspect domain target
        val effectiveStrategy = if (strategy.id == "auto_pilot") {
            when {
                // Streaming & Video Services -> Max Throughput SNI boundary split
                hostname.contains("googlevideo") || hostname.contains("youtube") ||
                hostname.contains("ytimg") || hostname.contains("twitch") ||
                hostname.contains("netflix") || hostname.contains("instagram") ||
                hostname.contains("fbcdn") || hostname.contains("tiktok") -> {
                    BypassStrategy.STREAMING_TURBO
                }

                // Voice, Gaming & RTC -> Low-latency Split Offset 2
                hostname.contains("discord") || hostname.contains("gateway") ||
                hostname.contains("voice") || hostname.contains("rtc") ||
                hostname.contains("telegram") -> {
                    BypassStrategy.GAMING_VOICE
                }

                // Strict Censorship / General Blocked Hosts -> Deep Universal Split
                else -> {
                    BypassStrategy.STRICT_FIREWALL
                }
            }
        } else {
            strategy
        }

        // Calculate Split Position
        val splitPos = when {
            effectiveStrategy.tlsSplitOffset == -1 && sniResult.sniExtensionOffset > 5 -> {
                sniResult.sniExtensionOffset.coerceIn(2, length - 2)
            }
            effectiveStrategy.tlsSplitOffset > 0 -> {
                effectiveStrategy.tlsSplitOffset.coerceIn(1, length - 1)
            }
            else -> {
                if (sniResult.sniExtensionOffset > 0) sniResult.sniExtensionOffset.coerceIn(2, length - 2) else (length / 2).coerceIn(2, length - 2)
            }
        }

        if (effectiveStrategy.useMultisplit && length > 12) {
            val p1 = 5.coerceAtMost(splitPos - 1)
            val p2 = splitPos.coerceIn(p1 + 1, length - 1)

            val c1 = payload.copyOfRange(0, p1)
            val c2 = payload.copyOfRange(p1, p2)
            val c3 = payload.copyOfRange(p2, length)

            outputStream.write(c1)
            outputStream.flush()
            Thread.sleep(1)

            outputStream.write(c2)
            outputStream.flush()
            Thread.sleep(1)

            outputStream.write(c3)
            outputStream.flush()

            onTechniqueApplied(if (strategy.id == "auto_pilot") "AUTO:MULTISPLIT" else "MULTISPLIT(5,$p2)")
        } else {
            val c1 = payload.copyOfRange(0, splitPos)
            val c2 = payload.copyOfRange(splitPos, length)

            outputStream.write(c1)
            outputStream.flush()
            Thread.sleep(1)

            outputStream.write(c2)
            outputStream.flush()

            onTechniqueApplied(if (strategy.id == "auto_pilot") "AUTO:SNI_SPLIT" else "SNI_SPLIT($splitPos)")
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
            Thread.sleep(1)
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