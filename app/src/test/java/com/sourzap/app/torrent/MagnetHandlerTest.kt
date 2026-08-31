package com.sourzap.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Test suite for Magnet Link Parser, BTIH Extraction, and Serialization.
 * Verifies Requirement R2 & Feature F8:
 * - Parsing 40-char Hex and 32-char Base32 info hashes (xt=urn:btih:)
 * - Display name (dn=) and exact length (xl=) extraction
 * - Trackers (tr=) and web seeds (ws=) extraction
 * - Robustness against malformed, truncated, and corrupted magnet URIs
 * - Round-trip serialization fidelity
 */
class MagnetHandlerTest {

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
                    "dn" -> {
                        displayName = decodedValue
                    }
                    "xl" -> {
                        fileLength = decodedValue.toLongOrNull()
                    }
                    "tr" -> {
                        if (decodedValue.isNotBlank()) {
                            trackers.add(decodedValue)
                        }
                    }
                    "ws" -> {
                        if (decodedValue.isNotBlank()) {
                            webSeeds.add(decodedValue)
                        }
                    }
                    "kt" -> {
                        keywords = decodedValue
                    }
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

    @Test
    fun `test Parse 40-char Hex BTIH Magnet URI`() {
        val hexHash = "c12fe1c06bba254a9dc9f519b335de7ece74f6d2"
        val magnet = "magnet:?xt=urn:btih:$hexHash&dn=Ubuntu-24.04-Desktop-amd64.iso&xl=5046586573&tr=https%3A%2F%2Ftracker.tamersunion.org%3A443%2Fannounce"

        val parsed = MagnetHandler.parse(magnet)
        assertNotNull("Parsed magnet must not be null", parsed)
        assertEquals(hexHash, parsed!!.infoHash)
        assertEquals("Ubuntu-24.04-Desktop-amd64.iso", parsed.displayName)
        assertEquals(5046586573L, parsed.fileLength)
        assertEquals(1, parsed.trackers.size)
        assertEquals("https://tracker.tamersunion.org:443/announce", parsed.trackers[0])
    }

    @Test
    fun `test Parse 32-char Base32 BTIH Magnet URI and normalization`() {
        // Base32 representation of a 20-byte hash
        val base32Hash = "YNCKHTQ3XIRUVE6J6UM345O6P3TXJ5WS"
        val magnet = "magnet:?xt=urn:btih:$base32Hash&dn=Test+Dataset"

        val parsed = MagnetHandler.parse(magnet)
        assertNotNull("Base32 magnet must parse successfully", parsed)
        assertEquals(40, parsed!!.infoHash.length)
        assertTrue(parsed.infoHash.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("Test Dataset", parsed.displayName)
    }

    @Test
    fun `test Parse Magnet with Multiple Trackers and Web Seeds`() {
        val magnet = "magnet:?xt=urn:btih:4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a" +
                "&dn=MultiTrackers" +
                "&tr=https%3A%2F%2Ftracker1.org%3A443%2Fannounce" +
                "&tr=https%3A%2F%2Ftracker2.org%3A443%2Fannounce" +
                "&ws=https%3A%2F%2Fcdn.example.com%2Ffile.iso" +
                "&kt=linux+distro+iso"

        val parsed = MagnetHandler.parse(magnet)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.trackers.size)
        assertEquals("https://tracker1.org:443/announce", parsed.trackers[0])
        assertEquals("https://tracker2.org:443/announce", parsed.trackers[1])
        assertEquals(1, parsed.webSeeds.size)
        assertEquals("https://cdn.example.com/file.iso", parsed.webSeeds[0])
        assertEquals("linux distro iso", parsed.keywords)
    }

    @Test
    fun `test Parse Magnet with Unicode and Escaped Characters`() {
        val magnet = "magnet:?xt=urn:btih:4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a&dn=%E4%B8%AD%E6%96%87%E6%B5%8B%E8%AF%95%20%26%20Special%20%5B2026%5D"

        val parsed = MagnetHandler.parse(magnet)
        assertNotNull(parsed)
        assertEquals("中文测试 & Special [2026]", parsed!!.displayName)
    }

    @Test
    fun `test Parse Malformed Magnet URIs returns null safely`() {
        // Null & Empty
        assertNull(MagnetHandler.parse(null))
        assertNull(MagnetHandler.parse(""))
        assertNull(MagnetHandler.parse("   "))

        // Missing magnet:?
        assertNull(MagnetHandler.parse("http://example.com/test"))
        assertNull(MagnetHandler.parse("xt=urn:btih:4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a"))

        // Missing xt parameter
        assertNull(MagnetHandler.parse("magnet:?dn=NoHash"))

        // Unsupported xt URN
        assertNull(MagnetHandler.parse("magnet:?xt=urn:sha1:4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a"))
        assertNull(MagnetHandler.parse("magnet:?xt=urn:ed2k:4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a"))

        // Invalid hash length
        assertNull(MagnetHandler.parse("magnet:?xt=urn:btih:tooshort"))
        assertNull(MagnetHandler.parse("magnet:?xt=urn:btih:12345678901234567890123456789012345678901234567890")) // 50 chars
    }

    @Test
    fun `test Parse Magnet with Corrupted Percent-Encoding does not crash`() {
        val corruptedMagnet = "magnet:?xt=urn:btih:4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a&dn=Broken%2G%&tr=https%3A%2F%2Ftracker.org"
        val parsed = MagnetHandler.parse(corruptedMagnet)

        assertNotNull(parsed)
        assertEquals("4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a", parsed!!.infoHash)
    }

    @Test
    fun `test Round-Trip Serialization Fidelity`() {
        val original = MagnetInfo(
            infoHash = "c12fe1c06bba254a9dc9f519b335de7ece74f6d2",
            displayName = "SourZap E2E Test Payload",
            fileLength = 10485760L,
            trackers = listOf("https://tracker.tamersunion.org:443/announce", "https://tracker.loligirl.cn:443/announce"),
            webSeeds = listOf("https://cdn.sourzap.app/test.bin"),
            keywords = "sourzap bittorrent test"
        )

        val uri = original.toUri()
        val parsed = MagnetHandler.parse(uri)

        assertNotNull(parsed)
        assertEquals(original.infoHash, parsed!!.infoHash)
        assertEquals(original.displayName, parsed.displayName)
        assertEquals(original.fileLength, parsed.fileLength)
        assertEquals(original.trackers, parsed.trackers)
        assertEquals(original.webSeeds, parsed.webSeeds)
        assertEquals(original.keywords, parsed.keywords)
    }
}
