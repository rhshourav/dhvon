// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhshourav.dhvon.R
import com.rhshourav.dhvon.ui.theme.DhvonColors
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import kotlin.math.sin

/** The metallic soundwave emblem (vector, 5:4). */
@Composable
fun DhvonLogo(modifier: Modifier = Modifier, height: Dp = 32.dp) {
    Image(
        painter = painterResource(R.drawable.ic_dhvon_logo),
        contentDescription = "DHVON",
        modifier = modifier.height(height).aspectRatio(1.25f),
        contentScale = ContentScale.Fit,
    )
}

/** Emblem + letter-spaced "DHVON" wordmark. */
@Composable
fun DhvonWordmark(modifier: Modifier = Modifier, logoHeight: Dp = 28.dp, fontSize: Int = 20) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DhvonLogo(height = logoHeight)
        Text("DHVON", color = LocalDhvon.current.textPrimary, fontSize = fontSize.sp, fontWeight = FontWeight.Medium, letterSpacing = (fontSize * 0.28f).sp)
    }
}

/** Soft purple→cyan wave glow used on the splash and More screens. */
@Composable
fun WaveGlow(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "phase")
    Canvas(modifier) {
        val brush = Brush.horizontalGradient(listOf(DhvonColors.AccentPurple, DhvonColors.AccentCyan))
        fun wave(amp: Float, base: Float, shift: Float) = Path().apply {
            moveTo(0f, size.height * base)
            var x = 0f
            while (x <= size.width) { lineTo(x, size.height * base + sin(x / size.width * 5f + phase + shift) * amp); x += 8f }
        }
        val main = wave(size.height * 0.06f, 0.55f, 0f)
        drawPath(main, brush, style = Stroke(width = 14f, cap = StrokeCap.Round), alpha = 0.18f)
        drawPath(main, brush, style = Stroke(width = 3.5f, cap = StrokeCap.Round), alpha = 0.95f)
        val back = wave(size.height * 0.045f, 0.68f, 1.8f)
        drawPath(back, brush, style = Stroke(width = 2f, cap = StrokeCap.Round), alpha = 0.45f)
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, DhvonColors.AccentPurple.copy(alpha = 0.10f)), startY = size.height * 0.5f, endY = size.height),
            topLeft = Offset(0f, size.height * 0.5f),
        )
    }
}
