package com.sourzap.app.ui

import androidx.compose.ui.graphics.Color
import com.sourzap.app.ui.theme.buildCustomColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemeAndContrastTest {

    @Test
    fun testOledBlackCanvasGeneration() {
        val yellowAccent = Color(0xFFFFD600)
        val scheme = buildCustomColorScheme(
            primaryColor = yellowAccent,
            bgMode = "OLED",
            systemInDark = true
        )

        // OLED mode must produce pure #000000 background and surface
        assertEquals(Color(0xFF000000), scheme.background)
        assertEquals(Color(0xFF000000), scheme.surface)
        // Text on dark/OLED must be crisp white
        assertEquals(Color(0xFFFFFFFF), scheme.onBackground)
        assertEquals(Color(0xFFFFFFFF), scheme.onSurface)
    }

    @Test
    fun testDarkSlateCanvasGeneration() {
        val cyanAccent = Color(0xFF00E5FF)
        val scheme = buildCustomColorScheme(
            primaryColor = cyanAccent,
            bgMode = "DARK_SLATE",
            systemInDark = false
        )

        // Dark Slate mode must produce dark slate background and white text
        assertEquals(Color(0xFF101216), scheme.background)
        assertEquals(Color(0xFF101216), scheme.surface)
        assertEquals(Color(0xFFFFFFFF), scheme.onBackground)
        assertEquals(Color(0xFFFFFFFF), scheme.onSurface)
    }

    @Test
    fun testCleanLightCanvasGeneration() {
        val emeraldAccent = Color(0xFF00E676)
        val scheme = buildCustomColorScheme(
            primaryColor = emeraldAccent,
            bgMode = "LIGHT",
            systemInDark = true // even if system is dark, user forced LIGHT
        )

        // Light mode must produce clean off-white background and dark text
        assertEquals(Color(0xFFF8F9FA), scheme.background)
        assertEquals(Color(0xFFFFFFFF), scheme.surface)
        assertEquals(Color(0xFF191C1E), scheme.onBackground)
        assertEquals(Color(0xFF191C1E), scheme.onSurface)
    }

    @Test
    fun testHighContrastButtonTextCalculation() {
        // Bright Yellow / Lemon has high luminance (> 0.5) -> button text must be dark
        val brightYellow = Color(0xFFFFD600)
        val yellowScheme = buildCustomColorScheme(
            primaryColor = brightYellow,
            bgMode = "OLED",
            systemInDark = true
        )
        assertEquals("Light accent must yield dark text for maximum contrast", Color(0xFF121214), yellowScheme.onPrimary)

        // Dark Navy / Midnight Blue has low luminance (< 0.5) -> button text must be white
        val deepNavy = Color(0xFF0D1B2A)
        val navyScheme = buildCustomColorScheme(
            primaryColor = deepNavy,
            bgMode = "OLED",
            systemInDark = true
        )
        assertEquals("Dark accent must yield white text for maximum contrast", Color(0xFFFFFFFF), navyScheme.onPrimary)
    }

    @Test
    fun testCustomSchemeContainerAndVariantContrast() {
        val magentaAccent = Color(0xFFFF007F)
        val scheme = buildCustomColorScheme(
            primaryColor = magentaAccent,
            bgMode = "DARK_SLATE",
            systemInDark = true
        )

        // Ensure primary container has non-zero alpha and contrast
        assertTrue(scheme.primaryContainer.alpha > 0f)
        assertEquals(Color(0xFFFFFFFF), scheme.onPrimaryContainer)
        assertEquals(Color(0xFFC4C7D0), scheme.onSurfaceVariant)
    }

    @Test
    fun testPerceptualLuminanceCalculations() {
        // Pure White -> 1.0
        val whiteLum = com.sourzap.app.ui.theme.ContrastEngine.calculatePerceptualLuminance(Color(1f, 1f, 1f))
        assertEquals(1.0f, whiteLum, 0.001f)

        // Pure Black -> 0.0
        val blackLum = com.sourzap.app.ui.theme.ContrastEngine.calculatePerceptualLuminance(Color(0f, 0f, 0f))
        assertEquals(0.0f, blackLum, 0.001f)

        // Pure Yellow -> 0.299 + 0.587 = 0.886
        val yellowLum = com.sourzap.app.ui.theme.ContrastEngine.calculatePerceptualLuminance(Color(1f, 1f, 0f))
        assertEquals(0.886f, yellowLum, 0.001f)

        // Pure Blue -> 0.114
        val blueLum = com.sourzap.app.ui.theme.ContrastEngine.calculatePerceptualLuminance(Color(0f, 0f, 1f))
        assertEquals(0.114f, blueLum, 0.001f)
    }

    @Test
    fun testThresholdBoundaryContrastDecision() {
        // Just above 0.5 luminance threshold -> must be dark text #121214
        val justAbove = Color(0f, 0.87f, 0f)
        assertTrue(com.sourzap.app.ui.theme.ContrastEngine.calculatePerceptualLuminance(justAbove) > 0.5f)
        assertEquals(Color(0xFF121214), com.sourzap.app.ui.theme.ContrastEngine.getHighContrastContentColor(justAbove))

        // Just below 0.5 luminance threshold -> must be white text #FFFFFF
        val justBelow = Color(0f, 0.83f, 0f)
        assertTrue(com.sourzap.app.ui.theme.ContrastEngine.calculatePerceptualLuminance(justBelow) < 0.5f)
        assertEquals(Color(0xFFFFFFFF), com.sourzap.app.ui.theme.ContrastEngine.getHighContrastContentColor(justBelow))
    }

    @Test
    fun testSystemCanvasAdaptiveMode() {
        val accent = Color(0xFF00E5FF)

        // System dark -> dark slate canvas with white text
        val darkSystemScheme = buildCustomColorScheme(accent, bgMode = "SYSTEM", systemInDark = true)
        assertEquals(Color(0xFF101216), darkSystemScheme.background)
        assertEquals(Color(0xFFFFFFFF), darkSystemScheme.onBackground)
        assertEquals(Color(0xFFFFFFFF), darkSystemScheme.onSurface)

        // System light -> light canvas with dark text
        val lightSystemScheme = buildCustomColorScheme(accent, bgMode = "SYSTEM", systemInDark = false)
        assertEquals(Color(0xFFF8F9FA), lightSystemScheme.background)
        assertEquals(Color(0xFF191C1E), lightSystemScheme.onBackground)
        assertEquals(Color(0xFF191C1E), lightSystemScheme.onSurface)
    }

    @Test
    fun testWcagContrastRatioAndAaCompliance() {
        // Pure black on pure white has 21:1 contrast ratio
        val ratio = com.sourzap.app.ui.theme.ContrastEngine.calculateWcagContrastRatio(Color.Black, Color.White)
        assertEquals(21.0f, ratio, 0.1f)
        assertTrue(com.sourzap.app.ui.theme.ContrastEngine.isWcagAaCompliant(Color.Black, Color.White))

        // On OLED canvas (pure black), white text has maximum contrast
        val oledTextRatio = com.sourzap.app.ui.theme.ContrastEngine.calculateWcagContrastRatio(Color(0xFFFFFFFF), Color(0xFF000000))
        assertTrue("OLED onBackground contrast ratio must exceed 7:1", oledTextRatio >= 7.0f)
        assertTrue(com.sourzap.app.ui.theme.ContrastEngine.isWcagAaCompliant(Color(0xFFFFFFFF), Color(0xFF000000)))

        // On Light canvas (#F8F9FA), dark text (#191C1E) has high contrast
        val lightTextRatio = com.sourzap.app.ui.theme.ContrastEngine.calculateWcagContrastRatio(Color(0xFF191C1E), Color(0xFFF8F9FA))
        assertTrue("Light onBackground contrast ratio must exceed 7:1", lightTextRatio >= 7.0f)
        assertTrue(com.sourzap.app.ui.theme.ContrastEngine.isWcagAaCompliant(Color(0xFF191C1E), Color(0xFFF8F9FA)))
    }

    @Test
    fun testCuratedPaletteHighContrastCompliance() {
        val palette = listOf(
            0xFFFFD600L, // Electric Lemon
            0xFF00E5FFL, // Neon Cyan
            0xFF00E676L, // Cyber Mint
            0xFFFF007FL, // Hot Magenta
            0xFF7C4DFFL, // Electric Violet
            0xFFFF6D00L, // Sunset Flame
            0xFFFF4081L, // Coral Rose
            0xFF00FF66L, // Matrix Emerald
            0xFF2979FFL, // Royal Blue
            0xFF80D8FFL, // Ice Glacier
            0xFFFFAB00L, // Golden Amber
            0xFFFF1744L, // Crimson Ruby
            0xFF1DE9B6L, // Arctic Teal
            0xFFAA00FFL, // Deep Orchid
            0xFFEEEEEEL, // Silver White
            0xFFA1887FL  // Caramel Mocha
        )

        for (colorVal in palette) {
            val c = Color(colorVal)
            val contentColor = com.sourzap.app.ui.theme.ContrastEngine.getHighContrastContentColor(c)
            assertTrue(
                "Content color must be either #121214 or #FFFFFF for high contrast",
                contentColor == Color(0xFF121214) || contentColor == Color(0xFFFFFFFF)
            )

            val ratio = com.sourzap.app.ui.theme.ContrastEngine.calculateWcagContrastRatio(contentColor, c)
            assertTrue(
                "Contrast ratio for color 0x${colorVal.toString(16)} with content $contentColor must be >= 3.0:1 (actual: $ratio)",
                ratio >= 3.0f
            )
        }
    }

    @Test
    fun testHexColorNormalizationAndParsing() {
        val engine = com.sourzap.app.ui.theme.ContrastEngine

        // Standard 6-character hex with and without #
        assertEquals("FFD600", engine.normalizeHexColor("#FFD600"))
        assertEquals("FFD600", engine.normalizeHexColor("FFD600"))
        assertEquals("00E5FF", engine.normalizeHexColor("#00e5ff"))
        assertEquals(0xFFFFD600L, engine.parseHexColor("#FFD600"))
        assertEquals(0xFF00E5FFL, engine.parseHexColor("00e5ff"))

        // 8-character ARGB from Android resources / Color picker (#AARRGGBB)
        assertEquals("FFD600", engine.normalizeHexColor("#FFFFD600"))
        assertEquals(0xFFFFD600L, engine.parseHexColor("#FFFFD600"))

        // 8-character RGBA from Figma / CSS (#RRGGBBAA)
        assertEquals("00E5FF", engine.normalizeHexColor("#00E5FFFF"))
        assertEquals(0xFF00E5FFL, engine.parseHexColor("#00E5FFFF"))

        // 0x prefix from Kotlin / Java code (0xFFFFD600 and 0xFFD600)
        assertEquals("FFD600", engine.normalizeHexColor("0xFFFFD600"))
        assertEquals(0xFFFFD600L, engine.parseHexColor("0xFFFFD600"))
        assertEquals("FFD600", engine.normalizeHexColor("0xFFD600"))
        assertEquals(0xFFFFD600L, engine.parseHexColor("0xFFD600"))

        // 3-character CSS shorthand (#RGB -> #RRGGBB)
        assertEquals("FFFFFF", engine.normalizeHexColor("#FFF"))
        assertEquals("FF00AA", engine.normalizeHexColor("#F0A"))
        assertEquals(0xFFFFFFFFL, engine.parseHexColor("#FFF"))

        // Quoted strings, semicolons, and surrounding whitespace
        assertEquals("FFD600", engine.normalizeHexColor("  \"#FFD600\";  \n"))
        assertEquals("FFD600", engine.normalizeHexColor("'#FFD600'"))
        assertEquals(0xFFFFD600L, engine.parseHexColor("  #FFD600  "))

        // Rich HTML and CSS clipboard snippet extraction
        assertEquals("FFD600", engine.normalizeHexColor("color: #FFD600;"))
        assertEquals("00E5FF", engine.normalizeHexColor("<span style=\"color: #00E5FF\">"))
        assertEquals("FFD600", engine.normalizeHexColor("const accent = 0xFFFFD600;"))

        // CSS rgb() and rgba() formats
        assertEquals("FFD600", engine.normalizeHexColor("rgb(255, 214, 0)"))
        assertEquals(0xFFFFD600L, engine.parseHexColor("rgb(255, 214, 0)"))
        assertEquals("00E5FF", engine.normalizeHexColor("rgba(0, 229, 255, 1.0)"))
        assertEquals(0xFF00E5FFL, engine.parseHexColor("rgba(0, 229, 255, 1.0)"))

        // Out-of-bounds or malformed rgb
        org.junit.Assert.assertNull(engine.normalizeHexColor("rgb(300, 0, 0)"))
        org.junit.Assert.assertNull(engine.normalizeHexColor("rgb(-10, 0, 0)"))
        org.junit.Assert.assertNull(engine.normalizeHexColor("rgb(abc, def, ghi)"))

        // Invalid hex inputs must return null
        org.junit.Assert.assertNull(engine.normalizeHexColor("HELLO"))
        org.junit.Assert.assertNull(engine.normalizeHexColor("12345")) // 5 digits
        org.junit.Assert.assertNull(engine.normalizeHexColor("#12345")) // 5 digits with #
        org.junit.Assert.assertNull(engine.normalizeHexColor("1234567")) // 7 digits
        org.junit.Assert.assertNull(engine.normalizeHexColor("#1234567")) // 7 digits with #
        org.junit.Assert.assertNull(engine.normalizeHexColor(""))
        org.junit.Assert.assertNull(engine.normalizeHexColor(null))
        org.junit.Assert.assertNull(engine.parseHexColor("INVALID_HEX"))
    }

    @Test
    fun testWcagAaComplianceNormalAndLargeText() {
        val engine = com.sourzap.app.ui.theme.ContrastEngine

        // 4.5:1 threshold for normal text
        val normalPassFg = Color(0xFFFFFFFF)
        val normalPassBg = Color(0xFF000000)
        assertTrue(engine.isWcagAaCompliant(normalPassFg, normalPassBg, isLargeText = false))

        // Ratio between 3.0:1 and 4.5:1 passes large text but fails normal text
        // E.g., #757575 on #FFFFFF has ~4.6:1, #767676 has ~4.54:1, #888888 has ~3.5:1
        val midGray = Color(0xFF888888)
        val ratio = engine.calculateWcagContrastRatio(Color.White, midGray)
        assertTrue("Ratio must be between 3.0 and 4.5 for midGray vs White (actual: $ratio)", ratio >= 3.0f && ratio < 4.5f)
        assertTrue("Large text passes WCAG AA at >= 3.0:1", engine.isWcagAaCompliant(Color.White, midGray, isLargeText = true))
        org.junit.Assert.assertFalse("Normal text fails WCAG AA when ratio < 4.5:1", engine.isWcagAaCompliant(Color.White, midGray, isLargeText = false))
    }
}
