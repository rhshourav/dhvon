// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.rhshourav.dhvon.data.settings.DecoderMode
import com.rhshourav.dhvon.data.settings.SeekSnap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds renderers for the three DHVON decoder modes.
 *  - HW   : hardware MediaCodec only (software only if no hardware decoder exists); FFmpeg audio only as fallback.
 *  - HW+  : hardware first, software fallback; FFmpeg audio extension is *preferred* (DTS / AC3 / EAC3 / TrueHD…).
 *  - SW   : software decoders first; FFmpeg audio preferred.
 * The FFmpeg audio renderer (org.jellyfin.media3:media3-ffmpeg-decoder) is discovered by reflection by
 * DefaultRenderersFactory, so no direct reference is needed here.
 */
class DhvonRenderersFactory(
    context: Context,
    mode: DecoderMode,
    private val processor: DhvonAudioProcessor,
) : DefaultRenderersFactory(context) {
    init {
        setExtensionRendererMode(
            if (mode == DecoderMode.HW) EXTENSION_RENDERER_MODE_ON else EXTENSION_RENDERER_MODE_PREFER
        )
        setEnableDecoderFallback(true)
        setMediaCodecSelector(selectorFor(mode))
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setAudioProcessors(arrayOf<AudioProcessor>(processor))
        .build()

    private fun selectorFor(mode: DecoderMode) = MediaCodecSelector { mime, secure, tunneling ->
        val all: List<MediaCodecInfo> = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
        when (mode) {
            DecoderMode.HW -> all.filter { it.hardwareAccelerated }.ifEmpty { all }
            DecoderMode.HW_PLUS -> all.sortedBy { if (it.hardwareAccelerated) 0 else 1 }
            DecoderMode.SW -> all.sortedBy { if (it.softwareOnly) 0 else 1 }
        }
    }
}

/**
 * Process-wide owner of the single ExoPlayer instance. The UI, the MediaSession and the audio effects all talk to
 * the same player; [DhvonPlaybackService] simply wraps it in a MediaSession so playback survives the UI.
 * Switching decoder mode rebuilds the player (renderers are fixed at construction) and restores queue + position.
 */
@Singleton
class PlayerHolder @Inject constructor(@ApplicationContext private val context: Context) {

    val audioProcessor = DhvonAudioProcessor()
    var decoderMode: DecoderMode = DecoderMode.HW
        private set

    private val _player = MutableStateFlow(create(DecoderMode.HW))
    val playerFlow: StateFlow<ExoPlayer> = _player.asStateFlow()
    val player: ExoPlayer get() = _player.value

    private fun create(mode: DecoderMode): ExoPlayer =
        ExoPlayer.Builder(context, DhvonRenderersFactory(context, mode, audioProcessor))
            .setLooper(Looper.getMainLooper())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()

    /** Rebuilds the player with a new decoder mode, preserving queue, position, speed, repeat/shuffle and track choices. */
    fun setDecoderMode(mode: DecoderMode) {
        if (mode == decoderMode) return
        val old = player
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val play = old.playWhenReady
        decoderMode = mode
        val fresh = create(mode).apply {
            trackSelectionParameters = old.trackSelectionParameters
            playbackParameters = old.playbackParameters
            repeatMode = old.repeatMode
            shuffleModeEnabled = old.shuffleModeEnabled
            setSeekParameters(old.seekParameters)
            if (items.isNotEmpty()) {
                setMediaItems(items, index.coerceAtLeast(0), position)
                prepare()
                playWhenReady = play
            }
        }
        _player.value = fresh
        // Release after collectors (session, UI, effects) have moved over to the new instance.
        Handler(Looper.getMainLooper()).post { old.release() }
    }

    fun setSeekSnap(snap: SeekSnap) {
        player.setSeekParameters(
            when (snap) {
                SeekSnap.EXACT -> SeekParameters.EXACT
                SeekSnap.CLOSEST -> SeekParameters.CLOSEST_SYNC
                SeekSnap.PREVIOUS -> SeekParameters.PREVIOUS_SYNC
            }
        )
    }

    /** Turns the video renderer off while only audio is needed (background play) to save battery. */
    fun setVideoEnabled(enabled: Boolean) {
        val p = player
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build()
    }
}
