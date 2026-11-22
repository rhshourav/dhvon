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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
    private var samplesPerWindow = 19_200

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        resizePending = true
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
        if (resizePending) resize()
        val out = replaceOutputBuffer(size)
        if (ring.isEmpty()) {
            out.put(inputBuffer)
        } else {
            val r = ring
            var pos = ringPos
            while (inputBuffer.hasRemaining()) {
                val b = inputBuffer.get()
                out.put(r[pos])
                r[pos] = b
                if (++pos == r.size) pos = 0
            }
            ringPos = pos
        }
        out.flip()
    }

    override fun onQueueEndOfStream() {
        if (ring.isEmpty()) return
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
        lowFilter.reset(); midFilter.reset(); highFilter.reset()
        resetAccumulators()
    }

    override fun onReset() {
        ring = ByteArray(0); ringPos = 0; level = 0f; resizePending = true
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
