package com.sourzap.app.service.core

object HttpParser {

    data class HttpResult(
        val isHttp: Boolean,
        val method: String?,
        val host: String?,
        val hostHeaderOffset: Int
    )

    private val HTTP_METHODS = listOf("GET ", "POST ", "HEAD ", "OPTIONS ", "PUT ", "DELETE ", "CONNECT ")

    fun parseHttpRequest(buffer: ByteArray, length: Int): HttpResult {
        if (length < 10) return HttpResult(false, null, null, -1)
        val text = String(buffer, 0, length.coerceAtMost(2048), Charsets.US_ASCII)

        val method = HTTP_METHODS.firstOrNull { text.startsWith(it) } ?: return HttpResult(false, null, null, -1)

        val hostIndex = text.indexOf("\nHost:", ignoreCase = true)
        if (hostIndex != -1) {
            val lineStart = hostIndex + 1
            val lineEnd = text.indexOf("\r\n", lineStart).let { if (it == -1) text.indexOf("\n", lineStart) else it }
            if (lineEnd > lineStart) {
                val hostLine = text.substring(lineStart, lineEnd)
                val colonPos = hostLine.indexOf(':')
                if (colonPos != -1) {
                    val host = hostLine.substring(colonPos + 1).trim()
                    return HttpResult(true, method.trim(), host, lineStart)
                }
            }
        }

        return HttpResult(true, method.trim(), null, -1)
    }

    /**
     * Applies Zapret HTTP host desync techniques (casing, space before colon, header split)
     */
    fun desyncHttpPayload(buffer: ByteArray, length: Int): ByteArray {
        val text = String(buffer, 0, length, Charsets.US_ASCII)
        val modified = text.replace(Regex("(?i)\r\nHost: "), "\r\nhOst:  ")
        return modified.toByteArray(Charsets.US_ASCII)
    }
}