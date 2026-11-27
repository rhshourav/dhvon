// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhshourav.dhvon.data.settings.UserSoundProfile
import com.rhshourav.dhvon.player.AudioDspState
import com.rhshourav.dhvon.player.AudioEffectsController
import com.rhshourav.dhvon.player.AudioRouteType
import com.rhshourav.dhvon.player.SoundSignature
import com.rhshourav.dhvon.ui.components.ConfirmDialog
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
    var saveDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<UserSoundProfile?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AudioPathCard(effects, st)
        if (st.autoDeviceProfileEnabled) AutoProfilesCard(effects, st)
        if (saveDialog) SaveProfileDialog(onSave = { effects.saveCurrentAsCustomProfile(it); saveDialog = false }, onDismiss = { saveDialog = false })
        deleteTarget?.let { prof ->
            ConfirmDialog(
                "Delete \u201c${prof.name}\u201d?",
                "Outputs that use it go back to Keep Current.",
                "Delete",
                onConfirm = { effects.deleteCustomProfile(prof.id); deleteTarget = null },
                onDismiss = { deleteTarget = null },
            )
        }
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

        // ---- sound signatures + the user's saved profiles
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sound signature", color = p.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            DhvonChip("Save Profile", false, { saveDialog = true }, icon = Icons.Rounded.BookmarkAdd)
        }
        LazyRow(Modifier.alpha(if (dsp) 1f else 0.45f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.isCustomEq && st.activeCustomProfileId == null && !st.lofiEnabled) item { DhvonChip("Custom", true, {}) }
            items(SoundSignature.entries.filter { it.selectable }) { sig ->
                DhvonChip(sig.label, selected = !st.isCustomEq && !st.lofiEnabled && st.signature == sig, onClick = { if (dsp) effects.setSoundSignature(sig) })
            }
            items(st.customProfiles, key = { it.id }) { prof ->
                CustomProfileChip(
                    prof.name, selected = st.isCustomEq && !st.lofiEnabled && st.activeCustomProfileId == prof.id,
                    onClick = { if (dsp) effects.applyProfileById(prof.id) }, onDelete = { deleteTarget = prof },
                )
            }
        }

        if (st.enabled && !st.lofiEnabled && st.crossfeedMix > 0f) {
            Notice("This profile includes stereo crossfeed (${(st.crossfeedMix * 100).roundToInt()}% mix, ${st.crossfeedDelayUs} \u00b5s delay). The 14 sliders show the tonal curve only.")
        }

        // ---- 14-band strip: static Pre-EQ + live Post-EQ
        Text("14-band equalizer", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
        BandStrip(st, effects, interactive = dsp && st.enabled && !st.lofiEnabled)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendItem(p.accent, "Pre-EQ — signature or your edits", Modifier.weight(1f))
            LegendItem(PostEqCyan, "Post-EQ — live adaptation", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------------------------------------ adaptive card

/**
 * Honest audio path card. Three separate facts: what the device advertises, what the source is, and the output path Android
 * confirmed (accent-coloured only while bit-perfect is confirmed). On Bluetooth it shows the wireless badge and a Bluetooth Settings
 * shortcut and hides Bit-Perfect, which only exists for USB.
 */
@Composable
private fun AudioPathCard(effects: AudioEffectsController, st: AudioDspState) {
    val p = LocalDhvon.current
    val ctx = LocalContext.current
    val d by effects.usbDac.diagnostics.collectAsStateWithLifecycle()
    val usb = d.routeType == AudioRouteType.USB_DAC
    val bluetooth = d.routeType == AudioRouteType.BLUETOOTH_TWS
    val android14 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val canBitPerfect = android14 && d.bitPerfectSupportedByHal
    val bitPerfectNote = when {
        !android14 -> "Needs Android 14 or newer."
        !d.bitPerfectSupportedByHal -> "This phone's USB audio HAL doesn't advertise bit-perfect output (MIXER_BEHAVIOR_BIT_PERFECT), so it can't be used with this DAC."
        d.bitPerfectConfirmedActive -> "Confirmed by Android. EQ, Lo-Fi, spatial, crossfeed and audio delay are bypassed while it is active."
        st.bitPerfectUsbEnabled -> "Requested \u2014 waiting for Android to confirm. Until it does, the normal DSP path is in use."
        else -> "Sends untouched PCM to the DAC. EQ and DSP are bypassed while it is active."
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1 · device and what it advertises (+ Bluetooth Settings shortcut on a wireless route)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(p.elevated), contentAlignment = Alignment.Center) {
                val icon = when (d.routeType) {
                    AudioRouteType.USB_DAC -> Icons.Rounded.Usb
                    AudioRouteType.BLUETOOTH_TWS -> Icons.Rounded.Bluetooth
                    AudioRouteType.WIRED_ANALOG -> Icons.Rounded.Headphones
                    AudioRouteType.SPEAKER -> Icons.Rounded.Speaker
                }
                Icon(icon, null, tint = if (usb || bluetooth) p.accentSecondary else p.textSecondary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(d.deviceName, color = p.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(d.advertisedCaps, color = if (bluetooth) p.accentSecondary else p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            if (bluetooth) {
                Spacer(Modifier.size(8.dp))
                OutlinedButton({ openBluetoothSettings(ctx) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Rounded.Bluetooth, null, tint = p.accentSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("BT Settings", color = p.textPrimary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        HorizontalDivider(color = p.elevated)
        // 2 · source → active output path
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("SOURCE \u2192 OUTPUT PATH", color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text(d.sourceFormatLabel, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text("\u2192 " + d.outputPathLabel, color = if (d.bitPerfectConfirmedActive) p.accent else p.textSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        if (bluetooth) {
            Text(
                "Bluetooth audio is re-encoded by the phone for your earbuds. Open BT Settings to check or switch HD Audio / aptX / LDAC.",
                color = p.textSecondary, style = MaterialTheme.typography.bodySmall,
            )
        }
        // 3 · switches — Bit-Perfect exists for USB only
        if (usb) {
            HorizontalDivider(color = p.elevated)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bit-Perfect USB (Android 14+)", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Text(bitPerfectNote, color = if (d.bitPerfectConfirmedActive) p.accent else p.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.size(12.dp))
                Switch(st.bitPerfectUsbEnabled, { effects.setBitPerfectUsb(it) }, enabled = canBitPerfect || st.bitPerfectUsbEnabled, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
            }
        }
        HorizontalDivider(color = p.elevated)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-Switch Profile by Device", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text("Applies the profile assigned to the output that becomes active.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.size(12.dp))
            Switch(st.autoDeviceProfileEnabled, { effects.setAutoDeviceProfile(it) }, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
        }
    }
}

/** One tap from the EQ to the system Bluetooth settings (HD Audio / codec options live there). Needs no permission. */
private fun openBluetoothSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun profileLabel(id: String, st: AudioDspState): String =
    if (id.isEmpty()) "Keep Current"
    else SoundSignature.entries.firstOrNull { it.selectable && it.name == id }?.label
        ?: st.customProfiles.firstOrNull { it.id == id }?.name
        ?: "Keep Current"

/** Which profile each kind of output switches to while Auto-Switch is on. "Keep Current" leaves the EQ alone for that output. */
@Composable
private fun AutoProfilesCard(effects: AudioEffectsController, st: AudioDspState) {
    val p = LocalDhvon.current
    val d by effects.usbDac.diagnostics.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Profile per output", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text("Pick what DHVON switches to when that output becomes active.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        RouteProfileRow("Bluetooth TWS", Icons.Rounded.Bluetooth, AudioRouteType.BLUETOOTH_TWS, d.routeType, st, effects)
        RouteProfileRow("USB DAC", Icons.Rounded.Usb, AudioRouteType.USB_DAC, d.routeType, st, effects)
        RouteProfileRow("Wired", Icons.Rounded.Headphones, AudioRouteType.WIRED_ANALOG, d.routeType, st, effects)
        RouteProfileRow("Speaker", Icons.Rounded.Speaker, AudioRouteType.SPEAKER, d.routeType, st, effects)
    }
}

@Composable
private fun RouteProfileRow(
    title: String, icon: ImageVector, route: AudioRouteType, activeRoute: AudioRouteType, st: AudioDspState, effects: AudioEffectsController,
) {
    val p = LocalDhvon.current
    var open by remember { mutableStateOf(false) }
    val assigned = effects.presetIdFor(route, st)
    val active = route == activeRoute
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = true }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (active) p.accent else p.textSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                if (active) Text("Active output", color = p.accent, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                profileLabel(assigned, st), color = if (assigned.isEmpty()) p.textSecondary else p.accent, style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp),
            )
        }
        DropdownMenu(open, { open = false }, containerColor = p.elevated) {
            RouteMenuItem("Keep Current", assigned.isEmpty()) { effects.setRoutePreset(route, ""); open = false }
            SoundSignature.entries.filter { it.selectable }.forEach { sig ->
                RouteMenuItem(sig.label, assigned == sig.name) { effects.setRoutePreset(route, sig.name); open = false }
            }
            st.customProfiles.forEach { prof ->
                RouteMenuItem(prof.name, assigned == prof.id) { effects.setRoutePreset(route, prof.id); open = false }
            }
        }
    }
}

@Composable
private fun RouteMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalDhvon.current
    DropdownMenuItem(
        { Text(label, color = if (selected) p.accent else p.textPrimary) }, onClick,
        trailingIcon = { if (selected) Icon(Icons.Rounded.Check, null, tint = p.accent) },
    )
}

/** A saved profile as a chip with its own delete button. */
@Composable
private fun CustomProfileChip(label: String, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val p = LocalDhvon.current
    val bg by animateColorAsState(if (selected) p.accent else p.elevated, label = "custom_chip")
    val fg = if (selected) Color.White else p.textPrimary
    Row(
        Modifier.clip(CircleShape).background(bg).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp).padding(vertical = 9.dp),
        )
        IconButton(onDelete, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Delete profile", tint = fg.copy(alpha = 0.8f), modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun SaveProfileDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalDhvon.current
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = p.card, titleContentColor = p.textPrimary, textContentColor = p.textSecondary,
        title = { Text("Save profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Saves the current 14-band curve, crossfeed mix and adaptive EQ setting.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    name, { name = it.take(32) }, singleLine = true,
                    placeholder = { Text("e.g. My TWS, Car Audio, Night Movie") },
                )
            }
        },
        confirmButton = { TextButton({ val n = name.trim(); if (n.isNotEmpty()) onSave(n) }) { Text("Save", color = p.accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = p.textSecondary) } },
    )
}

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
    // 14 columns have to fit a 360 dp phone (~22 dp each), so the readouts keep a fixed 8 sp whatever the app's text scale.
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
        Row(Modifier.fillMaxWidth()) {
            effects.bandFrequencies.indices.forEach { i ->
                BandColumn(
                    label = effects.bandLabels.getOrElse(i) { "" },
                    preDb = pre.getOrElse(i) { 0f },
                    postDb = st.dynamicPostEqOffsets.getOrElse(i) { 0f },
                    interactive = interactive,
                    onChange = { effects.setCustomBand(i, it) },
                    modifier = Modifier.weight(1f),
                )
            }
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
        Text(fmtDb(preDb), color = p.textPrimary, fontSize = 8.sp, maxLines = 1, softWrap = false)
        BandSlider(preDb, postDb, interactive, onChange, Modifier.fillMaxWidth().height(176.dp))
        Text(fmtDb(postDb), color = PostEqCyan, fontSize = 8.sp, maxLines = 1, softWrap = false)
        Text(label, color = p.textSecondary, fontSize = 8.sp, maxLines = 1, softWrap = false)
    }
}

/**
 * Vertical ±12 dB slider, narrow enough for 14 of them on a phone. The accent fill is the static Pre-EQ gain; the thin cyan bar beside
 * it is the live Post-EQ compensation. A drag that starts on (or near) the thumb keeps the band exactly where it is and moves it
 * relatively — it never jumps to the finger or to 0 dB; a tap or a drag that starts elsewhere places the thumb at the finger.
 */
@Composable
private fun BandSlider(valueDb: Float, postDb: Float, interactive: Boolean, onChange: (Float) -> Unit, modifier: Modifier) {
    val p = LocalDhvon.current
    val latest by rememberUpdatedState(onChange)
    val currentDb by rememberUpdatedState(valueDb)

    fun yToDb(y: Float, height: Float, pad: Float): Float {
        val frac = ((y - pad) / (height - 2f * pad)).coerceIn(0f, 1f)
        val db = (1f - frac) * 2f * BAND_RANGE_DB - BAND_RANGE_DB
        return (db * 2f).roundToInt() / 2f                      // snap to 0.5 dB
    }
    fun dbToY(db: Float, height: Float, pad: Float): Float =
        pad + (1f - (db.coerceIn(-BAND_RANGE_DB, BAND_RANGE_DB) + BAND_RANGE_DB) / (2f * BAND_RANGE_DB)) * (height - 2f * pad)

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
                val grabRange = 28.dp.toPx()
                var grab = 0f
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        val thumbY = dbToY(currentDb, size.height.toFloat(), pad)
                        grab = if (abs(o.y - thumbY) <= grabRange) thumbY - o.y else 0f
                        latest(yToDb(o.y + grab, size.height.toFloat(), pad))
                    },
                ) { change, _ ->
                    change.consume()
                    latest(yToDb(change.position.y + grab, size.height.toFloat(), pad))
                }
            },
    ) {
        val cx = size.width / 2f
        val pad = 8.dp.toPx()
        val h = size.height - 2f * pad
        val trackW = 3.dp.toPx()
        fun yOf(db: Float) = pad + (1f - (db.coerceIn(-BAND_RANGE_DB, BAND_RANGE_DB) + BAND_RANGE_DB) / (2f * BAND_RANGE_DB)) * h
        val zeroY = yOf(0f)
        val valueY = yOf(valueDb)

        drawRoundRect(p.elevated, Offset(cx - trackW / 2f, pad), Size(trackW, h), CornerRadius(trackW / 2f))
        drawLine(p.textSecondary.copy(alpha = 0.5f), Offset(cx - 8.dp.toPx(), zeroY), Offset(cx + 8.dp.toPx(), zeroY), strokeWidth = 1.dp.toPx())

        // Pre-EQ: from the 0 dB line to the thumb
        drawRoundRect(p.accent, Offset(cx - trackW / 2f, min(valueY, zeroY)), Size(trackW, abs(valueY - zeroY).coerceAtLeast(1f)), CornerRadius(trackW / 2f))

        // Post-EQ: live compensation, just right of the track (kept inside the column even when it is only ~20 dp wide)
        if (abs(postDb) > 0.02f) {
            val postY = yOf(postDb)
            val w = 2.5.dp.toPx()
            val x = min(cx + 7.dp.toPx(), size.width - w)
            drawRoundRect(PostEqCyan, Offset(x, min(postY, zeroY)), Size(w, abs(postY - zeroY).coerceAtLeast(2f)), CornerRadius(w / 2f))
        }

        drawCircle(Color.White, 7.dp.toPx(), Offset(cx, valueY))
        drawCircle(p.accent, 4.5.dp.toPx(), Offset(cx, valueY))
    }
}
