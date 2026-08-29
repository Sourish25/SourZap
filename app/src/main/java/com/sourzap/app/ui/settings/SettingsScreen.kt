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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveChip
import com.sourzap.app.ui.components.SegmentedPillSwitch
import com.sourzap.app.ui.theme.AppThemePreset

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
                Column {
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

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
                    // Color Palette Chooser
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

                        Spacer(modifier = Modifier.height(12.dp))

                        val presets = AppThemePreset.values().toList()
                        SegmentedPillSwitch(
                            items = presets.take(3),
                            selectedItem = presets.firstOrNull { it.id == themePreset } ?: AppThemePreset.DYNAMIC,
                            itemLabel = {
                                when (it.id) {
                                    "DYNAMIC" -> "Dynamic Monet"
                                    "ELECTRIC_INDIGO" -> "Indigo"
                                    else -> "Mint"
                                }
                            },
                            onItemSelected = { settingsRepo.setThemePreset(it.id) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        SegmentedPillSwitch(
                            items = presets.drop(3),
                            selectedItem = presets.firstOrNull { it.id == themePreset } ?: AppThemePreset.DYNAMIC,
                            itemLabel = {
                                when (it.id) {
                                    "BERRY_EXPRESSIVE" -> "Berry"
                                    "SUNSET_TERRACOTTA" -> "Sunset"
                                    else -> "Oceanic"
                                }
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
                        Spacer(modifier = Modifier.height(8.dp))

                        SegmentedPillSwitch(
                            items = listOf("SYSTEM", "DARK", "LIGHT"),
                            selectedItem = darkModePref,
                            itemLabel = {
                                when (it) {
                                    "SYSTEM" -> "Follow System"
                                    "DARK" -> "Dark Mode"
                                    else -> "Light Mode"
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
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Direct connectivity for Chromecast, printers & home LAN",
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

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
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Automatically activates DPI desync upon device restart",
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = autoConnect,
                            onCheckedChange = { settingsRepo.setAutoConnect(it) }
                        )
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
                            text = "SourZap v1.0.0",
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )

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
}