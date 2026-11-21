// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens.video

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.MediaItem
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import com.rhshourav.dhvon.data.media.SubtitleParser
import com.rhshourav.dhvon.data.media.WaveformExtractor
import com.rhshourav.dhvon.data.media.SubtitleTrackEngine
import com.rhshourav.dhvon.data.settings.*
import com.rhshourav.dhvon.player.AudioEffectsController
import com.rhshourav.dhvon.player.PlaybackController
import com.rhshourav.dhvon.player.PlayerHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

data class TrackOption(val id: String, val label: String, val selected: Boolean, val group: Tracks.Group?, val index: Int)

data class VideoUiState(
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val durationMs: Long = 0,
    val title: String = "",
    val mediaKey: String = "",
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val speed: Float = 1f,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val decoder: DecoderMode = DecoderMode.HW,
    val audioTracks: List<TrackOption> = emptyList(),
    val embeddedSubs: List<TrackOption> = emptyList(),
    val externalSubs: List<String> = emptyList(),
    val selectedExternal: String? = null,
    val subtitleOffsetMs: Long = 0,
    val audioDelayMs: Int = 0,
    val notice: String? = null,
    val error: String? = null,
) {
    val subtitlesEnabled: Boolean get() = selectedExternal != null || embeddedSubs.any { it.selected }
}

/**
 * Drives the video screen: track lists, a DHVON-owned subtitle pipeline (so the sync offset works),
 * audio delay, decoder switching with automatic software fallback, and volume/boost helpers.
 *
 * Subtitle sync: external files (.srt/.ass/.vtt) are rendered from our own parsed cue list and support ±10 s offsets
 * in both directions. Embedded tracks are rendered from ExoPlayer's cue events and support *delay* (positive offset)
 * only, because the decoder can't reveal a cue before it reaches it.
 */
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: PlayerHolder,
    val playback: PlaybackController,
    private val settingsRepo: SettingsRepository,
    val effects: AudioEffectsController,
    private val waveform: WaveformExtractor,
) : ViewModel(), Player.Listener {

    val playerFlow = holder.playerFlow
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _ui = MutableStateFlow(VideoUiState())
    val ui: StateFlow<VideoUiState> = _ui.asStateFlow()
    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    /** "Audio mode": the screen becomes an audio-player UI, the video renderer sleeps, sound keeps playing. */
    private val _audioMode = MutableStateFlow(false)
    val audioMode: StateFlow<Boolean> = _audioMode.asStateFlow()

    fun setAudioMode(on: Boolean) {
        _audioMode.value = on
        holder.setVideoEnabled(!on)
    }

    /** Real waveform peaks of the playing file's audio track — only computed while audio mode is on. */
    val peaks: StateFlow<FloatArray?> = combine(_audioMode, _ui.map { it.mediaKey }.distinctUntilChanged()) { on, key -> if (on) key else "" }
        .distinctUntilChanged()
        .mapLatest { key -> if (key.isEmpty()) null else waveform.peaks(key, 96) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun liveLevel(): Float = holder.audioProcessor.level

    private var attached: Player? = null
    private val externals = LinkedHashMap<String, SubtitleTrackEngine>()
    private val pending = ArrayDeque<Pair<Long, List<Cue>>>()
    private var embeddedCues: List<Cue> = emptyList()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    init {
        viewModelScope.launch { holder.playerFlow.collect { attach(it) } }
        viewModelScope.launch { while (true) { tick(); delay(80) } }
    }

    override fun onCleared() { attached?.removeListener(this) }

    private fun attach(p: Player) {
        attached?.removeListener(this)
        attached = p
        p.addListener(this)
        publish(p)
        refreshTracks(p.currentTracks)
    }

    // ------------------------------------------------------------------ player events

    override fun onEvents(player: Player, events: Player.Events) = publish(player)
    override fun onTracksChanged(tracks: Tracks) = refreshTracks(tracks)
    override fun onVideoSizeChanged(videoSize: VideoSize) { attached?.let { publish(it) } }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        externals.clear(); pending.clear(); embeddedCues = emptyList(); _cues.value = emptyList()
        holder.audioProcessor.delayMs = 0
        _ui.update { it.copy(selectedExternal = null, externalSubs = emptyList(), subtitleOffsetMs = 0, audioDelayMs = 0, error = null) }
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) pending.clear()
    }

    override fun onCues(cueGroup: CueGroup) {
        if (_ui.value.selectedExternal != null) return
        val delay = max(0L, _ui.value.subtitleOffsetMs)
        pending.addLast((cueGroup.presentationTimeUs / 1000L + delay) to cueGroup.cues)
    }

    override fun onPlayerError(error: PlaybackException) {
        val decoderProblem = error.errorCode in setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )
        if (decoderProblem && holder.decoderMode != DecoderMode.SW) {
            // Automatic fallback: rebuild with software decoders (+ FFmpeg audio) and keep playing from the same spot.
            holder.setDecoderMode(DecoderMode.SW)
            _ui.update { it.copy(decoder = DecoderMode.SW, notice = "That decoder couldn't play this file — switched to Software", error = null) }
        } else {
            _ui.update { it.copy(error = error.message ?: "Playback error") }
        }
    }

    private fun publish(p: Player) {
        val size = p.videoSize
        _ui.update {
            it.copy(
                isPlaying = p.isPlaying, buffering = p.playbackState == Player.STATE_BUFFERING,
                durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET && d > 0 } ?: 0L,
                title = p.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty(),
                mediaKey = p.currentMediaItem?.mediaId.orEmpty(),
                videoWidth = size.width, videoHeight = size.height,
                speed = p.playbackParameters.speed, repeatMode = p.repeatMode, decoder = holder.decoderMode,
            )
        }
    }

    // ------------------------------------------------------------------ tracks

    private fun refreshTracks(tracks: Tracks) {
        fun options(type: Int, kind: String): List<TrackOption> =
            tracks.groups.withIndex().filter { it.value.type == type }.flatMap { (gi, g) ->
                (0 until g.length).filter { g.isTrackSupported(it) }.map { i ->
                    TrackOption("$kind:$gi:$i", label(g.getTrackFormat(i), i + 1), g.isTrackSelected(i), g, i)
                }
            }
        _ui.update { it.copy(audioTracks = options(C.TRACK_TYPE_AUDIO, "a"), embeddedSubs = options(C.TRACK_TYPE_TEXT, "t")) }
    }

    private fun label(f: Format, n: Int): String {
        val lang = f.language?.takeIf { it != "und" }
            ?.let { Locale.forLanguageTag(it).displayLanguage }?.takeIf { it.isNotBlank() }?.replaceFirstChar { c -> c.titlecase() }
        val name = f.label?.takeIf { it.isNotBlank() } ?: lang ?: "Track $n"
        val ch = when (f.channelCount) { Format.NO_VALUE, 0 -> null; 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount}ch" }
        return listOfNotNull(name, codec(f.sampleMimeType), ch).joinToString(" · ")
    }

    private fun codec(mime: String?): String? = when (mime) {
        null -> null
        "audio/ac3" -> "AC3"; "audio/eac3", "audio/eac3-joc" -> "EAC3"; "audio/mp4a-latm" -> "AAC"
        "audio/vnd.dts" -> "DTS"; "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr" -> "DTS-HD"; "audio/true-hd" -> "TrueHD"
        "audio/opus" -> "Opus"; "audio/flac" -> "FLAC"; "audio/mpeg" -> "MP3"; "audio/raw" -> "PCM"; "audio/vorbis" -> "Vorbis"
        "application/x-subrip" -> "SRT"; "text/x-ssa" -> "ASS"; "application/pgs" -> "PGS"; "text/vtt" -> "VTT"; "application/dvbsubs" -> "DVB"
        else -> mime.substringAfter('/').uppercase().take(8)
    }

    fun selectAudio(o: TrackOption) {
        val p = attached ?: return
        val g = o.group ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, o.index)).build()
    }

    fun selectEmbeddedSubtitle(o: TrackOption?) {
        val p = attached ?: return
        pending.clear(); embeddedCues = emptyList(); _cues.value = emptyList()
        _ui.update { it.copy(selectedExternal = null) }
        val g = o?.group
        val b = p.trackSelectionParameters.buildUpon()
        if (o == null || g == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, o.index))
        p.trackSelectionParameters = b.build()
    }

    fun selectExternalSubtitle(name: String) {
        val p = attached ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        pending.clear(); embeddedCues = emptyList(); _cues.value = emptyList()
        _ui.update { it.copy(selectedExternal = name) }
    }

    fun disableSubtitles() = selectEmbeddedSubtitle(null)

    fun toggleSubtitles() {
        val s = _ui.value
        if (s.subtitlesEnabled) disableSubtitles()
        else {
            val ext = externals.keys.firstOrNull()
            if (ext != null) selectExternalSubtitle(ext) else s.embeddedSubs.firstOrNull()?.let { selectEmbeddedSubtitle(it) }
        }
    }

    fun loadExternalSubtitle(uri: Uri) {
        viewModelScope.launch {
            val (name, cues) = withContext(Dispatchers.IO) {
                val n = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: uri.lastPathSegment ?: "subtitle.srt"
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: ByteArray(0)
                n to SubtitleParser.parse(n, bytes)
            }
            if (cues.isEmpty()) { _ui.update { it.copy(notice = "Couldn't read any subtitles from that file") }; return@launch }
            externals[name] = SubtitleTrackEngine(name, cues)
            _ui.update { it.copy(externalSubs = externals.keys.toList()) }
            selectExternalSubtitle(name)
        }
    }

    fun adjustSubtitleOffset(deltaMs: Long) = _ui.update { it.copy(subtitleOffsetMs = (it.subtitleOffsetMs + deltaMs).coerceIn(-10_000, 10_000)) }
    fun resetSubtitleOffset() = _ui.update { it.copy(subtitleOffsetMs = 0) }

    /** Called ~12×/s: picks which cues should be on screen right now. */
    private fun tick() {
        val p = attached ?: return
        val s = _ui.value
        if (!s.subtitlesEnabled) { if (_cues.value.isNotEmpty()) _cues.value = emptyList(); return }
        val pos = p.currentPosition
        val next: List<Cue> = s.selectedExternal?.let { externals[it] }
            ?.activeAt(pos - s.subtitleOffsetMs)?.map { c -> Cue.Builder().setText(c.text).build() }
            ?: run {
                while (pending.isNotEmpty() && pending.first().first <= pos) embeddedCues = pending.removeFirst().second
                embeddedCues
            }
        if (next != _cues.value) _cues.value = next
    }

    fun setSubtitleSize(sp: Float) = viewModelScope.launch { settingsRepo.set(Keys.SUB_SIZE, sp) }
    fun setSubtitleColor(idx: Int) = viewModelScope.launch { settingsRepo.set(Keys.SUB_COLOR, idx) }
    fun setSubtitleBackground(on: Boolean) = viewModelScope.launch { settingsRepo.set(Keys.SUB_BG, on) }

    // ------------------------------------------------------------------ audio / decoder / transport

    /** Positive only: pushes the soundtrack later to fix audio that plays *ahead* of the picture. */
    fun setAudioDelay(ms: Int) {
        val v = ms.coerceIn(0, 3000)
        holder.audioProcessor.delayMs = v
        _ui.update { it.copy(audioDelayMs = v) }
    }

    fun setDecoder(mode: DecoderMode) {
        holder.setDecoderMode(mode)
        viewModelScope.launch { settingsRepo.setEnum(Keys.DECODER, mode) }
        _ui.update { it.copy(decoder = mode) }
    }

    fun setBackgroundPlay(on: Boolean) = viewModelScope.launch { settingsRepo.set(Keys.BG_PLAY, on) }
    fun setSpeed(speed: Float) = playback.setSpeed(speed)
    fun setRepeat(mode: Int) = playback.setRepeat(mode)
    fun togglePlay() = playback.togglePlayPause()
    fun scrubTo(ms: Long) = playback.seekTo(ms)
    fun consumeNotice() = _ui.update { it.copy(notice = null) }

    // ------------------------------------------------------------------ volume (0–150 %)

    fun currentVolumePercent(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val base = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max
        return if (base >= 99.9f) effects.state.value.volumeBoostPct.toFloat() else base
    }

    /** ≤100 % moves the system media volume; above 100 % pins it at max and adds LoudnessEnhancer gain. */
    fun setVolumePercent(pct: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val base = (pct.coerceIn(0f, 100f) / 100f * max).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, base, 0)
        effects.setVolumeBoost(if (pct > 100f) pct.roundToInt() else 100)
    }
}
