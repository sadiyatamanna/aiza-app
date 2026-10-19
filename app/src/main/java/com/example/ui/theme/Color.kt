package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// Accent Palettes definition
data class AppAccentPalette(
    val key: String,
    val name: String,
    val primary: Color,
    val primaryDark: Color,
    val primaryLight: Color,
    val lightSoftBg: Color,
    val darkSoftBg: Color,
    val previewColor: Color
)

val AccentEmerald = AppAccentPalette(
    key = "emerald",
    name = "Emerald",
    primary = Color(0xFF059669),
    primaryDark = Color(0xFF047857),
    primaryLight = Color(0xFF34D399),
    lightSoftBg = Color(0xFFECFDF5),
    darkSoftBg = Color(0xFF064E3B),
    previewColor = Color(0xFF059669)
)

val AccentMagenta = AppAccentPalette(
    key = "magenta",
    name = "Magenta",
    primary = Color(0xFFE91E63),
    primaryDark = Color(0xFFC2185B),
    primaryLight = Color(0xFFF06292),
    lightSoftBg = Color(0xFFFDF2F8),
    darkSoftBg = Color(0xFF380A1E),
    previewColor = Color(0xFFE91E63)
)

val AccentCyber = AppAccentPalette(
    key = "cyber",
    name = "Cyber",
    primary = Color(0xFF0891B2),
    primaryDark = Color(0xFF0E7490),
    primaryLight = Color(0xFF22D3EE),
    lightSoftBg = Color(0xFFECFEFF),
    darkSoftBg = Color(0xFF164E63),
    previewColor = Color(0xFF0891B2)
)

val AccentElectric = AppAccentPalette(
    key = "electric",
    name = "Electric",
    primary = Color(0xFF4F46E5),
    primaryDark = Color(0xFF3730A3),
    primaryLight = Color(0xFF818CF8),
    lightSoftBg = Color(0xFFEEF2FF),
    darkSoftBg = Color(0xFF1E1B4B),
    previewColor = Color(0xFF4F46E5)
)

val AccentCrimson = AppAccentPalette(
    key = "crimson",
    name = "Crimson",
    primary = Color(0xFFE53935),
    primaryDark = Color(0xFFB71C1C),
    primaryLight = Color(0xFFEF5350),
    lightSoftBg = Color(0xFFFEF2F2),
    darkSoftBg = Color(0xFF3B0B0B),
    previewColor = Color(0xFFE53935)
)

val AvailableAccents = listOf(
    AccentEmerald,
    AccentMagenta,
    AccentCyber,
    AccentElectric,
    AccentCrimson
)

fun getAccentPalette(key: String): AppAccentPalette {
    return AvailableAccents.find { it.key.equals(key, ignoreCase = true) }
        ?: when (key.lowercase()) {
            "cyan" -> AccentCyber
            "indigo", "purple" -> AccentElectric
            "amber" -> AccentCrimson
            else -> AccentEmerald
        }
}

// Static Base Palette Constants
val StatusEmerald = Color(0xFF10B981)
val StatusEmeraldLight = Color(0xFFECFDF5)
val EmergencyCrimson = Color(0xFFEF4444)
val EmergencyCrimsonDark = Color(0xFFDC2626)
val EmergencyCrimsonLight = Color(0xFFFEE2E2)
val EmeraldLight = StatusEmerald
val EmeraldDark = StatusEmerald
val MagentaAccent = Color(0xFFFF2A7A)

// Crisp Light Theme Surfaces
val LightBackground = Color(0xFFFFFFFF)
val LightSurface = Color(0xFFF8FAFC)
val LightElevated = Color(0xFFFFFFFF)
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF475569)
val LightTextMuted = Color(0xFF64748B)
val LightBorder = Color(0xFFE2E8F0)
val LightBorderSubtle = Color(0xFFF1F5F9)

// Dark Theme Surfaces
val DarkBackground = Color(0xFF0B0F19)
val DarkSurface = Color(0xFF161E2E)
val DarkElevated = Color(0xFF1E293B)
val DarkTextPrimary = Color(0xFFF8FAFC)
val DarkTextSecondary = Color(0xFFCBD5E1)
val DarkTextMuted = Color(0xFF94A3B8)
val DarkBorder = Color(0xFF26334D)
val DarkBorderSubtle = Color(0xFF1E293B)

// Dynamic Theme-Aware Composables (Seamlessly adapts between Light & Dark and Accents)
val CurrentBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val CurrentSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val CurrentPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val CurrentPrimaryLight: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.secondary

val CurrentSoftAccentBg: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceVariant

val CurrentTextPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onBackground

val CurrentTextMuted: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.onSurfaceVariant

val CurrentBorder: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.outline

val AlertPulseRed: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.error

// Dynamic Aliases for Full Backward Compatibility Across All Code
val MagentaPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val MagentaPrimaryDark: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val MagentaPrimaryLight: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.secondary

val MagentaSoftBg: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceVariant

val MinimalWhiteBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val MinimalWhiteSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val MinimalWhiteElevated: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val DeepObsidianBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val GlassySurfaceDark: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val CurrentCardBackground: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val SoftBackgroundLight: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background

val CardSurfaceLight: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface

val SleekPrimary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val SleekPrimaryLight: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.secondary

val SleekEmerald: Color = StatusEmerald
val SleekCrimson: Color = EmergencyCrimson
val RoyalBlueLight: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val RoyalBlueDark: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val RoyalBlueLightSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.secondary

val RoyalBlueDarkSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.secondary

val BlueGradientStart: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primary

val BlueGradientEnd: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.secondary
