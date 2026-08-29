package com.sourzap.app.service.core

import com.sourzap.app.data.model.BypassStrategy
import java.io.OutputStream
import java.net.Socket

object DpiEngine {

    /**
     * Applies Zapret DPI circumvention techniques on the initial TLS/HTTP handshake stream.
     * Ensures 100% TCP stream integrity while fragmenting across discrete TCP packets
     * to evade ISP DPI middlebox signature matching.
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
        val appliedTechniques = mutableListOf<String>()

        // 1. Calculate Split Position
        val splitPos = when {
            strategy.tlsSplitOffset == -1 && sniResult.sniExtensionOffset > 5 -> {
                sniResult.sniExtensionOffset.coerceIn(2, length - 2)
            }
            strategy.tlsSplitOffset > 0 -> {
                strategy.tlsSplitOffset.coerceIn(1, length - 1)
            }
            else -> {
                // Default split at position 2 or middle of ClientHello
                if (sniResult.sniExtensionOffset > 0) sniResult.sniExtensionOffset.coerceIn(2, length - 2) else (length / 2).coerceIn(2, length - 2)
            }
        }

        if (strategy.useMultisplit && length > 12) {
            // Multisplit into 3 micro-segments:
            // Chunk 1: TLS Record header (5 bytes: 0x16, 0x03, 0x01, length)
            // Chunk 2: ClientHello prefix up to SNI
            // Chunk 3: SNI payload and extensions
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

            appliedTechniques.add("MULTISPLIT(5,$p2)")
        } else {
            // Clean 2-segment SNI split
            val c1 = payload.copyOfRange(0, splitPos)
            val c2 = payload.copyOfRange(splitPos, length)

            outputStream.write(c1)
            outputStream.flush()
            Thread.sleep(1)

            outputStream.write(c2)
            outputStream.flush()

            appliedTechniques.add("SNI_SPLIT($splitPos)")
        }

        onTechniqueApplied(appliedTechniques.joinToString("+"))
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