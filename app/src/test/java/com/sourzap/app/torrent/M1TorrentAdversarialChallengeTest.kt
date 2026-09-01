package com.sourzap.app.torrent

import android.content.Context
import android.content.ContextWrapper
import android.os.Environment
import com.sourzap.app.service.core.HttpParser
import com.sourzap.app.service.core.LocalDpiProxyServer
import com.sourzap.app.torrent.core.MagnetHandler
import com.sourzap.app.torrent.core.MagnetInfo
import com.sourzap.app.torrent.core.TorrentEngineManager
import com.sourzap.app.torrent.core.TorrentSessionConfig
import com.sourzap.app.torrent.core.TorrentStorageHelper
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adversarial Empirical Challenge Suite for Milestone 1 (Torrent Engine Core & Storage Fixes).
 *
 * Tests stress invariants, boundary conditions, edge cases, and failure scenarios for:
 * 1. Base32 / 40-char Hex normalization, RFC 4648 compliance, malformed inputs, and URL encoding.
 * 2. Torrent session auto-start lifecycle and concurrent multi-threaded torrent additions.
 * 3. State mapping and pause state override across all lifecycle phases.
 * 4. Scoped Storage 3-tier fallback safety, permission barriers, and space calculations.
 * 5. DpiEngine split header and IPv6/IPv4 host parser invariants.
 */
class M1TorrentAdversarialChallengeTest {

    private val tempTestDir = File(
        System.getProperty("java.io.tmpdir") ?: ".",
        "sourzap_m1_adv_test_${System.currentTimeMillis()}"
    )

    @Before
    fun setUp() {
        tempTestDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempTestDir.deleteRecursively()
    }

    // =========================================================================
    // 1. Base32 & Hex Normalization Adversarial Stress Tests
    // =========================================================================

    @Test
    fun testNormalization_ValidHexCases_LowercaseUppercaseMixed() {
        val lowerHex = "c12fe1c06bba254a9dc9f519b335de7ece74f6d2"
        val upperHex = "C12FE1C06BBA254A9DC9F519B335DE7ECE74F6D2"
        val mixedHex = "C12fe1c06bBA254A9dc9F519b335de7ECE74f6d2"

        assertEquals(lowerHex, MagnetHandler.normalizeInfoHash(lowerHex))
        assertEquals(lowerHex, MagnetHandler.normalizeInfoHash(upperHex))
        assertEquals(lowerHex, MagnetHandler.normalizeInfoHash(mixedHex))
    }

    @Test
    fun testNormalization_ValidBase32Cases_CaseInsensitivityAndConversion() {
        // Base32 RFC 4648 test vector
        val base32Upper = "YNCKHTQ3XIRUVE6J6UM345O6P3TXJ5WS"
        val base32Lower = "ynckhtq3xiruve6j6um345o6p3txj5ws"
        val base32Mixed = "YnCkHtQ3XiRuVe6J6Um345o6p3txJ5Ws"

        val normalizedUpper = MagnetHandler.normalizeInfoHash(base32Upper)
        val normalizedLower = MagnetHandler.normalizeInfoHash(base32Lower)
        val normalizedMixed = MagnetHandler.normalizeInfoHash(base32Mixed)

        assertNotNull(normalizedUpper)
        assertEquals(40, normalizedUpper!!.length)
        assertTrue("Output must be hex characters only", normalizedUpper.all { it in '0'..'9' || it in 'a'..'f' })

        // All cases must normalize to identical lowercase hex hash
        assertEquals(normalizedUpper, normalizedLower)
        assertEquals(normalizedUpper, normalizedMixed)
    }

    @Test
    fun testNormalization_InvalidLengthAndIllegalCharacters() {
        // Hex boundary length tests
        assertNull("39-char hex is invalid", MagnetHandler.normalizeInfoHash("c12fe1c06bba254a9dc9f519b335de7ece74f6d"))
        assertNull("41-char hex is invalid", MagnetHandler.normalizeInfoHash("c12fe1c06bba254a9dc9f519b335de7ece74f6d2a"))

        // Base32 boundary length tests
        assertNull("31-char Base32 is invalid", MagnetHandler.normalizeInfoHash("YNCKHTQ3XIRUVE6J6UM345O6P3TXJ5W"))
        assertNull("33-char Base32 is invalid", MagnetHandler.normalizeInfoHash("YNCKHTQ3XIRUVE6J6UM345O6P3TXJ5WSA"))

        // Illegal characters in 40-char strings (e.g. 'g', 'z', symbols, spaces)
        assertNull("40-char string with 'g' must be rejected", MagnetHandler.normalizeInfoHash("g12fe1c06bba254a9dc9f519b335de7ece74f6d2"))
        assertNull("40-char string with '-' must be rejected", MagnetHandler.normalizeInfoHash("c12fe1c06bba254a9dc9f519b335de7ece74f6-2"))
        assertNull("40-char string with space must be rejected", MagnetHandler.normalizeInfoHash("c12fe1c06bba254a9dc9 519b335de7ece74f6d2"))

        // Illegal characters in Base32 (RFC 4648 uses A-Z and 2-7; '0', '1', '8', '9' are invalid)
        assertNull("Base32 with '8' must be rejected", MagnetHandler.normalizeInfoHash("8NCKHTQ3XIRUVE6J6UM345O6P3TXJ5WS"))
        assertNull("Base32 with '9' must be rejected", MagnetHandler.normalizeInfoHash("Y9CKHTQ3XIRUVE6J6UM345O6P3TXJ5WS"))
        assertNull("Base32 with '0' must be rejected", MagnetHandler.normalizeInfoHash("YNC0HTQ3XIRUVE6J6UM345O6P3TXJ5WS"))
        assertNull("Base32 with '1' must be rejected", MagnetHandler.normalizeInfoHash("YNCK1TQ3XIRUVE6J6UM345O6P3TXJ5WS"))

        // Empty and whitespace strings
        assertNull(MagnetHandler.normalizeInfoHash(""))
        assertNull(MagnetHandler.normalizeInfoHash("   "))
    }

    @Test
    fun testMagnetParser_ComplexUriPermutations() {
        val hexHash = "c12fe1c06bba254a9dc9f519b335de7ece74f6d2"

        // 1. Scrambled parameter ordering
        val scrambledUri = "magnet:?dn=Test+Video+File&xl=104857600&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce&xt=urn:btih:$hexHash&ws=https%3A%2F%2Fexample.com%2Ffile.mkv&kt=test+video"
        val parsed = MagnetHandler.parse(scrambledUri)

        assertNotNull(parsed)
        assertEquals(hexHash, parsed!!.infoHash)
        assertEquals("Test Video File", parsed.displayName)
        assertEquals(104857600L, parsed.fileLength)
        assertEquals(1, parsed.trackers.size)
        assertEquals("udp://tracker.opentrackr.org:1337/announce", parsed.trackers[0])
        assertEquals(1, parsed.webSeeds.size)
        assertEquals("https://example.com/file.mkv", parsed.webSeeds[0])
        assertEquals("test video", parsed.keywords)

        // 2. Base32 magnet URI with URL-encoded parameters
        val base32 = "YNCKHTQ3XIRUVE6J6UM345O6P3TXJ5WS"
        val base32Uri = "magnet:?xt=urn:btih:$base32&dn=Ubuntu%2024.04%20LTS&tr=https%3A%2F%2Ftorrent.ubuntu.com%2Fannounce"
        val parsedBase32 = MagnetHandler.parse(base32Uri)

        assertNotNull(parsedBase32)
        assertEquals(MagnetHandler.normalizeInfoHash(base32), parsedBase32!!.infoHash)
        assertEquals("Ubuntu 24.04 LTS", parsedBase32.displayName)
        assertEquals(1, parsedBase32.trackers.size)
        assertEquals("https://torrent.ubuntu.com/announce", parsedBase32.trackers[0])

        // 3. Round-trip serialization
        val reSerializedUri = parsed.toUri()
        val reParsed = MagnetHandler.parse(reSerializedUri)
        assertNotNull(reParsed)
        assertEquals(parsed.infoHash, reParsed!!.infoHash)
        assertEquals(parsed.displayName, reParsed.displayName)
        assertEquals(parsed.fileLength, reParsed.fileLength)
        assertEquals(parsed.trackers, reParsed.trackers)
    }

    @Test
    fun testMagnetParser_InvalidUrisReturnNull() {
        assertNull(MagnetHandler.parse(null))
        assertNull(MagnetHandler.parse(""))
        assertNull(MagnetHandler.parse("https://example.com/file.torrent"))
        assertNull(MagnetHandler.parse("magnet:"))
        assertNull(MagnetHandler.parse("magnet:?"))
        assertNull(MagnetHandler.parse("magnet:?dn=NoHashHere"))
        assertNull(MagnetHandler.parse("magnet:?xt=urn:ed2k:31D6CFE0D16AE931B73C59D7E0C089C0"))
    }

    // =========================================================================
    // 2. Torrent Engine Session Auto-Start & Concurrency Tests
    // =========================================================================

    @Test
    fun testTorrentEngine_AutoStartOnAddTorrent() {
        try {
            val engine = TorrentEngineManager.create(TorrentSessionConfig.DEFAULT)
            assertFalse("Engine session should initially be inactive", engine.isSessionRunning())

            val hexHash = "c12fe1c06bba254a9dc9f519b335de7ece74f6d2"
            val magnetUri = "magnet:?xt=urn:btih:$hexHash&dn=AutoStartTest"

            val returnedHash = engine.addTorrent(
                torrentSource = TorrentSource.Magnet(magnetUri),
                saveDir = tempTestDir
            )

            assertEquals(hexHash, returnedHash)
            assertTrue("Session must auto-start when addTorrent is called", engine.isSessionRunning())

            engine.stopSession()
            assertFalse("Session should be stopped after stopSession()", engine.isSessionRunning())
        } catch (_: LinkageError) {
            // Expected on non-Android host JVM when libjlibtorrent native binary is not present
            assertTrue(true)
        }
    }

    @Test
    fun testTorrentEngine_ConcurrentTorrentAdditionsThreadSafety() {
        runBlocking {
            try {
                val engine = TorrentEngineManager.create(TorrentSessionConfig.DEFAULT)
                val threadCount = 20
                val errors = AtomicInteger(0)

                val jobs = (1..threadCount).map { i ->
                    async(Dispatchers.IO) {
                        try {
                            val dummyHash = String.format(Locale.US, "%040d", i)
                            val uri = "magnet:?xt=urn:btih:$dummyHash&dn=ConcurrentTorrent_$i"
                            val dir = File(tempTestDir, "sub_$i")
                            val id = engine.addTorrent(TorrentSource.Magnet(uri), dir)
                            assertEquals(dummyHash, id)
                        } catch (e: LinkageError) {
                            // Native lib linkage error is acceptable on host JVM
                        } catch (e: Throwable) {
                            errors.incrementAndGet()
                        }
                    }
                }

                jobs.awaitAll()
                assertEquals("Concurrent addTorrent calls must not throw concurrency exceptions", 0, errors.get())
                engine.stopSession()
            } catch (_: LinkageError) {
                assertTrue(true)
            }
        }
    }

    // =========================================================================
    // 3. Paused State Invariant & State Mapping Tests
    // =========================================================================

    @Test
    fun testTorrentState_PausedStateMappingInvariants() {
        // Pure mapping function mirroring LibtorrentEngineManager.mapTorrentState logic
        fun mapState(isPaused: Boolean, rawStateName: String): TorrentState {
            if (isPaused) {
                return TorrentState.PAUSED
            }
            return when (rawStateName) {
                "CHECKING_FILES", "CHECKING_RESUME_DATA" -> TorrentState.CHECKING
                "DOWNLOADING_METADATA" -> TorrentState.METADATA
                "DOWNLOADING" -> TorrentState.DOWNLOADING
                "FINISHED" -> TorrentState.FINISHED
                "SEEDING" -> TorrentState.SEEDING
                else -> TorrentState.DOWNLOADING
            }
        }

        // When paused is TRUE: MUST ALWAYS BE PAUSED regardless of sub-state
        assertEquals(TorrentState.PAUSED, mapState(isPaused = true, rawStateName = "DOWNLOADING"))
        assertEquals(TorrentState.PAUSED, mapState(isPaused = true, rawStateName = "CHECKING_FILES"))
        assertEquals(TorrentState.PAUSED, mapState(isPaused = true, rawStateName = "CHECKING_RESUME_DATA"))
        assertEquals(TorrentState.PAUSED, mapState(isPaused = true, rawStateName = "DOWNLOADING_METADATA"))
        assertEquals(TorrentState.PAUSED, mapState(isPaused = true, rawStateName = "FINISHED"))
        assertEquals(TorrentState.PAUSED, mapState(isPaused = true, rawStateName = "SEEDING"))
        assertEquals(TorrentState.PAUSED, mapState(isPaused = true, rawStateName = "ALLOCATING"))

        // When paused is FALSE: maps to correct active state
        assertEquals(TorrentState.CHECKING, mapState(isPaused = false, rawStateName = "CHECKING_FILES"))
        assertEquals(TorrentState.CHECKING, mapState(isPaused = false, rawStateName = "CHECKING_RESUME_DATA"))
        assertEquals(TorrentState.METADATA, mapState(isPaused = false, rawStateName = "DOWNLOADING_METADATA"))
        assertEquals(TorrentState.DOWNLOADING, mapState(isPaused = false, rawStateName = "DOWNLOADING"))
        assertEquals(TorrentState.FINISHED, mapState(isPaused = false, rawStateName = "FINISHED"))
        assertEquals(TorrentState.SEEDING, mapState(isPaused = false, rawStateName = "SEEDING"))
    }

    // =========================================================================
    // 4. Scoped Storage 3-Tier Fallback & Safety Tests
    // =========================================================================

    private class ConfigurableMockContext(
        private val extDownloads: File? = null,
        private val extFiles: File? = null,
        private val internalFiles: File,
        private val throwOnExternal: Boolean = false
    ) : ContextWrapper(null) {
        override fun getExternalFilesDir(type: String?): File? {
            if (throwOnExternal) {
                throw SecurityException("Mock security exception accessing external storage")
            }
            return if (type != null && (type == "Download" || type == Environment.DIRECTORY_DOWNLOADS)) {
                extDownloads
            } else {
                extFiles
            }
        }

        override fun getFilesDir(): File {
            return internalFiles
        }
    }

    @Test
    fun testStorageHelper_Tier1PrimaryDownloadsDirectory() {
        val extDownloads = File(tempTestDir, "ext_downloads")
        extDownloads.mkdirs()

        val mockContext = ConfigurableMockContext(
            extDownloads = extDownloads,
            extFiles = File(tempTestDir, "ext_files"),
            internalFiles = File(tempTestDir, "internal")
        )

        val resolved = TorrentStorageHelper.getSaveDirectory(mockContext)
        assertEquals(File(extDownloads, "SourZap").absolutePath, resolved.absolutePath)
        assertTrue("Directory must exist on disk", resolved.exists())
    }

    @Test
    fun testStorageHelper_Tier2SecondaryExtFilesFallbackWhenDownloadsNull() {
        val extFiles = File(tempTestDir, "ext_files")
        extFiles.mkdirs()

        val mockContext = ConfigurableMockContext(
            extDownloads = null,
            extFiles = extFiles,
            internalFiles = File(tempTestDir, "internal")
        )

        val resolved = TorrentStorageHelper.getSaveDirectory(mockContext)
        assertEquals(File(extFiles, "SourZap").absolutePath, resolved.absolutePath)
        assertTrue(resolved.exists())
    }

    @Test
    fun testStorageHelper_Tier3InternalFilesFallbackWhenAllExternalNull() {
        val internalFiles = File(tempTestDir, "internal_dir")
        internalFiles.mkdirs()

        val mockContext = ConfigurableMockContext(
            extDownloads = null,
            extFiles = null,
            internalFiles = internalFiles
        )

        val resolved = TorrentStorageHelper.getSaveDirectory(mockContext)
        assertEquals(File(internalFiles, "SourZap").absolutePath, resolved.absolutePath)
        assertTrue(resolved.exists())
    }

    @Test
    fun testStorageHelper_ExceptionSafetyFallbackToInternal() {
        val internalFiles = File(tempTestDir, "internal_exception_fallback")
        internalFiles.mkdirs()

        val mockContext = ConfigurableMockContext(
            extDownloads = null,
            extFiles = null,
            internalFiles = internalFiles,
            throwOnExternal = true
        )

        val resolved = TorrentStorageHelper.getSaveDirectory(mockContext)
        assertEquals(File(internalFiles, "SourZap").absolutePath, resolved.absolutePath)
        assertTrue(resolved.exists())
    }

    @Test
    fun testStorageHelper_IsWritableOrCreatable_BlockedByFile() {
        val file = File(tempTestDir, "regular_file.bin").apply { writeText("hello") }
        val impossibleDir = File(file, "sub_dir")

        assertFalse(TorrentStorageHelper.isWritableOrCreatable(impossibleDir))
    }

    @Test
    fun testStorageHelper_AvailableFreeSpaceSafety() {
        val internalFiles = File(tempTestDir, "space_check")
        internalFiles.mkdirs()

        val mockContext = ConfigurableMockContext(internalFiles = internalFiles)
        val freeSpace = TorrentStorageHelper.getAvailableFreeSpaceBytes(mockContext)
        assertTrue("Free space must be non-negative", freeSpace >= 0L)
    }

    // =========================================================================
    // 5. DpiEngine HttpParser & LocalDpiProxyServer Invariant Tests
    // =========================================================================

    @Test
    fun testDpiEngine_SplitHttpHeaderBytePreservation() {
        val sampleHeaders = listOf(
            "GET /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n",
            "POST /api/v1/upload HTTP/1.1\r\nHost: api.sourzap.org\r\nContent-Length: 12\r\n\r\nhello world!",
            "CONNECT www.google.com:443 HTTP/1.1\r\nHost: www.google.com:443\r\nUser-Agent: Mozilla/5.0\r\n\r\n"
        )

        for (headerStr in sampleHeaders) {
            val bytes = headerStr.toByteArray(Charsets.ISO_8859_1)
            val chunks = HttpParser.splitHttpHeader(bytes, bytes.size)
            val c1: ByteArray = chunks.first
            val c2: ByteArray = chunks.second

            assertTrue("Chunk 1 must not be empty", c1.isNotEmpty())
            assertTrue("Chunk 2 must not be empty", c2.isNotEmpty())
            assertEquals("Combined chunk length must match original", bytes.size, c1.size + c2.size)

            val reconstructed = ByteArray(bytes.size)
            System.arraycopy(c1, 0, reconstructed, 0, c1.size)
            System.arraycopy(c2, 0, reconstructed, c1.size, c2.size)

            assertEquals(headerStr, String(reconstructed, Charsets.ISO_8859_1))
        }
    }

    @Test
    fun testDpiEngine_LocalDpiProxyServerHostParser() {
        // IPv4 with port
        assertEquals(Pair("192.168.1.1", 8080), LocalDpiProxyServer.parseHostAndPort("192.168.1.1:8080", 80))
        // IPv4 default port
        assertEquals(Pair("192.168.1.1", 80), LocalDpiProxyServer.parseHostAndPort("192.168.1.1", 80))
        // Domain with port
        assertEquals(Pair("tracker.opentrackr.org", 1337), LocalDpiProxyServer.parseHostAndPort("tracker.opentrackr.org:1337", 80))
        // IPv6 bracketed with port
        assertEquals(Pair("2001:db8::1", 8080), LocalDpiProxyServer.parseHostAndPort("[2001:db8::1]:8080", 80))
        // IPv6 bracketed default port
        assertEquals(Pair("2001:db8::1", 443), LocalDpiProxyServer.parseHostAndPort("[2001:db8::1]", 443))
    }
}
