// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** DHVON design tokens — OLED-first obsidian with silver metallic icons and purple active states. */
object DhvonColors {
    val BackgroundPrimary = Color(0xFF08090D)   // Deep Obsidian Black
    val BackgroundAmoled = Color(0xFF000000)
    val SurfaceCard = Color(0xFF171922)          // cards, bottom sheets, popups
    val SurfaceElevated = Color(0xFF212432)      // floating pills, active containers
    val AccentPurple = Color(0xFF5525E8)         // scrubber, active pills, selected nav, waveform progress
    val AccentCyan = Color(0xFF22C8E8)           // secondary highlights, ambient glow
    val IconSilverStart = Color(0xFFD8DCE6)
    val IconSilverEnd = Color(0xFF9BA3B4)
    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFF8E95A5)

    // Light theme counterparts
    val LightBackground = Color(0xFFF5F6FA)
    val LightCard = Color(0xFFFFFFFF)
    val LightElevated = Color(0xFFE9EBF3)
    val LightTextPrimary = Color(0xFF12141C)
    val LightTextSecondary = Color(0xFF5E6575)
}

@Immutable
class DhvonPalette(
    val background: Color,
    val card: Color,
    val elevated: Color,
    val accent: Color,
    val accentSecondary: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val silverStart: Color,
    val silverEnd: Color,
    val isDark: Boolean,
)

val ObsidianPalette = DhvonPalette(
    DhvonColors.BackgroundPrimary, DhvonColors.SurfaceCard, DhvonColors.SurfaceElevated,
    DhvonColors.AccentPurple, DhvonColors.AccentCyan, DhvonColors.TextPrimary, DhvonColors.TextSecondary,
    DhvonColors.IconSilverStart, DhvonColors.IconSilverEnd, true,
)
val AmoledPalette = ObsidianPalette.copyWith(background = DhvonColors.BackgroundAmoled, card = Color(0xFF0F1015), elevated = Color(0xFF1A1C26))
val LightPalette = DhvonPalette(
    DhvonColors.LightBackground, DhvonColors.LightCard, DhvonColors.LightElevated,
    DhvonColors.AccentPurple, Color(0xFF0E9BB8), DhvonColors.LightTextPrimary, DhvonColors.LightTextSecondary,
    Color(0xFF4A5060), Color(0xFF7B8296), false,
)

fun DhvonPalette.copyWith(
    background: Color = this.background, card: Color = this.card, elevated: Color = this.elevated,
    accent: Color = this.accent, accentSecondary: Color = this.accentSecondary,
) = DhvonPalette(background, card, elevated, accent, accentSecondary, textPrimary, textSecondary, silverStart, silverEnd, isDark)
