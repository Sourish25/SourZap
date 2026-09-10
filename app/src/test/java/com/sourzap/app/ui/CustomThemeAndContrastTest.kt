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
}
