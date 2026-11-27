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
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import com.rhshourav.dhvon.data.settings.DecoderMode
import com.rhshourav.dhvon.data.settings.SeekSnap
import com.rhshourav.dhvon.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
        // Must stay false: with float output on, DefaultAudioSink bypasses the AudioProcessorChain — and with it DhvonAudioProcessor.
        // The processor does its math in Float32 internally and hands the sink 16-bit PCM.
        .setEnableFloatOutput(false)
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

/** Short codec name for the audio diagnostics; unknown MIME types fall back to their sub-type. */
private fun codecLabel(mime: String?): String = when (mime) {
    null -> ""
    "audio/flac" -> "FLAC"; "audio/mpeg", "audio/mpeg-L2" -> "MP3"; "audio/mp4a-latm" -> "AAC"; "audio/alac" -> "ALAC"
    "audio/opus" -> "Opus"; "audio/vorbis" -> "Vorbis"; "audio/ac3" -> "AC3"; "audio/eac3", "audio/eac3-joc" -> "EAC3"
    "audio/vnd.dts" -> "DTS"; "audio/vnd.dts.hd" -> "DTS-HD"; "audio/true-hd" -> "TrueHD"; "audio/raw" -> "PCM"
    else -> mime.substringAfter('/').uppercase().take(8)
}

/** PCM bit depth when the container states it; 0 for compressed formats, whose decoded depth isn't a property of the source. */
private fun pcmBitDepth(encoding: Int): Int = when (encoding) {
    C.ENCODING_PCM_8BIT -> 8
    C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
    C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
    C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
    else -> 0
}

/**
 * Shuffle that plays EVERYTHING. ExoPlayer's default shuffle order is a random permutation in which the *current* item sits at a
 * random spot, and playback only walks forward from there — so "Shuffle" used to play the few tracks after that spot and then end.
 * This order starts at the current item and then visits every other item exactly once.
 */
fun ExoPlayer.shuffleFromCurrent() {
    val n = mediaItemCount
    if (n <= 1) return
    val current = currentMediaItemIndex.coerceIn(0, n - 1)
    val order = IntArray(n)
    order[0] = current
    (0 until n).filter { it != current }.shuffled().forEachIndexed { i, index -> order[i + 1] = index }
    setShuffleOrder(DefaultShuffleOrder(order, System.nanoTime()))
}

/**
 * Process-wide owner of the single ExoPlayer instance. The UI, the MediaSession and the audio effects all talk to
 * the same player; [DhvonPlaybackService] simply wraps it in a MediaSession so playback survives the UI.
 * Switching decoder mode rebuilds the player (renderers are fixed at construction) and restores queue + position.
 */
@Singleton
class PlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    /** Shared by every ExoPlayer this holder builds; also feeds the live Post-EQ in [AudioEffectsController]. */
    val audioProcessor: DhvonAudioProcessor,
    private val usbDac: UsbDacManager,
    @ApplicationScope private val scope: CoroutineScope,
) {

    var decoderMode: DecoderMode = DecoderMode.HW
        private set

    /** The user's "Seek precision" choice. Scrubbing temporarily overrides it (see [beginScrub] / [finishScrub]). */
    private var userSeekParams: SeekParameters = SeekParameters.CLOSEST_SYNC

    private val _player = MutableStateFlow(create(DecoderMode.HW))
    val playerFlow: StateFlow<ExoPlayer> = _player.asStateFlow()
    val player: ExoPlayer get() = _player.value

    private var formatPlayer: ExoPlayer? = null
    private var formatListener: Player.Listener? = null

    init {
        // Bit-perfect only counts once Android has CONFIRMED it (UsbDacManager); until then the DSP tap keeps running.
        scope.launch { usbDac.diagnostics.map { it.bitPerfectConfirmedActive }.distinctUntilChanged().collect { audioProcessor.bypassForBitPerfect = it } }
        // Tell the diagnostics what is playing; re-attached whenever a decoder switch rebuilds the player.
        scope.launch { playerFlow.collect { watchTrackFormat(it) } }
    }

    private fun watchTrackFormat(p: ExoPlayer) {
        formatListener?.let { formatPlayer?.removeListener(it) }
        val l = object : Player.Listener { override fun onTracksChanged(tracks: Tracks) = reportSourceFormat(tracks) }
        formatListener = l
        formatPlayer = p
        p.addListener(l)
        reportSourceFormat(p.currentTracks)
    }

    /** Codec, sample rate and (when the container states it) bit depth of the selected audio track → [UsbDacManager]. */
    private fun reportSourceFormat(tracks: Tracks) {
        val format = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
        if (format == null) { usbDac.onTrackFormatChanged("", 0, 0); return }
        usbDac.onTrackFormatChanged(codecLabel(format.sampleMimeType), format.sampleRate.coerceAtLeast(0), pcmBitDepth(format.pcmEncoding))
    }

    private fun create(mode: DecoderMode): ExoPlayer {
        // .ts fixes: look ~282 KB ahead so late PAT/PMT tables (and their video PIDs) are found, accept non-IDR keyframes,
        // and detect H.264 access units so broadcast captures don't open audio-only. CBR seeking helps MP3/ADTS/AMR.
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
        return ExoPlayer.Builder(context, DhvonRenderersFactory(context, mode, audioProcessor), DefaultMediaSourceFactory(context, extractors))
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
            .apply {
                // Hardware audio offload would bypass DhvonAudioProcessor (and therefore the live spectral analysis).
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setAudioOffloadPreferences(
                        TrackSelectionParameters.AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED)
                            .build()
                    )
                    .build()
                setSeekParameters(userSeekParams)
            }
    }

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
            setSeekParameters(userSeekParams)
            if (items.isNotEmpty()) {
                setMediaItems(items, index.coerceAtLeast(0), position)
                if (shuffleModeEnabled) shuffleFromCurrent()      // the rebuilt player must not lose part of the queue
                prepare()
                playWhenReady = play
            }
        }
        _player.value = fresh
        // Release after collectors (session, UI, effects) have moved over to the new instance.
        Handler(Looper.getMainLooper()).post { old.release() }
    }

    fun setSeekSnap(snap: SeekSnap) {
        userSeekParams = when (snap) {
            SeekSnap.EXACT -> SeekParameters.EXACT
            SeekSnap.CLOSEST -> SeekParameters.CLOSEST_SYNC
            SeekSnap.PREVIOUS -> SeekParameters.PREVIOUS_SYNC
        }
        player.setSeekParameters(userSeekParams)
    }

    /** ExoPlayer's own tag-based hint ("en" also matches "eng"); AudioTrackPicker then handles files whose tags are missing or wrong. */
    fun setPreferredAudioLanguage(language: String?) {
        val p = player
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setPreferredAudioLanguage(language).build()
    }

    /** Drag start / double-tap: land on the nearest keyframe so scrubbing 4K .mkv / .ts / .avi stays instant. */
    fun beginScrub() = player.setSeekParameters(SeekParameters.CLOSEST_SYNC)

    /**
     * Drag release: one frame-accurate seek to where the finger stopped, then back to the user's chosen precision.
     * The three calls are queued in order on the playback thread, so the seek really runs with EXACT.
     */
    fun finishScrub(targetMs: Long) {
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(targetMs.coerceAtLeast(0L))
        player.setSeekParameters(userSeekParams)
    }

    /** Ends a keyframe-snapped skip (double-tap) without a final exact seek. */
    fun endScrub() = player.setSeekParameters(userSeekParams)

    /** Turns the video renderer off while only audio is needed (background play) to save battery. */
    fun setVideoEnabled(enabled: Boolean) {
        val p = player
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build()
    }
}
