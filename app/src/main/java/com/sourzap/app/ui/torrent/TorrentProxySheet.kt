package com.sourzap.app.ui.torrent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.SourZapApp
import com.sourzap.app.torrent.model.ProxyType
import com.sourzap.app.torrent.model.TorrentProxyConfig
import com.sourzap.app.ui.components.ExpressiveCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrentProxySheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = SourZapApp.instance
    val proxyRepo = app.torrentProxyRepository
    val torrentManager = app.torrentEngineManager

    val currentConfig = proxyRepo.config.value
    var isSnowflakeEnabled by remember { mutableStateOf(currentConfig.enabled && currentConfig.isSnowflakePreset) }
    var isCustomProxyEnabled by remember { mutableStateOf(currentConfig.enabled && !currentConfig.isSnowflakePreset) }
    var proxyType by remember { mutableStateOf(if (currentConfig.isSnowflakePreset) ProxyType.SOCKS5 else currentConfig.type) }
    var hostText by remember { mutableStateOf(if (currentConfig.isSnowflakePreset) "127.0.0.1" else currentConfig.host) }
    var portText by remember { mutableStateOf(if (currentConfig.isSnowflakePreset) "9050" else currentConfig.port.toString()) }
    var proxyPeers by remember { mutableStateOf(currentConfig.proxyPeers) }
    var proxyTrackers by remember { mutableStateOf(currentConfig.proxyTrackers) }
    var proxyHostnames by remember { mutableStateOf(currentConfig.proxyHostnames) }

    val isOrbotInstalled = remember(context) {
        try {
            context.packageManager.getPackageInfo("org.torproject.android", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Network & Proxy Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Bypass ISP port blocking & tracker censorship",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // --- Card 1: Snowflake / Orbot Mode ---
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = if (isSnowflakeEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                borderColor = if (isSnowflakeEnabled) MaterialTheme.colorScheme.primary else Color.Transparent
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("❄️", fontSize = 18.sp)
                            }
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "Snowflake (Orbot) Mode",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "FREE",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Bypasses BSNL port blocks over Port 443 via WebRTC",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = isSnowflakeEnabled,
                            onCheckedChange = { checked ->
                                isSnowflakeEnabled = checked
                                if (checked) {
                                    isCustomProxyEnabled = false
                                }
                            }
                        )
                    }

                    if (isSnowflakeEnabled) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "How to connect via Snowflake:",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "1. Open Orbot → Tap Settings ⚙️\n2. Select 'Use Bridges' → Choose Snowflake ❄️\n3. Tap START in Orbot (Wait for 100% connected)\n4. SourZap will route peers automatically through 127.0.0.1:9050.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Button(
                                    onClick = {
                                        if (isOrbotInstalled) {
                                            val launchIntent = context.packageManager.getLaunchIntentForPackage("org.torproject.android")
                                            if (launchIntent != null) {
                                                context.startActivity(launchIntent)
                                            } else {
                                                Toast.makeText(context, "Could not open Orbot", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.torproject.android"))
                                            try {
                                                context.startActivity(marketIntent)
                                            } catch (_: Exception) {
                                                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://orbot.app"))
                                                context.startActivity(webIntent)
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isOrbotInstalled) Icons.AutoMirrored.Rounded.Launch else Icons.Rounded.Security,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isOrbotInstalled) "Open Orbot App" else "Install Orbot (Free)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // --- Card 2: Custom SOCKS5 / HTTP Proxy ---
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = if (isCustomProxyEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                borderColor = if (isCustomProxyEnabled) MaterialTheme.colorScheme.primary else Color.Transparent
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.VpnKey,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Custom SOCKS5 / HTTP Proxy",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Route torrents through your own proxy server",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Switch(
                            checked = isCustomProxyEnabled,
                            onCheckedChange = { checked ->
                                isCustomProxyEnabled = checked
                                if (checked) {
                                    isSnowflakeEnabled = false
                                }
                            }
                        )
                    }

                    if (isCustomProxyEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Proxy Type pills
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(ProxyType.SOCKS5, ProxyType.HTTP).forEach { type ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (proxyType == type) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceContainerHighest
                                            )
                                            .clickable { proxyType = type }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = type.displayName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = if (proxyType == type) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = hostText,
                                    onValueChange = { hostText = it },
                                    label = { Text("Host / IP") },
                                    singleLine = true,
                                    modifier = Modifier.weight(2f),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                OutlinedTextField(
                                    value = portText,
                                    onValueChange = { portText = it.filter { c -> c.isDigit() } },
                                    label = { Text("Port") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }

                            // Checkbox Options
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { proxyPeers = !proxyPeers }
                                ) {
                                    Checkbox(checked = proxyPeers, onCheckedChange = { proxyPeers = it })
                                    Text(
                                        text = "Proxy Peer Connections (Recommended)",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { proxyTrackers = !proxyTrackers }
                                ) {
                                    Checkbox(checked = proxyTrackers, onCheckedChange = { proxyTrackers = it })
                                    Text(
                                        text = "Proxy Tracker Announces",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { proxyHostnames = !proxyHostnames }
                                ) {
                                    Checkbox(checked = proxyHostnames, onCheckedChange = { proxyHostnames = it })
                                    Text(
                                        text = "Resolve DNS through Proxy (Anti-DNS Poisoning)",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val disabledConfig = TorrentProxyConfig.DEFAULT
                        proxyRepo.saveConfig(disabledConfig)
                        torrentManager.updateProxySettings(disabledConfig)
                        Toast.makeText(context, "Proxy disabled (Direct P2P active)", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Direct (Off)", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = {
                        val newConfig = when {
                            isSnowflakeEnabled -> TorrentProxyConfig.SNOWFLAKE_ORBOT
                            isCustomProxyEnabled -> {
                                val parsedPort = portText.toIntOrNull() ?: 9050
                                TorrentProxyConfig(
                                    enabled = true,
                                    type = proxyType,
                                    host = hostText.trim().ifBlank { "127.0.0.1" },
                                    port = parsedPort,
                                    proxyPeers = proxyPeers,
                                    proxyTrackers = proxyTrackers,
                                    proxyHostnames = proxyHostnames,
                                    isSnowflakePreset = false
                                )
                            }
                            else -> TorrentProxyConfig.DEFAULT
                        }

                        proxyRepo.saveConfig(newConfig)
                        torrentManager.updateProxySettings(newConfig)

                        val feedbackMsg = if (newConfig.enabled) {
                            if (newConfig.isSnowflakePreset) "Snowflake (Orbot) proxy activated!"
                            else "${newConfig.type.name} proxy (${newConfig.host}:${newConfig.port}) activated!"
                        } else {
                            "Direct P2P connection active"
                        }
                        Toast.makeText(context, feedbackMsg, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Apply Settings", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
