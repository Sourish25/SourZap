package com.sourzap.app.service.core

object HttpParser {

    data class HttpResult(
        val isHttp: Boolean,
        val method: String?,
        val host: String?,
        val hostHeaderOffset: Int
    )

    private val HTTP_METHODS = listOf(
        "GET ", "POST ", "HEAD ", "OPTIONS ", "PUT ", "DELETE ", "CONNECT ", "TRACE ", "PATCH "
    )

    /**
     * Finds the delimiter bounding the HTTP header section.
     * Returns a Pair(delimiterStartOffset, delimiterLength) where:
     * - delimiterStartOffset: index where \r\n\r\n, \n\n, \r\n\n, or \n\r\n starts.
     * - delimiterLength: length of the delimiter sequence (4, 2, or 3 bytes).
     * The header section ends at (delimiterStartOffset + delimiterLength).
     */
    fun findHeaderBoundary(buffer: ByteArray, length: Int): Pair<Int, Int>? {
        val limit = minOf(buffer.size, length)
        for (i in 0 until limit - 1) {
            // Check for \r\n\r\n (4 bytes)
            if (i + 3 < limit &&
                buffer[i] == 0x0D.toByte() && buffer[i + 1] == 0x0A.toByte() &&
                buffer[i + 2] == 0x0D.toByte() && buffer[i + 3] == 0x0A.toByte()
            ) {
                return Pair(i, 4)
            }
            // Check for \r\n\n (3 bytes)
            if (i + 2 < limit &&
                buffer[i] == 0x0D.toByte() && buffer[i + 1] == 0x0A.toByte() &&
                buffer[i + 2] == 0x0A.toByte()
            ) {
                return Pair(i, 3)
            }
            // Check for \n\r\n (3 bytes)
            if (i + 2 < limit &&
                buffer[i] == 0x0A.toByte() && buffer[i + 1] == 0x0D.toByte() &&
                buffer[i + 2] == 0x0A.toByte()
            ) {
                return Pair(i, 3)
            }
            // Check for \n\n (2 bytes)
            if (buffer[i] == 0x0A.toByte() && buffer[i + 1] == 0x0A.toByte()) {
                return Pair(i, 2)
            }
        }
        return null
    }

    /**
     * Parses the HTTP request method and Host header value without character corruption.
     * Uses ISO-8859-1 for lossless 1:1 character-to-byte mapping.
     */
    fun parseHttpRequest(buffer: ByteArray, length: Int): HttpResult {
        val safeLen = minOf(buffer.size, length)
        if (safeLen < 10) return HttpResult(false, null, null, -1)

        val checkLen = safeLen.coerceAtMost(2048)
        val text = String(buffer, 0, checkLen, Charsets.ISO_8859_1)

        val methodWithSpace = HTTP_METHODS.firstOrNull { text.startsWith(it) }
            ?: return HttpResult(false, null, null, -1)

        val hostIndex = text.indexOf("\nHost:", ignoreCase = true)
        if (hostIndex != -1) {
            val lineStart = hostIndex + 1
            val lineEnd = text.indexOf("\r\n", lineStart).let { if (it == -1) text.indexOf("\n", lineStart) else it }
            val actualEnd = if (lineEnd == -1) checkLen else lineEnd
            if (actualEnd > lineStart) {
                val hostLine = text.substring(lineStart, actualEnd)
                val colonPos = hostLine.indexOf(':')
                if (colonPos != -1) {
                    val host = hostLine.substring(colonPos + 1).trim()
                    return HttpResult(true, methodWithSpace.trim(), host, lineStart)
                }
            }
        } else if (text.startsWith("Host:", ignoreCase = true)) {
            val lineEnd = text.indexOf("\r\n").let { if (it == -1) text.indexOf("\n") else it }
            val actualEnd = if (lineEnd == -1) checkLen else lineEnd
            val hostLine = text.substring(0, actualEnd)
            val colonPos = hostLine.indexOf(':')
            if (colonPos != -1) {
                val host = hostLine.substring(colonPos + 1).trim()
                return HttpResult(true, methodWithSpace.trim(), host, 0)
            }
        }

        return HttpResult(true, methodWithSpace.trim(), null, -1)
    }

    /**
     * Applies Zapret HTTP host desync techniques (casing: "Host: " -> "hOst:  ").
     * Binary-safe: isolates the header section and modifies ONLY the header bytes,
     * preserving binary request body bytes byte-for-byte without String decoding corruption.
     */
    fun desyncHttpPayload(buffer: ByteArray, length: Int): ByteArray {
        val safeLen = minOf(buffer.size, length)
        if (safeLen < 10) return buffer.copyOfRange(0, safeLen)

        val boundary = findHeaderBoundary(buffer, safeLen)
        val headerEnd = if (boundary != null) {
            boundary.first + boundary.second
        } else {
            safeLen
        }

        // Decode ONLY the header slice using ISO-8859-1 (lossless 1:1 mapping)
        val headerText = String(buffer, 0, headerEnd, Charsets.ISO_8859_1)

        // Modify Host header casing and spacing within header section
        val modifiedHeader = headerText.replace(Regex("(?im)^Host:\\s*")) {
            "hOst:  "
        }

        val modifiedHeaderBytes = modifiedHeader.toByteArray(Charsets.ISO_8859_1)
        val bodyLen = safeLen - headerEnd

        val result = ByteArray(modifiedHeaderBytes.size + bodyLen)
        System.arraycopy(modifiedHeaderBytes, 0, result, 0, modifiedHeaderBytes.size)
        if (bodyLen > 0) {
            System.arraycopy(buffer, headerEnd, result, modifiedHeaderBytes.size, bodyLen)
        }

        return result
    }

    /**
     * Splits an HTTP request header at a specified offset or at the Host header,
     * returning two byte chunks without corrupting any attached binary body bytes.
     */
    fun splitHttpHeader(buffer: ByteArray, length: Int, splitOffset: Int = -1): Pair<ByteArray, ByteArray> {
        val safeLen = minOf(buffer.size, length)
        if (safeLen <= 1) {
            return Pair(buffer.copyOfRange(0, safeLen), ByteArray(0))
        }

        val actualSplit = if (splitOffset in 1 until safeLen) {
            splitOffset
        } else {
            val parseRes = parseHttpRequest(buffer, safeLen)
            if (parseRes.hostHeaderOffset in 1 until safeLen) {
                parseRes.hostHeaderOffset
            } else {
                (safeLen / 2).coerceIn(1, safeLen - 1)
            }
        }

        val c1 = buffer.copyOfRange(0, actualSplit)
        val c2 = buffer.copyOfRange(actualSplit, safeLen)
        return Pair(c1, c2)
    }
}