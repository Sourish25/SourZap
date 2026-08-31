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

enum class AppThemePreset(
    val id: String,
    val displayName: String,
    val primaryColor: Long,
    val secondaryColor: Long
) {
    DYNAMIC("DYNAMIC", "System Wallpaper", 0xFF6750A4, 0xFF7D5260),
    ELECTRIC_INDIGO("ELECTRIC_INDIGO", "Electric Indigo", 0xFF6366F1, 0xFF00E5FF),
    CYBER_MINT("CYBER_MINT", "Cyber Mint", 0xFF00E676, 0xFF00BFA5),
    BERRY_EXPRESSIVE("BERRY_EXPRESSIVE", "Berry Vivid", 0xFFE040FB, 0xFFFF4081),
    SUNSET_TERRACOTTA("SUNSET_TERRACOTTA", "Sunset Amber", 0xFFFF9100, 0xFFFF6D00),
    OCEANIC_CYAN("OCEANIC_CYAN", "Oceanic Cyan", 0xFF00E5FF, 0xFF0288D1),
    FOREST_EMERALD("FOREST_EMERALD", "Forest Emerald", 0xFF2E7D32, 0xFF66BB6A),
    CRIMSON_VELVET("CRIMSON_VELVET", "Crimson Velvet", 0xFFD32F2F, 0xFFFF5252),
    SOLAR_GOLD("SOLAR_GOLD", "Solar Gold", 0xFFFFB300, 0xFFFFA000),
    NORDIC_FROST("NORDIC_FROST", "Nordic Frost", 0xFF88C0D0, 0xFF81A1C1),
    AMOLED_BLACK("AMOLED_BLACK", "AMOLED Black", 0xFF000000, 0xFFFFFFFF),
    MIDNIGHT_SYNTH("MIDNIGHT_SYNTH", "Midnight Synth", 0xFF7C4DFF, 0xFFFF1744),
    LAVENDER_DREAM("LAVENDER_DREAM", "Lavender Dream", 0xFFB388FF, 0xFF82B1FF),
    SAKURA_BLOSSOM("SAKURA_BLOSSOM", "Sakura Blossom", 0xFFF48FB1, 0xFFFFAB91),
    COFFEE_MOCHA("COFFEE_MOCHA", "Coffee Mocha", 0xFF8D6E63, 0xFFD7CCC8),
    AURORA_BOREALIS("AURORA_BOREALIS", "Aurora Glow", 0xFF00BFA5, 0xFF7C4DFF)
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
        themePreset == AppThemePreset.AMOLED_BLACK.id -> {
            if (darkTheme) AmoledDarkColorScheme else AmoledLightColorScheme
        }
        themePreset == AppThemePreset.NORDIC_FROST.id -> {
            if (darkTheme) NordicFrostDarkColorScheme else NordicFrostLightColorScheme
        }
        themePreset == AppThemePreset.FOREST_EMERALD.id -> {
            if (darkTheme) ForestEmeraldDarkColorScheme else ForestEmeraldLightColorScheme
        }
        themePreset == AppThemePreset.CRIMSON_VELVET.id -> {
            if (darkTheme) CrimsonVelvetDarkColorScheme else CrimsonVelvetLightColorScheme
        }
        themePreset == AppThemePreset.SOLAR_GOLD.id -> {
            if (darkTheme) SolarGoldDarkColorScheme else SolarGoldLightColorScheme
        }
        themePreset == AppThemePreset.MIDNIGHT_SYNTH.id -> {
            if (darkTheme) MidnightSynthDarkColorScheme else MidnightSynthLightColorScheme
        }
        themePreset == AppThemePreset.LAVENDER_DREAM.id -> {
            if (darkTheme) LavenderDreamDarkColorScheme else LavenderDreamLightColorScheme
        }
        themePreset == AppThemePreset.SAKURA_BLOSSOM.id -> {
            if (darkTheme) SakuraBlossomDarkColorScheme else SakuraBlossomLightColorScheme
        }
        themePreset == AppThemePreset.COFFEE_MOCHA.id -> {
            if (darkTheme) CoffeeMochaDarkColorScheme else CoffeeMochaLightColorScheme
        }
        themePreset == AppThemePreset.AURORA_BOREALIS.id -> {
            if (darkTheme) AuroraBorealisDarkColorScheme else AuroraBorealisLightColorScheme
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
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
                if (Build.VERSION.SDK_INT < 35) {
                    @Suppress("DEPRECATION")
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpressiveTypography,
        content = content
    )
}