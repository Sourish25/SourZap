package com.sourzap.app.ui

import androidx.compose.ui.unit.dp
import com.sourzap.app.ui.components.FloatingDockSpecs
import com.sourzap.app.ui.torrent.TorrentHeaderActionClusterSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit verification suite for Milestone M3 (UI Modernization):
 * Verifies dock item geometry, icon size specifications, TalkBack accessibility descriptions,
 * action cluster state configurations, and visual harmonization parameters.
 */
class TorrentHeaderAndNavigationModernizationTest {

    // =========================================================================
    // SECTION 1: FLOATING NAVIGATION DOCK GEOMETRY & CONTAINER CONSTRAINTS
    // =========================================================================

    @Test
    fun testFloatingDockSpecs_GeometryInvariants() {
        // Outer dock container constraints (strict preservation of bounds)
        assertEquals("Outer dock container height must remain exactly 66dp", 66.dp, FloatingDockSpecs.ContainerHeight)
        assertEquals("Outer dock maximum width must be constrained to 500dp", 500.dp, FloatingDockSpecs.ContainerMaxWidth)
        assertEquals("Dock shape corner radius must be 34dp", 34.dp, FloatingDockSpecs.ContainerCornerRadius)

        // Inner item selection pill geometry
        assertEquals("Selection pill height must be 50dp", 50.dp, FloatingDockSpecs.ItemHeight)
        assertEquals("Selection pill corner radius must be 26dp", 26.dp, FloatingDockSpecs.ItemCornerRadius)

        // Verifying vertical padding / clearance
        val verticalClearance = FloatingDockSpecs.ContainerHeight - FloatingDockSpecs.ItemHeight
        assertEquals("Vertical clearance inside dock must be 16dp (8dp top/bottom)", 16.dp, verticalClearance)
        assertTrue("Item height must strictly fit inside container", FloatingDockSpecs.ItemHeight < FloatingDockSpecs.ContainerHeight)
    }

    @Test
    fun testFloatingDockSpecs_IconAndAnimationSpecs() {
        // Modernized icon sizing: enlarged from 19dp to 26dp
        assertEquals("Dock icon size must be enlarged to 26dp", 26.dp, FloatingDockSpecs.IconSize)
        assertTrue("Icon size must be strictly larger than legacy 19dp", FloatingDockSpecs.IconSize.value > 19f)

        // Spring scale animation targets
        assertEquals("Selected item scale must expand to 1.08f (8% bouncy expansion)", 1.08f, FloatingDockSpecs.SelectedScale, 0.001f)
        assertEquals("Unselected item scale must be resting 1.0f", 1.0f, FloatingDockSpecs.UnselectedScale, 0.001f)

        // Ratio of icon size to selection pill height
        val ratio = FloatingDockSpecs.IconSize.value / FloatingDockSpecs.ItemHeight.value
        assertEquals("Icon must occupy approximately 52% of pill height for optimal touch clearance", 0.52f, ratio, 0.01f)
    }

    // =========================================================================
    // SECTION 2: FLOATING DOCK TALKBACK ACCESSIBILITY & ROUTE INTEGRITY
    // =========================================================================

    @Test
    fun testFloatingDockSpecs_ItemsAndAccessibility() {
        val items = FloatingDockSpecs.DefaultItems
        assertEquals("Floating dock must have exactly 5 destination items", 5, items.size)

        val expectedRoutes = listOf("dashboard", "torrents", "speedtest", "traffic", "settings")
        val actualRoutes = items.map { it.route }
        assertEquals("Dock routes must match navigation graph", expectedRoutes, actualRoutes)

        // Route and label uniqueness
        assertEquals("All routes must be unique", actualRoutes.distinct().size, actualRoutes.size)
        val labels = items.map { it.label }
        assertEquals("All labels must be unique", labels.distinct().size, labels.size)

        // TalkBack accessibility content descriptions
        for (item in items) {
            assertNotNull("Item icon must not be null", item.icon)
            assertTrue("Route must not be empty", item.route.isNotEmpty())
            assertTrue("Label must not be empty", item.label.isNotEmpty())

            val tabDescription = FloatingDockSpecs.itemTabContentDescription(item.label)
            val iconDescription = FloatingDockSpecs.itemIconContentDescription(item.label)

            assertEquals("Tab content description must be '<Label> tab'", "${item.label} tab", tabDescription)
            assertEquals("Icon content description must equal label", item.label, iconDescription)
        }
    }

    // =========================================================================
    // SECTION 3: TORRENT HEADER ACTION CLUSTER GEOMETRY & ICON SIZING
    // =========================================================================

    @Test
    fun testTorrentHeaderActionCluster_DimensionsAndGeometry() {
        // Layered pill height & shield pill width
        assertEquals("Cluster layered pill height must be 44dp", 44.dp, TorrentHeaderActionClusterSpecs.PillHeight)
        assertEquals("Shield pill width must be 48dp", 48.dp, TorrentHeaderActionClusterSpecs.ShieldPillWidth)
        assertEquals("Shield icon size must be 20dp", 20.dp, TorrentHeaderActionClusterSpecs.ShieldIconSize)

        // Playback cluster inner button dimensions
        assertEquals("Pause button size must be 38dp", 38.dp, TorrentHeaderActionClusterSpecs.PauseButtonSize)
        assertEquals("Pause icon size must be 20dp", 20.dp, TorrentHeaderActionClusterSpecs.PauseIconSize)
        assertEquals("Resume circular button size must be 38dp", 38.dp, TorrentHeaderActionClusterSpecs.ResumeButtonSize)
        assertEquals("Resume icon size must be 22dp", 22.dp, TorrentHeaderActionClusterSpecs.ResumeIconSize)

        // Structural fit inside the 44dp layered pill container
        assertTrue("Pause button (38dp) must fit inside 44dp pill", TorrentHeaderActionClusterSpecs.PauseButtonSize < TorrentHeaderActionClusterSpecs.PillHeight)
        assertTrue("Resume button (38dp) must fit inside 44dp pill", TorrentHeaderActionClusterSpecs.ResumeButtonSize < TorrentHeaderActionClusterSpecs.PillHeight)
        assertEquals("Pause and Resume buttons must have symmetrical 38dp size",
            TorrentHeaderActionClusterSpecs.PauseButtonSize,
            TorrentHeaderActionClusterSpecs.ResumeButtonSize
        )
    }

    // =========================================================================
    // SECTION 4: TORRENT HEADER ACTION CLUSTER STATE & COLOR HARMONIZATION
    // =========================================================================

    @Test
    fun testTorrentHeaderActionCluster_ProxyEnabledStateConfiguration() {
        val config = TorrentHeaderActionClusterSpecs.getShieldVisualConfig(proxyEnabled = true)

        assertTrue("Config must reflect proxy enabled", config.isProxyEnabled)
        assertEquals("Proxy enabled background must use full opacity (primaryContainer)", 1.0f, config.backgroundAlpha, 0.001f)
        assertEquals("Proxy enabled border must use full opacity (primary)", 1.0f, config.borderAlpha, 0.001f)
        assertEquals("Proxy enabled icon must use full opacity (primary)", 1.0f, config.iconAlpha, 0.001f)
    }

    @Test
    fun testTorrentHeaderActionCluster_ProxyDisabledStateHarmonization() {
        val config = TorrentHeaderActionClusterSpecs.getShieldVisualConfig(proxyEnabled = false)

        assertFalse("Config must reflect proxy disabled", config.isProxyEnabled)
        assertEquals("Proxy disabled background must use 0.12f primary alpha", 0.12f, config.backgroundAlpha, 0.001f)
        assertEquals("Proxy disabled border must use 0.35f primary alpha", 0.35f, config.borderAlpha, 0.001f)
        assertEquals("Proxy disabled icon must use 0.8f primary alpha", 0.8f, config.iconAlpha, 0.001f)
    }

    // =========================================================================
    // SECTION 5: TORRENT HEADER ACTION CLUSTER ACCESSIBILITY DESCRIPTIONS
    // =========================================================================

    @Test
    fun testTorrentHeaderActionCluster_AccessibilityDescriptions() {
        assertEquals(
            "Shield icon must have 'Torrent Proxy Settings' accessibility description",
            "Torrent Proxy Settings",
            TorrentHeaderActionClusterSpecs.ShieldContentDescription
        )
        assertEquals(
            "Pause button must have 'Pause All' accessibility description",
            "Pause All",
            TorrentHeaderActionClusterSpecs.PauseAllContentDescription
        )
        assertEquals(
            "Resume button must have 'Resume All' accessibility description",
            "Resume All",
            TorrentHeaderActionClusterSpecs.ResumeAllContentDescription
        )
    }
}
