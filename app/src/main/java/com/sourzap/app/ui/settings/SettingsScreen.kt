package com.sourzap.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.sourzap.app.ui.components.MarkdownText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.AppInfo
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.data.repository.AppListHelper
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveChip
import com.sourzap.app.ui.components.ExpressiveWavyProgressIndicator
import com.sourzap.app.ui.components.SegmentedPillSwitch
import com.sourzap.app.ui.theme.AppThemePreset
import com.sourzap.app.update.UpdateState
import kotlinx.coroutines.launch

/**
 * Settings Navigation Hierarchy
 */
enum class SettingsPage {
    MAIN,
    APPEARANCE,
    NETWORK,
    DNS,
    UPDATES,
    ABOUT
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = SourZapApp.instance
    val settingsRepo = app.settingsRepository
    val strategyRepo = app.strategyRepository

    val bypassLan by settingsRepo.bypassLan.collectAsStateWithLifecycle()
    val autoConnect by settingsRepo.autoConnectOnBoot.collectAsStateWithLifecycle()
    val themePreset by settingsRepo.themePreset.collectAsStateWithLifecycle()
    val darkModePref by settingsRepo.darkModePref.collectAsStateWithLifecycle()
    val customPrimary by settingsRepo.customThemePrimary.collectAsStateWithLifecycle()
    val customBgMode by settingsRepo.customThemeBackground.collectAsStateWithLifecycle()
    val disallowedPackages by settingsRepo.disallowedPackages.collectAsStateWithLifecycle()
    val currentStrategy by strategyRepo.currentStrategy.collectAsStateWithLifecycle()

    var currentPage by remember { mutableStateOf(SettingsPage.MAIN) }
    var showAppSheet by remember { mutableStateOf(false) }
    var showCustomThemeSheet by remember { mutableStateOf(false) }

    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var appSearchQuery by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val updateManager = app.updateManager
    val updateState by updateManager.updateState.collectAsStateWithLifecycle()
    val currentAppVersion = com.sourzap.app.BuildConfig.VERSION_NAME

    // Handle system back navigation to return to main settings menu
    BackHandler(enabled = currentPage != SettingsPage.MAIN) {
        currentPage = SettingsPage.MAIN
    }

    LaunchedEffect(showAppSheet) {
        if (showAppSheet && installedApps.isEmpty()) {
            installedApps = AppListHelper.getInstalledLaunchableApps(context, disallowedPackages)
        }
    }

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    val currentThemeObj = AppThemePreset.entries.firstOrNull { it.id == themePreset } ?: AppThemePreset.DYNAMIC

    com.sourzap.app.ui.components.AdaptiveContentContainer(maxWidth = 760.dp) {
        AnimatedContent(
            targetState = currentPage,
            transitionSpec = {
                if (targetState != SettingsPage.MAIN) {
                    (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> -width } + fadeOut()
                    )
                } else {
                    (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                        slideOutHorizontally { width -> width } + fadeOut()
                    )
                }
            },
            label = "SettingsNavTransition"
        ) { targetPage ->
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (targetPage) {
                    SettingsPage.MAIN -> {
                        // Main Settings Header
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Settings",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 32.sp,
                                        letterSpacing = (-1).sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Preferences & System Configuration",
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Category 1: Appearance & Themes
                        item {
                            SettingsCategoryTile(
                                icon = Icons.Rounded.Palette,
                                title = "Appearance & Themes",
                                subtitle = "${currentThemeObj.displayName} • ${
                                    when (darkModePref) {
                                        "DARK" -> "Dark Mode"
                                        "LIGHT" -> "Light Mode"
                                        else -> "System Default"
                                    }
                                }",
                                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentPage = SettingsPage.APPEARANCE
                                }
                            )
                        }

                        // Category 2: Network & Routing
                        item {
                            SettingsCategoryTile(
                                icon = Icons.AutoMirrored.Rounded.AltRoute,
                                title = "Network & Routing",
                                subtitle = if (disallowedPackages.isEmpty()) "All apps tunnelled • LAN Bypass ${if (bypassLan) "On" else "Off"}" else "${disallowedPackages.size} apps bypassed • LAN Bypass ${if (bypassLan) "On" else "Off"}",
                                iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                iconTint = MaterialTheme.colorScheme.onSecondaryContainer,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentPage = SettingsPage.NETWORK
                                }
                            )
                        }

                        // Category 3: DNS & Security
                        item {
                            SettingsCategoryTile(
                                icon = Icons.Rounded.Dns,
                                title = "DNS & Security",
                                subtitle = "${currentStrategy.dohProvider.displayName} • Encrypted DNS",
                                iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                iconTint = MaterialTheme.colorScheme.onTertiaryContainer,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentPage = SettingsPage.DNS
                                }
                            )
                        }

                        // Category 4: Updates & Releases
                        item {
                            SettingsCategoryTile(
                                icon = Icons.Rounded.SystemUpdate,
                                title = "Updates & Releases",
                                subtitle = "SourZap v$currentAppVersion • Check for updates",
                                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                iconTint = MaterialTheme.colorScheme.primary,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentPage = SettingsPage.UPDATES
                                }
                            )
                        }

                        // Category 5: About
                        item {
                            SettingsCategoryTile(
                                icon = Icons.Rounded.Info,
                                title = "About",
                                subtitle = "App info, license & source code",
                                iconContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentPage = SettingsPage.ABOUT
                                }
                            )
                        }
                    }

                    SettingsPage.APPEARANCE -> {
                        // Sub-Page Header with Back Button
                        item {
                            SettingsSubPageHeader(
                                title = "Themes & Display",
                                subtitle = "Color palettes, accent styles, and display modes",
                                onBackClick = { currentPage = SettingsPage.MAIN }
                            )
                        }

                        // Theme Mode Selector Card
                        item {
                            ExpressiveCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                backgroundColor = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "Theme Mode",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Choose light, dark, or follow system default mode",
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    SegmentedPillSwitch(
                                        items = listOf("SYSTEM", "DARK", "LIGHT"),
                                        selectedItem = darkModePref,
                                        itemLabel = {
                                            when (it) {
                                                "SYSTEM" -> "System"
                                                "DARK" -> "Dark"
                                                else -> "Light"
                                            }
                                        },
                                        onItemSelected = { settingsRepo.setDarkModePref(it) }
                                    )
                                }
                            }
                        }

                        // Color Palettes Grid Card
                        item {
                            ExpressiveCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(28.dp),
                                backgroundColor = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Text(
                                        text = "Color Palettes",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Choose a color palette or multi-tone theme",
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 12.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    val allPresets = AppThemePreset.entries

                                    if (isTablet) {
                                        allPresets.chunked(2).forEach { pair ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                pair.forEach { preset ->
                                                    Box(modifier = Modifier.weight(1f)) {
                                                        ThemeSwatchCard(
                                                            preset = preset,
                                                            isSelected = preset.id == themePreset,
                                                            customPrimary = customPrimary,
                                                            customBgMode = customBgMode,
                                                            onClick = {
                                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                settingsRepo.setThemePreset(preset.id)
                                                                if (preset == AppThemePreset.CUSTOM) {
                                                                    showCustomThemeSheet = true
                                                                }
                                                            },
                                                            onCustomizeClick = {
                                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                settingsRepo.setThemePreset(preset.id)
                                                                showCustomThemeSheet = true
                                                            }
                                                        )
                                                    }
                                                }
                                                if (pair.size == 1) {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    } else {
                                        allPresets.forEach { preset ->
                                            ThemeSwatchCard(
                                                preset = preset,
                                                isSelected = preset.id == themePreset,
                                                customPrimary = customPrimary,
                                                customBgMode = customBgMode,
                                                onClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    settingsRepo.setThemePreset(preset.id)
                                                    if (preset == AppThemePreset.CUSTOM) {
                                                        showCustomThemeSheet = true
                                                    }
                                                },
                                                onCustomizeClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    settingsRepo.setThemePreset(preset.id)
                                                    showCustomThemeSheet = true
                                                }
                                            )
                                        }
                                    }

                                }
                            }
                        }
                    }

                SettingsPage.NETWORK -> {
                    // Sub-Page Header with Back Button
                    item {
                        SettingsSubPageHeader(
                            title = "Network & Routing",
                            subtitle = "Split Tunneling, LAN routing, and auto-start",
                            onBackClick = { currentPage = SettingsPage.MAIN }
                        )
                    }

                    item {
                        ExpressiveCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            backgroundColor = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                // Split Tunneling Button Tile
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .semantics {
                                            role = Role.Button
                                            contentDescription = "App Bypass Split Tunneling. ${if (disallowedPackages.isEmpty()) "All apps go through VPN" else "${disallowedPackages.size} apps bypassed"}."
                                        }
                                        .clickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            showAppSheet = true
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Apps,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "App Bypass (Split Tunneling)",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (disallowedPackages.isEmpty()) "All apps go through DPI circumvention" else "${disallowedPackages.size} apps connect directly",
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 12.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    ExpressiveChip(
                                        text = "CONFIGURE",
                                        backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                        textColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                // Bypass Local LAN Switch Tile
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Lan,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Bypass Local LAN",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Direct connectivity for Chromecast, printers & home LAN",
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 12.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Switch(
                                        checked = bypassLan,
                                        onCheckedChange = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            settingsRepo.setBypassLan(it)
                                        }
                                    )
                                }

                                // Auto-Connect on Boot Switch Tile
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PowerSettingsNew,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.tertiary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Auto-Connect on Boot",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.5.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "Automatically activates DPI desync upon device restart",
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 12.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Switch(
                                        checked = autoConnect,
                                        onCheckedChange = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            settingsRepo.setAutoConnectOnBoot(it)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                SettingsPage.DNS -> {
                    // Sub-Page Header with Back Button
                    item {
                        SettingsSubPageHeader(
                            title = "DNS & Security",
                            subtitle = "Encrypted DNS-over-HTTPS & Evasion Config",
                            onBackClick = { currentPage = SettingsPage.MAIN }
                        )
                    }

                    item {
                        ExpressiveCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            backgroundColor = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text(
                                    text = "DNS-over-HTTPS (DoH) Provider",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Encrypted DNS prevents your ISP from seeing or blocking the websites and trackers you connect to.",
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                val dohProviders = listOf(
                                    DohProvider.CLOUDFLARE,
                                    DohProvider.GOOGLE,
                                    DohProvider.QUAD9,
                                    DohProvider.ADGUARD
                                )

                                dohProviders.forEach { provider ->
                                    val isSelected = currentStrategy.dohProvider == provider
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(18.dp))
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                                shape = RoundedCornerShape(18.dp)
                                            )
                                            .clickable {
                                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                strategyRepo.setDohProvider(provider)
                                            },
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Dns,
                                                    contentDescription = null,
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Text(
                                                    text = provider.displayName,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    fontSize = 14.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }

                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = "Selected",
                                                        tint = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }


                }

                SettingsPage.UPDATES -> {
                    // Sub-Page Header with Back Button
                    item {
                        SettingsSubPageHeader(
                            title = "Updates & Releases",
                            subtitle = "GitHub Release Check & In-App Installer",
                            onBackClick = { currentPage = SettingsPage.MAIN }
                        )
                    }

                    item {
                        ExpressiveCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            backgroundColor = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Current Installed Version",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "SourZap v$currentAppVersion",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            updateManager.checkForUpdates(currentAppVersion)
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Check Now", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }

                                when (val state = updateState) {
                                    is UpdateState.Checking -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = "Checking GitHub repository for updates...",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    is UpdateState.Available -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer)
                                                .padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "New Update Available",
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 16.sp,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                                ) {
                                                    Text(
                                                        text = "v${state.release.versionName}",
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 12.sp,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            var isChangelogExpanded by remember { mutableStateOf(false) }
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f))
                                                    .padding(10.dp)
                                            ) {
                                                MarkdownText(
                                                    markdown = state.release.releaseNotes,
                                                    textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    maxLines = if (isChangelogExpanded) Int.MAX_VALUE else 6
                                                )
                                                if (state.release.releaseNotes.lines().size > 3) {
                                                    Text(
                                                        text = if (isChangelogExpanded) "Show less ▲" else "View full changelog ▼",
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier
                                                            .clickable { isChangelogExpanded = !isChangelogExpanded }
                                                            .padding(top = 6.dp)
                                                    )
                                                }
                                            }

                                            Button(
                                                onClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    updateManager.startDownload(state.release.apkDownloadUrl)
                                                },
                                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                                shape = RoundedCornerShape(16.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Download,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Download & Install Update", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                            }
                                        }
                                    }

                                    is UpdateState.UpToDate -> {
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Text(
                                                    text = "You are running the latest version of SourZap.",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }

                                    is UpdateState.Downloading -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                                .padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "Downloading APK package...",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "${(state.progress * 100).toInt()}%",
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            ExpressiveWavyProgressIndicator(
                                                progress = state.progress,
                                                color = MaterialTheme.colorScheme.primary,
                                                trackColor = MaterialTheme.colorScheme.surfaceContainer
                                            )
                                        }
                                    }

                                    is UpdateState.ReadyToInstall -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer)
                                                .padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Text(
                                                text = "Package ready for installation",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )

                                            Button(
                                                onClick = {
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    updateManager.installApk(state.apkFile)
                                                },
                                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                                shape = RoundedCornerShape(16.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = MaterialTheme.colorScheme.primary,
                                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.CheckCircle,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Install Update", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                            }
                                        }
                                    }

                                    is UpdateState.Error -> {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(MaterialTheme.colorScheme.errorContainer)
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.WarningAmber,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = state.message,
                                                fontSize = 12.5.sp,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    else -> {}
                                }
                            }
                        }
                    }
                }

                SettingsPage.ABOUT -> {
                    // Sub-Page Header with Back Button
                    item {
                        SettingsSubPageHeader(
                            title = "About",
                            subtitle = "App Information & Source Code",
                            onBackClick = { currentPage = SettingsPage.MAIN }
                        )
                    }

                    // Open Source Repository Card
                    item {
                        ExpressiveCard(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(28.dp),
                            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                    Text(
                                        text = "SourZap v$currentAppVersion",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString("v$currentAppVersion (27)"))
                                                android.widget.Toast.makeText(context, "Version copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                    )
                                    ExpressiveChip(
                                        text = "MIT LICENSE",
                                        icon = Icons.Rounded.Code,
                                        backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                                        textColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                Text(
                                    text = "A rootless DPI circumvention utility for Android with customizable themes and granular traffic controls.",
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Sourish25/SourZap"))
                                            context.startActivity(intent)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                            contentDescription = "Open GitHub repository in browser",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "github.com/Sourish25/SourZap",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheet for Custom Theme Editor
    if (showCustomThemeSheet) {
        CustomThemeBottomSheet(
            customPrimary = customPrimary,
            customBgMode = customBgMode,
            onPrimaryChange = { newPrimary ->
                settingsRepo.setCustomThemePrimary(newPrimary)
            },
            onBgModeChange = { newBgMode ->
                settingsRepo.setCustomThemeBackground(newBgMode)
            },
            onDismiss = { showCustomThemeSheet = false }
        )
    }

    // Modal Bottom Sheet for Split Tunneling App Selection
    if (showAppSheet) {

        ModalBottomSheet(
            onDismissRequest = { showAppSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "App Bypass (Split Tunneling)",
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Select apps to connect directly without DPI desynchronization",
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = appSearchQuery,
                    onValueChange = { appSearchQuery = it },
                    placeholder = { Text("Search installed applications...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (appSearchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                appSearchQuery = ""
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(14.dp))

                val filteredApps = installedApps.filter {
                    it.appName.contains(appSearchQuery, ignoreCase = true) ||
                    it.packageName.contains(appSearchQuery, ignoreCase = true)
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (filteredApps.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (installedApps.isEmpty()) "Loading installed applications..." else "No applications match \"$appSearchQuery\"",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(filteredApps, key = { it.packageName }) { appInfo ->
                            val isBypassed = disallowedPackages.contains(appInfo.packageName)

                            ExpressiveCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        settingsRepo.toggleAppBypass(appInfo.packageName)
                                    },
                                shape = RoundedCornerShape(18.dp),
                                backgroundColor = if (isBypassed) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Android,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = appInfo.appName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = appInfo.packageName,
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Switch(
                                        checked = isBypassed,
                                        onCheckedChange = {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            settingsRepo.toggleAppBypass(appInfo.packageName)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

/**
 * Color Palette Preview Card with 4-Segment Visual Swatch Strip
 */
@Composable
private fun ThemeSwatchCard(
    preset: AppThemePreset,
    isSelected: Boolean,
    customPrimary: Long = 0xFFFFD600L,
    customBgMode: String = "OLED",
    onClick: () -> Unit,
    onCustomizeClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    val targetScheme = remember(preset, isDark, customPrimary, customBgMode) {
        com.sourzap.app.ui.theme.getThemeColorScheme(preset, isDark, context, customPrimary, customBgMode)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Title, Subtitle, and Selection Checkmark
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = preset.description,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (isSelected) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // If Custom Preset: show prominent Customize Button
            if (preset == AppThemePreset.CUSTOM) {
                OutlinedButton(
                    onClick = { onCustomizeClick?.invoke() },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Customize Palette & Canvas",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }


            // Visual 4-Color Swatch Strip (Primary, Secondary, Surface, Background)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(10.dp)
                    )
            ) {
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .background(targetScheme.primary)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(targetScheme.secondary)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(targetScheme.surfaceContainerHighest)
                )
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .background(targetScheme.background)
                )
            }
        }
    }
}

/**
 * Clean, Ergonomic Category Tile for Main Settings Menu
 */
@Composable
private fun SettingsCategoryTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconContainerColor: Color,
    iconTint: Color,
    onClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    ExpressiveCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
        shape = RoundedCornerShape(22.dp),
        backgroundColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Navigate to $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Sub-Page Header with Back Arrow Button
 */
@Composable
private fun SettingsSubPageHeader(
    title: String,
    subtitle: String,
    onBackClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onBackClick()
            },
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back to Settings",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Black,
                fontSize = 24.sp,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Interactive Material 3 Custom Theme Customizer Bottom Sheet.
 * Enables users to customize their primary accent color (curated swatches, hue slider, hex input)
 * and choose their canvas background (OLED pitch black, dark slate, clean light, or system auto).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomThemeBottomSheet(
    customPrimary: Long,
    customBgMode: String,
    onPrimaryChange: (Long) -> Unit,
    onBgModeChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val systemInDark = isSystemInDarkTheme()

    val curatedColors = remember {
        listOf(
            0xFFFFD600L to "Electric Lemon",
            0xFF00E5FFL to "Neon Cyan",
            0xFF00E676L to "Cyber Mint",
            0xFFFF007FL to "Hot Magenta",
            0xFF7C4DFFL to "Electric Violet",
            0xFFFF6D00L to "Sunset Flame",
            0xFFFF4081L to "Coral Rose",
            0xFF00FF66L to "Matrix Emerald",
            0xFF2979FFL to "Royal Blue",
            0xFF80D8FFL to "Ice Glacier",
            0xFFFFAB00L to "Golden Amber",
            0xFFFF1744L to "Crimson Ruby",
            0xFF1DE9B6L to "Arctic Teal",
            0xFFAA00FFL to "Deep Orchid",
            0xFFEEEEEEL to "Silver White",
            0xFFA1887FL to "Caramel Mocha"
        )
    }

    var hexText by remember(customPrimary) {
        mutableStateOf(String.format("%06X", customPrimary and 0xFFFFFFL))
    }
    var hexError by remember { mutableStateOf(false) }

    val currentColor = Color(customPrimary)
    val hsv = remember(customPrimary) {
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(currentColor.toArgb(), arr)
        arr
    }
    var currentHue by remember(customPrimary) { mutableStateOf(hsv[0]) }

    val previewScheme = remember(customPrimary, customBgMode, systemInDark) {
        com.sourzap.app.ui.theme.buildCustomColorScheme(currentColor, customBgMode, systemInDark)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Customize Theme",
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Personalize accent color and background canvas",
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Live Interactive Preview Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(22.dp)),
                    color = previewScheme.background,
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Theme Preview",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = previewScheme.onBackground
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = previewScheme.primaryContainer
                            ) {
                                Text(
                                    text = customBgMode,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = previewScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = previewScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(previewScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Palette,
                                        contentDescription = null,
                                        tint = previewScheme.onPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "High-Contrast UI Text",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = previewScheme.onSurface
                                    )
                                    val bgLum = 0.299f * previewScheme.background.red + 0.587f * previewScheme.background.green + 0.114f * previewScheme.background.blue
                                    Text(
                                        text = "Optimized legibility on ${if (bgLum > 0.5f) "light" else "dark"} canvas",
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 12.sp,
                                        color = previewScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {},
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = previewScheme.primary,
                                contentColor = previewScheme.onPrimary
                            )
                        ) {
                            Text(
                                text = "Accent Button (${String.format("#%06X", customPrimary and 0xFFFFFFL)})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                        }
                    }
                }
            }

            // Section 1: Background Style
            item {
                Text(
                    text = "Background Style",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Choose pure pitch black for AMOLED, dark slate, or clean light",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                val bgModes = listOf(
                    "OLED" to "🖤 OLED Black",
                    "DARK_SLATE" to "🌑 Dark Slate",
                    "LIGHT" to "☀️ Clean Light",
                    "SYSTEM" to "🌗 System Auto"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    bgModes.forEach { (modeKey, label) ->
                        val isSelected = customBgMode == modeKey
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onBgModeChange(modeKey)
                                },
                            color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.5.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: Preset Accent Color Swatches
            item {
                Text(
                    text = "Accent Color Palette",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Pick a curated vibrant accent or customize below",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                curatedColors.chunked(8).forEach { rowColors ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        rowColors.forEach { (colorVal, _) ->
                            val isColorSelected = (customPrimary and 0xFFFFFFL) == (colorVal and 0xFFFFFFL)
                            val c = Color(colorVal)
                            val isLight = (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue) > 0.5f

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (isColorSelected) 3.dp else 1.dp,
                                        color = if (isColorSelected) MaterialTheme.colorScheme.onSurface else Color.Black.copy(alpha = 0.25f),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onPrimaryChange(colorVal)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isColorSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Selected",
                                        tint = if (isLight) Color(0xFF121214) else Color(0xFFFFFFFF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            // Section 3: Continuous Hue Rainbow Slider
            item {
                Text(
                    text = "Hue Spectrum Slider",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                                    Color.Blue, Color.Magenta, Color.Red
                                )
                            )
                        )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Slider(
                    value = currentHue,
                    onValueChange = { newHue ->
                        currentHue = newHue
                        val newColorInt = android.graphics.Color.HSVToColor(floatArrayOf(newHue, 0.90f, 0.98f))
                        val newColorLong = (newColorInt.toLong() and 0xFFFFFFFFL)
                        onPrimaryChange(newColorLong)
                    },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = currentColor,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Section 4: Custom Hex Code Input
            item {
                Text(
                    text = "Hex Color Code",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { text ->
                            val clean = text.filter { it.isLetterOrDigit() }.take(6).uppercase()
                            hexText = clean
                            if (clean.length == 6) {
                                try {
                                    val parsed = ("FF" + clean).toLong(16)
                                    hexError = false
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onPrimaryChange(parsed)
                                } catch (_: Throwable) {
                                    hexError = true
                                }
                            } else {
                                hexError = false
                            }
                        },
                        prefix = { Text("#", fontWeight = FontWeight.Bold) },
                        placeholder = { Text("RRGGBB") },
                        isError = hexError,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        trailingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(currentColor)
                                    .border(1.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
                            )
                        }
                    )

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.height(54.dp)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}