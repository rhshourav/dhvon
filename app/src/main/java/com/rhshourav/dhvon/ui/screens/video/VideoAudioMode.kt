// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens.video

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.rhshourav.dhvon.data.settings.AppSettings
import com.rhshourav.dhvon.domain.MediaFile
import com.rhshourav.dhvon.domain.formatDuration
import com.rhshourav.dhvon.player.PlaybackUiState
import com.rhshourav.dhvon.ui.components.DhvonSeekBar
import com.rhshourav.dhvon.ui.components.MediaThumb
import com.rhshourav.dhvon.ui.components.SilverIcon
import com.rhshourav.dhvon.ui.components.rememberPositionMs
import com.rhshourav.dhvon.ui.components.rememberSymmetricInsets
import com.rhshourav.dhvon.ui.screens.EqualizerPanel
import com.rhshourav.dhvon.data.settings.ProgressStyle
import com.rhshourav.dhvon.ui.theme.LocalDhvon

/**
 * The video player "transformed" into an audio player: artwork from the video, waveform / bar scrubber, full transport.
 * The video renderer is switched off (see [VideoPlayerViewModel.setAudioMode]), so it costs about as much as music.
 * Keeps playing when you leave the app.
 */
@Composable
fun AudioModeUi(
    vm: VideoPlayerViewModel,
    player: Player,
    ui: VideoUiState,
    pb: PlaybackUiState,
    settings: AppSettings,
    peaks: FloatArray?,
    onShowVideo: () -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalDhvon.current
    val position by rememberPositionMs(player)
    var scrub by remember { mutableStateOf<Float?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dur = ui.durationMs
    val fraction = scrub ?: if (dur > 0) (position.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val shownMs = scrub?.let { (it * dur).toLong() } ?: position
    val art = remember(ui.mediaKey) { MediaFile(0, ui.mediaKey, ui.title, ui.title, dur, 0, 0, "", true) }
    var eqOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(p.background)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(p.accent.copy(alpha = 0.5f), p.background), endY = 1500f)))
        Column(Modifier.fillMaxSize().padding(rememberSymmetricInsets()).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close", tint = p.textPrimary) }
                Spacer(Modifier.weight(1f))
                Text("Audio mode", color = p.textSecondary, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                IconButton(onShowVideo) { SilverIcon(Icons.Rounded.Videocam, "Show video", Modifier.size(26.dp)) }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    MediaThumb(art, Modifier.fillMaxHeight(0.82f).aspectRatio(1f).shadow(20.dp, RoundedCornerShape(24.dp), ambientColor = p.accent, spotColor = p.accent), RoundedCornerShape(24.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        AudioControls(vm, ui, pb, settings, peaks, fraction, shownMs, onScrub = { scrub = it }, onSeek = { f -> scrub = null; vm.scrubTo((f * dur).toLong()) }, onShowVideo = onShowVideo, onOpenEq = { eqOpen = true })
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                MediaThumb(art, Modifier.fillMaxWidth(0.86f).aspectRatio(1f).align(Alignment.CenterHorizontally).shadow(24.dp, RoundedCornerShape(28.dp), ambientColor = p.accent, spotColor = p.accent), RoundedCornerShape(28.dp))
                Spacer(Modifier.height(24.dp))
                AudioControls(vm, ui, pb, settings, peaks, fraction, shownMs, onScrub = { scrub = it }, onSeek = { f -> scrub = null; vm.scrubTo((f * dur).toLong()) }, onShowVideo = onShowVideo, onOpenEq = { eqOpen = true })
            }
        }
        EqOverlay(eqOpen, { eqOpen = false }, vm)
    }
}

/**
 * The full equalizer (signatures, Auto EQ, Lo-Fi, spatial, 10 bands with the live Post-EQ) as an in-screen bottom panel.
 * It is drawn inside the activity instead of a dialog window, so the immersive system-bar hiding is never disturbed.
 */
@Composable
private fun EqOverlay(open: Boolean, onClose: () -> Unit, vm: VideoPlayerViewModel) {
    val p = LocalDhvon.current
    val safe = rememberSymmetricInsets()
    BackHandler(enabled = open, onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClose),
            )
        }
        AnimatedVisibility(
            open, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        ) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(p.card)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},   // taps inside never reach the scrim
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Equalizer", color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClose) { Icon(Icons.Rounded.Close, "Close equalizer", tint = p.textSecondary) }
                }
                EqualizerPanel(
                    vm.effects,
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                        .padding(bottom = safe.calculateBottomPadding() + 16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioControls(
    vm: VideoPlayerViewModel,
    ui: VideoUiState,
    pb: PlaybackUiState,
    settings: AppSettings,
    peaks: FloatArray?,
    fraction: Float,
    shownMs: Long,
    onScrub: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    onShowVideo: () -> Unit,
    onOpenEq: () -> Unit,
) {
    val p = LocalDhvon.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ui.title, color = p.textPrimary, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        Text("Playing the soundtrack · video paused", color = p.textSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        DhvonSeekBar(
            fraction = fraction, style = settings.musicProgressStyle, peaks = peaks, levelProvider = vm::liveLevel,
            onScrub = onScrub, onSeekFinished = onSeek,
            modifier = Modifier.fillMaxWidth().height(if (settings.musicProgressStyle == ProgressStyle.WAVE) 60.dp else 40.dp),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(shownMs), color = p.textSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(formatDuration(ui.durationMs), color = p.textSecondary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(vm.playback::toggleShuffle) { Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (pb.shuffle) p.accent else p.textPrimary) }
            IconButton({ vm.playback.previous() }, Modifier.size(52.dp)) { SilverIcon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(36.dp)) }
            Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFFF5F7FA)).clickable { vm.togglePlay() }, contentAlignment = Alignment.Center) {
                Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Color(0xFF08090D), modifier = Modifier.size(42.dp))
            }
            IconButton({ vm.playback.next() }, Modifier.size(52.dp), enabled = pb.queue.size > 1) { SilverIcon(Icons.Rounded.SkipNext, "Next", Modifier.size(36.dp)) }
            IconButton(vm.playback::cycleRepeat) {
                Icon(if (pb.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                    tint = if (pb.repeatMode == Player.REPEAT_MODE_OFF) p.textPrimary else p.accent)
            }
        }
        Spacer(Modifier.height(14.dp))
        // FlowRow: three pills don't fit one line on a 360 dp phone, so they wrap instead of overflowing
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(Icons.Rounded.Tune, "Equalizer", onOpenEq)
            Pill(Icons.Rounded.Videocam, "Show video", onShowVideo)
            Pill(Icons.Rounded.Speed, "${ui.speed}x".replace(".0x", "x")) {
                val steps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                val i = steps.indexOfFirst { kotlin.math.abs(it - ui.speed) < 0.01f }   // -1 (custom speed) → starts at 0.75×
                vm.setSpeed(steps[(i + 1) % steps.size])
            }
        }
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val p = LocalDhvon.current
    Row(
        Modifier.clip(CircleShape).background(p.elevated).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SilverIcon(icon, null, Modifier.size(18.dp))
        Text(label, color = p.textPrimary, style = MaterialTheme.typography.labelLarge)
    }
}
