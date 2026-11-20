// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.theme

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalContext
import com.rhshourav.dhvon.data.settings.ThemeMode

val LocalDhvon = staticCompositionLocalOf { ObsidianPalette }
/** UI density toggle from Settings — components shrink their paddings when true. */
val LocalCompact = staticCompositionLocalOf { false }

/**
 * Four themes: Obsidian (default), Pure AMOLED, Light, and Material You (dynamic colour, Android 12+).
 * [accentCyan] swaps the purple accent for the cyan highlight.
 */
@SuppressLint("NewApi") // dynamic colour is gated by `dynamicOk` (SDK_INT >= S)
@Composable
fun DhvonTheme(
    mode: ThemeMode = ThemeMode.OBSIDIAN,
    accentCyan: Boolean = false,
    compact: Boolean = false,
    accentArgb: Int = 0,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicOk = mode == ThemeMode.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dark = if (mode == ThemeMode.DYNAMIC) isSystemInDarkTheme() else mode != ThemeMode.LIGHT

    val (palette, scheme) = if (dynamicOk) {
        val s = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        DhvonPalette(
            background = s.background, card = s.surfaceContainer, elevated = s.surfaceContainerHigh,
            accent = s.primary, accentSecondary = s.tertiary, textPrimary = s.onSurface, textSecondary = s.onSurfaceVariant,
            silverStart = s.onSurface, silverEnd = s.onSurfaceVariant, isDark = dark,
        ) to s
    } else {
        var p = when (mode) { ThemeMode.AMOLED -> AmoledPalette; ThemeMode.LIGHT -> LightPalette; else -> ObsidianPalette }
        if (accentCyan) p = p.copyWith(accent = p.accentSecondary, accentSecondary = p.accent)
        if (accentArgb != 0) p = p.copyWith(accent = Color(accentArgb))   // user-picked accent wins
        val onAccent = if (p.accent.luminance() > 0.55f) Color(0xFF08090D) else Color.White
        val s = if (p.isDark) darkColorScheme(
            primary = p.accent, onPrimary = onAccent, secondary = p.accentSecondary, background = p.background, onBackground = p.textPrimary,
            surface = p.card, onSurface = p.textPrimary, surfaceVariant = p.elevated, onSurfaceVariant = p.textSecondary,
            surfaceContainer = p.card, surfaceContainerHigh = p.elevated, surfaceContainerHighest = p.elevated,
            outline = p.textSecondary.copy(alpha = 0.4f), outlineVariant = p.textSecondary.copy(alpha = 0.18f),
        ) else lightColorScheme(
            primary = p.accent, onPrimary = onAccent, secondary = p.accentSecondary, background = p.background, onBackground = p.textPrimary,
            surface = p.card, onSurface = p.textPrimary, surfaceVariant = p.elevated, onSurfaceVariant = p.textSecondary,
            surfaceContainer = p.card, surfaceContainerHigh = p.elevated, surfaceContainerHighest = p.elevated,
            outline = p.textSecondary.copy(alpha = 0.4f), outlineVariant = p.textSecondary.copy(alpha = 0.18f),
        )
        p to s
    }

    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDhvon provides palette, LocalCompact provides compact,
        LocalDensity provides Density(base.density, base.fontScale * textScale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = DhvonTypography, content = content)
    }
}
