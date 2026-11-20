// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * DHVON's in-pipeline audio tap. It does two jobs on 16-bit PCM:
 *  1. **Audio delay** — a ring-buffer delay line (0–3000 ms) so the soundtrack can be pushed later to fix A/V sync.
 *     (A delay can only move audio *later*; to move it earlier, use the subtitle/video side instead.)
 *  2. **Level meter** — publishes the peak level of the buffer being played, which drives the live waveform pulse.
 * Any other PCM encoding returns NOT_SET, which makes ExoPlayer skip the processor instead of failing.
 */
class DhvonAudioProcessor : BaseAudioProcessor() {

    @Volatile var delayMs: Int = 0
        set(value) { field = value.coerceIn(0, 3000); resizePending = true }

    /** Peak amplitude of the most recent buffer, 0f..1f. */
    @Volatile var level: Float = 0f
        private set

    @Volatile private var resizePending = true
    private var ring = ByteArray(0)
    private var ringPos = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        resizePending = true
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        measure(inputBuffer)
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

    override fun onFlush() { resizePending = true }   // seek → fresh zero-filled delay line

    override fun onReset() { ring = ByteArray(0); ringPos = 0; level = 0f; resizePending = true }

    private fun resize() {
        resizePending = false
        val fmt = this.inputAudioFormat
        val frames = (delayMs.toLong() * fmt.sampleRate / 1000L).toInt()
        ring = ByteArray(frames * fmt.bytesPerFrame)
        ringPos = 0
    }

    private fun measure(buf: ByteBuffer) {
        var peak = 0
        var i = buf.position()
        val end = buf.limit() - 1
        while (i < end) {
            val s = ((buf.get(i).toInt() and 0xFF) or (buf.get(i + 1).toInt() shl 8)).toShort().toInt()
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            i += 16
        }
        level = peak / 32768f
    }
}
