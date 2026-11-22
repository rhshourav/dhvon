// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.rhshourav.dhvon.data.settings.AppSettings
import com.rhshourav.dhvon.data.settings.DecoderMode
import com.rhshourav.dhvon.ui.components.DhvonChip
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import kotlin.math.roundToInt

enum class AspectMode(val label: String) {
    FIT("Fit"), FILL("Crop / Fill"), STRETCH("Stretch"), R16_9("16:9"), R4_3("4:3"), ORIGINAL("100%")
}

val SubtitleColors = listOf(Color.White, Color(0xFFFFEB3B), Color(0xFF22C8E8), Color(0xFF7CFC00))

private enum class Page { MAIN, DECODER, SPEED, ASPECT, SUBTITLES, AUDIO, MODE, REPEAT, SLEEP }

private fun remainingLabel(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The floating glass quick-settings menu: Decoder · Speed · Aspect · Subtitles(+sync) · Audio(+delay) · Play mode · Repeat · Sleep timer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickSettingsMenu(
    ui: VideoUiState,
    settings: AppSettings,
    aspect: AspectMode,
    onAspect: (AspectMode) -> Unit,
    vm: VideoPlayerViewModel,
    onLoadSubtitle: () -> Unit,
    onPip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalDhvon.current
    var page by remember { mutableStateOf(Page.MAIN) }
    val sleep by vm.sleepTimer.state.collectAsStateWithLifecycle()

    Column(
        modifier.width(320.dp).heightIn(max = 360.dp).clip(RoundedCornerShape(20.dp)).background(p.card.copy(alpha = 0.96f))
            .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
    ) {
        when (page) {
            Page.MAIN -> {
                val sub = ui.selectedExternal ?: ui.embeddedSubs.firstOrNull { it.selected }?.label ?: "Off"
                MenuRow("Decoder", ui.decoder.short) { page = Page.DECODER }
                MenuRow("Speed", "%.2fx".format(ui.speed).replace(".00x", ".0x")) { page = Page.SPEED }
                MenuRow("Aspect Ratio", aspect.label) { page = Page.ASPECT }
                MenuRow("Subtitles", sub) { page = Page.SUBTITLES }
                MenuRow("Audio Track", ui.audioTracks.firstOrNull { it.selected }?.label ?: "Default") { page = Page.AUDIO }
                MenuRow("Play Mode", if (settings.backgroundPlay) "Background" else "Video") { page = Page.MODE }
                MenuRow("Repeat", when (ui.repeatMode) { Player.REPEAT_MODE_ONE -> "One"; Player.REPEAT_MODE_ALL -> "All"; else -> "Off" }) { page = Page.REPEAT }
                MenuRow("Sleep timer", if (!sleep.active) "Off" else if (sleep.endOfTrack) "End of video" else remainingLabel(sleep.remainingMs)) { page = Page.SLEEP }
            }
            Page.DECODER -> {
                Header("Decoder") { page = Page.MAIN }
                DecoderMode.entries.forEach { m -> RadioRow("${m.short} · ${m.label}", m.hint, ui.decoder == m) { vm.setDecoder(m) } }
            }
            Page.SPEED -> {
                Header("Playback speed") { page = Page.MAIN }
                Text("%.2fx".format(ui.speed), color = p.textPrimary, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 18.dp))
                Slider(
                    ui.speed, { vm.setSpeed((it * 20).roundToInt() / 20f) }, valueRange = 0.25f..4f, steps = 74,
                    modifier = Modifier.padding(horizontal = 14.dp),
                    colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.elevated),
                )
                FlowRow(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f).forEach { s ->
                        DhvonChip("${s}x".replace(".0x", "x"), ui.speed == s, { vm.setSpeed(s) })
                    }
                }
            }
            Page.ASPECT -> {
                Header("Aspect ratio") { page = Page.MAIN }
                AspectMode.entries.forEach { m -> RadioRow(m.label, null, aspect == m) { onAspect(m) } }
                Text("Tip: pinch the video to zoom freely.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            }
            Page.SUBTITLES -> {
                Header("Subtitles") { page = Page.MAIN }
                RadioRow("Off", null, !ui.subtitlesEnabled) { vm.disableSubtitles() }
                ui.embeddedSubs.forEach { o -> RadioRow(o.label, "Embedded", o.selected && ui.selectedExternal == null) { vm.selectEmbeddedSubtitle(o) } }
                ui.externalSubs.forEach { n -> RadioRow(n, "External file", ui.selectedExternal == n) { vm.selectExternalSubtitle(n) } }
                TextButton(onLoadSubtitle, Modifier.padding(horizontal = 8.dp)) { Text("Load subtitle file…  (.srt .ass .vtt)", color = p.accent) }
                HorizontalDivider(color = p.elevated)
                Stepper(
                    "Sync", "%+.1fs".format(ui.subtitleOffsetMs / 1000f),
                    onMinus = { vm.adjustSubtitleOffset(-100) }, onPlus = { vm.adjustSubtitleOffset(100) }, onReset = vm::resetSubtitleOffset,
                )
                if (ui.selectedExternal == null && ui.subtitlesEnabled)
                    Text("Embedded tracks can be delayed (+) but not shown earlier. Load the subtitle as a file for full ± sync.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp))
                HorizontalDivider(color = p.elevated, modifier = Modifier.padding(top = 8.dp))
                Text("Size  ${settings.subtitleSizeSp.roundToInt()}sp", color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 18.dp, top = 8.dp))
                Slider(
                    settings.subtitleSizeSp, { vm.setSubtitleSize(it.roundToInt().toFloat()) }, valueRange = 12f..40f, modifier = Modifier.padding(horizontal = 14.dp),
                    colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.elevated),
                )
                Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    SubtitleColors.forEachIndexed { i, c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(c).clickable { vm.setSubtitleColor(i) }, contentAlignment = Alignment.Center) {
                            if (settings.subtitleColorIdx == i) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Background box", color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(settings.subtitleBackground, vm::setSubtitleBackground, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
                }
            }
            Page.AUDIO -> {
                Header("Audio track") { page = Page.MAIN }
                if (ui.audioTracks.isEmpty()) Text("No selectable audio tracks.", color = p.textSecondary, modifier = Modifier.padding(18.dp))
                ui.audioTracks.forEach { o -> RadioRow(o.label, null, o.selected) { vm.selectAudio(o) } }
                HorizontalDivider(color = p.elevated, modifier = Modifier.padding(top = 6.dp))
                Stepper(
                    "Audio delay", "${ui.audioDelayMs} ms",
                    onMinus = { vm.setAudioDelay(ui.audioDelayMs - 100) }, onPlus = { vm.setAudioDelay(ui.audioDelayMs + 100) }, onReset = { vm.setAudioDelay(0) },
                )
                Text("Delay pushes sound later (0–3000 ms) — use it when you hear things before you see them.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            }
            Page.MODE -> {
                Header("Play mode") { page = Page.MAIN }
                Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Background audio", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("Keep playing the sound when you minimise the app", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(settings.backgroundPlay, vm::setBackgroundPlay, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
                }
                MenuRow("Pop-up window (PiP)", "") { onPip() }
            }
            Page.SLEEP -> {
                Header("Sleep timer") { page = Page.MAIN }
                if (sleep.active) Text(
                    if (sleep.endOfTrack) "Stops at the end of this video" else "Stops in ${remainingLabel(sleep.remainingMs)}",
                    color = p.accent, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                )
                Row(Modifier.padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Close DHVON when it ends", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
                        Text("Stops playback, closes the app and removes it from recents", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(settings.sleepCloseApp, vm::setSleepCloseApp, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
                }
                HorizontalDivider(color = p.elevated)
                RadioRow("Off", null, !sleep.active) { vm.sleepTimer.cancel() }
                listOf(5, 10, 15, 30, 45, 60, 90, 120).forEach { m ->
                    RadioRow("$m minutes", null, false) { vm.sleepTimer.startMinutes(m, settings.sleepCloseApp) }
                }
                RadioRow("End of this video", null, sleep.endOfTrack) { vm.sleepTimer.startEndOfTrack(settings.sleepCloseApp) }
                var custom by remember { mutableStateOf("") }
                Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        custom, { txt -> custom = txt.filter { it.isDigit() }.take(3) }, label = { Text("Custom minutes") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    Button(
                        { custom.toIntOrNull()?.takeIf { it in 1..999 }?.let { vm.sleepTimer.startMinutes(it, settings.sleepCloseApp) } },
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White),
                    ) { Text("Start") }
                }
            }
            Page.REPEAT -> {
                Header("Repeat") { page = Page.MAIN }
                RadioRow("Off — play sequentially", null, ui.repeatMode == Player.REPEAT_MODE_OFF) { vm.setRepeat(Player.REPEAT_MODE_OFF) }
                RadioRow("Loop one", null, ui.repeatMode == Player.REPEAT_MODE_ONE) { vm.setRepeat(Player.REPEAT_MODE_ONE) }
                RadioRow("Loop all", null, ui.repeatMode == Player.REPEAT_MODE_ALL) { vm.setRepeat(Player.REPEAT_MODE_ALL) }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, value: String, onClick: () -> Unit) {
    val p = LocalDhvon.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = p.textSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    val p = LocalDhvon.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onBack).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = p.textPrimary, modifier = Modifier.padding(8.dp).size(20.dp))
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RadioRow(label: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    val p = LocalDhvon.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (selected) p.accent else p.textPrimary, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hint != null) Text(hint, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) Icon(Icons.Rounded.Check, null, tint = p.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Stepper(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, onReset: () -> Unit) {
    val p = LocalDhvon.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 6.dp).weight(1f))
        OutlinedButton(onMinus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("−0.1", color = p.textPrimary, style = MaterialTheme.typography.labelSmall) }
        Text(value, color = p.textPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(min = 64.dp).clickable(onClick = onReset), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedButton(onPlus, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("+0.1", color = p.textPrimary, style = MaterialTheme.typography.labelSmall) }
    }
}
