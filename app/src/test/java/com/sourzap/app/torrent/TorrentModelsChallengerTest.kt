package com.sourzap.app.torrent

import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentPieceInfo
import com.sourzap.app.torrent.model.TorrentSessionStats
import com.sourzap.app.torrent.model.TorrentSource
import com.sourzap.app.torrent.model.TorrentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Empirical Challenger Test Suite for Milestone M1 Data Models & Logic (TorrentModels.kt).
 * Stress tests:
 * 1. State transitions, lifecycle flags across all 8 states.
 * 2. Boundary, negative, zero, and extreme integer conversions for Priority (including libtorrent SWIG roundtrips).
 * 3. TorrentFilter exhaustive 8x3 state/progress matrix matching.
 * 4. TorrentItem formatting under extreme values (0, negative, Long.MAX_VALUE, multi-terabyte, year+ ETAs).
 * 5. Numerical stability of progress (NaN, -Infinity, +Infinity, underflow, overflow, percent clamping).
 * 6. Division-by-zero resistance and bitfield equality in TorrentPieceInfo.
 * 7. Path parsing across POSIX, Windows, mixed separators, and empty strings in TorrentFileItem.
 * 8. Byte array deep equality & hashCode contracts in TorrentSource.FileContent.
 */
class TorrentModelsChallengerTest {

    @Test
    fun testTorrentStateExhaustiveMatrix() {
        val runningStates = setOf(
            TorrentState.DOWNLOADING,
            TorrentState.SEEDING,
            TorrentState.METADATA,
            TorrentState.ALLOCATING,
            TorrentState.CHECKING
        )
        val nonRunningStates = setOf(
            TorrentState.PAUSED,
            TorrentState.FINISHED,
            TorrentState.ERROR
        )

        val completedStates = setOf(
            TorrentState.FINISHED,
            TorrentState.SEEDING
        )

        for (state in TorrentState.values()) {
            if (state in runningStates) {
                assertTrue("State $state should be running", state.isRunning)
            } else {
                assertTrue("State $state should NOT be running", state in nonRunningStates)
                assertFalse("State $state should NOT be running", state.isRunning)
            }

            if (state in completedStates) {
                assertTrue("State $state should be completed", state.isCompleted)
            } else {
                assertFalse("State $state should NOT be completed", state.isCompleted)
            }
        }
    }

    @Test
    fun testPriorityBoundaryAndExtremeValues() {
        // Negative / zero boundaries -> IGNORE
        assertEquals(Priority.IGNORE, Priority.fromValue(0))
        assertEquals(Priority.IGNORE, Priority.fromValue(-1))
        assertEquals(Priority.IGNORE, Priority.fromValue(-100))
        assertEquals(Priority.IGNORE, Priority.fromValue(Int.MIN_VALUE))

        // 1..3 boundaries -> LOW
        assertEquals(Priority.LOW, Priority.fromValue(1))
        assertEquals(Priority.LOW, Priority.fromValue(2))
        assertEquals(Priority.LOW, Priority.fromValue(3))

        // 4..6 boundaries -> NORMAL
        assertEquals(Priority.NORMAL, Priority.fromValue(4))
        assertEquals(Priority.NORMAL, Priority.fromValue(5))
        assertEquals(Priority.NORMAL, Priority.fromValue(6))

        // >= 7 boundaries -> HIGH
        assertEquals(Priority.HIGH, Priority.fromValue(7))
        assertEquals(Priority.HIGH, Priority.fromValue(8))
        assertEquals(Priority.HIGH, Priority.fromValue(100))
        assertEquals(Priority.HIGH, Priority.fromValue(Int.MAX_VALUE))
    }

    @Test
    fun testPriorityLibtorrentSwigRoundtrips() {
        // Native mapping verification
        assertEquals(org.libtorrent4j.Priority.IGNORE, Priority.IGNORE.toLibtorrentPriority())
        assertEquals(0, Priority.IGNORE.toLibtorrentPriority().swig().toInt())

        assertEquals(1, Priority.LOW.toLibtorrentPriority().swig().toInt())

        assertEquals(org.libtorrent4j.Priority.DEFAULT, Priority.NORMAL.toLibtorrentPriority())
        assertEquals(4, Priority.NORMAL.toLibtorrentPriority().swig().toInt())

        assertEquals(org.libtorrent4j.Priority.TOP_PRIORITY, Priority.HIGH.toLibtorrentPriority())
        assertEquals(7, Priority.HIGH.toLibtorrentPriority().swig().toInt())

        // Roundtrip from libtorrent Priority
        assertEquals(Priority.IGNORE, Priority.fromLibtorrent(org.libtorrent4j.Priority.IGNORE))
        assertEquals(Priority.NORMAL, Priority.fromLibtorrent(org.libtorrent4j.Priority.DEFAULT))
        assertEquals(Priority.HIGH, Priority.fromLibtorrent(org.libtorrent4j.Priority.TOP_PRIORITY))
        assertEquals(Priority.LOW, Priority.fromLibtorrent(org.libtorrent4j.Priority.fromSwig(1)))
        assertEquals(Priority.LOW, Priority.fromLibtorrent(org.libtorrent4j.Priority.fromSwig(2)))
        assertEquals(Priority.LOW, Priority.fromLibtorrent(org.libtorrent4j.Priority.fromSwig(3)))
        assertEquals(Priority.HIGH, Priority.fromLibtorrent(org.libtorrent4j.Priority.fromSwig(7)))
        assertEquals(Priority.HIGH, Priority.fromValue(8))
    }

    @Test
    fun testTorrentFilterExhaustivePermutations() {
        val progressValues = listOf(0.0f, 0.5f, 1.0f, 1.2f)

        for (state in TorrentState.values()) {
            for (progress in progressValues) {
                val item = createItem(state, progress)

                // ALL filter must always match
                assertTrue("ALL filter failed for state=$state progress=$progress", TorrentFilter.ALL.matches(item))

                // DOWNLOADING filter
                val isDownloadingMatch = state == TorrentState.DOWNLOADING ||
                        state == TorrentState.ALLOCATING ||
                        state == TorrentState.METADATA
                assertEquals(
                    "DOWNLOADING filter mismatch for state=$state",
                    isDownloadingMatch,
                    TorrentFilter.DOWNLOADING.matches(item)
                )

                // SEEDING filter
                val isSeedingMatch = state == TorrentState.SEEDING
                assertEquals(
                    "SEEDING filter mismatch for state=$state",
                    isSeedingMatch,
                    TorrentFilter.SEEDING.matches(item)
                )

                // PAUSED filter
                val isPausedMatch = state == TorrentState.PAUSED
                assertEquals(
                    "PAUSED filter mismatch for state=$state",
                    isPausedMatch,
                    TorrentFilter.PAUSED.matches(item)
                )

                // COMPLETED filter: FINISHED or SEEDING or progress >= 1.0f
                val isCompletedMatch = state == TorrentState.FINISHED ||
                        state == TorrentState.SEEDING ||
                        progress >= 1.0f
                assertEquals(
                    "COMPLETED filter mismatch for state=$state progress=$progress",
                    isCompletedMatch,
                    TorrentFilter.COMPLETED.matches(item)
                )
            }
        }
    }

    @Test
    fun testFileSizeFormattingBoundariesAndExtremeValues() {
        // Zero and negative
        assertEquals("0 B", TorrentItem.formatFileSize(-999999L))
        assertEquals("0 B", TorrentItem.formatFileSize(-1L))
        assertEquals("0 B", TorrentItem.formatFileSize(0L))

        // Bytes
        assertEquals("1 B", TorrentItem.formatFileSize(1L))
        assertEquals("512 B", TorrentItem.formatFileSize(512L))
        assertEquals("1023 B", TorrentItem.formatFileSize(1023L))

        // Kilobytes
        assertEquals("1.00 KB", TorrentItem.formatFileSize(1024L))
        assertEquals("1.50 KB", TorrentItem.formatFileSize(1536L))
        assertEquals("1023.00 KB", TorrentItem.formatFileSize(1024L * 1023))

        // Megabytes
        assertEquals("1.00 MB", TorrentItem.formatFileSize(1024L * 1024))
        assertEquals("10.50 MB", TorrentItem.formatFileSize((10.5 * 1024 * 1024).toLong()))
        assertEquals("1023.00 MB", TorrentItem.formatFileSize(1024L * 1024 * 1023))

        // Gigabytes
        assertEquals("1.00 GB", TorrentItem.formatFileSize(1024L * 1024 * 1024))
        assertEquals("4.25 GB", TorrentItem.formatFileSize((4.25 * 1024 * 1024 * 1024).toLong()))

        // Terabytes
        assertEquals("1.00 TB", TorrentItem.formatFileSize(1024L * 1024 * 1024 * 1024))
        assertEquals("16.00 TB", TorrentItem.formatFileSize(16L * 1024 * 1024 * 1024 * 1024))

        // Extreme Long.MAX_VALUE without crash
        val maxFormatted = TorrentItem.formatFileSize(Long.MAX_VALUE)
        assertTrue(maxFormatted.contains("TB"))
        assertFalse(maxFormatted.contains("NaN"))
    }

    @Test
    fun testSpeedFormattingBoundariesAndExtremeValues() {
        // Zero and negative
        assertEquals("0 B/s", TorrentItem.formatBytesPerSec(-500L))
        assertEquals("0 B/s", TorrentItem.formatBytesPerSec(-1L))
        assertEquals("0 B/s", TorrentItem.formatBytesPerSec(0L))

        // Bytes/s
        assertEquals("500 B/s", TorrentItem.formatBytesPerSec(500L))
        assertEquals("1023 B/s", TorrentItem.formatBytesPerSec(1023L))

        // KB/s
        assertEquals("1.0 KB/s", TorrentItem.formatBytesPerSec(1024L))
        assertEquals("512.0 KB/s", TorrentItem.formatBytesPerSec(512L * 1024))

        // MB/s
        assertEquals("1.0 MB/s", TorrentItem.formatBytesPerSec(1024L * 1024))
        assertEquals("25.5 MB/s", TorrentItem.formatBytesPerSec((25.5 * 1024 * 1024).toLong()))

        // GB/s
        assertEquals("1.0 GB/s", TorrentItem.formatBytesPerSec(1024L * 1024 * 1024))
        assertEquals("10.0 GB/s", TorrentItem.formatBytesPerSec(10L * 1024 * 1024 * 1024))

        // Long.MAX_VALUE
        val maxSpeed = TorrentItem.formatBytesPerSec(Long.MAX_VALUE)
        assertTrue(maxSpeed.contains("GB/s"))
        assertFalse(maxSpeed.contains("NaN"))
    }

    @Test
    fun testEtaFormattingBoundariesAndExtremeValues() {
        // Invalid / unknown / infinite ETAs
        assertEquals("∞", TorrentItem.formatEtaDuration(-1L))
        assertEquals("∞", TorrentItem.formatEtaDuration(-999L))
        assertEquals("∞", TorrentItem.formatEtaDuration(Long.MIN_VALUE))
        assertEquals("∞", TorrentItem.formatEtaDuration(86400L * 365)) // 1 year+
        assertEquals("∞", TorrentItem.formatEtaDuration(Long.MAX_VALUE))

        // Zero
        assertEquals("0s", TorrentItem.formatEtaDuration(0L))

        // Seconds only (1s to 59s)
        assertEquals("1s", TorrentItem.formatEtaDuration(1L))
        assertEquals("45s", TorrentItem.formatEtaDuration(45L))
        assertEquals("59s", TorrentItem.formatEtaDuration(59L))

        // Minutes and seconds (60s to 3599s)
        assertEquals("1m 00s", TorrentItem.formatEtaDuration(60L))
        assertEquals("1m 05s", TorrentItem.formatEtaDuration(65L))
        assertEquals("5m 30s", TorrentItem.formatEtaDuration(330L))
        assertEquals("59m 59s", TorrentItem.formatEtaDuration(3599L))

        // Hours and minutes (3600s up to 365 days)
        assertEquals("1h 00m", TorrentItem.formatEtaDuration(3600L))
        assertEquals("1h 01m", TorrentItem.formatEtaDuration(3665L))
        assertEquals("2h 15m", TorrentItem.formatEtaDuration(8100L))
        assertEquals("24h 00m", TorrentItem.formatEtaDuration(86400L))
        assertEquals("8736h 00m", TorrentItem.formatEtaDuration(86400L * 364))
    }

    @Test
    fun testProgressNumericalRobustnessAndClamping() {
        // Normal progress
        val itemMid = createItem(TorrentState.DOWNLOADING, 0.542f)
        assertEquals(54, itemMid.progressPercent)
        assertEquals("54.2%", itemMid.formattedProgress)

        // Lower bounds / negative values
        val itemZero = createItem(TorrentState.DOWNLOADING, 0.0f)
        assertEquals(0, itemZero.progressPercent)
        assertEquals("0.0%", itemZero.formattedProgress)

        val itemNeg = createItem(TorrentState.DOWNLOADING, -0.25f)
        assertEquals(0, itemNeg.progressPercent)

        // Upper bounds / overflow
        val itemFull = createItem(TorrentState.DOWNLOADING, 1.0f)
        assertEquals(100, itemFull.progressPercent)
        assertEquals("100.0%", itemFull.formattedProgress)

        val itemOver = createItem(TorrentState.DOWNLOADING, 1.5f)
        assertEquals(100, itemOver.progressPercent)

        // Edge case: Float.NaN and Infinities
        val itemNaN = createItem(TorrentState.DOWNLOADING, Float.NaN)
        assertEquals(0, itemNaN.progressPercent)

        val itemPosInf = createItem(TorrentState.DOWNLOADING, Float.POSITIVE_INFINITY)
        assertEquals(100, itemPosInf.progressPercent)

        val itemNegInf = createItem(TorrentState.DOWNLOADING, Float.NEGATIVE_INFINITY)
        assertEquals(0, itemNegInf.progressPercent)
    }

    @Test
    fun testTorrentPieceInfoZeroDivisionAndEquivalence() {
        // Zero pieceCount protection
        val zeroPiece = TorrentPieceInfo(pieceCount = 0, piecesCompleted = 0)
        assertEquals(0.0f, zeroPiece.completionRatio, 0.0001f)

        // Negative pieceCount protection
        val negPiece = TorrentPieceInfo(pieceCount = -1, piecesCompleted = 0)
        assertEquals(0.0f, negPiece.completionRatio, 0.0001f)

        // Regular ratio
        val regularPiece = TorrentPieceInfo(pieceCount = 100, piecesCompleted = 75)
        assertEquals(0.75f, regularPiece.completionRatio, 0.0001f)

        // Bitfield array content equality & hash code
        val random = Random(42)
        val bits1 = BooleanArray(64) { random.nextBoolean() }
        val bits2 = bits1.clone()
        val bits3 = BooleanArray(64) { !bits1[it] }

        val p1 = TorrentPieceInfo(pieceCount = 64, piecesCompleted = 32, pieceBitfield = bits1)
        val p2 = TorrentPieceInfo(pieceCount = 64, piecesCompleted = 32, pieceBitfield = bits2)
        val p3 = TorrentPieceInfo(pieceCount = 64, piecesCompleted = 32, pieceBitfield = bits3)

        assertEquals(p1, p2)
        assertEquals(p1.hashCode(), p2.hashCode())
        assertNotEquals(p1, p3)
    }

    @Test
    fun testTorrentFileItemPathParsingAndPriorities() {
        // POSIX path
        val posixFile = TorrentFileItem(index = 0, path = "movies/ubuntu/installer.iso", size = 1024L)
        assertEquals("installer.iso", posixFile.fileName)
        assertFalse(posixFile.isSkipped)

        // Windows path
        val winFile = TorrentFileItem(index = 1, path = "C:\\Downloads\\Ubuntu\\root.img", size = 2048L)
        assertEquals("root.img", winFile.fileName)

        // Mixed path
        val mixedFile = TorrentFileItem(index = 2, path = "nested/dir\\sub/mixed_file.tar.gz", size = 4096L)
        assertEquals("mixed_file.tar.gz", mixedFile.fileName)

        // Single filename (no directories)
        val singleFile = TorrentFileItem(index = 3, path = "standalone.zip", size = 8192L)
        assertEquals("standalone.zip", singleFile.fileName)

        // Empty path
        val emptyFile = TorrentFileItem(index = 4, path = "", size = 0L)
        assertEquals("", emptyFile.fileName)

        // Priority skipping
        val skippedFile = posixFile.copy(priority = Priority.IGNORE)
        assertTrue(skippedFile.isSkipped)

        val lowFile = posixFile.copy(priority = Priority.LOW)
        assertFalse(lowFile.isSkipped)
    }

    @Test
    fun testTorrentSourceContentEqualityContracts() {
        // Magnet equality
        val m1 = TorrentSource.Magnet("magnet:?xt=urn:btih:12345", "Test")
        val m2 = TorrentSource.Magnet("magnet:?xt=urn:btih:12345", "Test")
        val m3 = TorrentSource.Magnet("magnet:?xt=urn:btih:12345", "Different")
        assertEquals(m1, m2)
        assertEquals(m1.hashCode(), m2.hashCode())
        assertNotEquals(m1, m3)

        // FileContent deep byte array equality
        val bytesA = byteArrayOf(1, 2, 3, 4, 5)
        val bytesB = byteArrayOf(1, 2, 3, 4, 5)
        val bytesC = byteArrayOf(1, 2, 3, 4, 6)

        val fc1 = TorrentSource.FileContent(bytesA, "sample.torrent")
        val fc2 = TorrentSource.FileContent(bytesB, "sample.torrent")
        val fc3 = TorrentSource.FileContent(bytesC, "sample.torrent")
        val fc4 = TorrentSource.FileContent(bytesA, "other.torrent")

        assertEquals(fc1, fc2)
        assertEquals(fc1.hashCode(), fc2.hashCode())
        assertNotEquals(fc1, fc3)
        assertNotEquals(fc1, fc4)
    }

    @Test
    fun testTorrentSessionStatsFormatting() {
        val emptyStats = TorrentSessionStats()
        assertEquals("0 B/s", emptyStats.formattedDownloadSpeed)
        assertEquals("0 B/s", emptyStats.formattedUploadSpeed)
        assertEquals(0, emptyStats.activeTorrents)
        assertEquals(0L, emptyStats.dhtNodes)

        val activeStats = TorrentSessionStats(
            totalDownloadSpeed = 104_857_600L, // 100 MB/s
            totalUploadSpeed = 10_485_760L,    // 10 MB/s
            totalDownloadedBytes = 500_000_000_000L,
            totalUploadedBytes = 50_000_000_000L,
            activeTorrents = 15,
            pausedTorrents = 5,
            seedingTorrents = 8,
            dhtNodes = 1540L
        )
        assertEquals("100.0 MB/s", activeStats.formattedDownloadSpeed)
        assertEquals("10.0 MB/s", activeStats.formattedUploadSpeed)
    }

    private fun createItem(state: TorrentState, progress: Float): TorrentItem {
        return TorrentItem(
            id = "test-hash",
            name = "Test Item",
            state = state,
            progress = progress,
            downloadSpeed = 0L,
            uploadSpeed = 0L,
            totalBytes = 1000L,
            downloadedBytes = 0L,
            uploadedBytes = 0L,
            numSeeds = 0,
            numPeers = 0
        )
    }
}
