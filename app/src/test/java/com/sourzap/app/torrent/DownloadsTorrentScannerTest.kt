package com.sourzap.app.torrent

import android.content.Context
import android.content.ContextWrapper
import com.sourzap.app.torrent.core.DiscoveredTorrentFile
import com.sourzap.app.torrent.core.DownloadsTorrentScanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Comprehensive Unit Test Suite for [DownloadsTorrentScanner] and [DiscoveredTorrentFile].
 * Verifies Milestone 2 Requirements:
 * - Scans device filesystem and Downloads directories for .torrent files
 * - Case-insensitive .torrent filtering (e.g. .torrent, .TORRENT, .Torrent)
 * - Excludes non-torrent files (.txt, .pdf, .iso, .png)
 * - Bounded recursive subfolder scanning
 * - Deduplication by lowercased filename preferring the newest lastModified timestamp
 * - Descending sorting by lastModified
 * - Binary payload reading via [DiscoveredTorrentFile.readBytes]
 * - Exception safety and null-directory robustness
 */
class DownloadsTorrentScannerTest {

    private val tempBase = File(
        System.getProperty("java.io.tmpdir") ?: ".",
        "sourzap_scanner_test_${System.currentTimeMillis()}"
    )

    @Before
    fun setUp() {
        tempBase.mkdirs()
    }

    @After
    fun tearDown() {
        tempBase.deleteRecursively()
    }

    private class MockScannerContext(
        private val extDownloads: File? = null,
        private val extFiles: File? = null,
        private val internalFiles: File
    ) : ContextWrapper(null) {
        override fun getExternalFilesDir(type: String?): File? {
            return if (type == null || type.contains("Download", ignoreCase = true)) {
                extDownloads ?: extFiles ?: internalFiles
            } else {
                extFiles ?: internalFiles
            }
        }

        override fun getFilesDir(): File {
            return internalFiles
        }
    }

    @Test
    fun testDiscoveredTorrentFile_ReadBytesFromRealFile() {
        val sampleBytes = "d8:announce26:http://tracker.example.com4:infod6:lengthi1024e4:name8:test.iso12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val file = File(tempBase, "ubuntu.torrent").apply {
            writeBytes(sampleBytes)
        }

        val discovered = DiscoveredTorrentFile(
            name = file.name,
            size = file.length(),
            lastModified = file.lastModified(),
            file = file
        )

        val mockContext = MockScannerContext(internalFiles = tempBase)
        val read = discovered.readBytes(mockContext)

        assertNotNull("Read bytes must not be null for existing file", read)
        assertTrue("Read bytes must match file content exactly", read!!.contentEquals(sampleBytes))
    }

    @Test
    fun testDiscoveredTorrentFile_ReadBytesNonExistentFileReturnsNull() {
        val nonExistent = File(tempBase, "missing_file_${System.currentTimeMillis()}.torrent")
        val discovered = DiscoveredTorrentFile(
            name = nonExistent.name,
            size = 100L,
            lastModified = System.currentTimeMillis(),
            file = nonExistent
        )

        val mockContext = MockScannerContext(internalFiles = tempBase)
        val read = discovered.readBytes(mockContext)

        assertNull("Read bytes must be null for non-existent file", read)
    }

    @Test
    fun testDiscoveredTorrentFile_FormattedSize() {
        val zero = DiscoveredTorrentFile("0.torrent", 0L, 0L)
        assertEquals("0 B", zero.formattedSize)

        val kb = DiscoveredTorrentFile("kb.torrent", 2048L, 0L)
        assertEquals("2.0 KB", kb.formattedSize)

        val mb = DiscoveredTorrentFile("mb.torrent", 5 * 1024 * 1024L, 0L)
        assertEquals("5.0 MB", mb.formattedSize)

        val gb = DiscoveredTorrentFile("gb.torrent", 3L * 1024 * 1024 * 1024L, 0L)
        assertEquals("3.0 GB", gb.formattedSize)
    }

    @Test
    fun testDownloadsTorrentScanner_DiscoversTorrentsAndFiltersNonTorrents() = runBlocking {
        val downloadsDir = File(tempBase, "Downloads").apply { mkdirs() }
        val internalDir = File(tempBase, "internal").apply { mkdirs() }

        // Valid torrent files
        val file1 = File(downloadsDir, "archlinux.torrent").apply { writeText("dummy bencode") }
        val file2 = File(downloadsDir, "fedora.TORRENT").apply { writeText("dummy bencode") }
        val subDir = File(downloadsDir, "sub_folder").apply { mkdirs() }
        val file3 = File(subDir, "debian.Torrent").apply { writeText("dummy bencode") }

        // Non-torrent files (must be excluded)
        File(downloadsDir, "readme.txt").writeText("text data")
        File(downloadsDir, "image.png").writeText("png data")
        File(downloadsDir, "archive.iso").writeText("iso data")
        File(subDir, "notes.pdf").writeText("pdf data")

        val mockContext = MockScannerContext(
            extDownloads = downloadsDir,
            extFiles = null,
            internalFiles = internalDir
        )

        val results = DownloadsTorrentScanner.scanDownloads(mockContext)

        assertEquals("Must discover exactly 3 .torrent files", 3, results.size)
        val names = results.map { it.name.lowercase() }
        assertTrue("Must contain archlinux.torrent", names.contains("archlinux.torrent"))
        assertTrue("Must contain fedora.torrent", names.contains("fedora.torrent"))
        assertTrue("Must contain debian.torrent", names.contains("debian.torrent"))
    }

    @Test
    fun testDownloadsTorrentScanner_DeduplicationPrefersLatestTimestamp() = runBlocking {
        val downloadsDir = File(tempBase, "Downloads").apply { mkdirs() }
        val internalDir = File(tempBase, "internal").apply { mkdirs() }

        // Older file
        val olderFile = File(downloadsDir, "ubuntu-24.04.torrent").apply {
            writeText("older version")
            setLastModified(1000000000L)
        }

        // Newer file with same name (case-insensitive) in a subfolder
        val subDir = File(downloadsDir, "SourZap").apply { mkdirs() }
        val newerFile = File(subDir, "UBUNTU-24.04.TORRENT").apply {
            writeText("newer version")
            setLastModified(2000000000L)
        }

        val mockContext = MockScannerContext(
            extDownloads = downloadsDir,
            internalFiles = internalDir
        )

        val results = DownloadsTorrentScanner.scanDownloads(mockContext)

        val duplicates = results.filter { it.name.equals("ubuntu-24.04.torrent", ignoreCase = true) }
        assertEquals("Duplicate filenames must be deduplicated to 1 item", 1, duplicates.size)
        assertEquals("Deduplicated file must retain the newer timestamp", newerFile.lastModified(), duplicates.first().lastModified)
    }

    @Test
    fun testDownloadsTorrentScanner_SortingDescendingByLastModified() = runBlocking {
        val downloadsDir = File(tempBase, "Downloads").apply { mkdirs() }
        val internalDir = File(tempBase, "internal").apply { mkdirs() }

        val f1 = File(downloadsDir, "f1.torrent").apply { writeText("1"); setLastModified(10000L) }
        val f2 = File(downloadsDir, "f2.torrent").apply { writeText("2"); setLastModified(50000L) }
        val f3 = File(downloadsDir, "f3.torrent").apply { writeText("3"); setLastModified(30000L) }
        val f4 = File(downloadsDir, "f4.torrent").apply { writeText("4"); setLastModified(80000L) }

        val mockContext = MockScannerContext(
            extDownloads = downloadsDir,
            internalFiles = internalDir
        )

        val results = DownloadsTorrentScanner.scanDownloads(mockContext)
        val timestamps = results.map { it.lastModified }

        assertEquals("Must discover 4 files", 4, results.size)
        assertEquals("Timestamps must be sorted in descending order", timestamps.sortedDescending(), timestamps)
        assertEquals("First item must be the most recent", f4.name, results.first().name)
    }

    @Test
    fun testDownloadsTorrentScanner_EmptyOrInaccessibleDirsDoNotCrash() = runBlocking {
        val emptyDir = File(tempBase, "empty_dir") // exists = false
        val mockContext = MockScannerContext(
            extDownloads = emptyDir,
            extFiles = emptyDir,
            internalFiles = emptyDir
        )

        val results = DownloadsTorrentScanner.scanDownloads(mockContext)
        assertNotNull("Results should not be null", results)
        assertTrue("Results should be empty", results.isEmpty())
    }
}
