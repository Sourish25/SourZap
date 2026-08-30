package com.sourzap.app.ui.speedtest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.SpeedTestPhase
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveChip
import com.sourzap.app.ui.components.ExpressiveMetricTile
import com.sourzap.app.ui.components.ExpressiveSpeedGauge
import com.sourzap.app.ui.components.ExpressiveWavyProgressIndicator
import kotlinx.coroutines.launch

@Composable
fun SpeedTestScreen(
    modifier: Modifier = Modifier
) {
    val app = SourZapApp.instance
    val speedEngine = app.speedTestEngine
    val settingsRepo = app.settingsRepository
    val strategyRepo = app.strategyRepository

    val state by speedEngine.state.collectAsState()
    val history by settingsRepo.speedTestHistory.collectAsState()
    val currentStrategy by strategyRepo.currentStrategy.collectAsState()

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val isRunning = state.phase != SpeedTestPhase.IDLE && state.phase != SpeedTestPhase.COMPLETED && state.phase != SpeedTestPhase.FAILED

    // Stability grade computation based on ping and jitter
    val stabilityRating = when {
        state.currentJitterMs > 0 && state.currentJitterMs < 3.5f -> "A+ (Optimal)"
        state.currentJitterMs >= 3.5f && state.currentJitterMs < 8f -> "A (Stable)"
        state.currentJitterMs >= 8f && state.currentJitterMs < 18f -> "B (Good)"
        state.currentJitterMs >= 18f -> "C (Variable)"
        else -> "--"
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
                        text = "Speed Test",
                        fontWeight = FontWeight.Black,
                        fontSize = 32.sp,
                        letterSpacing = (-1).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Bandwidth & Latency Benchmark",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                ExpressiveChip(
                    text = if (isRunning) "BENCHMARKING" else "DIAGNOSTIC",
                    icon = if (isRunning) Icons.Rounded.Speed else Icons.Rounded.NetworkCheck,
                    backgroundColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    textColor = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // Speedometer Gauge Card
        item {
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(32.dp),
                backgroundColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ExpressiveSpeedGauge(
                        speedMbps = state.activeGaugeSpeedMbps,
                        statusText = state.statusMessage
                    )

                    if (isRunning) {
                        Spacer(modifier = Modifier.height(14.dp))
                        ExpressiveWavyProgressIndicator(
                            progress = state.progress,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Large Smartphone Action Button (64dp height)
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isRunning) {
                                speedEngine.cancelTest()
                            } else {
                                scope.launch { speedEngine.runSpeedTest() }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
                            contentColor = if (isRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRunning) "CANCEL TEST" else "START SPEED TEST",
                                fontWeight = FontWeight.Black,
                                fontSize = 15.5.sp,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }
            }
        }

        // Section Title: Diagnostic Telemetry Metrics
        item {
            Text(
                text = "DIAGNOSTIC METRICS GRID",
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Diagnostic Metrics: Row 1 (Ping & Jitter)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExpressiveMetricTile(
                    title = "LATENCY / PING",
                    value = if (state.currentPingMs > 0) String.format("%.0f", state.currentPingMs) else "--",
                    unit = if (state.currentPingMs > 0) "ms" else null,
                    icon = Icons.Rounded.Timer,
                    iconTint = MaterialTheme.colorScheme.primary,
                    subtitle = "Edge Server Round-Trip",
                    modifier = Modifier.weight(1f)
                )

                ExpressiveMetricTile(
                    title = "JITTER",
                    value = if (state.currentJitterMs > 0) String.format("%.1f", state.currentJitterMs) else "--",
                    unit = if (state.currentJitterMs > 0) "ms" else null,
                    icon = Icons.Rounded.Timeline,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    subtitle = "Packet Delay Variance",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Diagnostic Metrics: Row 2 (Download & Upload Speeds)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExpressiveMetricTile(
                    title = "DOWNLOAD SPEED",
                    value = if (state.currentDownloadMbps > 0) String.format("%.1f", state.currentDownloadMbps) else "--",
                    unit = if (state.currentDownloadMbps > 0) "Mbps" else null,
                    icon = Icons.Rounded.ArrowDownward,
                    iconTint = MaterialTheme.colorScheme.primary,
                    subtitle = "4-Stream Parallel Pipe",
                    modifier = Modifier.weight(1f)
                )

                ExpressiveMetricTile(
                    title = "UPLOAD SPEED",
                    value = if (state.currentUploadMbps > 0) String.format("%.1f", state.currentUploadMbps) else "--",
                    unit = if (state.currentUploadMbps > 0) "Mbps" else null,
                    icon = Icons.Rounded.ArrowUpward,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    subtitle = "Upstream Throughput",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Diagnostic Metrics: Row 3 (Bufferbloat/Stability & Active Strategy)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExpressiveMetricTile(
                    title = "BUFFERBLOAT / STABILITY",
                    value = stabilityRating,
                    icon = Icons.Rounded.Shield,
                    iconTint = MaterialTheme.colorScheme.tertiary,
                    subtitle = "Connection Health Rating",
                    modifier = Modifier.weight(1f)
                )

                ExpressiveMetricTile(
                    title = "ACTIVE BYPASS",
                    value = currentStrategy.name,
                    icon = Icons.Rounded.VpnLock,
                    iconTint = MaterialTheme.colorScheme.primary,
                    subtitle = "Zapret DPI Engine",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Test History Section
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "BENCHMARK HISTORY",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ExpressiveChip(
                        text = "${history.size} tests",
                        icon = Icons.Rounded.History,
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (history.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No speed tests recorded yet",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.take(5).forEach { test ->
                            ExpressiveCard(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = test.strategyName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = test.formattedDate(),
                                            fontWeight = FontWeight.Normal,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ArrowDownward,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = String.format("%.1f Mbps", test.downloadMbps),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.5.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ArrowUpward,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(2.dp))
                                                Text(
                                                    text = String.format("%.1f Mbps", test.uploadMbps),
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        ExpressiveChip(
                                            text = String.format("%.0f ms", test.pingMs),
                                            backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            textColor = MaterialTheme.colorScheme.onSurface
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