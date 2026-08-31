package com.sourzap.app.torrent.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Normalized representation of a parsed Magnet URI.
 */
data class MagnetInfo(
    val infoHash: String, // Normalized 40-character lowercase hex string
    val displayName: String? = null,
    val fileLength: Long? = null,
    val trackers: List<String> = emptyList(),
    val webSeeds: List<String> = emptyList(),
    val keywords: String? = null
) {
    fun toUri(): String {
        val sb = StringBuilder("magnet:?xt=urn:btih:").append(infoHash)
        if (!displayName.isNullOrBlank()) {
            sb.append("&dn=").append(URLEncoder.encode(displayName, StandardCharsets.UTF_8.name()))
        }
        if (fileLength != null && fileLength > 0) {
            sb.append("&xl=").append(fileLength)
        }
        for (tr in trackers) {
            sb.append("&tr=").append(URLEncoder.encode(tr, StandardCharsets.UTF_8.name()))
        }
        for (ws in webSeeds) {
            sb.append("&ws=").append(URLEncoder.encode(ws, StandardCharsets.UTF_8.name()))
        }
        if (!keywords.isNullOrBlank()) {
            sb.append("&kt=").append(URLEncoder.encode(keywords, StandardCharsets.UTF_8.name()))
        }
        return sb.toString()
    }
}

/**
 * Magnet Link Parser, BTIH Extraction, Base32 normalization, and Serialization.
 */
object MagnetHandler {

    private const val BTIH_PREFIX = "urn:btih:"

    fun parse(uriString: String?): MagnetInfo? {
        if (uriString.isNullOrBlank()) return null
        val trimmed = uriString.trim()
        if (!trimmed.startsWith("magnet:?", ignoreCase = true)) return null

        val queryPart = trimmed.substring("magnet:?".length)
        if (queryPart.isEmpty()) return null

        val pairs = queryPart.split("&").filter { it.isNotEmpty() }

        var infoHash: String? = null
        var displayName: String? = null
        var fileLength: Long? = null
        val trackers = mutableListOf<String>()
        val webSeeds = mutableListOf<String>()
        var keywords: String? = null

        for (pair in pairs) {
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue
            val key = pair.substring(0, eqIdx).trim()
            val rawValue = pair.substring(eqIdx + 1).trim()
            val decodedValue = safeUrlDecode(rawValue)

            when (key.lowercase(Locale.US)) {
                "xt" -> {
                    val xtLower = decodedValue.lowercase(Locale.US)
                    if (xtLower.startsWith(BTIH_PREFIX)) {
                        val hashCandidate = decodedValue.substring(BTIH_PREFIX.length).trim()
                        val normalized = normalizeInfoHash(hashCandidate)
                        if (normalized != null) {
                            infoHash = normalized
                        }
                    }
                }
                "dn" -> displayName = decodedValue
                "xl" -> fileLength = decodedValue.toLongOrNull()
                "tr" -> if (decodedValue.isNotBlank()) trackers.add(decodedValue)
                "ws" -> if (decodedValue.isNotBlank()) webSeeds.add(decodedValue)
                "kt" -> keywords = decodedValue
            }
        }

        if (infoHash == null) return null

        return MagnetInfo(
            infoHash = infoHash,
            displayName = displayName,
            fileLength = fileLength,
            trackers = trackers,
            webSeeds = webSeeds,
            keywords = keywords
        )
    }

    fun normalizeInfoHash(candidate: String): String? {
        val clean = candidate.trim()
        // 40-character Hex
        if (clean.length == 40 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return clean.lowercase(Locale.US)
        }
        // 32-character Base32
        if (clean.length == 32 && clean.all { it in 'a'..'z' || it in 'A'..'Z' || it in '2'..'7' }) {
            return base32ToHex(clean)
        }
        return null
    }

    private fun base32ToHex(base32: String): String? {
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val upper = base32.uppercase(Locale.US)
        val bytes = ByteArray(20)
        var buffer = 0
        var bitsLeft = 0
        var byteIndex = 0

        for (c in upper) {
            val val5 = base32Chars.indexOf(c)
            if (val5 < 0) return null
            buffer = (buffer shl 5) or val5
            bitsLeft += 5
            if (bitsLeft >= 8) {
                if (byteIndex >= 20) return null
                bytes[byteIndex++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
            }
        }

        if (byteIndex != 20) return null

        val sb = StringBuilder(40)
        for (b in bytes) {
            sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private fun safeUrlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }
}
