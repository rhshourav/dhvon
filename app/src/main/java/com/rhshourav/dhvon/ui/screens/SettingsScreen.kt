// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import com.rhshourav.dhvon.ui.components.DhvonSeekBar
import com.rhshourav.dhvon.ui.components.ExpandableColorPicker
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhshourav.dhvon.data.settings.*
import com.rhshourav.dhvon.data.user.UserDataRepository
import com.rhshourav.dhvon.ui.components.DhvonChip
import com.rhshourav.dhvon.ui.components.ConfirmDialog
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val user: UserDataRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    fun <T> set(key: Preferences.Key<T>, value: T) { viewModelScope.launch { repo.set(key, value) } }
    fun setEnum(key: Preferences.Key<String>, value: Enum<*>) { viewModelScope.launch { repo.setEnum(key, value) } }
    fun removeTree(uri: String) { viewModelScope.launch { repo.removeSafTree(uri) } }
    fun clearHistory() { viewModelScope.launch { user.clearHistory() } }
    fun resetCustomization() { viewModelScope.launch { repo.resetCustomization() } }
    fun setHomeSectionVisible(id: String, visible: Boolean) {
        val hidden = settings.value.hiddenHomeSections
        viewModelScope.launch { repo.set(Keys.HIDDEN_HOME, if (visible) hidden - id else hidden + id) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onPickFolder: () -> Unit, onAbout: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val p = LocalDhvon.current
    val s by vm.settings.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize().background(p.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = p.textPrimary) }
            Text("UI & Gesture Settings", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Section("Appearance")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m -> DhvonChip(m.label, s.themeMode == m, { vm.setEnum(Keys.THEME, m) }) }
            }
            val effectiveAccent = if (s.accentArgb != 0) s.accentArgb else if (s.accentCyan) 0xFF22C8E8.toInt() else 0xFF5525E8.toInt()
            ExpandableColorPicker(
                title = "Accent colour", currentArgb = effectiveAccent, presets = AccentPresets.map { it.second },
                onCommit = { vm.set(Keys.ACCENT_CYAN, false); vm.set(Keys.ACCENT_ARGB, it) },
                onReset = { vm.set(Keys.ACCENT_CYAN, false); vm.set(Keys.ACCENT_ARGB, 0) },
            )
            Text("Material You theme uses your wallpaper colours instead.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            Label("Library layout")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DhvonChip("Grid", s.viewMode == ViewMode.GRID, { vm.setEnum(Keys.VIEW, ViewMode.GRID) })
                DhvonChip("List", s.viewMode == ViewMode.LIST, { vm.setEnum(Keys.VIEW, ViewMode.LIST) })
            }
            SwitchRow("Compact density", "Tighter spacing in lists and folders", s.compactDensity) { vm.set(Keys.COMPACT, it) }
            Label("Text size")
            Slider(s.textScale, { vm.set(Keys.TEXT_SCALE, (it * 20).roundToInt() / 20f) }, valueRange = 0.8f..1.4f,
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.elevated))
            Text("${(s.textScale * 100).roundToInt()}% of normal", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)

            Section("Progress bars")
            Label("Video player")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ProgressStyle.LINE, ProgressStyle.FLAT).forEach { st -> DhvonChip(st.label, s.videoProgressStyle == st, { vm.setEnum(Keys.VIDEO_PROGRESS, st) }) }
            }
            ProgressPreview(s.videoProgressStyle.let { if (it == ProgressStyle.WAVE) ProgressStyle.FLAT else it })
            Label("Music player")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ProgressStyle.WAVE, ProgressStyle.FLAT, ProgressStyle.LINE).forEach { st -> DhvonChip(st.label, s.musicProgressStyle == st, { vm.setEnum(Keys.MUSIC_PROGRESS, st) }) }
            }
            ProgressPreview(s.musicProgressStyle)

            Section("Home layout")
            listOf("hero" to "Hero banner", "recent" to "Recently played", "folders" to "Folders", "quick" to "Quick access", "continue" to "Continue watching").forEach { (id, label) ->
                SwitchRow(label, "Show on the Home screen", id !in s.hiddenHomeSections) { vm.setHomeSectionVisible(id, it) }
            }

            Section("Playback")
            Label("Decoder")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DecoderMode.entries.forEach { m -> DhvonChip("${m.short} · ${m.label}", s.decoderMode == m, { vm.setEnum(Keys.DECODER, m) }) }
            }
            Text(s.decoderMode.hint, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            SwitchRow("Resume playback", "Continue videos where you left off", s.resumePlayback) { vm.set(Keys.RESUME, it) }
            SwitchRow("Keep screen on", "While a video is playing", s.keepScreenOn) { vm.set(Keys.KEEP_ON, it) }
            SwitchRow("Auto picture-in-picture", "Shrink the video to a window when you leave the app", s.autoPip) { vm.set(Keys.AUTO_PIP, it) }
            SwitchRow("Background audio play", "Keep playing video audio when the app is minimised", s.backgroundPlay) { vm.set(Keys.BG_PLAY, it) }
            SwitchRow("Restore last session", "After Android closes the app, bring back your queue (paused) at the same spot", s.restoreSession) { vm.set(Keys.RESTORE_SESSION, it) }
            SwitchRow("Safe headphone volume", "When headphones connect: cap the volume at 40% and fade the sound in", s.safeHeadphoneVolume) { vm.set(Keys.SAFE_HP_VOLUME, it) }
            OutlinedButton({ runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text("Background play keeps stopping? Battery settings", color = p.textPrimary) }
            Text("Some phones (Xiaomi, Huawei, Samsung…) stop background audio unless DHVON is set to “Unrestricted / Don't optimise”.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)

            Section("Gestures")
            SliderRow("Seek sensitivity", s.seekSensitivity, 0.5f..2f) { vm.set(Keys.SEEK_SENS, it) }
            SliderRow("Brightness sensitivity", s.brightnessSensitivity, 0.5f..2f) { vm.set(Keys.BRIGHT_SENS, it) }
            SliderRow("Volume sensitivity", s.volumeSensitivity, 0.5f..2f) { vm.set(Keys.VOL_SENS, it) }
            Label("Double-tap skip")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30).forEach { sec -> DhvonChip("${sec}s", s.doubleTapSeconds == sec, { vm.set(Keys.DOUBLE_TAP, sec) }) }
            }
            Label("Seek precision")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SeekSnap.entries.forEach { m -> DhvonChip(m.label, s.seekSnap == m, { vm.setEnum(Keys.SEEK_SNAP, m) }) }
            }
            Text("Keyframe snapping makes scrubbing .mkv / .ts files feel instant; frame accurate is exact but can lag on long-GOP video.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)

            Section("Video controls")
            SwitchRow("Swipe to seek", "Horizontal slide on the video", s.gestureSeek) { vm.set(Keys.G_SEEK, it) }
            SwitchRow("Brightness swipe", "Vertical slide on the left half", s.gestureBrightness) { vm.set(Keys.G_BRIGHT, it) }
            SwitchRow("Volume swipe", "Vertical slide on the right half (up to 150%)", s.gestureVolume) { vm.set(Keys.G_VOLUME, it) }
            SwitchRow("Pinch to zoom", "Two fingers to zoom and pan", s.gestureZoom) { vm.set(Keys.G_ZOOM, it) }
            SwitchRow("Double-tap to skip", "Off = a single tap just shows/hides the controls", s.gestureDoubleTap) { vm.set(Keys.G_DOUBLE, it) }
            SwitchRow("Show remaining time", "Count down instead of showing total length", s.showRemainingTime) { vm.set(Keys.REMAINING, it) }
            Label("Edge buffer: ${s.edgeBufferDp} dp")
            Slider(s.edgeBufferDp.toFloat(), { vm.set(Keys.EDGE_DP, (it / 4f).roundToInt() * 4) }, valueRange = 0f..64f, steps = 15,
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.elevated))
            Text("Swipes that start this close to a screen edge are ignored by the video player, so Android's back gesture never seeks or changes volume.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            Label("Hide controls after ${s.controlsHideSeconds}s")
            Slider(s.controlsHideSeconds.toFloat(), { vm.set(Keys.HIDE_SECS, it.roundToInt()) }, valueRange = 2f..10f, steps = 7,
                colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.elevated))

            Section("Library")
            if (s.safTrees.isEmpty()) Text("No extra folders added. Add a folder (or SD card) that Android hasn't indexed.", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            s.safTrees.forEach { tree ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.card).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(android.net.Uri.decode(tree).substringAfterLast("tree/"), color = p.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton({ vm.removeTree(tree) }) { Icon(Icons.Rounded.Close, "Remove", tint = p.textSecondary) }
                }
            }
            OutlinedButton(onPickFolder) { Text("Add folder…", color = p.textPrimary) }
            OutlinedButton({ confirmClear = true }) { Text("Clear watch history", color = p.textPrimary) }
            OutlinedButton({ confirmReset = true }) { Text("Reset all customisation", color = p.textPrimary) }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(p.card).clickable(onClick = onAbout).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("About DHVON", color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = p.textSecondary)
            }
        }
    }
    if (confirmReset) ConfirmDialog("Reset customisation?", "Theme, accent, text size, progress bars, gestures and Home layout go back to defaults. Your library, playlists and equalizer are untouched.", "Reset",
        { vm.resetCustomization(); confirmReset = false }, { confirmReset = false })
    if (confirmClear) ConfirmDialog("Clear watch history?", "Resume positions and “Recently played” will be reset. Your files are untouched.", "Clear",
        { vm.clearHistory(); confirmClear = false }, { confirmClear = false })
}

/** Accent presets (name → ARGB). The first one is DHVON's signature purple. */
private val AccentPresets = listOf(
    "Purple" to 0xFF5525E8.toInt(), "Cyan" to 0xFF22C8E8.toInt(), "Pink" to 0xFFE8257A.toInt(), "Green" to 0xFF22C55E.toInt(),
    "Orange" to 0xFFF97316.toInt(), "Red" to 0xFFEF4444.toInt(), "Blue" to 0xFF3B82F6.toInt(), "Gold" to 0xFFEAB308.toInt(),
)

/** Static preview so you can see a style before committing to it. */
@Composable
private fun ProgressPreview(style: ProgressStyle) {
    val p = LocalDhvon.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.card).padding(horizontal = 16.dp, vertical = 6.dp)) {
        DhvonSeekBar(
            fraction = 0.42f, style = style, onScrub = {}, onSeekFinished = {}, interactive = false,
            peaks = remember { FloatArray(48) { i -> (0.35f + 0.55f * kotlin.math.abs(kotlin.math.sin(i * 0.55f))).coerceIn(0.1f, 1f) } },
            modifier = Modifier.fillMaxWidth().height(if (style == ProgressStyle.WAVE) 56.dp else 36.dp),
        )
    }
}

@Composable private fun Section(title: String) {
    Text(title.uppercase(), color = LocalDhvon.current.accentSecondary, style = MaterialTheme.typography.labelLarge, letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp), modifier = Modifier.padding(top = 18.dp))
}
@Composable private fun Label(text: String) { Text(text, color = LocalDhvon.current.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)) }

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalDhvon.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = p.accent))
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val p = LocalDhvon.current
    Column {
        Row { Text(title, color = p.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text("%.1f×".format(value), color = p.textSecondary, style = MaterialTheme.typography.labelLarge) }
        Slider(value, { onChange((it * 10).toInt() / 10f) }, valueRange = range, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.elevated))
    }
}
