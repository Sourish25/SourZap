package com.sourzap.app.ui.traffic

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.service.TrafficMonitor
import com.sourzap.app.ui.components.ExpressiveCard
import com.sourzap.app.ui.components.ExpressiveChip
import com.sourzap.app.ui.components.ExpressiveConfirmationDialog
import com.sourzap.app.ui.components.ExpressiveMetricTile
import com.sourzap.app.ui.components.ExpressiveTrafficWave
import com.sourzap.app.ui.theme.NumberDisplayMedium
import com.sourzap.app.ui.theme.NumberDisplaySmall
import java.text.SimpleDateFormat
import java.util.Locale

import androidx.compose.ui.platform.LocalConfiguration
import com.sourzap.app.ui.components.AdaptiveContentContainer

enum class TrafficFilterTab(val id: String, val displayName: String, val icon: ImageVector) {
    ALL("ALL", "All Flows", Icons.Rounded.SwapVert),
    TLS("TLS", "HTTPS/TLS", Icons.Rounded.Lock),
    DNS("DNS", "DNS", Icons.Rounded.Dns),
    P2P("P2P", "BitTorrent/P2P", Icons.Rounded.CloudDownload),
    UDP("UDP", "UDP", Icons.Rounded.Bolt)
}

@Composable
fun TrafficScreen(
    modifier: Modifier = Modifier
) {
    val stats by TrafficMonitor.stats.collectAsState()
    val logs by TrafficMonitor.recentLogs.collectAsState()
    val isVpnActive by TrafficMonitor.isVpnActive.collectAsState()

    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(TrafficFilterTab.ALL) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    val haptics = LocalHapticFeedback.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    val filteredLogs = remember(logs, searchQuery, selectedTab) {
        logs.filter { log ->
            val matchesSearch = searchQuery.isBlank() ||
                    log.domain.contains(searchQuery, ignoreCase = true) ||
                    log.protocol.contains(searchQuery, ignoreCase = true) ||
                    log.technique.contains(searchQuery, ignoreCase = true) ||
                    log.port.toString().contains(searchQuery)

            val matchesTab = when (selectedTab) {
                TrafficFilterTab.ALL -> true
                TrafficFilterTab.TLS -> log.protocol.contains("TLS", ignoreCase = true) ||
                        log.protocol.contains("HTTPS", ignoreCase = true) ||
                        log.port == 443
                TrafficFilterTab.DNS -> log.protocol.contains("DNS", ignoreCase = true) ||
                        log.protocol.contains("DOH", ignoreCase = true) ||
                        log.port == 53 || log.port == 853
                TrafficFilterTab.P2P -> log.protocol.contains("P2P", ignoreCase = true) ||
                        log.protocol.contains("Torrent", ignoreCase = true) ||
                        log.technique.contains("P2P", ignoreCase = true) ||
                        log.technique.contains("BitTorrent", ignoreCase = true) ||
                        (log.port in 6881..6889)
                TrafficFilterTab.UDP -> log.protocol.contains("UDP", ignoreCase = true) ||
                        log.protocol.contains("QUIC", ignoreCase = true) ||
                        log.technique.contains("QUIC", ignoreCase = true)
            }

            matchesSearch && matchesTab
        }
    }

    if (showClearConfirmDialog) {
        ExpressiveConfirmationDialog(
            title = "Clear Intercepted Logs?",
            message = "This will wipe all currently displayed connection packet streams and inspector telemetry records.",
            confirmText = "Clear All",
            dismissText = "Keep Logs",
            icon = Icons.Rounded.DeleteOutline,
            onConfirm = {
                TrafficMonitor.clearLogs()
                showClearConfirmDialog = false
            },
            onDismiss = {
                showClearConfirmDialog = false
            }
        )
    }

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
            // Header with Responsive Title and Action Chips
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Traffic Inspector",
                                fontWeight = FontWeight.Black,
                                fontSize = 30.sp,
                                letterSpacing = (-1).sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Live traffic & connection streams",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Clear Logs Button
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = "Clear intercepted traffic logs"
                                    }
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        showClearConfirmDialog = true
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "CLEAR",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp,
                                        letterSpacing = 0.3.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            // Reset Session Counters
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .semantics {
                                        role = Role.Button
                                        contentDescription = "Reset session traffic counters"
                                    }
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        TrafficMonitor.resetSession()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.RestartAlt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "RESET",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp,
                                        letterSpacing = 0.3.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Live Bandwidth Graph Card
            item {
                ExpressiveCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainer,
                    borderColor = if (isVpnActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
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
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowDownward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "LIVE DOWNLOAD",
                                        fontWeight = FontWeight.Black,
                                        fontSize = 11.sp,
                                        letterSpacing = 0.8.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stats.formattedDownloadSpeed(),
                                    style = NumberDisplayMedium.copy(fontSize = 22.sp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowUpward,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "LIVE UPLOAD",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stats.formattedUploadSpeed(),
                                    style = NumberDisplaySmall.copy(fontSize = 20.sp),
                                    color = MaterialTheme.colorScheme.secondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        ExpressiveTrafficWave(
                            speedHistory = stats.recentSpeedHistory,
                            lineColor = MaterialTheme.colorScheme.primary,
                            fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        )
                    }
                }
            }

            // Summary Metric Tiles (4-Column on Tablet, 2x2 Grid on Phone)
            if (isTablet) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExpressiveMetricTile(
                            title = "SESSION DL",
                            value = stats.formattedSessionDownload(),
                            icon = Icons.Rounded.CloudDownload,
                            iconTint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )

                        ExpressiveMetricTile(
                            title = "SESSION UL",
                            value = stats.formattedSessionUpload(),
                            icon = Icons.Rounded.CloudUpload,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f)
                        )

                        ExpressiveMetricTile(
                            title = "TOTAL LIFETIME",
                            value = stats.formattedTotalDownload(),
                            icon = Icons.Rounded.Storage,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )

                        ExpressiveMetricTile(
                            title = "PACKET RATE",
                            value = "${stats.packetsPerSecond}",
                            unit = "pps",
                            icon = Icons.Rounded.Speed,
                            iconTint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExpressiveMetricTile(
                            title = "SESSION DL",
                            value = stats.formattedSessionDownload(),
                            icon = Icons.Rounded.CloudDownload,
                            iconTint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )

                        ExpressiveMetricTile(
                            title = "SESSION UL",
                            value = stats.formattedSessionUpload(),
                            icon = Icons.Rounded.CloudUpload,
                            iconTint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ExpressiveMetricTile(
                            title = "TOTAL LIFETIME",
                            value = stats.formattedTotalDownload(),
                            icon = Icons.Rounded.Storage,
                            iconTint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )

                        ExpressiveMetricTile(
                            title = "PACKET RATE",
                            value = "${stats.packetsPerSecond}",
                            unit = "pps",
                            icon = Icons.Rounded.Speed,
                            iconTint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

        // Search & Animated Filter Tabs Header
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CONNECTIONS",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 0.8.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    ExpressiveChip(
                        text = "${filteredLogs.size} of ${logs.size} logged",
                        icon = Icons.Rounded.FilterList,
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter by domain, IP, protocol...") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                searchQuery = ""
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Filter Tabs with safe bounds and zero clipping
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrafficFilterTab.values().forEach { tab ->
                        val isSelected = selectedTab == tab
                        val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
                        val textCol = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        val border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = bg,
                            border = border,
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .semantics {
                                    role = Role.Tab
                                    selected = isSelected
                                    contentDescription = "${tab.displayName} filter"
                                }
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTab = tab
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = textCol,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tab.displayName,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.5.sp,
                                    color = textCol,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }

        // Intercepted Connection Logs List with Smooth Animated Transitions
        if (filteredLogs.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (logs.isEmpty()) Icons.Rounded.ClearAll else Icons.Rounded.FilterAltOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = if (logs.isEmpty()) "No connection streams intercepted yet" else "No streams match \"${selectedTab.displayName}\"",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredLogs, key = { it.id }) { log ->
                val protocolIcon: ImageVector = when {
                    log.protocol.contains("TLS", ignoreCase = true) || log.protocol.contains("HTTPS", ignoreCase = true) -> Icons.Rounded.Lock
                    log.protocol.contains("HTTP", ignoreCase = true) -> Icons.Rounded.Language
                    log.protocol.contains("DNS", ignoreCase = true) || log.protocol.contains("DOH", ignoreCase = true) -> Icons.Rounded.Dns
                    log.protocol.contains("Torrent", ignoreCase = true) || log.protocol.contains("P2P", ignoreCase = true) || (log.port in 6881..6889) -> Icons.Rounded.CloudDownload
                    else -> Icons.Rounded.Bolt
                }

                val iconTint = when {
                    log.protocol.contains("TLS", ignoreCase = true) || log.protocol.contains("HTTPS", ignoreCase = true) -> MaterialTheme.colorScheme.primary
                    log.protocol.contains("HTTP", ignoreCase = true) -> MaterialTheme.colorScheme.secondary
                    log.protocol.contains("DNS", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
                    log.protocol.contains("Torrent", ignoreCase = true) || log.protocol.contains("P2P", ignoreCase = true) -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }

                ExpressiveCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(iconTint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = protocolIcon,
                                contentDescription = log.protocol,
                                tint = iconTint,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = log.domain,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${log.protocol}:${log.port} • ${log.formattedBytes()} • ${timeFormatter.format(java.util.Date(log.timestamp))}",
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        ExpressiveChip(
                            text = log.technique,
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}
}