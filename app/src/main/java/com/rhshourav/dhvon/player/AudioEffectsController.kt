// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rhshourav.dhvon.data.settings.Keys
import com.rhshourav.dhvon.data.settings.SettingsRepository
import com.rhshourav.dhvon.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** Ten-band Pre-EQ curves (dB) for 31 · 62 · 125 · 250 · 500 · 1k · 2k · 4k · 8k · 16k Hz. */
enum class SoundSignature(val label: String, val gainsDb: FloatArray, val selectable: Boolean = true) {
    HARMAN("Harman Target", floatArrayOf(3.5f, 2.5f, 1.0f, 0.0f, -0.5f, 0.0f, 1.0f, 2.0f, 1.5f, 1.0f)),
    NEW_META_JM1("New Meta (JM-1)", floatArrayOf(2.0f, 1.5f, 0.5f, 0.0f, 0.0f, -0.5f, -0.5f, 0.5f, 0.0f, -0.5f)),
    DIFFUSE_FIELD("Diffuse Field", floatArrayOf(-1.5f, -1.0f, -0.5f, 0.0f, 0.0f, 0.5f, 1.5f, 2.0f, 1.5f, 1.0f)),
    V_SHAPED("V-Shaped Punch", floatArrayOf(5.0f, 3.5f, 1.0f, -2.0f, -3.0f, -1.5f, 1.0f, 3.0f, 4.5f, 4.0f)),
    WARM_NEUTRAL("Warm Neutral", floatArrayOf(2.5f, 2.0f, 1.5f, 0.5f, 0.0f, -0.5f, -1.0f, -1.5f, -2.0f, -2.5f)),
    VOCAL_CLARITY("Vocal Clarity", floatArrayOf(-3.0f, -2.0f, 0.0f, 2.0f, 4.0f, 4.5f, 3.0f, 1.0f, -1.0f, -2.0f)),
    BASS_BOOST("Sub-Bass Boost", floatArrayOf(6.5f, 5.0f, 3.0f, 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)),
    FLAT("Flat Reference", floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)),
    /** Warm tape roll-off; only ever applied by Lo-Fi mode, never offered in the selector. */
    LOFI_TAPE("Lo-Fi Tape", floatArrayOf(2.0f, 2.5f, 2.0f, 0.5f, 0.0f, -0.5f, -1.5f, -3.0f, -5.0f, -7.0f), selectable = false),
}

data class AudioDspState(
    /** An audio session is attached and at least one effect could be created. */
    val available: Boolean = false,
    /** DynamicsProcessing (Pre-EQ · MBC · Post-EQ · Limiter) exists on this device — Android 9+ and the OEM allows it. */
    val dspSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
    /** Master switch for the EQ / dynamics chain (signatures, adaptive EQ, compressor, limiter). */
    val enabled: Boolean = true,
    val signature: SoundSignature = SoundSignature.HARMAN,
    val dynamicAdaptiveEnabled: Boolean = true,
    /** Live Post-EQ compensation per band, dB. */
    val dynamicPostEqOffsets: List<Float> = List(BANDS) { 0f },
    val customBandsDb: List<Float> = List(BANDS) { 0f },
    val isCustomEq: Boolean = false,
    /** The analyzer is currently hearing music (drives the "adapting" indicator). */
    val signalActive: Boolean = false,
    val spatialEnabled: Boolean = false,
    /** 0..1000, the Virtualizer's native range. */
    val spatialStrength: Int = 550,
    val spatialSupported: Boolean = false,
    val spatialStrengthAdjustable: Boolean = false,
    val lofiEnabled: Boolean = false,
    /** 100..150 — the video player's right-edge volume gesture (LoudnessEnhancer). */
    val volumeBoostPct: Int = 100,
) {
    /** The static Pre-EQ curve actually in force right now. */
    val preEqGainsDb: List<Float>
        get() = when {
            lofiEnabled -> SoundSignature.LOFI_TAPE.gainsDb.toList()
            isCustomEq -> customBandsDb
            else -> signature.gainsDb.toList()
        }

    companion object { const val BANDS = 10 }
}

/**
 * DHVON's acoustic engine, attached to the shared player's audio session.
 *
 * Chain (all on the session mixer, via [DynamicsProcessing], Android 9+):
 *   input gain (headroom) → **Pre-EQ** (sound signature / custom) → **3-band compressor** → **Post-EQ** (live spectral
 *   compensation from [DhvonAudioProcessor.metrics]) → **limiter**.
 * Plus **Spatial Surround** ([Virtualizer]), **Lo-Fi mode** (0.88× speed & pitch, medium-room [PresetReverb], tape
 * roll-off curve) and a [LoudnessEnhancer] for the 100–150 % video volume boost.
 * It never pushes a value it has already pushed, because the Post-EQ is updated every 400 ms.
 */
@Singleton
class AudioEffectsController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val holder: PlayerHolder,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Band centres, used for the UI labels. */
    val bandFrequencies = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    /**
     * DynamicsProcessing bands are defined by their *upper* cutoff, so each band is cut at centre × √2 — that way the
     * "1 kHz" slider really moves 707 Hz–1.4 kHz instead of everything below 1 kHz.
     */
    private val bandCutoffs = floatArrayOf(44f, 88f, 177f, 354f, 707f, 1414f, 2828f, 5657f, 11314f, 20000f)

    private val _state = MutableStateFlow(AudioDspState())
    val state: StateFlow<AudioDspState> = _state.asStateFlow()

    /** Emits the device name when Bluetooth audio becomes the output (drives the "Switched to Bluetooth" pill). */
    private val _bluetoothConnectedEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val bluetoothConnectedEvent: SharedFlow<String> = _bluetoothConnectedEvent.asSharedFlow()

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var virtualizer: Virtualizer? = null
    private var presetReverb: PresetReverb? = null
    private var loudness: LoudnessEnhancer? = null
    private var currentSessionId = 0
    private var boundPlayer: Player? = null
    private var attachedExo: ExoPlayer? = null
    private var sessionListener: Player.Listener? = null
    private var started = false
    private var savedPlayback: PlaybackParameters? = null
    private var saveJob: Job? = null

    private val smoothedPost = FloatArray(AudioDspState.BANDS)
    private val zeros = FloatArray(AudioDspState.BANDS)

    // What the effect currently holds, so unchanged values never cross the binder again.
    private val appliedPre = FloatArray(AudioDspState.BANDS) { Float.NaN }
    private val appliedPost = FloatArray(AudioDspState.BANDS) { Float.NaN }
    private var appliedInputGain = Float.NaN
    private var appliedMbc: Boolean? = null
    private var appliedDspOn: Boolean? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val knownBluetooth = HashSet<Int>()

    private val knownHeadphones = HashSet<Int>()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // Only devices that were NOT already connected count (the callback also replays the ones that already were).
            if (addedDevices.filter { isHeadphoneSink(it) && knownHeadphones.add(it.id) }.isNotEmpty()) onHeadphonesConnected()
            val fresh = addedDevices.filter { isBluetoothSink(it) && knownBluetooth.add(it.id) }
            val dev = fresh.firstOrNull() ?: return
            _bluetoothConnectedEvent.tryEmit(dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth Audio")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            removedDevices.forEach { knownBluetooth.remove(it.id); knownHeadphones.remove(it.id) }
        }
    }

    private fun isHeadphoneSink(d: AudioDeviceInfo): Boolean = d.isSink && when (d.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
        else -> (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d.type == AudioDeviceInfo.TYPE_HEARING_AID)
    }

    private fun isBluetoothSink(d: AudioDeviceInfo): Boolean = d.isSink && (
        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        )

    // ------------------------------------------------------------------ lifecycle

    /** Idempotent. Called when media access is granted and when the playback service starts. */
    fun start() {
        if (started) return
        started = true
        runCatching {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outputs.filter { isBluetoothSink(it) }.forEach { knownBluetooth.add(it.id) }
            outputs.filter { isHeadphoneSink(it) }.forEach { knownHeadphones.add(it.id) }
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
        }
        scope.launch { holder.audioProcessor.metrics.collect { onSpectrum(it) } }
        scope.launch {
            restoreSavedState()
            holder.playerFlow.collect { attachPlayer(it) }      // re-attaches whenever a decoder switch rebuilds the player
        }
    }

    private fun attachPlayer(p: ExoPlayer) {
        sessionListener?.let { l -> attachedExo?.removeListener(l) }
        val l = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { attachToPlayer(p, audioSessionId) }
            // Headphones were connected while paused: cap the volume *before* the next play actually starts.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (playWhenReady && pendingSafeVolume) applySafeVolume() }
        }
        sessionListener = l
        attachedExo = p
        p.addListener(l)
        attachToPlayer(p, p.audioSessionId)                     // ExoPlayer already owns a session id right after creation
    }

    fun attachToPlayer(player: Player, audioSessionId: Int) {
        boundPlayer = player
        if (audioSessionId == 0 || audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        if (currentSessionId != audioSessionId) {
            releaseEffects()
            currentSessionId = audioSessionId
            initEffects(audioSessionId)
        }
        applyEffects()
    }

    fun releaseEffects() {
        runCatching { dynamicsProcessing?.release() }
        runCatching { virtualizer?.release() }
        runCatching { presetReverb?.release() }
        runCatching { loudness?.release() }
        dynamicsProcessing = null; virtualizer = null; presetReverb = null; loudness = null
        currentSessionId = 0
        resetAppliedCache()
    }

    private fun initEffects(sessionId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessing = runCatching { buildDynamicsProcessing(sessionId) }.getOrNull()
        }
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        presetReverb = runCatching { PresetReverb(0, sessionId) }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        resetAppliedCache()
        val v = virtualizer
        _state.update {
            it.copy(
                available = dynamicsProcessing != null || v != null || presetReverb != null,
                dspSupported = dynamicsProcessing != null,
                spatialSupported = v != null,
                spatialStrengthAdjustable = runCatching { v?.strengthSupported == true }.getOrDefault(false),
            )
        }
    }

    private fun resetAppliedCache() {
        appliedPre.fill(Float.NaN); appliedPost.fill(Float.NaN)
        appliedInputGain = Float.NaN; appliedMbc = null; appliedDspOn = null
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun buildDynamicsProcessing(sessionId: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,                              // channels
            true, AudioDspState.BANDS,      // Pre-EQ
            true, MBC_BANDS,                // multi-band compressor
            true, AudioDspState.BANDS,      // Post-EQ
            true,                           // limiter
        ).build()
        return DynamicsProcessing(0, sessionId, config).also { dp ->
            // Brick-wall-ish safety net at the end of the chain: -2 dBFS, 10:1, fast attack.
            dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -2f, 0f))
        }
    }

    // ------------------------------------------------------------------ public controls

    fun setEnabled(on: Boolean) { _state.update { it.copy(enabled = on) }; applyEffects(); scheduleSave() }

    fun setSoundSignature(signature: SoundSignature) {
        if (!signature.selectable) return
        _state.update { it.copy(signature = signature, isCustomEq = false) }
        applyEffects(); scheduleSave()
    }

    fun setDynamicAdaptive(enabled: Boolean) {
        if (!enabled) { smoothedPost.fill(0f) }
        _state.update { it.copy(dynamicAdaptiveEnabled = enabled, dynamicPostEqOffsets = if (enabled) it.dynamicPostEqOffsets else List(AudioDspState.BANDS) { 0f }) }
        applyEffects(); scheduleSave()
    }

    /** Dragging a slider starts from the curve you can see, then switches the EQ to "Custom". */
    fun setCustomBand(index: Int, gainDb: Float) {
        if (index !in 0 until AudioDspState.BANDS) return
        _state.update { s ->
            val base = if (s.isCustomEq) s.customBandsDb else s.signature.gainsDb.toList()
            s.copy(customBandsDb = base.toMutableList().also { it[index] = gainDb.coerceIn(-12f, 12f) }, isCustomEq = true)
        }
        applyEffects(); scheduleSave()
    }

    fun setSpatialSurround(enabled: Boolean, strength: Int = _state.value.spatialStrength) {
        _state.update { it.copy(spatialEnabled = enabled, spatialStrength = strength.coerceIn(0, 1000)) }
        applyEffects(); scheduleSave()
    }

    /** Slowed (0.88× speed *and* pitch) + medium-room reverb + warm tape roll-off. Lasts for the session, not saved. */
    fun setLofiMode(enabled: Boolean) {
        val p = boundPlayer
        if (enabled) {
            if (!_state.value.lofiEnabled) savedPlayback = p?.playbackParameters
            p?.playbackParameters = PlaybackParameters(0.88f, 0.88f)
        } else if (_state.value.lofiEnabled) {
            p?.playbackParameters = savedPlayback ?: PlaybackParameters.DEFAULT
            savedPlayback = null
        }
        _state.update { it.copy(lofiEnabled = enabled) }
        applyEffects()
    }

    /** 100..150 — the video player's right-edge volume gesture. Not persisted. */
    fun setVolumeBoost(pct: Int) {
        _state.update { it.copy(volumeBoostPct = pct.coerceIn(100, 150)) }
        applyBoost()
    }

    // ------------------------------------------------------------------ live spectral compensation

    private fun onSpectrum(m: SpectralMetrics) {
        val s = _state.value
        if (s.signalActive != m.isActiveSignal) _state.update { it.copy(signalActive = m.isActiveSignal) }

        val adaptive = s.enabled && s.dynamicAdaptiveEnabled && !s.lofiEnabled
        if (!m.isActiveSignal || !adaptive) {
            if (smoothedPost.any { abs(it) > 0.01f }) {
                for (i in smoothedPost.indices) smoothedPost[i] *= 0.7f
                publishPostEq()
            }
            return
        }

        val target = FloatArray(AudioDspState.BANDS)

        // 1. Sub-bass & warmth compensation when the track lacks low-end body.
        val bassDeficit = (m.midDb - 6.0f) - m.lowDb
        if (bassDeficit > 0f) {
            val boost = (bassDeficit * 0.45f).coerceIn(0f, 3.5f)
            target[0] = boost; target[1] = boost * 0.85f; target[2] = boost * 0.5f
        }
        // 2. Vocal presence lift when the midrange is recessed behind bass and treble.
        val midDeficit = ((m.lowDb + m.highDb) / 2f - 3.5f) - m.midDb
        if (midDeficit > 0f) {
            val v = (midDeficit * 0.5f).coerceIn(0f, 3.0f)
            target[5] = v * 0.7f; target[6] = v; target[7] = v * 0.8f
        }
        // 3. De-harsh / sibilance attenuation on bright or over-compressed tracks.
        val trebleExcess = m.highDb - (m.midDb + 1.5f)
        if (trebleExcess > 0f) {
            val cut = -(trebleExcess * 0.55f).coerceIn(0f, 4.0f)
            target[8] = cut * 0.75f; target[9] = cut
        }

        val alpha = 0.25f
        for (i in 0 until AudioDspState.BANDS) smoothedPost[i] += alpha * (target[i] - smoothedPost[i])
        publishPostEq()
    }

    private fun publishPostEq() {
        _state.update { it.copy(dynamicPostEqOffsets = smoothedPost.toList()) }
        applyDynamics()                // only the Post-EQ bands + headroom actually change
    }

    // ------------------------------------------------------------------ applying to the effects

    private fun applyEffects() {
        applyDynamics()
        applySpatial()
        applyReverb()
        applyBoost()
    }

    private fun applyDynamics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) applyFourStageDynamics(_state.value)
    }

    /**
     * Zero-clipping headroom: the loudest combined Pre+Post boost of any band is subtracted at the input, so the
     * EQ can never push the signal past full scale before the limiter even has to act.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyFourStageDynamics(s: AudioDspState) {
        val dp = dynamicsProcessing ?: return
        val lofi = s.lofiEnabled
        val dspOn = s.enabled || lofi
        val pre = when {
            lofi -> SoundSignature.LOFI_TAPE.gainsDb
            s.isCustomEq -> s.customBandsDb.toFloatArray()
            else -> s.signature.gainsDb
        }
        val post = if (s.enabled && s.dynamicAdaptiveEnabled && !lofi) smoothedPost else zeros
        val mbc = dspOn && s.dynamicAdaptiveEnabled

        runCatching {
            var peakCombinedBoost = 0f
            for (i in 0 until AudioDspState.BANDS) {
                val preGain = pre[i]
                val postGain = post[i]
                if (preGain + postGain > peakCombinedBoost) peakCombinedBoost = preGain + postGain
                if (appliedPre[i] != preGain) {
                    dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, bandCutoffs[i], preGain))
                    appliedPre[i] = preGain
                }
                if (appliedPost[i].isNaN() || abs(appliedPost[i] - postGain) >= 0.05f) {
                    dp.setPostEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, bandCutoffs[i], postGain))
                    appliedPost[i] = postGain
                }
            }
            val headroom = -peakCombinedBoost
            if (appliedInputGain.isNaN() || abs(appliedInputGain - headroom) >= 0.05f) {
                dp.setInputGainAllChannelsTo(headroom)
                appliedInputGain = headroom
            }
            if (appliedMbc != mbc) {
                // Bands are rewritten with their on/off flag; replacing the whole Mbc object would reset their settings.
                for (band in 0 until MBC_BANDS) dp.setMbcBandAllChannelsTo(band, mbcBand(band, mbc))
                appliedMbc = mbc
            }
            if (appliedDspOn != dspOn) {
                dp.setEnabled(dspOn)
                appliedDspOn = dspOn
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun mbcBand(band: Int, enabled: Boolean) = DynamicsProcessing.MbcBand(
        enabled, MBC_CUTOFFS[band],
        12f, 120f,                         // attack / release ms
        MBC_RATIOS[band], MBC_THRESHOLDS[band], 4f,
        -65f, 1.0f,                        // noise gate threshold / expander ratio (1.0 = off)
        0f, 0f,                            // pre / post gain — no make-up gain, headroom is already budgeted
    )

    private fun applySpatial() {
        val v = virtualizer ?: return
        val s = _state.value
        runCatching {
            if (s.spatialEnabled && s.spatialStrengthAdjustable) v.setStrength(s.spatialStrength.coerceIn(0, 1000).toShort())
            v.setEnabled(s.spatialEnabled)
        }
    }

    private fun applyReverb() {
        val r = presetReverb ?: return
        val lofi = _state.value.lofiEnabled
        runCatching {
            if (lofi) r.setPreset(PresetReverb.PRESET_MEDIUMROOM)
            r.setEnabled(lofi)
        }
    }

    private fun applyBoost() {
        val l = loudness ?: return
        val pct = _state.value.volumeBoostPct
        runCatching {
            l.setTargetGain((2000.0 * log10(pct.coerceAtLeast(100) / 100.0)).roundToInt())
            l.setEnabled(pct > 100)
        }
    }

    // ------------------------------------------------------------------ headphone safety

    private var pendingSafeVolume = false
    private var fadeJob: Job? = null

    /**
     * Each output device remembers its own media volume, so connecting headphones can suddenly bring back a loud level.
     * While DHVON has something loaded: cap the system media volume (default 40 %) and fade the sound in over ~1.2 s.
     * If playback is paused (the usual unplug → re-plug → play case) it is armed and applied the moment play is pressed.
     */
    private fun onHeadphonesConnected() {
        val p = boundPlayer ?: return
        if (p.mediaItemCount == 0) return                 // DHVON isn't in use — never touch the volume of other apps
        scope.launch {
            if (settings.get(Keys.SAFE_HP_VOLUME) == false) return@launch
            if (p.playWhenReady) applySafeVolume() else pendingSafeVolume = true
        }
    }

    private fun applySafeVolume() {
        pendingSafeVolume = false
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cap = (max * SAFE_VOLUME_FRACTION).roundToInt().coerceIn(1, max)
        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > cap) {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, cap, AudioManager.FLAG_SHOW_UI) }
        }
        fadeIn()
    }

    private fun fadeIn() {
        val p = boundPlayer ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val steps = 12
            for (i in 0 until steps) { p.volume = i / steps.toFloat(); delay(100) }
            p.volume = 1f
        }
    }

    // ------------------------------------------------------------------ persistence (global settings only)

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)                                        // slider drags fire many updates
            val s = _state.value
            settings.set(Keys.DSP_ENABLED, s.enabled)
            settings.setEnum(Keys.DSP_SIGNATURE, s.signature)
            settings.set(Keys.DSP_ADAPTIVE, s.dynamicAdaptiveEnabled)
            settings.set(Keys.DSP_CUSTOM_ON, s.isCustomEq)
            settings.set(Keys.DSP_CUSTOM_BANDS, s.customBandsDb.joinToString(","))
            settings.set(Keys.DSP_SPATIAL_ON, s.spatialEnabled)
            settings.set(Keys.DSP_SPATIAL_STRENGTH, s.spatialStrength)
        }
    }

    private suspend fun restoreSavedState() {
        val enabled = settings.get(Keys.DSP_ENABLED)
        val signature = settings.get(Keys.DSP_SIGNATURE)
            ?.let { n -> runCatching { SoundSignature.valueOf(n) }.getOrNull() }?.takeIf { it.selectable }
        val adaptive = settings.get(Keys.DSP_ADAPTIVE)
        val customOn = settings.get(Keys.DSP_CUSTOM_ON)
        val bands = settings.get(Keys.DSP_CUSTOM_BANDS)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == AudioDspState.BANDS }
        val spatialOn = settings.get(Keys.DSP_SPATIAL_ON)
        val spatialStrength = settings.get(Keys.DSP_SPATIAL_STRENGTH)
        _state.update {
            it.copy(
                enabled = enabled ?: it.enabled,
                signature = signature ?: it.signature,
                dynamicAdaptiveEnabled = adaptive ?: it.dynamicAdaptiveEnabled,
                customBandsDb = bands ?: it.customBandsDb,
                isCustomEq = (customOn ?: false) && bands != null,
                spatialEnabled = spatialOn ?: it.spatialEnabled,
                spatialStrength = (spatialStrength ?: it.spatialStrength).coerceIn(0, 1000),
            )
        }
    }

    private companion object {
        const val MBC_BANDS = 3
        /** Headphone safety: media volume is capped to this share of the maximum when headphones connect. */
        const val SAFE_VOLUME_FRACTION = 0.4f
        val MBC_CUTOFFS = floatArrayOf(250f, 4000f, 20000f)
        val MBC_RATIOS = floatArrayOf(2.2f, 1.8f, 2.0f)
        val MBC_THRESHOLDS = floatArrayOf(-18f, -20f, -16f)
    }
}
