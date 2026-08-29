package com.sourzap.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ExpressiveDarkColorScheme = darkColorScheme(
    primary = ElectricViolet,
    onPrimary = TextPrimary,
    primaryContainer = ElectricVioletContainer,
    onPrimaryContainer = ElectricVioletLight,
    secondary = NeonMint,
    onSecondary = DarkBackground,
    secondaryContainer = NeonMintContainer,
    onSecondaryContainer = NeonMintLight,
    tertiary = CandyCoral,
    onTertiary = TextPrimary,
    tertiaryContainer = CandyCoralContainer,
    onTertiaryContainer = CandyCoralLight,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkSurfaceContainerHighest,
    outlineVariant = DarkSurfaceContainerHigh
)

@Composable
fun SourZapTheme(
    darkTheme: Boolean = true, // Expressive dark mode hero
    content: @Composable () -> Unit
) {
    val colorScheme = ExpressiveDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBackground.toArgb()
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ExpressiveTypography,
        content = content
    )
}