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
 * Mathematical Contrast & Legibility Engine implementing perceptual luminance and WCAG AA contrast standards.
 */
object ContrastEngine {
    /**
     * Standard perceptual luminance formula: 0.299*R + 0.587*G + 0.114*B
     */
    fun calculatePerceptualLuminance(color: Color): Float {
        return (0.299f * color.red + 0.587f * color.green + 0.114f * color.blue).coerceIn(0f, 1f)
    }

    /**
     * Calculates WCAG AA compliant high-contrast text/icon color for a given surface/container color.
     * High luminance (> 0.5) yields crisp dark (#121214).
     * Low luminance (<= 0.5) yields crisp pure white (#FFFFFF).
     */
    fun getHighContrastContentColor(backgroundColor: Color): Color {
        val lum = calculatePerceptualLuminance(backgroundColor)
        return if (lum > 0.5f) Color(0xFF121214) else Color(0xFFFFFFFF)
    }

    /**
     * Calculates the WCAG contrast ratio between foreground and background colors.
     * Relative luminance L = 0.2126*R_lin + 0.7152*G_lin + 0.0722*B_lin
     * Ratio = (L1 + 0.05) / (L2 + 0.05)
     */
    fun calculateWcagContrastRatio(foreground: Color, background: Color): Float {
        fun toLinear(c: Float): Float {
            return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055) / 1.055).toDouble(), 2.4).toFloat()
        }
        val l1 = 0.2126f * toLinear(foreground.red) + 0.7152f * toLinear(foreground.green) + 0.0722f * toLinear(foreground.blue)
        val l2 = 0.2126f * toLinear(background.red) + 0.7152f * toLinear(background.green) + 0.0722f * toLinear(background.blue)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    fun isWcagAaCompliant(foreground: Color, background: Color, isLargeText: Boolean = false): Boolean {
        val ratio = calculateWcagContrastRatio(foreground, background)
        return if (isLargeText) ratio >= 3.0f else ratio >= 4.5f
    }

    /**
     * Normalizes a hex color string from various formats (e.g. #RRGGBB, RRGGBB, #AARRGGBB, 0xRRGGBB, #RRGGBBAA, #RGB)
     * into a canonical 6-digit uppercase RRGGBB hex string.
     * Strips surrounding quotes, semicolons, and whitespace.
     * Returns 6-character uppercase hex string, or null if invalid.
     */
    fun normalizeHexColor(raw: String?): String? {
        if (raw == null) return null
        var s = raw.trim().trimEnd(';', ',').trim()
        s = s.removeSurrounding("\"", "\"").removeSurrounding("'", "'").trimEnd(';', ',').trim()
        if (s.startsWith("#")) {
            s = s.removePrefix("#").trim()
        } else if (s.startsWith("0x", ignoreCase = true)) {
            s = s.substring(2).trim()
        }
        if (s.isNotEmpty() && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            val hex = s.uppercase()
            return when (hex.length) {
                6 -> hex
                8 -> {
                    // If starts with FF (standard ARGB with full opacity), strip alpha prefix
                    if (hex.startsWith("FF")) {
                        hex.substring(2, 8)
                    } else if (hex.endsWith("FF")) {
                        // If ends with FF (standard RGBA with full opacity), take RGB prefix
                        hex.substring(0, 6)
                    } else {
                        // Default to Android ARGB convention: strip alpha channel
                        hex.substring(2, 8)
                    }
                }
                3 -> {
                    // Expand 3-digit #RGB shorthand to #RRGGBB
                    "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
                }
                else -> null
            }
        }

        // Support CSS rgb(r, g, b) and rgba(r, g, b, a) clipboard formats
        val rgbRegex = Regex("""rgba?\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*[\d.]+\s*)?\)""", RegexOption.IGNORE_CASE)
        val rgbMatch = rgbRegex.find(raw)
        if (rgbMatch != null) {
            val (rStr, gStr, bStr) = rgbMatch.destructured
            val r = rStr.toIntOrNull()
            val g = gStr.toIntOrNull()
            val b = bStr.toIntOrNull()
            if (r != null && g != null && b != null && r in 0..255 && g in 0..255 && b in 0..255) {
                return String.format(java.util.Locale.US, "%02X%02X%02X", r, g, b)
            }
        }

        // Support embedded hex colors in rich text / HTML / CSS (e.g. "color: #FFD600;", style="color:#00E5FF", 0xFFFFD600)
        val hexRegex = Regex("""(?<![0-9A-Fa-f])(?:#|0x)([0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{3})(?![0-9A-Fa-f])""")
        val match = hexRegex.find(raw)
        if (match != null) {
            val matchedHex = match.groupValues[1].uppercase()
            return when (matchedHex.length) {
                6 -> matchedHex
                8 -> {
                    if (matchedHex.startsWith("FF")) {
                        matchedHex.substring(2, 8)
                    } else if (matchedHex.endsWith("FF")) {
                        matchedHex.substring(0, 6)
                    } else {
                        matchedHex.substring(2, 8)
                    }
                }
                3 -> "${matchedHex[0]}${matchedHex[0]}${matchedHex[1]}${matchedHex[1]}${matchedHex[2]}${matchedHex[2]}"
                else -> null
            }
        }

        return null
    }

    /**
     * Parses a hex color string into a 0xFFRRGGBB Long value, or null if invalid.
     */
    fun parseHexColor(raw: String?): Long? {
        val normalized = normalizeHexColor(raw) ?: return null
        return try {
            ("FF" + normalized).toLong(16)
        } catch (_: Throwable) {
            null
        }
    }
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
    val isOled = bgMode == "OLED"

    val primaryLum = ContrastEngine.calculatePerceptualLuminance(primaryColor)

    // High-contrast text on primary: light colors get dark text, dark colors get white text
    val onPrimaryColor = ContrastEngine.getHighContrastContentColor(primaryColor)

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
            red = (primaryColor.red * 0.7f + 0.25f).coerceIn(0f, 1f),
            green = (primaryColor.green * 0.7f + 0.25f).coerceIn(0f, 1f),
            blue = (primaryColor.blue * 0.7f + 0.25f).coerceIn(0f, 1f)
        )
        val onSecondaryColor = ContrastEngine.getHighContrastContentColor(secondaryColor)

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
                red = (primaryColor.red * 0.84f).coerceIn(0f, 1f),
                green = (primaryColor.green * 0.84f).coerceIn(0f, 1f),
                blue = (primaryColor.blue * 0.84f).coerceIn(0f, 1f)
            )
        } else {
            primaryColor
        }
        val lightOnPrimary = ContrastEngine.getHighContrastContentColor(lightModePrimary)

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