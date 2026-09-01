package com.sourzap.app.torrent

import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import com.sourzap.app.torrent.core.TorrentStorageHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests validating Scoped Storage resolution and directory safety in TorrentStorageHelper.
 */
class TorrentStorageHelperTest {

    private val tempBase = File(System.getProperty("java.io.tmpdir") ?: ".", "sourzap_storage_test_${System.currentTimeMillis()}")

    @Before
    fun setup() {
        tempBase.mkdirs()
    }

    @After
    fun tearDown() {
        tempBase.deleteRecursively()
    }

    private class MockTestContext(
        private val externalDownloads: File? = null,
        private val externalFiles: File? = null,
        private val internalFiles: File
    ) : ContextWrapper(null) {
        private var callCount = 0

        override fun getExternalFilesDir(type: String?): File? {
            return if (callCount++ == 0) {
                externalDownloads ?: externalFiles
            } else {
                externalFiles
            }
        }

        override fun getFilesDir(): File {
            return internalFiles
        }
    }

    @Test
    fun testGetSaveDirectory_PrimaryExternalDownloads() {
        val extDownloads = File(tempBase, "ext_downloads")
        extDownloads.mkdirs()

        val mockContext = MockTestContext(
            externalDownloads = extDownloads,
            internalFiles = File(tempBase, "internal")
        )
        val saveDir = TorrentStorageHelper.getSaveDirectory(mockContext)

        assertNotNull(saveDir)
        assertEquals(File(extDownloads, "SourZap").absolutePath, saveDir.absolutePath)
        assertTrue("Resolved directory must exist", saveDir.exists())
    }

    @Test
    fun testGetSaveDirectory_SecondaryExternalFilesFallback() {
        val extFiles = File(tempBase, "ext_files")
        extFiles.mkdirs()

        // Null external downloads, fallback to external files
        val mockContext = MockTestContext(
            externalDownloads = null,
            externalFiles = extFiles,
            internalFiles = File(tempBase, "internal")
        )
        val saveDir = TorrentStorageHelper.getSaveDirectory(mockContext)

        assertNotNull(saveDir)
        assertEquals(File(extFiles, "SourZap").absolutePath, saveDir.absolutePath)
        assertTrue(saveDir.exists())
    }

    @Test
    fun testGetSaveDirectory_InternalFilesFallbackWhenExternalNull() {
        val internalFiles = File(tempBase, "internal_fallback")
        internalFiles.mkdirs()

        // Null external dirs, fallback to internal filesDir
        val mockContext = MockTestContext(
            externalDownloads = null,
            externalFiles = null,
            internalFiles = internalFiles
        )
        val saveDir = TorrentStorageHelper.getSaveDirectory(mockContext)

        assertNotNull(saveDir)
        assertEquals(File(internalFiles, "SourZap").absolutePath, saveDir.absolutePath)
        assertTrue(saveDir.exists())
    }

    @Test
    fun testGetSaveDirectory_CustomSubDirName() {
        val extDownloads = File(tempBase, "ext_custom")
        extDownloads.mkdirs()

        val mockContext = MockTestContext(
            externalDownloads = extDownloads,
            internalFiles = File(tempBase, "internal")
        )
        val saveDir = TorrentStorageHelper.getSaveDirectory(mockContext, subDirName = "CustomTorrentFolder")

        assertNotNull(saveDir)
        assertEquals(File(extDownloads, "CustomTorrentFolder").absolutePath, saveDir.absolutePath)
        assertTrue(saveDir.exists())
    }

    @Test
    fun testGetSaveDirectory_EmptySubDirNameUsesBase() {
        val extDownloads = File(tempBase, "ext_direct")
        extDownloads.mkdirs()

        val mockContext = MockTestContext(
            externalDownloads = extDownloads,
            internalFiles = File(tempBase, "internal")
        )
        val saveDir = TorrentStorageHelper.getSaveDirectory(mockContext, subDirName = "")

        assertNotNull(saveDir)
        assertEquals(extDownloads.absolutePath, saveDir.absolutePath)
    }

    @Test
    fun testIsWritableOrCreatable_ValidDirectory() {
        val dir = File(tempBase, "writable_test")
        assertTrue(TorrentStorageHelper.isWritableOrCreatable(dir))
    }

    @Test
    fun testIsWritableOrCreatable_InvalidPath() {
        // Create a regular file, then attempt to create a child directory inside it
        val regularFile = File(tempBase, "blocked_file.txt").apply { writeText("data") }
        val impossibleDir = File(regularFile, "invalid_child_dir")
        assertFalse(TorrentStorageHelper.isWritableOrCreatable(impossibleDir))
        assertFalse(TorrentStorageHelper.isWritableOrCreatable(regularFile))
    }

    @Test
    fun testGetSaveDirectory_ExternalDownloadsIsRegularFile_FallsBackToSecondary() {
        val blockedFile = File(tempBase, "blocked_downloads_file.txt").apply { writeText("file") }
        val extFiles = File(tempBase, "valid_ext_files").apply { mkdirs() }
        val mockContext = MockTestContext(
            externalDownloads = blockedFile,
            externalFiles = extFiles,
            internalFiles = File(tempBase, "internal")
        )
        val saveDir = TorrentStorageHelper.getSaveDirectory(mockContext)
        assertNotNull(saveDir)
        assertEquals(File(extFiles, "SourZap").absolutePath, saveDir.absolutePath)
        assertTrue(saveDir.exists())
        assertTrue(saveDir.isDirectory)
    }

    @Test
    fun testGetAvailableFreeSpaceBytes() {
        val internalFiles = File(tempBase, "space_check")
        internalFiles.mkdirs()

        val mockContext = MockTestContext(internalFiles = internalFiles)
        val freeBytes = TorrentStorageHelper.getAvailableFreeSpaceBytes(mockContext)

        assertTrue("Free space should be non-negative", freeBytes >= 0L)
    }
}
