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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.rhshourav.dhvon.data.settings.ProgressStyle
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * One progress / seek bar, three looks (chosen in Settings):
 *  - LINE : thin track with a round thumb that grows while dragging
 *  - FLAT : thick, thumb-less pill with an accent gradient fill
 *  - WAVE : the waveform scrubber (real audio peaks + live level pulse)
 * [fraction] is 0..1; [onScrub] fires while dragging, [onSeekFinished] on release or tap.
 */
@Composable
fun DhvonSeekBar(
    fraction: Float,
    style: ProgressStyle,
    onScrub: (Float) -> Unit,
    onSeekFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    peaks: FloatArray? = null,
    levelProvider: () -> Float = { 0f },
    trackColor: Color? = null,
    interactive: Boolean = true,
) {
    if (style == ProgressStyle.WAVE) {
        WaveformSeekBar(peaks, fraction, levelProvider, onSeekFinished, modifier, onScrub)
        return
    }
    val p = LocalDhvon.current
    val flat = style == ProgressStyle.FLAT
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = (drag ?: fraction).coerceIn(0f, 1f)
    val track = trackColor ?: p.textSecondary.copy(alpha = 0.35f)

    val touch = if (!interactive) modifier else modifier
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
        }

    Canvas(touch) {
        val dragging = drag != null
        val h = if (flat) 14.dp.toPx() else (if (dragging) 6.dp else 4.dp).toPx()
        val top = (size.height - h) / 2f
        val r = CornerRadius(h / 2f)
        drawRoundRect(track, Offset(0f, top), Size(size.width, h), r)
        val w = size.width * shown
        if (w > 0f) {
            val fill = if (flat) Brush.horizontalGradient(listOf(p.accent, p.accentSecondary), 0f, max(1f, size.width))
            else Brush.horizontalGradient(listOf(p.accent, p.accent))
            drawRoundRect(fill, Offset(0f, top), Size(max(w, h), h), r)
        }
        if (!flat) drawCircle(Color.White, (if (dragging) 9.dp else 6.dp).toPx(), Offset(w.coerceIn(0f, size.width), size.height / 2f))
    }
}
