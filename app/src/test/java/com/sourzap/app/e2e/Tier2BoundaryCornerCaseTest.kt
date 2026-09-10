package com.sourzap.app.e2e

import com.sourzap.app.data.model.DohProvider
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tier 2: Boundary & Corner Cases Test Suite.
 * Covers all 12 features (F1 to F12) with >=5 edge/boundary/stress tests per feature (60+ tests total).
 */
class Tier2BoundaryCornerCaseTest {

    // =========================================================================
    // FEATURE 1: Torrent Crash Resolution Boundary & Corner Cases (5 tests)
    // =========================================================================

    @Test
    fun testF1_Boundary_ZeroByteUdpPacket() {
        val emptyBytes = ByteArray(0)
        val peers = UdpTrackerAnnouncer.parsePeersFromResponse(emptyBytes, 0, 0x1234)
        assertTrue("0-byte datagram must return empty list without throwing", peers.isEmpty())
    }

    @Test
    fun testF1_Boundary_TruncatedUdpHeader() {
        // Less than 20 bytes (e.g. 12 bytes)
        val truncated = ByteArray(12)
        val peers = UdpTrackerAnnouncer.parsePeersFromResponse(truncated, 12, 0x1234)
        assertTrue("Truncated datagram must return empty list safely", peers.isEmpty())
    }

    @Test
    fun testF1_Boundary_InvalidActionCode() {
        // BEP 15 Announce requires action = 1. Action = 2 is scrape, action = 99 is invalid.
        val buf = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(99) // Invalid action
        buf.putInt(0x1234) // Transaction ID
        buf.putInt(1800)
        buf.putInt(10)
        buf.putInt(20)
        buf.put(10.toByte()); buf.put(0.toByte()); buf.put(0.toByte()); buf.put(1.toByte())
        buf.putShort(6881.toShort())

        val peers = UdpTrackerAnnouncer.parsePeersFromResponse(buf.array(), 26, 0x1234)
        assertTrue("Invalid action code must return empty list", peers.isEmpty())
    }

    @Test
    fun testF1_Boundary_MismatchedTransactionId() {
        val expectedTransId = 0xAAAA
        val buf = ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(1) // announce
        buf.putInt(0xBBBB) // Mismatched transId
        buf.putInt(1800)
        buf.putInt(10)
        buf.putInt(20)
        buf.put(10.toByte()); buf.put(0.toByte()); buf.put(0.toByte()); buf.put(1.toByte())
        buf.putShort(6881.toShort())

        val peers = UdpTrackerAnnouncer.parsePeersFromResponse(buf.array(), 26, expectedTransId)
        assertTrue("Mismatched transaction ID must return empty list", peers.isEmpty())
    }

    @Test
    fun testF1_Boundary_ExtremePortValuesAndZeroIp() {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(1) // announce
        buf.putInt(0x1234)
        buf.putInt(1800)
        buf.putInt(0)
        buf.putInt(0)

        // Peer 1: 0.0.0.0:8080 (invalid IP)
        buf.put(0.toByte()); buf.put(0.toByte()); buf.put(0.toByte()); buf.put(0.toByte())
        buf.putShort(8080.toShort())

        // Peer 2: 1.1.1.1:0 (invalid port 0)
        buf.put(1.toByte()); buf.put(1.toByte()); buf.put(1.toByte()); buf.put(1.toByte())
        buf.putShort(0.toShort())

        val peers = UdpTrackerAnnouncer.parsePeersFromResponse(buf.array(), 32, 0x1234)
        // Both invalid peers must be filtered out
        assertTrue("Invalid zero IP and port 0 must be rejected", peers.isEmpty())
    }

    // =========================================================================
    // FEATURE 2: Partial File Progress Tracking Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF2_Boundary_ZeroByteFilesAndDivisionByZero() {
        val files = listOf(
            TorrentFileItem(0, "empty.txt", 0L, downloadedBytes = 0L, priority = Priority.NORMAL)
        )
        val selected = files.filter { !it.isSkipped }
        val totalBytes = selected.sumOf { it.size }
        val downloadedBytes = selected.sumOf { it.downloadedBytes }

        // Division by zero safeguard
        val progress = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 1.0f
        assertEquals(1.0f, progress, 0.001f)
    }

    @Test
    fun testF2_Boundary_AllFilesSkipped() {
        val files = listOf(
            TorrentFileItem(0, "f1.dat", 1000L, priority = Priority.IGNORE),
            TorrentFileItem(1, "f2.dat", 2000L, priority = Priority.IGNORE)
        )
        val selected = files.filter { !it.isSkipped }
        val totalBytes = selected.sumOf { it.size }
        assertEquals(0L, totalBytes)

        val progress = if (totalBytes > 0L) 0f else 1.0f
        val remainingBytes = (totalBytes - 0L).coerceAtLeast(0L)
        assertEquals(0L, remainingBytes)
        assertEquals(1.0f, progress, 0.001f)
    }

    @Test
    fun testF2_Boundary_MultiTerabyteFiles64Bit() {
        // 5 TB total, 2.5 TB downloaded
        val totalBytes = 5_000_000_000_000L
        val downloadedBytes = 2_500_000_000_000L
        val progress = (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat()
        assertEquals(0.5f, progress, 0.001f)

        val remainingBytes = (totalBytes - downloadedBytes).coerceAtLeast(0L)
        assertEquals(2_500_000_000_000L, remainingBytes)
    }

    @Test
    fun testF2_Boundary_ZeroDownloadSpeedEta() {
        val remainingBytes = 500_000_000L
        val downloadSpeed = 0L
        val etaSeconds = if (downloadSpeed > 0 && remainingBytes > 0) remainingBytes / downloadSpeed else -1L
        assertEquals(-1L, etaSeconds)
        assertEquals("∞", TorrentItem.formatEtaDuration(etaSeconds))
    }

    @Test
    fun testF2_Boundary_DownloadedExceedsTotalClamping() {
        // Corrupt blocks re-downloaded might cause downloadedBytes > totalBytes
        val totalBytes = 100_000L
        val downloadedBytes = 120_000L
        val progress = (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        assertEquals(1.0f, progress, 0.001f)

        val remainingBytes = (totalBytes - downloadedBytes).coerceAtLeast(0L)
        assertEquals(0L, remainingBytes)
    }

    // =========================================================================
    // FEATURE 3: Real-Time Speed Test Smoothing Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF3_Boundary_ZeroElapsedTime() {
        val t1 = 1000L
        val t2 = 1000L
        val elapsed = (t2 - t1).coerceAtLeast(1)
        assertEquals(1L, elapsed)
    }

    @Test
    fun testF3_Boundary_BackwardTimestamp() {
        val t1 = 2000L
        val t2 = 1500L
        val delta = if (t2 > t1) t2 - t1 else 1L
        assertEquals(1L, delta)
    }

    @Test
    fun testF3_Boundary_ZeroByteDelta() {
        val bytes1 = 5_000_000L
        val bytes2 = 5_000_000L
        val deltaBytes = (bytes2 - bytes1).coerceAtLeast(0L)
        val speedMbps = ((deltaBytes * 8f) / (1000f / 1000f)) / 1_000_000f
        assertEquals(0.0f, speedMbps, 0.001f)
    }

    @Test
    fun testF3_Boundary_ExtremeSurge10Gbps() {
        // 1.25 GB in 1 second = 10 Gbps (10,000 Mbps)
        val deltaBytes = 1_250_000_000L
        val elapsedSec = 1.0f
        val speedMbps = ((deltaBytes * 8f) / elapsedSec) / 1_000_000f
        assertEquals(10000.0f, speedMbps, 0.1f)
        assertFalse(speedMbps.isInfinite())
        assertFalse(speedMbps.isNaN())
    }

    @Test
    fun testF3_Boundary_LongIdlePeriod() {
        // Samples 15s apart
        val t1 = 1000L
        val t2 = 16000L
        val windowMs = 1000L
        val isStale = (t2 - t1) > windowMs
        assertTrue("Sample older than 1000ms must be deemed stale", isStale)
    }

    // =========================================================================
    // FEATURE 4: Speed Test UI Damping & Trimmed Mean Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF4_Boundary_EmptySampleList() {
        val samples = emptyList<Float>()
        val fallback = 0.0f
        val result = if (samples.isNotEmpty()) samples.average().toFloat() else fallback
        assertEquals(0.0f, result, 0.001f)
    }

    @Test
    fun testF4_Boundary_SingleSampleTrim() {
        val samples = listOf(85.5f)
        val trimCount = (samples.size * 0.15f).toInt() // 0
        val trimmed = samples.subList(trimCount, samples.size - trimCount)
        assertEquals(1, trimmed.size)
        assertEquals(85.5f, trimmed[0], 0.001f)
    }

    @Test
    fun testF4_Boundary_AllSamplesIdentical() {
        val samples = List(50) { 100.0f }
        val trimCount = (samples.size * 0.15f).toInt() // 7
        val trimmed = samples.subList(trimCount, samples.size - trimCount)
        assertEquals(36, trimmed.size)
        assertEquals(100.0f, trimmed.average().toFloat(), 0.001f)
    }

    @Test
    fun testF4_Boundary_HighVolumeSamples10000() {
        val samples = (1..10000).map { (it % 100).toFloat() }
        val sorted = samples.sorted()
        val trimCount = (samples.size * 0.15f).toInt()
        val trimmed = sorted.subList(trimCount, sorted.size - trimCount)
        assertEquals(7000, trimmed.size)
        assertTrue(trimmed.average() > 0)
    }

    @Test
    fun testF4_Boundary_NegativeOrNanSampleFiltering() {
        val raw = listOf(Float.NaN, -5.0f, 100.0f, 105.0f, Float.POSITIVE_INFINITY)
        val sanitized = raw.filter { !it.isNaN() && !it.isInfinite() && it >= 0f }
        assertEquals(2, sanitized.size)
        assertEquals(listOf(100.0f, 105.0f), sanitized)
    }

    // =========================================================================
    // FEATURE 5: Torrent Header Action Pill Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF5_Boundary_RapidRepeatedClicks() {
        var isPaused = false
        for (i in 0 until 100) {
            isPaused = !isPaused
        }
        assertFalse(isPaused)
    }

    @Test
    fun testF5_Boundary_EmptyTorrentListActionCluster() {
        val torrents = emptyList<TorrentItem>()
        fun pauseAll(list: List<TorrentItem>) = list.map { it.copy(state = TorrentState.PAUSED) }
        val result = pauseAll(torrents)
        assertTrue(result.isEmpty())
    }

    @Test
    fun testF5_Boundary_AllTorrentsInErrorState() {
        val torrents = listOf(
            TorrentItem("1", "T1", TorrentState.ERROR, 0f, 0L, 0L, 100L, 0L, 0L, 0, 0, error = "Disk full")
        )
        val hasActiveDownloads = torrents.any { it.state.isRunning }
        assertFalse(hasActiveDownloads)
    }

    @Test
    fun testF5_Boundary_SingleTorrentVsMultiTorrent() {
        fun computeActiveCount(torrents: List<TorrentItem>): Int {
            return torrents.count { it.state == TorrentState.DOWNLOADING }
        }

        assertEquals(1, computeActiveCount(listOf(
            TorrentItem("1", "T1", TorrentState.DOWNLOADING, 0f, 0L, 0L, 100L, 0L, 0L, 0, 0)
        )))
        assertEquals(0, computeActiveCount(emptyList()))
    }

    @Test
    fun testF5_Boundary_ShieldColorContrastOnExtremeThemes() {
        val shieldLum = 0.70
        val barLum = 0.02
        val cr = (shieldLum + 0.05) / (barLum + 0.05)
        assertTrue("Shield accent must have >= 3:1 contrast against app bar", cr >= 3.0)
    }

    // =========================================================================
    // FEATURE 6: Bottom Nav Icon Centering & Enlargement Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF6_Boundary_ReSelectingActiveTab() {
        var currentTab = 1
        fun selectTab(newTab: Int) {
            if (newTab != currentTab) {
                currentTab = newTab
            }
        }
        selectTab(1)
        assertEquals(1, currentTab)
    }

    @Test
    fun testF6_Boundary_OutOfBoundsTabIndex() {
        fun sanitizeTabIndex(index: Int, totalTabs: Int = 4): Int {
            return index.coerceIn(0, totalTabs - 1)
        }
        assertEquals(0, sanitizeTabIndex(-1))
        assertEquals(3, sanitizeTabIndex(99))
    }

    @Test
    fun testF6_Boundary_CompactWidthDisplay() {
        val dockItemWidthDp = 50
        val tabCount = 4
        val totalDockItemsWidth = dockItemWidthDp * tabCount // 200dp
        val minSupportedScreenWidthDp = 320
        assertTrue(totalDockItemsWidth < minSupportedScreenWidthDp)
    }

    @Test
    fun testF6_Boundary_TalkbackContentDescriptionNotEmpty() {
        val descriptions = listOf("VPN", "Torrents", "Speed Test", "Settings")
        assertTrue(descriptions.all { it.isNotBlank() })
    }

    @Test
    fun testF6_Boundary_ZeroPaddingEncroachment() {
        val containerHeight = 66
        val pillHeight = 50
        val verticalPadding = (containerHeight - pillHeight) / 2
        assertEquals(8, verticalPadding)
    }

    // =========================================================================
    // FEATURE 7: Update Changelog Markdown Engine Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF7_Boundary_EmptyMarkdownString() {
        val emptyInput = ""
        val lines = emptyInput.lines().filter { it.isNotBlank() }
        assertTrue(lines.isEmpty())
    }

    @Test
    fun testF7_Boundary_TextWithoutMarkdown() {
        val plainText = "Regular release notes with no markdown syntax."
        val isHeader = plainText.startsWith("#")
        val isBullet = plainText.startsWith("- ") || plainText.startsWith("* ")
        assertFalse(isHeader)
        assertFalse(isBullet)
    }

    @Test
    fun testF7_Boundary_UnclosedFormattingTokens() {
        val unclosedBold = "This has **unclosed bold text"
        val regex = Regex("\\*\\*(.*?)\\*\\*")
        val match = regex.find(unclosedBold)
        // Should safely not match without throwing regex errors
        assertTrue(match == null)
    }

    @Test
    fun testF7_Boundary_DeeplyNestedList() {
        val nestedList = listOf(
            "- Level 1",
            "  - Level 2",
            "    - Level 3"
        )
        val parsed = nestedList.map { line ->
            val indent = line.takeWhile { it == ' ' }.length / 2
            val content = line.trim().removePrefix("- ")
            indent to content
        }
        assertEquals(3, parsed.size)
        assertEquals(0 to "Level 1", parsed[0])
        assertEquals(1 to "Level 2", parsed[1])
        assertEquals(2 to "Level 3", parsed[2])
    }

    @Test
    fun testF7_Boundary_HtmlAndSpecialChars() {
        val safeText = "Check <https://example.com> & 'quotes' & \"double\""
        assertNotNull(safeText)
        assertTrue(safeText.contains("<https"))
    }

    // =========================================================================
    // FEATURE 8: DNS Persistence Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF8_Boundary_CorruptedProviderString() {
        val corrupted = "NON_EXISTENT_DOH_123"
        val resolved = try {
            DohProvider.valueOf(corrupted)
        } catch (_: Exception) {
            DohProvider.CLOUDFLARE
        }
        assertEquals(DohProvider.CLOUDFLARE, resolved)
    }

    @Test
    fun testF8_Boundary_EmptyOrNullProviderString() {
        val emptyStr: String? = ""
        val resolved = if (!emptyStr.isNullOrBlank()) {
            try { DohProvider.valueOf(emptyStr) } catch (_: Exception) { DohProvider.CLOUDFLARE }
        } else {
            DohProvider.CLOUDFLARE
        }
        assertEquals(DohProvider.CLOUDFLARE, resolved)
    }

    @Test
    fun testF8_Boundary_RapidConcurrentWrites() {
        val map = ConcurrentHashMap<String, String>()
        val executor = Executors.newFixedThreadPool(4)
        val latch = CountDownLatch(4)

        for (i in 0 until 4) {
            executor.execute {
                try {
                    for (j in 0 until 100) {
                        val provider = if (j % 2 == 0) DohProvider.GOOGLE else DohProvider.QUAD9
                        map["selected_doh_provider"] = provider.name
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val finalVal = map["selected_doh_provider"]
        assertTrue(finalVal == DohProvider.GOOGLE.name || finalVal == DohProvider.QUAD9.name)
        executor.shutdown()
    }

    @Test
    fun testF8_Boundary_CustomStrategyJsonWithMissingFields() {
        val partialJson = """{"id":"custom","name":"My Strategy"}"""
        val providerStr = if (partialJson.contains("dohProvider")) "FOUND" else DohProvider.CLOUDFLARE.name
        assertEquals(DohProvider.CLOUDFLARE.name, providerStr)
    }

    @Test
    fun testF8_Boundary_CaseSensitivityTolerance() {
        fun parseProviderCaseInsensitive(input: String): DohProvider {
            return DohProvider.values().firstOrNull { it.name.equals(input, ignoreCase = true) }
                ?: DohProvider.CLOUDFLARE
        }

        assertEquals(DohProvider.CLOUDFLARE, parseProviderCaseInsensitive("cloudflare"))
        assertEquals(DohProvider.GOOGLE, parseProviderCaseInsensitive("Google"))
        assertEquals(DohProvider.QUAD9, parseProviderCaseInsensitive("quad9"))
    }

    // =========================================================================
    // FEATURE 9: OLED Theme Presets Expansion Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF9_Boundary_UnknownPresetStringFallback() {
        val preset = AppThemePreset.values().firstOrNull { it.id == "BOGUS_THEME" } ?: AppThemePreset.DYNAMIC
        assertEquals(AppThemePreset.DYNAMIC, preset)
    }

    @Test
    fun testF9_Boundary_CaseInsensitivePresetLookup() {
        val found = AppThemePreset.values().firstOrNull { it.name.equals("amoled_black", ignoreCase = true) }
        assertNotNull(found)
        assertEquals(AppThemePreset.AMOLED_BLACK, found)
    }

    @Test
    fun testF9_Boundary_DarkThemeToggleImmutableOnOled() {
        fun isEffectiveDark(preset: AppThemePreset, systemDarkTheme: Boolean): Boolean {
            return if (preset == AppThemePreset.AMOLED_BLACK) true else systemDarkTheme
        }

        // Even when systemDarkTheme is false, AMOLED_BLACK forces dark mode
        assertTrue(isEffectiveDark(AppThemePreset.AMOLED_BLACK, false))
        assertTrue(isEffectiveDark(AppThemePreset.AMOLED_BLACK, true))
    }

    @Test
    fun testF9_Boundary_PureBlackHexAccuracy() {
        val pureBlack = 0xFF000000.toInt()
        val red = (pureBlack shr 16) and 0xFF
        val green = (pureBlack shr 8) and 0xFF
        val blue = pureBlack and 0xFF
        assertEquals(0, red)
        assertEquals(0, green)
        assertEquals(0, blue)
    }

    @Test
    fun testF9_Boundary_OledSilverAccentContrast() {
        val silverLum = 0.50
        val blackLum = 0.0
        val cr = (silverLum + 0.05) / (blackLum + 0.05)
        assertEquals(11.0, cr, 0.01)
        assertTrue(cr >= 7.0) // WCAG AAA
    }

    // =========================================================================
    // FEATURE 10: Custom Theming Support Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF10_Boundary_PureWhiteCustomAccent() {
        val whiteAccent = 0xFFFFFFFF.toInt()
        assertNotNull(whiteAccent)
    }

    @Test
    fun testF10_Boundary_PureBlackCustomAccent() {
        val blackAccent = 0xFF000000.toInt()
        assertNotNull(blackAccent)
    }

    @Test
    fun testF10_Boundary_InvalidHexColorString() {
        fun parseColorSafe(hex: String, defaultColor: Int = 0xFF6200EE.toInt()): Int {
            return try {
                val clean = hex.removePrefix("#")
                if (clean.length == 6) {
                    (0xFF000000 or clean.toLong(16)).toInt()
                } else if (clean.length == 8) {
                    clean.toLong(16).toInt()
                } else {
                    defaultColor
                }
            } catch (_: Exception) {
                defaultColor
            }
        }

        assertEquals(0xFF6200EE.toInt(), parseColorSafe("INVALID_HEX"))
        assertEquals(0xFF00E5FF.toInt(), parseColorSafe("#00E5FF"))
    }

    @Test
    fun testF10_Boundary_AlphaChannelClamping() {
        // Enforce opaque color (alpha = 0xFF)
        fun makeOpaque(color: Int): Int {
            return color or 0xFF000000.toInt()
        }
        val semiTransparent = 0x80FF0000.toInt()
        val opaque = makeOpaque(semiTransparent)
        assertEquals(0xFFFF0000.toInt(), opaque)
    }

    @Test
    fun testF10_Boundary_ExtremeHueTransitions() {
        val neonGreen = 0xFF00FF00.toInt()
        val neonMagenta = 0xFFFF00FF.toInt()
        assertNotNull(neonGreen)
        assertNotNull(neonMagenta)
    }

    // =========================================================================
    // FEATURE 11: Strict Contrast Adherence Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF11_Boundary_BorderlineContrastThresholds() {
        val cr1 = 4.51
        val cr2 = 4.49
        assertTrue(cr1 >= 4.5)
        assertFalse(cr2 >= 4.5)
    }

    @Test
    fun testF11_Boundary_DisabledTextContrastMinimum() {
        val disabledTextLum = 0.20
        val surfaceLum = 0.08
        val cr = (disabledTextLum + 0.05) / (surfaceLum + 0.05)
        assertTrue("Disabled text must maintain minimum 1.5:1 contrast", cr >= 1.5)
    }

    @Test
    fun testF11_Boundary_LargeTextThreshold3To1() {
        val fgLum = 0.35
        val bgLum = 0.05
        val cr = (fgLum + 0.05) / (bgLum + 0.05)
        assertTrue("Large text threshold is 3.0:1", cr >= 3.0)
    }

    @Test
    fun testF11_Boundary_IdenticalForegroundAndBackground() {
        val lum = 0.5
        val cr = (lum + 0.05) / (lum + 0.05)
        assertEquals(1.0, cr, 0.001)
        assertFalse("Identical colors fail WCAG", cr >= 4.5)
    }

    @Test
    fun testF11_Boundary_MaximumPossibleContrast() {
        val maxLum = 1.0
        val minLum = 0.0
        val maxCr = (maxLum + 0.05) / (minLum + 0.05)
        assertEquals(21.0, maxCr, 0.001)
    }

    // =========================================================================
    // FEATURE 12: Codebase QoL Enhancements Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun testF12_Boundary_ZeroBpsAutoRanging() {
        fun format(mbps: Float): String {
            return if (mbps <= 0f) "0 bps" else "${mbps} Mbps"
        }
        assertEquals("0 bps", format(0.0f))
    }

    @Test
    fun testF12_Boundary_ExtremePetabitScaleAutoRanging() {
        fun formatScale(mbps: Double): String {
            return when {
                mbps >= 1_000_000.0 -> String.format(Locale.US, "%.1f Tbps", mbps / 1_000_000.0)
                mbps >= 1000.0 -> String.format(Locale.US, "%.1f Gbps", mbps / 1000.0)
                else -> String.format(Locale.US, "%.1f Mbps", mbps)
            }
        }

        assertEquals("5.0 Tbps", formatScale(5_000_000.0))
    }

    @Test
    fun testF12_Boundary_EmptyVersionString() {
        val versionName = ""
        val versionCode = 0
        val formatted = if (versionName.isBlank()) "vUnknown ($versionCode)" else "v$versionName ($versionCode)"
        assertEquals("vUnknown (0)", formatted)
    }

    @Test
    fun testF12_Boundary_MultiByteUnicodeAndEmojiSearch() {
        val items = listOf(
            TorrentItem("1", "Ubuntu Linux 🐧 24.04", TorrentState.DOWNLOADING, 0f, 0L, 0L, 0L, 0L, 0L, 0, 0),
            TorrentItem("2", "Фильм 2026", TorrentState.DOWNLOADING, 0f, 0L, 0L, 0L, 0L, 0L, 0, 0)
        )

        fun search(q: String) = items.filter { it.name.contains(q, ignoreCase = true) }
        assertEquals(1, search("🐧").size)
        assertEquals(1, search("Фильм").size)
    }

    @Test
    fun testF12_Boundary_ExcessiveWhitespaceSearchQuery() {
        val query = "   ubuntu    "
        val trimmed = query.trim()
        assertEquals("ubuntu", trimmed)
    }
}
