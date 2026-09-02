package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.BencodeValidator
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.model.PreDownloadFileItem
import com.sourzap.app.torrent.model.PreDownloadState
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Comprehensive Unit Test Suite for Pre-Download File Selection & Priority 0 Engine Enforcement (Milestone 3 / Requirement R2).
 * Verifies:
 * - Single-file and multi-file bencode extraction.
 * - Multi-byte UTF-8 path decoding.
 * - Deselecting file(s) sets priority to Priority.IGNORE (0) and reduces selectedSize.
 * - "Select All" and "Deselect All" state transitions.
 * - Custom save path assignment.
 * - Insufficient disk space detection when selectedSize > freeSpace.
 * - Priority enum and Libtorrent4j SWIG mappings.
 */
class PreDownloadFileSelectionTest {

    // =========================================================================
    // Helper Methods to build binary bencoded torrent buffers
    // =========================================================================

    private fun createSingleFileTorrent(
        name: String = "linux_distro.iso",
        fileLength: Long = 2147483648L, // 2 GB
        pieceLength: Int = 262144,
        pieceCount: Int = 8192,
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
        dirName: String = "AlbumRelease",
        files: List<Pair<String, Long>> = listOf(
            "01_Intro.flac" to 25000000L,
            "02_MainTrack.flac" to 45000000L,
            "03_Outro.flac" to 30000000L,
            "Artwork/cover.png" to 5000000L,
            "Lyrics/info.txt" to 10000L
        ),
        pieceLength: Int = 65536,
        pieceCount: Int = 1600,
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
    // 1. Single-File and Multi-File Bencode Extraction
    // =========================================================================

    @Test
    fun testSingleFileBencodeExtraction() {
        val bytes = createSingleFileTorrent(
            name = "archlinux-2026.09.01-x86_64.iso",
            fileLength = 987654321L
        )

        val result = TorrentFileValidator.validate(bytes)
        assertTrue("Validation must succeed", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid

        assertEquals("archlinux-2026.09.01-x86_64.iso", valid.name)
        assertEquals(987654321L, valid.totalSize)
        assertFalse("Must not be multi-file", valid.isMultiFile)
        assertEquals(1, valid.fileCount)
        assertEquals(1, valid.files.size)

        val item = valid.files.first()
        assertEquals(0, item.index)
        assertEquals("archlinux-2026.09.01-x86_64.iso", item.path)
        assertEquals("archlinux-2026.09.01-x86_64.iso", item.fileName)
        assertEquals(987654321L, item.size)
        assertTrue("Default selection should be true", item.isSelected)

        val extracted = TorrentFileValidator.extractFiles(bytes)
        assertEquals(1, extracted.size)
        assertEquals(item, extracted.first())
    }

    @Test
    fun testMultiFileBencodeExtraction() {
        val fileSpecs = listOf(
            "Season 1/S01E01.mkv" to 500000000L,
            "Season 1/S01E02.mkv" to 550000000L,
            "Season 1/S01E03.mkv" to 520000000L,
            "Subtitles/English.srt" to 45000L,
            "Subtitles/Spanish.srt" to 42000L,
            "readme.txt" to 1200L
        )
        val expectedTotalSize = fileSpecs.sumOf { it.second }

        val bytes = createMultiFileTorrent(
            dirName = "CoolShow_S01",
            files = fileSpecs
        )

        val result = TorrentFileValidator.validate(bytes)
        assertTrue("Validation must succeed", result is TorrentValidationResult.Valid)
        val valid = result as TorrentValidationResult.Valid

        assertEquals("CoolShow_S01", valid.name)
        assertEquals(expectedTotalSize, valid.totalSize)
        assertTrue("Must be multi-file", valid.isMultiFile)
        assertEquals(6, valid.fileCount)
        assertEquals(6, valid.files.size)

        for (i in fileSpecs.indices) {
            val fileItem = valid.files[i]
            assertEquals(i, fileItem.index)
            assertEquals(fileSpecs[i].first, fileItem.path)
            assertEquals(fileSpecs[i].first.substringAfterLast('/'), fileItem.fileName)
            assertEquals(fileSpecs[i].second, fileItem.size)
            assertTrue("Default selection must be true", fileItem.isSelected)
        }

        val extracted = BencodeValidator.extractFiles(bytes)
        assertEquals(6, extracted.size)
    }

    @Test
    fun testMultiByteUtf8PathsExtraction() {
        val fileSpecs = listOf(
            "日本語/エピソード01.mp4" to 100000000L,
            "Русский/Серия_01.mp4" to 120000000L,
            "🎬 Movies/Bonus 🚀.mkv" to 80000000L
        )
        val bytes = createMultiFileTorrent(
            dirName = "InternationalMedia",
            files = fileSpecs
        )

        val result = TorrentFileValidator.validate(bytes) as TorrentValidationResult.Valid
        assertEquals(3, result.files.size)

        assertEquals("日本語/エピソード01.mp4", result.files[0].path)
        assertEquals("エピソード01.mp4", result.files[0].fileName)

        assertEquals("Русский/Серия_01.mp4", result.files[1].path)
        assertEquals("Серия_01.mp4", result.files[1].fileName)

        assertEquals("🎬 Movies/Bonus 🚀.mkv", result.files[2].path)
        assertEquals("Bonus 🚀.mkv", result.files[2].fileName)
    }

    // =========================================================================
    // 2. Deselecting File(s) Sets Priority to Priority.IGNORE (0) & Reduces selectedSize
    // =========================================================================

    @Test
    fun testDeselectFileReducesSelectedSizeAndMapsToPriorityIgnore() {
        val files = listOf(
            PreDownloadFileItem(0, "video.mp4", 1000000000L, isSelected = true),
            PreDownloadFileItem(1, "sample.mp4", 50000000L, isSelected = true),
            PreDownloadFileItem(2, "subs.srt", 100000L, isSelected = true)
        )
        val targetDir = File("/mock/storage/downloads")
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock/test.torrent"),
            name = "Movie",
            files = files,
            targetDirectory = targetDir,
            availableDiskSpace = 5000000000L
        )

        assertEquals(1050100000L, state.totalSize)
        assertEquals(1050100000L, state.selectedSize)
        assertEquals(3, state.selectedCount)
        assertTrue(state.allSelected)
        assertFalse(state.noneSelected)
        assertTrue(state.isDownloadEnabled)

        // Deselect the sample video (index 1)
        val stateAfterDeselect = state.toggleFile(1)
        assertFalse("File 1 must now be deselected", stateAfterDeselect.files[1].isSelected)
        assertEquals(2, stateAfterDeselect.selectedCount)
        assertEquals(1000100000L, stateAfterDeselect.selectedSize)
        assertFalse(stateAfterDeselect.allSelected)
        assertFalse(stateAfterDeselect.noneSelected)
        assertTrue(stateAfterDeselect.isDownloadEnabled)

        // Verify priority mapping: index 1 must be IGNORE (0), others NORMAL (4)
        val priorities = stateAfterDeselect.toPriorities()
        assertEquals(3, priorities.size)
        assertEquals(Priority.NORMAL, priorities[0])
        assertEquals(Priority.IGNORE, priorities[1])
        assertEquals(Priority.NORMAL, priorities[2])

        // Verify explicit priority values
        assertEquals(4, priorities[0].value)
        assertEquals(0, priorities[1].value)
        assertEquals(4, priorities[2].value)

        // Verify libtorrent4j SWIG conversion
        assertEquals(org.libtorrent4j.Priority.IGNORE, priorities[1].toLibtorrentPriority())
        assertEquals(org.libtorrent4j.Priority.DEFAULT, priorities[0].toLibtorrentPriority())
    }

    // =========================================================================
    // 3. "Select All" and "Deselect All" State Transitions
    // =========================================================================

    @Test
    fun testSelectAllAndDeselectAllTransitions() {
        val files = listOf(
            PreDownloadFileItem(0, "fileA.bin", 100L, isSelected = true),
            PreDownloadFileItem(1, "fileB.bin", 200L, isSelected = true),
            PreDownloadFileItem(2, "fileC.bin", 300L, isSelected = true)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
            name = "TestBundle",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = 10000L
        )

        // Initial: all selected
        assertTrue(state.allSelected)
        assertEquals(600L, state.selectedSize)

        // Transition: Deselect All
        val deselectedState = state.deselectAll()
        assertTrue(deselectedState.noneSelected)
        assertFalse(deselectedState.allSelected)
        assertFalse(deselectedState.isDownloadEnabled)
        assertEquals(0, deselectedState.selectedCount)
        assertEquals(0L, deselectedState.selectedSize)

        val deselectedPriorities = deselectedState.toPriorities()
        assertTrue("All priorities must be IGNORE (0)", deselectedPriorities.all { it == Priority.IGNORE && it.value == 0 })

        // Transition: Select All
        val reselectedState = deselectedState.selectAll()
        assertTrue(reselectedState.allSelected)
        assertFalse(reselectedState.noneSelected)
        assertTrue(reselectedState.isDownloadEnabled)
        assertEquals(3, reselectedState.selectedCount)
        assertEquals(600L, reselectedState.selectedSize)

        val reselectedPriorities = reselectedState.toPriorities()
        assertTrue("All priorities must be NORMAL", reselectedPriorities.all { it == Priority.NORMAL && it.value == 4 })
    }

    @Test
    fun testZeroFilesSelectedDisablesDownload() {
        val files = listOf(
            PreDownloadFileItem(0, "only_file.zip", 500L, isSelected = true)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock/file.torrent"),
            name = "SingleZip",
            files = files,
            targetDirectory = File("/downloads")
        )

        assertTrue(state.isDownloadEnabled)

        val deselected = state.setFileSelected(0, false)
        assertFalse("When 0 files selected, download must be disabled", deselected.isDownloadEnabled)
        assertEquals(0, deselected.selectedCount)
        assertEquals(0L, deselected.selectedSize)
    }

    // =========================================================================
    // 4. Custom Save Path Assignment
    // =========================================================================

    @Test
    fun testCustomSavePathAssignment() {
        val initialDir = File("/storage/emulated/0/Download/SourZap")
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock/test.torrent"),
            name = "TestSavePath",
            files = listOf(PreDownloadFileItem(0, "file.bin", 1000L)),
            targetDirectory = initialDir,
            availableDiskSpace = 50000L
        )

        assertEquals(initialDir, state.targetDirectory)

        val customDir = File("/storage/emulated/0/Movies/CustomTorrentDir")
        val updatedState = state.withTargetDirectory(customDir)

        assertEquals(customDir, updatedState.targetDirectory)
        assertEquals(state.name, updatedState.name)
        assertEquals(state.files, updatedState.files)
        assertEquals(state.selectedSize, updatedState.selectedSize)
    }

    // =========================================================================
    // 5. Insufficient Disk Space Detection when selectedSize > freeSpace
    // =========================================================================

    @Test
    fun testInsufficientDiskSpaceDetection() {
        val files = listOf(
            PreDownloadFileItem(0, "game_part1.rar", 4000000000L, isSelected = true), // 4 GB
            PreDownloadFileItem(1, "game_part2.rar", 4000000000L, isSelected = true)  // 4 GB
        )
        // Free space is 5 GB (5,000,000,000 bytes). Selected size is 8 GB.
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock/game.torrent"),
            name = "BigGame",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = 5000000000L
        )

        assertEquals(8000000000L, state.selectedSize)
        assertEquals(5000000000L, state.availableDiskSpace)
        assertTrue("Must flag insufficient disk space when selectedSize > availableSpace", state.hasInsufficientSpace)

        // Deselect part 2 (4 GB) so selected size becomes 4 GB <= 5 GB free space
        val stateAfterDeselect = state.toggleFile(1)
        assertEquals(4000000000L, stateAfterDeselect.selectedSize)
        assertFalse("Must not flag insufficient space when selectedSize <= availableSpace", stateAfterDeselect.hasInsufficientSpace)
    }

    @Test
    fun testZeroOrUnknownDiskSpaceDoesNotFalseTriggerWarning() {
        val files = listOf(
            PreDownloadFileItem(0, "doc.pdf", 10000000L, isSelected = true)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock/doc.torrent"),
            name = "Doc",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = 0L // Unknown free space
        )

        assertFalse("0L available space should not trigger false positive warning", state.hasInsufficientSpace)
    }

    // =========================================================================
    // 6. Zero-Byte Files & Formatting
    // =========================================================================

    @Test
    fun testZeroByteFilesHandling() {
        val files = listOf(
            PreDownloadFileItem(0, ".nomedia", 0L, isSelected = true),
            PreDownloadFileItem(1, "data.bin", 5000L, isSelected = true)
        )
        val state = PreDownloadState.create(
            torrentSource = TorrentSource.FilePath("/mock/pkg.torrent"),
            name = "Package",
            files = files,
            targetDirectory = File("/downloads"),
            availableDiskSpace = 100000L
        )

        assertEquals(5000L, state.totalSize)
        assertEquals(5000L, state.selectedSize)

        // Deselect 0-byte file
        val toggled = state.toggleFile(0)
        assertEquals(1, toggled.selectedCount)
        assertEquals(5000L, toggled.selectedSize)
        assertEquals(Priority.IGNORE, toggled.toPriorities()[0])
        assertEquals(Priority.NORMAL, toggled.toPriorities()[1])
    }

    @Test
    fun testPriorityEnumValuesAndLibtorrentMappings() {
        assertEquals(0, Priority.IGNORE.value)
        assertEquals(1, Priority.LOW.value)
        assertEquals(4, Priority.NORMAL.value)
        assertEquals(7, Priority.HIGH.value)

        assertEquals(Priority.IGNORE, Priority.fromValue(0))
        assertEquals(Priority.IGNORE, Priority.fromValue(-5))
        assertEquals(Priority.LOW, Priority.fromValue(1))
        assertEquals(Priority.LOW, Priority.fromValue(2))
        assertEquals(Priority.NORMAL, Priority.fromValue(4))
        assertEquals(Priority.NORMAL, Priority.fromValue(5))
        assertEquals(Priority.HIGH, Priority.fromValue(7))
        assertEquals(Priority.HIGH, Priority.fromValue(10))

        assertEquals(org.libtorrent4j.Priority.IGNORE, Priority.IGNORE.toLibtorrentPriority())
        assertEquals(org.libtorrent4j.Priority.DEFAULT, Priority.NORMAL.toLibtorrentPriority())
    }
}
