// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { OBSIDIAN("Obsidian"), AMOLED("AMOLED Black"), LIGHT("Light"), DYNAMIC("Material You") }
enum class ViewMode { GRID, LIST }
enum class SortBy(val label: String) { NAME("Name"), DATE("Date"), SIZE("Size"), DURATION("Duration") }
enum class DecoderMode(val short: String, val label: String, val hint: String) {
    HW("HW", "Hardware", "Fastest, lowest battery use"),
    HW_PLUS("HW+", "Hardware+", "Hardware video, FFmpeg audio for DTS/AC3/EAC3"),
    SW("SW", "Software", "Software video + FFmpeg audio — maximum compatibility"),
}
enum class ProgressStyle(val label: String) { LINE("Line"), FLAT("Flat"), WAVE("Waveform") }
enum class SeekSnap(val label: String) { EXACT("Frame accurate"), CLOSEST("Closest keyframe"), PREVIOUS("Previous keyframe") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.OBSIDIAN,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortBy: SortBy = SortBy.DATE,
    val sortAscending: Boolean = false,
    val compactDensity: Boolean = false,
    val accentCyan: Boolean = false,
    val seekSensitivity: Float = 1f,
    val brightnessSensitivity: Float = 1f,
    val volumeSensitivity: Float = 1f,
    val doubleTapSeconds: Int = 10,
    val seekSnap: SeekSnap = SeekSnap.CLOSEST,
    val decoderMode: DecoderMode = DecoderMode.HW,
    val autoPip: Boolean = true,
    val backgroundPlay: Boolean = false,
    val resumePlayback: Boolean = true,
    val keepScreenOn: Boolean = true,
    val subtitleSizeSp: Float = 20f,
    val subtitleColorIdx: Int = 0,
    val subtitleBackground: Boolean = false,
    val lastFolderPath: String? = null,
    val safTrees: Set<String> = emptySet(),
    val eqEnabled: Boolean = false,
    val eqPreset: String = "Flat",
    val eqLevels: List<Int> = emptyList(),
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    // ---- customisation
    val accentArgb: Int = 0,                       // 0 = use the theme's own accent
    val textScale: Float = 1f,
    val videoProgressStyle: ProgressStyle = ProgressStyle.LINE,
    val musicProgressStyle: ProgressStyle = ProgressStyle.WAVE,
    val hiddenHomeSections: Set<String> = emptySet(),
    val gestureSeek: Boolean = true,
    val gestureBrightness: Boolean = true,
    val gestureVolume: Boolean = true,
    val gestureZoom: Boolean = true,
    val gestureDoubleTap: Boolean = true,
    val controlsHideSeconds: Int = 4,
    val showRemainingTime: Boolean = false,
    val edgeBufferDp: Int = 32,                    // video gestures ignore touches this close to a screen edge
    val sleepCloseApp: Boolean = true,             // sleep timer also closes the app when it fires
    val safeHeadphoneVolume: Boolean = true,       // cap the volume + fade in when headphones connect
    val preferEnglishAudio: Boolean = true,        // auto-pick the English audio track when a video has several
    val restoreSession: Boolean = true,            // bring back the last queue after the process is killed
    // ---- USB DAC / per-transport profiles
    val bitPerfectUsbEnabled: Boolean = false,     // ask Android 14+ for bit-perfect output on a USB DAC (DSP is bypassed while it is confirmed)
    val autoDeviceProfileEnabled: Boolean = true,  // switch the EQ profile when a USB DAC / Bluetooth device connects
    val usbDacPresetId: String = "CHU_II_USB",     // SoundSignature name applied when a USB DAC connects
    val bluetoothPresetId: String = "KZ_XTRA_FIX", // SoundSignature name applied when Bluetooth audio connects
    val wiredPresetId: String = "",                // profile (built-in name or custom id) for a wired headset; "" = keep current
    val speakerPresetId: String = "",              // profile for the phone speaker; "" = keep current
    val customProfiles: List<UserSoundProfile> = emptyList(),
)

/** The equalizer has 14 ISO-spaced bands (31 Hz … 16 kHz). Anything persisted with another length is never indexed blindly. */
const val EQ_BAND_COUNT = 14

/** A band list of the right size is kept (clamped to ±12 dB); any other size — e.g. one saved by the 10-band release — resets to flat. */
fun normalizeBandGains(raw: List<Float>): List<Float> =
    if (raw.size == EQ_BAND_COUNT) raw.map { if (it.isFinite()) it.coerceIn(-12f, 12f) else 0f } else List(EQ_BAND_COUNT) { 0f }

/** A saved EQ profile: the 14 band gains plus the crossfeed mix and the adaptive-EQ flag it was saved with. */
data class UserSoundProfile(
    val id: String,
    val name: String,
    val bandGainsDb: List<Float>, // Always normalized to size 14
    val crossfeedMix: Float = 0f,
    val adaptiveEqEnabled: Boolean = false,
)

fun encodeCustomProfiles(profiles: List<UserSoundProfile>): String {
    val arr = JSONArray()
    profiles.forEach { p ->
        val obj = JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("crossfeedMix", p.crossfeedMix.toDouble())
            put("adaptiveEqEnabled", p.adaptiveEqEnabled)
            put("bands", JSONArray().apply {
                List(EQ_BAND_COUNT) { idx -> p.bandGainsDb.getOrElse(idx) { 0f } }.forEach { put(it.toDouble()) }
            })
        }
        arr.put(obj)
    }
    return arr.toString()
}

/** One damaged entry is skipped instead of losing every saved profile. */
fun decodeCustomProfiles(raw: String): List<UserSoundProfile> {
    if (raw.isBlank()) return emptyList()
    val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        runCatching {
            val obj = arr.getJSONObject(i)
            val bandsArr = obj.getJSONArray("bands")
            val rawBands = List(bandsArr.length()) { b -> bandsArr.getDouble(b).toFloat() }
            UserSoundProfile(
                id = obj.getString("id"),
                name = obj.getString("name"),
                bandGainsDb = normalizeBandGains(rawBands),
                crossfeedMix = obj.optDouble("crossfeedMix", 0.0).toFloat().coerceIn(0f, 0.5f),
                adaptiveEqEnabled = obj.optBoolean("adaptiveEqEnabled", false),
            )
        }.getOrNull()
    }
}

object Keys {
    val THEME = stringPreferencesKey("theme")
    val VIEW = stringPreferencesKey("view_mode")
    val SORT = stringPreferencesKey("sort_by")
    val SORT_ASC = booleanPreferencesKey("sort_asc")
    val COMPACT = booleanPreferencesKey("compact")
    val ACCENT_CYAN = booleanPreferencesKey("accent_cyan")
    val SEEK_SENS = floatPreferencesKey("seek_sens")
    val BRIGHT_SENS = floatPreferencesKey("bright_sens")
    val VOL_SENS = floatPreferencesKey("vol_sens")
    val DOUBLE_TAP = intPreferencesKey("double_tap_sec")
    val SEEK_SNAP = stringPreferencesKey("seek_snap")
    val DECODER = stringPreferencesKey("decoder")
    val AUTO_PIP = booleanPreferencesKey("auto_pip")
    val BG_PLAY = booleanPreferencesKey("bg_play")
    val RESUME = booleanPreferencesKey("resume")
    val KEEP_ON = booleanPreferencesKey("keep_screen_on")
    val SUB_SIZE = floatPreferencesKey("sub_size")
    val SUB_COLOR = intPreferencesKey("sub_color")
    val SUB_BG = booleanPreferencesKey("sub_bg")
    val LAST_FOLDER = stringPreferencesKey("last_folder")
    val SAF_TREES = stringSetPreferencesKey("saf_trees")
    val EQ_ON = booleanPreferencesKey("eq_on")
    val EQ_PRESET = stringPreferencesKey("eq_preset")
    val EQ_LEVELS = stringPreferencesKey("eq_levels")
    val BASS = intPreferencesKey("bass")
    val VIRT = intPreferencesKey("virt")
    val ACCENT_ARGB = intPreferencesKey("accent_argb")
    val TEXT_SCALE = floatPreferencesKey("text_scale")
    val VIDEO_PROGRESS = stringPreferencesKey("video_progress")
    val MUSIC_PROGRESS = stringPreferencesKey("music_progress")
    val HIDDEN_HOME = stringSetPreferencesKey("hidden_home")
    val G_SEEK = booleanPreferencesKey("g_seek")
    val G_BRIGHT = booleanPreferencesKey("g_bright")
    val G_VOLUME = booleanPreferencesKey("g_volume")
    val G_ZOOM = booleanPreferencesKey("g_zoom")
    val G_DOUBLE = booleanPreferencesKey("g_double")
    val HIDE_SECS = intPreferencesKey("controls_hide_secs")
    val REMAINING = booleanPreferencesKey("show_remaining")
    val EDGE_DP = intPreferencesKey("edge_buffer_dp")
    val RESTORE_SESSION = booleanPreferencesKey("restore_session")
    val SLEEP_CLOSE = booleanPreferencesKey("sleep_close_app")
    val SAFE_HP_VOLUME = booleanPreferencesKey("safe_headphone_volume")
    val PREFER_ENGLISH = booleanPreferencesKey("prefer_english_audio")
    val SESSION_QUEUE = stringPreferencesKey("session_queue")
    val SESSION_INDEX = intPreferencesKey("session_index")
    val SESSION_POS = longPreferencesKey("session_pos")
    // ---- DSP engine (global settings — deliberately NOT per-headphone profiles)
    val DSP_ENABLED = booleanPreferencesKey("dsp_enabled")
    val DSP_SIGNATURE = stringPreferencesKey("dsp_signature")
    val DSP_ADAPTIVE = booleanPreferencesKey("dsp_adaptive")
    val DSP_CUSTOM_ON = booleanPreferencesKey("dsp_custom_on")
    val DSP_CUSTOM_BANDS = stringPreferencesKey("dsp_custom_bands")
    val DSP_SPATIAL_ON = booleanPreferencesKey("dsp_spatial_on")
    val DSP_SPATIAL_STRENGTH = intPreferencesKey("dsp_spatial_strength")
    // ---- USB DAC / per-transport profiles
    val BIT_PERFECT_USB = booleanPreferencesKey("bit_perfect_usb")
    val AUTO_DEVICE_PROFILE = booleanPreferencesKey("auto_device_profile")
    val USB_DAC_PRESET = stringPreferencesKey("usb_dac_preset_id")
    val BLUETOOTH_PRESET = stringPreferencesKey("bluetooth_preset_id")
    val WIRED_PRESET = stringPreferencesKey("wired_preset_id")
    val SPEAKER_PRESET = stringPreferencesKey("speaker_preset_id")
    val CUSTOM_PROFILES = stringPreferencesKey("custom_sound_profiles_json")
    val DSP_CROSSFEED = floatPreferencesKey("dsp_crossfeed")
    val DSP_ACTIVE_CUSTOM = stringPreferencesKey("dsp_active_custom_profile")
    fun lrcFor(uriHash: Int) = stringPreferencesKey("lrc_$uriHash")
}

private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E =
    this[key]?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            themeMode = p.enum(Keys.THEME, d.themeMode),
            viewMode = p.enum(Keys.VIEW, d.viewMode),
            sortBy = p.enum(Keys.SORT, d.sortBy),
            sortAscending = p[Keys.SORT_ASC] ?: d.sortAscending,
            compactDensity = p[Keys.COMPACT] ?: d.compactDensity,
            accentCyan = p[Keys.ACCENT_CYAN] ?: d.accentCyan,
            seekSensitivity = p[Keys.SEEK_SENS] ?: d.seekSensitivity,
            brightnessSensitivity = p[Keys.BRIGHT_SENS] ?: d.brightnessSensitivity,
            volumeSensitivity = p[Keys.VOL_SENS] ?: d.volumeSensitivity,
            doubleTapSeconds = p[Keys.DOUBLE_TAP] ?: d.doubleTapSeconds,
            seekSnap = p.enum(Keys.SEEK_SNAP, d.seekSnap),
            decoderMode = p.enum(Keys.DECODER, d.decoderMode),
            autoPip = p[Keys.AUTO_PIP] ?: d.autoPip,
            backgroundPlay = p[Keys.BG_PLAY] ?: d.backgroundPlay,
            resumePlayback = p[Keys.RESUME] ?: d.resumePlayback,
            keepScreenOn = p[Keys.KEEP_ON] ?: d.keepScreenOn,
            subtitleSizeSp = p[Keys.SUB_SIZE] ?: d.subtitleSizeSp,
            subtitleColorIdx = p[Keys.SUB_COLOR] ?: d.subtitleColorIdx,
            subtitleBackground = p[Keys.SUB_BG] ?: d.subtitleBackground,
            lastFolderPath = p[Keys.LAST_FOLDER],
            safTrees = p[Keys.SAF_TREES] ?: emptySet(),
            eqEnabled = p[Keys.EQ_ON] ?: d.eqEnabled,
            eqPreset = p[Keys.EQ_PRESET] ?: d.eqPreset,
            eqLevels = p[Keys.EQ_LEVELS]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: d.eqLevels,
            bassBoost = p[Keys.BASS] ?: d.bassBoost,
            virtualizer = p[Keys.VIRT] ?: d.virtualizer,
            accentArgb = p[Keys.ACCENT_ARGB] ?: d.accentArgb,
            textScale = p[Keys.TEXT_SCALE] ?: d.textScale,
            videoProgressStyle = p.enum(Keys.VIDEO_PROGRESS, d.videoProgressStyle),
            musicProgressStyle = p.enum(Keys.MUSIC_PROGRESS, d.musicProgressStyle),
            hiddenHomeSections = p[Keys.HIDDEN_HOME] ?: d.hiddenHomeSections,
            gestureSeek = p[Keys.G_SEEK] ?: d.gestureSeek,
            gestureBrightness = p[Keys.G_BRIGHT] ?: d.gestureBrightness,
            gestureVolume = p[Keys.G_VOLUME] ?: d.gestureVolume,
            gestureZoom = p[Keys.G_ZOOM] ?: d.gestureZoom,
            gestureDoubleTap = p[Keys.G_DOUBLE] ?: d.gestureDoubleTap,
            controlsHideSeconds = p[Keys.HIDE_SECS] ?: d.controlsHideSeconds,
            showRemainingTime = p[Keys.REMAINING] ?: d.showRemainingTime,
            edgeBufferDp = p[Keys.EDGE_DP] ?: d.edgeBufferDp,
            restoreSession = p[Keys.RESTORE_SESSION] ?: d.restoreSession,
            sleepCloseApp = p[Keys.SLEEP_CLOSE] ?: d.sleepCloseApp,
            safeHeadphoneVolume = p[Keys.SAFE_HP_VOLUME] ?: d.safeHeadphoneVolume,
            preferEnglishAudio = p[Keys.PREFER_ENGLISH] ?: d.preferEnglishAudio,
            bitPerfectUsbEnabled = p[Keys.BIT_PERFECT_USB] ?: d.bitPerfectUsbEnabled,
            autoDeviceProfileEnabled = p[Keys.AUTO_DEVICE_PROFILE] ?: d.autoDeviceProfileEnabled,
            usbDacPresetId = p[Keys.USB_DAC_PRESET] ?: d.usbDacPresetId,
            bluetoothPresetId = p[Keys.BLUETOOTH_PRESET] ?: d.bluetoothPresetId,
            wiredPresetId = p[Keys.WIRED_PRESET] ?: d.wiredPresetId,
            speakerPresetId = p[Keys.SPEAKER_PRESET] ?: d.speakerPresetId,
            customProfiles = decodeCustomProfiles(p[Keys.CUSTOM_PROFILES].orEmpty()),
        )
    }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) { store.edit { it[key] = value } }
    suspend fun setEnum(key: Preferences.Key<String>, value: Enum<*>) { store.edit { it[key] = value.name } }
    suspend fun <T> get(key: Preferences.Key<T>): T? = store.data.first()[key]
    /** Resets every look-and-feel / gesture / layout preference (keeps library folders, EQ and history). */
    suspend fun resetCustomization() {
        store.edit {
            it.remove(Keys.THEME); it.remove(Keys.VIEW); it.remove(Keys.COMPACT); it.remove(Keys.ACCENT_CYAN)
            it.remove(Keys.ACCENT_ARGB); it.remove(Keys.TEXT_SCALE); it.remove(Keys.VIDEO_PROGRESS); it.remove(Keys.MUSIC_PROGRESS)
            it.remove(Keys.HIDDEN_HOME); it.remove(Keys.G_SEEK); it.remove(Keys.G_BRIGHT); it.remove(Keys.G_VOLUME)
            it.remove(Keys.G_ZOOM); it.remove(Keys.G_DOUBLE); it.remove(Keys.HIDE_SECS); it.remove(Keys.REMAINING)
            it.remove(Keys.SEEK_SENS); it.remove(Keys.BRIGHT_SENS); it.remove(Keys.VOL_SENS); it.remove(Keys.DOUBLE_TAP)
            it.remove(Keys.SUB_SIZE); it.remove(Keys.SUB_COLOR); it.remove(Keys.SUB_BG); it.remove(Keys.EDGE_DP)
        }
    }

    suspend fun addSafTree(uri: String) { store.edit { it[Keys.SAF_TREES] = (it[Keys.SAF_TREES] ?: emptySet()) + uri } }
    suspend fun removeSafTree(uri: String) { store.edit { it[Keys.SAF_TREES] = (it[Keys.SAF_TREES] ?: emptySet()) - uri } }
    suspend fun saveEq(enabled: Boolean, preset: String, levels: List<Int>, bass: Int, virt: Int) {
        store.edit {
            it[Keys.EQ_ON] = enabled; it[Keys.EQ_PRESET] = preset
            it[Keys.EQ_LEVELS] = levels.joinToString(","); it[Keys.BASS] = bass; it[Keys.VIRT] = virt
        }
    }
}
