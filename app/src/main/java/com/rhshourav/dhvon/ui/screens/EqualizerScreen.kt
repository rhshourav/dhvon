// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhshourav.dhvon.player.AudioDspState
import com.rhshourav.dhvon.player.AudioEffectsController
import com.rhshourav.dhvon.player.SoundSignature
import com.rhshourav.dhvon.ui.components.DhvonChip
import com.rhshourav.dhvon.ui.theme.DhvonColors
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** The live Post-EQ compensation is always drawn in DHVON cyan so it reads as "what the engine is doing right now". */
private val PostEqCyan = DhvonColors.AccentCyan
private const val BAND_RANGE_DB = 12f

@HiltViewModel
class EqualizerViewModel @Inject constructor(val effects: AudioEffectsController) : ViewModel()

@Composable
fun EqualizerScreen(onBack: () -> Unit, vm: EqualizerViewModel = hiltViewModel()) {
    val p = LocalDhvon.current
    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = p.textPrimary) }
            Text("Equalizer", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
        }
        EqualizerPanel(vm.effects, Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

/** DHVON sound panel — reused by the full screen and by the Now Playing sheet (which makes it scrollable). */
@Composable
fun EqualizerPanel(effects: AudioEffectsController, modifier: Modifier = Modifier) {
    val p = LocalDhvon.current
    val st by effects.state.collectAsStateWithLifecycle()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!st.available) {
            Text("The equalizer attaches to the audio session once the player has started. Play a song or video, then come back.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val dsp = st.dspSupported
        if (!dsp) Notice("This device doesn't offer the dynamics engine (needs Android 9+). Spatial surround and Lo-Fi mode still work.")

        // ---- master
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Equalizer and dynamics", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                Text("Sound signature, adaptive EQ, compressor, limiter", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Switch(st.enabled && dsp, { effects.setEnabled(it) }, enabled = dsp, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
        }

        // ---- adaptive dynamic EQ with live status
        AdaptiveCard(st, enabled = dsp) { effects.setDynamicAdaptive(it) }

        // ---- lo-fi + spatial
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TogglePill(Icons.Rounded.Album, "Lo-Fi mode", "Slowed + reverb", st.lofiEnabled, true, Modifier.weight(1f)) { effects.setLofiMode(!st.lofiEnabled) }
            TogglePill(
                Icons.Rounded.SurroundSound, "Spatial surround", if (st.spatialSupported) "Wider sound stage" else "Not supported here",
                st.spatialEnabled, st.spatialSupported, Modifier.weight(1f),
            ) { effects.setSpatialSurround(!st.spatialEnabled) }
        }
        if (st.lofiEnabled) {
            Notice("0.88× speed and pitch, medium-room reverb and a warm tape roll-off. The sliders below show the tape curve and are locked while Lo-Fi is on.")
        }
        if (st.spatialEnabled && st.spatialSupported) {
            Column {
                Row {
                    Text("Spatial strength", color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${st.spatialStrength / 10}%", color = p.textSecondary, style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    st.spatialStrength / 10f, { effects.setSpatialSurround(true, (it * 10f).roundToInt()) }, valueRange = 0f..100f,
                    enabled = st.spatialStrengthAdjustable,
                    colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.elevated),
                )
                if (!st.spatialStrengthAdjustable) Text("This device uses a fixed spatial strength.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ---- sound signatures
        Text("Sound signature", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        LazyRow(Modifier.alpha(if (dsp) 1f else 0.45f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.isCustomEq && !st.lofiEnabled) item { DhvonChip("Custom", true, {}) }
            items(SoundSignature.entries.filter { it.selectable }) { sig ->
                DhvonChip(sig.label, selected = !st.isCustomEq && !st.lofiEnabled && st.signature == sig, onClick = { if (dsp) effects.setSoundSignature(sig) })
            }
        }

        // ---- 10-band strip: static Pre-EQ + live Post-EQ
        Text("10-band equalizer", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        BandStrip(st, effects, interactive = dsp && st.enabled && !st.lofiEnabled)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(p.accent, "Pre-EQ — signature or your edits", Modifier.weight(1f))
            LegendItem(PostEqCyan, "Post-EQ — live adaptation", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------------------------------------ adaptive card

@Composable
private fun AdaptiveCard(st: AudioDspState, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val p = LocalDhvon.current
    val live = enabled && st.enabled && st.dynamicAdaptiveEnabled && !st.lofiEnabled && st.signalActive
    val peak = st.dynamicPostEqOffsets.maxOfOrNull { abs(it) } ?: 0f
    val status = when {
        !st.dynamicAdaptiveEnabled -> "Off — compressor and live Post-EQ are bypassed"
        st.lofiEnabled -> "Paused while Lo-Fi mode is on"
        !st.enabled -> "Paused — the equalizer is off"
        live -> "Adapting live · up to ${"%.1f".format(peak)} dB"
        else -> "Listening — waiting for music"
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.elevated), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = if (live) PostEqCyan else p.textSecondary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Adaptive dynamic EQ", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (live) PulsingDot() else Box(Modifier.size(8.dp).clip(CircleShape).background(p.textSecondary.copy(alpha = 0.5f)))
                Text(status, color = if (live) PostEqCyan else p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(st.dynamicAdaptiveEnabled, onToggle, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
    }
}

/** Only composed while the engine is adapting, so the transition isn't ticking when idle. */
@Composable
private fun PulsingDot() {
    val t = rememberInfiniteTransition(label = "adapt_dot")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "alpha")
    Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(PostEqCyan))
}

@Composable
private fun TogglePill(icon: ImageVector, title: String, subtitle: String, checked: Boolean, enabled: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val p = LocalDhvon.current
    val bg by animateColorAsState(if (checked) p.accent else p.card, label = "pill_bg")
    val fg = if (checked) Color.White else p.textPrimary
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(bg).alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onToggle).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Text(title, color = fg, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        Text(subtitle, color = fg.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun Notice(text: String) {
    val p = LocalDhvon.current
    Text(text, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(12.dp))
}

@Composable
private fun LegendItem(color: Color, label: String, modifier: Modifier) {
    val p = LocalDhvon.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
    }
}

// ------------------------------------------------------------------------------------------------ band strip

@Composable
private fun BandStrip(st: AudioDspState, effects: AudioEffectsController, interactive: Boolean) {
    val pre = st.preEqGainsDb
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        effects.bandFrequencies.forEachIndexed { i, hz ->
            BandColumn(
                label = if (hz >= 1000f) "${(hz / 1000f).toInt()}k" else hz.toInt().toString(),
                preDb = pre.getOrElse(i) { 0f },
                postDb = st.dynamicPostEqOffsets.getOrElse(i) { 0f },
                interactive = interactive,
                onChange = { effects.setCustomBand(i, it) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun fmtDb(db: Float): String = when {
    abs(db) < 0.05f -> "0"
    abs(db) >= 10f -> "%+.0f".format(db)
    else -> "%+.1f".format(db)
}

@Composable
private fun BandColumn(label: String, preDb: Float, postDb: Float, interactive: Boolean, onChange: (Float) -> Unit, modifier: Modifier) {
    val p = LocalDhvon.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(fmtDb(preDb), color = p.textPrimary, fontSize = 10.sp, maxLines = 1, softWrap = false)
        BandSlider(preDb, postDb, interactive, onChange, Modifier.fillMaxWidth().height(176.dp))
        Text(fmtDb(postDb), color = PostEqCyan, fontSize = 10.sp, maxLines = 1, softWrap = false)
        Text(label, color = p.textSecondary, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

/**
 * Vertical ±12 dB slider (a 36 dp-wide column, which a rotated Material slider can't be). The accent fill is the static
 * Pre-EQ gain; the thin cyan bar beside it is the live Post-EQ compensation the engine is adding on top.
 */
@Composable
private fun BandSlider(valueDb: Float, postDb: Float, interactive: Boolean, onChange: (Float) -> Unit, modifier: Modifier) {
    val p = LocalDhvon.current
    val latest by rememberUpdatedState(onChange)

    fun yToDb(y: Float, height: Float, pad: Float): Float {
        val frac = ((y - pad) / (height - 2f * pad)).coerceIn(0f, 1f)
        val db = (1f - frac) * 2f * BAND_RANGE_DB - BAND_RANGE_DB
        return (db * 2f).roundToInt() / 2f                      // snap to 0.5 dB
    }

    Canvas(
        modifier
            .alpha(if (interactive) 1f else 0.5f)
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                val pad = 8.dp.toPx()
                detectTapGestures { o -> latest(yToDb(o.y, size.height.toFloat(), pad)) }
            }
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                val pad = 8.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { o -> latest(yToDb(o.y, size.height.toFloat(), pad)) },
                ) { change, _ ->
                    change.consume()
                    latest(yToDb(change.position.y, size.height.toFloat(), pad))
                }
            },
    ) {
        val cx = size.width / 2f
        val pad = 8.dp.toPx()
        val h = size.height - 2f * pad
        val trackW = 4.dp.toPx()
        fun yOf(db: Float) = pad + (1f - (db.coerceIn(-BAND_RANGE_DB, BAND_RANGE_DB) + BAND_RANGE_DB) / (2f * BAND_RANGE_DB)) * h
        val zeroY = yOf(0f)
        val valueY = yOf(valueDb)

        drawRoundRect(p.elevated, Offset(cx - trackW / 2f, pad), Size(trackW, h), CornerRadius(trackW / 2f))
        drawLine(p.textSecondary.copy(alpha = 0.5f), Offset(cx - 10.dp.toPx(), zeroY), Offset(cx + 10.dp.toPx(), zeroY), strokeWidth = 1.dp.toPx())

        // Pre-EQ: from the 0 dB line to the thumb
        drawRoundRect(p.accent, Offset(cx - trackW / 2f, min(valueY, zeroY)), Size(trackW, abs(valueY - zeroY).coerceAtLeast(1f)), CornerRadius(trackW / 2f))

        // Post-EQ: live compensation, offset to the right of the track
        if (abs(postDb) > 0.02f) {
            val postY = yOf(postDb)
            val w = 3.dp.toPx()
            drawRoundRect(PostEqCyan, Offset(cx + 8.dp.toPx(), min(postY, zeroY)), Size(w, abs(postY - zeroY).coerceAtLeast(2f)), CornerRadius(w / 2f))
        }

        drawCircle(Color.White, 8.dp.toPx(), Offset(cx, valueY))
        drawCircle(p.accent, 5.dp.toPx(), Offset(cx, valueY))
    }
}
