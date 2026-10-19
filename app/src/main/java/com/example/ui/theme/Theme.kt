package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    accentKey: String = "magenta",
    content: @Composable () -> Unit
) {
    val palette = getAccentPalette(accentKey)

    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.primaryLight,
            onPrimary = Color.White,
            primaryContainer = palette.darkSoftBg,
            onPrimaryContainer = palette.primaryLight,
            secondary = palette.primary,
            onSecondary = Color.White,
            secondaryContainer = palette.darkSoftBg,
            onSecondaryContainer = palette.primaryLight,
            tertiary = StatusEmerald,
            onTertiary = Color.White,
            background = DarkBackground,
            onBackground = DarkTextPrimary,
            surface = DarkSurface,
            onSurface = DarkTextPrimary,
            surfaceVariant = palette.darkSoftBg,
            onSurfaceVariant = DarkTextMuted,
            outline = DarkBorder,
            outlineVariant = DarkBorderSubtle,
            error = EmergencyCrimson,
            onError = Color.White
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = Color.White,
            primaryContainer = palette.lightSoftBg,
            onPrimaryContainer = palette.primaryDark,
            secondary = palette.primaryLight,
            onSecondary = Color.White,
            secondaryContainer = palette.lightSoftBg,
            onSecondaryContainer = palette.primaryDark,
            tertiary = StatusEmerald,
            onTertiary = Color.White,
            background = LightBackground,
            onBackground = LightTextPrimary,
            surface = LightSurface,
            onSurface = LightTextPrimary,
            surfaceVariant = palette.lightSoftBg,
            onSurfaceVariant = LightTextMuted,
            outline = LightBorder,
            outlineVariant = LightBorderSubtle,
            error = EmergencyCrimson,
            onError = Color.White
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
