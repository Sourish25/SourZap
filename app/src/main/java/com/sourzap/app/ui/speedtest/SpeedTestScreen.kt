package com.sourzap.app.ui.speedtest

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.SpeedTestPhase
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveSpeedGauge
import com.sourzap.app.ui.components.ScallopedBadge
import com.sourzap.app.ui.theme.CandyCoral
import com.sourzap.app.ui.theme.CyanSpark
import com.sourzap.app.ui.theme.DarkBackground
import com.sourzap.app.ui.theme.DarkSurfaceContainer
import com.sourzap.app.ui.theme.DarkSurfaceContainerHigh
import com.sourzap.app.ui.theme.DarkSurfaceContainerHighest
import com.sourzap.app.ui.theme.ElectricViolet
import com.sourzap.app.ui.theme.ElectricVioletLight
import com.sourzap.app.ui.theme.ExpressiveShapes
import com.sourzap.app.ui.theme.NeonMint
import com.sourzap.app.ui.theme.NumberDisplayMedium
import com.sourzap.app.ui.theme.NumberDisplaySmall
import com.sourzap.app.ui.theme.SunbeamYellow
import com.sourzap.app.ui.theme.TextPrimary
import com.sourzap.app.ui.theme.TextSecondary
import com.sourzap.app.ui.theme.TextTertiary
import kotlinx.coroutines.launch

@Composable
fun SpeedTestScreen(
    modifier: Modifier = Modifier
) {
    val app = SourZapApp.instance
    val speedEngine = app.speedTestEngine
    val settingsRepo = app.settingsRepository
    val scope = rememberCoroutineScope()

    val testState by speedEngine.state.collectAsState()
    val history by settingsRepo.speedTestHistory.collectAsState()
    val isRunning = testState.phase != SpeedTestPhase.IDLE && testState.phase != SpeedTestPhase.COMPLETED && testState.phase != SpeedTestPhase.FAILED

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Expressive Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Speed Test",
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            letterSpacing = (-1).sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ScallopedBadge(
                            text = "🚀 ULTRA SPEED",
                            backgroundColor = SunbeamYellow,
                            textColor = DarkBackground
                        )
                    }
                    Text(
                        text = "Benchmark real latency, download & upload streams",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = TextTertiary
                    )
                }
            }
        }

        // Crazy Speedometer Gauge
        item {
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.AsymmetricPillLarge,
                backgroundColor = DarkSurfaceContainer,
                borderColor = if (isRunning) CyanSpark else DarkSurfaceContainerHighest
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ExpressiveSpeedGauge(
                        speedMbps = testState.activeGaugeSpeedMbps,
                        pingMs = testState.currentPingMs,
                        jitterMs = testState.currentJitterMs,
                        isTesting = isRunning,
                        statusText = testState.statusMessage
                    )

                    if (isRunning) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { testState.progress },
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(6.dp)
                                .clip(CircleShape),
                            color = CyanSpark,
                            trackColor = DarkSurfaceContainerHighest
                        )
                    }
                }
            }
        }

        // Metrics Grid (Ping, Jitter, Download, Upload)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Ping
                ExpressiveCard(
                    modifier = Modifier.weight(1f),
                    shape = ExpressiveShapes.Squircle,
                    backgroundColor = DarkSurfaceContainerHigh,
                    borderColor = DarkSurfaceContainerHighest
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Timer,
                                contentDescription = null,
                                tint = SunbeamYellow,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "PING",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (testState.currentPingMs > 0) String.format("%.0f ms", testState.currentPingMs) else "--",
                            style = NumberDisplaySmall,
                            color = SunbeamYellow
                        )
                    }
                }

                // Jitter
                ExpressiveCard(
                    modifier = Modifier.weight(1f),
                    shape = ExpressiveShapes.Squircle,
                    backgroundColor = DarkSurfaceContainerHigh,
                    borderColor = DarkSurfaceContainerHighest
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = null,
                                tint = CandyCoral,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "JITTER",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (testState.currentJitterMs > 0) String.format("%.1f ms", testState.currentJitterMs) else "--",
                            style = NumberDisplaySmall,
                            color = CandyCoral
                        )
                    }
                }

                // Download
                ExpressiveCard(
                    modifier = Modifier.weight(1.2f),
                    shape = ExpressiveShapes.Squircle,
                    backgroundColor = DarkSurfaceContainerHigh,
                    borderColor = DarkSurfaceContainerHighest
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowDownward,
                                contentDescription = null,
                                tint = NeonMint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "DOWNLOAD",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (testState.currentDownloadMbps > 0) String.format("%.1f", testState.currentDownloadMbps) else "--",
                            style = NumberDisplaySmall,
                            color = NeonMint
                        )
                    }
                }

                // Upload
                ExpressiveCard(
                    modifier = Modifier.weight(1.2f),
                    shape = ExpressiveShapes.Squircle,
                    backgroundColor = DarkSurfaceContainerHigh,
                    borderColor = DarkSurfaceContainerHighest
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowUpward,
                                contentDescription = null,
                                tint = CyanSpark,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "UPLOAD",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (testState.currentUploadMbps > 0) String.format("%.1f", testState.currentUploadMbps) else "--",
                            style = NumberDisplaySmall,
                            color = CyanSpark
                        )
                    }
                }
            }
        }

        // Big Tactile Start / Stop Button
        item {
            val buttonGradient = if (isRunning) {
                Brush.linearGradient(listOf(CandyCoral, Color(0xFFC7153E)))
            } else {
                Brush.linearGradient(listOf(ElectricViolet, Color(0xFF6714E2)))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .shadow(16.dp, ExpressiveShapes.SuperPill, spotColor = if (isRunning) CandyCoral else ElectricViolet)
                    .clip(ExpressiveShapes.SuperPill)
                    .background(buttonGradient)
                    .clickable {
                        if (isRunning) {
                            speedEngine.cancelTest()
                        } else {
                            scope.launch {
                                speedEngine.runSpeedTest()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning) "STOP SPEED TEST" else "START SPEED TEST",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        letterSpacing = 0.8.sp,
                        color = TextPrimary
                    )
                }
            }
        }

        // DPI Boost Comparison Card
        item {
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.AsymmetricPillInverse,
                backgroundColor = DarkSurfaceContainer,
                borderColor = NeonMint.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "⚡ Zapret DPI Acceleration",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                color = NeonMint
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Eliminates ISP throttling on YouTube 4K, Discord streams, and restricted CDNs by desyncing DPI inspection buffers.",
                            fontWeight = FontWeight.Normal,
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    ScallopedBadge(
                        text = "4X FASTER!",
                        backgroundColor = NeonMint,
                        textColor = DarkBackground,
                        numPetals = 12
                    )
                }
            }
        }

        // Speed Test History
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "SPEED TEST HISTORY",
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 0.8.sp,
                    color = ElectricVioletLight
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (history.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Squircle)
                            .background(DarkSurfaceContainer)
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No speed test records yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TextTertiary
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        history.forEach { item ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(ExpressiveShapes.Squircle)
                                    .background(DarkSurfaceContainerHigh)
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = item.serverName,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 14.sp,
                                            color = TextPrimary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = " • Ping: ms",
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 11.sp,
                                            color = TextTertiary
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = String.format("%.1f Mbps", item.downloadMbps),
                                                fontWeight = FontWeight.Black,
                                                fontSize = 16.sp,
                                                color = NeonMint
                                            )
                                            Text(
                                                text = "↓ DL  ↑  UL",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = TextSecondary
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