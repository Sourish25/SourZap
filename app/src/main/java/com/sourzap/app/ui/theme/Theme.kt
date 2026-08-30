package com.sourzap.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

enum class AppThemePreset(val id: String, val displayName: String, val previewColor: Long) {
    DYNAMIC("DYNAMIC", "System Dynamic (Monet)", 0xFF6750A4),
    ELECTRIC_INDIGO("ELECTRIC_INDIGO", "Electric Indigo", 0xFF6366F1),
    CYBER_MINT("CYBER_MINT", "Cyber Mint", 0xFF00E676),
    BERRY_EXPRESSIVE("BERRY_EXPRESSIVE", "Berry Expressive", 0xFFE040FB),
    SUNSET_TERRACOTTA("SUNSET_TERRACOTTA", "Sunset Amber", 0xFFFF9100),
    OCEANIC_CYAN("OCEANIC_CYAN", "Oceanic Slate", 0xFF00E5FF)
}

@Composable
fun SourZapTheme(
    themePreset: String = "DYNAMIC",
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val isDynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme: ColorScheme = when {
        themePreset == AppThemePreset.DYNAMIC.id && isDynamicAvailable -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        themePreset == AppThemePreset.CYBER_MINT.id -> {
            if (darkTheme) CyberMintDarkColorScheme else CyberMintLightColorScheme
        }
        themePreset == AppThemePreset.BERRY_EXPRESSIVE.id -> {
            if (darkTheme) BerryDarkColorScheme else BerryLightColorScheme
        }
        themePreset == AppThemePreset.SUNSET_TERRACOTTA.id -> {
            if (darkTheme) SunsetDarkColorScheme else SunsetLightColorScheme
        }
        themePreset == AppThemePreset.OCEANIC_CYAN.id -> {
            if (darkTheme) OceanicDarkColorScheme else OceanicLightColorScheme
        }
        else -> {
            // Default Electric Indigo
            if (darkTheme) ElectricIndigoDarkColorScheme else ElectricIndigoLightColorScheme
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            var ctx = view.context
            var activity: Activity? = null
            while (ctx is android.content.ContextWrapper) {
                if (ctx is Activity) {
                    activity = ctx
                    break
                }
                ctx = ctx.baseContext
            }
            activity?.window?.let { window ->
                window.statusBarColor = colorScheme.surface.toArgb()
                window.navigationBarColor = colorScheme.surfaceContainer.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpressiveTypography,
        content = content
    )
}