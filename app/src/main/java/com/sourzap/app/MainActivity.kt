package com.sourzap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sourzap.app.ui.components.FloatingExpressiveDock
import com.sourzap.app.ui.dashboard.DashboardScreen
import com.sourzap.app.ui.settings.SettingsScreen
import com.sourzap.app.ui.speedtest.SpeedTestScreen
import com.sourzap.app.ui.strategies.StrategiesScreen
import com.sourzap.app.ui.theme.DarkBackground
import com.sourzap.app.ui.theme.SourZapTheme
import com.sourzap.app.ui.traffic.TrafficScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SourZapTheme {
                MainAppScreen()
            }
        }
    }
}

@Composable
fun MainAppScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "dashboard"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
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
                    onNavigateToStrategies = { navController.navigate("strategies") },
                    onNavigateToTraffic = { navController.navigate("traffic") }
                )
            }
            composable("speedtest") {
                SpeedTestScreen()
            }
            composable("strategies") {
                StrategiesScreen()
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
                    navController.navigate(route) {
                        popUpTo("dashboard") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}