package com.sourzap.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
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
    AURORA_BOREALIS("AURORA_BOREALIS", "Aurora Glow", "Northern teal & violet glow"),
    OLED_MONOCHROME("OLED_MONOCHROME", "OLED Monochrome", "Pure black #000000 with crisp white & silver accents"),
    OLED_AMBER("OLED_AMBER", "OLED Amber Gold", "Pure black #000000 with glowing warm amber"),
    OLED_EMERALD("OLED_EMERALD", "OLED Neon Emerald", "Pure black #000000 with glowing matrix green"),
    CUSTOM("CUSTOM", "Custom Palette", "Custom accent with optional OLED black");

    val isOledPreset: Boolean
        get() = this == AMOLED_BLACK || this == OLED_MONOCHROME || this == OLED_AMBER || this == OLED_EMERALD
}

/**
 * Mathematically builds a harmonious Material 3 ColorScheme from custom user selections.
 * Guarantees contrast on all surfaces, backgrounds, buttons, and text.
 */
fun buildCustomColorScheme(
    primaryColor: Color,
    bgMode: String,
    systemInDark: Boolean
): ColorScheme {
    val isDark = when (bgMode) {
        "LIGHT" -> false
        "DARK_SLATE", "OLED" -> true
        else -> systemInDark
    }
    val isOled = bgMode == "OLED" || (bgMode == "SYSTEM" && isDark)

    val r = primaryColor.red
    val g = primaryColor.green
    val b = primaryColor.blue
    val primaryLum = 0.299f * r + 0.587f * g + 0.114f * b

    // High-contrast text on primary: light colors get dark text, dark colors get white text
    val onPrimaryColor = if (primaryLum > 0.5f) Color(0xFF121214) else Color(0xFFFFFFFF)

    return if (isDark) {
        val bgColor = if (isOled) Color(0xFF000000) else Color(0xFF101216)
        val surfaceColor = if (isOled) Color(0xFF000000) else Color(0xFF101216)
        val surfaceVariantColor = if (isOled) Color(0xFF14171A) else Color(0xFF262B34)
        val surfaceContainerColor = if (isOled) Color(0xFF0A0C0E) else Color(0xFF181B22)
        val surfaceContainerHighColor = if (isOled) Color(0xFF121518) else Color(0xFF20242D)
        val surfaceContainerHighestColor = if (isOled) Color(0xFF1A1E22) else Color(0xFF282D37)

        val primaryContainerColor = primaryColor.copy(alpha = 0.25f)
        val onPrimaryContainerColor = if (primaryLum > 0.6f) primaryColor else Color(0xFFFFFFFF)

        val secondaryColor = Color(
            red = (r * 0.7f + 0.25f).coerceIn(0f, 1f),
            green = (g * 0.7f + 0.25f).coerceIn(0f, 1f),
            blue = (b * 0.7f + 0.25f).coerceIn(0f, 1f)
        )
        val secLum = 0.299f * secondaryColor.red + 0.587f * secondaryColor.green + 0.114f * secondaryColor.blue
        val onSecondaryColor = if (secLum > 0.5f) Color(0xFF121214) else Color(0xFFFFFFFF)

        darkColorScheme(
            primary = primaryColor,
            onPrimary = onPrimaryColor,
            primaryContainer = primaryContainerColor,
            onPrimaryContainer = onPrimaryContainerColor,
            secondary = secondaryColor,
            onSecondary = onSecondaryColor,
            secondaryContainer = secondaryColor.copy(alpha = 0.22f),
            onSecondaryContainer = Color(0xFFFFFFFF),
            tertiary = primaryColor,
            onTertiary = onPrimaryColor,
            tertiaryContainer = primaryContainerColor,
            onTertiaryContainer = onPrimaryContainerColor,
            background = bgColor,
            onBackground = Color(0xFFFFFFFF),
            surface = surfaceColor,
            onSurface = Color(0xFFFFFFFF),
            surfaceVariant = surfaceVariantColor,
            onSurfaceVariant = Color(0xFFC4C7D0),
            surfaceContainer = surfaceContainerColor,
            surfaceContainerHigh = surfaceContainerHighColor,
            surfaceContainerHighest = surfaceContainerHighestColor,
            outline = Color(0xFF8C9199),
            outlineVariant = Color(0xFF3F444D)
        )
    } else {
        // Light Mode: ensure text is dark and background is bright
        val bgColor = Color(0xFFF8F9FA)
        val surfaceColor = Color(0xFFFFFFFF)
        val surfaceVariantColor = Color(0xFFE2E4E8)
        val surfaceContainerColor = Color(0xFFF1F3F6)
        val surfaceContainerHighColor = Color(0xFFE9EBEF)
        val surfaceContainerHighestColor = Color(0xFFE1E4E9)

        val lightModePrimary = if (primaryLum > 0.65f) {
            Color(
                red = (r * 0.84f).coerceIn(0f, 1f),
                green = (g * 0.84f).coerceIn(0f, 1f),
                blue = (b * 0.84f).coerceIn(0f, 1f)
            )
        } else {
            primaryColor
        }
        val lightPrimaryLum = 0.299f * lightModePrimary.red + 0.587f * lightModePrimary.green + 0.114f * lightModePrimary.blue
        val lightOnPrimary = if (lightPrimaryLum > 0.5f) Color(0xFF121214) else Color(0xFFFFFFFF)

        lightColorScheme(
            primary = lightModePrimary,
            onPrimary = lightOnPrimary,
            primaryContainer = primaryColor.copy(alpha = 0.18f),
            onPrimaryContainer = Color(0xFF191C1E),
            secondary = Color(0xFF4F616E),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFDCE4EC),
            onSecondaryContainer = Color(0xFF0B1D29),
            tertiary = Color(0xFF63597C),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFE9DDFF),
            onTertiaryContainer = Color(0xFF1F1635),
            background = bgColor,
            onBackground = Color(0xFF191C1E),
            surface = surfaceColor,
            onSurface = Color(0xFF191C1E),
            surfaceVariant = surfaceVariantColor,
            onSurfaceVariant = Color(0xFF44474E),
            surfaceContainer = surfaceContainerColor,
            surfaceContainerHigh = surfaceContainerHighColor,
            surfaceContainerHighest = surfaceContainerHighestColor,
            outline = Color(0xFF74777F),
            outlineVariant = Color(0xFFC4C7D0)
        )
    }
}

/**
 * Single source of truth resolving exact ColorScheme for presets.
 */
fun getThemeColorScheme(
    preset: AppThemePreset,
    darkTheme: Boolean,
    context: android.content.Context,
    customPrimary: Long = 0xFFFFD600L,
    customBgMode: String = "OLED"
): ColorScheme {
    val isDynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    return when {
        // OLED Presets force pitch black background in dark mode or always
        preset == AppThemePreset.AMOLED_BLACK -> AmoledDarkColorScheme
        preset == AppThemePreset.OLED_MONOCHROME -> if (darkTheme) OledMonochromeDarkColorScheme else OledMonochromeLightColorScheme
        preset == AppThemePreset.OLED_AMBER -> if (darkTheme) OledAmberDarkColorScheme else OledAmberLightColorScheme
        preset == AppThemePreset.OLED_EMERALD -> if (darkTheme) OledEmeraldDarkColorScheme else OledEmeraldLightColorScheme
        preset == AppThemePreset.CUSTOM -> buildCustomColorScheme(Color(customPrimary), customBgMode, darkTheme)

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
    customPrimary: Long = 0xFFFFD600L,
    customBgMode: String = "OLED",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val preset = AppThemePreset.entries.firstOrNull { it.id == themePreset } ?: AppThemePreset.DYNAMIC

    // Determine if effective theme requires dark status bar
    val isEffectiveDark = when {
        preset.isOledPreset -> true
        preset == AppThemePreset.CUSTOM -> when (customBgMode) {
            "LIGHT" -> false
            "DARK_SLATE", "OLED" -> true
            else -> darkTheme
        }
        else -> darkTheme
    }
    val colorScheme: ColorScheme = getThemeColorScheme(preset, darkTheme, context, customPrimary, customBgMode)


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