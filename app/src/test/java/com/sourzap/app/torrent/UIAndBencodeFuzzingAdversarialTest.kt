package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.BencodeValidator
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.model.PreDownloadFileItem
import com.sourzap.app.torrent.model.PreDownloadState
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.model.TorrentSource
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
import java.util.Random

/**
 * Empirical Adversarial & Fuzzing Challenge Test Suite for:
 * 1. Bencode Parser & Metadata Extraction (nested dicts, deep hierarchies, trailing garbage, 10,000+ files, 0-byte, multi-TB).
 * 2. Path Traversal & Filename Sanitization (../../, leading slashes, backslashes, null bytes).
 * 3. Pre-Download File Selection State Machine (rapid toggling, Select/Deselect All cycles, priority mapping).
 * 4. Free Disk Space & Insufficient Space Banner Calculations (boundaries, 0 space, Long boundaries).
 * 5. UI Models & Formatting Under Pressure.
 */
class UIAndBencodeFuzzingAdversarialTest {

    // =========================================================================
    // Helpers to build bencoded payloads
    // =========================================================================

    private fun buildSingleFileTorrent(
        name: String = "test.bin",
        fileLength: Long = 1048576L,
        pieceLength: Int = 262144,
        pieceCount: Int = 4,
        announce: String = "https://tracker.sourzap.org:443/announce"
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

    private fun buildMultiFileTorrent(
        dirName: String = "MultiDir",
        files: List<Pair<String, Long>>,
        pieceLength: Int = 65536,
        pieceCount: Int = 8,
        announce: String = "https://tracker.sourzap.org:443/announce"
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
        out.write("e".toByteArray(StandardCharsets.US_ASCII))
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
    // 1. BENCODE FUZZING: Pathological Nesting, Dictionaries, and Lists
    // =========================================================================

    @Test
    fun testBencodeFuzz_50LevelNestedDictionaries_SafelyRejected() {
        val out = ByteArrayOutputStream()
        out.write("d".toByteArray(StandardCharsets.US_ASCII))
        for (i in 1..50) {
            out.write("4:nestd".toByteArray(StandardCharsets.US_ASCII))
        }
        out.write("3:key5:value".toByteArray(StandardCharsets.US_ASCII))
        for (i in 1..51) {
            out.write("e".toByteArray(StandardCharsets.US_ASCII))
        }
        val result = TorrentFileValidator.validate(out.toByteArray())
        assertTrue("Deeply nested dictionary without info dict must be Invalid", result is TorrentValidationResult.Invalid)
        assertEquals("Missing info dictionary", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testBencodeFuzz_50LevelNestedLists_SafelyParsedOrRejected() {
        val out = ByteArrayOutputStream()
        out.write("d4:infod6:lengthi1000e4:name4:test12:piece lengthi16384e6:pieces20:123456789012345678908:deepnest".toByteArray(StandardCharsets.US_ASCII))
        for (i in 1..50) {
            out.write("l".toByteArray(StandardCharsets.US_ASCII))
        }
        out.write("4:item".toByteArray(StandardCharsets.US_ASCII))
        for (i in 1..50) {
            out.write("e".toByteArray(StandardCharsets.US_ASCII))
        }
        out.write("ee".toByteArray(StandardCharsets.US_ASCII))
        val result = TorrentFileValidator.validate(out.toByteArray())
        assertTrue("Valid info dict with deep custom lists must be accepted safely", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("test", valid.name)
        assertEquals(1000L, valid.totalSize)
    }

    @Test
    fun testBencodeFuzz_UnbalancedDictionaryNesting_Rejected() {
        val unbalanced = "d4:infod6:lengthi1000e4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890e".toByteArray(StandardCharsets.US_ASCII)
        val result = TorrentFileValidator.validate(unbalanced)
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Corrupted bencode", (result as TorrentValidationResult.Invalid).reason)
    }

    // =========================================================================
    // 2. BENCODE FUZZING: Trailing Garbage, Truncation, and Random Bit Mutators
    // =========================================================================

    @Test
    fun testBencodeFuzz_TrailingGarbageBytes_Rejected() {
        val valid = buildSingleFileTorrent()
        val withGarbage = valid + "GARBAGE_PAYLOAD_1234567890".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentFileValidator.validate(withGarbage)
        assertTrue("Trailing garbage bytes without 'e' at end must be Invalid", result is TorrentValidationResult.Invalid)
        assertEquals("Corrupted bencode", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testBencodeFuzz_TruncatedAtEveryByteBoundary_NeverThrowsUnhandledException() {
        val valid = buildSingleFileTorrent()
        for (len in 0 until valid.size) {
            val truncated = valid.copyOf(len)
            try {
                val result = TorrentFileValidator.validate(truncated)
                assertTrue("Truncated byte buffer at length $len must be Invalid", result is TorrentValidationResult.Invalid)
            } catch (t: Throwable) {
                throw AssertionError("Unhandled crash at truncation length $len: ${t.message}", t)
            }
        }
    }

    @Test
    fun testBencodeFuzz_RandomBitMutation_RobustnessHarness() {
        val valid = buildSingleFileTorrent()
        val rng = Random(424242)
        for (iteration in 1..200) {
            val mutated = valid.clone()
            val mutations = rng.nextInt(5) + 1
            for (m in 0 until mutations) {
                val bytePos = rng.nextInt(mutated.size)
                mutated[bytePos] = (mutated[bytePos].toInt() xor (1 shl rng.nextInt(8))).toByte()
            }
            try {
                val result = TorrentFileValidator.validate(mutated)
                assertNotNull("Result must not be null under random fuzzing", result)
            } catch (t: Throwable) {
                throw AssertionError("Crash during fuzz iteration $iteration: ${t.message}", t)
            }
        }
    }

    // =========================================================================
    // 3. BENCODE FUZZING: Massive File Count (10,000+ Files), 0-Byte & Multi-TB
    // =========================================================================

    @Test
    fun testBencodeFuzz_HugeFileCount_10000Files_PerformanceAndIntegrity() {
        val fileCount = 10000
        val files = (0 until fileCount).map { i ->
            "dataset/shard_${i / 1000}/part_${i}.bin" to 51200L
        }
        val startTime = System.currentTimeMillis()
        val bytes = buildMultiFileTorrent(dirName = "MassiveDataset", files = files, pieceCount = 100)
        val parseStartTime = System.currentTimeMillis()
        val result = TorrentFileValidator.validate(bytes)
        val elapsed = System.currentTimeMillis() - parseStartTime

        assertTrue("Validation must succeed for 10,000 files", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(fileCount, valid.fileCount)
        assertEquals(fileCount, valid.files.size)
        assertEquals(fileCount * 51200L, valid.totalSize)
        assertTrue("Parsing 10,000 files must complete within 3 seconds (took ${elapsed}ms)", elapsed < 3000)

        // Verify PreDownloadState aggregation with 10,000 files
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FileContent(bytes, "MassiveDataset.torrent"),
            name = valid.name,
            files = valid.files,
            targetDirectory = File("/storage/downloads"),
            availableDiskSpace = 1_000_000_000L
        )
        assertEquals(10000, state.totalCount)
        assertEquals(10000, state.selectedCount)
        assertEquals(fileCount * 51200L, state.totalSize)
        assertEquals(fileCount * 51200L, state.selectedSize)
        assertTrue(state.allSelected)
        assertFalse(state.noneSelected)
    }

    @Test
    fun testBencodeFuzz_MultiTerabyteFiles_50TB_NoOverflow() {
        val fiftyTerabytes = 50L * 1024L * 1024L * 1024L * 1024L // 54,975,581,388,800 bytes
        val bytes = buildSingleFileTorrent(name = "astronomy_sky_survey.raw", fileLength = fiftyTerabytes, pieceLength = 16777216, pieceCount = 10)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(fiftyTerabytes, valid.totalSize)
        assertEquals("50.00 TB", TorrentItem.formatFileSize(valid.totalSize))
    }

    @Test
    fun testBencodeFuzz_AllZeroByteFilesMultiTorrent() {
        val files = listOf(
            "empty1.txt" to 0L,
            "empty2.txt" to 0L,
            "sub/empty3.log" to 0L
        )
        val bytes = buildMultiFileTorrent(dirName = "EmptyBundle", files = files)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(0L, valid.totalSize)
        assertEquals(3, valid.files.size)
        assertTrue(valid.files.all { it.size == 0L })
    }

    @Test
    fun testBencodeFuzz_MixedZeroByteAndMultiGigabyteFiles() {
        val files = listOf(
            "zero.dat" to 0L,
            "huge_part.iso" to 8589934592L, // 8 GB
            "another_zero.dat" to 0L
        )
        val bytes = buildMultiFileTorrent(files = files)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals(8589934592L, valid.totalSize)
        assertEquals(3, valid.files.size)
    }

    // =========================================================================
    // 4. BENCODE FUZZING: Malformed Integers, Negative Values, and String Lengths
    // =========================================================================

    @Test
    fun testBencodeFuzz_MalformedInteger_ExceedingLongMax_Rejected() {
        val hugeInt = "d4:infod6:lengthi999999999999999999999999999999999999999999999e4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee"
        val result = TorrentFileValidator.validate(hugeInt.toByteArray(StandardCharsets.US_ASCII))
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Corrupted bencode", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testBencodeFuzz_MalformedInteger_LeadingZero_Rejected() {
        val leadingZero = "d4:infod6:lengthi0500e4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee"
        val result = TorrentFileValidator.validate(leadingZero.toByteArray(StandardCharsets.US_ASCII))
        assertTrue(result is TorrentValidationResult.Invalid)
        assertEquals("Corrupted bencode", (result as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun testBencodeFuzz_MalformedInteger_NegativeZero_Rejected() {
        val negZero = "d4:infod6:lengthi-0e4:name4:test12:piece lengthi16384e6:pieces20:12345678901234567890ee"
        val result = TorrentFileValidator.validate(negZero.toByteArray(StandardCharsets.US_ASCII))
        assertTrue(result is TorrentValidationResult.Invalid)
    }

    @Test
    fun testBencodeFuzz_MalformedStringLength_NegativeOrExceedingBuffer_Rejected() {
        val negLen = "d4:infod6:lengthi100e4:name-5:test12:piece lengthi16384e6:pieces20:12345678901234567890ee"
        val result1 = TorrentFileValidator.validate(negLen.toByteArray(StandardCharsets.US_ASCII))
        assertTrue(result1 is TorrentValidationResult.Invalid)

        val overflowLen = "d4:infod6:lengthi100e4:name9999999:test12:piece lengthi16384e6:pieces20:12345678901234567890ee"
        val result2 = TorrentFileValidator.validate(overflowLen.toByteArray(StandardCharsets.US_ASCII))
        assertTrue(result2 is TorrentValidationResult.Invalid)
    }

    // =========================================================================
    // 5. PATH TRAVERSAL SANITIZATION PROBING
    // =========================================================================

    @Test
    fun testPathTraversal_RelativeDotDotExtraction_LeavesOnlyLeaf() {
        val files = listOf(
            "../../etc/passwd" to 1000L,
            "../../../../var/log/syslog" to 2000L,
            "sub/../../secret.key" to 500L
        )
        val bytes = buildMultiFileTorrent(files = files)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid

        assertEquals("passwd", valid.files[0].fileName)
        assertEquals("syslog", valid.files[1].fileName)
        assertEquals("secret.key", valid.files[2].fileName)
    }

    @Test
    fun testPathTraversal_LeadingSlashesAndRootPaths() {
        val files = listOf(
            "/root/.bashrc" to 500L,
            "//system/bin/su" to 1000L
        )
        val bytes = buildMultiFileTorrent(files = files)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid

        assertEquals(".bashrc", valid.files[0].fileName)
        assertEquals("su", valid.files[1].fileName)
    }

    @Test
    fun testPathTraversal_WindowsBackslashesAndDriveLetters() {
        val item1 = PreDownloadFileItem(0, "C:\\Windows\\System32\\cmd.exe", 300000L)
        val item2 = PreDownloadFileItem(1, "..\\..\\boot.ini", 200L)
        val item3 = TorrentFileItem(0, "D:\\Downloads\\Sub\\Movie.mp4", 1000000L)

        assertEquals("cmd.exe", item1.fileName)
        assertEquals("boot.ini", item2.fileName)
        assertEquals("Movie.mp4", item3.fileName)
    }

    @Test
    fun testPathTraversal_MixedSeparatorsAndNullBytes() {
        val itemNull = PreDownloadFileItem(0, "innocent.pdf\u0000.exe", 1000L)
        assertEquals("innocent.pdf\u0000.exe", itemNull.fileName)

        val itemMixed = PreDownloadFileItem(1, "folder/sub\\nested/target.iso", 5000L)
        assertEquals("target.iso", itemMixed.fileName)
    }

    @Test
    fun testPathTraversal_DeepDirectoryHierarchy_100Levels() {
        val deepSegments = (1..100).map { "level$it" }
        val deepPath = deepSegments.joinToString("/") + "/deep_payload.bin"
        val files = listOf(deepPath to 12345L)
        val bytes = buildMultiFileTorrent(files = files)
        val result = TorrentFileValidator.validate(bytes)
        assertTrue(result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid
        assertEquals("deep_payload.bin", valid.files[0].fileName)
        assertEquals(deepPath, valid.files[0].path)
    }

    // =========================================================================
    // 6. PRE-DOWNLOAD SELECTION STATE MACHINE ADVERSARIAL STRESS
    // =========================================================================

    @Test
    fun testStateMachine_RapidToggleStress_1000Cycles() {
        val files = (0 until 10).map { PreDownloadFileItem(it, "file_$it.dat", 1000L * (it + 1), isSelected = true) }
        var state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock/test.torrent"),
            name = "TestStress",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = 100000L
        )
        val totalSum = files.sumOf { it.size }
        assertEquals(totalSum, state.selectedSize)

        // Rapid toggle index 0 for 1000 cycles
        for (cycle in 1..1000) {
            state = state.toggleFile(0)
            val expectedSelected = (cycle % 2 == 0)
            assertEquals(expectedSelected, state.files[0].isSelected)
            val expectedSize = if (expectedSelected) totalSum else totalSum - files[0].size
            assertEquals(expectedSize, state.selectedSize)
        }
    }

    @Test
    fun testStateMachine_SelectAllDeselectAllInterleavedWithSingleToggles() {
        val files = listOf(
            PreDownloadFileItem(0, "A.bin", 100L, isSelected = true),
            PreDownloadFileItem(1, "B.bin", 200L, isSelected = true),
            PreDownloadFileItem(2, "C.bin", 300L, isSelected = true),
            PreDownloadFileItem(3, "D.bin", 400L, isSelected = true)
        )
        var state = PreDownloadState.create(
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
            name = "InterleavedTest",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = 5000L
        )

        // Step 1: Deselect All
        state = state.deselectAll()
        assertTrue(state.noneSelected)
        assertFalse(state.allSelected)
        assertFalse(state.isDownloadEnabled)
        assertEquals(0L, state.selectedSize)
        assertEquals(0, state.selectedCount)

        // Step 2: Toggle Index 2 (300L)
        state = state.toggleFile(2)
        assertFalse(state.noneSelected)
        assertFalse(state.allSelected)
        assertTrue(state.isDownloadEnabled)
        assertEquals(300L, state.selectedSize)
        assertEquals(1, state.selectedCount)
        assertEquals(Priority.IGNORE, state.toPriorities()[0])
        assertEquals(Priority.IGNORE, state.toPriorities()[1])
        assertEquals(Priority.NORMAL, state.toPriorities()[2])
        assertEquals(Priority.IGNORE, state.toPriorities()[3])

        // Step 3: Select All
        state = state.selectAll()
        assertTrue(state.allSelected)
        assertFalse(state.noneSelected)
        assertTrue(state.isDownloadEnabled)
        assertEquals(1000L, state.selectedSize)
        assertEquals(4, state.selectedCount)
        assertTrue(state.toPriorities().all { it == Priority.NORMAL && it.value == 4 })

        // Step 4: Toggle Index 0 and 3
        state = state.toggleFile(0).toggleFile(3)
        assertEquals(2, state.selectedCount)
        assertEquals(500L, state.selectedSize) // B (200) + C (300)
        assertEquals(listOf(Priority.IGNORE, Priority.NORMAL, Priority.NORMAL, Priority.IGNORE), state.toPriorities())
    }

    @Test
    fun testStateMachine_SingleFileDeselectDisablesDownload() {
        val singleFile = listOf(PreDownloadFileItem(0, "archive.zip", 500000L, isSelected = true))
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock.torrent"),
            name = "Single",
            files = singleFile,
            targetDirectory = File("/mock")
        )
        assertTrue(state.isDownloadEnabled)

        val deselected = state.setFileSelected(0, false)
        assertFalse(deselected.isDownloadEnabled)
        assertEquals(0L, deselected.selectedSize)
        assertEquals(listOf(Priority.IGNORE), deselected.toPriorities())
    }

    // =========================================================================
    // 7. FREE DISK SPACE & INSUFFICIENT SPACE BANNER VALIDATION
    // =========================================================================

    @Test
    fun testDiskSpace_ZeroOrNegativeFreeSpace_NeverTriggersWarningBanner() {
        val files = listOf(PreDownloadFileItem(0, "game.iso", 10_000_000_000L))
        val stateZero = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock.torrent"),
            name = "ZeroDiskSpace",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = 0L // Unknown
        )
        assertFalse("0L available space indicates unknown/unsupported and must NOT trigger banner", stateZero.hasInsufficientSpace)

        val stateNegative = stateZero.copy(availableDiskSpace = -1000L)
        assertFalse("Negative space must NOT trigger banner", stateNegative.hasInsufficientSpace)
    }

    @Test
    fun testDiskSpace_ExactBoundaryEquality_NoBanner() {
        val files = listOf(PreDownloadFileItem(0, "file.bin", 5_000_000_000L))
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock.torrent"),
            name = "ExactSpace",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = 5_000_000_000L
        )
        assertFalse("Exact match between available and selected space must NOT trigger warning", state.hasInsufficientSpace)
    }

    @Test
    fun testDiskSpace_OneByteDeficit_TriggersWarningBanner() {
        val files = listOf(PreDownloadFileItem(0, "file.bin", 5_000_000_001L))
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock.torrent"),
            name = "OneByteDeficit",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = 5_000_000_000L
        )
        assertTrue("1 byte deficit MUST trigger insufficient storage warning banner", state.hasInsufficientSpace)
    }

    @Test
    fun testDiskSpace_ZeroSelectedSize_NoBannerEvenWithLowSpace() {
        val files = listOf(PreDownloadFileItem(0, "file.bin", 5_000_000_000L, isSelected = false))
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock.torrent"),
            name = "ZeroSelected",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = 100L
        )
        assertEquals(0L, state.selectedSize)
        assertFalse("When 0 bytes selected, banner must NOT trigger", state.hasInsufficientSpace)
    }

    @Test
    fun testDiskSpace_LongMaxValueOverflowProtection() {
        val files = listOf(PreDownloadFileItem(0, "huge.bin", Long.MAX_VALUE))
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock.torrent"),
            name = "LongMax",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = Long.MAX_VALUE - 1000L
        )
        assertTrue(state.hasInsufficientSpace)
    }

    // =========================================================================
    // 8. UI MODELS & STATS FORMATTING UNDER ADVERSARIAL INPUTS
    // =========================================================================

    @Test
    fun testUIModels_FormatFileSize_Extremes() {
        assertEquals("0 B", TorrentItem.formatFileSize(0L))
        assertEquals("0 B", TorrentItem.formatFileSize(-500L))
        assertEquals("1023 B", TorrentItem.formatFileSize(1023L))
        assertEquals("1.00 KB", TorrentItem.formatFileSize(1024L))
        assertEquals("1.00 MB", TorrentItem.formatFileSize(1048576L))
        assertEquals("1.00 GB", TorrentItem.formatFileSize(1073741824L))
        assertEquals("1.00 TB", TorrentItem.formatFileSize(1099511627776L))
        assertEquals("1000.00 TB", TorrentItem.formatFileSize(1099511627776000L))
    }

    @Test
    fun testUIModels_FormatBytesPerSec_Extremes() {
        assertEquals("0 B/s", TorrentItem.formatBytesPerSec(0L))
        assertEquals("0 B/s", TorrentItem.formatBytesPerSec(-50L))
        assertEquals("500 B/s", TorrentItem.formatBytesPerSec(500L))
        assertEquals("1.0 KB/s", TorrentItem.formatBytesPerSec(1024L))
        assertEquals("10.5 MB/s", TorrentItem.formatBytesPerSec(11010048L))
        assertEquals("1.0 GB/s", TorrentItem.formatBytesPerSec(1073741824L))
    }

    @Test
    fun testUIModels_FormatEtaDuration_Extremes() {
        assertEquals("∞", TorrentItem.formatEtaDuration(-1L))
        assertEquals("0s", TorrentItem.formatEtaDuration(0L))
        assertEquals("45s", TorrentItem.formatEtaDuration(45L))
        assertEquals("12m 34s", TorrentItem.formatEtaDuration(754L))
        assertEquals("3h 25m", TorrentItem.formatEtaDuration(12300L))
        assertEquals("∞", TorrentItem.formatEtaDuration(86400L * 400)) // > 1 year
    }

    @Test
    fun testUIModels_TorrentFilter_AllStateCombinations() {
        val dlItem = TorrentItem("1", "dl", TorrentState.DOWNLOADING, 0.5f, 100, 0, 1000, 500, 0, 1, 1)
        val seedingItem = TorrentItem("2", "sd", TorrentState.SEEDING, 1.0f, 0, 100, 1000, 1000, 500, 0, 5)
        val pausedItem = TorrentItem("3", "ps", TorrentState.PAUSED, 0.5f, 0, 0, 1000, 500, 0, 0, 0)
        val finishedItem = TorrentItem("4", "fn", TorrentState.FINISHED, 1.0f, 0, 0, 1000, 1000, 0, 0, 0)
        val metaItem = TorrentItem("5", "mt", TorrentState.METADATA, 0.0f, 50, 0, 0, 0, 0, 1, 2)
        val allocItem = TorrentItem("6", "al", TorrentState.ALLOCATING, 0.0f, 0, 0, 1000, 0, 0, 0, 0)

        // ALL filter
        assertTrue(TorrentFilter.ALL.matches(dlItem))
        assertTrue(TorrentFilter.ALL.matches(seedingItem))
        assertTrue(TorrentFilter.ALL.matches(pausedItem))
        assertTrue(TorrentFilter.ALL.matches(finishedItem))
        assertTrue(TorrentFilter.ALL.matches(metaItem))
        assertTrue(TorrentFilter.ALL.matches(allocItem))

        // DOWNLOADING filter (includes ALLOCATING and METADATA)
        assertTrue(TorrentFilter.DOWNLOADING.matches(dlItem))
        assertTrue(TorrentFilter.DOWNLOADING.matches(metaItem))
        assertTrue(TorrentFilter.DOWNLOADING.matches(allocItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(seedingItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(pausedItem))

        // SEEDING filter
        assertTrue(TorrentFilter.SEEDING.matches(seedingItem))
        assertFalse(TorrentFilter.SEEDING.matches(dlItem))

        // PAUSED filter
        assertTrue(TorrentFilter.PAUSED.matches(pausedItem))
        assertFalse(TorrentFilter.PAUSED.matches(dlItem))

        // COMPLETED filter
        assertTrue(TorrentFilter.COMPLETED.matches(seedingItem))
        assertTrue(TorrentFilter.COMPLETED.matches(finishedItem))
        assertFalse(TorrentFilter.COMPLETED.matches(dlItem))
        assertFalse(TorrentFilter.COMPLETED.matches(pausedItem))
    }

    @Test
    fun testUIModels_TorrentSessionStats_AggregationAndClamping() {
        val stats = TorrentSessionStats(
            totalDownloadSpeed = 25000000L, // 23.8 MB/s
            totalUploadSpeed = 5000000L,
            totalDownloadedBytes = 5000000000L,
            totalUploadedBytes = 1000000000L,
            activeTorrents = 3,
            pausedTorrents = 1,
            seedingTorrents = 2,
            dhtNodes = 350L,
            totalBytes = 10000000000L,
            aggregateProgress = 0.50f
        )
        assertEquals("23.8 MB/s", stats.formattedDownloadSpeed)
        assertEquals("50.0%", stats.formattedProgress)
        assertEquals(50, stats.progressPercent)
    }
}
