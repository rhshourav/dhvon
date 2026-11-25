// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.rhshourav.dhvon.ui.theme.LocalDhvon

/** One bar of the emblem, in the SVG's own units (viewBox 319 × 179): x, height and how far into the 2.6 s cycle it starts. */
private class EmblemBar(val x: Float, val height: Float, val delaySeconds: Float)

// Straight from dhvon-logo-animated.svg: eight 28-wide pills, 41 apart, all centred on one line; the negative CSS delays become phase offsets.
private val EMBLEM_BARS = listOf(
    EmblemBar(0f, 39.1f, 1.75f), EmblemBar(41f, 82.8f, 1.05f), EmblemBar(82f, 147.2f, 0.35f), EmblemBar(123f, 174.8f, 0.00f),
    EmblemBar(164f, 151.8f, 0.70f), EmblemBar(205f, 128.8f, 1.40f), EmblemBar(246f, 82.8f, 2.10f), EmblemBar(287f, 39.1f, 2.45f),
)
private const val BAR_WIDTH = 28f
private const val EMBLEM_WIDTH = 315f
private const val EMBLEM_HEIGHT = 174.8f
private const val CYCLE_SECONDS = 2.6f
private val BarEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)    // the SVG's cubic-bezier(.2,.8,.2,1)

/** The SVG's keyframes: 1.0 → 0.6 at 50 % → 1.0, eased on each half. [t] is the position in the cycle, 0..1. */
private fun barScale(t: Float): Float =
    if (t < 0.5f) 1f - 0.4f * BarEasing.transform(t / 0.5f)
    else 0.6f + 0.4f * BarEasing.transform((t - 0.5f) / 0.5f)

/**
 * The DHVON emblem as eight pill bars that breathe in the logo's own staggered rhythm, tinted with the accent colour
 * (a lighter shade of it at the top of each bar). The phase is only read while drawing, so it never recomposes.
 * Only compose it while something is playing — it keeps an infinite transition running.
 */
@Composable
fun AnimatedDhvonBars(modifier: Modifier = Modifier, tint: Color = LocalDhvon.current.accent) {
    val transition = rememberInfiniteTransition(label = "dhvon_bars")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((CYCLE_SECONDS * 1000).toInt(), easing = LinearEasing)),
        label = "dhvon_bars_phase",
    )
    val top = lerp(tint, Color.White, 0.45f)
    Canvas(modifier.aspectRatio(EMBLEM_WIDTH / EMBLEM_HEIGHT)) {
        val unit = size.width / EMBLEM_WIDTH
        EMBLEM_BARS.forEach { bar ->
            val t = (phase + bar.delaySeconds / CYCLE_SECONDS) % 1f
            val h = bar.height * unit * barScale(t)
            val y = (size.height - h) / 2f                      // scales around its centre, like transform-origin:center
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(top, tint), startY = y, endY = y + h),
                topLeft = Offset(bar.x * unit, y),
                size = Size(BAR_WIDTH * unit, h),
                cornerRadius = CornerRadius(BAR_WIDTH * unit / 2f),
            )
        }
    }
}

/**
 * What sits inside the round resume button: the silver emblem while nothing plays, and the accent-tinted animated bars
 * while anything (music or video) is playing, cross-faded.
 */
@Composable
fun PlayingEmblem(playing: Boolean, modifier: Modifier = Modifier) {
    Crossfade(playing, modifier, animationSpec = tween(250), label = "emblem") { isPlaying ->
        Box(Modifier.size(width = 44.dp, height = 32.dp), contentAlignment = Alignment.Center) {
            if (isPlaying) AnimatedDhvonBars(Modifier.width(42.dp)) else DhvonLogo(height = 30.dp)
        }
    }
}
