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
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentModelsTest {

    @Test
    fun testTorrentStateLifecycle() {
        assertTrue(TorrentState.DOWNLOADING.isRunning)
        assertTrue(TorrentState.SEEDING.isRunning)
        assertTrue(TorrentState.METADATA.isRunning)
        assertTrue(TorrentState.CHECKING.isRunning)
        assertFalse(TorrentState.PAUSED.isRunning)
        assertFalse(TorrentState.FINISHED.isRunning)
        assertFalse(TorrentState.ERROR.isRunning)

        assertTrue(TorrentState.FINISHED.isCompleted)
        assertTrue(TorrentState.SEEDING.isCompleted)
        assertFalse(TorrentState.DOWNLOADING.isCompleted)
    }

    @Test
    fun testPriorityMappings() {
        assertEquals(Priority.IGNORE, Priority.fromValue(0))
        assertEquals(Priority.LOW, Priority.fromValue(1))
        assertEquals(Priority.NORMAL, Priority.fromValue(4))
        assertEquals(Priority.HIGH, Priority.fromValue(7))

        assertEquals(org.libtorrent4j.Priority.IGNORE, Priority.IGNORE.toLibtorrentPriority())
        assertEquals(org.libtorrent4j.Priority.DEFAULT, Priority.NORMAL.toLibtorrentPriority())
        assertEquals(org.libtorrent4j.Priority.TOP_PRIORITY, Priority.HIGH.toLibtorrentPriority())

        assertEquals(Priority.IGNORE, Priority.fromLibtorrent(org.libtorrent4j.Priority.IGNORE))
        assertEquals(Priority.NORMAL, Priority.fromLibtorrent(org.libtorrent4j.Priority.DEFAULT))
        assertEquals(Priority.HIGH, Priority.fromLibtorrent(org.libtorrent4j.Priority.TOP_PRIORITY))
    }

    @Test
    fun testTorrentFilterMatching() {
        val downloadingItem = createDummyItem("1", TorrentState.DOWNLOADING, 0.45f)
        val seedingItem = createDummyItem("2", TorrentState.SEEDING, 1.0f)
        val pausedItem = createDummyItem("3", TorrentState.PAUSED, 0.2f)
        val finishedItem = createDummyItem("4", TorrentState.FINISHED, 1.0f)

        // ALL
        assertTrue(TorrentFilter.ALL.matches(downloadingItem))
        assertTrue(TorrentFilter.ALL.matches(seedingItem))
        assertTrue(TorrentFilter.ALL.matches(pausedItem))

        // DOWNLOADING
        assertTrue(TorrentFilter.DOWNLOADING.matches(downloadingItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(seedingItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(pausedItem))

        // SEEDING
        assertFalse(TorrentFilter.SEEDING.matches(downloadingItem))
        assertTrue(TorrentFilter.SEEDING.matches(seedingItem))

        // PAUSED
        assertTrue(TorrentFilter.PAUSED.matches(pausedItem))
        assertFalse(TorrentFilter.PAUSED.matches(downloadingItem))

        // COMPLETED
        assertTrue(TorrentFilter.COMPLETED.matches(finishedItem))
        assertTrue(TorrentFilter.COMPLETED.matches(seedingItem))
        assertFalse(TorrentFilter.COMPLETED.matches(downloadingItem))
    }

    @Test
    fun testTorrentItemFormatting() {
        val item = createDummyItem(
            id = "abc123hash",
            state = TorrentState.DOWNLOADING,
            progress = 0.542f,
            downloadSpeed = 5_242_880L, // 5 MB/s
            uploadSpeed = 524_288L,    // 512 KB/s
            totalBytes = 1_073_741_824L, // 1 GB
            downloadedBytes = 536_870_912L, // 512 MB
            etaSeconds = 125L
        )

        assertEquals("54.2%", item.formattedProgress)
        assertEquals(54, item.progressPercent)
        assertEquals("5.0 MB/s", item.formattedDownloadSpeed)
        assertEquals("512.0 KB/s", item.formattedUploadSpeed)
        assertEquals("1.00 GB", item.formattedTotalSize)
        assertEquals("512.00 MB", item.formattedDownloadedSize)
        assertEquals("2m 05s", item.formattedEta)
    }

    @Test
    fun testEtaFormattingUnits() {
        assertEquals("0s", TorrentItem.formatEtaDuration(0L))
        assertEquals("45s", TorrentItem.formatEtaDuration(45L))
        assertEquals("5m 30s", TorrentItem.formatEtaDuration(330L))
        assertEquals("2h 15m", TorrentItem.formatEtaDuration(8100L))
        assertEquals("∞", TorrentItem.formatEtaDuration(-1L))
    }

    @Test
    fun testTorrentPieceInfo() {
        val bits = BooleanArray(4) { it % 2 == 0 }
        val pieceInfo = TorrentPieceInfo(pieceCount = 4, piecesCompleted = 2, pieceBitfield = bits)

        assertEquals(0.5f, pieceInfo.completionRatio, 0.001f)
        assertEquals(4, pieceInfo.pieceCount)
        assertEquals(2, pieceInfo.piecesCompleted)

        val duplicate = TorrentPieceInfo(pieceCount = 4, piecesCompleted = 2, pieceBitfield = bits.clone())
        assertEquals(pieceInfo, duplicate)
        assertEquals(pieceInfo.hashCode(), duplicate.hashCode())
    }

    @Test
    fun testTorrentSourceEquivalence() {
        val magnet = TorrentSource.Magnet("magnet:?xt=urn:btih:deadbeef", "Debian ISO")
        assertEquals("magnet:?xt=urn:btih:deadbeef", magnet.uri)
        assertEquals("Debian ISO", magnet.displayName)

        val bytes1 = byteArrayOf(0x64, 0x31, 0x3a)
        val bytes2 = byteArrayOf(0x64, 0x31, 0x3a)
        val fileContent1 = TorrentSource.FileContent(bytes1, "test.torrent")
        val fileContent2 = TorrentSource.FileContent(bytes2, "test.torrent")
        assertEquals(fileContent1, fileContent2)
        assertEquals(fileContent1.hashCode(), fileContent2.hashCode())

        val filePath = TorrentSource.FilePath("/sdcard/Download/test.torrent")
        assertEquals("/sdcard/Download/test.torrent", filePath.path)
    }

    @Test
    fun testTorrentFileItem() {
        val fileItem = TorrentFileItem(
            index = 0,
            path = "Ubuntu/casper/filesystem.squashfs",
            size = 2_000_000_000L,
            downloadedBytes = 1_000_000_000L,
            progress = 0.5f,
            priority = Priority.HIGH
        )

        assertEquals("filesystem.squashfs", fileItem.fileName)
        assertFalse(fileItem.isSkipped)

        val skipped = fileItem.copy(priority = Priority.IGNORE)
        assertTrue(skipped.isSkipped)
    }

    @Test
    fun testTorrentSessionStats() {
        val stats = TorrentSessionStats(
            totalDownloadSpeed = 10_485_760L, // 10 MB/s
            totalUploadSpeed = 1_048_576L,    // 1 MB/s
            totalDownloadedBytes = 50_000_000_000L,
            totalUploadedBytes = 5_000_000_000L,
            activeTorrents = 3,
            pausedTorrents = 1,
            seedingTorrents = 2,
            dhtNodes = 120
        )

        assertEquals("10.0 MB/s", stats.formattedDownloadSpeed)
        assertEquals("1.0 MB/s", stats.formattedUploadSpeed)
        assertEquals(3, stats.activeTorrents)
        assertEquals(1, stats.pausedTorrents)
        assertEquals(2, stats.seedingTorrents)
        assertEquals(120L, stats.dhtNodes)
    }

    private fun createDummyItem(
        id: String,
        state: TorrentState,
        progress: Float,
        downloadSpeed: Long = 0L,
        uploadSpeed: Long = 0L,
        totalBytes: Long = 1000L,
        downloadedBytes: Long = 0L,
        etaSeconds: Long = -1L
    ): TorrentItem {
        return TorrentItem(
            id = id,
            name = "Test Torrent $id",
            state = state,
            progress = progress,
            downloadSpeed = downloadSpeed,
            uploadSpeed = uploadSpeed,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            uploadedBytes = 0L,
            numSeeds = 5,
            numPeers = 20,
            etaSeconds = etaSeconds
        )
    }
}
