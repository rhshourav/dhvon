// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhshourav.dhvon.player.AudioEffectsController
import com.rhshourav.dhvon.ui.components.DhvonChip
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

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

/** DHVON equalizer UI — reused by the full screen and by the Now Playing modal sheet. */
@Composable
fun EqualizerPanel(effects: AudioEffectsController, modifier: Modifier = Modifier) {
    val p = LocalDhvon.current
    val st by effects.state.collectAsStateWithLifecycle()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!st.available) {
            Text("The equalizer attaches to the audio session once something has been played. Start a song or video, then come back.", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable equalizer", color = p.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(st.enabled, effects::setEnabled, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(effects.presets) { name -> DhvonChip(name, st.preset == name, { effects.applyPreset(name) }) }
        }
        // Band sliders (vertical): the platform reports 5 bands on most devices, some expose 10.
        Row(Modifier.fillMaxWidth().height(220.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            st.levels.forEachIndexed { i, level ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${level / 100}", color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
                    VerticalSlider(level.toFloat(), st.minLevel.toFloat()..st.maxLevel.toFloat(), { effects.setBand(i, it.toInt()) }, Modifier.weight(1f))
                    val hz = st.bandFreqsHz.getOrNull(i) ?: 0
                    Text(if (hz >= 1000) "${hz / 1000}k" else "$hz", color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (st.bassSupported) {
            Text("Bass boost", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
            Slider(st.bassBoost.toFloat(), { effects.setBassBoost(it.toInt()) }, valueRange = 0f..1000f, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
        }
        if (st.virtSupported) {
            Text("Virtualizer", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
            Slider(st.virtualizer.toFloat(), { effects.setVirtualizer(it.toInt()) }, valueRange = 0f..1000f, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** A Slider rotated to vertical (bottom = min) that reports its layout size correctly. */
@Composable
private fun VerticalSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalDhvon.current
    Slider(
        value, onChange, valueRange = range,
        colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.elevated),
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints(minWidth = constraints.minHeight, maxWidth = constraints.maxHeight, minHeight = constraints.minWidth, maxHeight = constraints.maxWidth))
                layout(placeable.height, placeable.width) { placeable.place(-(placeable.width - placeable.height) / 2, (placeable.width - placeable.height) / 2) }
            }
            .rotate(-90f),
    )
}
