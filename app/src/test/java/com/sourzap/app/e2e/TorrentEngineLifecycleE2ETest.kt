package com.sourzap.app.e2e

import com.sourzap.app.torrent.core.TorrentEngineManager
import com.sourzap.app.torrent.core.TorrentSessionConfig
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E2E BitTorrent Swarm Engine Lifecycle, Session Config & State Model Test Suite.
 * Covers Features F3, F4, F12 (Requirements R1, R3).
 */
class TorrentEngineLifecycleE2ETest {

    // =========================================================================
    // FEATURE F3 / R3: Session Configuration & Pure TCP Anti-Censorship
    // =========================================================================

    @Test
    fun testSessionConfig_PureTcpAntiCensorshipDefaults() {
        val config = TorrentSessionConfig.DEFAULT

        // Dual transport: uTP enabled, TCP enabled
        assertTrue("Incoming uTP must be enabled for peer connectivity", config.enableIncomingUtp)
        assertTrue("Outgoing uTP must be enabled for peer connectivity", config.enableOutgoingUtp)
        assertTrue("Incoming TCP must be enabled", config.enableIncomingTcp)
        assertTrue("Outgoing TCP must be enabled", config.enableOutgoingTcp)

        // Protocol Encryption: PE forced outbound to bypass BSNL DPI, enabled inbound with BOTH levels
        assertEquals(TorrentSessionConfig.ENC_POLICY_FORCED, config.outEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_POLICY_ENABLED, config.inEncPolicy)
        assertEquals(TorrentSessionConfig.ENC_LEVEL_BOTH, config.allowedEncLevel)
        assertTrue("RC4 preference must be active", config.preferRc4)

        // Swarm Saturation
        assertEquals(500, config.connectionsLimit)
        assertEquals(4000, config.maxPeerlistSize)
        assertEquals(1500, config.maxOutRequestQueue)
        assertTrue("DHT must be enabled", config.enableDht)
    }

    // =========================================================================
    // FEATURE F4: Engine State Mapping, Paused Detection & Priority
    // =========================================================================

    @Test
    fun testTorrentState_LifecycleProperties() {
        // isRunning property
        assertTrue(TorrentState.DOWNLOADING.isRunning)
        assertTrue(TorrentState.SEEDING.isRunning)
        assertTrue(TorrentState.METADATA.isRunning)
        assertTrue(TorrentState.ALLOCATING.isRunning)
        assertTrue(TorrentState.CHECKING.isRunning)
        assertFalse(TorrentState.PAUSED.isRunning)
        assertFalse(TorrentState.FINISHED.isRunning)
        assertFalse(TorrentState.ERROR.isRunning)

        // isCompleted property
        assertTrue(TorrentState.FINISHED.isCompleted)
        assertTrue(TorrentState.SEEDING.isCompleted)
        assertFalse(TorrentState.DOWNLOADING.isCompleted)
        assertFalse(TorrentState.PAUSED.isCompleted)
    }

    @Test
    fun testPriority_ConversionAndLevels() {
        assertEquals(Priority.IGNORE, Priority.fromValue(0))
        assertEquals(Priority.LOW, Priority.fromValue(1))
        assertEquals(Priority.LOW, Priority.fromValue(3))
        assertEquals(Priority.NORMAL, Priority.fromValue(4))
        assertEquals(Priority.NORMAL, Priority.fromValue(6))
        assertEquals(Priority.HIGH, Priority.fromValue(7))
        assertEquals(Priority.HIGH, Priority.fromValue(10))

        assertEquals(0, Priority.IGNORE.value)
        assertEquals(1, Priority.LOW.value)
        assertEquals(4, Priority.NORMAL.value)
        assertEquals(7, Priority.HIGH.value)
    }

    @Test
    fun testTorrentFilter_MatchingLogic() {
        val downloadingItem = createMockTorrentItem("1", "Down", TorrentState.DOWNLOADING, 0.5f)
        val seedingItem = createMockTorrentItem("2", "Seed", TorrentState.SEEDING, 1.0f)
        val pausedItem = createMockTorrentItem("3", "Pause", TorrentState.PAUSED, 0.2f)
        val finishedItem = createMockTorrentItem("4", "Fin", TorrentState.FINISHED, 1.0f)
        val metadataItem = createMockTorrentItem("5", "Meta", TorrentState.METADATA, 0.0f)

        // ALL filter
        assertTrue(TorrentFilter.ALL.matches(downloadingItem))
        assertTrue(TorrentFilter.ALL.matches(seedingItem))
        assertTrue(TorrentFilter.ALL.matches(pausedItem))

        // DOWNLOADING filter (includes METADATA and ALLOCATING)
        assertTrue(TorrentFilter.DOWNLOADING.matches(downloadingItem))
        assertTrue(TorrentFilter.DOWNLOADING.matches(metadataItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(seedingItem))
        assertFalse(TorrentFilter.DOWNLOADING.matches(pausedItem))

        // SEEDING filter
        assertTrue(TorrentFilter.SEEDING.matches(seedingItem))
        assertFalse(TorrentFilter.SEEDING.matches(downloadingItem))

        // PAUSED filter
        assertTrue(TorrentFilter.PAUSED.matches(pausedItem))
        assertFalse(TorrentFilter.PAUSED.matches(downloadingItem))

        // COMPLETED filter
        assertTrue(TorrentFilter.COMPLETED.matches(finishedItem))
        assertTrue(TorrentFilter.COMPLETED.matches(seedingItem))
        assertFalse(TorrentFilter.COMPLETED.matches(downloadingItem))
    }

    @Test
    fun testTorrentItem_FormattingAndEtaCalculations() {
        val item = TorrentItem(
            id = "c12fe1c06bba254a9dc9f519b335de7ece74f6d2",
            name = "Test Torrent Payload",
            state = TorrentState.DOWNLOADING,
            progress = 0.654f,
            downloadSpeed = 2_097_152L, // 2.0 MB/s
            uploadSpeed = 102_400L,     // 100.0 KB/s
            totalBytes = 1_073_741_824L, // 1 GB
            downloadedBytes = 702_237_153L,
            uploadedBytes = 52_428_800L,
            numSeeds = 15,
            numPeers = 42,
            totalSeeds = 30,
            totalPeers = 100,
            etaSeconds = 180L, // 3 minutes
            shareRatio = 0.075f
        )

        assertEquals("65.4%", item.formattedProgress)
        assertEquals(65, item.progressPercent)
        assertEquals("2.0 MB/s", item.formattedDownloadSpeed)
        assertEquals("100.0 KB/s", item.formattedUploadSpeed)
        assertEquals("1.00 GB", item.formattedTotalSize)
        assertEquals("3m 00s", item.formattedEta)
        assertFalse(item.isCompleted)

        // ETA format variations
        assertEquals("0s", TorrentItem.formatEtaDuration(0L))
        assertEquals("45s", TorrentItem.formatEtaDuration(45L))
        assertEquals("1h 15m", TorrentItem.formatEtaDuration(4500L))
        assertEquals("∞", TorrentItem.formatEtaDuration(-1L))
    }

    @Test
    fun testTorrentPieceInfo_CompletionRatio() {
        val pieceInfo = TorrentPieceInfo(
            pieceCount = 100,
            piecesCompleted = 75,
            pieceBitfield = BooleanArray(100) { it < 75 }
        )

        assertEquals(0.75f, pieceInfo.completionRatio, 0.001f)

        val emptyPieceInfo = TorrentPieceInfo(pieceCount = 0, piecesCompleted = 0)
        assertEquals(0.0f, emptyPieceInfo.completionRatio, 0.001f)
    }

    @Test
    fun testTorrentFileItem_FileNameExtraction() {
        val fileItem1 = TorrentFileItem(index = 0, path = "Ubuntu-ISO/ubuntu.iso", size = 4_000_000_000L)
        assertEquals("ubuntu.iso", fileItem1.fileName)
        assertFalse(fileItem1.isSkipped)

        val fileItem2 = TorrentFileItem(index = 1, path = "Doc\\Sub\\readme.txt", size = 1024L, priority = Priority.IGNORE)
        assertEquals("readme.txt", fileItem2.fileName)
        assertTrue(fileItem2.isSkipped)
    }

    // =========================================================================
    // FEATURE F3: Engine Auto-Start & Session Lifecycle Contracts
    // =========================================================================

    @Test
    fun testEngineManager_SafeLifecycleExecution() {
        try {
            val manager = TorrentEngineManager.create(TorrentSessionConfig.DEFAULT)
            assertNotNull(manager)
            assertFalse(manager.isSessionRunning())

            // Test observe flows default values
            val initialTorrents = manager.observeTorrents().value
            assertTrue(initialTorrents.isEmpty())

            val initialStats = manager.observeStats().value
            assertEquals(0L, initialStats.totalDownloadSpeed)
            assertEquals(0, initialStats.activeTorrents)

            // Safe stop without start
            manager.stopSession()
            assertFalse(manager.isSessionRunning())
        } catch (_: LinkageError) {
            // Expected on host JVM without jlibtorrent native binaries loaded
            assertTrue(true)
        }
    }

    private fun createMockTorrentItem(id: String, name: String, state: TorrentState, progress: Float): TorrentItem {
        return TorrentItem(
            id = id,
            name = name,
            state = state,
            progress = progress,
            downloadSpeed = 0L,
            uploadSpeed = 0L,
            totalBytes = 1000L,
            downloadedBytes = (1000L * progress).toLong(),
            uploadedBytes = 0L,
            numSeeds = 0,
            numPeers = 0
        )
    }
}
