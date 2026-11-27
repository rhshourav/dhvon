// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val PCM16_TO_FLOAT = 1f / 32_768f
/** Crossfeed low-pass corner: the head shadows mostly the highs, so only the low part of the opposite channel is fed across. */
private const val CROSSFEED_CUTOFF_HZ = 700.0

/** Band energy of the music being played, in dB relative to full scale, refreshed every 400 ms. */
data class SpectralMetrics(
    val lowDb: Float = -30f,
    val midDb: Float = -30f,
    val highDb: Float = -30f,
    val isActiveSignal: Boolean = false,
)

/**
 * DHVON's in-pipeline audio tap. Passes 16-bit and float PCM through bit-for-bit and, on the way, does three jobs:
 *  1. **Audio delay** — a ring-buffer delay line (0–3000 ms) so the soundtrack can be pushed later to fix A/V sync.
 *  2. **Level meter** — [level] is the peak of the buffer being played; it drives the live waveform pulse.
 *  3. **Spectral analyzer** — RMS energy at Low (65 Hz) / Mid (1.2 kHz) / High (8.5 kHz) per 400 ms window, published as
 *     [metrics]; [AudioEffectsController] turns it into the live Post-EQ compensation.
 * Any other PCM encoding returns NOT_SET, which makes ExoPlayer skip the processor instead of failing playback.
 * 4. **Crossfeed** ([setCrossfeed]) — stereo headphone crossfeed done in Float32; 16-bit PCM is converted with `s / 32768f` and written back
 *    with TPDF dither, PCM_FLOAT stays float. With no crossfeed the samples are never re-quantised (bit-exact pass-through).
 * 5. **Bit-perfect bypass** ([bypassForBitPerfect]) — once Android has confirmed bit-perfect USB output the buffers go through
 *    completely untouched: no crossfeed, no delay, no level change (the read-only meters keep running).
 * DhvonRenderersFactory keeps DefaultAudioSink's float output OFF: with it on, Media3 skips this processor altogether.
 * One singleton is shared by every ExoPlayer instance DHVON builds (see [PlayerHolder]).
 */
@UnstableApi
@Singleton
class DhvonAudioProcessor @Inject constructor() : BaseAudioProcessor() {

    @Volatile var delayMs: Int = 0
        set(value) { field = value.coerceIn(0, 3000); resizePending = true }

    /** Peak amplitude of the most recent buffer, 0f..1f. */
    @Volatile var level: Float = 0f
        private set

    /**
     * True only while Android has CONFIRMED bit-perfect USB output (see [UsbDacManager]). The stream is then passed through
     * byte-for-byte so it reaches the HAL unaltered. Switching it re-creates the delay line (silence, like after a seek).
     */
    @Volatile var bypassForBitPerfect: Boolean = false
        set(value) { if (field != value) { field = value; resizePending = true } }

    @Volatile private var crossfeedMix = 0f
    @Volatile private var crossfeedDelayUs = 0
    @Volatile private var cfDirty = true

    /** [mix] 0..0.5 share of the opposite ear (0 = off); [delayUs] interaural delay in microseconds. Stereo 16-bit / float only. */
    fun setCrossfeed(mix: Float, delayUs: Int) {
        val m = mix.coerceIn(0f, 0.5f)
        val d = delayUs.coerceIn(0, 1_000)
        if (m == crossfeedMix && d == crossfeedDelayUs) return
        crossfeedMix = m; crossfeedDelayUs = d
        cfDirty = true
    }

    private val _metrics = MutableStateFlow(SpectralMetrics())
    val metrics: StateFlow<SpectralMetrics> = _metrics.asStateFlow()

    @Volatile private var resizePending = true
    private var ring = ByteArray(0)
    private var ringPos = 0

    private val lowFilter = BiquadBandpass()
    private val midFilter = BiquadBandpass()
    private val highFilter = BiquadBandpass()
    private var sumLowSq = 0.0
    private var sumMidSq = 0.0
    private var sumHighSq = 0.0
    private var sampleCounter = 0

    // crossfeed state (playback thread only)
    private var cfActive = false
    private var cfRingL = FloatArray(0)
    private var cfRingR = FloatArray(0)
    private var cfPos = 0
    private var cfLpL = 0f
    private var cfLpR = 0f
    private var cfK = 0f
    private var cfMix = 0f
    private var cfGain = 1f
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var rng = 0x2545F491
    private var samplesPerWindow = 19_200

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        resizePending = true
        cfDirty = true
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        val sr = inputAudioFormat.sampleRate.toDouble().coerceAtLeast(8_000.0)
        samplesPerWindow = (sr * 0.4).toInt()
        lowFilter.configure(sr, freq = 65.0, q = 1.2)
        midFilter.configure(sr, freq = 1_200.0, q = 1.0)
        highFilter.configure(sr, freq = 8_500.0, q = 1.2)
        resetAccumulators()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        analyse(inputBuffer)                       // read-only pass over a duplicate; the buffer is untouched
        if (bypassForBitPerfect) {                 // bit-perfect: byte-for-byte to the HAL — no crossfeed, no delay, no level change
            val passThrough = replaceOutputBuffer(size)
            passThrough.put(inputBuffer)
            passThrough.flip()
            return
        }
        if (resizePending) resize()
        if (cfDirty) rebuildCrossfeed()
        val src = if (cfActive) crossfeed(inputBuffer) else inputBuffer   // crossfeed consumes the input into a Float32-processed copy
        val out = replaceOutputBuffer(size)
        if (ring.isEmpty()) {
            out.put(src)
        } else {
            val r = ring
            var pos = ringPos
            while (src.hasRemaining()) {
                val b = src.get()
                out.put(r[pos])
                r[pos] = b
                if (++pos == r.size) pos = 0
            }
            ringPos = pos
        }
        out.flip()
    }

    override fun onQueueEndOfStream() {
        if (bypassForBitPerfect || ring.isEmpty()) return
        // Drain the tail still sitting in the delay line so the last <delay> ms of the track isn't cut off.
        val out = replaceOutputBuffer(ring.size)
        out.put(ring, ringPos, ring.size - ringPos)
        out.put(ring, 0, ringPos)
        out.flip()
        ring.fill(0)
        ringPos = 0
    }

    override fun onFlush() {                       // seek → fresh zero-filled delay line, fresh filter memory
        resizePending = true
        cfDirty = true
        lowFilter.reset(); midFilter.reset(); highFilter.reset()
        resetAccumulators()
    }

    override fun onReset() {
        ring = ByteArray(0); ringPos = 0; level = 0f; resizePending = true; cfDirty = true
        lowFilter.reset(); midFilter.reset(); highFilter.reset()
        resetAccumulators()
        _metrics.value = SpectralMetrics()
    }

    private fun resize() {
        resizePending = false
        val fmt = this.inputAudioFormat
        val frames = (delayMs.toLong() * fmt.sampleRate / 1000L).toInt()
        ring = ByteArray(frames * fmt.bytesPerFrame)
        ringPos = 0
    }

    // ------------------------------------------------------------------ crossfeed (Float32 math)

    private fun rebuildCrossfeed() {
        cfDirty = false
        val fmt = inputAudioFormat
        val mix = crossfeedMix
        val delayUs = crossfeedDelayUs
        cfActive = mix > 0.001f && fmt.channelCount == 2 &&
            (fmt.encoding == C.ENCODING_PCM_16BIT || fmt.encoding == C.ENCODING_PCM_FLOAT)
        cfPos = 0; cfLpL = 0f; cfLpR = 0f
        if (!cfActive) { cfRingL = FloatArray(0); cfRingR = FloatArray(0); return }
        val sr = fmt.sampleRate.coerceAtLeast(8_000)
        val n = ((delayUs.toLong() * sr + 500_000L) / 1_000_000L).toInt()       // 240 µs ≈ 12 samples at 48 kHz
        cfRingL = FloatArray(n); cfRingR = FloatArray(n)
        cfMix = mix
        cfGain = 1f / (1f + mix)                                                // convex mix: never louder than the input
        cfK = (1.0 - exp(-2.0 * PI * CROSSFEED_CUTOFF_HZ / sr)).toFloat()
    }

    /** Consumes [input] and returns a same-sized crossfed copy (valid until the next call). out = (self + mix · lowpass(delayed other)) / (1 + mix). */
    private fun crossfeed(input: ByteBuffer): ByteBuffer {
        val size = input.remaining()
        if (scratch.capacity() < size) scratch = ByteBuffer.allocateDirect(size)
        scratch.clear()
        scratch.order(input.order())
        val ringL = cfRingL
        val ringR = cfRingR
        val n = ringL.size
        var pos = cfPos
        var lpL = cfLpL
        var lpR = cfLpR
        val k = cfK
        val mix = cfMix
        val gain = cfGain
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            while (input.remaining() >= 8) {
                val l = input.getFloat()
                val r = input.getFloat()
                var dl = l
                var dr = r
                if (n > 0) { dl = ringL[pos]; dr = ringR[pos]; ringL[pos] = l; ringR[pos] = r; if (++pos == n) pos = 0 }
                lpL += k * (dl - lpL)
                lpR += k * (dr - lpR)
                scratch.putFloat((l + mix * lpR) * gain)          // Float32 stays Float32 — no re-quantisation, no dither
                scratch.putFloat((r + mix * lpL) * gain)
            }
        } else {
            while (input.remaining() >= 4) {
                val l = input.getShort() * PCM16_TO_FLOAT
                val r = input.getShort() * PCM16_TO_FLOAT
                var dl = l
                var dr = r
                if (n > 0) { dl = ringL[pos]; dr = ringR[pos]; ringL[pos] = l; ringR[pos] = r; if (++pos == n) pos = 0 }
                lpL += k * (dl - lpL)
                lpR += k * (dr - lpR)
                scratch.putShort(toPcm16((l + mix * lpR) * gain))
                scratch.putShort(toPcm16((r + mix * lpL) * gain))
            }
        }
        while (input.hasRemaining()) scratch.put(input.get())     // a trailing partial frame (never expected) passes through
        cfPos = pos; cfLpL = lpL; cfLpR = lpR
        scratch.flip()
        return scratch
    }

    /** Float32 → PCM_16BIT with TPDF dither: (u1 − u2) / 32768 added before rounding, so the quantisation error is noise, not distortion. */
    private fun toPcm16(x: Float): Short {
        val dither = (nextUnit() - nextUnit()) / 32_768f
        return ((x + dither) * 32_768f).roundToInt().coerceIn(-32_768, 32_767).toShort()
    }

    /** xorshift32 → uniform [0, 1); allocation-free and cheap enough for the audio thread. */
    private fun nextUnit(): Float {
        var x = rng
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rng = x
        return (x ushr 8) * (1f / 16_777_216f)
    }

    // ------------------------------------------------------------------ analysis

    private fun analyse(input: ByteBuffer) {
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val view = input.duplicate().order(input.order())      // duplicate() resets byte order — restore it
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            val fb = view.asFloatBuffer()
            var peak = 0f
            while (fb.remaining() >= channels) {
                var mono = 0.0
                for (c in 0 until channels) {
                    val s = fb.get().coerceIn(-1f, 1f)
                    val a = if (s < 0f) -s else s
                    if (a > peak) peak = a
                    mono += s
                }
                feed(mono / channels)
            }
            level = peak
        } else {
            val sb = view.asShortBuffer()
            var peak = 0
            while (sb.remaining() >= channels) {
                var mono = 0.0
                for (c in 0 until channels) {
                    val s = sb.get().toInt()
                    val a = if (s < 0) -s else s
                    if (a > peak) peak = a
                    mono += s
                }
                feed(mono / (channels * 32_768.0))
            }
            level = min(1f, peak / 32_768f)
        }
    }

    private fun feed(mono: Double) {
        val l = lowFilter.process(mono)
        val m = midFilter.process(mono)
        val h = highFilter.process(mono)
        sumLowSq += l * l
        sumMidSq += m * m
        sumHighSq += h * h
        if (++sampleCounter >= samplesPerWindow) {
            publishWindowMetrics()
            resetAccumulators()
        }
    }

    private fun publishWindowMetrics() {
        val n = sampleCounter.coerceAtLeast(1).toDouble()
        fun toDb(rms: Double): Float = (20.0 * log10(rms.coerceAtLeast(1e-6))).toFloat()
        val lowDb = toDb(sqrt(sumLowSq / n))
        val midDb = toDb(sqrt(sumMidSq / n))
        val highDb = toDb(sqrt(sumHighSq / n))
        _metrics.value = SpectralMetrics(lowDb, midDb, highDb, isActiveSignal = midDb > -55f || lowDb > -55f)
    }

    private fun resetAccumulators() {
        sumLowSq = 0.0; sumMidSq = 0.0; sumHighSq = 0.0
        sampleCounter = 0
    }

    /** RBJ constant-0-dB-peak band-pass biquad. */
    private class BiquadBandpass {
        private var b0 = 0.0; private var b2 = 0.0
        private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0

        fun configure(sampleRate: Double, freq: Double, q: Double) {
            val f = min(freq, sampleRate * 0.45)        // stay below Nyquist on low sample rates
            val w0 = 2.0 * PI * f / sampleRate
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = alpha / a0
            b2 = -alpha / a0
            a1 = (-2.0 * cos(w0)) / a0
            a2 = (1.0 - alpha) / a0
            reset()
        }

        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

        fun process(x0: Double): Double {
            val y0 = b0 * x0 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
            return y0
        }
    }
}
