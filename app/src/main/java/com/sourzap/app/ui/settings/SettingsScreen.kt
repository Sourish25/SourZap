package com.sourzap.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.AppInfo
import com.sourzap.app.data.repository.AppListHelper
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveChip
import com.sourzap.app.ui.components.SegmentedPillSwitch
import com.sourzap.app.ui.theme.AppThemePreset
import com.sourzap.app.update.UpdateState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = SourZapApp.instance
    val settingsRepo = app.settingsRepository

    val bypassLan by settingsRepo.bypassLan.collectAsState()
    val autoConnect by settingsRepo.autoConnectOnBoot.collectAsState()
    val themePreset by settingsRepo.themePreset.collectAsState()
    val darkModePref by settingsRepo.darkModePref.collectAsState()
    val disallowedPackages by settingsRepo.disallowedPackages.collectAsState()

    var showAppSheet by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var appSearchQuery by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val updateManager = app.updateManager
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val currentAppVersion = com.sourzap.app.BuildConfig.VERSION_NAME

    LaunchedEffect(showAppSheet) {
        if (showAppSheet && installedApps.isEmpty()) {
            installedApps = AppListHelper.getInstalledLaunchableApps(context, disallowedPackages)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        text = "Settings",
                        fontWeight = FontWeight.Black,
                        fontSize = 32.sp,
                        letterSpacing = (-1).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Theme, Preferences & Routing",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                ExpressiveChip(
                    text = "PREFS",
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // Material You Theme Customizer
        item {
            Text(
                text = "MATERIAL YOU THEME SYSTEM",
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.primary
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
                    // Color Palette Chooser (2 per row for generous boundary fitting)
                    Column {
                        Text(
                            text = "Color Palette",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Dynamic Monet extracts colors directly from your wallpaper",
                            fontWeight = FontWeight.Normal,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        val presets = AppThemePreset.values().toList()
                        val currentActivePreset = presets.firstOrNull { it.id == themePreset } ?: AppThemePreset.DYNAMIC

                        // Row 1: Dynamic Monet & Indigo
                        SegmentedPillSwitch(
                            items = listOf(AppThemePreset.DYNAMIC, AppThemePreset.ELECTRIC_INDIGO),
                            selectedItem = currentActivePreset,
                            itemLabel = {
                                if (it == AppThemePreset.DYNAMIC) "Dynamic Monet" else "Electric Indigo"
                            },
                            onItemSelected = { settingsRepo.setThemePreset(it.id) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Row 2: Fresh Mint & Berry Vivid
                        SegmentedPillSwitch(
                            items = listOf(AppThemePreset.CYBER_MINT, AppThemePreset.BERRY_EXPRESSIVE),
                            selectedItem = currentActivePreset,
                            itemLabel = {
                                if (it == AppThemePreset.CYBER_MINT) "Fresh Mint" else "Berry Vivid"
                            },
                            onItemSelected = { settingsRepo.setThemePreset(it.id) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Row 3: Sunset Glow & Oceanic Cyan
                        SegmentedPillSwitch(
                            items = listOf(AppThemePreset.SUNSET_TERRACOTTA, AppThemePreset.OCEANIC_CYAN),
                            selectedItem = currentActivePreset,
                            itemLabel = {
                                if (it == AppThemePreset.SUNSET_TERRACOTTA) "Sunset Glow" else "Oceanic Cyan"
                            },
                            onItemSelected = { settingsRepo.setThemePreset(it.id) }
                        )
                    }

                    // Dark / Light / System Mode Chooser
                    Column {
                        Text(
                            text = "Theme Mode",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))

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
        }

        // Routing Rules Large Tiles
        item {
            Text(
                text = "NETWORK & ROUTING",
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.primary
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
                    // Split Tunneling App Bypass Button Tile
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAppSheet = true },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App Bypass (Split Tunneling)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (disallowedPackages.isEmpty()) "All apps go through DPI circumvention" else "${disallowedPackages.size} app(s) bypass the VPN",
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        ExpressiveChip(
                            text = if (disallowedPackages.isEmpty()) "CONFIGURE" else "${disallowedPackages.size} BYPASSED",
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bypass Local LAN",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Direct connectivity for Chromecast, printers & home LAN",
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Switch(
                            checked = bypassLan,
                            onCheckedChange = { settingsRepo.setBypassLan(it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Connect on Boot",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Automatically activates DPI desync upon device restart",
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Switch(
                            checked = autoConnect,
                            onCheckedChange = { settingsRepo.setAutoConnect(it) }
                        )
                    }
                }
            }
        }

        // In-App Updates Card (Open-Source Live Updater)
        item {
            Text(
                text = "IN-APP UPDATES & RELEASES",
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.primary
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
                                text = "SourZap v$currentAppVersion",
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Automatic GitHub Release Updater",
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    updateManager.checkForUpdates(currentAppVersion).collect {
                                        updateState = it
                                    }
                                }
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text("Check Now", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                        }
                    }

                    // Update States
                    when (val state = updateState) {
                        is UpdateState.Checking -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Checking GitHub for latest release...",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        is UpdateState.UpToDate -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = "✨ You are on the latest version of SourZap!",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        is UpdateState.Available -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🚀 New Version: ${state.release.tagName}",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    val sizeMb = String.format("%.1f MB", state.release.apkSizeBytes / (1024f * 1024f))
                                    ExpressiveChip(
                                        text = sizeMb,
                                        backgroundColor = MaterialTheme.colorScheme.primary,
                                        textColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                }

                                Text(
                                    text = state.release.releaseNotes,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 4
                                )

                                Button(
                                    onClick = {
                                        scope.launch {
                                            updateManager.downloadAndPrepareApk(state.release.apkDownloadUrl).collect {
                                                updateState = it
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text("Download & Install Update", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }
                            }
                        }

                        is UpdateState.Downloading -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Downloading update...",
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

                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp)),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                )

                                val dlMb = String.format("%.1f", state.downloadedBytes / (1024f * 1024f))
                                val totMb = String.format("%.1f", state.totalBytes / (1024f * 1024f))
                                Text(
                                    text = "$dlMb MB / $totMb MB",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        is UpdateState.ReadyToInstall -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "🎉 Update package downloaded & verified!",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )

                                Button(
                                    onClick = { updateManager.installApk(state.apkFile) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                ) {
                                    Text("Install Update Now", fontWeight = FontWeight.Black, fontSize = 14.sp)
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
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "⚠️ ${state.message}",
                                    fontSize = 12.sp,
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

        // About & Open Source Tile
        item {
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
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
                        Text(
                            text = "SourZap v${com.sourzap.app.BuildConfig.VERSION_NAME}",
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        ExpressiveChip(
                            text = "OPEN SOURCE",
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Text(
                        text = "A rootless implementation of Zapret DPI circumvention for Android, built with Google Material You 3 Expressive design architecture.",
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Sourish25/SourZap"))
                                context.startActivity(intent)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "github.com/Sourish25/SourZap",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
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
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
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
                    items(filteredApps) { appInfo ->
                        val isBypassed = disallowedPackages.contains(appInfo.packageName)

                        ExpressiveCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { settingsRepo.toggleAppBypass(appInfo.packageName) },
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
                                    onCheckedChange = { settingsRepo.toggleAppBypass(appInfo.packageName) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}