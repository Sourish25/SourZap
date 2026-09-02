package com.sourzap.app.torrent.core

import com.sourzap.app.torrent.model.PreDownloadFileItem
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Typed result returned by [TorrentFileValidator.validate].
 */
sealed class TorrentValidationResult {
    data class Valid(
        val name: String,
        val totalSize: Long,
        val isMultiFile: Boolean,
        val pieceLength: Int,
        val pieceCount: Int,
        val infoHash: String?,
        val fileCount: Int = 1,
        val files: List<PreDownloadFileItem> = emptyList()
    ) : TorrentValidationResult()

    data class Invalid(
        val reason: String,
        val detailedMessage: String,
        val isHtmlPayload: Boolean = false
    ) : TorrentValidationResult()
}

/**
 * Alias object matching the interface contract specified in PROJECT.md.
 */
object BencodeValidator {
    fun validate(bytes: ByteArray?): TorrentValidationResult = TorrentFileValidator.validate(bytes)
    fun validate(file: File): TorrentValidationResult = TorrentFileValidator.validate(file)
    fun isValidTorrent(bytes: ByteArray?): Boolean = TorrentFileValidator.isValidTorrent(bytes)
    fun extractFiles(bytes: ByteArray?): List<PreDownloadFileItem> = TorrentFileValidator.extractFiles(bytes)
    fun extractFiles(file: File): List<PreDownloadFileItem> = TorrentFileValidator.extractFiles(file)
}

/**
 * Binary-safe BitTorrent bencode parser and pre-validator.
 *
 * Operates directly on byte arrays to safely inspect bencoded data without
 * converting raw binary piece hashes or control bytes into UTF-8 strings.
 *
 * Detects HTML/XML/JSON error responses, Cloudflare interstitials, truncated
 * buffers, invalid piece hash lengths, and extracts metadata (name, totalSize,
 * multi-file structure, piece length, piece count, and SHA-1 infoHash).
 */
object TorrentFileValidator {

    fun isValidTorrent(bytes: ByteArray?): Boolean {
        return validate(bytes) is TorrentValidationResult.Valid
    }

    fun extractFiles(bytes: ByteArray?): List<PreDownloadFileItem> {
        val result = validate(bytes)
        return (result as? TorrentValidationResult.Valid)?.files ?: emptyList()
    }

    fun extractFiles(file: File): List<PreDownloadFileItem> {
        val result = validate(file)
        return (result as? TorrentValidationResult.Valid)?.files ?: emptyList()
    }

    fun validate(file: File): TorrentValidationResult {
        if (!file.exists() || !file.isFile) {
            return TorrentValidationResult.Invalid(
                reason = "File not found",
                detailedMessage = "The specified .torrent file does not exist or is not a regular file: ${file.absolutePath}",
                isHtmlPayload = false
            )
        }
        return try {
            val bytes = file.readBytes()
            validate(bytes)
        } catch (e: Exception) {
            TorrentValidationResult.Invalid(
                reason = "Read error",
                detailedMessage = "Failed to read .torrent file from disk: ${e.message}",
                isHtmlPayload = false
            )
        }
    }

    fun validate(bytes: ByteArray?): TorrentValidationResult {
        if (bytes == null || bytes.isEmpty()) {
            return TorrentValidationResult.Invalid(
                reason = "Empty file",
                detailedMessage = "The file is empty (0 bytes).",
                isHtmlPayload = false
            )
        }

        if (bytes.size < 11) {
            return TorrentValidationResult.Invalid(
                reason = "File too small",
                detailedMessage = "The file is too small to be a valid .torrent file (${bytes.size} bytes).",
                isHtmlPayload = false
            )
        }

        // Fast-path detection of HTML, XML, JSON, HTTP error payloads, and Cloudflare interstitials
        if (isWebOrErrorPayload(bytes)) {
            return TorrentValidationResult.Invalid(
                reason = "Web page / Error payload",
                detailedMessage = "The file appears to be a web page or error response (HTML/XML/JSON), not a valid .torrent file.",
                isHtmlPayload = true
            )
        }

        // Bencoded root element must be a dictionary: starts with 'd' (0x64) and ends with 'e' (0x65)
        if (bytes[0] != 'd'.code.toByte()) {
            return TorrentValidationResult.Invalid(
                reason = "Invalid bencode header",
                detailedMessage = "The file does not start with a bencoded dictionary ('d').",
                isHtmlPayload = false
            )
        }

        if (bytes[bytes.size - 1] != 'e'.code.toByte()) {
            return TorrentValidationResult.Invalid(
                reason = "Corrupted bencode",
                detailedMessage = "The file does not end with a bencoded dictionary terminator ('e').",
                isHtmlPayload = false
            )
        }

        // Parse bencoded dictionary safely using byte offsets
        val parser = BencodeParser(bytes)
        val rootValue = try {
            parser.parseValue()
        } catch (e: Exception) {
            return TorrentValidationResult.Invalid(
                reason = "Corrupted bencode",
                detailedMessage = "The file contains corrupted or malformed bencoded data: ${e.message}",
                isHtmlPayload = false
            )
        }

        if (rootValue !is BencodeValue.BDict) {
            return TorrentValidationResult.Invalid(
                reason = "Invalid root element",
                detailedMessage = "The root element of a .torrent file must be a bencoded dictionary.",
                isHtmlPayload = false
            )
        }

        // Check for 'info' dictionary
        val infoVal = rootValue.entries["info"]
        if (infoVal == null || infoVal !is BencodeValue.BDict) {
            return TorrentValidationResult.Invalid(
                reason = "Missing info dictionary",
                detailedMessage = "The .torrent file is missing the required 'info' dictionary.",
                isHtmlPayload = false
            )
        }

        // Calculate SHA-1 infoHash from exact raw bytes of the info dictionary
        val infoBytes = bytes.copyOfRange(infoVal.start, infoVal.end)
        val infoHash = try {
            val digest = MessageDigest.getInstance("SHA-1").digest(infoBytes)
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }

        // Validate 'piece length' (positive integer)
        val pieceLengthVal = infoVal.entries["piece length"]
        if (pieceLengthVal == null || pieceLengthVal !is BencodeValue.BInt || pieceLengthVal.value <= 0) {
            return TorrentValidationResult.Invalid(
                reason = "Invalid piece length",
                detailedMessage = "The 'piece length' field is missing or not a positive integer.",
                isHtmlPayload = false
            )
        }
        val pieceLength = pieceLengthVal.value.toInt()

        // Validate 'pieces' binary string (length > 0 and divisible by 20 for SHA-1 hashes)
        val piecesVal = infoVal.entries["pieces"]
        var pieceCount = 0
        if (piecesVal != null) {
            if (piecesVal !is BencodeValue.BString || piecesVal.data.isEmpty() || piecesVal.data.size % 20 != 0) {
                val len = (piecesVal as? BencodeValue.BString)?.data?.size ?: 0
                return TorrentValidationResult.Invalid(
                    reason = "Invalid pieces",
                    detailedMessage = "The 'pieces' field is missing, empty, or not a multiple of 20 bytes ($len bytes).",
                    isHtmlPayload = false
                )
            }
            pieceCount = piecesVal.data.size / 20
        } else {
            // Check BEP 52 v2 piece layers / meta version
            val metaVersion = (infoVal.entries["meta version"] as? BencodeValue.BInt)?.value
            val pieceLayers = rootValue.entries["piece layers"] as? BencodeValue.BDict
            if (metaVersion != 2L && pieceLayers == null) {
                return TorrentValidationResult.Invalid(
                    reason = "Invalid pieces",
                    detailedMessage = "The 'pieces' field is missing from the info dictionary.",
                    isHtmlPayload = false
                )
            }
        }

        // Extract name (prefer 'name.utf-8', fallback to 'name')
        val nameVal = infoVal.entries["name.utf-8"] ?: infoVal.entries["name"]
        val name = when (nameVal) {
            is BencodeValue.BString -> nameVal.asUtf8String().ifBlank { "Unnamed" }
            else -> "Unnamed"
        }

        // Validate single-file ('length') vs multi-file ('files' list)
        val lengthVal = infoVal.entries["length"]
        val filesVal = infoVal.entries["files"]

        val fileItems = mutableListOf<PreDownloadFileItem>()
        var totalSize = 0L
        var isMultiFile = false
        var fileCount = 1

        if (filesVal != null) {
            if (filesVal !is BencodeValue.BList) {
                return TorrentValidationResult.Invalid(
                    reason = "Invalid files list",
                    detailedMessage = "The 'files' field is not a valid list.",
                    isHtmlPayload = false
                )
            }
            if (filesVal.values.isEmpty()) {
                return TorrentValidationResult.Invalid(
                    reason = "Empty files list",
                    detailedMessage = "The 'files' list is empty.",
                    isHtmlPayload = false
                )
            }
            isMultiFile = true
            fileCount = filesVal.values.size
            var sumSize = 0L
            for ((idx, fileElem) in filesVal.values.withIndex()) {
                if (fileElem !is BencodeValue.BDict) {
                    return TorrentValidationResult.Invalid(
                        reason = "Invalid file entry",
                        detailedMessage = "File entry #$idx is not a valid dictionary.",
                        isHtmlPayload = false
                    )
                }
                val fLen = fileElem.entries["length"]
                if (fLen == null || fLen !is BencodeValue.BInt || fLen.value < 0) {
                    return TorrentValidationResult.Invalid(
                        reason = "Invalid file length",
                        detailedMessage = "File entry #$idx has an invalid length.",
                        isHtmlPayload = false
                    )
                }
                val pathElem = fileElem.entries["path.utf-8"] ?: fileElem.entries["path"]
                if (pathElem == null || pathElem !is BencodeValue.BList || pathElem.values.isEmpty()) {
                    return TorrentValidationResult.Invalid(
                        reason = "Invalid file path",
                        detailedMessage = "File entry #$idx has an invalid path.",
                        isHtmlPayload = false
                    )
                }
                val segments = pathElem.values.mapNotNull {
                    (it as? BencodeValue.BString)?.asUtf8String()
                }
                val relativePath = if (segments.isNotEmpty()) segments.joinToString("/") else "file_$idx"
                sumSize += fLen.value
                fileItems.add(
                    PreDownloadFileItem(
                        index = idx,
                        path = relativePath,
                        size = fLen.value,
                        isSelected = true
                    )
                )
            }
            totalSize = sumSize
        } else if (lengthVal != null) {
            if (lengthVal !is BencodeValue.BInt || lengthVal.value < 0) {
                return TorrentValidationResult.Invalid(
                    reason = "Invalid length",
                    detailedMessage = "The 'length' field is not a valid non-negative integer.",
                    isHtmlPayload = false
                )
            }
            isMultiFile = false
            fileCount = 1
            totalSize = lengthVal.value
            fileItems.add(
                PreDownloadFileItem(
                    index = 0,
                    path = name,
                    size = totalSize,
                    isSelected = true
                )
            )
        } else {
            return TorrentValidationResult.Invalid(
                reason = "Missing length or files",
                detailedMessage = "The .torrent file contains neither 'length' nor 'files' specification.",
                isHtmlPayload = false
            )
        }

        // For BEP 52 v2 torrents without v1 pieces string, compute pieceCount from total size and piece length
        if (pieceCount == 0 && totalSize > 0 && pieceLength > 0) {
            pieceCount = ((totalSize + pieceLength - 1) / pieceLength).toInt()
        }

        return TorrentValidationResult.Valid(
            name = name,
            totalSize = totalSize,
            isMultiFile = isMultiFile,
            pieceLength = pieceLength,
            pieceCount = pieceCount,
            infoHash = infoHash,
            fileCount = fileCount,
            files = fileItems
        )
    }

    private fun isWebOrErrorPayload(bytes: ByteArray): Boolean {
        val checkLen = minOf(bytes.size, 2048)
        val sample = String(bytes, 0, checkLen, StandardCharsets.ISO_8859_1).trim().lowercase()

        // Explicit HTML/XML prefixes
        if (sample.startsWith("<!doctype") ||
            sample.startsWith("<html") ||
            sample.startsWith("<?xml") ||
            sample.startsWith("<!") ||
            sample.startsWith("<head") ||
            sample.startsWith("<body") ||
            sample.startsWith("<script")
        ) {
            return true
        }

        // JSON error response signatures
        if (sample.startsWith("{\"") || sample.startsWith("{ \"") || sample.startsWith("{\n")) {
            if (sample.contains("\"error\"") ||
                sample.contains("\"message\"") ||
                sample.contains("\"status\"") ||
                sample.contains("\"code\"") ||
                sample.contains("\"detail\"") ||
                sample.contains("\"success\": false") ||
                sample.contains("\"success\":false")
            ) {
                return true
            }
        }

        // Cloudflare / DDoS / Captcha / WAF challenge pages
        if (sample.contains("<html") || sample.contains("<!doctype html")) {
            return true
        }
        if (sample.contains("cloudflare") && (sample.contains("ray id") || sample.contains("challenge") || sample.contains("turnstile"))) {
            return true
        }
        if (sample.contains("just a moment...") ||
            sample.contains("ddos-guard") ||
            sample.contains("attention required!") ||
            sample.contains("checking your browser")
        ) {
            return true
        }

        // HTTP response status lines
        if (sample.startsWith("http/1.") || sample.startsWith("http/2")) {
            return true
        }
        if (sample.contains("302 found") ||
            sample.contains("403 forbidden") ||
            sample.contains("404 not found") ||
            sample.contains("502 bad gateway") ||
            sample.contains("503 service temporarily unavailable")
        ) {
            return true
        }

        return false
    }

    private sealed class BencodeValue {
        data class BString(val data: ByteArray, val start: Int, val end: Int) : BencodeValue() {
            fun asUtf8String(): String = try {
                val str = String(data, StandardCharsets.UTF_8)
                // If malformed replacement char occurs on binary data, preserve as ISO-8859-1
                if (str.contains('\uFFFD') && !String(data, StandardCharsets.ISO_8859_1).contains('\uFFFD')) {
                    String(data, StandardCharsets.ISO_8859_1)
                } else {
                    str
                }
            } catch (_: Exception) {
                String(data, StandardCharsets.ISO_8859_1)
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is BString) return false
                return data.contentEquals(other.data)
            }

            override fun hashCode(): Int = data.contentHashCode()
        }

        data class BInt(val value: Long, val start: Int, val end: Int) : BencodeValue()
        data class BList(val values: List<BencodeValue>, val start: Int, val end: Int) : BencodeValue()
        data class BDict(val entries: Map<String, BencodeValue>, val start: Int, val end: Int) : BencodeValue()
    }

    private class BencodeParser(private val data: ByteArray) {
        var pos = 0
            private set

        fun parseValue(): BencodeValue {
            if (pos >= data.size) {
                throw IllegalArgumentException("Unexpected EOF while parsing bencode value at offset $pos")
            }
            return when (val b = data[pos]) {
                'i'.code.toByte() -> parseInt()
                'l'.code.toByte() -> parseList()
                'd'.code.toByte() -> parseDict()
                in '0'.code.toByte()..'9'.code.toByte() -> parseString()
                else -> throw IllegalArgumentException("Unexpected byte '${b.toInt().toChar()}' (0x${(b.toInt() and 0xFF).toString(16)}) at offset $pos")
            }
        }

        private fun parseInt(): BencodeValue.BInt {
            val start = pos
            pos++ // skip 'i'
            val endPos = indexOfByte('e'.code.toByte(), pos)
            if (endPos == -1) {
                throw IllegalArgumentException("Unterminated integer starting at offset $start")
            }
            val intStr = String(data, pos, endPos - pos, StandardCharsets.US_ASCII)
            if (intStr.isEmpty()) {
                throw IllegalArgumentException("Empty integer at offset $start")
            }
            if (intStr == "-0" || (intStr.length > 1 && intStr.startsWith("0")) || (intStr.length > 2 && intStr.startsWith("-0"))) {
                throw IllegalArgumentException("Malformed integer '$intStr' at offset $start")
            }
            val value = intStr.toLongOrNull() ?: throw IllegalArgumentException("Invalid integer value '$intStr' at offset $start")
            pos = endPos + 1 // skip 'e'
            return BencodeValue.BInt(value, start, pos)
        }

        private fun parseString(): BencodeValue.BString {
            val start = pos
            val colonPos = indexOfByte(':'.code.toByte(), pos)
            if (colonPos == -1) {
                throw IllegalArgumentException("Unterminated string length at offset $start")
            }
            val lenStr = String(data, pos, colonPos - pos, StandardCharsets.US_ASCII)
            if (lenStr.isEmpty() || (lenStr.length > 1 && lenStr.startsWith("0"))) {
                throw IllegalArgumentException("Malformed string length '$lenStr' at offset $start")
            }
            val length = lenStr.toIntOrNull() ?: throw IllegalArgumentException("Invalid string length '$lenStr' at offset $start")
            if (length < 0) {
                throw IllegalArgumentException("Negative string length $length at offset $start")
            }
            pos = colonPos + 1
            if (length < 0 || length.toLong() > data.size - pos || pos + length > data.size) {
                throw IllegalArgumentException("String length $length exceeds buffer length (offset: $pos, buffer size: ${data.size})")
            }
            val strBytes = data.copyOfRange(pos, pos + length)
            pos += length
            return BencodeValue.BString(strBytes, start, pos)
        }

        private fun parseList(): BencodeValue.BList {
            val start = pos
            pos++ // skip 'l'
            val list = mutableListOf<BencodeValue>()
            while (pos < data.size && data[pos] != 'e'.code.toByte()) {
                list.add(parseValue())
            }
            if (pos >= data.size || data[pos] != 'e'.code.toByte()) {
                throw IllegalArgumentException("Unterminated list starting at offset $start")
            }
            pos++ // skip 'e'
            return BencodeValue.BList(list, start, pos)
        }

        private fun parseDict(): BencodeValue.BDict {
            val start = pos
            pos++ // skip 'd'
            val map = mutableMapOf<String, BencodeValue>()
            while (pos < data.size && data[pos] != 'e'.code.toByte()) {
                val keyVal = parseString()
                val key = keyVal.asUtf8String()
                val value = parseValue()
                map[key] = value
            }
            if (pos >= data.size || data[pos] != 'e'.code.toByte()) {
                throw IllegalArgumentException("Unterminated dictionary starting at offset $start")
            }
            pos++ // skip 'e'
            return BencodeValue.BDict(map, start, pos)
        }

        private fun indexOfByte(target: Byte, from: Int): Int {
            for (i in from until data.size) {
                if (data[i] == target) return i
            }
            return -1
        }
    }
}
