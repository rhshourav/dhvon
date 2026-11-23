// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.ui.screens.video

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
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
import com.rhshourav.dhvon.data.media.SupportedMediaFormats
import com.rhshourav.dhvon.data.settings.*
import com.rhshourav.dhvon.player.AudioEffectsController
import com.rhshourav.dhvon.player.PlaybackController
import com.rhshourav.dhvon.domain.durationHint
import com.rhshourav.dhvon.domain.isVideoItem
import com.rhshourav.dhvon.player.PlayerHolder
import com.rhshourav.dhvon.player.SleepTimer
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
    val sleepTimer: SleepTimer,   // public: the quick-settings sleep page reads its state
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
        if (on && _fallback.value.active) {
            // Audio-only never needs the software video engine: hand the soundtrack back to ExoPlayer at the same spot.
            val pos = _vlcPosition.value
            _fallback.value = VlcFallbackState()
            attached?.let { it.seekTo(pos); it.play() }
        } else if (!on) {
            attached?.let { evaluateFallback(it.currentTracks) }   // back to video: re-check whether it needs VLC
        }
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

    // ------------------------------------------------------------------ screen dimming (brightness gesture below the panel's minimum)

    private val _extraDim = MutableStateFlow(0f)
    /** 0..1 — how much darker than the panel's lowest brightness the screen is (drawn as a black overlay by the screen). */
    val extraDim: StateFlow<Float> = _extraDim.asStateFlow()
    private val _brightnessEpoch = MutableStateFlow(0)
    /** Bumps whenever a video is (re)opened, so the screen re-reads the phone's own brightness. */
    val brightnessEpoch: StateFlow<Int> = _brightnessEpoch.asStateFlow()

    fun setExtraDim(value: Float) { _extraDim.value = value.coerceIn(0f, 1f) }
    fun resetDisplayToPhoneLevel() { _extraDim.value = 0f; _brightnessEpoch.update { it + 1 } }

    // ------------------------------------------------------------------ LibVLC software fallback

    /** [active] while LibVLC (not ExoPlayer) plays the current file because MediaCodec has no decoder for its video. */
    data class VlcFallbackState(val active: Boolean = false, val uri: String = "", val startMs: Long = 0L, val reason: String = "")

    private val _fallback = MutableStateFlow(VlcFallbackState())
    val fallback: StateFlow<VlcFallbackState> = _fallback.asStateFlow()
    private val _vlcPlaying = MutableStateFlow(true)
    val vlcPlaying: StateFlow<Boolean> = _vlcPlaying.asStateFlow()
    private val _vlcPosition = MutableStateFlow(0L)
    val vlcPosition: StateFlow<Long> = _vlcPosition.asStateFlow()
    private val _vlcDuration = MutableStateFlow(0L)
    val vlcDuration: StateFlow<Long> = _vlcDuration.asStateFlow()
    private var lastVlcSave = 0L

    /** Selectable *or* merely too big for the hardware counts as decodable; only "no decoder at all" triggers VLC. */
    private fun Tracks.Group.isDecodable(): Boolean = (0 until length).any {
        val support = getTrackSupport(it)
        support == C.FORMAT_HANDLED || support == C.FORMAT_EXCEEDS_CAPABILITIES
    }

    private fun evaluateFallback(tracks: Tracks) {
        val p = attached ?: return
        if (_fallback.value.active || _audioMode.value || tracks.groups.isEmpty()) return
        val item = p.currentMediaItem ?: return
        if (!item.isVideoItem) return
        val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if (videoGroups.isNotEmpty() && videoGroups.none { it.isDecodable() }) {
            enterVlcFallback("No decoder for this video format")
            return
        }
        // Audio but no video at all: only suspicious for containers ExoPlayer is known to struggle with (not every audio-only .mp4).
        if (videoGroups.isEmpty() && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
            val id = item.mediaId
            viewModelScope.launch {
                val ext = withContext(Dispatchers.IO) { SupportedMediaFormats.extensionOf(displayNameOf(id)) }
                if (ext in SupportedMediaFormats.FALLBACK_PRONE_VIDEO_EXTENSIONS && !_fallback.value.active && !_audioMode.value &&
                    attached?.currentMediaItem?.mediaId == id
                ) enterVlcFallback("No video track found in this container")
            }
        }
    }

    private fun displayNameOf(uriString: String): String? {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment
    }

    /** Parks ExoPlayer (paused, same position) and lets the screen mount the LibVLC surface for this file. */
    fun enterVlcFallback(reason: String) {
        val p = attached ?: return
        val item = p.currentMediaItem ?: return
        if (_fallback.value.active) return
        val start = p.currentPosition.coerceAtLeast(0L)
        p.pause()
        _vlcPosition.value = start
        _vlcDuration.value = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: item.durationHint
        _vlcPlaying.value = true
        lastVlcSave = SystemClock.elapsedRealtime()
        _fallback.value = VlcFallbackState(true, item.mediaId, start, reason)
        _ui.update { it.copy(error = null, notice = "Switched to the VLC engine for this file") }
    }

    /** A headset button or notification "play" reaches the parked ExoPlayer — treat it as play/pause for VLC instead. */
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (_fallback.value.active && playWhenReady) {
            attached?.pause()
            _vlcPlaying.update { !it }
        }
    }

    fun setVlcPlaying(on: Boolean) { _vlcPlaying.value = on }
    fun toggleVlcPlaying() { _vlcPlaying.update { !it } }
    fun setVlcPosition(ms: Long) { _vlcPosition.value = ms }
    fun onVlcLength(ms: Long) { if (ms > 0) _vlcDuration.value = ms }

    fun onVlcTime(ms: Long) {
        _vlcPosition.value = ms
        val now = SystemClock.elapsedRealtime()
        if (now - lastVlcSave > 5_000) { lastVlcSave = now; saveVlcPosition() }   // resume point, like the 5 s recorder for ExoPlayer
    }

    /** @return true when the screen should restart the same file (repeat-one). */
    fun onVlcEnded(): Boolean {
        saveVlcPosition(finished = true)
        if (sleepTimer.onExternalTrackEnded()) { _vlcPlaying.value = false; return false }   // "End of this video" sleep timer
        val p = attached ?: return false
        return when {
            p.repeatMode == Player.REPEAT_MODE_ONE -> true
            p.hasNextMediaItem() -> { p.seekToNextMediaItem(); false }        // → onMediaItemTransition starts it
            p.repeatMode == Player.REPEAT_MODE_ALL && p.mediaItemCount > 1 -> { p.seekTo(0, C.TIME_UNSET); false }
            else -> { _vlcPlaying.value = false; false }
        }
    }

    fun onVlcError(message: String) {
        _fallback.value = VlcFallbackState()
        _ui.update { it.copy(error = message) }
    }

    private fun saveVlcPosition(finished: Boolean = false) =
        playback.recordExternalPosition(_vlcPosition.value, _vlcDuration.value, finished)

    // ------------------------------------------------------------------ scrubbing (keyframe-snapped drag, exact release)

    fun beginScrub() = holder.beginScrub()
    fun finishScrub(targetMs: Long) = holder.finishScrub(targetMs)

    /** Double-tap / button skip: snap to the nearest keyframe for speed, then restore the configured precision. */
    fun skipBy(deltaMs: Long) {
        holder.beginScrub()
        playback.seekBy(deltaMs)
        holder.endScrub()
    }

    init {
        effects.start()          // idempotent — makes sure the DSP and the Bluetooth watcher run even on a cold "Open with"
        viewModelScope.launch { sleepTimer.fired.collect { if (_fallback.value.active) _vlcPlaying.value = false } }
        viewModelScope.launch { holder.playerFlow.collect { attach(it) } }
        viewModelScope.launch { while (true) { tick(); delay(80) } }
    }

    override fun onCleared() {
        attached?.removeListener(this)
        if (_fallback.value.active) saveVlcPosition()
    }

    private fun attach(p: Player) {
        attached?.removeListener(this)
        attached = p
        p.addListener(this)
        publish(p)
        refreshTracks(p.currentTracks)
    }

    // ------------------------------------------------------------------ player events

    override fun onEvents(player: Player, events: Player.Events) = publish(player)
    override fun onTracksChanged(tracks: Tracks) { refreshTracks(tracks); evaluateFallback(tracks) }
    override fun onVideoSizeChanged(videoSize: VideoSize) { attached?.let { publish(it) } }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        externals.clear(); pending.clear(); embeddedCues = emptyList(); _cues.value = emptyList()
        holder.audioProcessor.delayMs = 0
        _ui.update { it.copy(selectedExternal = null, externalSubs = emptyList(), subtitleOffsetMs = 0, audioDelayMs = 0, error = null) }
        if (_fallback.value.active) {
            // Next / previous / auto-advance while VLC was playing: ExoPlayer was parked paused, so start the new item.
            _fallback.value = VlcFallbackState()
            _vlcPosition.value = 0L; _vlcDuration.value = 0L; _vlcPlaying.value = true
            if (mediaItem != null) attached?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
        }
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
        val containerProblem = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        val canFallback = attached?.currentMediaItem?.isVideoItem == true && !_audioMode.value && !_fallback.value.active
        if (canFallback && (containerProblem || (decoderProblem && holder.decoderMode == DecoderMode.SW))) {
            // Last resort: Software decoders already failed (or the container is unreadable) → LibVLC.
            enterVlcFallback(if (containerProblem) "ExoPlayer can't read this container" else "No decoder could play this video")
        } else if (decoderProblem && holder.decoderMode != DecoderMode.SW) {
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
        val pos = if (_fallback.value.active) _vlcPosition.value else p.currentPosition
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

    fun setSleepCloseApp(on: Boolean) = viewModelScope.launch { settingsRepo.set(Keys.SLEEP_CLOSE, on) }
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
