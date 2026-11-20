// DHVON — Every Sound. Every Frame.
// Created by rhshourav · https://github.com/rhshourav/DHVON · GPL-3.0-or-later
package com.rhshourav.dhvon.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Loads embedded album art (system thumbnail first, then embedded picture) with an in-memory LRU cache. */
@Singleton
class ArtworkLoader @Inject constructor(@ApplicationContext private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val misses = HashSet<String>()

    suspend fun audioArt(uri: String, sizePx: Int = 512): Bitmap? {
        cache.get(uri)?.let { return it }
        if (uri in misses) return null
        val bmp = withContext(Dispatchers.IO) {
            val u = uri.toUri()
            var b: Bitmap? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && u.authority == "media") {
                b = runCatching { context.contentResolver.loadThumbnail(u, Size(sizePx, sizePx), null) }.getOrNull()
            }
            if (b == null) {
                val r = MediaMetadataRetriever()
                b = try {
                    r.setDataSource(context, u)
                    r.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = 2 }) }
                } catch (_: Exception) { null } finally { runCatching { r.release() } }
            }
            b
        }
        if (bmp != null) cache.put(uri, bmp) else misses += uri
        return bmp
    }
}

/**
 * Real waveform peaks: decodes the audio track with MediaCodec and keeps the peak amplitude of each of N slices.
 * Falls back to a smooth deterministic pseudo-waveform when the platform cannot decode the format.
 */
@Singleton
class WaveformExtractor @Inject constructor(@ApplicationContext private val context: Context) {
    private val cache = LruCache<String, FloatArray>(48)

    suspend fun peaks(uri: String, bars: Int = 96): FloatArray {
        val key = "$uri#$bars"
        cache.get(key)?.let { return it }
        val result = withContext(Dispatchers.Default) { runCatching { decode(uri, bars) }.getOrNull() } ?: fallback(uri, bars)
        cache.put(key, result)
        return result
    }

    private fun decode(uri: String, bars: Int): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri.toUri(), null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else return null
            if (durationUs <= 0 || durationUs > 20 * 60 * 1_000_000L) return null // keep decode time bounded
            val channels = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val peaks = FloatArray(bars)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    if (info.size > 0) {
                        val out = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val slice = ((info.presentationTimeUs.toDouble() / durationUs) * bars).toInt().coerceIn(0, bars - 1)
                        var p = 0
                        var idx = 0
                        while (out.hasRemaining()) {
                            val s = abs(out.get().toInt())
                            if (idx % max(1, channels) == 0 && s > p) p = s
                            idx++
                        }
                        peaks[slice] = max(peaks[slice], p / 32768f)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            val top = peaks.max()
            if (top <= 0f) return null
            // Normalise + gentle compression so quiet passages stay visible.
            return FloatArray(bars) { Math.pow((peaks[it] / top).toDouble(), 0.7).toFloat().coerceIn(0.06f, 1f) }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { ex.release() }
        }
    }

    private fun fallback(uri: String, bars: Int): FloatArray {
        val rnd = Random(uri.hashCode())
        val p1 = rnd.nextFloat() * 6f; val p2 = rnd.nextFloat() * 6f
        return FloatArray(bars) { i ->
            val x = i / bars.toFloat()
            (0.45f + 0.25f * sin(x * 18f + p1) + 0.18f * sin(x * 41f + p2) + rnd.nextFloat() * 0.12f).coerceIn(0.08f, 1f)
        }
    }
}
