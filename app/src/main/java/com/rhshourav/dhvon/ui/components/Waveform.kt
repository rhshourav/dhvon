// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import com.rhshourav.dhvon.player.DhvonAudioProcessor
import com.rhshourav.dhvon.ui.theme.DhvonColors
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import kotlin.math.abs
import kotlin.math.max

/**
 * Waveform scrubber. Bar heights are the real decoded peaks of the track; played bars use the purple→cyan gradient
 * and the bars around the playhead pulse with the live output level taken from [DhvonAudioProcessor].
 */
@Composable
fun WaveformSeekBar(
    peaks: FloatArray?,
    progress: Float,
    levelProvider: () -> Float,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onScrub: (Float) -> Unit = {},
) {
    val p = LocalDhvon.current
    var drag by remember { mutableStateOf<Float?>(null) }
    // Smoothed live level, advanced once per frame.
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos {
            val target = levelProvider()
            level += (target - level) * if (target > level) 0.6f else 0.12f
        }
    }
    val shown = drag ?: progress
    val data = peaks ?: FloatArray(96) { 0.12f }

    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { onSeekFinished((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(onSeekFinished); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ ->
                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                    drag = f; onScrub(f)
                }
            },
    ) {
        val n = data.size
        val slot = size.width / n
        val barW = slot * 0.58f
        val playedBrush = Brush.horizontalGradient(listOf(DhvonColors.AccentPurple, DhvonColors.AccentCyan), 0f, size.width)
        val idle = p.textSecondary.copy(alpha = 0.38f)
        val playhead = shown * n
        for (i in 0 until n) {
            val dist = abs(i - playhead)
            val pulse = if (dist < 5f) 1f + level * 0.7f * (1f - dist / 5f) else 1f
            val h = max(size.height * 0.1f, size.height * data[i] * pulse).coerceAtMost(size.height)
            val x = i * slot + (slot - barW) / 2f
            val top = (size.height - h) / 2f
            val played = i < playhead
            if (played) drawRoundRect(playedBrush, Offset(x, top), Size(barW, h), CornerRadius(barW / 2f))
            else drawRoundRect(idle, Offset(x, top), Size(barW, h), CornerRadius(barW / 2f))
        }
    }
}
