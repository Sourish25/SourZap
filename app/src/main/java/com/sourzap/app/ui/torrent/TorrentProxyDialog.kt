package com.sourzap.app.ui.torrent

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sourzap.app.torrent.model.ProxyType
import com.sourzap.app.torrent.model.TorrentProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

private sealed class TestResult {
    object Idle : TestResult()
    object Testing : TestResult()
    data class Success(val latencyMs: Long) : TestResult()
    data class Failure(val error: String) : TestResult()
}

@Composable
fun TorrentProxyDialog(
    initialConfig: TorrentProxyConfig,
    onDismiss: () -> Unit,
    onSave: (TorrentProxyConfig) -> Unit
) {
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(initialConfig.enabled) }
    var selectedType by remember { mutableStateOf(initialConfig.type.takeIf { it != ProxyType.NONE } ?: ProxyType.SOCKS5) }
    var host by remember { mutableStateOf(initialConfig.host) }
    var port by remember { mutableStateOf(if (initialConfig.port > 0) initialConfig.port.toString() else "1080") }
    var username by remember { mutableStateOf(initialConfig.username) }
    var password by remember { mutableStateOf(initialConfig.password) }
    var proxyPeers by remember { mutableStateOf(initialConfig.proxyPeers) }
    var proxyTrackers by remember { mutableStateOf(initialConfig.proxyTrackers) }

    var testResult by remember { mutableStateOf<TestResult>(TestResult.Idle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "Torrent Proxy Settings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "Route peer connections through SOCKS5/HTTP",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Master Enable Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Proxy",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (enabled) "Torrents will route through this proxy" else "Direct connections enabled",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }

                // Proxy Type Selector (SOCKS5 / HTTP)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Proxy Protocol",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(ProxyType.SOCKS5, ProxyType.HTTP).forEach { type ->
                            val isSelected = selectedType == type
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHighest
                                    )
                                    .clickable { selectedType = type }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = type.name,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Server Host and Port
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = {
                            host = it
                            testResult = TestResult.Idle
                        },
                        label = { Text("Server Host / IP") },
                        placeholder = { Text("e.g. 192.168.1.100") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = port,
                        onValueChange = {
                            if (it.all { c -> c.isDigit() } && it.length <= 5) {
                                port = it
                                testResult = TestResult.Idle
                            }
                        },
                        label = { Text("Port") },
                        placeholder = { Text("1080") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // Optional Authentication
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (Optional)") },
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (Optional)") },
                    leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Route options
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { proxyPeers = !proxyPeers },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = proxyPeers,
                        onCheckedChange = { proxyPeers = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Route Peer Data Connections",
                        fontSize = 13.sp
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { proxyTrackers = !proxyTrackers },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = proxyTrackers,
                        onCheckedChange = { proxyTrackers = it }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Route Tracker Announcements",
                        fontSize = 13.sp
                    )
                }

                // Test Connection Button & Result
                OutlinedButton(
                    onClick = {
                        val p = port.toIntOrNull()
                        if (host.isBlank() || p == null || p !in 1..65535) {
                            testResult = TestResult.Failure("Please enter a valid Host and Port")
                            return@OutlinedButton
                        }
                        testResult = TestResult.Testing
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val startTime = System.currentTimeMillis()
                                try {
                                    Socket().use { s ->
                                        s.connect(InetSocketAddress(host.trim(), p), 3000)
                                        val latency = System.currentTimeMillis() - startTime
                                        TestResult.Success(latency)
                                    }
                                } catch (e: Exception) {
                                    TestResult.Failure(e.message ?: "Connection timed out")
                                }
                            }
                            testResult = result
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (testResult == TestResult.Testing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Testing Reachability...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Rounded.Lan, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("Test Proxy Reachability", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                when (val res = testResult) {
                    is TestResult.Success -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE8F5E9))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                            Text("Proxy reachable! Latency: ${res.latencyMs} ms", color = Color(0xFF1B5E20), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    is TestResult.Failure -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFFEBEE))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Color(0xFFC62828), modifier = Modifier.size(16.dp))
                            Text("Unreachable: ${res.error}", color = Color(0xFFB71C1C), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    else -> {}
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val p = port.toIntOrNull() ?: 1080
                    val newConfig = TorrentProxyConfig(
                        enabled = enabled,
                        type = selectedType,
                        host = host.trim(),
                        port = p.coerceIn(1, 65535),
                        username = username.trim(),
                        password = password,
                        proxyPeers = proxyPeers,
                        proxyTrackers = proxyTrackers,
                        proxyHostnames = true
                    )
                    onSave(newConfig)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Save Settings", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}
