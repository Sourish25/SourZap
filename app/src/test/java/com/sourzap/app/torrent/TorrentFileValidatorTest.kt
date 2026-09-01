package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.BencodeValidator
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.core.TrackerInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Comprehensive Unit Test Suite for [TorrentFileValidator] and [BencodeValidator].
 * Verifies Milestone 1 Requirements:
 * - Binary-safe bencode parser and validator
 * - Valid single-file and multi-file torrent parsing
 * - Corrupted, truncated, empty, and invalid piece length buffers
 * - Cloudflare, HTML, XML, JSON, and web error payloads
 * - Multi-byte UTF-8 (Japanese, Cyrillic, Emoji) and binary encodings
 * - InfoHash SHA-1 calculation
 * - Port-443 HTTPS tracker injection
 */
class TorrentFileValidatorTest {

    // =========================================================================
    // Helper Methods to build binary bencoded torrent buffers
    // =========================================================================

    private fun createSingleFileTorrent(
        name: String = "test.iso",
        fileLength: Long = 1048576L,
        pieceLength: Int = 262144,
        pieceCount: Int = 4,
        announce: String = "https://tracker.example.com/announce",
        nameUtf8: String? = null
    ): ByteArray {
        val piecesBytes = ByteArray(pieceCount * 20) { (it % 256).toByte() }
        val out = ByteArrayOutputStream()

        val announceBytes = announce.toByteArray(StandardCharsets.UTF_8)
        out.write("d8:announce${announceBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(announceBytes)
        out.write("4:infod".toByteArray(StandardCharsets.US_ASCII))
        out.write("6:lengthi${fileLength}e".toByteArray(StandardCharsets.US_ASCII))

        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        out.write("4:name${nameBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(nameBytes)

        if (nameUtf8 != null) {
            val utf8Bytes = nameUtf8.toByteArray(StandardCharsets.UTF_8)
            out.write("10:name.utf-8${utf8Bytes.size}:".toByteArray(StandardCharsets.US_ASCII))
            out.write(utf8Bytes)
        }

        out.write("12:piece lengthi${pieceLength}e".toByteArray(StandardCharsets.US_ASCII))
        out.write("6:pieces${piecesBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(piecesBytes)
        out.write("ee".toByteArray(StandardCharsets.US_ASCII))

        return out.toByteArray()
    }

    private fun createMultiFileTorrent(
        dirName: String = "MultiRelease",
        files: List<Pair<String, Long>> = listOf("file1.txt" to 100L, "sub/file2.bin" to 200L, "file3.iso" to 300L),
        pieceLength: Int = 16384,
        pieceCount: Int = 2,
        announce: String = "https://tracker.example.com/announce"
    ): ByteArray {
        val piecesBytes = ByteArray(pieceCount * 20) { (it % 256).toByte() }
        val out = ByteArrayOutputStream()

        val announceBytes = announce.toByteArray(StandardCharsets.UTF_8)
        out.write("d8:announce${announceBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(announceBytes)
        out.write("4:infod".toByteArray(StandardCharsets.US_ASCII))
        out.write("5:filesl".toByteArray(StandardCharsets.US_ASCII))

        for ((path, len) in files) {
            out.write("d6:lengthi${len}e4:pathl".toByteArray(StandardCharsets.US_ASCII))
            val segments = path.split("/")
            for (seg in segments) {
                val segBytes = seg.toByteArray(StandardCharsets.UTF_8)
                out.write("${segBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
                out.write(segBytes)
            }
            out.write("ee".toByteArray(StandardCharsets.US_ASCII))
        }
        out.write("e".toByteArray(StandardCharsets.US_ASCII)) // end files list

        val dirBytes = dirName.toByteArray(StandardCharsets.UTF_8)
        out.write("4:name${dirBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(dirBytes)

        out.write("12:piece lengthi${pieceLength}e".toByteArray(StandardCharsets.US_ASCII))
        out.write("6:pieces${piecesBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(piecesBytes)
        out.write("ee".toByteArray(StandardCharsets.US_ASCII))

        return out.toByteArray()
    }

    // =========================================================================
    // 1. Valid Single-File and Multi-File Torrents
    // =========================================================================

    @Test
    fun testValidate_ValidSingleFileTorrent() {
        val torrentBytes = createSingleFileTorrent(
            name = "ubuntu-24.04-desktop-amd64.iso",
            fileLength = 5000000000L,
            pieceLength = 262144,
            pieceCount = 5
        )

        val result = TorrentFileValidator.validate(torrentBytes)
        assertTrue("Expected Valid result, got: $result", result is TorrentValidationResult.Valid)

        val valid = result as TorrentValidationResult.Valid
        assertEquals("ubuntu-24.04-desktop-amd64.iso", valid.name)
        assertEquals(5000000000L, valid.totalSize)
        assertFalse("Must not be multi-file", valid.isMultiFile)
        assertEquals(1, valid.fileCount)
        assertEquals(262144, valid.pieceLength)
        assertEquals(5, valid.pieceCount)
        assertNotNull(valid.infoHash)
        assertEquals(40, valid.infoHash!!.length)
        assertTrue("InfoHash must be lowercase hex", valid.infoHash!!.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testValidate_ValidMultiFileTorrent() {
        val files = listOf(
            "folder/track01.mp3" to 10485760L,
            "folder/track02.mp3" to 12582912L,
            "folder/cover.jpg" to 524288L
        )
        val expectedTotalSize = 10485760L + 12582912L + 524288L

        val torrentBytes = createMultiFileTorrent(
            dirName = "CoolAlbum",
            files = files,
            pieceLength = 65536,
            pieceCount = 10
        )

        val result = TorrentFileValidator.validate(torrentBytes)
        assertTrue("Expected Valid result, got: $result", result is TorrentValidationResult.Valid)

        val valid = result as TorrentValidationResult.Valid
        assertEquals("CoolAlbum", valid.name)
        assertEquals(expectedTotalSize, valid.totalSize)
        assertTrue("Must be multi-file", valid.isMultiFile)
        assertEquals(3, valid.fileCount)
        assertEquals(65536, valid.pieceLength)
        assertEquals(10, valid.pieceCount)
        assertNotNull(valid.infoHash)
        assertEquals(40, valid.infoHash!!.length)
    }

    @Test
    fun testValidate_InfoHashCalculationMatchesSha1OfInfoDict() {
        val torrentBytes = createSingleFileTorrent(
            name = "hash_verify.dat",
            fileLength = 2048L,
            pieceLength = 16384,
            pieceCount = 1
        )

        val result = TorrentFileValidator.validate(torrentBytes) as TorrentValidationResult.Valid

        // Extract info dict bytes manually from torrentBytes to verify SHA-1
        val announcePrefix = "d8:announce36:https://tracker.example.com/announce4:info".toByteArray(StandardCharsets.UTF_8).size
        val infoBytes = torrentBytes.copyOfRange(announcePrefix, torrentBytes.size - 1) // exclude final 'e'
        val expectedSha1 = MessageDigest.getInstance("SHA-1").digest(infoBytes).joinToString("") { "%02x".format(it) }

        assertEquals(expectedSha1, result.infoHash)
    }

    // =========================================================================
    // 2. Multi-Byte UTF-8 & Character Encodings
    // =========================================================================

    @Test
    fun testValidate_MultiByteJapaneseName() {
        val japaneseName = "日本語テスト_アニメ.mkv"
        val bytes = createSingleFileTorrent(name = japaneseName)

        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        assertEquals(japaneseName, (result as TorrentValidationResult.Valid).name)
    }

    @Test
    fun testValidate_MultiByteCyrillicName() {
        val cyrillicName = "Архив_Документов_2026.zip"
        val bytes = createSingleFileTorrent(name = cyrillicName)

        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        assertEquals(cyrillicName, (result as TorrentValidationResult.Valid).name)
    }

    @Test
    fun testValidate_MultiByteEmojiName() {
        val emojiName = "🚀 Fast Distro 🔥 Release.iso"
        val bytes = createSingleFileTorrent(name = emojiName)

        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        assertEquals(emojiName, (result as TorrentValidationResult.Valid).name)
    }

    @Test
    fun testValidate_NameUtf8KeyTakesPrecedenceOverName() {
        val legacyName = "legacy_ascii_name.txt"
        val utf8Name = "高級日本語タイトル.txt"

        val bytes = createSingleFileTorrent(
            name = legacyName,
            nameUtf8 = utf8Name
        )

        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        assertEquals(utf8Name, (result as TorrentValidationResult.Valid).name)
    }

    // =========================================================================
    // 3. Web / HTML / XML / JSON / Cloudflare Payloads Detection
    // =========================================================================

    @Test
    fun testValidate_HtmlDoctypePayload_ReturnsIsHtmlPayload() {
        val html = "<!DOCTYPE html><html><head><title>Cloudflare DDOS</title></head><body>Just a moment...</body></html>"
        val bytes = html.toByteArray(StandardCharsets.UTF_8)

        val result = TorrentFileValidator.validate(bytes)
        assertTrue("Result must be Invalid", result is TorrentValidationResult.Invalid)

        val invalid = result as TorrentValidationResult.Invalid
        assertTrue("Must flag as isHtmlPayload", invalid.isHtmlPayload)
        assertEquals("Web page / Error payload", invalid.reason)
        assertTrue(invalid.detailedMessage.contains("web page or error response"))
    }

    @Test
    fun testValidate_HtmlHeadPayload_ReturnsIsHtmlPayload() {
        val html = "<html><head><title>404 Not Found</title></head><body><h1>404 Not Found</h1></body></html>"
        val bytes = html.toByteArray(StandardCharsets.UTF_8)

        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertTrue((result as TorrentValidationResult.Invalid).isHtmlPayload)
    }

    @Test
    fun testValidate_XmlErrorPayload_ReturnsIsHtmlPayload() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>"
        val bytes = xml.toByteArray(StandardCharsets.UTF_8)

        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertTrue((result as TorrentValidationResult.Invalid).isHtmlPayload)
    }

    @Test
    fun testValidate_JsonErrorPayload_ReturnsIsHtmlPayload() {
        val json = "{\"error\": \"Torrent file not found\", \"status\": 404, \"code\": \"NOT_FOUND\"}"
        val bytes = json.toByteArray(StandardCharsets.UTF_8)

        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertTrue((result as TorrentValidationResult.Invalid).isHtmlPayload)
    }

    @Test
    fun testValidate_Http302RedirectResponse_ReturnsIsHtmlPayload() {
        val http = "HTTP/1.1 302 Found\r\nLocation: https://tracker.com/login\r\nContent-Length: 0\r\n\r\n"
        val bytes = http.toByteArray(StandardCharsets.UTF_8)

        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertTrue((result as TorrentValidationResult.Invalid).isHtmlPayload)
    }

    @Test
    fun testValidate_CloudflareChallengePage_ReturnsIsHtmlPayload() {
        val cf = "<div class=\"cf-browser-verification\">Checking your browser before accessing... Ray ID: 8943ab293fe</div>"
        val bytes = cf.toByteArray(StandardCharsets.UTF_8)

        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertTrue((result as TorrentValidationResult.Invalid).isHtmlPayload)
    }

    // =========================================================================
    // 4. Corrupted, Truncated, and Empty Buffers
    // =========================================================================

    @Test
    fun testValidate_NullBuffer_ReturnsEmptyFileError() {
        val result = TorrentFileValidator.validate(null as ByteArray?)
        assertTrue(result is TorrentValidationResult.Invalid)
        val invalid = result as TorrentValidationResult.Invalid
        assertEquals("Empty file", invalid.reason)
        assertFalse(invalid.isHtmlPayload)
    }

    @Test
    fun testValidate_EmptyBuffer_ReturnsEmptyFileError() {
        val result = TorrentFileValidator.validate(ByteArray(0))
        assertTrue(result is TorrentValidationResult.Invalid)
        val invalid = result as TorrentValidationResult.Invalid
        assertEquals("Empty file", invalid.reason)
        assertFalse(invalid.isHtmlPayload)
    }

    @Test
    fun testValidate_TooSmallBuffer_ReturnsFileTooSmall() {
        val result = TorrentFileValidator.validate("d4:infoe".toByteArray(StandardCharsets.UTF_8))
        assertTrue(result is TorrentValidationResult.Invalid)
        val invalid = result as TorrentValidationResult.Invalid
        assertEquals("File too small", invalid.reason)
    }

    @Test
    fun testValidate_MissingStartingDictChar_ReturnsInvalidHeader() {
        val invalidBytes = "123456789012345".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(invalidBytes)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Invalid bencode header", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidate_MissingEndingDictChar_ReturnsCorruptedBencode() {
        val bytes = createSingleFileTorrent()
        val truncated = bytes.copyOf(bytes.size - 1) // strip last 'e'

        val result = TorrentFileValidator.validate(truncated)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Corrupted bencode", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidate_TruncatedInsideInfoDict_ReturnsCorruptedBencode() {
        val truncated = "d4:infod12:piece lengthi16384e".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(truncated)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Corrupted bencode", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidate_MissingInfoDict_ReturnsMissingInfoDictionary() {
        val noInfoBytes = "d8:announce26:http://tracker.example.come".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(noInfoBytes)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Missing info dictionary", (result as TorrentValidationResult.Invalid).reason)
    }

    // =========================================================================
    // 5. Piece Length and Pieces Validation
    // =========================================================================

    @Test
    fun testValidate_ZeroPieceLength_ReturnsInvalidPieceLength() {
        val badPieceLength = "d4:infod6:lengthi100e4:name4:test12:piece lengthi0e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(badPieceLength)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Invalid piece length", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidate_NegativePieceLength_ReturnsInvalidPieceLength() {
        val badPieceLength = "d4:infod6:lengthi100e4:name4:test12:piece lengthi-16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(badPieceLength)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Invalid piece length", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidate_PiecesNotMultipleOf20_ReturnsInvalidPieces() {
        // 19 bytes pieces string
        val badPieces = "d4:infod6:lengthi100e4:name4:test12:piece lengthi16384e6:pieces19:1234567890123456789ee".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(badPieces)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Invalid pieces", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidate_EmptyPiecesString_ReturnsInvalidPieces() {
        val emptyPieces = "d4:infod6:lengthi100e4:name4:test12:piece lengthi16384e6:pieces0:ee".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(emptyPieces)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Invalid pieces", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidate_MissingBothLengthAndFiles_ReturnsMissingLengthOrFiles() {
        val noLengthOrFiles = "d4:infod4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(noLengthOrFiles)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Missing length or files", (result as TorrentValidationResult.Invalid).reason)
    }

    // =========================================================================
    // 6. File & BencodeValidator Alias Tests
    // =========================================================================

    @Test
    fun testValidate_FileValidation_SuccessAndNonExistent() {
        val tempFile = File.createTempFile("sourzap_test", ".torrent")
        tempFile.deleteOnExit()

        val validBytes = createSingleFileTorrent(name = "temp_test.bin", fileLength = 4096L)
        tempFile.writeBytes(validBytes)

        val fileResult = TorrentFileValidator.validate(tempFile)
        assertTrue(fileResult is TorrentValidationResult.Valid)
        assertEquals("temp_test.bin", (fileResult as TorrentValidationResult.Valid).name)

        val nonExistent = File(tempFile.parentFile, "does_not_exist_${System.currentTimeMillis()}.torrent")
        val errorResult = TorrentFileValidator.validate(nonExistent)
        assertTrue(errorResult is TorrentValidationResult.Invalid)
        assertEquals("File not found", (errorResult as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testBencodeValidatorAlias_IdenticalBehavior() {
        val validBytes = createSingleFileTorrent(name = "alias.iso")
        val v1 = TorrentFileValidator.validate(validBytes)
        val v2 = BencodeValidator.validate(validBytes)

        assertEquals(v1, v2)
        assertTrue(TorrentFileValidator.isValidTorrent(validBytes))
        assertTrue(BencodeValidator.isValidTorrent(validBytes))
    }

    // =========================================================================
    // 7. TrackerInjector Helper and Invariants
    // =========================================================================

    @Test
    fun testTrackerInjector_CatalogProperties() {
        val trackers = TrackerInjector.HTTPS_PORT_443_TRACKERS
        assertTrue(trackers.size >= 20)
        for (tr in trackers) {
            assertTrue(tr.startsWith("https://"))
            assertTrue(tr.contains("announce"))
        }
    }
}
