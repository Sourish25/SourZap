package com.sourzap.app.ui.speedtest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.draw.scale
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

import androidx.compose.ui.platform.LocalConfiguration
import com.sourzap.app.ui.components.AdaptiveContentContainer

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

    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val isRunning = state.phase != SpeedTestPhase.IDLE && state.phase != SpeedTestPhase.COMPLETED && state.phase != SpeedTestPhase.FAILED

    // Spring-animated diagnostic metric values
    val animatedPing by animateFloatAsState(
        targetValue = state.currentPingMs,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "AnimPing"
    )
    val animatedJitter by animateFloatAsState(
        targetValue = state.currentJitterMs,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "AnimJitter"
    )
    val animatedDownload by animateFloatAsState(
        targetValue = state.currentDownloadMbps,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "AnimDL"
    )
    val animatedUpload by animateFloatAsState(
        targetValue = state.currentUploadMbps,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "AnimUL"
    )

    AdaptiveContentContainer(maxWidth = 760.dp) {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 108.dp),
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
                }
            }

            if (isTablet) {
                // Tablet Split View: Gauge on Left, Metrics on Right
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Left: Speedometer Gauge Card
                        Box(modifier = Modifier.weight(1f)) {
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

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Button(
                                        onClick = {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            if (isRunning) {
                                                speedEngine.cancelTest()
                                            } else {
                                                scope.launch { speedEngine.runSpeedTest() }
                                            }
                                        },
                                        shape = RoundedCornerShape(22.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primary,
                                            contentColor = if (isRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimary
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(58.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isRunning) "CANCEL TEST" else "START SPEED TEST",
                                                fontWeight = FontWeight.Black,
                                                fontSize = 14.5.sp,
                                                letterSpacing = 0.6.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Right: 4 Diagnostic Tiles in 2x2 Grid
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ExpressiveMetricTile(
                                    title = "LATENCY / PING",
                                    value = if (animatedPing > 0) String.format(java.util.Locale.US, "%.0f", animatedPing) else "--",
                                    unit = if (animatedPing > 0) "ms" else null,
                                    icon = Icons.Rounded.Timer,
                                    iconTint = MaterialTheme.colorScheme.primary,
                                    subtitle = "Edge Server Round-Trip",
                                    modifier = Modifier.weight(1f)
                                )

                                ExpressiveMetricTile(
                                    title = "JITTER",
                                    value = if (animatedJitter > 0) String.format(java.util.Locale.US, "%.1f", animatedJitter) else "--",
                                    unit = if (animatedJitter > 0) "ms" else null,
                                    icon = Icons.Rounded.Timeline,
                                    iconTint = MaterialTheme.colorScheme.secondary,
                                    subtitle = "Packet Delay Variance",
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                ExpressiveMetricTile(
                                    title = "DOWNLOAD SPEED",
                                    value = if (animatedDownload > 0) String.format(java.util.Locale.US, "%.1f", animatedDownload) else "--",
                                    unit = if (animatedDownload > 0) "Mbps" else null,
                                    icon = Icons.Rounded.ArrowDownward,
                                    iconTint = MaterialTheme.colorScheme.primary,
                                    subtitle = "4-Stream Parallel Pipe",
                                    modifier = Modifier.weight(1f)
                                )

                                ExpressiveMetricTile(
                                    title = "UPLOAD SPEED",
                                    value = if (animatedUpload > 0) String.format(java.util.Locale.US, "%.1f", animatedUpload) else "--",
                                    unit = if (animatedUpload > 0) "Mbps" else null,
                                    icon = Icons.Rounded.ArrowUpward,
                                    iconTint = MaterialTheme.colorScheme.secondary,
                                    subtitle = "Upstream Throughput",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            } else {
                // Phone Vertical Stacking Layout
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
                                    .height(62.dp)
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
                                        fontSize = 15.sp,
                                        letterSpacing = 0.6.sp
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

                // Diagnostic Metrics: Row 1 (Ping & Jitter with Spring Physics)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExpressiveMetricTile(
                            title = "LATENCY / PING",
                            value = if (animatedPing > 0) String.format(java.util.Locale.US, "%.0f", animatedPing) else "--",
                            unit = if (animatedPing > 0) "ms" else null,
                            icon = Icons.Rounded.Timer,
                            iconTint = MaterialTheme.colorScheme.primary,
                            subtitle = "Edge Server Round-Trip",
                            modifier = Modifier.weight(1f)
                        )

                        ExpressiveMetricTile(
                            title = "JITTER",
                            value = if (animatedJitter > 0) String.format(java.util.Locale.US, "%.1f", animatedJitter) else "--",
                            unit = if (animatedJitter > 0) "ms" else null,
                            icon = Icons.Rounded.Timeline,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            subtitle = "Packet Delay Variance",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Diagnostic Metrics: Row 2 (Download & Upload Speeds with Spring Physics)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExpressiveMetricTile(
                            title = "DOWNLOAD SPEED",
                            value = if (animatedDownload > 0) String.format(java.util.Locale.US, "%.1f", animatedDownload) else "--",
                            unit = if (animatedDownload > 0) "Mbps" else null,
                            icon = Icons.Rounded.ArrowDownward,
                            iconTint = MaterialTheme.colorScheme.primary,
                            subtitle = "4-Stream Parallel Pipe",
                            modifier = Modifier.weight(1f)
                        )

                        ExpressiveMetricTile(
                            title = "UPLOAD SPEED",
                            value = if (animatedUpload > 0) String.format(java.util.Locale.US, "%.1f", animatedUpload) else "--",
                            unit = if (animatedUpload > 0) "Mbps" else null,
                            icon = Icons.Rounded.ArrowUpward,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            subtitle = "Upstream Throughput",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Benchmark History Section
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
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        // Row 1: Title on left, Ping Chip on right
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Speed Benchmark",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.5.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )

                                            Spacer(modifier = Modifier.width(8.dp))

                                            ExpressiveChip(
                                                text = String.format(java.util.Locale.US, "%.0f ms", test.pingMs),
                                                backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                textColor = MaterialTheme.colorScheme.onSurface
                                            )
                                        }

                                        // Row 2: Date on left, Download/Upload speeds on right
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = test.formattedDate(),
                                                fontWeight = FontWeight.Normal,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.ArrowDownward,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Text(
                                                        text = String.format(java.util.Locale.US, "%.1f Mbps", test.downloadMbps),
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.5.sp,
                                                        color = MaterialTheme.colorScheme.primary
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
                                                        text = String.format(java.util.Locale.US, "%.1f Mbps", test.uploadMbps),
                                                        fontWeight = FontWeight.Medium,
                                                        fontSize = 11.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
        }
    }
}