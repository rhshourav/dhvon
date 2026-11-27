// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the audio path really looks like, as three separate facts (never merged, never guessed):
 *  1. **Advertised device capabilities** — what the USB device's own descriptors report ([advertisedCaps]);
 *  2. **Source format** — what the current track is ([sourceFormatLabel]);
 *  3. **Active output path** — what Android confirmed ([outputPathLabel], [bitPerfectConfirmedActive]).
 */
data class AudioPathDiagnostics(
    val isUsbConnected: Boolean = false,
    val deviceName: String = "Phone Speaker / Internal",
    val advertisedCaps: String = "Standard Android Audio",
    val sourceFormatLabel: String = "Idle",
    val outputPathLabel: String = "Standard Android Mixer",
    val bitPerfectSupportedByHal: Boolean = false,
    val bitPerfectConfirmedActive: Boolean = false,
    /** Which kind of output media audio is really going to. Bit-Perfect only ever exists on [AudioRouteType.USB_DAC]. */
    val routeType: AudioRouteType = AudioRouteType.SPEAKER,
)

enum class AudioRouteType { SPEAKER, WIRED_ANALOG, USB_DAC, BLUETOOTH_TWS }

fun isBluetoothOutputType(type: Int): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_HEARING_AID ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))

fun isUsbOutputType(type: Int): Boolean =
    type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET

fun routeTypeOf(device: AudioDeviceInfo?): AudioRouteType = when {
    device == null -> AudioRouteType.SPEAKER
    isUsbOutputType(device.type) -> AudioRouteType.USB_DAC
    isBluetoothOutputType(device.type) -> AudioRouteType.BLUETOOTH_TWS
    device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        device.type == AudioDeviceInfo.TYPE_LINE_ANALOG -> AudioRouteType.WIRED_ANALOG
    else -> AudioRouteType.SPEAKER
}

private val ROUTE_ATTRIBUTES = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

/**
 * The output media audio is routed to. Android 13+: Android's own answer via the public `getAudioDevicesForAttributes`
 * (`getDevicesForAttributes` is hidden API — no reflection here). Before that, or when it has no answer: USB, then Bluetooth, then wired.
 * Everything is read from [AudioDeviceInfo]: no permission is needed, `BLUETOOTH_CONNECT` is never requested.
 */
fun resolveActiveRoute(audioManager: AudioManager): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { audioManager.getAudioDevicesForAttributes(ROUTE_ATTRIBUTES).firstOrNull() }.getOrNull()?.let { return it }
    }
    val outputs = runCatching { audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink } }.getOrDefault(emptyList())
    return outputs.firstOrNull { isUsbOutputType(it.type) }
        ?: outputs.firstOrNull { isBluetoothOutputType(it.type) }
        ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
}

private fun routeName(device: AudioDeviceInfo?, type: AudioRouteType): String {
    val name = device?.productName?.toString()?.takeIf { it.isNotBlank() }
    return when (type) {
        AudioRouteType.BLUETOOTH_TWS -> name ?: "Bluetooth TWS"
        AudioRouteType.USB_DAC -> name ?: "USB Audio DAC"
        AudioRouteType.WIRED_ANALOG -> "Wired Headphones"
        AudioRouteType.SPEAKER -> when (device?.type) {
            null, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone Speaker / Internal"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone Earpiece"
            else -> name ?: "System Audio Route"
        }
    }
}

/** The transport badge. Which codec (SBC / AAC / aptX / LDAC …) is in use cannot be read without extra permissions, so none is claimed. */
private fun bluetoothBadge(device: AudioDeviceInfo): String = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET || device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) -> "Bluetooth LE Audio"
    device.type == AudioDeviceInfo.TYPE_HEARING_AID -> "Bluetooth Hearing Aid"
    else -> "Bluetooth A2DP Wireless"
}

/** DHVON's sink always hands Android 16-bit PCM (float output stays off so the DSP tap is never bypassed). */
private const val PATH_MIXER = "Float32 DSP → 16-bit PCM → Android Mixer"
private const val PATH_BLUETOOTH = "Float32 DSP → Bluetooth Encoder"
private const val SINK_ENCODING = AudioFormat.ENCODING_PCM_16BIT

private fun khz(rate: Int): String = (rate / 1000.0).toString().removeSuffix(".0")

private fun encodingLabel(encoding: Int): String = when (encoding) {
    AudioFormat.ENCODING_PCM_16BIT -> "16-bit PCM"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit PCM"
    AudioFormat.ENCODING_PCM_32BIT -> "32-bit PCM"
    AudioFormat.ENCODING_PCM_FLOAT -> "Float32 PCM"
    else -> "PCM"
}

/**
 * USB DAC awareness on top of Android's own audio stack — [AudioManager] + the device callback owned by
 * [AudioEffectsController]. It never touches `UsbManager` / libusb and never claims the interface, so the inline microphone,
 * calls and system sounds of a USB-C headset keep working normally.
 *
 * The route is classified as speaker / wired / USB DAC / Bluetooth TWS ([AudioRouteType]); only a USB route can be bit-perfect, and on
 * every other route any earlier request is withdrawn and the state is reset.
 *
 * Bit-Perfect USB (Android 14+, `AudioMixerAttributes`): DHVON only *asks* for `MIXER_BEHAVIOR_BIT_PERFECT`. The state is reported
 * as active only after [AudioManager.OnPreferredMixerAttributesChangedListener] says Android actually switched.
 * The request uses the format DHVON's sink really produces (16-bit PCM at the track's sample rate) and is skipped — and
 * reported as such — when the HAL offers no matching bit-perfect format.
 */
@Singleton
class UsbDacManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val executor = Executors.newSingleThreadExecutor()
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    @Volatile private var activeUsbDevice: AudioDeviceInfo? = null
    @Volatile private var currentSampleRate: Int = 0
    @Volatile private var bitPerfectRequested: Boolean = false
    private var appliedMixerKey: String? = null
    private var policySyncedDeviceId: Int? = null
    private var mixerListener: Any? = null          // an AudioManager.OnPreferredMixerAttributesChangedListener (API 34+)

    private val _diagnostics = MutableStateFlow(AudioPathDiagnostics())
    val diagnostics: StateFlow<AudioPathDiagnostics> = _diagnostics.asStateFlow()

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) registerMixerListener()
    }

    /** The only place "bit-perfect active" is ever set to true. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun registerMixerListener() {
        val listener = AudioManager.OnPreferredMixerAttributesChangedListener { _, device, mixerAttrs ->
            if (device.id == activeUsbDevice?.id) {
                val bitPerfect = mixerAttrs?.takeIf { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
                _diagnostics.update {
                    it.copy(
                        bitPerfectConfirmedActive = bitPerfect != null,
                        outputPathLabel = if (bitPerfect != null) {
                            "Bit-Perfect USB (${khz(bitPerfect.format.sampleRate)} kHz, ${encodingLabel(bitPerfect.format.encoding)}, DSP bypassed)"
                        } else PATH_MIXER,
                    )
                }
            }
        }
        mixerListener = listener
        runCatching { audioManager.addOnPreferredMixerAttributesChangedListener(executor, listener) }
    }

    /**
     * Called whenever the media route may have changed. [device] is the output Android routes media to (see [resolveActiveRoute]);
     * null means nothing specific is known. A previous bit-perfect request is withdrawn as soon as the route is no longer that USB DAC,
     * so Bluetooth, wired and speaker routes can never be reported — or treated — as bit-perfect.
     */
    fun onOutputRouteChanged(device: AudioDeviceInfo?) {
        val type = routeTypeOf(device)
        val usbDevice = device?.takeIf { type == AudioRouteType.USB_DAC }
        val prev = activeUsbDevice
        if (prev != null && prev.id != usbDevice?.id) clearBitPerfect(prev)
        if (prev?.id != usbDevice?.id) { appliedMixerKey = null; policySyncedDeviceId = null }
        activeUsbDevice = usbDevice

        if (usbDevice != null) {
            val halSupportsBitPerfect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && queryBitPerfectSupport(usbDevice)
            _diagnostics.update {
                it.copy(
                    routeType = type,
                    isUsbConnected = true,
                    deviceName = routeName(usbDevice, type),
                    advertisedCaps = describeCapabilities(usbDevice),
                    bitPerfectSupportedByHal = halSupportsBitPerfect,
                    bitPerfectConfirmedActive = false,
                    outputPathLabel = PATH_MIXER,
                )
            }
            applyMixerPolicy()
            return
        }

        val bluetooth = type == AudioRouteType.BLUETOOTH_TWS
        _diagnostics.update {
            it.copy(
                routeType = type,
                isUsbConnected = false,
                deviceName = routeName(device, type),
                advertisedCaps = if (bluetooth && device != null) bluetoothBadge(device) else "System audio route",
                bitPerfectSupportedByHal = false,
                bitPerfectConfirmedActive = false,
                outputPathLabel = if (bluetooth) PATH_BLUETOOTH else PATH_MIXER,
            )
        }
    }

    /** Sample rate 0 / bit depth 0 mean "the container didn't say" — the label then simply leaves that part out. */
    fun onTrackFormatChanged(codec: String, sampleRate: Int, bitDepth: Int) {
        currentSampleRate = sampleRate
        val detail = listOfNotNull(
            if (bitDepth > 0) "$bitDepth-bit" else null,
            if (sampleRate > 0) "${khz(sampleRate)} kHz" else null,
        ).joinToString(" / ")
        val label = when {
            codec.isBlank() -> "Idle"
            detail.isEmpty() -> codec
            else -> "$codec • $detail"
        }
        _diagnostics.update { it.copy(sourceFormatLabel = label) }
        applyMixerPolicy()
    }

    fun setBitPerfectRequested(enabled: Boolean) {
        bitPerfectRequested = enabled
        applyMixerPolicy()
    }

    /** Only what the device itself reports; anything it doesn't report is said so instead of being filled in. */
    private fun describeCapabilities(device: AudioDeviceInfo): String {
        val rates = device.sampleRates.sorted().joinToString("/") { khz(it) }
        val enc = device.encodings.toSet()
        val depths = buildList {
            if (AudioFormat.ENCODING_PCM_16BIT in enc) add(16)
            if (AudioFormat.ENCODING_PCM_24BIT_PACKED in enc) add(24)
            if (AudioFormat.ENCODING_PCM_32BIT in enc) add(32)
        }
        val float = AudioFormat.ENCODING_PCM_FLOAT in enc
        val bits = when {
            depths.isNotEmpty() -> depths.joinToString("/") + "-bit" + if (float) " + float" else ""
            float -> "float"
            else -> "formats not reported"
        }
        return "Advertised: $bits • " + if (rates.isEmpty()) "rates not reported" else "$rates kHz"
    }

    private fun setPath(label: String) = _diagnostics.update { it.copy(outputPathLabel = label, bitPerfectConfirmedActive = false) }

    private fun applyMixerPolicy() {
        val device = activeUsbDevice ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setPath(PATH_MIXER)
            return
        }
        if (bitPerfectRequested) {
            val rate = currentSampleRate
            if (rate > 0) requestBitPerfect(device, rate)
        } else {
            // Once per device: also wipes a preference a previous DHVON process may have left behind.
            if (appliedMixerKey != null || policySyncedDeviceId != device.id) {
                clearBitPerfect(device)
                policySyncedDeviceId = device.id
            }
            setPath(PATH_MIXER)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun queryBitPerfectSupport(device: AudioDeviceInfo): Boolean = runCatching {
        audioManager.getSupportedMixerAttributes(device).any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
    }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun requestBitPerfect(device: AudioDeviceInfo, rate: Int) {
        val supported = runCatching { audioManager.getSupportedMixerAttributes(device) }.getOrDefault(emptyList())
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        if (supported.isEmpty()) {
            clearBitPerfect(device)
            _diagnostics.update { it.copy(bitPerfectSupportedByHal = false) }
            setPath("$PATH_MIXER (HAL unsupported)")
            return
        }
        // The track DHVON plays is a 16-bit PCM AudioTrack at the source's rate; a bit-perfect format that differs from it would
        // not match the stream, so no match means no request (instead of picking some other format).
        val match = supported.firstOrNull { it.format.sampleRate == rate && it.format.encoding == SINK_ENCODING && it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO }
            ?: supported.firstOrNull { it.format.sampleRate == rate && it.format.encoding == SINK_ENCODING }
        if (match == null) {
            clearBitPerfect(device)
            setPath("$PATH_MIXER (no bit-perfect format for ${khz(rate)} kHz / 16-bit)")
            return
        }
        val key = "${device.id}|${match.format.sampleRate}|${match.format.encoding}|${match.format.channelMask}"
        if (key == appliedMixerKey) return
        if (appliedMixerKey == null) clearBitPerfect(device)    // start clean, so Android answers our request with a fresh callback
        val accepted = runCatching { audioManager.setPreferredMixerAttributes(mediaAttributes, device, match) }.getOrDefault(false)
        appliedMixerKey = if (accepted) key else null
        if (!accepted) setPath("$PATH_MIXER (bit-perfect request rejected)")
        // On success nothing is claimed here: the listener flips bitPerfectConfirmedActive when Android confirms.
    }

    private fun clearBitPerfect(device: AudioDeviceInfo) {
        appliedMixerKey = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { audioManager.clearPreferredMixerAttributes(mediaAttributes, device) }
        }
    }
}
