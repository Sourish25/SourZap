package com.sourzap.app.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ScallopedBadge
import com.sourzap.app.ui.components.SegmentedPillSwitch
import com.sourzap.app.ui.theme.CyanSpark
import com.sourzap.app.ui.theme.DarkBackground
import com.sourzap.app.ui.theme.DarkSurfaceContainer
import com.sourzap.app.ui.theme.DarkSurfaceContainerHigh
import com.sourzap.app.ui.theme.DarkSurfaceContainerHighest
import com.sourzap.app.ui.theme.ElectricViolet
import com.sourzap.app.ui.theme.ExpressiveShapes
import com.sourzap.app.ui.theme.NeonMint
import com.sourzap.app.ui.theme.SunbeamYellow
import com.sourzap.app.ui.theme.TextPrimary
import com.sourzap.app.ui.theme.TextSecondary
import com.sourzap.app.ui.theme.TextTertiary

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val app = SourZapApp.instance
    val settingsRepo = app.settingsRepository

    val bypassLan by settingsRepo.bypassLan.collectAsState()
    val autoConnect by settingsRepo.autoConnectOnBoot.collectAsState()
    val themeMode by settingsRepo.themeMode.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Settings",
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            letterSpacing = (-1).sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ScallopedBadge(
                            text = "⚙️ SYSTEM",
                            backgroundColor = ElectricViolet,
                            textColor = TextPrimary
                        )
                    }
                    Text(
                        text = "App routing, VPN rules & theme configuration",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = TextTertiary
                    )
                }
            }
        }

        // Routing Rules
        item {
            Text(
                text = "NETWORK & ROUTING RULES",
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = ElectricViolet
            )
        }

        item {
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.AsymmetricPillLarge,
                backgroundColor = DarkSurfaceContainer,
                borderColor = DarkSurfaceContainerHighest
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lan,
                                contentDescription = null,
                                tint = NeonMint,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Bypass Local Area Network (LAN)",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Allows local devices, Chromecast & printers to connect directly",
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Switch(
                            checked = bypassLan,
                            onCheckedChange = { settingsRepo.setBypassLan(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NeonMint,
                                checkedTrackColor = Color(0xFF005234)
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                tint = SunbeamYellow,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Auto-Connect on Boot",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Automatically activates DPI bypass upon phone restart",
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Switch(
                            checked = autoConnect,
                            onCheckedChange = { settingsRepo.setAutoConnect(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SunbeamYellow,
                                checkedTrackColor = Color(0xFF574100)
                            )
                        )
                    }
                }
            }
        }

        // Theme Palette
        item {
            Text(
                text = "EXPRESSIVE THEME PALETTE",
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = CyanSpark
            )
        }

        item {
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.Squircle,
                backgroundColor = DarkSurfaceContainer,
                borderColor = DarkSurfaceContainerHighest
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Accent Color Mode",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SegmentedPillSwitch(
                        items = listOf("EXPRESSIVE_VIOLET", "NEON_MINT", "CANDY_CORAL"),
                        selectedItem = themeMode,
                        itemLabel = {
                            when (it) {
                                "EXPRESSIVE_VIOLET" -> "⚡ Violet"
                                "NEON_MINT" -> "🌱 Mint"
                                else -> "🍭 Coral"
                            }
                        },
                        onItemSelected = { settingsRepo.setThemeMode(it) }
                    )
                }
            }
        }

        // About & Open Source
        item {
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.AsymmetricPillInverse,
                backgroundColor = DarkSurfaceContainer,
                borderColor = DarkSurfaceContainerHighest
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = ElectricViolet,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "SourZap v1.0.0",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                color = TextPrimary
                            )
                        }

                        ScallopedBadge(
                            text = "OPEN SOURCE",
                            backgroundColor = ElectricViolet,
                            textColor = TextPrimary,
                            numPetals = 8
                        )
                    }

                    Text(
                        text = "A rootless implementation of Zapret DPI evasion algorithms for Android, built with Google Material You 3 Expressive design architecture.",
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Text(
                        text = "Techniques: TLS Split, Multisplit, Fake SNI, Disorder, DoH & HTTP Casing Mod.",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = CyanSpark
                    )
                }
            }
        }
    }
}