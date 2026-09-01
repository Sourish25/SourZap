package com.sourzap.app.e2e

import com.sourzap.app.torrent.core.DohTrackerResolver
import com.sourzap.app.torrent.core.MagnetHandler
import com.sourzap.app.torrent.core.MagnetInfo
import com.sourzap.app.torrent.core.TorrentStorageHelper
import com.sourzap.app.torrent.core.TrackerInjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * E2E Storage, Safe Directory Resolution, Magnet Parsing & Tracker Metadata Test Suite.
 * Covers Features F1, F2, F8 (Requirements R1, R2).
 */
class StorageAndMetadataE2ETest {

    @Before
    fun setUp() {
        DohTrackerResolver.clearCache()
    }

    // =========================================================================
    // FEATURE F2: Scoped Storage Safe Directory Resolution
    // =========================================================================

    @Test
    fun testStorageHelper_DirectoryWritabilityVerification() {
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "sourzap_test_dir_${System.currentTimeMillis()}")
        try {
            assertTrue(TorrentStorageHelper.isWritableOrCreatable(tempRoot))
            assertTrue(tempRoot.exists())
            assertTrue(tempRoot.isDirectory)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testStorageHelper_NestedFolderCreation() {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "sourzap_nested_${System.currentTimeMillis()}")
        val subDir = File(baseDir, "nested/downloads/torrents")
        try {
            assertTrue(TorrentStorageHelper.isWritableOrCreatable(subDir))
            assertTrue(subDir.exists())
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun testStorageHelper_InvalidDirectoryHandling() {
        // Non-creatable path on Windows / standard systems (empty file as directory parent)
        val dummyFile = File(System.getProperty("java.io.tmpdir"), "dummy_file_${System.currentTimeMillis()}")
        dummyFile.createNewFile()
        val impossibleDir = File(dummyFile, "impossible_child")
        try {
            val writable = TorrentStorageHelper.isWritableOrCreatable(impossibleDir)
            assertFalse("Child of a regular file cannot be created as directory", writable)
        } finally {
            dummyFile.delete()
        }
    }

    @Test
    fun testStorageHelper_FallbackResolutionLogic() {
        // Verify fallback directory logic: when external is null/unwritable, fall back to internal
        fun resolveFallbackDir(extDownloads: File?, extFiles: File?, internalFiles: File, subDir: String = "SourZap"): File {
            val base = if (extDownloads != null && TorrentStorageHelper.isWritableOrCreatable(extDownloads)) {
                extDownloads
            } else if (extFiles != null && TorrentStorageHelper.isWritableOrCreatable(extFiles)) {
                extFiles
            } else {
                internalFiles
            }
            val target = if (subDir.isNotEmpty()) File(base, subDir) else base
            if (!target.exists()) target.mkdirs()
            return target
        }

        val internalDir = File(System.getProperty("java.io.tmpdir"), "internal_files_${System.currentTimeMillis()}")
        internalDir.mkdirs()
        try {
            // Case 1: Both external null -> internal fallback
            val res1 = resolveFallbackDir(null, null, internalDir)
            assertEquals(File(internalDir, "SourZap").absolutePath, res1.absolutePath)
            assertTrue(res1.exists())

            // Case 2: External downloads available
            val extDownloads = File(System.getProperty("java.io.tmpdir"), "ext_downloads_${System.currentTimeMillis()}")
            extDownloads.mkdirs()
            val res2 = resolveFallbackDir(extDownloads, null, internalDir)
            assertEquals(File(extDownloads, "SourZap").absolutePath, res2.absolutePath)
            extDownloads.deleteRecursively()
        } finally {
            internalDir.deleteRecursively()
        }
    }

    // =========================================================================
    // FEATURE F1: Magnet Parsing, Normalization & Serialization
    // =========================================================================

    @Test
    fun testMagnetHandler_ParseStandard40HexHash() {
        val hexHash = "4a2f8b9c1d3e5f7a9b1c3d5e7f9a1b3c5d7e9f1a"
        val magnetUri = "magnet:?xt=urn:btih:$hexHash&dn=Arch+Linux+2026&xl=1073741824"

        val parsed = MagnetHandler.parse(magnetUri)
        assertNotNull(parsed)
        assertEquals(hexHash, parsed!!.infoHash)
        assertEquals("Arch Linux 2026", parsed.displayName)
        assertEquals(1073741824L, parsed.fileLength)
    }

    @Test
    fun testMagnetHandler_Parse32Base32HashAndNormalizeToHex() {
        val base32Hash = "V52QNY6T3OQ2KGY4NZTTEZZF6A======".replace("=", "") // 32 chars
        // 32-char Base32 hash representation
        val b32 = "JBSWY3DPEBLW64TMMQQQ====".replace("=", "").padEnd(32, 'A')
        val magnetUri = "magnet:?xt=urn:btih:$b32&dn=Base32+Dataset"

        val parsed = MagnetHandler.parse(magnetUri)
        assertNotNull(parsed)
        assertEquals(40, parsed!!.infoHash.length)
        assertTrue("InfoHash must be lowercase hex", parsed.infoHash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testMagnetHandler_ExtractMultipleTrackersAndWebSeeds() {
        val magnetUri = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709" +
                "&tr=https%3A%2F%2Ftracker1.org%3A443%2Fannounce" +
                "&tr=https%3A%2F%2Ftracker2.org%3A443%2Fannounce" +
                "&ws=https%3A%2F%2Fseed1.org%2Fdata.iso" +
                "&ws=https%3A%2F%2Fseed2.org%2Fdata.iso" +
                "&kt=iso+linux"

        val parsed = MagnetHandler.parse(magnetUri)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.trackers.size)
        assertEquals("https://tracker1.org:443/announce", parsed.trackers[0])
        assertEquals("https://tracker2.org:443/announce", parsed.trackers[1])
        assertEquals(2, parsed.webSeeds.size)
        assertEquals("https://seed1.org/data.iso", parsed.webSeeds[0])
        assertEquals("https://seed2.org/data.iso", parsed.webSeeds[1])
        assertEquals("iso linux", parsed.keywords)
    }

    @Test
    fun testMagnetHandler_RoundtripSerializationIntegrity() {
        val original = MagnetInfo(
            infoHash = "abcdef0123456789abcdef0123456789abcdef01",
            displayName = "Debian Live 12.5.0 [KDE]",
            fileLength = 3221225472L,
            trackers = listOf("https://tracker.tamersunion.org:443/announce"),
            webSeeds = listOf("https://cdimage.debian.org/debian-cd/current-live/amd64/iso-hybrid/debian-live.iso"),
            keywords = "debian linux kde"
        )

        val uri = original.toUri()
        val reparsed = MagnetHandler.parse(uri)
        assertNotNull(reparsed)
        assertEquals(original.infoHash, reparsed!!.infoHash)
        assertEquals(original.displayName, reparsed.displayName)
        assertEquals(original.fileLength, reparsed.fileLength)
        assertEquals(original.trackers, reparsed.trackers)
        assertEquals(original.webSeeds, reparsed.webSeeds)
        assertEquals(original.keywords, reparsed.keywords)
    }

    // =========================================================================
    // FEATURE F1 / R3: Tracker Injector Subsystem
    // =========================================================================

    @Test
    fun testTrackerInjector_InjectPort443TrackersIntoMagnet() {
        val initialMagnet = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Test"
        val augmented = TrackerInjector.injectTrackers(initialMagnet)

        assertTrue(augmented.startsWith("magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709"))
        assertTrue("Must contain injected port-443 tracker", augmented.contains("tracker.tamersunion.org"))
        assertTrue("Must contain injected loligirl tracker", augmented.contains("tracker.loligirl.cn"))

        val parsed = MagnetHandler.parse(augmented)
        assertNotNull(parsed)
        assertTrue("Injected trackers count must be >= 20", parsed!!.trackers.size >= 20)
    }

    @Test
    fun testTrackerInjector_DeduplicationOfExistingTrackers() {
        val tracker1 = "https://tracker.tamersunion.org:443/announce"
        val initialMagnet = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&tr=" +
                URLEncoder.encode(tracker1, StandardCharsets.UTF_8.name())

        val augmented = TrackerInjector.injectTrackers(initialMagnet)
        val parsed = MagnetHandler.parse(augmented)
        assertNotNull(parsed)

        val occurrences = parsed!!.trackers.count { it.equals(tracker1, ignoreCase = true) }
        assertEquals("Duplicate tracker must not be added multiple times", 1, occurrences)
    }

    // =========================================================================
    // FEATURE F1 / R3: DoH Tracker Hostname Pre-Resolution
    // =========================================================================

    @Test
    fun testDohTrackerResolver_ExtractHostVariations() {
        assertEquals("tracker.tamersunion.org", DohTrackerResolver.extractHost("https://tracker.tamersunion.org:443/announce"))
        assertEquals("tracker.loligirl.cn", DohTrackerResolver.extractHost("http://tracker.loligirl.cn:8080/announce"))
        assertEquals("open.tracker.ink", DohTrackerResolver.extractHost("udp://open.tracker.ink:6969/announce"))
        assertEquals("wss.tracker.com", DohTrackerResolver.extractHost("wss://wss.tracker.com/announce"))
        assertEquals("2001:db8::1", DohTrackerResolver.extractHost("https://[2001:db8::1]:443/announce"))
        assertNull(DohTrackerResolver.extractHost(null))
        assertNull(DohTrackerResolver.extractHost(""))
    }

    @Test
    fun testDohTrackerResolver_IsIpLiteral() {
        assertTrue(DohTrackerResolver.isIpLiteral("1.1.1.1"))
        assertTrue(DohTrackerResolver.isIpLiteral("192.168.1.100"))
        assertTrue(DohTrackerResolver.isIpLiteral("2001:db8::1"))
        assertTrue(DohTrackerResolver.isIpLiteral("::1"))
        assertFalse(DohTrackerResolver.isIpLiteral("tracker.tamersunion.org"))
        assertFalse(DohTrackerResolver.isIpLiteral("google.com"))
    }
}
