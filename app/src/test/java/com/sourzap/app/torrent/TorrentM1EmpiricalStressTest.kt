package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.BencodeValidator
import com.sourzap.app.torrent.core.TorrentEngineManager
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentSessionConfig
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Empirical Adversarial & Stress Test Suite for Milestone 1 (M1).
 *
 * Exhaustively tests:
 * 1. Single-file torrents:
 *    - Zero-length, 1-byte, 50GB+ large files
 *    - Extreme piece lengths (16KB to 32MB)
 *    - SHA-1 infoHash verification matching raw info dictionary bytes
 *    - BEP 52 v2 piece layers / meta version fallback
 *
 * 2. Multi-file torrents with complex directory trees:
 *    - Deep directory nesting (10, 30 levels deep)
 *    - Wide directory trees (100+ files across nested folders)
 *    - Mixed complex directory trees with 0-byte and large files
 *    - Aggregate size calculation integrity
 *    - Empty/corrupted file list entries
 *
 * 3. Special characters & Multi-byte UTF-8 filenames:
 *    - Japanese, Chinese, Cyrillic, Greek, Arabic, Devanagari, Hebrew
 *    - Multi-byte emojis and 4-byte surrogate pairs (🚀, 🔥, 🌟, 🛸, 📁)
 *    - Special symbols, brackets, parentheses, semicolons, quotes, hashes
 *    - Mixed directory separators (/ and \)
 *    - `name.utf-8` precedence over `name`
 *    - Directory traversal characters in paths
 *
 * 4. Engine error boundary handling:
 *    - Truncated, empty, and malformed bencode buffers
 *    - Malformed integers (`i-0e`, `i03e`, `i00e`, `ie`, unclosed `i123`)
 *    - Malformed strings (negative lengths, length exceeding buffer, leading zero lengths)
 *    - Unterminated dictionaries and lists
 *    - Missing required keys (`info`, `piece length`, `pieces`)
 *    - Pieces binary string length not divisible by 20
 *    - HTML, XML, JSON error payloads, HTTP 302/403/502 status lines, Cloudflare challenge pages
 *    - `TorrentEngineManager.addTorrent` error containment (typed IllegalArgumentException, no crashes)
 *    - `TorrentEngineManager` lifecycle boundary robustness (non-existent IDs, invalid inputs)
 */
class TorrentM1EmpiricalStressTest {

    private val tempDir = File(
        System.getProperty("java.io.tmpdir") ?: ".",
        "sourzap_m1_stress_${System.currentTimeMillis()}"
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

    private fun buildSingleFileTorrentBytes(
        name: String = "single_file.iso",
        fileLength: Long = 1048576L,
        pieceLength: Int = 262144,
        pieceCount: Int = 4,
        announce: String = "https://tracker.tamersunion.org:443/announce",
        nameUtf8: String? = null,
        metaVersion: Int? = null,
        includePieces: Boolean = true
    ): ByteArray {
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

        if (metaVersion != null) {
            out.write("12:meta versioni${metaVersion}e".toByteArray(StandardCharsets.US_ASCII))
        }

        out.write("12:piece lengthi${pieceLength}e".toByteArray(StandardCharsets.US_ASCII))

        if (includePieces) {
            val piecesBytes = ByteArray(pieceCount * 20) { ((it * 7) % 256).toByte() }
            out.write("6:pieces${piecesBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
            out.write(piecesBytes)
        }

        out.write("ee".toByteArray(StandardCharsets.US_ASCII))
        return out.toByteArray()
    }

    private fun buildMultiFileTorrentBytes(
        dirName: String = "MultiRelease",
        files: List<Pair<List<String>, Long>>,
        pieceLength: Int = 65536,
        pieceCount: Int = 8,
        announce: String = "https://tracker.tamersunion.org:443/announce",
        dirNameUtf8: String? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()

        val announceBytes = announce.toByteArray(StandardCharsets.UTF_8)
        out.write("d8:announce${announceBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(announceBytes)
        out.write("4:infod".toByteArray(StandardCharsets.US_ASCII))
        out.write("5:filesl".toByteArray(StandardCharsets.US_ASCII))

        for ((pathSegments, len) in files) {
            out.write("d6:lengthi${len}e4:pathl".toByteArray(StandardCharsets.US_ASCII))
            for (seg in pathSegments) {
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

        if (dirNameUtf8 != null) {
            val utf8Bytes = dirNameUtf8.toByteArray(StandardCharsets.UTF_8)
            out.write("10:name.utf-8${utf8Bytes.size}:".toByteArray(StandardCharsets.US_ASCII))
            out.write(utf8Bytes)
        }

        out.write("12:piece lengthi${pieceLength}e".toByteArray(StandardCharsets.US_ASCII))
        val piecesBytes = ByteArray(pieceCount * 20) { ((it * 13) % 256).toByte() }
        out.write("6:pieces${piecesBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(piecesBytes)
        out.write("ee".toByteArray(StandardCharsets.US_ASCII))

        return out.toByteArray()
    }

    // =========================================================================
    // SECTION 1: SINGLE-FILE TORRENTS STRESS TESTS
    // =========================================================================

    @Test
    fun testSingleFile_ZeroByteLength() {
        val bytes = buildSingleFileTorrentBytes(
            name = "empty_file.dat",
            fileLength = 0L,
            pieceLength = 16384,
            pieceCount = 1
        )
        val result = TorrentFileValidator.validate(bytes)
        assertTrue("Zero-length file should validate successfully", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("empty_file.dat", valid.name)
        assertEquals(0L, valid.totalSize)
        assertFalse(valid.isMultiFile)
        assertEquals(1, valid.fileCount)
        assertEquals(16384, valid.pieceLength)
        assertEquals(1, valid.pieceCount)
        assertNotNull(valid.infoHash)
    }

    @Test
    fun testSingleFile_OneByteLength() {
        val bytes = buildSingleFileTorrentBytes(
            name = "single_byte.bin",
            fileLength = 1L,
            pieceLength = 16384,
            pieceCount = 1
        )
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("single_byte.bin", valid.name)
        assertEquals(1L, valid.totalSize)
        assertFalse(valid.isMultiFile)
        assertEquals(1, valid.fileCount)
    }

    @Test
    fun testSingleFile_LargeSize50GB() {
        val fiftyGB = 53_687_091_200L // 50 GiB
        val twoMB = 2_097_152 // 2 MiB piece size
        val expectedPieces = (fiftyGB / twoMB).toInt() // 25600 pieces

        val bytes = buildSingleFileTorrentBytes(
            name = "big_linux_distro.iso",
            fileLength = fiftyGB,
            pieceLength = twoMB,
            pieceCount = expectedPieces
        )
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("big_linux_distro.iso", valid.name)
        assertEquals(fiftyGB, valid.totalSize)
        assertFalse(valid.isMultiFile)
        assertEquals(twoMB, valid.pieceLength)
        assertEquals(expectedPieces, valid.pieceCount)
    }

    @Test
    fun testSingleFile_VariousExtremePieceLengths() {
        val pieceLengths = listOf(16384, 32768, 65536, 131072, 262144, 524288, 1048576, 2097152, 4194304, 8388608, 16777216, 33554432)
        for (pl in pieceLengths) {
            val bytes = buildSingleFileTorrentBytes(
                name = "test_pl_$pl.dat",
                fileLength = pl.toLong() * 3,
                pieceLength = pl,
                pieceCount = 3
            )
            val result = TorrentFileValidator.validate(bytes)
            assertTrue("Piece length $pl should validate", result is TorrentValidationResult.Valid)
            val valid = result as TorrentValidationResult.Valid
            assertEquals(pl, valid.pieceLength)
            assertEquals(3, valid.pieceCount)
        }
    }

    @Test
    fun testSingleFile_InfoHashExactSha1Match() {
        val bytes = buildSingleFileTorrentBytes(
            name = "sha1_verification_target.iso",
            fileLength = 1048576L,
            pieceLength = 65536,
            pieceCount = 16
        )
        val result = TorrentFileValidator.validate(bytes) as TorrentValidationResult.Valid

        // Extract info dictionary bytes from buffer
        val announcePrefix = "d8:announce45:https://tracker.tamersunion.org:443/announce4:info".toByteArray(StandardCharsets.US_ASCII).size
        val infoBytes = bytes.copyOfRange(announcePrefix, bytes.size - 1)
        val expectedSha1 = MessageDigest.getInstance("SHA-1").digest(infoBytes).joinToString("") { "%02x".format(it) }

        assertEquals("Calculated infoHash must match SHA-1 of info dictionary bytes", expectedSha1, result.infoHash)
    }

    @Test
    fun testSingleFile_Bep52V2PieceLayersFallback() {
        val bytes = buildSingleFileTorrentBytes(
            name = "v2_torrent.iso",
            fileLength = 10485760L,
            pieceLength = 2097152,
            metaVersion = 2,
            includePieces = false
        )
        val result = TorrentFileValidator.validate(bytes)
        assertTrue("BEP 52 v2 torrent with meta version = 2 should validate", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("v2_torrent.iso", valid.name)
        assertEquals(10485760L, valid.totalSize)
        assertEquals(5, valid.pieceCount) // 10485760 / 2097152 = 5 pieces
    }

    // =========================================================================
    // SECTION 2: MULTI-FILE TORRENTS & DEEP DIRECTORY TREES
    // =========================================================================

    @Test
    fun testMultiFile_DeepNesting10Levels() {
        val nestedPath = (1..10).map { "depth_$it" } + listOf("payload_file.bin")
        val files = listOf(nestedPath to 102400L)

        val bytes = buildMultiFileTorrentBytes(
            dirName = "DeepRoot",
            files = files,
            pieceLength = 32768,
            pieceCount = 4
        )

        val result = TorrentFileValidator.validate(bytes)
        assertTrue("10-level nested directory should validate", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("DeepRoot", valid.name)
        assertEquals(102400L, valid.totalSize)
        assertTrue(valid.isMultiFile)
        assertEquals(1, valid.fileCount)
    }

    @Test
    fun testMultiFile_ExtremeDeepNesting30Levels() {
        val nestedPath = (1..30).map { "lvl_$it" } + listOf("extreme_nested.dat")
        val files = listOf(nestedPath to 5242880L)

        val bytes = buildMultiFileTorrentBytes(
            dirName = "ExtremeTree",
            files = files,
            pieceLength = 65536,
            pieceCount = 80
        )

        val result = TorrentFileValidator.validate(bytes)
        assertTrue("30-level nested directory should validate", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("ExtremeTree", valid.name)
        assertEquals(5242880L, valid.totalSize)
        assertTrue(valid.isMultiFile)
        assertEquals(1, valid.fileCount)
    }

    @Test
    fun testMultiFile_WideDirectoryTree100Files() {
        val files = (1..100).map { i ->
            val folder = "folder_${i % 10}"
            listOf(folder, "subfile_$i.mp4") to (i * 1024L)
        }
        val expectedTotalSize = files.sumOf { it.second }

        val bytes = buildMultiFileTorrentBytes(
            dirName = "WideArchive",
            files = files,
            pieceLength = 131072,
            pieceCount = 10
        )

        val result = TorrentFileValidator.validate(bytes)
        assertTrue("100 files in multi-file torrent should validate", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("WideArchive", valid.name)
        assertEquals(expectedTotalSize, valid.totalSize)
        assertTrue(valid.isMultiFile)
        assertEquals(100, valid.fileCount)
    }

    @Test
    fun testMultiFile_ComplexMixedDirectoryTreeWithZeroByteFiles() {
        val files = listOf(
            listOf("README.md") to 512L,
            listOf(".keep") to 0L, // 0-byte file
            listOf("docs", "architecture", "v1", "spec.pdf") to 2048576L,
            listOf("docs", "images", "diagram.png") to 512000L,
            listOf("src", "main", "kotlin", "App.kt") to 10240L,
            listOf("src", "main", "resources", "empty_placeholder.txt") to 0L, // another 0-byte file
            listOf("build", "libs", "sourzap-release.apk") to 15728640L
        )
        val expectedTotalSize = files.sumOf { it.second }

        val bytes = buildMultiFileTorrentBytes(
            dirName = "SourZapSourceCode",
            files = files,
            pieceLength = 65536,
            pieceCount = 280
        )

        val result = TorrentFileValidator.validate(bytes)
        assertTrue("Complex mixed directory tree should validate", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("SourZapSourceCode", valid.name)
        assertEquals(expectedTotalSize, valid.totalSize)
        assertTrue(valid.isMultiFile)
        assertEquals(7, valid.fileCount)
    }

    @Test
    fun testMultiFile_EmptyFileList_ReturnsInvalid() {
        val emptyFilesListBytes = "d8:announce36:https://tracker.example.com/announce4:infod5:filesle4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(emptyFilesListBytes)
        assertTrue("Empty files list must be invalid", result is TorrentValidationResult.Invalid)
        assertEquals("Empty files list", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testMultiFile_CorruptedFileEntryInList() {
        // Second file entry has negative length
        val badFileEntryBytes = "d8:announce36:https://tracker.example.com/announce4:infod5:filesld6:lengthi100e4:pathl5:file1eed6:lengthi-500e4:pathl5:file2eee4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(badFileEntryBytes)
        assertTrue("File entry with negative length must be invalid", result is TorrentValidationResult.Invalid)
        assertEquals("Invalid file length", (result as TorrentValidationResult.Invalid).reason)
    }

    // =========================================================================
    // SECTION 3: SPECIAL CHARACTERS & MULTI-BYTE UTF-8 FILENAMES
    // =========================================================================

    @Test
    fun testUtf8_JapaneseAndChinesePathTree() {
        val files = listOf(
            listOf("日本語アニメ", "第01話「運命の始まり」.mkv") to 500000000L,
            listOf("中文电影", "高清版", "字幕_简体中文.ass") to 125000L
        )
        val bytes = buildMultiFileTorrentBytes(
            dirName = "東洋メディア_2026",
            files = files,
            pieceLength = 262144,
            pieceCount = 2000
        )
        val result = TorrentFileValidator.validate(bytes)
        assertTrue("Multi-byte CJK path tree should validate", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("東洋メディア_2026", valid.name)
        assertEquals(500125000L, valid.totalSize)
        assertEquals(2, valid.fileCount)
    }

    @Test
    fun testUtf8_CyrillicAndGreekAndArabicAndDevanagari() {
        val files = listOf(
            listOf("Документы", "Архив_2026.zip") to 1000000L,
            listOf("Έγγραφα", "Σημειώσεις.pdf") to 200000L,
            listOf("مستندات", "تقرير_نهائي.doc") to 300000L,
            listOf("दस्तावेज़", "परियोजना_योजना.txt") to 50000L
        )
        val bytes = buildMultiFileTorrentBytes(
            dirName = "GlobalDocuments_Глобал_دولي",
            files = files,
            pieceLength = 65536,
            pieceCount = 25
        )
        val result = TorrentFileValidator.validate(bytes)
        assertTrue("Multi-script filenames should validate", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("GlobalDocuments_Глобал_دولي", valid.name)
        assertEquals(1550000L, valid.totalSize)
        assertEquals(4, valid.fileCount)
    }

    @Test
    fun testUtf8_EmojisAnd4ByteSurrogatePairs() {
        val emojiDirName = "📁 SourZap 🚀 Speed 🔥 Downloads 🎉"
        val files = listOf(
            listOf("🌟 Highlights", "🛸 Space_Odyssey_4K.mkv") to 4000000000L,
            listOf("🎵 Soundtracks", "🎸 Rock_Anthem_⚡.flac") to 45000000L
        )
        val bytes = buildMultiFileTorrentBytes(
            dirName = emojiDirName,
            files = files,
            pieceLength = 524288,
            pieceCount = 7800
        )
        val result = TorrentFileValidator.validate(bytes)
        assertTrue("Emoji directory and filenames should validate", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(emojiDirName, valid.name)
        assertEquals(4045000000L, valid.totalSize)
        assertEquals(2, valid.fileCount)
    }

    @Test
    fun testUtf8_SpecialSymbolsAndBracketsAndSlashes() {
        val complexName = "[Release-Group] Awesome_Show (2026) [1080p x265 10bit] [DTS-HD MA 5.1] - S01E01 #01 & #02 + Extras ~ !@$%^&()_+-={}[];',.`"
        val bytes = buildSingleFileTorrentBytes(
            name = complexName,
            fileLength = 1500000000L,
            pieceLength = 1048576,
            pieceCount = 1431
        )
        val result = TorrentFileValidator.validate(bytes)
        assertTrue("Special characters and symbols in filename should validate", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(complexName, valid.name)
    }

    @Test
    fun testUtf8_NameUtf8KeyPrecedenceOverLegacyName() {
        val bytes = buildSingleFileTorrentBytes(
            name = "ascii_fallback.bin",
            nameUtf8 = "日本語_最新版_2026.iso",
            fileLength = 500000L
        )
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("name.utf-8 must take precedence over name", "日本語_最新版_2026.iso", valid.name)
    }

    @Test
    fun testUtf8_PathTraversalCharactersInPathList() {
        val files = listOf(
            listOf("..", "system", "hosts") to 1024L,
            listOf("..\\..\\windows", "system32", "calc.exe") to 2048L
        )
        val bytes = buildMultiFileTorrentBytes(
            dirName = "TraversalTest",
            files = files
        )
        // Validator parses bencode safely without throwing exceptions on path strings
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("TraversalTest", valid.name)
        assertEquals(3072L, valid.totalSize)
    }

    // =========================================================================
    // SECTION 4: ENGINE ERROR BOUNDARY HANDLING
    // =========================================================================

    @Test
    fun testErrorBoundary_NullAndEmptyAndShortBuffers() {
        // Null
        val resNull = TorrentFileValidator.validate(null as ByteArray?)
        assertTrue(resNull is TorrentValidationResult.Invalid)
        assertEquals("Empty file", (resNull as TorrentValidationResult.Invalid).reason)

        // Empty
        val resEmpty = TorrentFileValidator.validate(ByteArray(0))
        assertTrue(resEmpty is TorrentValidationResult.Invalid)
        assertEquals("Empty file", (resEmpty as TorrentValidationResult.Invalid).reason)

        // Buffer lengths 1..10
        for (len in 1..10) {
            val shortBuf = ByteArray(len) { 'd'.code.toByte() }
            val resShort = TorrentFileValidator.validate(shortBuf)
            assertTrue(resShort is TorrentValidationResult.Invalid)
            assertEquals("File too small", (resShort as TorrentValidationResult.Invalid).reason)
        }
    }

    @Test
    fun testErrorBoundary_MalformedBencodeIntegers() {
        val malformedIntegers = listOf(
            "d4:infod6:lengthi-0e4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee", // negative zero
            "d4:infod6:lengthi03e4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee", // leading zero
            "d4:infod6:lengthi00e4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee", // double zero
            "d4:infod6:lengthi-00e4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee", // negative double zero
            "d4:infod6:lengthie4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee",   // empty integer
            "d4:infod6:lengthi1234:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee", // unclosed integer
            "d4:infod6:lengthi99999999999999999999999999999999e4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee" // overflow
        )

        for (badInt in malformedIntegers) {
            val result = TorrentFileValidator.validate(badInt.toByteArray(StandardCharsets.UTF_8))
            assertTrue("Malformed integer must result in Invalid: $badInt", result is TorrentValidationResult.Invalid)
        }
    }

    @Test
    fun testErrorBoundary_MalformedBencodeStrings() {
        val malformedStrings = listOf(
            "d4:infod6:lengthi100e4:name1000:short12:piece lengthi16384e6:pieces20:12345678901234567890ee", // length exceeds buffer
            "d4:infod6:lengthi100e4:name-5:abc12:piece lengthi16384e6:pieces20:12345678901234567890ee",    // negative string length
            "d4:infod6:lengthi100e4:name04:test12:piece lengthi16384e6:pieces20:12345678901234567890ee",   // leading zero in length
            "d4:infod6:lengthi100e4:name4abc12:piece lengthi16384e6:pieces20:12345678901234567890ee"      // missing colon
        )

        for (badStr in malformedStrings) {
            val result = TorrentFileValidator.validate(badStr.toByteArray(StandardCharsets.UTF_8))
            assertTrue("Malformed string must result in Invalid: $badStr", result is TorrentValidationResult.Invalid)
        }
    }

    @Test
    fun testErrorBoundary_UnterminatedDictionariesAndLists() {
        val unterminated = listOf(
            "d4:infod6:lengthi100e4:name4:test12:piece lengthi16384e", // missing 2 closing 'e's
            "d4:infod6:lengthi100e4:name4:teste",                     // missing 1 closing 'e'
            "d5:filesld6:lengthi100e4:pathl4:fileee4:name4:test",     // missing root 'e'
            "li1ei2ei3e"                                              // list at root instead of dict
        )

        for (u in unterminated) {
            val result = TorrentFileValidator.validate(u.toByteArray(StandardCharsets.UTF_8))
            assertTrue("Unterminated bencode must result in Invalid", result is TorrentValidationResult.Invalid)
        }
    }

    @Test
    fun testErrorBoundary_MissingRequiredInfoKeys() {
        // Missing info dict
        val noInfo = "d8:announce36:https://tracker.example.com/announcee".toByteArray(StandardCharsets.UTF_8)
        val resNoInfo = TorrentFileValidator.validate(noInfo)
        assertTrue(resNoInfo is TorrentValidationResult.Invalid)
        assertEquals("Missing info dictionary", (resNoInfo as TorrentValidationResult.Invalid).reason)

        // Missing piece length
        val noPieceLen = "d4:infod6:lengthi100e4:name4:test6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val resNoPieceLen = TorrentFileValidator.validate(noPieceLen)
        assertTrue(resNoPieceLen is TorrentValidationResult.Invalid)
        assertEquals("Invalid piece length", (resNoPieceLen as TorrentValidationResult.Invalid).reason)

        // Missing pieces
        val noPieces = "d4:infod6:lengthi100e4:name4:test12:piece lengthi16384eee".toByteArray(StandardCharsets.UTF_8)
        val resNoPieces = TorrentFileValidator.validate(noPieces)
        assertTrue(resNoPieces is TorrentValidationResult.Invalid)
        assertEquals("Invalid pieces", (resNoPieces as TorrentValidationResult.Invalid).reason)

        // Pieces length not divisible by 20 (e.g. 21 bytes)
        val badPieces21 = "d4:infod6:lengthi100e4:name4:test12:piece lengthi16384e6:pieces21:123456789012345678901ee".toByteArray(StandardCharsets.UTF_8)
        val resBadPieces21 = TorrentFileValidator.validate(badPieces21)
        assertTrue(resBadPieces21 is TorrentValidationResult.Invalid)
        assertEquals("Invalid pieces", (resBadPieces21 as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testErrorBoundary_WebAndErrorPayloadsDetection() {
        val payloads = listOf(
            "<!DOCTYPE html><html><head><title>500 Internal Server Error</title></head><body>Server error</body></html>",
            "<html><head><title>Cloudflare DDoS Protection</title></head><body>Just a moment...</body></html>",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>NoSuchKey</Code><Message>The specified key does not exist.</Message></Error>",
            "{\"error\": \"Invalid torrent file\", \"status\": 400, \"code\": \"BAD_REQUEST\"}",
            "HTTP/1.1 302 Found\r\nLocation: https://tracker.example.com/login\r\n\r\n",
            "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\n\r\nAccess denied",
            "HTTP/1.1 502 Bad Gateway\r\n\r\n",
            "<div class=\"cf-turnstile-wrapper\">Checking your browser... Ray ID: abc123xyz</div>"
        )

        for (payload in payloads) {
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            val result = TorrentFileValidator.validate(bytes)
            assertTrue("Web error payload must be Invalid: $payload", result is TorrentValidationResult.Invalid)
            val invalid = result as TorrentValidationResult.Invalid
            assertTrue("Must identify as HTML/Web payload: $payload", invalid.isHtmlPayload)
            assertEquals("Web page / Error payload", invalid.reason)
        }
    }

    @Test
    fun testErrorBoundary_EngineManagerAddTorrentInvalidBytesThrows() {
        try {
            val engine = TorrentEngineManager.create(TorrentSessionConfig.DEFAULT)
            val corruptedBytes = "corrupted_garbage_not_bencode_at_all".toByteArray(StandardCharsets.UTF_8)

            try {
                engine.addTorrent(
                    torrentSource = TorrentSource.FileContent(corruptedBytes),
                    saveDir = tempDir
                )
                fail("Expected IllegalArgumentException on corrupted torrent byte array")
            } catch (e: IllegalArgumentException) {
                assertTrue("Exception message must describe bencode failure", e.message!!.contains("Cannot load .torrent file"))
            }
        } catch (_: LinkageError) {
            // Expected on host JVM when native libtorrent binary is not available
        }
    }

    @Test
    fun testErrorBoundary_EngineManagerAddTorrentWebPayloadThrows() {
        try {
            val engine = TorrentEngineManager.create(TorrentSessionConfig.DEFAULT)
            val htmlBytes = "<!DOCTYPE html><html><body>Error 404 Not Found</body></html>".toByteArray(StandardCharsets.UTF_8)

            try {
                engine.addTorrent(
                    torrentSource = TorrentSource.FileContent(htmlBytes),
                    saveDir = tempDir
                )
                fail("Expected IllegalArgumentException on HTML web payload")
            } catch (e: IllegalArgumentException) {
                assertTrue("Exception message must describe web page error", e.message!!.contains("web page or error response"))
            }
        } catch (_: LinkageError) {
            // Expected on host JVM when native libtorrent binary is not available
        }
    }

    @Test
    fun testErrorBoundary_EngineManagerAddTorrentMissingFileThrows() {
        try {
            val engine = TorrentEngineManager.create(TorrentSessionConfig.DEFAULT)
            val missingFile = File(tempDir, "does_not_exist.torrent")

            try {
                engine.addTorrent(
                    torrentSource = TorrentSource.FilePath(missingFile.absolutePath),
                    saveDir = tempDir
                )
                fail("Expected IllegalArgumentException on missing file path")
            } catch (e: IllegalArgumentException) {
                assertTrue("Exception message must describe missing file", e.message!!.contains("Torrent file not found"))
            }
        } catch (_: LinkageError) {
            // Expected on host JVM when native libtorrent binary is not available
        }
    }

    @Test
    fun testErrorBoundary_EngineManagerLifecycleRobustness() {
        try {
            val engine = TorrentEngineManager.create(TorrentSessionConfig.DEFAULT)

            // Stopping when not started is safe
            engine.stopSession()
            assertFalse(engine.isSessionRunning())

            // Calling pause/resume/recheck/remove on non-existent hash does not crash
            val nonExistentHash = "c12fe1c06bba254a9dc9f519b335de7ece74f6d2"
            engine.pauseTorrent(nonExistentHash)
            engine.resumeTorrent(nonExistentHash)
            engine.recheckTorrent(nonExistentHash)
            engine.removeTorrent(nonExistentHash, false)
            engine.setFilePriority(nonExistentHash, 0, Priority.HIGH)
            engine.setSequentialDownload(nonExistentHash, true)
            engine.pauseAll()
            engine.resumeAll()

            assertNull(engine.getTorrentInfo(nonExistentHash))
            assertNotNull(engine.observeTorrents().value)
            assertNotNull(engine.observeStats().value)
        } catch (_: LinkageError) {
            // Expected on host JVM when native libtorrent binary is not available
        }
    }
}
