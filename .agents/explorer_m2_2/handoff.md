# Exploration Report: Binary-Safe HTTP Desynchronization & LocalDpiProxyServer URI/IPv6 Normalization

**Agent**: `explorer_m2_2`  
**Milestone**: M2 (BitTorrent & P2P DPI Evasion Resilience)  
**Date**: 2026-08-31  

---

## 1. Observation

### 1.1 `HttpParser.kt` Vulnerabilities and Deficiencies
- **File**: `app/src/main/java/com/sourzap/app/service/core/HttpParser.kt` (lines 3–45)
- **Current Implementation**:
  ```kotlin
  fun parseHttpRequest(buffer: ByteArray, length: Int): HttpResult {
      if (length < 10) return HttpResult(false, null, null, -1)
      val text = String(buffer, 0, length.coerceAtMost(2048), Charsets.US_ASCII)
      val method = HTTP_METHODS.firstOrNull { text.startsWith(it) } ?: return HttpResult(false, null, null, -1)
      val hostIndex = text.indexOf("\nHost:", ignoreCase = true)
      // ...
  }

  fun desyncHttpPayload(buffer: ByteArray, length: Int): ByteArray {
      val text = String(buffer, 0, length, Charsets.US_ASCII)
      val modified = text.replace(Regex("(?i)\r\nHost: "), "\r\nhOst:  ")
      return modified.toByteArray(Charsets.US_ASCII)
  }
  ```
- **Direct Observations & Defects**:
  1. **Binary Body Corruption**: `desyncHttpPayload` decodes the entire `buffer[0 until length]` into a `US_ASCII` String. When a request carries a binary body (e.g. HTTP POST uploads, binary BitTorrent tracker announce parameters, gzipped payloads, or WebSockets/custom streams), any byte with value $\ge 0x80$ is replaced by `?` (`0x3F`) or `\uFFFD`, irreversibly corrupting binary data.
  2. **False Positive Modification in Body**: A naive regex `replace(Regex("(?i)\r\nHost: "), ...)` evaluated over the whole string will match and alter `\r\nHost: ` patterns occurring inside the request body (e.g. HTTP documentation, multipart form data, or proxy payloads).
  3. **Header Delimiter Ignorance**: The parser lacks a boundary-detection function (scanning for `\r\n\r\n`, `\n\n`, `\r\n\n`, `\n\r\n`).
  4. **Line-Ending and Spacing Fragility**: `desyncHttpPayload` only matches CRLF (`\r\nHost: `). It fails to desynchronize requests formatted with LF-only line endings (`\nHost:`), missing space after colon (`Host:example.com`), or multiple spaces/tabs.

### 1.2 `LocalDpiProxyServer.kt` URI Normalization, Regex Crashes, and IPv6 Deficiencies
- **File**: `app/src/main/java/com/sourzap/app/service/core/LocalDpiProxyServer.kt` (lines 76–134, 216–282)
- **Current Implementation**:
  ```kotlin
  // Lines 126-133: CONNECT Target Parsing
  if (target.startsWith("[")) {
      targetHost = target.substringAfter("[").substringBefore("]")
      targetPort = target.substringAfter("]:", "443").toIntOrNull() ?: 443
  } else {
      targetHost = target.substringBefore(":")
      targetPort = target.substringAfter(":", "443").toIntOrNull() ?: 443
  }

  // Lines 221-230: Host Header Parsing
  if (rawHost.startsWith("[")) {
      targetHost = rawHost.substringAfter("[").substringBefore("]")
      targetPort = rawHost.substringAfter("]:", "80").toIntOrNull() ?: 80
  } else if (rawHost.contains(":")) {
      targetHost = rawHost.substringBefore(":")
      targetPort = rawHost.substringAfter(":").toIntOrNull() ?: 80
  } else {
      targetHost = rawHost
      targetPort = 80
  }

  // Lines 257-273: Proxy URI Normalization & Replacement
  if (firstLine.matches(Regex("""^[A-Z]+\s+https?://.*""", RegexOption.IGNORE_CASE))) {
      val method = firstLine.substringBefore(" ")
      val fullUrl = firstLine.substringAfter(" ").substringBefore(" ")
      val httpVersion = firstLine.substringAfterLast(" ")
      val uriPath = try {
          val uri = java.net.URI(fullUrl)
          val path = uri.rawPath.ifEmpty { "/" }
          val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
          "$path$query"
      } catch (_: Exception) {
          val afterSlash = fullUrl.substringAfter("://").substringAfter("/", "/")
          if (afterSlash.startsWith("/")) afterSlash else "/$afterSlash"
      }
      val newFirstLine = "$method $uriPath $httpVersion"
      val normalizedText = headerStr.replaceFirst(firstLine, newFirstLine)
      outgoingBuffer = normalizedText.toByteArray(Charsets.US_ASCII)
  }
  ```
- **Direct Observations & Defects**:
  1. **Fatal `PatternSyntaxException` via `replaceFirst`**: `headerStr.replaceFirst(firstLine, newFirstLine)` interprets `firstLine` as a Regular Expression. When `firstLine` contains regex metacharacters such as `[` / `]` (in IPv6 URLs like `http://[2001:db8::1]:8080/`), `?` / `&` / `+` / `*` / `(` / `)` (in tracker URLs such as `http://tracker.com/announce?info_hash=...&peer_id=(SZ)`), Java's `Pattern.compile()` throws `PatternSyntaxException: Unclosed character class` or `Dangling meta character`, immediately terminating client handling.
  2. **`java.net.URI` Syntax Exceptions on BitTorrent Query Parameters**: BitTorrent tracker URLs often transmit raw 20-byte `info_hash` or query parameters with unescaped 8-bit bytes or characters disallowed by RFC 2396 (`{`, `}`, `|`, `\`, `^`, `~`, `[`, `]`). `java.net.URI(fullUrl)` throws `URISyntaxException`. The fallback `fullUrl.substringAfter("://").substringAfter("/", "/")` fails if the URL lacks a path (e.g. `http://[2001:db8::1]:8080`), returning `"/"` incorrectly or stripping query strings.
  3. **Unbracketed IPv6 Literal Breakdown**: In `rawHost.contains(":")`, an unbracketed IPv6 address (e.g. `2001:db8::1` or `::1`) causes `rawHost.substringBefore(":")` to evaluate to `"2001"` or `""`, causing connection failures and DNS lookups for `"2001"`.
  4. **Initial Body Byte Truncation/Corruption**: In `handleClientConnection`, if the client transmits an HTTP POST where headers and initial body bytes are read together in `headerBuffer`, converting `headerBuffer` to US-ASCII `headerStr` and replacing `firstLine` destroys the initial body bytes before pumping.
  5. **Missing `Host:` Header in HTTP/1.0 Tracker Requests**: If an HTTP/1.0 client sends `GET http://tracker.com:6969/announce HTTP/1.0` without a `Host:` header, `rawHost` is empty and the proxy drops the connection instead of falling back to the host in the request line.

---

## 2. Logic Chain

1. **Header Boundary Separation Before Any Decoding**:
   - HTTP/1.0 and HTTP/1.1 message semantics mandate that the header section is strictly delimited by `\r\n\r\n` (`0x0D 0x0A 0x0D 0x0A`), `\n\n` (`0x0A 0x0A`), `\r\n\n`, or `\n\r\n`.
   - By identifying the boundary offset at the byte level, the request can be split into `headerBytes` ($0 \dots \text{headerEnd}$) and `bodyBytes` ($\text{headerEnd} \dots \text{totalRead}$).
   - `bodyBytes` is treated strictly as an immutable raw `ByteArray` and is never passed through any string decoding, regex, or transformation function.

2. **Lossless Character Encoding (`ISO-8859-1`)**:
   - `ISO-8859-1` (Latin-1) maps byte values $0x00 \dots 0xFF$ bijectively (1:1) to Unicode characters `\u0000` $\dots$ `\u00FF`.
   - Decoding `headerBytes` with `Charsets.ISO_8859_1` and re-encoding with `Charsets.ISO_8859_1` guarantees zero byte alteration or loss (unlike `US_ASCII` which replaces bytes $\ge 0x80$ with `?` $0x3F$).

3. **Deterministic URI Path & Query Extraction (Zero-Exception Guarantee)**:
   - Rather than relying on `java.net.URI`, which enforces strict RFC 2396 ASCII rules, URI parsing should be implemented via deterministic string indexing:
     - Strip scheme (`http://`, `https://`, `//`).
     - If the remaining string starts with `[`, find the closing bracket `]` to safely skip IPv6 colons.
     - Find the first `'/'` or `'?'`.
     - If `'/'` is found, return the substring from `'/'`.
     - If `'?'` is found first (no slash), return `"/" + substring`.
     - If neither is found, return `"/"`.
   - This eliminates all `URISyntaxException` and `IllegalArgumentException` throws regardless of unescaped binary bytes or malformed characters.

4. **Literal Substring Concatenation Over Regex Replacement**:
   - Replacing the request line by splitting `headerStr` at the first CRLF/LF and prepending `newFirstLine` avoids regex engines completely, preventing `PatternSyntaxException` when processing IPv6 brackets or query metacharacters.

5. **Universal Host & Port Parsing**:
   - An authority parsing helper must recognize:
     - Bracketed IPv6 with port: `[2001:db8::1]:8080` $\to$ `("2001:db8::1", 8080)`
     - Bracketed IPv6 without port: `[2001:db8::1]` $\to$ `("2001:db8::1", defaultPort)`
     - Unbracketed IPv6 ($\ge 2$ colons): `2001:db8::1` $\to$ `("2001:db8::1", defaultPort)`
     - Standard hostname/IPv4 with port: `example.com:8080` $\to$ `("example.com", 8080)`
     - Standard hostname/IPv4 without port: `example.com` $\to$ `("example.com", defaultPort)`

---

## 3. Proposed Implementation Plan

### 3.1 `HttpParser.kt`
Replace `HttpParser.kt` with the following binary-safe, boundary-aware implementation:

```kotlin
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
        // Matches "\r\nHost: ", "\nHost: ", or "^Host: " (case-insensitive)
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
```

### 3.2 `LocalDpiProxyServer.kt`
Add companion helper functions and update request processing in `LocalDpiProxyServer.kt`:

```kotlin
    companion object {
        /**
         * Parses authority strings into (host, port).
         * Correctly handles:
         * - Bracketed IPv6 with port: "[2001:db8::1]:8080" -> ("2001:db8::1", 8080)
         * - Bracketed IPv6 without port: "[2001:db8::1]" -> ("2001:db8::1", defaultPort)
         * - Unbracketed IPv6: "2001:db8::1" -> ("2001:db8::1", defaultPort)
         * - Hostname / IPv4 with port: "example.com:8080" -> ("example.com", 8080)
         * - Hostname / IPv4 without port: "example.com" -> ("example.com", defaultPort)
         */
        fun parseHostAndPort(rawAuthority: String, defaultPort: Int): Pair<String, Int> {
            val trimmed = rawAuthority.trim()
            if (trimmed.isEmpty()) return Pair("", defaultPort)

            if (trimmed.startsWith("[")) {
                val closeBracket = trimmed.indexOf(']')
                if (closeBracket != -1) {
                    val host = trimmed.substring(1, closeBracket).trim()
                    val afterBracket = trimmed.substring(closeBracket + 1)
                    val port = if (afterBracket.startsWith(":")) {
                        afterBracket.substring(1).toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
                    } else {
                        defaultPort
                    }
                    return Pair(host, port)
                }
            }

            val colonCount = trimmed.count { it == ':' }
            if (colonCount >= 2) {
                // Unbracketed IPv6 literal
                return Pair(trimmed, defaultPort)
            }

            if (colonCount == 1) {
                val host = trimmed.substringBefore(":").trim()
                val port = trimmed.substringAfter(":").toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
                return Pair(host, port)
            }

            return Pair(trimmed, defaultPort)
        }

        /**
         * Robustly normalizes proxy-style absolute URIs to origin-form relative paths.
         * Handles tracker URLs with unescaped raw binary bytes in query parameters (e.g. info_hash),
         * bracketed IPv6 hosts, query parameters without paths, and missing paths without throwing URISyntaxException.
         */
        fun normalizeUriPath(fullUrl: String): String {
            val trimmed = fullUrl.trim()
            if (trimmed.isEmpty()) return "/"
            if (trimmed.startsWith("/")) return trimmed

            val schemeEnd = trimmed.indexOf("://")
            val authorityAndPath = if (schemeEnd != -1) {
                trimmed.substring(schemeEnd + 3)
            } else if (trimmed.startsWith("//")) {
                trimmed.substring(2)
            } else {
                trimmed
            }

            val searchStart = if (authorityAndPath.startsWith("[")) {
                val closeBracket = authorityAndPath.indexOf(']')
                if (closeBracket != -1) closeBracket + 1 else 0
            } else {
                0
            }

            var slashIndex = -1
            var isQueryOnly = false
            for (i in searchStart until authorityAndPath.length) {
                val c = authorityAndPath[i]
                if (c == '/') {
                    slashIndex = i
                    break
                }
                if (c == '?' || c == '#') {
                    slashIndex = i
                    isQueryOnly = true
                    break
                }
            }

            return when {
                slashIndex == -1 -> "/"
                isQueryOnly -> "/" + authorityAndPath.substring(slashIndex)
                else -> authorityAndPath.substring(slashIndex)
            }
        }

        /**
         * Determines if a host string is an IPv4 or IPv6 address literal to bypass DNS resolution.
         */
        fun isIpLiteral(host: String): Boolean {
            val trimmed = host.trim()
            if (trimmed.contains(':')) return true
            if (trimmed.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) return true
            return false
        }
    }
```

In `handleClientConnection`:
```kotlin
            // 1. Read initial request line & headers using HttpParser.findHeaderBoundary
            val headerBuffer = ByteArrayPool.obtain16k()
            val tempBuf = ByteArrayPool.obtain4k()
            var totalRead = 0

            val initialHeaderBytes: ByteArray
            val initialBodyBytes: ByteArray
            val headerStr: String
            val firstLine: String
            val firstLineEnd: Int

            try {
                while (totalRead < 8192) {
                    val count = clientIn.read(tempBuf, 0, minOf(1024, 8192 - totalRead))
                    if (count <= 0) break
                    System.arraycopy(tempBuf, 0, headerBuffer, totalRead, count)
                    totalRead += count

                    if (HttpParser.findHeaderBoundary(headerBuffer, totalRead) != null) break
                }

                if (totalRead == 0) return

                val boundary = HttpParser.findHeaderBoundary(headerBuffer, totalRead)
                val headerEnd = if (boundary != null) boundary.first + boundary.second else totalRead

                initialHeaderBytes = headerBuffer.copyOfRange(0, headerEnd)
                initialBodyBytes = if (totalRead > headerEnd) headerBuffer.copyOfRange(headerEnd, totalRead) else ByteArray(0)

                headerStr = String(initialHeaderBytes, Charsets.ISO_8859_1)
                firstLineEnd = headerStr.indexOf("\r\n").let { if (it == -1) headerStr.indexOf("\n") else it }
                firstLine = if (firstLineEnd != -1) headerStr.substring(0, firstLineEnd) else headerStr
            } finally {
                ByteArrayPool.recycle16k(headerBuffer)
                ByteArrayPool.recycle4k(tempBuf)
            }

            if (firstLine.startsWith("CONNECT ", ignoreCase = true)) {
                // --- CONNECT Tunneling ---
                val parts = firstLine.trim().split(Regex("\\s+"))
                if (parts.size < 2) return

                val target = parts[1]
                val (targetHost, targetPort) = parseHostAndPort(target, 443)
                if (targetHost.isEmpty()) return

                val targetIp = try {
                    if (isIpLiteral(targetHost)) {
                        InetAddress.getByName(targetHost)
                    } else {
                        val targetIps = DohResolver.resolve(targetHost)
                        targetIps.firstOrNull() ?: InetAddress.getByName(targetHost)
                    }
                } catch (_: Exception) {
                    InetAddress.getByName(targetHost)
                }

                // ... upstream connection & pump ...
            } else {
                // --- Plain HTTP Request ---
                val hostLine = headerStr.lineSequence().firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                val rawHost = hostLine?.substringAfter(":")?.trim() ?: ""

                val (hostFromHeader, portFromHeader) = parseHostAndPort(rawHost, 80)
                var targetHost = hostFromHeader
                var targetPort = portFromHeader

                val firstLineParts = firstLine.trim().split(Regex("\\s+"))
                val method = if (firstLineParts.isNotEmpty()) firstLineParts[0] else "GET"
                val rawUri = if (firstLineParts.size >= 2) firstLineParts[1] else "/"
                val httpVersion = if (firstLineParts.size >= 3) firstLineParts[2] else "HTTP/1.1"

                // Fallback host extraction from absolute URI if Host header is missing
                if (targetHost.isEmpty() && (rawUri.startsWith("http://", ignoreCase = true) || rawUri.startsWith("https://", ignoreCase = true))) {
                    val uriWithoutScheme = rawUri.substringAfter("://").substringBefore("/")
                    val (extractedHost, extractedPort) = parseHostAndPort(uriWithoutScheme, 80)
                    targetHost = extractedHost
                    targetPort = extractedPort
                }

                if (targetHost.isNotEmpty()) {
                    val targetIp = try {
                        if (isIpLiteral(targetHost)) {
                            InetAddress.getByName(targetHost)
                        } else {
                            val targetIps = DohResolver.resolve(targetHost)
                            targetIps.firstOrNull() ?: InetAddress.getByName(targetHost)
                        }
                    } catch (_: Exception) {
                        InetAddress.getByName(targetHost)
                    }

                    val upstream = Socket().apply {
                        receiveBufferSize = 1048576
                        sendBufferSize = 524288
                        tcpNoDelay = true
                        keepAlive = true
                        soTimeout = 15000
                        trafficClass = 0x08
                        setPerformancePreferences(0, 1, 2)
                    }
                    upstreamSocket = upstream

                    vpnService.protect(upstream)
                    upstream.connect(InetSocketAddress(targetIp, targetPort), 6000)

                    val upstreamOut = upstream.getOutputStream()
                    val upstreamIn = upstream.getInputStream()
                    val strategy = SourZapApp.instance.strategyRepository.currentStrategy.value

                    // Rewrite absolute URI to relative path safely without regex replacement
                    var finalHeaderStr = headerStr
                    if (rawUri.startsWith("http://", ignoreCase = true) || rawUri.startsWith("https://", ignoreCase = true)) {
                        val normalizedPath = normalizeUriPath(rawUri)
                        val newFirstLine = "$method $normalizedPath $httpVersion"
                        val restOfHeaders = if (firstLineEnd != -1) headerStr.substring(firstLineEnd) else ""
                        finalHeaderStr = newFirstLine + restOfHeaders
                    }

                    var outgoingHeaderBytes = finalHeaderStr.toByteArray(Charsets.ISO_8859_1)

                    if (strategy.httpHostMod) {
                        outgoingHeaderBytes = HttpParser.desyncHttpPayload(outgoingHeaderBytes, outgoingHeaderBytes.size)
                    }

                    // Assemble outgoing header + preserved binary initial body bytes
                    val fullOutgoingBuffer = ByteArray(outgoingHeaderBytes.size + initialBodyBytes.size)
                    System.arraycopy(outgoingHeaderBytes, 0, fullOutgoingBuffer, 0, outgoingHeaderBytes.size)
                    if (initialBodyBytes.isNotEmpty()) {
                        System.arraycopy(initialBodyBytes, 0, fullOutgoingBuffer, outgoingHeaderBytes.size, initialBodyBytes.size)
                    }

                    upstreamOut.write(fullOutgoingBuffer)
                    upstreamOut.flush()

                    // ... logging and pumpBidirectional ...
                }
            }
```

---

## 4. Caveats

1. **Non-Standard Multi-Line Delimiters**: While RFC 7230 specifies `\r\n\r\n`, some embedded BitTorrent clients and legacy proxies omit `\r` and send `\n\n`. The proposed `findHeaderBoundary` handles `\r\n\r\n`, `\n\n`, `\r\n\n`, and `\n\r\n`.
2. **Pipelined HTTP Requests**: In HTTP/1.1 pipelining, multiple requests may exist in the read stream. By only desynchronizing the first parsed header and streaming subsequent bytes transparently through `pumpBidirectional`, subsequent pipelined requests pass without corruption.
3. **Fragmented Headers Across TCP Segments**: If an HTTP request header is split across multiple TCP packets and only the first 8 bytes arrive initially, `findHeaderBoundary` will return null. The proxy will read up to 8192 bytes or wait for the delimiter before dispatching upstream.
4. **No Side-Effects on Milestone M1**: The proposed changes preserve existing cooperative cancellation, buffer pooling (`ByteArrayPool`), and socket lifecycles verified in Milestone M1.

---

## 5. Conclusion

1. Refactoring `HttpParser.desyncHttpPayload` to use `findHeaderBoundary` and `Charsets.ISO_8859_1` completely isolates the header section and guarantees 100% bit-exact preservation of binary request bodies, resolving data corruption for BitTorrent tracker announces and HTTP POST/PUT payloads.
2. Replacing `java.net.URI` and regex `replaceFirst` in `LocalDpiProxyServer` with deterministic URI path normalization and string slicing eliminates all `PatternSyntaxException` and `URISyntaxException` crashes when processing IPv6 bracketed hosts and raw byte query strings.
3. Implementing `parseHostAndPort` and `isIpLiteral` provides full support for bracketed and unbracketed IPv6 addresses across both CONNECT and Plain HTTP modes.

---

## 6. Verification Method

To independently verify the implementation:

### 6.1 Unit Test Expansion (`app/src/test/java/com/sourzap/app/DpiEngineTest.kt` or `LocalDpiProxyServerTest.kt`)
1. **Binary Body Preservation Test**:
   - Construct a `POST /upload HTTP/1.1\r\nHost: example.com\r\n\r\n` request with 1024 binary bytes spanning `0x80..0xFF`.
   - Call `HttpParser.desyncHttpPayload(bytes, bytes.size)`.
   - Assert `hOst:  example.com` is present in the header.
   - Assert the last 1024 bytes of the returned array are byte-for-byte identical to the input binary body.
2. **BitTorrent Tracker Raw Query Test**:
   - Call `LocalDpiProxyServer.normalizeUriPath("http://tracker.com:6969/announce?info_hash=%80%91%A2%B3&peer_id=-SZ0001-[v2]")`.
   - Assert return value is `"/announce?info_hash=%80%91%A2%B3&peer_id=-SZ0001-[v2]"`.
3. **IPv6 Bracket and Port Normalization Test**:
   - Test `LocalDpiProxyServer.parseHostAndPort("[2001:db8::1]:8080", 80)` $\to$ `("2001:db8::1", 8080)`.
   - Test `LocalDpiProxyServer.parseHostAndPort("[2001:db8::1]", 80)` $\to$ `("2001:db8::1", 80)`.
   - Test `LocalDpiProxyServer.parseHostAndPort("2001:db8::1", 80)` $\to$ `("2001:db8::1", 80)`.
   - Test `LocalDpiProxyServer.normalizeUriPath("http://[2001:db8::1]:8080/announce")` $\to$ `"/announce"`.
   - Test `LocalDpiProxyServer.normalizeUriPath("http://[2001:db8::1]:8080")` $\to$ `"/"`.
4. **Regex Metacharacter Immunity Test**:
   - Verify request line `GET http://tracker.com/announce?file=[test]+(1080p).mkv HTTP/1.1` does not throw `PatternSyntaxException`.

### 6.2 Test Commands
- Execute full test suite:
  ```powershell
  cmd /c gradlew.bat testDebugUnitTest
  ```
  Expected: 100% pass rate (`BUILD SUCCESSFUL`).
- Execute release compilation:
  ```powershell
  cmd /c gradlew.bat assembleRelease
  ```
  Expected: `BUILD SUCCESSFUL`.
