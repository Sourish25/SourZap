package com.sourzap.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sourzap.app.torrent.core.TorrentIntentParser
import com.sourzap.app.torrent.model.PendingTorrentIntent
import com.sourzap.app.ui.components.FloatingExpressiveDock
import com.sourzap.app.ui.dashboard.DashboardScreen
import com.sourzap.app.ui.settings.SettingsScreen
import com.sourzap.app.ui.speedtest.SpeedTestScreen
import com.sourzap.app.ui.theme.SourZapTheme
import com.sourzap.app.ui.torrent.TorrentScreen
import com.sourzap.app.ui.traffic.TrafficScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {

    private val _pendingTorrentIntent = MutableStateFlow<PendingTorrentIntent?>(null)
    val pendingTorrentIntent: StateFlow<PendingTorrentIntent?> = _pendingTorrentIntent.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIncomingTorrentIntent(intent)

        setContent {
            val app = SourZapApp.instance
            val settingsRepo = app.settingsRepository

            val themePreset by settingsRepo.themePreset.collectAsStateWithLifecycle()
            val darkModePref by settingsRepo.darkModePref.collectAsStateWithLifecycle()
            val systemInDark = isSystemInDarkTheme()

            val isDark = when (darkModePref) {
                "DARK" -> true
                "LIGHT" -> false
                else -> systemInDark
            }

            SourZapTheme(
                themePreset = themePreset,
                darkTheme = isDark
            ) {
                MainAppScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingTorrentIntent(intent)
    }

    fun handleIncomingTorrentIntent(intent: Intent?) {
        val parsed = TorrentIntentParser.parseIntent(intent, contentResolver)
        if (parsed != null) {
            _pendingTorrentIntent.value = parsed
            SourZapApp.instance.setPendingTorrentIntent(parsed)
        }
    }

    companion object {
        fun shouldRequestNotificationPermission(sdkInt: Int, isPermissionGranted: Boolean): Boolean {
            return sdkInt >= 33 && !isPermissionGranted
        }
    }
}

@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "dashboard"

    val app = SourZapApp.instance
    val pendingTorrentIntent by app.pendingTorrentIntent.collectAsStateWithLifecycle()

    // Android 13+ (API 33+) POST_NOTIFICATIONS runtime permission request
    if (Build.VERSION.SDK_INT >= 33) {
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { _ ->
            // Notification permission result handled gracefully
        }

        LaunchedEffect(Unit) {
            val isGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (MainActivity.shouldRequestNotificationPermission(Build.VERSION.SDK_INT, isGranted)) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Auto-navigate to "torrents" tab when a pending torrent intent is received
    LaunchedEffect(pendingTorrentIntent) {
        if (pendingTorrentIntent != null && currentRoute != "torrents") {
            navController.navigate("torrents") {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(animationSpec = tween(280)) },
            exitTransition = { fadeOut(animationSpec = tween(280)) }
        ) {
            composable("dashboard") {
                DashboardScreen(
                    onNavigateToSpeedTest = { navController.navigate("speedtest") },
                    onNavigateToTraffic = { navController.navigate("traffic") }
                )
            }
            composable("torrents") {
                TorrentScreen()
            }
            composable("speedtest") {
                SpeedTestScreen()
            }
            composable("traffic") {
                TrafficScreen()
            }
            composable("settings") {
                SettingsScreen()
            }
        }

        // Floating Expressive Bottom Navigation Dock
        FloatingExpressiveDock(
            currentRoute = currentRoute,
            onNavigate = { route ->
                if (currentRoute != route) {
                    val popped = if (route == "dashboard") {
                        navController.popBackStack("dashboard", inclusive = false)
                    } else {
                        false
                    }
                    if (!popped) {
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}