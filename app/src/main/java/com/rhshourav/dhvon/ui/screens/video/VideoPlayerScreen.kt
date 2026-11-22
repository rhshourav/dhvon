// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens.video

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.rhshourav.dhvon.R
import com.rhshourav.dhvon.data.settings.ProgressStyle
import com.rhshourav.dhvon.domain.formatDuration
import com.rhshourav.dhvon.ui.components.DhvonSeekBar
import com.rhshourav.dhvon.ui.components.rememberPositionMs
import com.rhshourav.dhvon.ui.theme.LocalDhvon
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.MediaPlayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the screen needs from the hosting Activity (window-level things Compose can't do itself). */
interface PlayerHost {
    fun currentBrightness(): Float
    fun setBrightness(value: Float)
    fun toggleOrientation()
    fun lockOrientationFor(width: Int, height: Int)
    fun enterPip()
    fun finishPlayer()
}

private enum class HudKind { NONE, BRIGHTNESS, VOLUME, SEEK, ZOOM }

/** One handler per recomposition; the gesture detector always reads the latest through rememberUpdatedState. */
/** Strips along each screen edge where touches are ignored, so Android's back / home / notification gestures never move the video. */
private class DeadZone(val left: Float, val top: Float, val right: Float, val bottom: Float)

private class GestureHandler(
    val deadZone: DeadZone,
    val onTap: (Offset) -> Unit,
    val onSeekStart: () -> Unit, val onSeekDrag: (Float) -> Unit, val onSeekEnd: () -> Unit,
    val onBrightness: (Float) -> Unit, val onVolume: (Float) -> Unit, val onVerticalEnd: () -> Unit,
    val onZoom: (Float, Offset) -> Unit,
)

/**
 * Single-pointer-state-machine gesture layer (one detector avoids the classic tap/drag/pinch conflicts):
 *  - tap / double-tap handled by the caller from [GestureHandler.onTap]
 *  - horizontal drag  → slide-to-seek
 *  - vertical drag    → left half brightness, right half volume
 *  - two fingers      → pinch zoom + pan
 */
private fun Modifier.videoGestures(handler: State<GestureHandler>): Modifier = pointerInput(Unit) { detect(handler) }

private suspend fun PointerInputScope.detect(handler: State<GestureHandler>) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val h = handler.value
        val width = size.width.toFloat()
        val height = size.height.toFloat()
        val dz = h.deadZone
        val at = down.position
        // Started inside the edge buffer → this belongs to the system (back gesture etc.). Don't track it at all.
        if (at.x < dz.left || at.x > width - dz.right || at.y < dz.top || at.y > height - dz.bottom) return@awaitEachGesture
        var mode = 0 // 0 undecided · 1 seek · 2 brightness · 3 volume · 4 zoom
        var total = Offset.Zero
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                if (mode == 1) h.onSeekEnd() else if (mode == 2 || mode == 3) h.onVerticalEnd()
                mode = 4
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) h.onZoom(zoom, pan)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (mode != 4) {
                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                val delta = change.positionChange()
                total += delta
                if (mode == 0 && total.getDistance() > slop) {
                    mode = if (abs(total.x) > abs(total.y)) 1 else if (down.position.x < width / 2f) 2 else 3
                    if (mode == 1) h.onSeekStart()
                }
                if (mode != 0 && change.positionChanged()) {
                    change.consume()
                    when (mode) {
                        1 -> h.onSeekDrag(delta.x)
                        2 -> h.onBrightness(-delta.y / height)
                        3 -> h.onVolume(-delta.y / height)
                    }
                }
            }
        } while (event.changes.any { it.pressed })
        when (mode) {
            0 -> h.onTap(down.position)
            1 -> h.onSeekEnd()
            2, 3 -> h.onVerticalEnd()
        }
    }
}

@Composable
fun VideoPlayerScreen(vm: VideoPlayerViewModel, host: PlayerHost, inPip: Boolean) {
    val p = LocalDhvon.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val player by vm.playerFlow.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cues by vm.cues.collectAsStateWithLifecycle()
    val pb by vm.playback.state.collectAsStateWithLifecycle()
    val audioMode by vm.audioMode.collectAsStateWithLifecycle()
    val peaks by vm.peaks.collectAsStateWithLifecycle()
    val exoPosition by rememberPositionMs(player)
    // LibVLC software fallback: when active, position / duration / play state come from VLC and every control drives it.
    val fb by vm.fallback.collectAsStateWithLifecycle()
    val vlcPlaying by vm.vlcPlaying.collectAsStateWithLifecycle()
    val vlcPos by vm.vlcPosition.collectAsStateWithLifecycle()
    val vlcDur by vm.vlcDuration.collectAsStateWithLifecycle()
    val vlc = remember { LibVlcHandle() }
    val position = if (fb.active) vlcPos else exoPosition
    val playing = if (fb.active) vlcPlaying else ui.isPlaying
    val durationMs = if (fb.active && vlcDur > 0) vlcDur else ui.durationMs
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var locked by remember { mutableStateOf(false) }
    var lockHint by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var playlistOpen by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(AspectMode.FIT) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var brightness by remember { mutableFloatStateOf(host.currentBrightness()) }
    var volume by remember { mutableFloatStateOf(vm.currentVolumePercent()) }
    var hud by remember { mutableStateOf(HudKind.NONE) }
    var hudStamp by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableLongStateOf(0L) }
    var scrubBase by remember { mutableLongStateOf(0L) }
    var lastScrubSeek by remember { mutableLongStateOf(0L) }
    var skipSide by remember { mutableIntStateOf(0) }
    var skipTotal by remember { mutableIntStateOf(0) }
    var skipStamp by remember { mutableIntStateOf(0) }
    var skipWindowUntil by remember { mutableLongStateOf(0L) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var pendingTap by remember { mutableStateOf<Job?>(null) }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.loadExternalSubtitle(uri) }

    // ---- side effects
    LaunchedEffect(ui.mediaKey, ui.videoWidth, ui.videoHeight, audioMode) {
        if (!audioMode && ui.videoWidth > 0 && ui.videoHeight > 0) host.lockOrientationFor(ui.videoWidth, ui.videoHeight)
    }
    // Audio mode is a portrait experience: rotate up when entering it; leaving restores the video's own orientation (effect above).
    LaunchedEffect(audioMode) { if (audioMode) host.lockOrientationFor(1, 2) }
    LaunchedEffect(ui.notice) { ui.notice?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.consumeNotice() } }
    LaunchedEffect(controlsVisible, playing, menuOpen, playlistOpen, scrubbing, interaction) {
        if (controlsVisible && playing && !menuOpen && !playlistOpen && !scrubbing) { delay(settings.controlsHideSeconds * 1000L); controlsVisible = false }
    }
    LaunchedEffect(hud, hudStamp, scrubbing) { if (hud != HudKind.NONE && !scrubbing) { delay(900); hud = HudKind.NONE } }
    LaunchedEffect(skipStamp) { if (skipSide != 0) { delay(800); skipSide = 0; skipTotal = 0 } }
    LaunchedEffect(lockHint) { if (lockHint) { delay(2500); lockHint = false } }
    BackHandler(enabled = menuOpen || playlistOpen || locked) { if (playlistOpen) playlistOpen = false else if (menuOpen) menuOpen = false else lockHint = true }

    // ---- LibVLC housekeeping: ExoPlayer pauses on "becoming noisy" and when the app stops; VLC has to be told.
    if (fb.active) {
        DisposableEffect(Unit) {
            val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { vm.setVlcPlaying(false) } }
            ContextCompat.registerReceiver(ctx, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            onDispose { runCatching { ctx.unregisterReceiver(receiver) } }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, settings.backgroundPlay) {
            val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && !settings.backgroundPlay) vm.setVlcPlaying(false) }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // The Bluetooth pill sits below the top bar while the controls are showing, and tucks up to the edge when they hide.
    val btTopTarget = when {
        audioMode -> 60.dp
        controlsVisible && !locked -> if (landscape) 64.dp else 108.dp
        else -> 10.dp
    }
    val btTop by animateDpAsState(btTopTarget, label = "bt_top")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        if (audioMode && !inPip) {
            AudioModeUi(vm, player, ui, pb, settings, peaks, onShowVideo = { vm.setAudioMode(false) }, onClose = { host.finishPlayer() })
            BluetoothSwitchBanner(vm.effects.bluetoothConnectedEvent, Modifier.align(Alignment.TopCenter).statusBarsPadding().displayCutoutPadding().padding(top = btTop))
            return@BoxWithConstraints
        }
        val widthPx = constraints.maxWidth.toFloat()
        val fit = if (ui.videoWidth > 0 && ui.videoHeight > 0) min(widthPx / ui.videoWidth, constraints.maxHeight.toFloat() / ui.videoHeight) else 1f
        val effectiveZoom = zoom * if (aspect == AspectMode.ORIGINAL && fit > 0f) 1f / fit else 1f

        fun poke() { interaction++ }

        // Transport helpers: while the LibVLC fallback plays, the same buttons, seek bar and gestures drive VLC instead of ExoPlayer.
        fun nowMs(): Long = if (fb.active) vlcPos else player.currentPosition
        fun seekTo(ms: Long) { if (fb.active) { vlc.seekTo(ms); vm.setVlcPosition(ms) } else vm.scrubTo(ms) }
        fun finishScrub(ms: Long) { if (fb.active) seekTo(ms) else vm.finishScrub(ms) }
        fun seekBy(deltaMs: Long) {
            if (fb.active) seekTo((vlcPos + deltaMs).coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)) else vm.skipBy(deltaMs)
        }
        fun togglePlayback() { if (fb.active) vm.toggleVlcPlaying() else vm.togglePlay() }
        fun previous() { if (fb.active && vlcPos > 3_000L) seekTo(0L) else vm.playback.previous() }
        fun skip(side: Int) {
            val sec = settings.doubleTapSeconds
            if (skipSide == side && SystemClock.uptimeMillis() < skipWindowUntil) skipTotal += sec else { skipSide = side; skipTotal = sec }
            seekBy(side * sec * 1000L)
            skipWindowUntil = SystemClock.uptimeMillis() + 800
            skipStamp++
        }
        fun handleTap(pos: Offset) {
            val now = SystemClock.uptimeMillis()
            if (!settings.gestureDoubleTap) { controlsVisible = !controlsVisible; poke(); return }
            val region = if (pos.x < widthPx * 0.35f) -1 else if (pos.x > widthPx * 0.65f) 1 else 0
            when {
                region != 0 && now < skipWindowUntil -> skip(region)                       // keep tapping to accumulate
                now - lastTapAt < 280 -> {                                                  // double tap
                    pendingTap?.cancel(); lastTapAt = 0
                    if (region == 0) togglePlayback() else skip(region)
                }
                else -> {                                                                   // maybe a single tap
                    lastTapAt = now
                    pendingTap?.cancel()
                    pendingTap = scope.launch { delay(280); controlsVisible = !controlsVisible; poke() }
                }
            }
        }

        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val sysGestures = WindowInsets.systemGestures
        val bufferPx = with(density) { settings.edgeBufferDp.dp.toPx() }
        val capPx = with(density) { 72.dp.toPx() }
        val deadZone = DeadZone(
            left = max(bufferPx, sysGestures.getLeft(density, layoutDirection).toFloat()).coerceAtMost(capPx),
            top = max(bufferPx, sysGestures.getTop(density).toFloat()).coerceAtMost(capPx),
            right = max(bufferPx, sysGestures.getRight(density, layoutDirection).toFloat()).coerceAtMost(capPx),
            bottom = max(bufferPx, sysGestures.getBottom(density).toFloat()).coerceAtMost(capPx),
        )
        val handler = rememberUpdatedState(
            GestureHandler(
                deadZone = deadZone,
                onTap = ::handleTap,
                onSeekStart = { if (settings.gestureSeek) { scrubbing = true; scrubBase = nowMs(); scrubTarget = scrubBase; hud = HudKind.SEEK; hudStamp++; if (!fb.active) vm.beginScrub() } },
                onSeekDrag = { dx ->
                    val dur = durationMs
                    if (settings.gestureSeek && scrubbing && dur > 0) {
                        val msPerPx = min(dur, 600_000L).toFloat() / widthPx * settings.seekSensitivity
                        scrubTarget = (scrubTarget + dx * msPerPx).toLong().coerceIn(0L, dur)
                        hud = HudKind.SEEK; hudStamp++
                        val now = SystemClock.uptimeMillis()
                        if (now - lastScrubSeek > 120) { seekTo(scrubTarget); lastScrubSeek = now }   // throttled live preview
                    }
                },
                onSeekEnd = { if (scrubbing) { finishScrub(scrubTarget); scrubbing = false; hudStamp++ } },
                onBrightness = { d ->
                    if (settings.gestureBrightness) brightness = (brightness + d * 1.2f * settings.brightnessSensitivity).coerceIn(0.01f, 1f)
                    if (settings.gestureBrightness) { host.setBrightness(brightness); hud = HudKind.BRIGHTNESS; hudStamp++ }
                },
                onVolume = { d ->
                    if (settings.gestureVolume) {
                        volume = (volume + d * 150f * settings.volumeSensitivity).coerceIn(0f, 150f)
                        vm.setVolumePercent(volume); hud = HudKind.VOLUME; hudStamp++
                    }
                },
                onVerticalEnd = { hudStamp++ },
                onZoom = { z, panDelta ->
                    if (settings.gestureZoom) {
                    zoom = (zoom * z).coerceIn(0.5f, 4f)
                    pan = if (zoom > 1f) pan + panDelta else Offset.Zero
                    hud = HudKind.ZOOM; hudStamp++
                    }
                },
            )
        )

        // 1 — video surface: ExoPlayer (TextureView so zoom/pan transforms are rock solid), or LibVLC for files MediaCodec can't decode
        if (fb.active) {
            key(fb.uri) {
                LibVlcFallbackPlayer(
                    uri = fb.uri.toUri(),
                    isPlaying = vlcPlaying,
                    playbackSpeed = ui.speed,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
                    startPositionMs = fb.startMs,
                    volumePercent = if (volume > 100f) volume.roundToInt() else 100,
                    scaleType = aspect.toVlcScale(),
                    handle = vlc,
                    onTimeChanged = vm::onVlcTime,
                    onLengthChanged = vm::onVlcLength,
                    onEnded = { if (vm.onVlcEnded()) vlc.restart() },
                    onError = vm::onVlcError,
                    onVideoSize = { w, h -> host.lockOrientationFor(w, h) },
                    onAudioFocusLost = { vm.setVlcPlaying(false) },
                )
            }
        } else {
            VideoSurface(player, aspect, effectiveZoom, pan, Modifier.fillMaxSize())
        }

        // 2 — subtitles (our own pipeline → supports sync offset + styling)
        AndroidView(
            factory = { c -> SubtitleView(c).apply { setApplyEmbeddedStyles(false); setApplyEmbeddedFontSizes(false) } },
            update = { sv ->
                sv.setCues(cues)
                sv.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, settings.subtitleSizeSp)
                sv.setStyle(
                    CaptionStyleCompat(
                        SubtitleColors[settings.subtitleColorIdx.coerceIn(SubtitleColors.indices)].toArgb(),
                        if (settings.subtitleBackground) 0xAA000000.toInt() else android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null,
                    )
                )
                sv.setBottomPaddingFraction(if (controlsVisible && !locked && !inPip) 0.2f else 0.06f)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 3 — gestures (above video, below controls so buttons still win hit-testing)
        if (!inPip) {
            if (locked) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { lockHint = true } })
            else Box(Modifier.fillMaxSize().videoGestures(handler))
        }

        if (!inPip) {
            // 4 — HUDs
            if (skipSide != 0) SkipHud(skipSide, skipTotal, Modifier.align(if (skipSide < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 56.dp))
            when (hud) {
                HudKind.BRIGHTNESS -> LevelHud(Icons.Rounded.BrightnessMedium, brightness, "${(brightness * 100).roundToInt()}%", Modifier.align(Alignment.Center))
                HudKind.VOLUME -> LevelHud(if (volume <= 0f) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, volume / 150f, "${volume.roundToInt()}%", Modifier.align(Alignment.Center))
                HudKind.SEEK -> SeekHud(scrubTarget, scrubTarget - scrubBase, Modifier.align(Alignment.Center))
                HudKind.ZOOM -> Pill("Zoom ${(zoom * 100).roundToInt()}%", Modifier.align(Alignment.Center))
                HudKind.NONE -> {}
            }
            if (ui.buffering && !fb.active) CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = Color.White, strokeWidth = 3.dp)

            // 5 — controls
            AnimatedVisibility(controlsVisible && !locked, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize()) {
                    val topActions: @Composable RowScope.() -> Unit = {
                        IconButton({
                            poke()
                            val on = !settings.backgroundPlay
                            vm.setBackgroundPlay(on)
                            Toast.makeText(ctx, if (on) "Background play on — sound keeps playing when you leave" else "Background play off", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Rounded.Headphones, "Background play", tint = if (settings.backgroundPlay) p.accentSecondary else Color.White) }
                        IconButton({ vm.setAudioMode(true) }) { Icon(Icons.Rounded.GraphicEq, "Audio mode", tint = Color.White) }
                        IconButton({ poke(); host.enterPip() }) { Icon(Icons.Rounded.PictureInPictureAlt, "Pop-up window", tint = Color.White) }
                        IconButton({ poke(); playlistOpen = true }) { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, "Playlist", tint = Color.White) }
                        IconButton({ poke(); vm.toggleSubtitles() }) {
                            Icon(if (ui.subtitlesEnabled) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionDisabled, "Subtitles", tint = if (ui.subtitlesEnabled) p.accentSecondary else Color.White)
                        }
                        IconButton({ menuOpen = !menuOpen }) { Icon(Icons.Rounded.Settings, "Settings", tint = Color.White) }
                    }
                    Column(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
                            .displayCutoutPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ host.finishPlayer() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                            Text(ui.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                            if (fb.active) EngineBadge()
                            if (landscape) topActions()
                        }
                        if (!landscape) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { topActions() }
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                            .displayCutoutPadding().navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 0.dp, top = 12.dp),
                    ) {
                        val dur = max(durationMs, 1L)
                        val shown = if (scrubbing) scrubTarget else position
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDuration(shown), color = Color.White, style = MaterialTheme.typography.labelMedium)
                            DhvonSeekBar(
                                fraction = shown.coerceIn(0L, dur).toFloat() / dur,
                                style = if (settings.videoProgressStyle == ProgressStyle.WAVE) ProgressStyle.FLAT else settings.videoProgressStyle,
                                trackColor = Color.White.copy(alpha = 0.25f),
                                onScrub = { f ->
                                    if (!scrubbing && !fb.active) vm.beginScrub()
                                    scrubbing = true; scrubTarget = (f * dur).toLong()
                                    val now = SystemClock.uptimeMillis()
                                    if (now - lastScrubSeek > 120) { seekTo(scrubTarget); lastScrubSeek = now }
                                },
                                onSeekFinished = { f -> scrubTarget = (f * dur).toLong(); finishScrub(scrubTarget); scrubbing = false },
                                modifier = Modifier.weight(1f).height(32.dp).padding(horizontal = 8.dp),
                            )
                            Text(
                                if (settings.showRemainingTime) "-" + formatDuration((durationMs - shown).coerceAtLeast(0L)) else formatDuration(durationMs),
                                color = Color.White, style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        val hasQueue = pb.queue.size > 1
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton({ locked = true; controlsVisible = false; lockHint = true }, Modifier.size(40.dp)) { Icon(Icons.Rounded.LockOpen, "Lock screen", tint = Color.White, modifier = Modifier.size(22.dp)) }
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ poke(); previous() }, Modifier.size(42.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(30.dp)) }
                                IconButton({ poke(); seekBy(-10_000) }, Modifier.size(42.dp)) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(30.dp)) }
                                Box(Modifier.size(54.dp).clip(CircleShape).background(Color(0xFFF5F7FA)).clickable { poke(); togglePlayback() }, contentAlignment = Alignment.Center) {
                                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Color(0xFF08090D), modifier = Modifier.size(34.dp))
                                }
                                IconButton({ poke(); seekBy(10_000) }, Modifier.size(42.dp)) { Icon(Icons.Rounded.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(30.dp)) }
                                IconButton({ poke(); vm.playback.next() }, Modifier.size(42.dp), enabled = hasQueue) {
                                    Icon(Icons.Rounded.SkipNext, "Next", tint = Color.White.copy(alpha = if (hasQueue) 1f else 0.35f), modifier = Modifier.size(30.dp))
                                }
                            }
                            IconButton({ poke(); host.toggleOrientation() }, Modifier.size(40.dp)) { Icon(if (landscape) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Rotate", tint = Color.White, modifier = Modifier.size(24.dp)) }
                        }
                    }
                }
            }

            // 6 — quick settings menu
            if (menuOpen) {
                Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { menuOpen = false })
                QuickSettingsMenu(
                    ui, settings, aspect, onAspect = { aspect = it; if (it != AspectMode.ORIGINAL) { zoom = 1f; pan = Offset.Zero } }, vm = vm,
                    onLoadSubtitle = { subtitlePicker.launch(arrayOf("*/*")) },
                    onPip = { menuOpen = false; host.enterPip() },
                    modifier = Modifier.align(Alignment.TopEnd).displayCutoutPadding().padding(top = 60.dp, end = 12.dp),
                )
            }

            // 6b — playlist panel: slides up from the bottom in the same glass style as the settings window
            AnimatedVisibility(playlistOpen, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { playlistOpen = false })
            }
            AnimatedVisibility(
                playlistOpen, Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) {
                VideoPlaylistPanel(
                    pb.queue, pb.currentIndex,
                    onPick = { vm.playback.skipTo(it); playlistOpen = false },
                    onRemove = { vm.playback.removeFromQueue(it) },
                    onClose = { playlistOpen = false },
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight * 0.62f),
                )
            }

            // 7 — screen lock pill
            if (locked && lockHint) {
                Row(
                    Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(24.dp).clip(CircleShape).background(p.elevated.copy(alpha = 0.95f))
                        .clickable { locked = false; lockHint = false; controlsVisible = true }.padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Lock, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Tap to unlock", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }

            // 7b — "Switched to Bluetooth" pill (never intercepts touches)
            BluetoothSwitchBanner(vm.effects.bluetoothConnectedEvent, Modifier.align(Alignment.TopCenter).statusBarsPadding().displayCutoutPadding().padding(top = btTop))

            // error card
            ui.error?.let { msg ->
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(20.dp)).background(p.card).padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Can't play this file", color = p.textPrimary, style = MaterialTheme.typography.titleMedium)
                    Text(msg, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton({ vm.setDecoder(com.rhshourav.dhvon.data.settings.DecoderMode.SW) }) { Text("Try Software", color = p.textPrimary) }
                        OutlinedButton({ vm.enterVlcFallback("Chosen manually") }) { Text("Try VLC", color = p.textPrimary) }
                        Button({ host.finishPlayer() }, colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = Color.White)) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(player: Player, aspect: AspectMode, zoom: Float, pan: Offset, modifier: Modifier) {
    val ratio = when (aspect) { AspectMode.R16_9 -> 16f / 9f; AspectMode.R4_3 -> 4f / 3f; else -> null }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { c -> LayoutInflater.from(c).inflate(R.layout.view_player_texture, null, false) as PlayerView },
            update = { pv ->
                if (pv.player !== player) pv.player = player
                pv.resizeMode = when (aspect) {
                    AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectMode.STRETCH, AspectMode.R16_9, AspectMode.R4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            onRelease = { it.player = null },
            modifier = (if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize())
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y },
        )
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 18.dp, vertical = 10.dp))
}

/** "18:25 [+01:12]" — target time plus the delta from where the swipe began. */
@Composable
private fun SeekHud(target: Long, delta: Long, modifier: Modifier) {
    val sign = if (delta < 0) "-" else "+"
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(formatDuration(target).removePrefix("00:"), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text("[$sign${formatDuration(abs(delta)).removePrefix("00:")}]", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun SkipHud(side: Int, total: Int, modifier: Modifier) {
    Column(modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (side < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward, null, tint = Color.White, modifier = Modifier.size(30.dp))
        Text("${if (side < 0) "-" else "+"}${total}s", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Centered level indicator for brightness (0–100 %) and volume (0–150 %): icon · bar · percentage. */
@Composable
private fun LevelHud(icon: ImageVector, fraction: Float, label: String, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.64f)).padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Box(Modifier.width(170.dp).height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(LocalDhvon.current.accent))
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.widthIn(min = 46.dp))
    }
}

/** Small "VLC" tag in the top bar while the software engine is playing (the DSP chain doesn't apply to it). */
@Composable
private fun EngineBadge() {
    val p = LocalDhvon.current
    Text(
        "VLC", color = Color.White, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(6.dp)).background(p.accent.copy(alpha = 0.85f)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun AspectMode.toVlcScale(): MediaPlayer.ScaleType = when (this) {
    AspectMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
    AspectMode.FILL -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
    AspectMode.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
    AspectMode.R16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
    AspectMode.R4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
    AspectMode.ORIGINAL -> MediaPlayer.ScaleType.SURFACE_ORIGINAL
}
