package com.sourzap.app.ui.strategies

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.speedtest.DpiProbeEngine
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveChip
import com.sourzap.app.ui.components.SegmentedPillSwitch
import kotlinx.coroutines.launch

@Composable
fun StrategiesScreen(
    modifier: Modifier = Modifier
) {
    val app = SourZapApp.instance
    val strategyRepo = app.strategyRepository

    val currentStrategy by strategyRepo.currentStrategy.collectAsState()
    val customStrategy by strategyRepo.customStrategy.collectAsState()
    val probeState by DpiProbeEngine.state.collectAsState()
    val scope = rememberCoroutineScope()

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
                        text = "Bypass Engine",
                        fontWeight = FontWeight.Black,
                        fontSize = 32.sp,
                        letterSpacing = (-1).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Zapret DPI Circumvention Modes",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ExpressiveChip(
                    text = "PRESETS",
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // ISP DPI Diagnostic Card
        item {
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "ISP DPI DIAGNOSTIC & AUTO-TUNE",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "Probes your mobile or Wi-Fi ISP filter to recommend the optimal evasion preset.",
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (probeState.isRunning) {
                        LinearProgressIndicator(
                            progress = { probeState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        Text(
                            text = probeState.currentStep,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    probeState.result?.let { result ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(14.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Optimal: ${result.recommendedPreset.name}",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Evasion Latency: ${String.format("%.0f ms", result.latencyMs)} • DPI Evasion: 100% Active",
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (probeState.result != null && !probeState.isRunning) {
                                strategyRepo.selectStrategy(probeState.result!!.recommendedPreset)
                            } else {
                                scope.launch { DpiProbeEngine.runDpiAnalysis() }
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        Text(
                            text = if (probeState.isRunning) "ANALYZING ISP FILTER..." else if (probeState.result != null) "APPLY RECOMMENDED PRESET" else "RUN ISP DIAGNOSTIC PROBE",
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }

        // Section Title
        item {
            Text(
                text = "CURATED PRESETS",
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Preset Large Tiles
        items(BypassStrategy.DEFAULT_PRESETS) { strategy ->
            val isSelected = currentStrategy.id == strategy.id

            ExpressiveCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { strategyRepo.selectStrategy(strategy) },
                shape = RoundedCornerShape(24.dp),
                backgroundColor = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strategy.name,
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = strategy.description,
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        ExpressiveChip(
                            text = if (isSelected) "SELECTED" else strategy.tag,
                            backgroundColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            textColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Technique Breakdown Tags
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (strategy.tlsSplitOffset != 0) {
                            ExpressiveChip(
                                text = if (strategy.tlsSplitOffset == -1) "SNI Split" else "Split Pos ${strategy.tlsSplitOffset}",
                                backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                textColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        if (strategy.fakeSni.isNotEmpty()) {
                            ExpressiveChip(
                                text = "Fake ${strategy.fakeSni.substringBefore(".")}",
                                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                                textColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }

                        if (strategy.useDisorder) {
                            ExpressiveChip(
                                text = "TCP Disorder",
                                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                textColor = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Custom Strategy Configurator
        item {
            Text(
                text = "CUSTOM ENGINE BUILDER",
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        item {
            val isCustomSelected = currentStrategy.isCustom

            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                backgroundColor = if (isCustomSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                borderColor = if (isCustomSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = customStrategy.name,
                                fontWeight = FontWeight.Black,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Tailored packet desync parameters",
                                fontWeight = FontWeight.Normal,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = isCustomSelected,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    strategyRepo.selectStrategy(customStrategy)
                                } else {
                                    strategyRepo.selectStrategy(BypassStrategy.YOUTUBE_TURBO)
                                }
                            }
                        )
                    }

                    // TLS Split Offset
                    Column {
                        Text(
                            text = "TLS Split Offset",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SegmentedPillSwitch(
                            items = listOf(-1, 2, 1, 0),
                            selectedItem = customStrategy.tlsSplitOffset,
                            itemLabel = {
                                when (it) {
                                    -1 -> "SNI Start"
                                    2 -> "Offset 2"
                                    1 -> "Offset 1"
                                    else -> "Disabled"
                                }
                            },
                            onItemSelected = {
                                strategyRepo.updateCustomStrategy(customStrategy.copy(tlsSplitOffset = it))
                            }
                        )
                    }

                    // Fake SNI Injection
                    Column {
                        Text(
                            text = "Fake SNI Host Injection",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SegmentedPillSwitch(
                            items = listOf("www.google.com", "cloudflare.com", "yandex.ru", ""),
                            selectedItem = customStrategy.fakeSni,
                            itemLabel = {
                                when (it) {
                                    "www.google.com" -> "Google"
                                    "cloudflare.com" -> "Cloudflare"
                                    "yandex.ru" -> "Yandex"
                                    else -> "None"
                                }
                            },
                            onItemSelected = {
                                strategyRepo.updateCustomStrategy(customStrategy.copy(fakeSni = it))
                            }
                        )
                    }

                    // Fake TTL Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Fake Packet TTL",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${customStrategy.fakeTtl} hops",
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Slider(
                            value = customStrategy.fakeTtl.toFloat(),
                            onValueChange = {
                                strategyRepo.updateCustomStrategy(customStrategy.copy(fakeTtl = it.toInt()))
                            },
                            valueRange = 1f..10f,
                            steps = 8
                        )
                    }

                    // TCP Disorder & QUIC Switch Tiles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TCP Disorder",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Sends payload segments out of order to evade DPI",
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = customStrategy.useDisorder,
                            onCheckedChange = {
                                strategyRepo.updateCustomStrategy(customStrategy.copy(useDisorder = it))
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Block QUIC (UDP 443)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Forces apps to fallback to desynced TCP",
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = customStrategy.blockQuic,
                            onCheckedChange = {
                                strategyRepo.updateCustomStrategy(customStrategy.copy(blockQuic = it))
                            }
                        )
                    }

                    // DNS over HTTPS Chooser
                    Column {
                        Text(
                            text = "Encrypted DNS-over-HTTPS (DoH)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SegmentedPillSwitch(
                            items = listOf(DohProvider.CLOUDFLARE, DohProvider.GOOGLE, DohProvider.QUAD9),
                            selectedItem = customStrategy.dohProvider,
                            itemLabel = { it.displayName },
                            onItemSelected = {
                                strategyRepo.updateCustomStrategy(customStrategy.copy(dohProvider = it))
                            }
                        )
                    }
                }
            }
        }
    }
}