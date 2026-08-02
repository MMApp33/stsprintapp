package com.example.stsprint.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = BrandLimeDeep,
    onPrimary = Color.White,
    primaryContainer = BrandLimeMist,
    onPrimaryContainer = BrandInk,
    secondary = BrandForest,
    onSecondary = Color.White,
    secondaryContainer = SurfaceMuted,
    onSecondaryContainer = BrandInk,
    tertiary = StatusInfo,
    background = SurfaceCanvas,
    surface = SurfaceCard,
    surfaceVariant = SurfaceMuted,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    outline = Color(0xFFCDD5C2),
    error = StatusError,
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = BrandLime,
    onPrimary = BrandInk,
    primaryContainer = BrandForest,
    onPrimaryContainer = TextOnDark,
    secondary = Color(0xFFB7C7A6),
    onSecondary = BrandInk,
    secondaryContainer = Color(0xFF2C3628),
    onSecondaryContainer = TextOnDark,
    tertiary = Color(0xFF9BB8C4),
    background = Color(0xFF10150F),
    surface = Color(0xFF1A2218),
    surfaceVariant = Color(0xFF273026),
    onBackground = TextOnDark,
    onSurface = TextOnDark,
    onSurfaceVariant = Color(0xFFB8C3AE),
    outline = Color(0xFF4E5948),
    error = StatusError,
    onError = Color.White
)

@Composable
fun StsprintTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content
    )
}
