// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rhshourav.dhvon.data.settings.SettingsRepository
import com.rhshourav.dhvon.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * DHVON Equalizer. Uses the platform Equalizer / BassBoost / Virtualizer attached to the player's audio session
 * (band count comes from the device — usually 5, some devices expose 10) plus LoudnessEnhancer for the
 * 100–150 % video volume boost. Re-attaches automatically when the player or its audio session changes.
 */
@Singleton
class AudioEffectsController @Inject constructor(
    private val holder: PlayerHolder,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    data class State(
        val available: Boolean = false,
        val enabled: Boolean = false,
        val preset: String = "Flat",
        val bandFreqsHz: List<Int> = emptyList(),
        val levels: List<Int> = emptyList(),
        val minLevel: Int = -1500,
        val maxLevel: Int = 1500,
        val bassBoost: Int = 0,
        val virtualizer: Int = 0,
        val bassSupported: Boolean = false,
        val virtSupported: Boolean = false,
        val volumeBoostPct: Int = 100,
    )

    val presets = listOf("Flat", "Bass Boost", "Vocal", "Cinema", "Custom")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var virt: Virtualizer? = null
    private var loud: LoudnessEnhancer? = null
    private var attached: ExoPlayer? = null
    private var sessionListener: Player.Listener? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { holder.playerFlow.collect { attach(it) } }
    }

    private fun attach(p: ExoPlayer) {
        sessionListener?.let { l -> attached?.removeListener(l) }
        val l = object : Player.Listener { override fun onAudioSessionIdChanged(audioSessionId: Int) { build(audioSessionId) } }
        sessionListener = l
        attached = p
        p.addListener(l)
        build(p.audioSessionId)
    }

    private fun build(session: Int) {
        release()
        if (session == C.AUDIO_SESSION_ID_UNSET) return
        scope.launch {
            val saved = settings.settings.first()
            val boost = _state.value.volumeBoostPct
            runCatching {
                val e = Equalizer(0, session).also { eq = it }
                val n = e.numberOfBands.toInt()
                val range = e.bandLevelRange
                val freqs = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }
                val levels = if (saved.eqLevels.size == n) saved.eqLevels else List(n) { 0 }
                bass = runCatching { BassBoost(0, session) }.getOrNull()
                virt = runCatching { Virtualizer(0, session) }.getOrNull()
                loud = runCatching { LoudnessEnhancer(session) }.getOrNull()
                val st = State(
                    available = true, enabled = saved.eqEnabled, preset = saved.eqPreset,
                    bandFreqsHz = freqs, levels = levels, minLevel = range[0].toInt(), maxLevel = range[1].toInt(),
                    bassBoost = saved.bassBoost, virtualizer = saved.virtualizer,
                    bassSupported = bass?.strengthSupported == true, virtSupported = virt?.strengthSupported == true,
                    volumeBoostPct = boost,
                )
                _state.value = st
                push(st)
            }.onFailure { _state.value = State(volumeBoostPct = boost) }
        }
    }

    private fun push(st: State) {
        runCatching {
            eq?.let { e ->
                e.setEnabled(st.enabled)
                st.levels.forEachIndexed { i, lvl -> e.setBandLevel(i.toShort(), lvl.coerceIn(st.minLevel, st.maxLevel).toShort()) }
            }
            bass?.let { if (it.strengthSupported) { it.setStrength(st.bassBoost.toShort()); it.setEnabled(st.enabled && st.bassBoost > 0) } }
            virt?.let { if (it.strengthSupported) { it.setStrength(st.virtualizer.toShort()); it.setEnabled(st.enabled && st.virtualizer > 0) } }
            loud?.let {
                val gain = (2000.0 * log10(st.volumeBoostPct.coerceAtLeast(100) / 100.0)).roundToInt()
                it.setTargetGain(gain); it.setEnabled(st.volumeBoostPct > 100)
            }
        }
    }

    private fun update(block: (State) -> State) {
        val st = block(_state.value)
        _state.value = st
        push(st)
        scope.launch { settings.saveEq(st.enabled, st.preset, st.levels, st.bassBoost, st.virtualizer) }
    }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }
    fun setBand(index: Int, level: Int) = update { s ->
        s.copy(levels = s.levels.toMutableList().also { if (index in it.indices) it[index] = level }, preset = "Custom")
    }
    fun applyPreset(name: String) = update { s ->
        if (name == "Custom") s.copy(preset = name)
        else s.copy(preset = name, enabled = true, levels = presetLevels(name, s.levels.size, s.minLevel, s.maxLevel))
    }
    fun setBassBoost(v: Int) = update { it.copy(bassBoost = v.coerceIn(0, 1000), enabled = true) }
    fun setVirtualizer(v: Int) = update { it.copy(virtualizer = v.coerceIn(0, 1000), enabled = true) }

    /** 100..150 — used by the video player's right-edge volume gesture. Not persisted. */
    fun setVolumeBoost(pct: Int) {
        val st = _state.value.copy(volumeBoostPct = pct.coerceIn(100, 150))
        _state.value = st
        push(st)
    }

    private fun release() {
        runCatching { eq?.release() }; runCatching { bass?.release() }; runCatching { virt?.release() }; runCatching { loud?.release() }
        eq = null; bass = null; virt = null; loud = null
    }

    private fun presetLevels(name: String, bands: Int, min: Int, max: Int): List<Int> = List(bands) { i ->
        val x = if (bands <= 1) 0f else i / (bands - 1f)
        val shape = when (name) {
            "Bass Boost" -> 0.85f * (1f - x * 2.6f).coerceAtLeast(0f)
            "Vocal" -> 0.55f * exp(-((x - 0.62f) / 0.2f).pow(2)) - if (x < 0.12f) 0.25f else 0f
            "Cinema" -> 0.6f * (1f - x * 3f).coerceAtLeast(0f) + 0.5f * ((x - 0.65f) / 0.35f).coerceAtLeast(0f)
            else -> 0f
        }
        (shape * max).roundToInt().coerceIn(min, max)
    }
}
