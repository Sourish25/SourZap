package com.sourzap.app.torrent

import android.content.Intent
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentIntentParser
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.model.PendingTorrentIntent
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Empirical Adversarial Challenger Test Suite for Milestone 2 and M1 Refinements in SourZap.
 *
 * Exhaustively stress-tests:
 * 1. [TorrentFileValidator] (Milestone 1 Refinements & Attack Vectors):
 *    - Integer overflow attack vectors in bencode string length headers (> Int.MAX_VALUE, Long.MAX_VALUE)
 *    - Signed integer overflow checks (pos + length arithmetic safety)
 *    - Negative string lengths (-1:, -100:, -2147483648:)
 *    - Leading zeros in string lengths (04:test) and integer values (i03e, i00e, i-0e)
 *    - Integer overflow in bencode integer values (> Long.MAX_VALUE, < Long.MIN_VALUE)
 *    - Large string lengths exceeding buffer bounds (10,000,000 length in 50 byte buffer)
 *    - Unterminated string lengths and truncated bencode payloads
 *    - Multi-file torrent validation (empty files list, non-dict elements, invalid length/path)
 *    - Info dictionary validation (missing info, non-dict info, piece length <= 0, pieces not multiple of 20)
 *    - Web / Error payload detection (HTML doctypes, XML, JSON errors, Cloudflare, HTTP status lines)
 *    - SHA-1 infoHash accuracy matching raw bytes
 *
 * 2. File Picker MIME Contracts & SAF Stream Reading:
 *    - System file picker MIME contract matching (application/x-bittorrent, application/x-torrent, application/octet-stream)
 *    - Intent parser MIME recognition (mixed case, variations)
 *    - SAF URI stream reading and display name resolution
 */
class TorrentM2EmpiricalStressChallengeTest {

    private val tempDir = File(
        System.getProperty("java.io.tmpdir") ?: ".",
        "sourzap_m2_challenger_${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
    )

    @Before
    fun setUp() {
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // Helper Methods to build binary bencoded torrent buffers
    // =========================================================================

    private fun createSingleFileTorrent(
        name: String = "test_file.iso",
        fileLength: Long = 1048576L,
        pieceLength: Int = 262144,
        pieceCount: Int = 4,
        announce: String = "https://tracker.example.com/announce"
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

        out.write("12:piece lengthi${pieceLength}e".toByteArray(StandardCharsets.US_ASCII))
        out.write("6:pieces${piecesBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(piecesBytes)
        out.write("ee".toByteArray(StandardCharsets.US_ASCII))

        return out.toByteArray()
    }

    private fun createMultiFileTorrent(
        dirName: String = "MultiRelease",
        files: List<Pair<String, Long>> = listOf("file1.txt" to 100L, "sub/file2.bin" to 200L),
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
    // 1. TorrentFileValidator - Integer Overflow Attack Vectors & Bencode Fuzzing
    // =========================================================================

    @Test
    fun testValidator_IntegerOverflowStringLengthAttackVectors() {
        // String length > Int.MAX_VALUE (e.g. 2147483648, 4294967295, 9223372036854775807)
        val overflowLengths = listOf(
            "2147483648", // Int.MAX_VALUE + 1
            "2147483649",
            "4294967295", // UInt.MAX_VALUE
            "9223372036854775807", // Long.MAX_VALUE
            "99999999999999999999999999999999" // Way beyond Long
        )

        for (lenStr in overflowLengths) {
            val maliciousPayload = "d4:info${lenStr}:evil_contentee".toByteArray(StandardCharsets.US_ASCII)
            val result = TorrentFileValidator.validate(maliciousPayload)
            assertTrue("Payload with length $lenStr must be rejected as Invalid", result is TorrentValidationResult.Invalid)
            val invalid = result as TorrentValidationResult.Invalid
            assertEquals("Corrupted bencode", invalid.reason)
        }
    }

    @Test
    fun testValidator_IntMaxValueLengthWithShortBufferBoundsCheck() {
        // String length = Int.MAX_VALUE (2147483647). Must not cause OutOfMemoryError or buffer crash
        val payload = "d4:info2147483647:truncatedee".toByteArray(StandardCharsets.US_ASCII)
        val result = TorrentFileValidator.validate(payload)

        assertTrue("Must be Invalid without throwing OOM or array allocation error", result is TorrentValidationResult.Invalid)
        val invalid = result as TorrentValidationResult.Invalid
        assertEquals("Corrupted bencode", invalid.reason)
        assertTrue(invalid.detailedMessage.contains("exceeds buffer length") || invalid.detailedMessage.contains("corrupted"))
    }

    @Test
    fun testValidator_NegativeStringLengthsRejected() {
        val negativeLengths = listOf("-1", "-100", "-2147483648", "-9223372036854775808")

        for (lenStr in negativeLengths) {
            val payload = "d4:info${lenStr}:dataee".toByteArray(StandardCharsets.US_ASCII)
            val result = TorrentFileValidator.validate(payload)
            assertTrue("Negative length $lenStr must be rejected", result is TorrentValidationResult.Invalid)
        }
    }

    @Test
    fun testValidator_LeadingZerosInStringLengthsRejected() {
        // Bencode specification strictly forbids leading zeros in string lengths, e.g. "04:test"
        val payload = "d4:info04:testee".toByteArray(StandardCharsets.US_ASCII)
        val result = TorrentFileValidator.validate(payload)
        assertTrue("Leading zeros in string length must be rejected", result is TorrentValidationResult.Invalid)
        val invalid = result as TorrentValidationResult.Invalid
        assertTrue(invalid.detailedMessage.contains("Malformed string length") || invalid.detailedMessage.contains("corrupted"))
    }

    @Test
    fun testValidator_MalformedBencodeIntegers() {
        val badIntegers = listOf(
            "i-0e", // Negative zero is forbidden in bencode
            "i03e", // Leading zero is forbidden in bencode
            "i00e",
            "i-05e",
            "ie",   // Empty integer
            "i9223372036854775808e", // Beyond Long.MAX_VALUE
            "i-9223372036854775809e", // Below Long.MIN_VALUE
            "i999999999999999999999999999e"
        )

        for (badInt in badIntegers) {
            val payload = "d4:infod6:length${badInt}4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.US_ASCII)
            val result = TorrentFileValidator.validate(payload)
            assertTrue("Bad integer '$badInt' must be rejected", result is TorrentValidationResult.Invalid)
        }
    }

    @Test
    fun testValidator_LargeStringExceedingBufferBoundary() {
        val payload = "d4:info1000000:small_stringee".toByteArray(StandardCharsets.US_ASCII)
        val result = TorrentFileValidator.validate(payload)
        assertTrue(result is TorrentValidationResult.Invalid)
        val invalid = result as TorrentValidationResult.Invalid
        assertTrue(invalid.detailedMessage.contains("exceeds buffer length") || invalid.detailedMessage.contains("corrupted"))
    }

    @Test
    fun testValidator_UnterminatedBencodeElements() {
        val unterminatedCases = listOf(
            "d4:info10".toByteArray(StandardCharsets.US_ASCII), // Unterminated length
            "d4:infod6:lengthi100e".toByteArray(StandardCharsets.US_ASCII), // Unterminated dict
            "d4:infod5:filesl".toByteArray(StandardCharsets.US_ASCII), // Unterminated list
            "d4:infod6:lengthi100".toByteArray(StandardCharsets.US_ASCII) // Unterminated int
        )

        for (caseBytes in unterminatedCases) {
            val result = TorrentFileValidator.validate(caseBytes)
            assertTrue("Unterminated case must return Invalid", result is TorrentValidationResult.Invalid)
        }
    }

    // =========================================================================
    // 2. TorrentFileValidator - Multi-File Edge Cases & Refinements
    // =========================================================================

    @Test
    fun testValidator_MultiFileEmptyFilesListReturnsEmptyFilesListReason() {
        val emptyFilesPayload = "d4:infod4:name4:test12:piece lengthi16384e6:pieces20:123456789012345678905:filesleee".toByteArray(StandardCharsets.US_ASCII)
        val result = TorrentFileValidator.validate(emptyFilesPayload)

        assertTrue(result is TorrentValidationResult.Invalid)
        val invalid = result as TorrentValidationResult.Invalid
        assertEquals("Empty files list", invalid.reason)
        assertTrue(invalid.detailedMessage.contains("empty"))
    }

    @Test
    fun testValidator_MultiFileInvalidElementsInFilesList() {
        // files list contains an integer instead of dictionary
        val intInFiles = "d4:infod4:name4:test12:piece lengthi16384e6:pieces20:123456789012345678905:filesli100eeee".toByteArray(StandardCharsets.US_ASCII)
        val result = TorrentFileValidator.validate(intInFiles)

        assertTrue(result is TorrentValidationResult.Invalid)
        val invalid = result as TorrentValidationResult.Invalid
        assertEquals("Invalid file entry", invalid.reason)
    }

    @Test
    fun testValidator_MultiFileMissingOrNegativeLength() {
        // Missing length: file dict has only path: 1e (path list) + 1e (file dict) + 1e (files list) + 1e (info dict) + 1e (root dict) = 5 e's
        val missingLen = "d4:infod4:name4:test12:piece lengthi16384e6:pieces20:123456789012345678905:filesld4:pathl4:fileeeeee".toByteArray(StandardCharsets.US_ASCII)
        val res1 = TorrentFileValidator.validate(missingLen)
        assertTrue(res1 is TorrentValidationResult.Invalid)
        assertEquals("Invalid file length", (res1 as TorrentValidationResult.Invalid).reason)

        // Negative length: file dict has length -50 and path: 1e (path list) + 1e (file dict) + 1e (files list) + 1e (info dict) + 1e (root dict) = 5 e's
        val negativeLen = "d4:infod4:name4:test12:piece lengthi16384e6:pieces20:123456789012345678905:filesld6:lengthi-50e4:pathl4:fileeeeee".toByteArray(StandardCharsets.US_ASCII)
        val res2 = TorrentFileValidator.validate(negativeLen)
        assertTrue(res2 is TorrentValidationResult.Invalid)
        assertEquals("Invalid file length", (res2 as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidator_MultiFileMissingOrEmptyPath() {
        // Missing path: file dict has only length: i500e + 1e (file dict) + 1e (files list) + 1e (info dict) + 1e (root dict) = i500eeeee
        val missingPath = "d4:infod4:name4:test12:piece lengthi16384e6:pieces20:123456789012345678905:filesld6:lengthi500eeeee".toByteArray(StandardCharsets.US_ASCII)
        val res1 = TorrentFileValidator.validate(missingPath)
        assertTrue(res1 is TorrentValidationResult.Invalid)
        assertEquals("Invalid file path", (res1 as TorrentValidationResult.Invalid).reason)

        // Empty path list: file dict has length and pathl: 1e (path list) + 1e (file dict) + 1e (files list) + 1e (info dict) + 1e (root dict) = 5 e's
        val emptyPath = "d4:infod4:name4:test12:piece lengthi16384e6:pieces20:123456789012345678905:filesld6:lengthi500e4:pathleeeee".toByteArray(StandardCharsets.US_ASCII)
        val res2 = TorrentFileValidator.validate(emptyPath)
        assertTrue(res2 is TorrentValidationResult.Invalid)
        assertEquals("Invalid file path", (res2 as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidator_MissingBothLengthAndFilesSpecification() {
        val neither = "d4:infod4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.US_ASCII)
        val result = TorrentFileValidator.validate(neither)

        assertTrue(result is TorrentValidationResult.Invalid)
        val invalid = result as TorrentValidationResult.Invalid
        assertEquals("Missing length or files", invalid.reason)
    }

    @Test
    fun testValidator_MissingInfoDictionary() {
        val noInfo = "d8:announce26:http://tracker.example.come".toByteArray(StandardCharsets.US_ASCII)
        val result = TorrentFileValidator.validate(noInfo)

        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Missing info dictionary", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidator_NonDictInfoElement() {
        val nonDictInfo = "d4:infoi12345ee".toByteArray(StandardCharsets.US_ASCII)
        val result = TorrentFileValidator.validate(nonDictInfo)

        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Missing info dictionary", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidator_PieceLengthZeroOrNegative() {
        val zeroPiece = "d4:infod6:lengthi100e4:name4:test12:piece lengthi0e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.US_ASCII)
        val res1 = TorrentFileValidator.validate(zeroPiece)
        assertTrue(res1 is TorrentValidationResult.Invalid)
        assertEquals("Invalid piece length", (res1 as TorrentValidationResult.Invalid).reason)

        val negPiece = "d4:infod6:lengthi100e4:name4:test12:piece lengthi-16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.US_ASCII)
        val res2 = TorrentFileValidator.validate(negPiece)
        assertTrue(res2 is TorrentValidationResult.Invalid)
        assertEquals("Invalid piece length", (res2 as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testValidator_PiecesStringNotMultipleOf20() {
        // 21 bytes
        val badPieces21 = "d4:infod6:lengthi100e4:name4:test12:piece lengthi16384e6:pieces21:123456789012345678901ee".toByteArray(StandardCharsets.US_ASCII)
        val res1 = TorrentFileValidator.validate(badPieces21)
        assertTrue(res1 is TorrentValidationResult.Invalid)
        assertEquals("Invalid pieces", (res1 as TorrentValidationResult.Invalid).reason)

        // 19 bytes
        val badPieces19 = "d4:infod6:lengthi100e4:name4:test12:piece lengthi16384e6:pieces19:1234567890123456789ee".toByteArray(StandardCharsets.US_ASCII)
        val res2 = TorrentFileValidator.validate(badPieces19)
        assertTrue(res2 is TorrentValidationResult.Invalid)
        assertEquals("Invalid pieces", (res2 as TorrentValidationResult.Invalid).reason)
    }

    // =========================================================================
    // 3. TorrentFileValidator - Web / Error Payload Detection
    // =========================================================================

    @Test
    fun testValidator_CloudflareAndHttpErrorPayloads() {
        val htmlPage = "<!DOCTYPE html><html><head><title>Cloudflare DDoS</title></head><body>Ray ID: 948271 Just a moment...</body></html>".toByteArray(StandardCharsets.UTF_8)
        val r1 = TorrentFileValidator.validate(htmlPage)
        assertTrue(r1 is TorrentValidationResult.Invalid)
        assertTrue((r1 as TorrentValidationResult.Invalid).isHtmlPayload)

        val jsonError = "{\"error\": \"Invalid torrent hash\", \"code\": 404, \"status\": \"error\"}".toByteArray(StandardCharsets.UTF_8)
        val r2 = TorrentFileValidator.validate(jsonError)
        assertTrue(r2 is TorrentValidationResult.Invalid)
        assertTrue((r2 as TorrentValidationResult.Invalid).isHtmlPayload)

        val http502 = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/html\r\n\r\n<html>502</html>".toByteArray(StandardCharsets.UTF_8)
        val r3 = TorrentFileValidator.validate(http502)
        assertTrue(r3 is TorrentValidationResult.Invalid)
        assertTrue((r3 as TorrentValidationResult.Invalid).isHtmlPayload)
    }

    // =========================================================================
    // 4. MIME Contracts & SAF URI Stream Reading
    // =========================================================================

    @Test
    fun testFilePicker_SupportedMimeContractsMatching() {
        val validMimes = listOf(
            "application/x-bittorrent",
            "application/x-torrent",
            "application/octet-stream",
            "APPLICATION/X-BITTORRENT",
            "Application/X-Torrent",
            "APPLICATION/OCTET-STREAM"
        )

        for (mime in validMimes) {
            val parsed = TorrentIntentParser.parseData(
                action = Intent.ACTION_VIEW,
                dataUriString = "content://com.android.providers.downloads.documents/document/123",
                mimeType = mime,
                displayNameFallback = "download.torrent"
            )
            assertNotNull("MIME type $mime must be accepted by Intent parser", parsed)
            assertTrue(parsed is PendingTorrentIntent.TorrentFile)
        }
    }

    @Test
    fun testSAF_DisplayNameResolutionAndCleanFallback() {
        // Encoded URI name
        val name1 = TorrentIntentParser.resolveDisplayNameFromPath(
            "content://downloads/document/Arch%20Linux%202026.04.torrent",
            null
        )
        assertEquals("Arch Linux 2026.04.torrent", name1)

        // Path without .torrent extension appends .torrent
        val name2 = TorrentIntentParser.resolveDisplayNameFromPath(
            "content://downloads/document/42",
            null
        )
        assertEquals("42.torrent", name2)

        // Raw cursor display name takes precedence and is trimmed
        val name3 = TorrentIntentParser.resolveDisplayNameFromPath(
            "content://downloads/document/42",
            "   custom_debian_12.torrent   "
        )
        assertEquals("custom_debian_12.torrent", name3)
    }

    @Test
    fun testSAF_DirectStreamReadingViaIntentParser() {
        val validBytes = createSingleFileTorrent("fedora.iso", 2048L)
        val result = TorrentIntentParser.parseData(
            action = Intent.ACTION_VIEW,
            dataUriString = null,
            mimeType = "application/x-bittorrent",
            streamBytes = validBytes,
            displayNameFallback = "fedora.torrent"
        )

        assertNotNull(result)
        assertTrue(result is PendingTorrentIntent.TorrentFile)
        val torrentFile = result as PendingTorrentIntent.TorrentFile
        assertEquals("fedora.torrent", torrentFile.fileName)
        assertArrayEquals(validBytes, torrentFile.bytes)

        // Validate that parsed bytes are valid BitTorrent format
        val validation = TorrentFileValidator.validate(torrentFile.bytes)
        assertTrue(validation is TorrentValidationResult.Valid)
        assertEquals("fedora.iso", (validation as TorrentValidationResult.Valid).name)
    }

    @Test
    fun testValidator_InfoHashAccuracyMatchesSha1OfInfoDict() {
        val torrentBytes = createSingleFileTorrent("hash_test.iso", 4096L, 16384, 1)
        val result = TorrentFileValidator.validate(torrentBytes)

        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid

        // Extract info dict bytes manually and verify SHA-1
        val announcePrefix = "d8:announce36:https://tracker.example.com/announce4:info".toByteArray(StandardCharsets.UTF_8).size
        val infoBytes = torrentBytes.copyOfRange(announcePrefix, torrentBytes.size - 1)
        val expectedSha1 = MessageDigest.getInstance("SHA-1").digest(infoBytes).joinToString("") { "%02x".format(it) }

        assertEquals("infoHash must match exact SHA-1 digest of raw info dictionary bytes", expectedSha1, valid.infoHash)
    }
}
