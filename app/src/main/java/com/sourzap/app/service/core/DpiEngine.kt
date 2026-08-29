package com.sourzap.app.service.core

import com.sourzap.app.data.model.BypassStrategy
import java.io.OutputStream
import java.net.Socket

object DpiEngine {

    /**
     * Applies Zapret DPI bypass evasion techniques on the initial TLS/HTTP stream
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

            // Default: forward unmodified
            outputStream.write(payload, 0, length)
            outputStream.flush()
        } catch (e: Exception) {
            // Write normal on any error
            outputStream.write(payload, 0, length)
            outputStream.flush()
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

        // 1. Send Fake ClientHello if configured
        if (strategy.fakeSni.isNotBlank()) {
            val fakeHello = TlsParser.createFakeClientHello(strategy.fakeSni)
            outputStream.write(fakeHello)
            outputStream.flush()
            appliedTechniques.add("FAKE[]")
            Thread.sleep(1) // Micro delay for discrete packet frame
        }

        // 2. Calculate Split Offset
        val splitPos = when {
            strategy.tlsSplitOffset == -1 && sniResult.sniExtensionOffset > 0 -> {
                sniResult.sniExtensionOffset.coerceIn(1, length - 1)
            }
            strategy.tlsSplitOffset > 0 -> {
                strategy.tlsSplitOffset.coerceIn(1, length - 1)
            }
            else -> (length / 2).coerceIn(1, length - 1)
        }

        val chunk1 = payload.copyOfRange(0, splitPos)
        val chunk2 = payload.copyOfRange(splitPos, length)

        // 3. Disorder / Multisplit / Normal Split
        if (strategy.useDisorder) {
            // Send chunk2 first, then chunk1 (TCP reordering on server; DPI ignores out-of-order)
            outputStream.write(chunk2)
            outputStream.flush()
            Thread.sleep(1)
            outputStream.write(chunk1)
            outputStream.flush()
            appliedTechniques.add("DISORDER(@)")
        } else if (strategy.useMultisplit && length > 6) {
            // Split into 3 micro chunks
            val p1 = 2.coerceAtMost(length - 2)
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
            appliedTechniques.add("MULTISPLIT(,)")
        } else {
            // Clean 2-segment split
            outputStream.write(chunk1)
            outputStream.flush()
            Thread.sleep(1)
            outputStream.write(chunk2)
            outputStream.flush()
            appliedTechniques.add("SPLIT(@)")
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
            onTechniqueApplied("HTTP_DESYNC+SPLIT")
        } else {
            outputStream.write(payload, 0, length)
            outputStream.flush()
            onTechniqueApplied("HTTP_PASSTHROUGH")
        }
    }
}