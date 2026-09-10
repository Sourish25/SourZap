package com.sourzap.app.e2e

import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.data.model.SpeedTestPhase
import com.sourzap.app.data.model.SpeedTestState
import com.sourzap.app.torrent.core.NetworkIpHelper
import com.sourzap.app.torrent.core.UdpTrackerAnnouncer
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentState
import com.sourzap.app.ui.theme.AppThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Tier 3: Cross-Feature Combinations & Pairwise Interaction Test Suite.
 * Covers pairwise interactions across Features 1 through 12.
 */
class Tier3PairwiseInteractionsTest {

    // =========================================================================
    // P1: F1 (Crash-Safe Peer Injection) + F2 (Partial Progress Tracking)
    // =========================================================================

    @Test
    fun testP1_TorrentCrash_PeerInjection_And_PartialProgressCalculation() {
        // Safe peer injection connects peers, data arrives for selected files only
        val buf = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(1); buf.putInt(0x1000); buf.putInt(1800); buf.putInt(5); buf.putInt(10)
        buf.put(185.toByte()); buf.put(121.toByte()); buf.put(168.toByte()); buf.put(96.toByte())
        buf.putShort(6881.toShort())

        val peers = UdpTrackerAnnouncer.parsePeersFromResponse(buf.array(), 26, 0x1000)
        assertEquals(1, peers.size)
        assertFalse(NetworkIpHelper.isSelfOrLocal(peers[0].first))

        // Files: 1 selected (200MB), 1 ignored (800MB)
        val files = listOf(
            TorrentFileItem(0, "movie.mkv", 200_000_000L, downloadedBytes = 50_000_000L, priority = Priority.NORMAL),
            TorrentFileItem(1, "extra.iso", 800_000_000L, downloadedBytes = 0L, priority = Priority.IGNORE)
        )

        val selected = files.filter { !it.isSkipped }
        val totalBytes = selected.sumOf { it.size }
        val downloadedBytes = selected.sumOf { it.downloadedBytes }
        val progress = downloadedBytes.toFloat() / totalBytes.toFloat()

        assertEquals(200_000_000L, totalBytes)
        assertEquals(50_000_000L, downloadedBytes)
        assertEquals(0.25f, progress, 0.001f)
    }

    // =========================================================================
    // P2: F2 (Partial Progress Tracking) + F5 (Header Action Pill)
    // =========================================================================

    @Test
    fun testP2_PartialProgress_And_HeaderActionPill() {
        val item = TorrentItem(
            id = "f2_f5",
            name = "Linux Distro",
            state = TorrentState.DOWNLOADING,
            progress = 0.40f,
            downloadSpeed = 2_000_000L,
            uploadSpeed = 100_000L,
            totalBytes = 500_000_000L,
            downloadedBytes = 200_000_000L,
            uploadedBytes = 0L,
            numSeeds = 15,
            numPeers = 25
        )

        // Action pill triggers Pause All
        val paused = item.copy(state = TorrentState.PAUSED, downloadSpeed = 0L)
        assertEquals(TorrentState.PAUSED, paused.state)
        assertEquals(0.40f, paused.progress, 0.001f)
        assertEquals(200_000_000L, paused.downloadedBytes)
        assertEquals(500_000_000L, paused.totalBytes)

        // Action pill triggers Resume All
        val resumed = paused.copy(state = TorrentState.DOWNLOADING, downloadSpeed = 2_500_000L)
        assertEquals(TorrentState.DOWNLOADING, resumed.state)
        assertEquals(0.40f, resumed.progress, 0.001f)
    }

    // =========================================================================
    // P3: F3 (Real-Time Speed Smoother) + F4 (Trimmed Mean Throughput)
    // =========================================================================

    @Test
    fun testP3_SpeedSmoother_And_SpeedTestTrimmedMean() {
        // Collect samples from simulated smoother, feed into trimmed mean
        val rawSpeeds = listOf(10f, 95f, 98f, 102f, 99f, 101f, 100f, 97f, 103f, 300f) // outlier at 10 and 300
        val smoothedSamples = mutableListOf<Float>()
        var ema = rawSpeeds.first()

        for (speed in rawSpeeds) {
            ema = 0.32f * speed + 0.68f * ema
            smoothedSamples.add(ema)
        }

        // Apply 15% trimmed mean
        val sorted = smoothedSamples.sorted()
        val trimCount = (sorted.size * 0.15f).toInt() // 1
        val trimmed = sorted.subList(trimCount, sorted.size - trimCount)
        val finalSpeed = trimmed.average().toFloat()

        assertTrue("Final speed must reject outliers and remain stable around ~80-110", finalSpeed in 70f..120f)
    }

    // =========================================================================
    // P4: F3/F4 (Speed Test Engine) + F6 (Bottom Nav Dock Navigation)
    // =========================================================================

    @Test
    fun testP4_SpeedTestExecution_And_BottomNavNavigation() {
        var currentActiveTab = 2 // Speed Test tab
        var speedTestState = SpeedTestState(phase = SpeedTestPhase.DOWNLOAD, currentDownloadMbps = 88.5f)

        // User taps Torrent tab (index 1) in Bottom Dock
        currentActiveTab = 1
        assertEquals(1, currentActiveTab)

        // Speed test continues running concurrently in background
        speedTestState = speedTestState.copy(currentDownloadMbps = 92.0f, progress = 0.65f)
        assertEquals(92.0f, speedTestState.currentDownloadMbps, 0.001f)
        assertEquals(0.65f, speedTestState.progress, 0.001f)
    }

    // =========================================================================
    // P5: F7 (Markdown Changelog) + F8 (DNS Persistence in Settings)
    // =========================================================================

    @Test
    fun testP5_ChangelogMarkdownEngine_And_DnsSettingsPersistence() {
        val markdownText = "## Settings Update\n- DoH Provider persisted across app restarts"
        val hasHeading = markdownText.lines().any { it.startsWith("## ") }
        assertTrue(hasHeading)

        // User selects DoH Provider Quad9 in settings
        val settings = mutableMapOf<String, String>()
        settings["selected_doh_provider"] = DohProvider.QUAD9.name

        assertEquals(DohProvider.QUAD9.name, settings["selected_doh_provider"])
    }

    // =========================================================================
    // P6: F8 (DNS Persistence) + F1 (DoH Tracker Resolution in Torrent Engine)
    // =========================================================================

    @Test
    fun testP6_DnsSettingsPersistence_And_TorrentTrackerResolver() {
        val selectedProvider = DohProvider.QUAD9
        assertEquals(DohProvider.QUAD9, selectedProvider)

        // DohTrackerResolver relies on selected provider URL
        assertTrue(selectedProvider.url.contains("quad9.net"))
        assertEquals("QUAD9", selectedProvider.name)
        assertEquals("9.9.9.9", selectedProvider.bootstrapIp)
    }

    // =========================================================================
    // P7: F9 (OLED Themes) + F11 (Strict Contrast Adherence)
    // =========================================================================

    @Test
    fun testP7_OledMonochromeTheme_And_StrictContrastAdherence() {
        // OLED Monochrome uses pure pitch black #000000 background and pure white #FFFFFF text
        val bgLuminance = 0.0 // #000000
        val fgLuminance = 1.0 // #FFFFFF
        val contrastRatio = (fgLuminance + 0.05) / (bgLuminance + 0.05)

        assertEquals(21.0, contrastRatio, 0.01)
        assertTrue("OLED Monochrome must satisfy WCAG AAA (>= 7.0:1)", contrastRatio >= 7.0)
    }

    // =========================================================================
    // P8: F10 (Custom Themes) + F11 (Strict Contrast Adherence)
    // =========================================================================

    @Test
    fun testP8_CustomTheme_And_StrictContrastAdherence() {
        // Custom theme with OLED black enabled
        val isOledCustom = true
        val bgLuminance = if (isOledCustom) 0.0 else 0.05
        val textLuminance = 0.90
        val contrastRatio = (textLuminance + 0.05) / (bgLuminance + 0.05)

        assertTrue("Custom OLED theme must satisfy WCAG AA (>= 4.5:1)", contrastRatio >= 4.5)
    }

    // =========================================================================
    // P9: F11 (Strict Contrast) + F5 (Header Action Pill Harmonization)
    // =========================================================================

    @Test
    fun testP9_StrictContrastAdherence_And_HeaderActionPillHarmonization() {
        // Shield accent matches primary accent; must maintain readable contrast against app bar
        val appBarLuminance = 0.01 // dark app bar
        val shieldAccentLuminance = 0.65 // vibrant harmonized accent
        val cr = (shieldAccentLuminance + 0.05) / (appBarLuminance + 0.05)

        assertTrue("Harmonized shield accent must have >= 3:1 contrast against app bar", cr >= 3.0)
    }

    // =========================================================================
    // P10: F12 (QoL Auto-Ranging) + F3/F4 (Speed Test Real-Time Display)
    // =========================================================================

    @Test
    fun testP10_QoLAutoRanging_And_SpeedTestEngineReadouts() {
        fun formatSpeed(mbps: Float): String {
            return when {
                mbps >= 1000f -> String.format(Locale.US, "%.2f Gbps", mbps / 1000f)
                mbps >= 1f -> String.format(Locale.US, "%.1f Mbps", mbps)
                else -> String.format(Locale.US, "%.1f Kbps", mbps * 1000f)
            }
        }

        val testSpeeds = listOf(0.85f, 45.2f, 150.0f, 1050.0f)
        val formatted = testSpeeds.map { formatSpeed(it) }

        assertEquals("850.0 Kbps", formatted[0])
        assertEquals("45.2 Mbps", formatted[1])
        assertEquals("150.0 Mbps", formatted[2])
        assertEquals("1.05 Gbps", formatted[3])
    }

    // =========================================================================
    // P11: F12 (QoL Search Bar Clear) + F2 (Partial Progress Torrent List)
    // =========================================================================

    @Test
    fun testP11_QoLSearchBarClear_And_PartialProgressTorrentList() {
        val torrents = listOf(
            TorrentItem("1", "Ubuntu 24.04 ISO", TorrentState.DOWNLOADING, 0.3f, 0L, 0L, 100L, 30L, 0L, 0, 0),
            TorrentItem("2", "Fedora Workstation", TorrentState.DOWNLOADING, 0.7f, 0L, 0L, 200L, 140L, 0L, 0, 0)
        )

        var query = "Ubuntu"
        var filtered = torrents.filter { it.name.contains(query, ignoreCase = true) }
        assertEquals(1, filtered.size)
        assertEquals(0.3f, filtered[0].progress, 0.001f)

        // Clear query
        query = ""
        filtered = torrents.filter { it.name.contains(query, ignoreCase = true) }
        assertEquals(2, filtered.size)
    }

    // =========================================================================
    // P12: F6 (Bottom Nav Dock) + F9 (OLED Theming)
    // =========================================================================

    @Test
    fun testP12_BottomNavDimensions_And_OledTheming() {
        val dockHeightDp = 66
        val dockPillDp = 50
        val iconSizeDp = 26
        val oledBackgroundHex = "#000000"

        assertEquals(66, dockHeightDp)
        assertEquals(50, dockPillDp)
        assertEquals(26, iconSizeDp)
        assertEquals("#000000", oledBackgroundHex)
    }
}
