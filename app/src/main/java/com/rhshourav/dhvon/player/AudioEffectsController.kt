// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import android.content.Context
import android.media.AudioAttributes
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
import com.rhshourav.dhvon.data.settings.EQ_BAND_COUNT
import com.rhshourav.dhvon.data.settings.Keys
import com.rhshourav.dhvon.data.settings.SettingsRepository
import com.rhshourav.dhvon.data.settings.UserSoundProfile
import com.rhshourav.dhvon.data.settings.decodeCustomProfiles
import com.rhshourav.dhvon.data.settings.encodeCustomProfiles
import com.rhshourav.dhvon.data.settings.normalizeBandGains
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt

/** 14-band Pre-EQ curves (dB) for 31 · 50 · 80 · 125 · 200 · 315 · 500 · 800 · 1.25k · 2k · 3.15k · 5k · 8k · 16k Hz. */
enum class SoundSignature(
    val label: String,
    val gainsDb: FloatArray,
    val selectable: Boolean = true,
    /** Share of the opposite ear mixed in (0 = no crossfeed) and the interaural delay; done in Float32 by [DhvonAudioProcessor]. */
    val crossfeedMix: Float = 0f,
    val crossfeedDelayUs: Int = 0,
) {
    HARMAN("Harman Target", floatArrayOf(3.5f, 2.8f, 2.0f, 1.0f, 0.3f, -0.2f, -0.5f, -0.2f, 0.3f, 1.0f, 1.7f, 1.8f, 1.5f, 1.0f)),
    NEW_META_JM1("New Meta (JM-1)", floatArrayOf(2.0f, 1.7f, 1.1f, 0.5f, 0.2f, 0.0f, 0.0f, -0.3f, -0.5f, -0.5f, 0.2f, 0.3f, 0.0f, -0.5f)),
    DIFFUSE_FIELD("Diffuse Field", floatArrayOf(-1.5f, -1.2f, -0.8f, -0.5f, -0.2f, 0.0f, 0.0f, 0.3f, 0.8f, 1.5f, 1.8f, 1.8f, 1.5f, 1.0f)),
    V_SHAPED("V-Shaped Punch", floatArrayOf(5.0f, 4.0f, 2.6f, 1.0f, -1.0f, -2.3f, -3.0f, -2.0f, -0.7f, 1.0f, 2.3f, 3.5f, 4.5f, 4.0f)),
    WARM_NEUTRAL("Warm Neutral", floatArrayOf(2.5f, 2.2f, 1.8f, 1.5f, 0.8f, 0.3f, 0.0f, -0.3f, -0.7f, -1.0f, -1.3f, -1.7f, -2.0f, -2.5f)),
    VOCAL_CLARITY("Vocal Clarity", floatArrayOf(-3.0f, -2.3f, -1.3f, 0.0f, 1.4f, 2.7f, 4.0f, 4.3f, 4.0f, 3.0f, 1.7f, 0.4f, -1.0f, -2.0f)),
    BASS_BOOST("Sub-Bass Boost", floatArrayOf(6.5f, 5.5f, 4.3f, 3.0f, 1.3f, 0.3f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)),
    FLAT("Flat Reference", floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)),
    // Built-in earphone profiles (names double as the profile ids kept in settings), on the 14-band ISO grid:
    // 31 · 50 · 80 · 125 · 200 · 315 · 500 · 800 · 1.25k · 2k · 3.15k · 5k · 8k · 16k Hz.
    /** Wired IEMs on a USB DAC: subtle +1.0 dB @ 50 Hz warmth, −1.0 dB @ 3.15 kHz glare tame, 60° crossfeed (18 %, 240 µs). */
    CHU_II_USB("Chu II + USB Cinema", floatArrayOf(0.6f, 1.0f, 0.4f, 0.0f, -0.4f, 0.0f, 0.0f, 0.0f, 0.0f, -0.4f, -1.0f, -0.4f, 0.0f, 0.4f), crossfeedMix = 0.18f, crossfeedDelayUs = 240),
    /** KZ Xtra (QCC3091): tames mid-bass bleed @ 200 Hz and sibilance @ 8 kHz, lifts vocal body @ 1.25–2 kHz; no crossfeed. */
    KZ_XTRA_FIX("KZ Xtra Correction", floatArrayOf(0.0f, 0.0f, -0.5f, -1.2f, -2.0f, -0.8f, 0.0f, 0.6f, 1.5f, 1.0f, 0.0f, -0.8f, -2.0f, 0.5f)),
    /** Warm tape roll-off; only ever applied by Lo-Fi mode, never offered in the selector. */
    LOFI_TAPE("Lo-Fi Tape", floatArrayOf(2.0f, 2.3f, 2.3f, 2.0f, 1.0f, 0.3f, 0.0f, -0.3f, -0.8f, -1.5f, -2.5f, -3.6f, -5.0f, -7.0f), selectable = false),
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
    /** Ask for bit-perfect USB output (Android 14+). Persisted; whether it is actually active is [UsbDacManager]'s confirmed state. */
    val bitPerfectUsbEnabled: Boolean = false,
    /** Switch to the USB / Bluetooth profile when that device connects. Persisted. */
    val autoDeviceProfileEnabled: Boolean = true,
    /** Crossfeed in force right now: the active profile's mix and its interaural delay in µs (0 = off). */
    val crossfeedMix: Float = 0f,
    val crossfeedDelayUs: Int = 0,
    /** Profiles the user saved, and which one (if any) is the active curve. Editing a band clears the active id. */
    val customProfiles: List<UserSoundProfile> = emptyList(),
    val activeCustomProfileId: String? = null,
    /** Profile applied when that kind of output becomes active (built-in name or custom id; "" = keep current). */
    val usbDacPresetId: String = "CHU_II_USB",
    val bluetoothPresetId: String = "KZ_XTRA_FIX",
    val wiredPresetId: String = "",
    val speakerPresetId: String = "",
) {
    /** The static Pre-EQ curve actually in force right now. */
    val preEqGainsDb: List<Float>
        get() = when {
            lofiEnabled -> SoundSignature.LOFI_TAPE.gainsDb.toList()
            isCustomEq -> customBandsDb
            else -> signature.gainsDb.toList()
        }

    companion object { const val BANDS = EQ_BAND_COUNT }
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
    /** Source / device / output-path diagnostics for the Equalizer's audio path card. */
    val usbDac: UsbDacManager,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** The 14 ISO-spaced band centres (Hz). */
    val bandFrequencies = floatArrayOf(31f, 50f, 80f, 125f, 200f, 315f, 500f, 800f, 1250f, 2000f, 3150f, 5000f, 8000f, 16000f)

    /** Compact UI labels for [bandFrequencies]. */
    val bandLabels = listOf("31", "50", "80", "125", "200", "315", "500", "800", "1.25k", "2k", "3.15k", "5k", "8k", "16k")

    /**
     * DynamicsProcessing bands are defined by their *upper* cutoff, so each band is cut at centre × √2 — that way the
     * "1 kHz" slider really moves 707 Hz–1.4 kHz instead of everything below 1 kHz.
     */
    private val bandCutoffs = FloatArray(AudioDspState.BANDS) { minOf(bandFrequencies[it] * 1.4142135f, 20000f) }   // 16 kHz → clamped to 20 kHz

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
            scheduleRouteRefresh()                  // resolves the active route, tells the diagnostics, applies that route's profile
            val dev = fresh.firstOrNull() ?: return
            _bluetoothConnectedEvent.tryEmit(dev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth Audio")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            removedDevices.forEach { knownBluetooth.remove(it.id); knownHeadphones.remove(it.id) }
            scheduleRouteRefresh()
        }
    }

    private fun isHeadphoneSink(d: AudioDeviceInfo): Boolean = d.isSink && when (d.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
        else -> (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d.type == AudioDeviceInfo.TYPE_HEARING_AID)
    }

    /** A2DP, LE Audio headset / speaker, hearing aid — read straight from AudioDeviceInfo, no Bluetooth permission involved. */
    private fun isBluetoothSink(d: AudioDeviceInfo): Boolean = d.isSink && isBluetoothOutputType(d.type)

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
        refreshRoute(applyProfile = false)          // initial diagnostics only: starting the app never rewrites the saved EQ
        scope.launch {
            settings.settings.map {
                DeviceSettings(it.bitPerfectUsbEnabled, it.autoDeviceProfileEnabled, it.usbDacPresetId, it.bluetoothPresetId,
                    it.wiredPresetId, it.speakerPresetId, it.customProfiles)
            }.distinctUntilChanged().collect { d ->
                _state.update {
                    it.copy(
                        bitPerfectUsbEnabled = d.bitPerfect, autoDeviceProfileEnabled = d.auto,
                        usbDacPresetId = d.usb, bluetoothPresetId = d.bluetooth, wiredPresetId = d.wired, speakerPresetId = d.speaker,
                        customProfiles = d.profiles,
                    )
                }
                usbDac.setBitPerfectRequested(d.bitPerfect)
            }
        }
        scope.launch { holder.audioProcessor.metrics.collect { onSpectrum(it) } }
        scope.launch {
            restoreSavedState()
            applyCrossfeed()
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
        _state.update {
            it.copy(
                signature = signature, isCustomEq = false, activeCustomProfileId = null,
                crossfeedMix = signature.crossfeedMix, crossfeedDelayUs = signature.crossfeedDelayUs,
            )
        }
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
            // Starts from the curve that is active right now (never from flat), and is always exactly 14 values long.
            val base = normalizeBandGains(if (s.isCustomEq) s.customBandsDb else s.signature.gainsDb.toList())
            s.copy(customBandsDb = base.toMutableList().also { it[index] = gainDb.coerceIn(-12f, 12f) }, isCustomEq = true, activeCustomProfileId = null)
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

    // ------------------------------------------------------------------ output route · automatic profiles · saved profiles

    private data class DeviceSettings(
        val bitPerfect: Boolean, val auto: Boolean,
        val usb: String, val bluetooth: String, val wired: String, val speaker: String,
        val profiles: List<UserSoundProfile>,
    )

    private var lastRouteKey: String? = null
    private var routeJob: Job? = null

    /** Ask Android for bit-perfect USB output (Android 14+). Persisted; activity is reported only after Android confirms it. */
    fun setBitPerfectUsb(on: Boolean) { scope.launch { settings.set(Keys.BIT_PERFECT_USB, on) } }

    fun setAutoDeviceProfile(on: Boolean) { scope.launch { settings.set(Keys.AUTO_DEVICE_PROFILE, on) } }

    /** The profile assigned to an output type ("" = keep current). */
    fun presetIdFor(route: AudioRouteType, s: AudioDspState = _state.value): String = when (route) {
        AudioRouteType.BLUETOOTH_TWS -> s.bluetoothPresetId
        AudioRouteType.USB_DAC -> s.usbDacPresetId
        AudioRouteType.WIRED_ANALOG -> s.wiredPresetId
        AudioRouteType.SPEAKER -> s.speakerPresetId
    }

    /** Assigns a built-in profile name, a custom profile id, or "" (keep current) to an output type. */
    fun setRoutePreset(route: AudioRouteType, profileId: String) {
        val key = when (route) {
            AudioRouteType.BLUETOOTH_TWS -> Keys.BLUETOOTH_PRESET
            AudioRouteType.USB_DAC -> Keys.USB_DAC_PRESET
            AudioRouteType.WIRED_ANALOG -> Keys.WIRED_PRESET
            AudioRouteType.SPEAKER -> Keys.SPEAKER_PRESET
        }
        scope.launch { settings.set(key, profileId) }
        // Assigning a profile to the output that is playing right now applies it straight away.
        if (profileId.isNotEmpty() && _state.value.autoDeviceProfileEnabled && usbDac.diagnostics.value.routeType == route) applyProfileById(profileId)
    }

    /**
     * Built-in profiles are resolved first, then the user's saved ones. Band gains, crossfeed and the adaptive flag change in a single
     * state update, and the input headroom is recomputed from the new combined boost in the same pass (see applyFourStageDynamics).
     */
    fun applyProfileById(id: String) {
        val builtIn = SoundSignature.entries.firstOrNull { it.selectable && (it.name == id || (id == "BALANCED_REF" && it == SoundSignature.FLAT)) }
        if (builtIn != null) { setSoundSignature(builtIn); return }
        _state.value.customProfiles.firstOrNull { it.id == id }?.let { applyCustomProfile(it) }
    }

    private fun applyCustomProfile(profile: UserSoundProfile) {
        val gains = normalizeBandGains(profile.bandGainsDb)
        val mix = profile.crossfeedMix.coerceIn(0f, 0.5f)
        if (!profile.adaptiveEqEnabled) smoothedPost.fill(0f)
        _state.update {
            it.copy(
                customBandsDb = gains, isCustomEq = true, activeCustomProfileId = profile.id,
                crossfeedMix = mix, crossfeedDelayUs = if (mix > 0f) DEFAULT_CROSSFEED_DELAY_US else 0,
                dynamicAdaptiveEnabled = profile.adaptiveEqEnabled,
                dynamicPostEqOffsets = if (profile.adaptiveEqEnabled) it.dynamicPostEqOffsets else List(AudioDspState.BANDS) { 0f },
            )
        }
        applyEffects(); scheduleSave()
    }

    /** Saves the live 14-band curve, the crossfeed mix and the adaptive-EQ flag under [name]; the new profile becomes the active one. */
    fun saveCurrentAsCustomProfile(name: String) {
        val clean = name.trim().take(32)
        if (clean.isEmpty()) return
        val s = _state.value
        val gains = normalizeBandGains(if (s.isCustomEq) s.customBandsDb else s.signature.gainsDb.toList())
        val profile = UserSoundProfile(
            id = "custom_${System.currentTimeMillis()}", name = clean, bandGainsDb = gains,
            crossfeedMix = s.crossfeedMix, adaptiveEqEnabled = s.dynamicAdaptiveEnabled,
        )
        // The profile IS the current curve, so only the bookkeeping changes: it is now the named, active one.
        _state.update { it.copy(customBandsDb = gains, isCustomEq = true, activeCustomProfileId = profile.id) }
        scheduleSave()
        scope.launch {
            val saved = decodeCustomProfiles(settings.get(Keys.CUSTOM_PROFILES).orEmpty())
            settings.set(Keys.CUSTOM_PROFILES, encodeCustomProfiles(saved + profile))
        }
    }

    /** Removes a saved profile, clears every output assignment that pointed at it, and leaves the EQ on Flat if it was the active curve. */
    fun deleteCustomProfile(id: String) {
        val s = _state.value
        if (s.customProfiles.none { it.id == id }) return
        if (s.activeCustomProfileId == id) applyProfileById(FALLBACK_PROFILE_ID)
        scope.launch {
            val remaining = decodeCustomProfiles(settings.get(Keys.CUSTOM_PROFILES).orEmpty()).filterNot { it.id == id }
            settings.set(Keys.CUSTOM_PROFILES, encodeCustomProfiles(remaining))
            listOf(Keys.USB_DAC_PRESET, Keys.BLUETOOTH_PRESET, Keys.WIRED_PRESET, Keys.SPEAKER_PRESET).forEach { key ->
                if (settings.get(key) == id) settings.set(key, "")
            }
        }
    }

    /** Maps a custom curve saved by the 10-band release onto the 14-band grid, interpolating over log-frequency. */
    private fun resampleLegacyBands(old: List<Float>): List<Float> = List(AudioDspState.BANDS) { i ->
        val f = bandFrequencies[i]
        val c = LEGACY_10_BAND_CENTERS
        when {
            f <= c.first() -> old.first()
            f >= c.last() -> old.last()
            else -> {
                val k = c.indexOfLast { it <= f }
                old[k] + (old[k + 1] - old[k]) * (ln(f / c[k]) / ln(c[k + 1] / c[k]))
            }
        }.coerceIn(-12f, 12f)
    }

    /** Android settles the route a moment after the device list changes, so it is resolved now and again shortly after. */
    private fun scheduleRouteRefresh() {
        refreshRoute(applyProfile = true)
        routeJob?.cancel()
        routeJob = scope.launch { delay(600); refreshRoute(applyProfile = true) }
    }

    /**
     * Resolves where media audio really goes (speaker / wired / USB DAC / Bluetooth TWS) and tells the diagnostics. Only a change of route
     * counts: with Auto-Switch on, the profile assigned to the new route is then applied (when one is assigned). Bit-Perfect is USB-only,
     * so on any other route the processor bypass is forced off.
     */
    private fun refreshRoute(applyProfile: Boolean) {
        val device = resolveActiveRoute(audioManager)
        val type = routeTypeOf(device)
        val key = "${device?.id}|${device?.type}"
        if (key == lastRouteKey) return
        lastRouteKey = key
        usbDac.onOutputRouteChanged(device)
        if (type != AudioRouteType.USB_DAC) holder.audioProcessor.bypassForBitPerfect = false
        if (applyProfile && _state.value.autoDeviceProfileEnabled) {
            presetIdFor(type).takeIf { it.isNotEmpty() }?.let { applyProfileById(it) }
        }
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
            target[0] = boost; target[1] = boost * 0.9f; target[2] = boost * 0.75f; target[3] = boost * 0.5f        // 31 · 50 · 80 · 125 Hz
        }
        // 2. Vocal presence lift when the midrange is recessed behind bass and treble.
        val midDeficit = ((m.lowDb + m.highDb) / 2f - 3.5f) - m.midDb
        if (midDeficit > 0f) {
            val v = (midDeficit * 0.5f).coerceIn(0f, 3.0f)
            target[8] = v * 0.7f; target[9] = v; target[10] = v * 0.8f                                               // 1.25k · 2k · 3.15k
        }
        // 3. De-harsh / sibilance attenuation on bright or over-compressed tracks.
        val trebleExcess = m.highDb - (m.midDb + 1.5f)
        if (trebleExcess > 0f) {
            val cut = -(trebleExcess * 0.55f).coerceIn(0f, 4.0f)
            target[12] = cut * 0.75f; target[13] = cut                                                               // 8k · 16k
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
        applyCrossfeed()
    }

    /**
     * Crossfeed belongs to the active profile (it stays when the bands are edited into "Custom"); it is off while the EQ master switch is off
     * or Lo-Fi is on. The processor itself bypasses it during confirmed bit-perfect output.
     */
    private fun applyCrossfeed() {
        val s = _state.value
        val on = s.enabled && !s.lofiEnabled && s.crossfeedMix > 0f
        holder.audioProcessor.setCrossfeed(if (on) s.crossfeedMix else 0f, if (on) s.crossfeedDelayUs else 0)
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
            s.isCustomEq -> FloatArray(AudioDspState.BANDS) { s.customBandsDb.getOrElse(it) { 0f } }
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
            settings.set(Keys.DSP_CROSSFEED, s.crossfeedMix)
            settings.set(Keys.DSP_ACTIVE_CUSTOM, s.activeCustomProfileId.orEmpty())
        }
    }

    private suspend fun restoreSavedState() {
        val enabled = settings.get(Keys.DSP_ENABLED)
        val signature = settings.get(Keys.DSP_SIGNATURE)
            ?.let { n -> runCatching { SoundSignature.valueOf(n) }.getOrNull() }?.takeIf { it.selectable }
        val adaptive = settings.get(Keys.DSP_ADAPTIVE)
        val customOn = settings.get(Keys.DSP_CUSTOM_ON) ?: false
        // A custom curve saved by the 10-band release is resampled onto the 14-band grid; any other wrong-sized list resets to flat.
        // Nothing is ever indexed blindly, so an IndexOutOfBoundsException cannot happen.
        val bands = settings.get(Keys.DSP_CUSTOM_BANDS)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.let { raw ->
            if (raw.size == LEGACY_10_BAND_CENTERS.size) resampleLegacyBands(raw) else normalizeBandGains(raw)
        }
        val spatialOn = settings.get(Keys.DSP_SPATIAL_ON)
        val spatialStrength = settings.get(Keys.DSP_SPATIAL_STRENGTH)
        val savedCrossfeed = settings.get(Keys.DSP_CROSSFEED)
        val activeCustom = settings.get(Keys.DSP_ACTIVE_CUSTOM)?.takeIf { it.isNotBlank() }
        _state.update {
            val sig = signature ?: it.signature
            val custom = customOn && bands != null
            val mix = (savedCrossfeed ?: if (custom) 0f else sig.crossfeedMix).coerceIn(0f, 0.5f)
            val delay = when {
                mix <= 0f -> 0
                custom -> DEFAULT_CROSSFEED_DELAY_US
                else -> sig.crossfeedDelayUs.takeIf { d -> d > 0 } ?: DEFAULT_CROSSFEED_DELAY_US
            }
            it.copy(
                enabled = enabled ?: it.enabled,
                signature = sig,
                dynamicAdaptiveEnabled = adaptive ?: it.dynamicAdaptiveEnabled,
                customBandsDb = bands ?: it.customBandsDb,
                isCustomEq = custom,
                activeCustomProfileId = if (custom) activeCustom else null,
                crossfeedMix = mix, crossfeedDelayUs = delay,
                spatialEnabled = spatialOn ?: it.spatialEnabled,
                spatialStrength = (spatialStrength ?: it.spatialStrength).coerceIn(0, 1000),
            )
        }
    }

    private companion object {
        const val MBC_BANDS = 3
        const val DEFAULT_CROSSFEED_DELAY_US = 240
        /** What a profile falls back to when the active custom profile is deleted ("Flat Reference"; "BALANCED_REF" is accepted as its alias). */
        const val FALLBACK_PROFILE_ID = "FLAT"
        /** The 10-band grid of the previous DHVON release, for migrating a saved custom curve. */
        val LEGACY_10_BAND_CENTERS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        /** Headphone safety: media volume is capped to this share of the maximum when headphones connect. */
        const val SAFE_VOLUME_FRACTION = 0.4f
        val MBC_CUTOFFS = floatArrayOf(250f, 4000f, 20000f)
        val MBC_RATIOS = floatArrayOf(2.2f, 1.8f, 2.0f)
        val MBC_THRESHOLDS = floatArrayOf(-18f, -20f, -16f)
    }
}
