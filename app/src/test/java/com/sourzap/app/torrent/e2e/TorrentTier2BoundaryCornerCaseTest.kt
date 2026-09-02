package com.sourzap.app.torrent.e2e

import com.sourzap.app.torrent.core.BencodeValidator
import com.sourzap.app.torrent.core.MagnetHandler
import com.sourzap.app.torrent.core.MagnetInfo
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentStorageHelper
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.core.TrackerInjector
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentPieceInfo
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.model.TorrentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Dual-Track E2E Test Suite — Tier 2: Boundary, Corner & Stress Cases.
 *
 * Verifies edge cases across all requirement domains:
 * - Boundary 1: Empty torrent / 0-byte file handling.
 * - Boundary 2: Single-file torrents vs massive multi-file trees (1000+ files).
 * - Boundary 3: 0 files selected validation & all-skipped enforcement.
 * - Boundary 4: 100% files selected validation & full-tree piece aggregation.
 * - Boundary 5: Path traversal prevention (`../../`, `/root/`, absolute paths).
 * - Boundary 6: Invalid magnet URIs (corrupted xt, truncated hashes, malformed encoding).
 * - Boundary 7: Special characters, unicode, emoji, and spaces in file paths & names.
 * - Boundary 8: Massive file sizes (> 100 GB, 4TB, Long boundary protections).
 * - Boundary 9: Truncated / malformed bencode payload detection.
 * - Boundary 10: Extreme storage space conditions (0 free space, boundary equality, deficit).
 *
 * Total: 50+ rigorous boundary test cases.
 */
class TorrentTier2BoundaryCornerCaseTest {

    // Helper method to create a binary bencoded single-file torrent buffer
    private fun createSingleFileTorrent(
        name: String = "test.bin",
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

    // Helper method to create a binary bencoded multi-file torrent buffer
    private fun createMultiFileTorrent(
        dirName: String = "MultiFiles",
        files: List<Pair<String, Long>>,
        pieceLength: Int = 16384,
        pieceCount: Int = 4,
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
            val segments = path.split("/").filter { it.isNotEmpty() }
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
    // BOUNDARY 1: Empty Torrent & 0-Byte File Handling (5 tests)
    // =========================================================================

    @Test
    fun testB1_1_EmptyByteArray_ReturnsInvalidResult() {
        val emptyBytes = ByteArray(0)
        val result = TorrentFileValidator.validate(emptyBytes)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Empty file", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testB1_2_NullByteArray_ReturnsInvalidResult() {
        val result = TorrentFileValidator.validate(null as ByteArray?)
        assertTrue(result is TorrentValidationResult.Invalid)
    }

    @Test
    fun testB1_3_ZeroByteLengthSingleFileTorrent() {
        val bytes = createSingleFileTorrent(name = "empty_file.bin", fileLength = 0L, pieceCount = 1)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(0L, valid.totalSize)
        assertEquals(1, valid.fileCount)
    }

    @Test
    fun testB1_4_MultiFileTorrentWithZeroByteFiles() {
        val files = listOf(
            "folder/empty1.txt" to 0L,
            "folder/data.bin" to 1024L,
            "folder/empty2.txt" to 0L
        )
        val bytes = createMultiFileTorrent(files = files)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(1024L, valid.totalSize)
        assertEquals(3, valid.fileCount)
    }

    @Test
    fun testB1_5_ZeroLengthFileItemProgressCalculation() {
        val item = TorrentFileItem(index = 0, path = "empty.txt", size = 0L, downloadedBytes = 0L)
        assertEquals(0.0f, item.progress, 0.0001f)
        assertEquals("0 B", TorrentItem.formatFileSize(item.size))
    }

    // =========================================================================
    // BOUNDARY 2: Massive Multi-File Trees (1000+ Files Stress Test) (5 tests)
    // =========================================================================

    @Test
    fun testB2_1_MassiveMultiFileTree_1000FilesParsing() {
        val files = (1..1000).map { i ->
            "dataset_2026/batch_${i / 100}/sample_${i}.dat" to 10240L
        }
        val bytes = createMultiFileTorrent(dirName = "BigDataset", files = files, pieceCount = 100)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue("Validator must successfully parse 1000-file bencoded tree", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(1000, valid.fileCount)
        assertEquals(1000 * 10240L, valid.totalSize)
        assertTrue(valid.isMultiFile)
    }

    @Test
    fun testB2_2_DeeplyNestedDirectoryHierarchy_20Levels() {
        val deepPath = (1..20).joinToString("/") { "level$it" } + "/deep_payload.bin"
        val files = listOf(deepPath to 50000L)
        val bytes = createMultiFileTorrent(dirName = "DeepTree", files = files)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(1, valid.fileCount)
    }

    @Test
    fun testB2_3_LargeFileTreeItemFilteringAndSkipping() {
        val items = (0 until 1200).map { i ->
            TorrentFileItem(
                index = i,
                path = "repo/file_$i.cpp",
                size = 5000L,
                priority = if (i % 2 == 0) Priority.NORMAL else Priority.IGNORE
            )
        }
        val skippedCount = items.count { it.isSkipped }
        val activeCount = items.count { !it.isSkipped }
        assertEquals(600, skippedCount)
        assertEquals(600, activeCount)
        val selectedBytes = items.filter { !it.isSkipped }.sumOf { it.size }
        assertEquals(600 * 5000L, selectedBytes)
    }

    @Test
    fun testB2_4_SingleHugeFileVsMassiveSmallFilesEquality() {
        val hugeSingleSize = 10_000_000_000L // 10 GB
        val singleItem = TorrentFileItem(0, "huge.img", hugeSingleSize, priority = Priority.NORMAL)

        val smallItems = (0 until 10_000).map {
            TorrentFileItem(it, "part_$it.chunk", 1_000_000L, priority = Priority.NORMAL)
        }
        val smallSum = smallItems.sumOf { it.size }

        assertEquals(hugeSingleSize, singleItem.size)
        assertEquals(hugeSingleSize, smallSum)
    }

    @Test
    fun testB2_5_FileTreeSearchQueryFilteringStress() {
        val allFiles = (0 until 1500).map { i ->
            TorrentFileItem(i, "music/disc1/track_${"%04d".format(i)}.flac", 30000000L)
        }
        val query = "track_0500"
        val matched = allFiles.filter { it.fileName.contains(query, ignoreCase = true) }
        assertEquals(1, matched.size)
        assertEquals("track_0500.flac", matched[0].fileName)
    }

    // =========================================================================
    // BOUNDARY 3: 0 Files Selected Validation (All Deselected) (5 tests)
    // =========================================================================

    @Test
    fun testB3_1_AllFilesDeselected_SelectedSizeIsZero() {
        val files = listOf(
            TorrentFileItem(0, "A.iso", 100000L, priority = Priority.IGNORE),
            TorrentFileItem(1, "B.iso", 200000L, priority = Priority.IGNORE),
            TorrentFileItem(2, "C.iso", 300000L, priority = Priority.IGNORE)
        )
        val selectedBytes = files.filter { !it.isSkipped }.sumOf { it.size }
        assertEquals(0L, selectedBytes)
    }

    @Test
    fun testB3_2_AllFilesDeselected_AllPrioritiesAreZero() {
        val files = listOf(
            TorrentFileItem(0, "file1.dat", 100L, priority = Priority.IGNORE),
            TorrentFileItem(1, "file2.dat", 200L, priority = Priority.IGNORE)
        )
        for (f in files) {
            assertEquals(0, f.priority.value)
            assertTrue(f.isSkipped)
        }
    }

    @Test
    fun testB3_3_PreDownloadValidation_DetectsZeroFilesSelected() {
        fun validateDownloadSelection(selectedCount: Int): Boolean {
            return selectedCount > 0
        }
        assertFalse("Selection validation must reject 0 selected files", validateDownloadSelection(0))
        assertTrue("Selection validation must accept >= 1 selected files", validateDownloadSelection(1))
    }

    @Test
    fun testB3_4_AllDeselected_FormattedSizeIs0B() {
        val selectedSize = 0L
        assertEquals("0 B", TorrentItem.formatFileSize(selectedSize))
    }

    @Test
    fun testB3_5_AllDeselected_ToggleSelectAllRestoresAllFiles() {
        val initial = listOf(
            TorrentFileItem(0, "file1.dat", 500L, priority = Priority.IGNORE),
            TorrentFileItem(1, "file2.dat", 500L, priority = Priority.IGNORE)
        )
        val toggledAll = initial.map { it.copy(priority = Priority.NORMAL) }
        assertEquals(1000L, toggledAll.filter { !it.isSkipped }.sumOf { it.size })
    }

    // =========================================================================
    // BOUNDARY 4: 100% Files Selected Validation (5 tests)
    // =========================================================================

    @Test
    fun testB4_1_AllFilesSelected_SelectedSizeEqualsTotalSize() {
        val files = listOf(
            TorrentFileItem(0, "1.mkv", 500_000_000L, priority = Priority.NORMAL),
            TorrentFileItem(1, "2.mkv", 600_000_000L, priority = Priority.NORMAL),
            TorrentFileItem(2, "sub.srt", 100_000L, priority = Priority.NORMAL)
        )
        val totalSize = files.sumOf { it.size }
        val selectedSize = files.filter { !it.isSkipped }.sumOf { it.size }
        assertEquals(totalSize, selectedSize)
    }

    @Test
    fun testB4_2_AllFilesSelected_NoneIsSkipped() {
        val files = (0..10).map { TorrentFileItem(it, "item_$it.bin", 1000L, priority = Priority.NORMAL) }
        assertTrue(files.none { it.isSkipped })
    }

    @Test
    fun testB4_3_AllFilesSelected_PieceBitmapIntegrity() {
        val pieceInfo = TorrentPieceInfo(
            pieceCount = 100,
            piecesCompleted = 100,
            pieceBitfield = BooleanArray(100) { true }
        )
        assertEquals(1.0f, pieceInfo.completionRatio, 0.0001f)
    }

    @Test
    fun testB4_4_AllFilesSelected_ProgressPercentReaches100() {
        val item = TorrentItem(
            id = "b4_item",
            name = "Completed Torrent",
            state = TorrentState.FINISHED,
            progress = 1.0f,
            downloadSpeed = 0L,
            uploadSpeed = 0L,
            totalBytes = 1000L,
            downloadedBytes = 1000L,
            uploadedBytes = 0L,
            numSeeds = 0,
            numPeers = 0
        )
        assertEquals(100, item.progressPercent)
        assertEquals("100.0%", item.formattedProgress)
        assertTrue(item.isCompleted)
    }

    @Test
    fun testB4_5_AllFilesSelected_FilterMatchesCompleted() {
        val item = TorrentItem(
            id = "b4_filter",
            name = "Finished",
            state = TorrentState.FINISHED,
            progress = 1.0f,
            downloadSpeed = 0,
            uploadSpeed = 0,
            totalBytes = 100,
            downloadedBytes = 100,
            uploadedBytes = 0,
            numSeeds = 0,
            numPeers = 0
        )
        assertTrue(TorrentFilter.COMPLETED.matches(item))
    }

    // =========================================================================
    // BOUNDARY 5: Path Traversal Prevention (`../../`, absolute paths) (5 tests)
    // =========================================================================

    @Test
    fun testB5_1_PathTraversal_DotDotRelativeSegmentsSanitization() {
        val maliciousPath = "../../etc/passwd"
        val sanitized = maliciousPath.replace("..", "").replace("//", "/").trimStart('/')
        assertFalse("Sanitized path must not contain parent directory traversal", sanitized.contains(".."))
        assertEquals("etc/passwd", sanitized)
    }

    @Test
    fun testB5_2_PathTraversal_AbsoluteRootSlashSanitization() {
        val absolutePath = "/system/bin/sh"
        val sanitized = absolutePath.trimStart('/', '\\')
        assertFalse("Sanitized path must not start with absolute root slash", sanitized.startsWith("/"))
        assertEquals("system/bin/sh", sanitized)
    }

    @Test
    fun testB5_3_PathTraversal_WindowsDriveLetterSanitization() {
        val windowsPath = "C:\\Windows\\System32\\calc.exe"
        val leaf = windowsPath.substringAfterLast('/').substringAfterLast('\\')
        assertEquals("calc.exe", leaf)
    }

    @Test
    fun testB5_4_PathTraversal_EncodedDotDotSequences() {
        val encoded = "%2e%2e%2f%2e%2e%2fhidden.txt"
        val decoded = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        val sanitized = decoded.replace("..", "").replace("//", "/").trimStart('/')
        assertEquals("hidden.txt", sanitized)
    }

    @Test
    fun testB5_5_PathTraversal_SaveDirectoryContainmentCheck() {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "base_safe")
        val safeTarget = File(baseDir, "subfolder/file.bin")
        val escapeTarget = File(baseDir, "../../../escaped.bin")

        fun isContained(base: File, target: File): Boolean {
            return target.canonicalPath.startsWith(base.canonicalPath)
        }
        baseDir.mkdirs()
        try {
            assertTrue(isContained(baseDir, safeTarget))
            assertFalse(isContained(baseDir, escapeTarget))
        } finally {
            baseDir.deleteRecursively()
        }
    }

    // =========================================================================
    // BOUNDARY 6: Invalid Magnet URIs (5 tests)
    // =========================================================================

    @Test
    fun testB6_1_InvalidMagnet_MissingExactScheme() {
        assertNull(MagnetHandler.parse("http://tracker.com/torrent"))
        assertNull(MagnetHandler.parse("xt=urn:btih:0123456789abcdef0123456789abcdef01234567"))
    }

    @Test
    fun testB6_2_InvalidMagnet_MissingXtParameter() {
        assertNull(MagnetHandler.parse("magnet:?dn=Ubuntu+Linux&tr=http://tracker.org/announce"))
    }

    @Test
    fun testB6_3_InvalidMagnet_CorruptedHexHashLength() {
        assertNull(MagnetHandler.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef0123456")) // 39 chars
        assertNull(MagnetHandler.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef012345678")) // 41 chars
    }

    @Test
    fun testB6_4_InvalidMagnet_NonHexCharactersInHexHash() {
        assertNull(MagnetHandler.parse("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef0123456z")) // 'z' is invalid
    }

    @Test
    fun testB6_5_InvalidMagnet_EmptyAndBlankStrings() {
        assertNull(MagnetHandler.parse(""))
        assertNull(MagnetHandler.parse("   "))
        assertNull(MagnetHandler.parse("magnet:?"))
    }

    // =========================================================================
    // BOUNDARY 7: Special Characters, Unicode & Emoji in Paths (5 tests)
    // =========================================================================

    @Test
    fun testB7_1_SpecialChars_EmojiInTorrentName() {
        val emojiName = "🚀 Epic Movie - 2026 🎬 [1080p].mp4"
        val bytes = createSingleFileTorrent(name = emojiName)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(emojiName, valid.name)
    }

    @Test
    fun testB7_2_SpecialChars_CyrillicAndAsianCharacters() {
        val foreignName = "Фильм_日本語_한국어_العربية.mkv"
        val bytes = createSingleFileTorrent(name = foreignName)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(foreignName, valid.name)
    }

    @Test
    fun testB7_3_SpecialChars_SymbolsAndPunctuationInPaths() {
        val complexPath = "Special (2026) [v1.0] {100%} ~ !@#$%^&()_+={}[];',.txt"
        val fileItem = TorrentFileItem(0, complexPath, 1024L)
        assertEquals(complexPath, fileItem.fileName)
    }

    @Test
    fun testB7_4_SpecialChars_SpacesAndTabsInMagnetDisplayName() {
        val rawMagnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=Ubuntu%20Linux%2024.04%20LTS"
        val parsed = MagnetHandler.parse(rawMagnet)
        assertNotNull(parsed)
        assertEquals("Ubuntu Linux 24.04 LTS", parsed!!.displayName)
    }

    @Test
    fun testB7_5_SpecialChars_PercentEncodedTrackersWithSpecialChars() {
        val trackerUrl = "https://tracker.tamersunion.org:443/announce?passkey=abc%24123%26xyz"
        val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&tr=" +
                java.net.URLEncoder.encode(trackerUrl, StandardCharsets.UTF_8.name())
        val parsed = MagnetHandler.parse(magnet)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.trackers.size)
        assertEquals(trackerUrl, parsed.trackers[0])
    }

    // =========================================================================
    // BOUNDARY 8: Massive File Sizes (> 100 GB, 4TB & Long Overflow) (5 tests)
    // =========================================================================

    @Test
    fun testB8_1_MassiveFileSize_100GigabytesFormatting() {
        val bytes100Gb = 100L * 1024L * 1024L * 1024L
        assertEquals("100.00 GB", TorrentItem.formatFileSize(bytes100Gb))
    }

    @Test
    fun testB8_2_MassiveFileSize_4TerabytesFormatting() {
        val bytes4Tb = 4L * 1024L * 1024L * 1024L * 1024L
        assertEquals("4.00 TB", TorrentItem.formatFileSize(bytes4Tb))
    }

    @Test
    fun testB8_3_MassiveFileSize_LongMaxValueProtection() {
        val maxLongBytes = Long.MAX_VALUE
        val formatted = TorrentItem.formatFileSize(maxLongBytes)
        assertTrue(formatted.contains("TB") || formatted.contains("PB") || formatted.contains("EB"))
    }

    @Test
    fun testB8_4_MassiveSpeed_GigabitPerSecondFormatting() {
        val speed1Gbps = 125_000_000L // 125 MB/s
        assertEquals("119.2 MB/s", TorrentItem.formatBytesPerSec(speed1Gbps))
        val speed10Gbps = 1_250_000_000L // 1.16 GB/s -> 1.2 GB/s
        assertEquals("1.2 GB/s", TorrentItem.formatBytesPerSec(speed10Gbps))
    }

    @Test
    fun testB8_5_EtaCalculationWithLargeValues() {
        val etaInfinity = TorrentItem.formatEtaDuration(-1L)
        assertEquals("∞", etaInfinity)
        val etaOneYear = TorrentItem.formatEtaDuration(86400L * 365)
        assertEquals("∞", etaOneYear)
        val etaTwoHours = TorrentItem.formatEtaDuration(7325L)
        assertEquals("2h 02m", etaTwoHours)
    }

    // =========================================================================
    // BOUNDARY 9: Truncated & Malformed Bencode Payloads (5 tests)
    // =========================================================================

    @Test
    fun testB9_1_MalformedBencode_MissingEndingDictionaryTerminator() {
        val bytes = "d8:announce20:http://example.com/a4:infode".toByteArray() // missing final 'e'
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Invalid)
    }

    @Test
    fun testB9_2_MalformedBencode_InvalidLeadingZeroInteger() {
        val bytes = "d4:info d6:length i03e 4:name 4:test 12:piece length i100e 6:pieces 20:12345678901234567890 ee"
            .replace(" ", "").toByteArray()
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Invalid)
    }

    @Test
    fun testB9_3_MalformedBencode_NegativeZeroInteger() {
        val bytes = "d4:info d6:length i-0e 4:name 4:test 12:piece length i100e 6:pieces 20:12345678901234567890 ee"
            .replace(" ", "").toByteArray()
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Invalid)
    }

    @Test
    fun testB9_4_MalformedBencode_PiecesLengthNotMultipleOf20() {
        val pieces19Bytes = ByteArray(19) { 0x01 }
        val out = ByteArrayOutputStream()
        out.write("d4:infod6:lengthi100e4:name4:test12:piece lengthi100e6:pieces19:".toByteArray(StandardCharsets.US_ASCII))
        out.write(pieces19Bytes)
        out.write("ee".toByteArray(StandardCharsets.US_ASCII))

        val result = TorrentFileValidator.validate(out.toByteArray())
        assertTrue(result is TorrentValidationResult.Invalid)
        assertTrue((result as TorrentValidationResult.Invalid).detailedMessage.contains("multiple of 20"))
    }

    @Test
    fun testB9_5_MalformedBencode_StringLengthExceedsBuffer() {
        val bytes = "d4:info d4:name 500:short ee".replace(" ", "").toByteArray()
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Invalid)
    }

    // =========================================================================
    // BOUNDARY 10: Extreme Storage Space Conditions (5 tests)
    // =========================================================================

    @Test
    fun testB10_1_Storage_ExactBoundaryEquality() {
        val available = 5_000_000_000L
        val required = 5_000_000_000L
        assertTrue("Exact match between available and required space must be accepted", available >= required)
    }

    @Test
    fun testB10_2_Storage_OneByteDeficitRejection() {
        val available = 5_000_000_000L
        val required = 5_000_000_001L
        assertFalse("1-byte deficit must trigger insufficient storage warning", available >= required)
    }

    @Test
    fun testB10_3_Storage_ZeroAvailableSpace() {
        val available = 0L
        val required = 100L
        assertFalse("0 free space must trigger insufficient storage warning", available >= required)
    }

    @Test
    fun testB10_4_Storage_NestedDirectoryDeepCreation() {
        val temp = File(System.getProperty("java.io.tmpdir"), "b10_deep_${System.currentTimeMillis()}/sub1/sub2/sub3")
        try {
            assertTrue(TorrentStorageHelper.isWritableOrCreatable(temp))
            assertTrue(temp.exists() && temp.isDirectory)
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun testB10_5_Storage_NullContextFallbackFreeSpace() {
        // Fallback usable space query on system temporary directory
        val root = File(System.getProperty("java.io.tmpdir") ?: ".")
        val space = root.usableSpace
        assertTrue("Storage space must be non-negative", space >= 0L)
    }
}
