// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens.video

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import kotlin.concurrent.thread

/** Lets the video screen's own controls (seek bar, gestures, buttons) drive the VLC engine. */
class LibVlcHandle {
    @Volatile internal var player: MediaPlayer? = null

    fun seekTo(positionMs: Long) { player?.setTime(positionMs.coerceAtLeast(0L)) }

    /** Repeat-one: LibVLC has stopped at the end, so seek to the start and play again. */
    fun restart() { player?.let { it.setTime(0L); it.play() } }
}

/** One LibVLC instance + player + (for content:// files) the open file descriptor it reads from. */
private class VlcSession(
    val libVlc: LibVLC,
    val player: MediaPlayer,
    private val fd: ParcelFileDescriptor?,
) {
    /** stop() can block until the video output is torn down, so it never runs on the UI thread. */
    fun release() {
        val vlc = libVlc
        val mp = player
        val file = fd
        thread(name = "dhvon-vlc-release") {
            runCatching { mp.stop() }
            runCatching { mp.release() }
            runCatching { vlc.release() }
            runCatching { file?.close() }
        }
    }

    companion object {
        fun open(context: Context, uri: Uri, startMs: Long): VlcSession? {
            var vlc: LibVLC? = null
            var mp: MediaPlayer? = null
            var fd: ParcelFileDescriptor? = null
            return try {
                // --avcodec-fast: speed tricks for sloppy DivX/Xvid streams. --no-spu: DHVON draws subtitles itself (so sync works).
                // Late-frame dropping stays ON (VLC's default) so a slow CPU drops frames instead of drifting out of A/V sync.
                vlc = LibVLC(context.applicationContext, arrayListOf("--avcodec-fast", "--no-spu"))
                mp = MediaPlayer(vlc)
                val media = if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                    // LibVLC can't open content:// itself — hand it a descriptor and keep that descriptor open.
                    fd = context.contentResolver.openFileDescriptor(uri, "r")
                    Media(vlc, fd!!.fileDescriptor)
                } else {
                    Media(vlc, uri)
                }
                media.setHWDecoderEnabled(true, false)      // MediaCodec where it can, VLC's own software decoders where it can't
                if (startMs > 1_500L) media.addOption(":start-time=" + String.format(Locale.US, "%.3f", startMs / 1000.0))
                mp.media = media
                media.release()
                VlcSession(vlc, mp, fd)
            } catch (t: Throwable) {
                runCatching { mp?.release() }
                runCatching { vlc?.release() }
                runCatching { fd?.close() }
                null
            }
        }
    }
}

/**
 * Software video playback for files Android's MediaCodec can't decode (DivX / Xvid / MS-MPEG4 `.avi`, MPEG-2 `.ts` …).
 * Audio is decoded by VLC too, so DHVON's DSP chain does not apply while this engine is active.
 *
 * Give it a `key(uri)` at the call site: one composable instance plays exactly one file.
 */
@Composable
fun LibVlcFallbackPlayer(
    uri: Uri,
    isPlaying: Boolean,
    playbackSpeed: Float,
    modifier: Modifier = Modifier,
    startPositionMs: Long = 0L,
    volumePercent: Int = 100,
    scaleType: MediaPlayer.ScaleType = MediaPlayer.ScaleType.SURFACE_BEST_FIT,
    handle: LibVlcHandle? = null,
    onTimeChanged: (Long) -> Unit = {},
    onLengthChanged: (Long) -> Unit = {},
    onEnded: () -> Unit = {},
    onError: (String) -> Unit = {},
    onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> },
    onAudioFocusLost: () -> Unit = {},
) {
    val context = LocalContext.current
    val session = remember(uri) { VlcSession.open(context, uri, startPositionMs) }
    val errorCb by rememberUpdatedState(onError)
    if (session == null) {
        LaunchedEffect(uri) { errorCb("The VLC engine couldn't be started for this file") }
        return
    }
    VlcSurface(
        session, isPlaying, playbackSpeed, volumePercent, scaleType, handle, modifier,
        onTimeChanged, onLengthChanged, onEnded, onError, onVideoSize, onAudioFocusLost,
    )
}

@Composable
private fun VlcSurface(
    session: VlcSession,
    isPlaying: Boolean,
    speed: Float,
    volumePercent: Int,
    scaleType: MediaPlayer.ScaleType,
    handle: LibVlcHandle?,
    modifier: Modifier,
    onTimeChanged: (Long) -> Unit,
    onLengthChanged: (Long) -> Unit,
    onEnded: () -> Unit,
    onError: (String) -> Unit,
    onVideoSize: (Int, Int) -> Unit,
    onAudioFocusLost: () -> Unit,
) {
    val player = session.player
    val timeCb by rememberUpdatedState(onTimeChanged)
    val lengthCb by rememberUpdatedState(onLengthChanged)
    val endCb by rememberUpdatedState(onEnded)
    val errorCb by rememberUpdatedState(onError)
    val sizeCb by rememberUpdatedState(onVideoSize)
    val speedNow by rememberUpdatedState(speed)
    val main = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(session) {
        handle?.player = player
        player.setEventListener { e ->
            // LibVLC may call from its own thread; everything that touches UI state hops to the main thread.
            when (e.type) {
                MediaPlayer.Event.Playing -> main.post { player.setRate(speedNow) }
                MediaPlayer.Event.TimeChanged -> { val t = e.timeChanged; main.post { timeCb(t) } }
                MediaPlayer.Event.LengthChanged -> { val l = e.lengthChanged; main.post { lengthCb(l) } }
                MediaPlayer.Event.EndReached -> main.post { endCb() }
                MediaPlayer.Event.EncounteredError -> main.post { errorCb("VLC couldn't play this file either") }
                MediaPlayer.Event.Vout -> if (e.voutCount > 0) {
                    val track = player.currentVideoTrack
                    if (track != null) main.post { sizeCb(track.width, track.height) }
                }
                else -> Unit
            }
        }
        onDispose {
            handle?.player = null
            player.setEventListener(null)
            runCatching { player.detachViews() }
            session.release()
        }
    }

    // One call both starts playback (the player is attached before effects run) and follows play / pause.
    LaunchedEffect(session, isPlaying) { if (isPlaying) player.play() else player.pause() }
    LaunchedEffect(session, speed) { player.setRate(speed) }
    LaunchedEffect(session, volumePercent) { player.setVolume(volumePercent.coerceIn(0, 200)) }
    LaunchedEffect(session, scaleType) { player.setVideoScale(scaleType) }

    VlcAudioFocus(isPlaying, onAudioFocusLost)

    // TextureView (last argument) so pinch-zoom / pan transforms from the screen's graphicsLayer work, like the ExoPlayer surface.
    AndroidView(
        modifier = modifier,
        factory = { ctx -> VLCVideoLayout(ctx).also { layout -> player.attachViews(layout, null, false, true) } },
    )
}

/** ExoPlayer handles audio focus itself; VLC doesn't, so a call or another player must be able to pause it. */
@Composable
private fun VlcAudioFocus(playing: Boolean, onLost: () -> Unit) {
    val context = LocalContext.current
    val lost by rememberUpdatedState(onLost)
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val request = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) lost()
            }
            .build()
    }
    DisposableEffect(playing) {
        if (playing) audioManager.requestAudioFocus(request) else audioManager.abandonAudioFocusRequest(request)
        onDispose { }
    }
    DisposableEffect(Unit) { onDispose { audioManager.abandonAudioFocusRequest(request) } }
}
