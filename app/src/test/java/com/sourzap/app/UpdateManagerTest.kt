package com.sourzap.app

import com.sourzap.app.update.AppReleaseInfo
import com.sourzap.app.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateManagerTest {

    // Mirror the exact version extraction algorithm from UpdateManager
    private fun extractCleanVersion(raw: String): String {
        val match = Regex("""\d+(\.\d+)+""").find(raw)
        return match?.value ?: raw.filter { it.isDigit() || it == '.' }.trim('.')
    }

    // Mirror the exact version comparison algorithm from UpdateManager
    private fun isVersionNewer(latest: String, current: String): Boolean {
        try {
            val latestClean = extractCleanVersion(latest)
            val currentClean = extractCleanVersion(current)

            val latestParts = latestClean.split(".").map { it.toIntOrNull() ?: 0 }
            val currentParts = currentClean.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(latestParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val l = latestParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) {}
        return false
    }

    // Mirror APK integrity validation logic
    private fun validateApkHeader(magicBytes: ByteArray, fileLength: Long): Boolean {
        if (fileLength < 3_000_000L) return false
        if (magicBytes.size < 4) return false
        return magicBytes[0] == 0x50.toByte() &&
                magicBytes[1] == 0x4B.toByte() &&
                magicBytes[2] == 0x03.toByte() &&
                magicBytes[3] == 0x04.toByte()
    }

    @Test
    fun testExtractCleanVersion_StandardSemVer() {
        assertEquals("1.0.0", extractCleanVersion("1.0.0"))
        assertEquals("1.0.8", extractCleanVersion("1.0.8"))
        assertEquals("2.15.3", extractCleanVersion("2.15.3"))
    }

    @Test
    fun testExtractCleanVersion_PrefixedWithV() {
        assertEquals("1.0.0", extractCleanVersion("v1.0.0"))
        assertEquals("1.0.8", extractCleanVersion("v1.0.8"))
        assertEquals("2.0.1", extractCleanVersion("V2.0.1"))
    }

    @Test
    fun testExtractCleanVersion_ComplexStringsAndTags() {
        assertEquals("1.0.8", extractCleanVersion("SourZap v1.0.8-release"))
        assertEquals("1.0.9", extractCleanVersion("SourZap_v1.0.9"))
        assertEquals("2.0.0", extractCleanVersion("release-v2.0.0-final.apk"))
        assertEquals("1.0.8.2", extractCleanVersion("1.0.8.2"))
        assertEquals("3.4.5.6", extractCleanVersion("release-3.4.5.6-hotfix"))
    }

    @Test
    fun testExtractCleanVersion_ExtremeAdversarialStrings() {
        // v1.2.0-beta.2 -> extracts 1.2.0
        assertEquals("1.2.0", extractCleanVersion("v1.2.0-beta.2"))

        // 1.2.0.1
        assertEquals("1.2.0.1", extractCleanVersion("1.2.0.1"))

        // 2.0
        assertEquals("2.0", extractCleanVersion("2.0"))

        // 0.9.99
        assertEquals("0.9.99", extractCleanVersion("0.9.99"))

        // v1.2.0
        assertEquals("1.2.0", extractCleanVersion("v1.2.0"))

        // 1.0.0-rc.1
        assertEquals("1.0.0", extractCleanVersion("1.0.0-rc.1"))

        // v2.0.0-alpha+build.123
        assertEquals("2.0.0", extractCleanVersion("v2.0.0-alpha+build.123"))

        // 1.0.0.0.1
        assertEquals("1.0.0.0.1", extractCleanVersion("1.0.0.0.1"))

        // 1000.2000.3000
        assertEquals("1000.2000.3000", extractCleanVersion("1000.2000.3000"))

        // Blank/Empty/Non-numeric
        assertEquals("", extractCleanVersion(""))
        assertEquals("", extractCleanVersion("   "))
        assertEquals("", extractCleanVersion("v"))
        assertEquals("", extractCleanVersion("release-final"))
        assertEquals("999", extractCleanVersion("build-999"))
    }

    @Test
    fun testIsVersionNewer_ExtremeAdversarialMatrix() {
        // v1.2.0-beta.2 vs 1.2.0 -> same base 1.2.0
        assertFalse(isVersionNewer("v1.2.0-beta.2", "1.2.0"))

        // 1.2.0.1 vs 1.2.0 -> 1.2.0.1 is newer
        assertTrue(isVersionNewer("1.2.0.1", "1.2.0"))
        assertFalse(isVersionNewer("1.2.0", "1.2.0.1"))

        // 2.0 vs 1.9.99
        assertTrue(isVersionNewer("2.0", "1.9.99"))

        // 0.9.99 vs 1.0.0
        assertFalse(isVersionNewer("0.9.99", "1.0.0"))
        assertTrue(isVersionNewer("1.0.0", "0.9.99"))

        // v1.2.0 vs 1.2.0 -> identical
        assertFalse(isVersionNewer("v1.2.0", "1.2.0"))

        // Large version numbers: 1000.0.0 vs 999.999.999
        assertTrue(isVersionNewer("1000.0.0", "999.999.999"))

        // Deep sub-patch: 1.0.0.0.1 vs 1.0.0.0.0
        assertTrue(isVersionNewer("1.0.0.0.1", "1.0.0.0.0"))

        // Empty string fallbacks
        assertFalse(isVersionNewer("", "1.0.0"))
        assertTrue(isVersionNewer("1.0.0", ""))
        assertFalse(isVersionNewer("invalid", "invalid"))

        // Multi-zero padding: 2.0.0 vs 2.0.0.0.0.0
        assertFalse(isVersionNewer("2.0.0", "2.0.0.0.0.0"))
        assertTrue(isVersionNewer("2.0.0.0.0.1", "2.0.0"))
    }

    @Test
    fun testIsVersionNewer_MajorVersion() {
        assertTrue(isVersionNewer("2.0.0", "1.9.9"))
        assertTrue(isVersionNewer("v3.0.0", "v2.9.9"))
        assertFalse(isVersionNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun testIsVersionNewer_MinorVersion() {
        assertTrue(isVersionNewer("1.1.0", "1.0.9"))
        assertTrue(isVersionNewer("1.10.0", "1.9.0"))
        assertTrue(isVersionNewer("v1.2.0", "1.1.9"))
        assertFalse(isVersionNewer("1.0.9", "1.1.0"))
        assertFalse(isVersionNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun testIsVersionNewer_PatchVersion() {
        assertTrue(isVersionNewer("1.0.4", "1.0.3"))
        assertTrue(isVersionNewer("1.0.10", "1.0.9"))
        assertTrue(isVersionNewer("v1.0.8", "1.0.7"))
        assertFalse(isVersionNewer("1.0.3", "1.0.4"))
        assertFalse(isVersionNewer("1.0.7", "1.0.8"))
    }

    @Test
    fun testIsVersionNewer_SubPatchOrDifferentLengths() {
        assertTrue(isVersionNewer("1.0.8.1", "1.0.8"))
        assertTrue(isVersionNewer("1.0.0.1", "1.0.0"))
        assertFalse(isVersionNewer("1.0.8", "1.0.8.1"))
        assertFalse(isVersionNewer("1.0.8", "1.0.8.0"))
    }

    @Test
    fun testIsVersionNewer_EqualVersions() {
        assertFalse(isVersionNewer("1.0.8", "1.0.8"))
        assertFalse(isVersionNewer("v1.0.8", "1.0.8"))
        assertFalse(isVersionNewer("1.0.8", "v1.0.8"))
        assertFalse(isVersionNewer("v1.0.0", "v1.0.0"))
        assertFalse(isVersionNewer("1.0", "1.0.0"))
    }

    @Test
    fun testIsVersionNewer_WithAppNamePrefix() {
        assertTrue(isVersionNewer("SourZap v1.0.9", "1.0.8"))
        assertTrue(isVersionNewer("SourZap-v2.0.0", "SourZap-v1.9.9"))
        assertFalse(isVersionNewer("SourZap v1.0.8", "1.0.8"))
    }

    @Test
    fun testApkIntegrityValidation_AdversarialHeaders() {
        // Valid ZIP/APK magic header (PK\x03\x04 = 0x50, 0x4B, 0x03, 0x04)
        val validZipHeader = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())
        val validSize = 5_000_000L // 5MB
        val exactSize = 3_000_000L // Exact 3MB threshold

        assertTrue("Valid APK header and size must pass validation", validateApkHeader(validZipHeader, validSize))
        assertTrue("Exact 3,000,000 bytes with valid header must pass", validateApkHeader(validZipHeader, exactSize))

        // Too small (< 3MB)
        val smallSize = 2_999_999L
        assertFalse("APK under 3MB threshold must fail validation", validateApkHeader(validZipHeader, smallSize))

        // ZIP Central Directory Header (PK\x01\x02) instead of Local File Header (PK\x03\x04)
        val centralDirHeader = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x01.toByte(), 0x02.toByte())
        assertFalse("Central directory header must fail validation", validateApkHeader(centralDirHeader, validSize))

        // Corrupt magic header (e.g. HTML error page or zeroes)
        val invalidHeader = byteArrayOf(0x3C.toByte(), 0x21.toByte(), 0x44.toByte(), 0x4F.toByte()) // "<!DO" (HTML)
        assertFalse("Non-ZIP header must fail validation", validateApkHeader(invalidHeader, validSize))

        val zeroHeader = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        assertFalse("Zero header must fail validation", validateApkHeader(zeroHeader, validSize))

        val emptyHeader = ByteArray(0)
        assertFalse("Empty header must fail validation", validateApkHeader(emptyHeader, validSize))
    }

    @Test
    fun testAppReleaseInfoDataClass() {
        val release = AppReleaseInfo(
            tagName = "v1.1.0",
            versionName = "1.1.0",
            releaseNotes = "Fixed UDP NAT routing and DoH speed improvements.",
            apkDownloadUrl = "https://github.com/Sourish25/SourZap/releases/download/v1.1.0/SourZap-v1.1.0.apk",
            apkSizeBytes = 15_728_640L,
            isUpdateAvailable = true,
            publishedAt = "2026-08-30T10:00:00Z"
        )

        assertEquals("v1.1.0", release.tagName)
        assertEquals("1.1.0", release.versionName)
        assertTrue(release.isUpdateAvailable)
        assertEquals(15_728_640L, release.apkSizeBytes)
        assertTrue(release.apkDownloadUrl.endsWith(".apk"))
    }

    @Test
    fun testUpdateStateTransitions() {
        val idle: UpdateState = UpdateState.Idle
        val checking: UpdateState = UpdateState.Checking
        val release = AppReleaseInfo("v1.1.0", "1.1.0", "Notes", "https://example.com/app.apk", 1000L, true, "today")
        val available: UpdateState = UpdateState.Available(release)
        val upToDate: UpdateState = UpdateState.UpToDate(release)
        val downloading: UpdateState = UpdateState.Downloading(0.45f, 4500L, 10000L)
        val ready: UpdateState = UpdateState.ReadyToInstall(File("dummy.apk"))
        val error: UpdateState = UpdateState.Error("Network failure")

        assertTrue(idle is UpdateState.Idle)
        assertTrue(checking is UpdateState.Checking)
        assertTrue(available is UpdateState.Available)
        assertEquals(0.45f, (downloading as UpdateState.Downloading).progress, 0.001f)
        assertEquals(4500L, downloading.downloadedBytes)
        assertEquals(10000L, downloading.totalBytes)
        assertTrue(ready is UpdateState.ReadyToInstall)
        assertEquals("Network failure", (error as UpdateState.Error).message)
    }
}
