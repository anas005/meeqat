package com.anas.meeqat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = DarkPalette.forest,
    onPrimary = DarkPalette.ink,
    secondary = DarkPalette.sun,
    onSecondary = DarkPalette.background,
    tertiary = DarkPalette.chartAccent,
    background = DarkPalette.background,
    surface = DarkPalette.surface,
    onSurface = DarkPalette.ink,
    surfaceVariant = DarkPalette.tint,
    onSurfaceVariant = DarkPalette.muted,
)

private val LightColorScheme = lightColorScheme(
    primary = LightPalette.forest,
    onPrimary = Color.White,
    secondary = LightPalette.sun,
    onSecondary = LightPalette.ink,
    tertiary = LightPalette.chartAccent,
    background = LightPalette.background,
    surface = LightPalette.surface,
    onSurface = LightPalette.ink,
    surfaceVariant = LightPalette.tint,
    onSurfaceVariant = LightPalette.muted,
)

@Composable
fun MeeqatTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalMeeqatPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content,
        )
    }
}