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
    val description: String
) {
    DYNAMIC("DYNAMIC", "System Wallpaper", "Adaptive system dynamic tones"),
    AMOLED_BLACK("AMOLED_BLACK", "AMOLED Pitch Black", "True OLED pitch black & electric cyan"),
    ELECTRIC_INDIGO("ELECTRIC_INDIGO", "Electric Indigo", "Vivid indigo & electric violet"),
    CYBER_MINT("CYBER_MINT", "Cyber Mint", "Neon mint & deep slate jade"),
    OCEANIC_CYAN("OCEANIC_CYAN", "Oceanic Cyan", "Bright ocean cyan & deep marine"),
    SUNSET_TERRACOTTA("SUNSET_TERRACOTTA", "Sunset Amber", "Warm amber & coral flame"),
    BERRY_EXPRESSIVE("BERRY_EXPRESSIVE", "Berry Vivid", "Raspberry magenta & plum rose"),
    CRIMSON_VELVET("CRIMSON_VELVET", "Crimson Velvet", "Ruby crimson & dark rose"),
    FOREST_EMERALD("FOREST_EMERALD", "Forest Emerald", "Lush emerald & deep evergreen"),
    MIDNIGHT_SYNTH("MIDNIGHT_SYNTH", "Midnight Synth", "Synthwave violet & neon pink"),
    SOLAR_GOLD("SOLAR_GOLD", "Solar Gold", "Radiant gold & warm amber"),
    NORDIC_FROST("NORDIC_FROST", "Nordic Frost", "Arctic glacier & polar slate"),
    LAVENDER_DREAM("LAVENDER_DREAM", "Lavender Dream", "Pastel lavender & periwinkle"),
    SAKURA_BLOSSOM("SAKURA_BLOSSOM", "Sakura Blossom", "Cherry blossom & soft coral"),
    COFFEE_MOCHA("COFFEE_MOCHA", "Coffee Mocha", "Warm espresso & caramel cream"),
    AURORA_BOREALIS("AURORA_BOREALIS", "Aurora Glow", "Northern teal & violet glow")
}

/**
 * Single source of truth resolving exact ColorScheme for presets.
 */
fun getThemeColorScheme(
    preset: AppThemePreset,
    darkTheme: Boolean,
    context: android.content.Context
): ColorScheme {
    val isDynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    return when {
        // AMOLED Black is ALWAYS pitch black dark mode!
        preset == AppThemePreset.AMOLED_BLACK -> AmoledDarkColorScheme

        preset == AppThemePreset.DYNAMIC && isDynamicAvailable -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        preset == AppThemePreset.CYBER_MINT -> if (darkTheme) CyberMintDarkColorScheme else CyberMintLightColorScheme
        preset == AppThemePreset.BERRY_EXPRESSIVE -> if (darkTheme) BerryDarkColorScheme else BerryLightColorScheme
        preset == AppThemePreset.SUNSET_TERRACOTTA -> if (darkTheme) SunsetDarkColorScheme else SunsetLightColorScheme
        preset == AppThemePreset.OCEANIC_CYAN -> if (darkTheme) OceanicDarkColorScheme else OceanicLightColorScheme
        preset == AppThemePreset.FOREST_EMERALD -> if (darkTheme) ForestEmeraldDarkColorScheme else ForestEmeraldLightColorScheme
        preset == AppThemePreset.CRIMSON_VELVET -> if (darkTheme) CrimsonVelvetDarkColorScheme else CrimsonVelvetLightColorScheme
        preset == AppThemePreset.SOLAR_GOLD -> if (darkTheme) SolarGoldDarkColorScheme else SolarGoldLightColorScheme
        preset == AppThemePreset.NORDIC_FROST -> if (darkTheme) NordicFrostDarkColorScheme else NordicFrostLightColorScheme
        preset == AppThemePreset.MIDNIGHT_SYNTH -> if (darkTheme) MidnightSynthDarkColorScheme else MidnightSynthLightColorScheme
        preset == AppThemePreset.LAVENDER_DREAM -> if (darkTheme) LavenderDreamDarkColorScheme else LavenderDreamLightColorScheme
        preset == AppThemePreset.SAKURA_BLOSSOM -> if (darkTheme) SakuraBlossomDarkColorScheme else SakuraBlossomLightColorScheme
        preset == AppThemePreset.COFFEE_MOCHA -> if (darkTheme) CoffeeMochaDarkColorScheme else CoffeeMochaLightColorScheme
        preset == AppThemePreset.AURORA_BOREALIS -> if (darkTheme) AuroraBorealisDarkColorScheme else AuroraBorealisLightColorScheme
        else -> if (darkTheme) ElectricIndigoDarkColorScheme else ElectricIndigoLightColorScheme
    }
}

@Composable
fun SourZapTheme(
    themePreset: String = "DYNAMIC",
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val preset = AppThemePreset.values().firstOrNull { it.id == themePreset } ?: AppThemePreset.DYNAMIC

    // AMOLED Black always forces dark status bar and true black
    val isEffectiveDark = if (preset == AppThemePreset.AMOLED_BLACK) true else darkTheme
    val colorScheme: ColorScheme = getThemeColorScheme(preset, darkTheme, context)

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
                insetsController.isAppearanceLightStatusBars = !isEffectiveDark
                insetsController.isAppearanceLightNavigationBars = !isEffectiveDark
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