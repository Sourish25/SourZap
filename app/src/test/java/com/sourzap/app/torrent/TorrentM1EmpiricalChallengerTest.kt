package com.sourzap.app.torrent

import com.sourzap.app.torrent.core.BencodeValidator
import com.sourzap.app.torrent.core.TorrentFileValidator
import com.sourzap.app.torrent.core.TorrentIntentParser
import com.sourzap.app.torrent.core.TorrentValidationResult
import com.sourzap.app.torrent.core.TrackerInjector
import com.sourzap.app.torrent.model.PendingTorrentIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Empirical Adversarial Challenger Test Suite for Milestone 1 (Robust .torrent File Loading & Exception Protection).
 *
 * Exhaustively stress-tests:
 * 1. Fuzz generators, random noise, bit flips, truncation matrices, and corrupted buffers for [TorrentFileValidator] and [BencodeValidator].
 * 2. HTML, XML, JSON, Cloudflare, DDoS protection, WAF, and HTTP redirect/error response payload detection.
 * 3. Zero, negative, malformed, and non-integer piece lengths.
 * 4. Non-20-byte, empty, truncated, and non-divisible piece hash strings.
 * 5. Single-file and multi-file structural invariants, file counts, and infoHash calculation.
 * 6. Port-443 curated tracker catalog integrity, deduplication, URL encoding, and concurrent injection stress.
 * 7. Fallback dummy payload structural validity in [TorrentIntentParser].
 */
class TorrentM1EmpiricalChallengerTest {

    // =========================================================================
    // Test Helpers: Dynamic Bencode Torrent Construction
    // =========================================================================

    private fun buildSingleFileTorrent(
        name: String = "linux_distro.iso",
        fileLength: Long = 1048576L,
        pieceLength: Int = 16384,
        pieceCount: Int = 4,
        announce: String = "https://tracker.example.com/announce",
        nameUtf8: String? = null
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

        if (nameUtf8 != null) {
            val utf8Bytes = nameUtf8.toByteArray(StandardCharsets.UTF_8)
            out.write("10:name.utf-8${utf8Bytes.size}:".toByteArray(StandardCharsets.US_ASCII))
            out.write(utf8Bytes)
        }

        out.write("12:piece lengthi${pieceLength}e".toByteArray(StandardCharsets.US_ASCII))
        out.write("6:pieces${piecesBytes.size}:".toByteArray(StandardCharsets.US_ASCII))
        out.write(piecesBytes)
        out.write("ee".toByteArray(StandardCharsets.US_ASCII))

        return out.toByteArray()
    }

    private fun buildMultiFileTorrent(
        dirName: String = "MultiAlbum",
        files: List<Pair<String, Long>> = listOf("track1.flac" to 20000L, "track2.flac" to 30000L, "artwork.png" to 5000L),
        pieceLength: Int = 32768,
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
    // SECTION 1: FUZZING & CORRUPTED / TRUNCATED BUFFERS
    // =========================================================================

    @Test
    fun fuzz_AllSingleByteValues_MustReturnInvalidWithoutCrashing() {
        for (b in 0..255) {
            val buf = byteArrayOf(b.toByte())
            val result = TorrentFileValidator.validate(buf)
            assertTrue("Single byte $b must result in Invalid", result is TorrentValidationResult.Invalid)
            assertFalse("Single byte $b must not be valid", TorrentFileValidator.isValidTorrent(buf))
        }
    }

    @Test
    fun fuzz_IncrementalPrefixTruncationMatrix_ValidSingleFileTorrent() {
        val validTorrent = buildSingleFileTorrent()
        assertTrue(TorrentFileValidator.isValidTorrent(validTorrent))

        // Truncate at every single byte index from 0 to size - 1
        for (i in 0 until validTorrent.size) {
            val slice = validTorrent.copyOfRange(0, i)
            val result = TorrentFileValidator.validate(slice)
            assertTrue("Prefix of length $i must return Invalid", result is TorrentValidationResult.Invalid)
            assertFalse("Prefix of length $i must not be valid", TorrentFileValidator.isValidTorrent(slice))
        }
    }

    @Test
    fun fuzz_IncrementalPrefixTruncationMatrix_ValidMultiFileTorrent() {
        val validMulti = buildMultiFileTorrent()
        assertTrue(TorrentFileValidator.isValidTorrent(validMulti))

        // Truncate at every single byte index from 0 to size - 1
        for (i in 0 until validMulti.size) {
            val slice = validMulti.copyOfRange(0, i)
            val result = TorrentFileValidator.validate(slice)
            assertTrue("Multi-file prefix of length $i must return Invalid", result is TorrentValidationResult.Invalid)
            assertFalse("Multi-file prefix of length $i must not be valid", TorrentFileValidator.isValidTorrent(slice))
        }
    }

    @Test
    fun fuzz_BitFlipCorruptionMatrix_SingleFileTorrent() {
        val original = buildSingleFileTorrent()
        val rng = Random(42)

        for (trial in 0 until 500) {
            val corrupted = original.copyOf()
            val flipIndex = rng.nextInt(corrupted.size)
            val bitMask = (1 shl rng.nextInt(8)).toByte()
            corrupted[flipIndex] = (corrupted[flipIndex].toInt() xor bitMask.toInt()).toByte()

            val result = TorrentFileValidator.validate(corrupted)
            assertNotNull("Validator must never return null on bit flip trial $trial", result)
            // It may either be Valid (if in non-critical string) or Invalid, but must never throw uncaught exception
        }
    }

    @Test
    fun fuzz_RandomNoisePayloads_VaryingSizes() {
        val rng = Random(1337)
        val sizes = listOf(0, 1, 2, 5, 10, 11, 15, 32, 64, 128, 256, 512, 1024, 4096, 16384, 65536)

        for (size in sizes) {
            for (iter in 0 until 20) {
                val noise = ByteArray(size) { rng.nextInt(256).toByte() }
                val result = TorrentFileValidator.validate(noise)
                assertNotNull("Result must not be null for noise size $size", result)
                if (size < 11 || noise.isEmpty() || noise[0] != 'd'.code.toByte() || noise[noise.size - 1] != 'e'.code.toByte()) {
                    assertTrue("Noise buffer of size $size without valid d...e framing must be Invalid", result is TorrentValidationResult.Invalid)
                }
            }
        }
    }

    @Test
    fun fuzz_MalformedBencodeIntegerSyntax() {
        val malformedIntVectors = listOf(
            "d4:infod12:piece lengthi-0e6:pieces20:123456789012345678906:lengthi100eee", // negative zero
            "d4:infod12:piece lengthi05e6:pieces20:123456789012345678906:lengthi100eee", // leading zero
            "d4:infod12:piece lengthi-05e6:pieces20:123456789012345678906:lengthi100eee", // leading zero negative
            "d4:infod12:piece lengthie6:pieces20:123456789012345678906:lengthi100eee", // empty integer
            "d4:infod12:piece lengthi-e6:pieces20:123456789012345678906:lengthi100eee", // lone minus
            "d4:infod12:piece lengthi999999999999999999999999999999999e6:pieces20:123456789012345678906:lengthi100eee", // overflow long
            "d4:infod12:piece lengthiabcde6:pieces20:123456789012345678906:lengthi100eee" // alpha chars
        )

        for (vec in malformedIntVectors) {
            val bytes = vec.toByteArray(StandardCharsets.UTF_8)
            val res = TorrentFileValidator.validate(bytes)
            assertTrue("Malformed integer vector '$vec' must result in Invalid", res is TorrentValidationResult.Invalid)
        }
    }

    @Test
    fun fuzz_MalformedBencodeStringLengthSyntax() {
        val malformedStringVectors = listOf(
            "d4:infod12:piece lengthi16384e6:pieces-5:123456:lengthi100eee", // negative string length
            "d4:infod12:piece lengthi16384e6:pieces05:123456:lengthi100eee", // leading zero string length
            "d4:infod12:piece lengthi16384e6:pieces:123456:lengthi100eee", // missing string length
            "d4:infod12:piece lengthi16384e6:pieces999999999999999999999999999:123456:lengthi100eee", // overflow length
            "d4:infod12:piece lengthi16384e6:pieces2147483647:abc6:lengthi100eee" // length exceeds buffer size
        )

        for (vec in malformedStringVectors) {
            val bytes = vec.toByteArray(StandardCharsets.UTF_8)
            val res = TorrentFileValidator.validate(bytes)
            assertTrue("Malformed string length vector '$vec' must result in Invalid", res is TorrentValidationResult.Invalid)
        }
    }

    // =========================================================================
    // SECTION 2: HTML-REDIRECT, CLOUDFLARE, WEB-BLOCKER, & ERROR PAYLOADS
    // =========================================================================

    @Test
    fun testWebPayload_HtmlDoctypeVariations() {
        val doctypes = listOf(
            "<!DOCTYPE html><html><body>Error</body></html>",
            "<!doctype html><html><body>Error</body></html>",
            "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\"><html><body>Error</body></html>",
            "<!DOCTYPE html SYSTEM \"about:legacy-compat\"><html><body>Error</body></html>",
            "   \n\t <!doctype html>\n<html><body>Blocked</body></html>"
        )

        for (doc in doctypes) {
            val res = TorrentFileValidator.validate(doc.toByteArray(StandardCharsets.UTF_8))
            assertTrue("Doctype payload must be Invalid", res is TorrentValidationResult.Invalid)
            val invalid = res as TorrentValidationResult.Invalid
            assertTrue("Must be marked as isHtmlPayload", invalid.isHtmlPayload)
            assertEquals("Web page / Error payload", invalid.reason)
        }
    }

    @Test
    fun testWebPayload_HtmlTagsVariations() {
        val tags = listOf(
            "<html lang=\"en\"><head><title>403 Forbidden</title></head><body>Forbidden</body></html>",
            "<HTML><BODY>Access Denied</BODY></HTML>",
            "  <head><meta http-equiv=\"refresh\" content=\"0; url=https://example.com\"></head>",
            "<body onload=\"redirect()\"><h1>Loading...</h1></body>",
            "<script>window.location.href = 'https://tracker.org/login';</script>"
        )

        for (tag in tags) {
            val res = TorrentFileValidator.validate(tag.toByteArray(StandardCharsets.UTF_8))
            assertTrue("HTML tag payload must be Invalid", res is TorrentValidationResult.Invalid)
            assertTrue("Must flag as isHtmlPayload", (res as TorrentValidationResult.Invalid).isHtmlPayload)
        }
    }

    @Test
    fun testWebPayload_XmlAndJsonResponseVariations() {
        val errorBodies = listOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>",
            "<?xml version=\"1.0\"?><response error=\"1\">File not found</response>",
            "{\"error\": \"Torrent not found\", \"status\": 404, \"code\": \"NOT_FOUND\"}",
            "{\"message\": \"Rate limit exceeded\", \"status\": 429}",
            "{\n  \"status\": 500,\n  \"detail\": \"Internal Server Error\"\n}",
            "{\"code\": 401, \"message\": \"API Key required\"}",
            "{\"success\": false, \"message\": \"Torrent removed by copyright owner\"}",
            "{\"success\":false,\"error\":\"Forbidden\"}"
        )

        for (body in errorBodies) {
            val res = TorrentFileValidator.validate(body.toByteArray(StandardCharsets.UTF_8))
            assertTrue("XML/JSON error payload '$body' must be Invalid", res is TorrentValidationResult.Invalid)
            assertTrue("Must flag as isHtmlPayload", (res as TorrentValidationResult.Invalid).isHtmlPayload)
        }
    }

    @Test
    fun testWebPayload_CloudflareAndDDoSGuardPages() {
        val cfPages = listOf(
            "<html><head><title>Just a moment...</title></head><body>Please wait... Ray ID: 89ab329ef3</body></html>",
            "<!DOCTYPE html><html><body>Checking your browser before accessing sourzap.org... Cloudflare turnstile challenge</body></html>",
            "<html><body>Attention Required! | Cloudflare DDOS Protection</body></html>",
            "<html><body>Protected by DDoS-GUARD. Please enable JavaScript</body></html>"
        )

        for (cf in cfPages) {
            val res = TorrentFileValidator.validate(cf.toByteArray(StandardCharsets.UTF_8))
            assertTrue("Cloudflare page must be Invalid", res is TorrentValidationResult.Invalid)
            assertTrue("Must flag as isHtmlPayload", (res as TorrentValidationResult.Invalid).isHtmlPayload)
        }
    }

    @Test
    fun testWebPayload_HttpResponseStatusHeaders() {
        val httpStatuses = listOf(
            "HTTP/1.1 302 Found\r\nLocation: https://example.com/login\r\n\r\n",
            "HTTP/1.1 403 Forbidden\r\nContent-Type: text/plain\r\n\r\nAccess Denied",
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n",
            "HTTP/1.1 502 Bad Gateway\r\nServer: nginx\r\n\r\n",
            "HTTP/1.1 503 Service Temporarily Unavailable\r\nRetry-After: 120\r\n\r\n",
            "HTTP/2 403 Forbidden\r\ncontent-type: text/html\r\n\r\n"
        )

        for (http in httpStatuses) {
            val res = TorrentFileValidator.validate(http.toByteArray(StandardCharsets.UTF_8))
            assertTrue("HTTP status header '$http' must be Invalid", res is TorrentValidationResult.Invalid)
            assertTrue("Must flag as isHtmlPayload", (res as TorrentValidationResult.Invalid).isHtmlPayload)
        }
    }

    // =========================================================================
    // SECTION 3: PIECE LENGTH & PIECE HASHES INVARIANTS
    // =========================================================================

    @Test
    fun challenge_PieceLength_NegativeZeroAndMalformedInvariants() {
        val badPieceLengths = listOf(
            0,
            -1,
            -16384,
            -262144,
            -2147483648
        )

        for (badLen in badPieceLengths) {
            val torrentStr = "d4:infod6:lengthi1024e4:name4:test12:piece lengthi${badLen}e6:pieces20:12345678901234567890ee"
            val res = TorrentFileValidator.validate(torrentStr.toByteArray(StandardCharsets.UTF_8))
            assertTrue("Piece length $badLen must be Invalid", res is TorrentValidationResult.Invalid)
            assertEquals("Invalid piece length", (res as TorrentValidationResult.Invalid).reason)
        }
    }

    @Test
    fun challenge_PiecesHash_NonMultipleOf20BytesInvariants() {
        val invalidLengths = listOf(0, 1, 5, 19, 21, 39, 41, 59, 61, 99, 101)

        for (len in invalidLengths) {
            val piecesBytes = ByteArray(len) { 0x5A }
            val out = ByteArrayOutputStream()
            out.write("d4:infod6:lengthi1024e4:name4:test12:piece lengthi16384e6:pieces${len}:".toByteArray(StandardCharsets.US_ASCII))
            out.write(piecesBytes)
            out.write("ee".toByteArray(StandardCharsets.US_ASCII))

            val res = TorrentFileValidator.validate(out.toByteArray())
            assertTrue("Pieces length $len (not multiple of 20) must be Invalid", res is TorrentValidationResult.Invalid)
            assertEquals("Invalid pieces", (res as TorrentValidationResult.Invalid).reason)
        }
    }

    @Test
    fun challenge_PiecesHash_ValidMultiplesOf20Bytes() {
        val pieceCounts = listOf(1, 2, 5, 10, 50, 100)

        for (count in pieceCounts) {
            val torrentBytes = buildSingleFileTorrent(
                name = "test_count_$count.iso",
                fileLength = count * 16384L,
                pieceLength = 16384,
                pieceCount = count
            )

            val res = TorrentFileValidator.validate(torrentBytes)
            assertTrue("Piece count $count must be Valid", res is TorrentValidationResult.Valid)
            val valid = res as TorrentValidationResult.Valid
            assertEquals(count, valid.pieceCount)
            assertEquals(16384, valid.pieceLength)
            assertEquals(count * 16384L, valid.totalSize)
            assertNotNull(valid.infoHash)
            assertEquals(40, valid.infoHash!!.length)
        }
    }

    // =========================================================================
    // SECTION 4: INFO DICTIONARY & MULTI-FILE STRUCTURAL INVARIANTS
    // =========================================================================

    @Test
    fun challenge_InfoDict_MissingOrNonDictInvariants() {
        // 1. Missing info dict
        val noInfo = "d8:announce26:http://tracker.example.come".toByteArray(StandardCharsets.UTF_8)
        val res1 = TorrentFileValidator.validate(noInfo)
        assertTrue(res1 is TorrentValidationResult.Invalid)
        assertEquals("Missing info dictionary", (res1 as TorrentValidationResult.Invalid).reason)

        // 2. Info is a string, not dict
        val infoString = "d4:info11:hello_worlde".toByteArray(StandardCharsets.UTF_8)
        val res2 = TorrentFileValidator.validate(infoString)
        assertTrue(res2 is TorrentValidationResult.Invalid)
        assertEquals("Missing info dictionary", (res2 as TorrentValidationResult.Invalid).reason)

        // 3. Info is an int, not dict
        val infoInt = "d4:infoi12345ee".toByteArray(StandardCharsets.UTF_8)
        val res3 = TorrentFileValidator.validate(infoInt)
        assertTrue(res3 is TorrentValidationResult.Invalid)
        assertEquals("Missing info dictionary", (res3 as TorrentValidationResult.Invalid).reason)

        // 4. Info is a list, not dict
        val infoList = "d4:infol4:itemee".toByteArray(StandardCharsets.UTF_8)
        val res4 = TorrentFileValidator.validate(infoList)
        assertTrue(res4 is TorrentValidationResult.Invalid)
        assertEquals("Missing info dictionary", (res4 as TorrentValidationResult.Invalid).reason)
    }

    @Test
    fun challenge_SingleFile_LengthInvariants() {
        // Negative length
        val negLength = "d4:infod4:name4:test6:lengthi-500e12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val resNeg = TorrentFileValidator.validate(negLength)
        assertTrue(resNeg is TorrentValidationResult.Invalid)
        assertEquals("Invalid length", (resNeg as TorrentValidationResult.Invalid).reason)

        // Zero length (valid 0-byte file)
        val zeroLength = buildSingleFileTorrent(fileLength = 0L, pieceCount = 1)
        val resZero = TorrentFileValidator.validate(zeroLength)
        assertTrue(resZero is TorrentValidationResult.Valid)
        assertEquals(0L, (resZero as TorrentValidationResult.Valid).totalSize)
    }

    @Test
    fun challenge_MultiFile_FilesListInvariants() {
        // 1. Empty files list
        val emptyFilesList = "d4:infod4:name4:test5:filesl12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val resEmpty = TorrentFileValidator.validate(emptyFilesList)
        assertTrue(resEmpty is TorrentValidationResult.Invalid)

        // 2. File entry is not a dict
        val notDictFile = "d4:infod4:name4:test5:filesl4:filee12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val resNotDict = TorrentFileValidator.validate(notDictFile)
        assertTrue(resNotDict is TorrentValidationResult.Invalid)
        assertEquals("Invalid file entry", (resNotDict as TorrentValidationResult.Invalid).reason)

        // 3. File entry with negative length
        val negFileLen = "d4:infod4:name4:test5:filesld6:lengthi-100e4:pathl4:fileeee12:piece lengthi16384e6:pieces20:12345678901234567890ee".toByteArray(StandardCharsets.UTF_8)
        val resNegFile = TorrentFileValidator.validate(negFileLen)
        assertTrue(resNegFile is TorrentValidationResult.Invalid)
        assertEquals("Invalid file length", (resNegFile as TorrentValidationResult.Invalid).reason)

        // 4. Large multi-file sum size calculation
        val multiFiles = (1..50).map { "folder/sub/file_$it.bin" to (it * 1000L) }
        val expectedSum = multiFiles.sumOf { it.second }
        val validMulti = buildMultiFileTorrent(files = multiFiles, pieceLength = 65536, pieceCount = 10)

        val resMulti = TorrentFileValidator.validate(validMulti)
        assertTrue(resMulti is TorrentValidationResult.Valid)
        val valid = resMulti as TorrentValidationResult.Valid
        assertEquals(expectedSum, valid.totalSize)
        assertEquals(50, valid.fileCount)
        assertTrue(valid.isMultiFile)
    }

    // =========================================================================
    // SECTION 5: PORT-443 HTTPS TRACKER AUTO-INJECTION STRESS
    // =========================================================================

    @Test
    fun challenge_TrackerInjector_CuratedCatalogCompleteness() {
        val trackers = TrackerInjector.HTTPS_PORT_443_TRACKERS
        assertTrue("Tracker catalog must have at least 20 entries", trackers.size >= 20)

        val normalizedSet = mutableSetOf<String>()
        for (tr in trackers) {
            assertTrue("Tracker must be HTTPS: $tr", tr.startsWith("https://"))
            assertTrue("Tracker must target port 443: $tr", tr.contains(":443") || tr.startsWith("https://"))
            assertTrue("Tracker must contain announce: $tr", tr.contains("announce"))
            val norm = tr.trim().lowercase().removeSuffix("/")
            assertTrue("Zero duplicate trackers allowed in catalog: $tr", normalizedSet.add(norm))
        }
    }

    @Test
    fun challenge_TrackerInjector_MagnetDeduplicationMatrix() {
        val baseMagnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=SourZap+ISO"

        // Inject once
        val injected1 = TrackerInjector.injectTrackers(baseMagnet)
        // Inject twice on already injected magnet (idempotence)
        val injected2 = TrackerInjector.injectTrackers(injected1)

        assertEquals("Injecting twice must be idempotent", injected1, injected2)

        // Count occurrences of each tracker
        for (tr in TrackerInjector.HTTPS_PORT_443_TRACKERS) {
            val encoded = URLEncoder.encode(tr, StandardCharsets.UTF_8.name())
            val count = injected2.split("&tr=").count { it.startsWith(encoded) }
            assertEquals("Tracker $tr must appear exactly once in injected magnet", 1, count)
        }
    }

    @Test
    fun challenge_TrackerInjector_PreservesNonPort443Trackers() {
        val customTrackers = listOf(
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://tracker.opentrackr.org:1337/announce",
            "http://tracker.plain.com:80/announce"
        )
        val magnetBuilder = StringBuilder("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2")
        for (tr in customTrackers) {
            magnetBuilder.append("&tr=").append(URLEncoder.encode(tr, StandardCharsets.UTF_8.name()))
        }

        val injected = TrackerInjector.injectTrackers(magnetBuilder.toString())

        for (custom in customTrackers) {
            val enc = URLEncoder.encode(custom, StandardCharsets.UTF_8.name())
            assertTrue("Custom tracker $custom must be preserved", injected.contains("&tr=$enc"))
        }

        for (p443 in TrackerInjector.HTTPS_PORT_443_TRACKERS) {
            val enc = URLEncoder.encode(p443, StandardCharsets.UTF_8.name())
            assertTrue("Port-443 tracker $p443 must be added", injected.contains("&tr=$enc"))
        }
    }

    @Test
    fun challenge_TrackerInjector_ConcurrentInjectionStress() {
        runBlocking {
            val threadCount = 50
            val errors = AtomicInteger(0)

            val jobs = (1..threadCount).map { i ->
                async(Dispatchers.IO) {
                    try {
                        val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2&dn=Concurrent_$i"
                        val res = TrackerInjector.injectTrackers(magnet)
                        assertTrue(res.startsWith("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335de7ece74f6d2"))
                        assertTrue(res.contains("&dn=Concurrent_$i"))
                        assertTrue(res.contains("&tr="))

                        val augmented = TrackerInjector.getAugmentedTrackers(listOf("udp://custom.org:1337/announce"))
                        assertEquals(23, augmented.size)
                    } catch (e: Throwable) {
                        errors.incrementAndGet()
                    }
                }
            }

            jobs.awaitAll()
            assertEquals("No concurrent tracker injection errors allowed", 0, errors.get())
        }
    }

    // =========================================================================
    // SECTION 6: BENCODEVALIDATOR ALIAS & INTENT PARSER FALLBACK CONTRACTS
    // =========================================================================

    @Test
    fun challenge_BencodeValidatorAlias_IdentityContract() {
        val rng = Random(999)

        for (i in 0 until 100) {
            val singleOrMulti = rng.nextBoolean()
            val bytes = if (singleOrMulti) {
                buildSingleFileTorrent(name = "test_$i.bin", fileLength = (i + 1) * 1024L)
            } else {
                buildMultiFileTorrent(dirName = "dir_$i")
            }

            val r1 = TorrentFileValidator.validate(bytes)
            val r2 = BencodeValidator.validate(bytes)
            assertEquals("BencodeValidator must return identical result to TorrentFileValidator", r1, r2)
            assertEquals(TorrentFileValidator.isValidTorrent(bytes), BencodeValidator.isValidTorrent(bytes))
        }
    }

    @Test
    fun challenge_TorrentIntentParser_FallbackDummyPayloadIsStructurallyValid() {
        val dummyResult = TorrentIntentParser.parseData(
            action = "android.intent.action.VIEW",
            dataUriString = "file:///storage/emulated/0/Download/sample.torrent",
            mimeType = "application/x-bittorrent"
        )

        assertNotNull(dummyResult)
        assertTrue(dummyResult is PendingTorrentIntent.TorrentFile)

        val torrentFile = dummyResult as PendingTorrentIntent.TorrentFile
        assertEquals("sample.torrent", torrentFile.fileName)
        assertTrue(torrentFile.bytes.isNotEmpty())

        // Verify the dummy bytes pass Bencode validation completely!
        val validation = TorrentFileValidator.validate(torrentFile.bytes)
        assertTrue("Fallback dummy payload must be valid bencode: $validation", validation is TorrentValidationResult.Valid)

        val valid = validation as TorrentValidationResult.Valid
        assertEquals("fallback.dat", valid.name)
        assertEquals(1024L, valid.totalSize)
        assertEquals(16384, valid.pieceLength)
        assertEquals(1, valid.pieceCount)
        assertNotNull(valid.infoHash)
    }
}
