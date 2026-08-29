package com.sourzap.app.ui.dashboard

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.data.model.BypassStrategy
import com.sourzap.app.service.SourZapVpnService
import com.sourzap.app.service.TrafficMonitor
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveTrafficWave
import com.sourzap.app.ui.components.HeroConnectButton
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

@Composable
fun DashboardScreen(
    onNavigateToSpeedTest: () -> Unit,
    onNavigateToStrategies: () -> Unit,
    onNavigateToTraffic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = SourZapApp.instance
    val strategyRepo = app.strategyRepository

    val isConnected by TrafficMonitor.isVpnActive.collectAsState()
    val stats by TrafficMonitor.stats.collectAsState()
    val currentStrategy by strategyRepo.currentStrategy.collectAsState()
    val recentLogs by TrafficMonitor.recentLogs.collectAsState()

    val vpnPrepareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val startIntent = Intent(context, SourZapVpnService::class.java).apply {
                action = SourZapVpnService.ACTION_START
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(startIntent)
            } else {
                context.startService(startIntent)
            }
        }
    }

    val toggleVpn = {
        if (isConnected) {
            val stopIntent = Intent(context, SourZapVpnService::class.java).apply {
                action = SourZapVpnService.ACTION_STOP
            }
            context.startService(stopIntent)
        } else {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnPrepareLauncher.launch(prepareIntent)
            } else {
                val startIntent = Intent(context, SourZapVpnService::class.java).apply {
                    action = SourZapVpnService.ACTION_START
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(startIntent)
                } else {
                    context.startService(startIntent)
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Expressive App Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SourZap",
                            fontWeight = FontWeight.Black,
                            fontSize = 32.sp,
                            letterSpacing = (-1).sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ScallopedBadge(
                            text = "⚡ M3 EXPRESSIVE",
                            backgroundColor = ElectricViolet,
                            textColor = TextPrimary
                        )
                    }

                    Text(
                        text = "Rootless Zapret DPI Desync & Speed Engine",
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                        color = TextTertiary
                    )
                }

                // Status Pill
                Box(
                    modifier = Modifier
                        .clip(ExpressiveShapes.SuperPill)
                        .background(if (isConnected) NeonMint.copy(alpha = 0.2f) else DarkSurfaceContainerHigh)
                        .border(1.dp, if (isConnected) NeonMint else DarkSurfaceContainerHighest, ExpressiveShapes.SuperPill)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) NeonMint else CandyCoral)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isConnected) "ACTIVE" else "IDLE",
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            color = if (isConnected) NeonMint else TextSecondary
                        )
                    }
                }
            }
        }

        // Hero Connect Area
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                HeroConnectButton(
                    isConnected = isConnected,
                    onToggle = { toggleVpn() }
                )
            }
        }

        // Active Strategy Quick Card
        item {
            ExpressiveCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToStrategies() },
                shape = ExpressiveShapes.AsymmetricPillLarge,
                backgroundColor = DarkSurfaceContainer,
                borderColor = if (isConnected) ElectricViolet else DarkSurfaceContainerHighest
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(ExpressiveShapes.Squircle)
                                .background(ElectricViolet.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentStrategy.iconEmoji,
                                fontSize = 24.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = currentStrategy.name,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 17.sp,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                ScallopedBadge(
                                    text = currentStrategy.tag,
                                    backgroundColor = SunbeamYellow,
                                    textColor = DarkBackground,
                                    numPetals = 8
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = currentStrategy.description,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                color = TextSecondary,
                                maxLines = 1
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "Configure Strategy",
                        tint = ElectricVioletLight,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // Real-Time Traffic & Speeds Card
        item {
            ExpressiveCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToTraffic() },
                shape = ExpressiveShapes.AsymmetricPillInverse,
                backgroundColor = DarkSurfaceContainer,
                borderColor = DarkSurfaceContainerHighest
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = null,
                                tint = NeonMint,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "LIVE TRAFFIC THROUGHPUT",
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                                letterSpacing = 0.8.sp,
                                color = NeonMint
                            )
                        }

                        Text(
                            text = " active sockets",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = TextTertiary
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Download Speed
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowDownward,
                                    contentDescription = "Download",
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
                            Text(
                                text = stats.formattedDownloadSpeed(),
                                style = NumberDisplaySmall,
                                color = TextPrimary
                            )
                        }

                        // Upload Speed
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.ArrowUpward,
                                    contentDescription = "Upload",
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
                            Text(
                                text = stats.formattedUploadSpeed(),
                                style = NumberDisplaySmall,
                                color = TextPrimary
                            )
                        }

                        // Session Transferred
                        Column {
                            Text(
                                text = "SESSION",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = stats.formattedSessionDownload(),
                                style = NumberDisplaySmall,
                                color = SunbeamYellow
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Live Waveform Canvas
                    ExpressiveTrafficWave(
                        speedHistory = stats.recentSpeedHistory,
                        lineColor = NeonMint,
                        fillColor = NeonMint.copy(alpha = 0.25f)
                    )
                }
            }
        }

        // Live Evasion Stream Ticker
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE DPI EVASION STREAM",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 0.8.sp,
                        color = ElectricVioletLight
                    )

                    Text(
                        text = "Tap for Inspector",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = TextTertiary,
                        modifier = Modifier.clickable { onNavigateToTraffic() }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (recentLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(ExpressiveShapes.Squircle)
                            .background(DarkSurfaceContainer)
                            .padding(18.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isConnected) "⚡ Listening for DPI traffic..." else "Connect SourZap to start desyncing packets",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = TextTertiary
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recentLogs.take(4).forEach { log ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(ExpressiveShapes.Squircle)
                                    .background(DarkSurfaceContainerHigh)
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
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
                                            imageVector = Icons.Rounded.FlashOn,
                                            contentDescription = null,
                                            tint = NeonMint,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = log.domain,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = TextPrimary,
                                            maxLines = 1
                                        )
                                    }

                                    ScallopedBadge(
                                        text = log.technique,
                                        backgroundColor = ElectricViolet,
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

        // Speed Test CTA Banner
        item {
            ExpressiveCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToSpeedTest() },
                shape = ExpressiveShapes.ChunkyCard,
                backgroundColor = DarkSurfaceContainerHigh,
                borderColor = SunbeamYellow.copy(alpha = 0.4f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(SunbeamYellow.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = null,
                                tint = SunbeamYellow,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                text = "Test Internet Speed",
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Benchmark ping, download & upload unthrottled",
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    ScallopedBadge(
                        text = "TEST NOW",
                        backgroundColor = SunbeamYellow,
                        textColor = DarkBackground,
                        numPetals = 10
                    )
                }
            }
        }
    }
}