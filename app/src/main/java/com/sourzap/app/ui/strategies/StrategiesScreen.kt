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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.data.model.DohProvider
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveChip
import com.sourzap.app.ui.components.ScallopedBadge
import com.sourzap.app.ui.components.SegmentedPillSwitch
import com.sourzap.app.ui.theme.ExpressiveShapes

@Composable
fun StrategiesScreen(
    modifier: Modifier = Modifier
) {
    val app = SourZapApp.instance
    val strategyRepo = app.strategyRepository

    val currentStrategy by strategyRepo.currentStrategy.collectAsState()
    val customStrategy by strategyRepo.customStrategy.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 100.dp),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Bypass Engine",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ScallopedBadge(
                            text = "PRESETS",
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Text(
                        text = "Select DPI circumvention preset or build custom desync",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Preset Section Header
        item {
            Text(
                text = "CURATED PRESETS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Preset Cards
        items(BypassStrategy.DEFAULT_PRESETS) { strategy ->
            val isSelected = currentStrategy.id == strategy.id

            ExpressiveCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { strategyRepo.selectStrategy(strategy) },
                shape = ExpressiveShapes.AsymmetricPillLarge,
                backgroundColor = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
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
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(ExpressiveShapes.Squircle)
                                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = strategy.iconEmoji,
                                    fontSize = 20.sp
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = strategy.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = strategy.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            ExpressiveChip(
                                text = strategy.tag,
                                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Technique Breakdown
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (strategy.tlsSplitOffset != 0) {
                            ExpressiveChip(
                                text = if (strategy.tlsSplitOffset == -1) "SNI Split" else "Split @ ${strategy.tlsSplitOffset}",
                                backgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                                textColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        if (strategy.fakeSni.isNotEmpty()) {
                            ExpressiveChip(
                                text = "Fake: ${strategy.fakeSni}",
                                backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                                textColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }

                        if (strategy.useDisorder) {
                            ExpressiveChip(
                                text = "Disorder",
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
                text = "CUSTOM STRATEGY BUILDER",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            val isCustomSelected = currentStrategy.isCustom

            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.AsymmetricPillInverse,
                backgroundColor = if (isCustomSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                borderColor = if (isCustomSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(ExpressiveShapes.Squircle)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = customStrategy.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Tailored packet desync parameters",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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

                    // TLS Split Offset Chooser
                    Column {
                        Text(
                            text = "TLS Split Offset",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SegmentedPillSwitch(
                            items = listOf(-1, 2, 1, 0),
                            selectedItem = customStrategy.tlsSplitOffset,
                            itemLabel = {
                                when (it) {
                                    -1 -> "SNI Start"
                                    2 -> "Pos 2"
                                    1 -> "Pos 1"
                                    else -> "None"
                                }
                            },
                            onItemSelected = {
                                strategyRepo.updateCustomStrategy(customStrategy.copy(tlsSplitOffset = it))
                            }
                        )
                    }

                    // Fake SNI Host Chooser
                    Column {
                        Text(
                            text = "Fake SNI Host Injection",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        SegmentedPillSwitch(
                            items = listOf("www.google.com", "cloudflare.com", "yandex.ru", ""),
                            selectedItem = customStrategy.fakeSni,
                            itemLabel = {
                                when (it) {
                                    "www.google.com" -> "Google"
                                    "cloudflare.com" -> "Cloudflare"
                                    "yandex.ru" -> "Yandex"
                                    else -> "Disabled"
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
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${customStrategy.fakeTtl} hops",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
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

                    // TCP Disorder & QUIC Block
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "TCP Disorder (Out-of-Order)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Sends payload segments out of sequence",
                                style = MaterialTheme.typography.bodySmall,
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
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Forces apps to fallback to TCP where DPI desync applies",
                                style = MaterialTheme.typography.bodySmall,
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

                    // DoH Provider Chooser
                    Column {
                        Text(
                            text = "Encrypted DNS-over-HTTPS (DoH)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
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