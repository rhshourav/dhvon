// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.nav

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.rhshourav.dhvon.ui.components.DhvonLogo
import com.rhshourav.dhvon.ui.theme.LocalDhvon

/**
 * DHVON's sticky round button (the app icon), pinned to the right side above the navigation bar.
 * Tap it to play — or jump back into — whatever you played last. The ring spins while something is playing.
 * Pass `Modifier.fillMaxSize()`; the button aligns itself bottom-end and nothing else in the area is blocked.
 */
@Composable
fun NavFab(playing: Boolean, onPlayLast: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        MainButton(playing, onPlayLast, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp))
    }
}

@Composable
private fun MainButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalDhvon.current
    val spin = rememberInfiniteTransition(label = "fab")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
        label = "ring",
    )
    Box(
        modifier.size(66.dp).shadow(14.dp, CircleShape, ambientColor = p.accent, spotColor = p.accent).clip(CircleShape)
            .background(Color(0xFF08090D)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // `angle` is read inside graphicsLayer {} (draw phase), so the spinning ring never triggers recomposition.
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = if (playing) angle else 0f }) {
            drawCircle(
                Brush.sweepGradient(listOf(p.accent, p.accentSecondary, p.accent)),
                radius = size.minDimension / 2f - 2.dp.toPx(),
                style = Stroke(3.dp.toPx()),
            )
        }
        DhvonLogo(height = 30.dp)
    }
}
