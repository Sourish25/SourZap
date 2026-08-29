package com.sourzap.app.ui.traffic

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
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
import com.sourzap.app.data.model.TrafficStats
import com.sourzap.app.service.TrafficMonitor
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveTrafficWave
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrafficScreen(
    modifier: Modifier = Modifier
) {
    val stats by TrafficMonitor.stats.collectAsState()
    val logs by TrafficMonitor.recentLogs.collectAsState()
    val isVpnActive by TrafficMonitor.isVpnActive.collectAsState()

    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

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
                            text = "Traffic",
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            letterSpacing = (-1).sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ScallopedBadge(
                            text = "📊 LIVE DATA",
                            backgroundColor = NeonMint,
                            textColor = DarkBackground
                        )
                    }
                    Text(
                        text = "Real-time bandwidth usage & DPI packet telemetry",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = TextTertiary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(DarkSurfaceContainerHigh)
                        .clickable { TrafficMonitor.resetSession() }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reset Session",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Live Bandwidth Graph Card
        item {
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shape = ExpressiveShapes.AsymmetricPillLarge,
                backgroundColor = DarkSurfaceContainer,
                borderColor = if (isVpnActive) NeonMint else DarkSurfaceContainerHighest
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "LIVE THROUGHPUT",
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp,
                                letterSpacing = 0.8.sp,
                                color = NeonMint
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = stats.formattedDownloadSpeed(),
                                    style = NumberDisplayMedium,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "↓ DL",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = NeonMint,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "UPLOAD",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = stats.formattedUploadSpeed(),
                                style = NumberDisplaySmall,
                                color = CyanSpark
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    ExpressiveTrafficWave(
                        speedHistory = stats.recentSpeedHistory,
                        lineColor = NeonMint,
                        fillColor = NeonMint.copy(alpha = 0.25f)
                    )
                }
            }
        }

        // Metrics Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExpressiveCard(
                    modifier = Modifier.weight(1f),
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
                                text = "SESSION DL",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stats.formattedSessionDownload(),
                            style = NumberDisplaySmall,
                            color = NeonMint
                        )
                    }
                }

                ExpressiveCard(
                    modifier = Modifier.weight(1f),
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
                                text = "SESSION UL",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stats.formattedSessionUpload(),
                            style = NumberDisplaySmall,
                            color = CyanSpark
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExpressiveCard(
                    modifier = Modifier.weight(1f),
                    shape = ExpressiveShapes.Squircle,
                    backgroundColor = DarkSurfaceContainerHigh,
                    borderColor = DarkSurfaceContainerHighest
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "TOTAL LIFETIME",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stats.formattedTotalDownload(),
                            style = NumberDisplaySmall,
                            color = SunbeamYellow
                        )
                    }
                }

                ExpressiveCard(
                    modifier = Modifier.weight(1f),
                    shape = ExpressiveShapes.Squircle,
                    backgroundColor = DarkSurfaceContainerHigh,
                    borderColor = DarkSurfaceContainerHighest
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "PACKET RATE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = " pps",
                            style = NumberDisplaySmall,
                            color = ElectricVioletLight
                        )
                    }
                }
            }
        }

        // Connection Logs
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CONNECTION INSPECTOR STREAM",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 0.8.sp,
                        color = ElectricVioletLight
                    )

                    Text(
                        text = " events",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = TextTertiary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Squircle)
                            .background(DarkSurfaceContainer)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No connection streams active",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TextTertiary
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        logs.forEach { log ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(ExpressiveShapes.Squircle)
                                    .background(DarkSurfaceContainerHigh)
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Rounded.FlashOn,
                                                contentDescription = null,
                                                tint = if (log.port == 443) NeonMint else CyanSpark,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = log.domain,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 14.sp,
                                                color = TextPrimary,
                                                maxLines = 1
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(2.dp))

                                        Text(
                                            text = ": •  • ",
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 11.sp,
                                            color = TextTertiary
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    ScallopedBadge(
                                        text = log.technique,
                                        backgroundColor = if (log.protocol == "TLS") ElectricViolet else CandyCoral,
                                        textColor = TextPrimary,
                                        numPetals = 8
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