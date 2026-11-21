// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rhshourav.dhvon.ui.theme.LocalDhvon

private fun hsvOf(argb: Int): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
private fun hexOf(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

/**
 * Accent colour picker that lives in a card and *expands in place* when tapped:
 * presets, a saturation/brightness square, a hue bar and a hex field. The new colour is committed when you lift your
 * finger (so the whole app re-themes live, without hammering storage on every pixel of the drag).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpandableColorPicker(
    title: String,
    currentArgb: Int,
    presets: List<Int>,
    onCommit: (Int) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalDhvon.current
    var expanded by remember { mutableStateOf(false) }
    var hue by remember(currentArgb) { mutableFloatStateOf(hsvOf(currentArgb)[0]) }
    var sat by remember(currentArgb) { mutableFloatStateOf(hsvOf(currentArgb)[1]) }
    var value by remember(currentArgb) { mutableFloatStateOf(hsvOf(currentArgb)[2]) }
    var hex by remember(currentArgb) { mutableStateOf(hexOf(currentArgb)) }

    fun argb() = Color.hsv(hue, sat, value).toArgb()
    fun fromArgb(c: Int, updateHex: Boolean) {
        val h = hsvOf(c); hue = h[0]; sat = h[1]; value = h[2]
        if (updateHex) hex = hexOf(c)
    }

    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Color.hsv(hue, sat, value)).border(2.dp, p.textSecondary.copy(alpha = 0.45f), CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(if (expanded) "Drag to choose · release to apply" else hex, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.KeyboardArrowDown, if (expanded) "Collapse" else "Expand", tint = p.textSecondary, modifier = Modifier.rotate(if (expanded) 180f else 0f))
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    presets.forEach { c ->
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(Color(c)).clickable { fromArgb(c, true); onCommit(c) },
                            contentAlignment = Alignment.Center,
                        ) { if (argb() and 0xFFFFFF == c and 0xFFFFFF) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
                SvPanel(hue, sat, value, onChange = { s, v -> sat = s; value = v; hex = hexOf(argb()) }, onDone = { onCommit(argb()) })
                HueBar(hue, onChange = { hue = it; hex = hexOf(argb()) }, onDone = { onCommit(argb()) })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        hex, { txt ->
                            hex = txt
                            val parsed = runCatching { android.graphics.Color.parseColor(if (txt.startsWith("#")) txt else "#$txt") }.getOrNull()
                            if (parsed != null && txt.removePrefix("#").length == 6) fromArgb(parsed, false)
                        },
                        label = { Text("Hex") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    Button({ onCommit(argb()) }, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White)) { Text("Apply") }
                    TextButton({ onReset(); expanded = false }) { Text("Default", color = p.textSecondary) }
                }
            }
        }
    }
}

/** Saturation (x) × brightness (y) square for the current hue. */
@Composable
private fun SvPanel(hue: Float, sat: Float, value: Float, onChange: (Float, Float) -> Unit, onDone: () -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) { detectTapGestures { o -> onChange((o.x / size.width).coerceIn(0f, 1f), 1f - (o.y / size.height).coerceIn(0f, 1f)); onDone() } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o -> onChange((o.x / size.width).coerceIn(0f, 1f), 1f - (o.y / size.height).coerceIn(0f, 1f)) },
                    onDragEnd = onDone, onDragCancel = onDone,
                ) { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f), 1f - (change.position.y / size.height).coerceIn(0f, 1f))
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(sat * size.width, (1f - value) * size.height)
        drawCircle(Color.White, 12.dp.toPx(), c, style = Stroke(3.dp.toPx()))
        drawCircle(Color.hsv(hue, sat, value), 9.dp.toPx(), c)
    }
}

/** Rainbow hue strip. */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Canvas(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp))
            .pointerInput(Unit) { detectTapGestures { o -> onChange((o.x / size.width * 360f).coerceIn(0f, 359.9f)); onDone() } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> onChange((o.x / size.width * 360f).coerceIn(0f, 359.9f)) },
                    onDragEnd = onDone, onDragCancel = onDone,
                ) { change, _ -> change.consume(); onChange((change.position.x / size.width * 360f).coerceIn(0f, 359.9f)) }
            },
    ) {
        drawRect(Brush.horizontalGradient((0..6).map { Color.hsv((it * 60f) % 360f, 1f, 1f) }))
        drawCircle(Color.White, 13.dp.toPx(), Offset(hue / 360f * size.width, size.height / 2f), style = Stroke(3.dp.toPx()))
    }
}
