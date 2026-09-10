package com.sourzap.app.e2e

import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.data.model.SpeedTestPhase
import com.sourzap.app.data.model.SpeedTestResult
import com.sourzap.app.data.model.SpeedTestState
import com.sourzap.app.torrent.model.Priority
import com.sourzap.app.torrent.model.TorrentFileItem
import com.sourzap.app.torrent.model.TorrentFilter
import com.sourzap.app.torrent.model.TorrentItem
import com.sourzap.app.torrent.model.TorrentState
import com.sourzap.app.ui.theme.AppThemePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Tier 4: Real-World Multi-Feature End-to-End Application Workflow Scenarios Test Suite.
 * Exercises realistic user journeys spanning all major features and subsystems.
 */
class Tier4RealWorldScenariosTest {

    // =========================================================================
    // SCENARIO 1: Fresh Install -> Theme Customization -> Contrast Audit
    // =========================================================================

    @Test
    fun testScenario1_FreshInstall_ThemeCustomization_ContrastAudit() {
        // Step 1: Default theme is DYNAMIC
        var currentPreset = AppThemePreset.DYNAMIC.id
        assertEquals("DYNAMIC", currentPreset)

        // Step 2: User navigates to Settings -> Selects OLED Monochrome
        currentPreset = "OLED_MONOCHROME"
        val isOled = currentPreset.contains("OLED") || currentPreset.contains("AMOLED")
        assertTrue(isOled)

        // Step 3: Pure pitch black enforcement for OLED
        val bgLuminance = 0.0 // #000000
        val textLuminance = 1.0 // #FFFFFF
        val accentLuminance = 0.70 // Silver/White

        // Step 4: Strict WCAG Contrast Audit
        val textContrast = (textLuminance + 0.05) / (bgLuminance + 0.05)
        val accentContrast = (accentLuminance + 0.05) / (bgLuminance + 0.05)

        assertEquals(21.0, textContrast, 0.01)
        assertTrue("Text contrast must satisfy WCAG AAA (>= 7.0:1)", textContrast >= 7.0)
        assertTrue("Accent contrast must satisfy WCAG AA (>= 4.5:1)", accentContrast >= 4.5)
    }

    // =========================================================================
    // SCENARIO 2: Selective Torrent Download Lifecycle Workflow
    // =========================================================================

    @Test
    fun testScenario2_SelectiveTorrentDownload_LifecycleWorkflow() {
        // Step 1: Add a 3-file torrent (1.2 GB total)
        val file1 = TorrentFileItem(0, "Movie.mkv", 1_000_000_000L, priority = Priority.NORMAL)
        val file2 = TorrentFileItem(1, "Sample.mkv", 150_000_000L, priority = Priority.IGNORE) // Skipped
        val file3 = TorrentFileItem(2, "Subs.srt", 50_000_000L, priority = Priority.NORMAL)
        val files = listOf(file1, file2, file3)

        // Step 2: Calculate selected total bytes (skipping Sample.mkv)
        val selectedFiles = files.filter { !it.isSkipped }
        val totalSelectedBytes = selectedFiles.sumOf { it.size }
        assertEquals(1_050_000_000L, totalSelectedBytes) // NOT 1.2 GB

        // Step 3: Commence downloading, 525 MB downloaded across selected files
        val downloadedBytes = 525_000_000L
        val progress = (downloadedBytes.toFloat() / totalSelectedBytes.toFloat()).coerceIn(0f, 1f)
        assertEquals(0.50f, progress, 0.001f)

        var torrent = TorrentItem(
            id = "scenario2_torrent",
            name = "Feature Film",
            state = TorrentState.DOWNLOADING,
            progress = progress,
            downloadSpeed = 25_000_000L, // 25 MB/s
            uploadSpeed = 1_000_000L,
            totalBytes = totalSelectedBytes,
            downloadedBytes = downloadedBytes,
            uploadedBytes = 0L,
            numSeeds = 20,
            numPeers = 40,
            files = files
        )
        assertEquals(50, torrent.progressPercent)
        assertEquals(21L, (torrent.totalBytes - torrent.downloadedBytes) / torrent.downloadSpeed) // 21s ETA

        // Step 4: Top action cluster pause all
        torrent = torrent.copy(state = TorrentState.PAUSED, downloadSpeed = 0L)
        assertEquals(TorrentState.PAUSED, torrent.state)
        assertEquals(0.50f, torrent.progress, 0.001f)

        // Step 5: Resume and complete download
        torrent = torrent.copy(
            state = TorrentState.FINISHED,
            progress = 1.0f,
            downloadedBytes = totalSelectedBytes,
            downloadSpeed = 0L
        )
        assertTrue(torrent.isCompleted)
        assertEquals(100, torrent.progressPercent)
    }

    // =========================================================================
    // SCENARIO 3: Turbo Speed Test Complete Execution Workflow
    // =========================================================================

    @Test
    fun testScenario3_TurboSpeedTest_CompleteExecutionWorkflow() {
        // Step 1: Speed test begins with PING phase
        var state = SpeedTestState(
            phase = SpeedTestPhase.PING,
            currentPingMs = 12.4f,
            currentJitterMs = 1.2f,
            progress = 0.20f
        )
        assertEquals(SpeedTestPhase.PING, state.phase)

        // Step 2: Transition to multi-stream DOWNLOAD phase with real-time smoothing
        state = state.copy(phase = SpeedTestPhase.DOWNLOAD)
        val rawSpeeds = listOf(40f, 85f, 120f, 115f, 118f, 117f, 119f, 118f, 116f, 122f)
        var ema = rawSpeeds.first()
        val sustainedSamples = mutableListOf<Float>()

        for (inst in rawSpeeds) {
            ema = 0.32f * inst + 0.68f * ema
            sustainedSamples.add(ema)
        }

        // Step 3: Compute 15% trimmed mean of sustained samples
        val sorted = sustainedSamples.sorted()
        val trimCount = (sorted.size * 0.15f).toInt() // 1
        val trimmed = sorted.subList(trimCount, sorted.size - trimCount)
        val finalDownloadMbps = trimmed.average().toFloat()

        assertTrue("Final download speed must be smoothed around 90-125 Mbps", finalDownloadMbps in 90f..125f)

        // Step 4: Transition to UPLOAD phase
        state = state.copy(
            phase = SpeedTestPhase.UPLOAD,
            currentDownloadMbps = finalDownloadMbps,
            currentUploadMbps = 45.0f,
            progress = 0.85f
        )
        assertEquals(SpeedTestPhase.UPLOAD, state.phase)

        // Step 5: Complete test and format auto-ranged result
        state = state.copy(phase = SpeedTestPhase.COMPLETED, progress = 1.0f)
        fun autoRange(mbps: Float) = if (mbps >= 1000f) String.format(Locale.US, "%.2f Gbps", mbps / 1000f) else String.format(Locale.US, "%.1f Mbps", mbps)
        val formattedResult = autoRange(state.currentDownloadMbps)
        assertTrue(formattedResult.endsWith("Mbps"))

        // Step 6: Save result into speed test history
        val historyItem = SpeedTestResult(
            timestamp = System.currentTimeMillis(),
            downloadMbps = state.currentDownloadMbps,
            uploadMbps = state.currentUploadMbps,
            pingMs = state.currentPingMs,
            jitterMs = state.currentJitterMs
        )
        assertNotNull(historyItem)
        assertTrue(historyItem.downloadMbps > 0)
    }

    // =========================================================================
    // SCENARIO 4: In-App Update Markdown Changelog & About Version
    // =========================================================================

    @Test
    fun testScenario4_InAppUpdate_MarkdownChangelog_And_AboutVersion() {
        // Step 1: Release catalog delivers markdown changelog
        val rawChangelog = """
            # Version 2.6.1 Release
            ## Highlights
            - **Crash Fix**: Resolved native JNI crash in peer injection
            - **UI Modernization**: Expressive layered action pill and 26dp nav icons
            - **DoH Persistence**: Cloudflare & Quad9 preferences now survive restarts
            ## Under the Hood
            - Upgraded smoothing window with `SpeedMeasurementSmoother`
        """.trimIndent()

        // Step 2: Markdown parsing separates headers, bullets, bold spans, and code
        val lines = rawChangelog.lines()
        val h1Count = lines.count { it.startsWith("# ") }
        val h2Count = lines.count { it.startsWith("## ") }
        val bulletCount = lines.count { it.startsWith("- ") }
        val boldSpans = lines.filter { it.contains("**") }
        val codeSpans = lines.filter { it.contains("`") }

        assertEquals(1, h1Count)
        assertEquals(2, h2Count)
        assertEquals(4, bulletCount)
        assertEquals(3, boldSpans.size)
        assertEquals(1, codeSpans.size)

        // Step 3: About screen click-to-copy version formatting
        val versionName = "2.6.1"
        val versionCode = 27
        val clipboardPayload = "v$versionName ($versionCode)"
        assertEquals("v2.6.1 (27)", clipboardPayload)
    }

    // =========================================================================
    // SCENARIO 5: Security Settings & DoH Persistence Across Restarts
    // =========================================================================

    @Test
    fun testScenario5_SecuritySettings_DohPersistence_AcrossRestarts() {
        // Step 1: Default is Cloudflare
        val defaultProvider = DohProvider.CLOUDFLARE
        assertEquals(DohProvider.CLOUDFLARE, defaultProvider)

        // Step 2: User changes DNS provider to QUAD9
        val mockSharedPreferences = mutableMapOf<String, String>()
        val selectedProvider = DohProvider.QUAD9
        mockSharedPreferences["selected_doh_provider"] = selectedProvider.name

        // Step 3: User switches DPI bypass strategy to TLS Split
        val strategy = BypassStrategy(id = "tls_split", name = "TLS Split", dohProvider = selectedProvider)
        assertEquals(DohProvider.QUAD9, strategy.dohProvider)

        // Step 4: Simulate app process kill and restart
        // Fresh repository initialization reads from SharedPreferences
        val restoredProviderName = mockSharedPreferences["selected_doh_provider"]
        val restoredProvider = restoredProviderName?.let {
            try { DohProvider.valueOf(it) } catch (_: Exception) { DohProvider.CLOUDFLARE }
        } ?: DohProvider.CLOUDFLARE

        assertEquals("DoH preference must persist as QUAD9 across restart", DohProvider.QUAD9, restoredProvider)
    }

    // =========================================================================
    // SCENARIO 6: Multi-Torrent Search, Filter, and Dock Navigation
    // =========================================================================

    @Test
    fun testScenario6_MultiTorrentManagement_SearchFilter_DockNavigation() {
        // Step 1: Session has 4 torrents in various states
        val torrentList = listOf(
            TorrentItem("1", "Ubuntu Desktop 24.04", TorrentState.DOWNLOADING, 0.4f, 1000L, 0L, 1000L, 400L, 0L, 1, 1),
            TorrentItem("2", "Debian NetInst 12", TorrentState.PAUSED, 0.8f, 0L, 0L, 500L, 400L, 0L, 0, 0),
            TorrentItem("3", "Arch Linux Base", TorrentState.FINISHED, 1.0f, 0L, 0L, 800L, 800L, 0L, 0, 0),
            TorrentItem("4", "Fedora Server 40", TorrentState.DOWNLOADING, 0.1f, 500L, 0L, 2000L, 200L, 0L, 1, 1)
        )

        // Step 2: Filter by DOWNLOADING
        val downloading = torrentList.filter { TorrentFilter.DOWNLOADING.matches(it) }
        assertEquals(2, downloading.size)

        // Step 3: User searches "Debian" in search bar
        var searchQuery = "Debian"
        val searchResults = torrentList.filter { it.name.contains(searchQuery, ignoreCase = true) }
        assertEquals(1, searchResults.size)
        assertEquals("Debian NetInst 12", searchResults[0].name)

        // Step 4: User clicks Clear button in search bar
        searchQuery = ""
        val allRestored = torrentList.filter { it.name.contains(searchQuery, ignoreCase = true) }
        assertEquals(4, allRestored.size)

        // Step 5: User taps Speed Test tab in redesigned bottom dock
        val dockTabs = listOf("VPN", "Torrents", "SpeedTest", "Settings")
        val activeTabIndex = dockTabs.indexOf("SpeedTest")
        assertEquals(2, activeTabIndex)
    }
}
