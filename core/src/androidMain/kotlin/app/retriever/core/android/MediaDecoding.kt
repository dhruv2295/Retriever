package app.retriever.core.android

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.scale
import app.retriever.core.AUDIO_SAMPLE_RATE
import app.retriever.core.Moment
import app.retriever.core.headOrder
import app.retriever.core.moments
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** Messages carry the failure's class, never a URI. */
typealias MediaLog = (String, Throwable?) -> Unit

private val NO_LOG: MediaLog = { _, _ -> }

object MediaDecoding {
    private const val TIMEOUT_US = 10_000L
    private const val MAX_POST_EOS_OUTPUT_TIMEOUTS = 100

    fun decodeAudio16kMono(context: Context, uri: Uri, maxSeconds: Int): ShortArray =
        audioAt(context, uri, 0, maxSeconds * 1000L, EmptyCoroutineContext, NO_LOG)

    /** Mono 16 kHz PCM for [fromMs, toMs); empty when the file has no decodable audio. Stops within one codec step of [ctx] being cancelled. */
    fun audioAt(context: Context, uri: Uri, fromMs: Long, toMs: Long, ctx: CoroutineContext, log: MediaLog): ShortArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return ShortArray(0)
            extractor.selectTrack(track)
            val fromUs = fromMs * 1000
            val toUs = toMs * 1000
            if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inFormat = extractor.getTrackFormat(track)
            // Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/VideoAudioExtractor.kt (Apache-2.0). Modified for Retriever: createOrNull's early exits and pumpOnce's post-EOS guard folded into this loop; seeks and trims by presentation time.
            var channels = inFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 0)
            var rate = inFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 0)
            if (channels <= 0 || rate <= 0) return ShortArray(0)
            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            var float = false
            var mono = ShortArray(rate * 10)
            var count = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var postEosTimeouts = 0

            while (!outputDone) {
                ctx.ensureActive()
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val n = if (extractor.sampleTime >= toUs) {
                            -1
                        } else {
                            dec.getInputBuffer(i)?.let { extractor.readSampleData(it, 0) } ?: -1
                        }
                        if (n < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    // A decoder that never emits output EOS would otherwise spin here forever.
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone && ++postEosTimeouts >= MAX_POST_EOS_OUTPUT_TIMEOUTS) {
                        log("audio decoder gave no EOS; stopping", null)
                        outputDone = true
                    }

                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        postEosTimeouts = 0
                        val f = dec.outputFormat
                        channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).takeIf { it > 0 } ?: channels
                        rate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, rate).takeIf { it > 0 } ?: rate
                        float = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                    }

                    o >= 0 -> {
                        postEosTimeouts = 0
                        val buf = dec.getOutputBuffer(o)!!
                        buf.position(info.offset).limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        val frames = info.size / ((if (float) 4 else 2) * channels)
                        val shorts = if (float) null else buf.asShortBuffer()
                        val floats = if (float) buf.asFloatBuffer() else null
                        // The seek lands on an earlier sync sample; keep only frames inside the window.
                        val pts = info.presentationTimeUs
                        val first = ((fromUs - pts) * rate / 1_000_000).coerceIn(0, frames.toLong()).toInt()
                        val last = ((toUs - pts) * rate / 1_000_000).coerceIn(0, frames.toLong()).toInt()
                        for (fr in first until last) {
                            var sum = 0
                            var fsum = 0f
                            for (c in 0 until channels) {
                                if (floats != null) {
                                    fsum += floats.get(fr * channels + c)
                                } else {
                                    sum += shorts!!.get(fr * channels + c)
                                }
                            }
                            val v = if (floats != null) {
                                (fsum / channels * 32767f).coerceIn(-32768f, 32767f).toInt()
                            } else {
                                sum / channels
                            }
                            if (count == mono.size) mono = mono.copyOf(count * 2)
                            mono[count++] = v.toShort()
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || last < frames) outputDone = true
                    }
                }
            }
            return resample(mono, count, rate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("audio decode failed", e)
            return ShortArray(0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.intOr(key: String, default: Int) = if (containsKey(key)) getInteger(key) else default

    private fun resample(src: ShortArray, n: Int, rate: Int): ShortArray {
        if (n == 0) return ShortArray(0)
        if (rate == AUDIO_SAMPLE_RATE) return src.copyOf(n)
        val outN = (n.toLong() * AUDIO_SAMPLE_RATE / rate).toInt()
        val step = rate.toDouble() / AUDIO_SAMPLE_RATE
        return ShortArray(outN) { i ->
            val pos = i * step
            val a = pos.toInt().coerceAtMost(n - 1)
            val b = (a + 1).coerceAtMost(n - 1)
            val t = pos - a
            (src[a] * (1 - t) + src[b] * t).toInt().toShort()
        }
    }

    /**
     * One bright [maxSide] px JPEG per moment; a moment whose frame and ±1 s neighbours are all black is dropped. With
     * [head], one moment only ([headOrder]), and the moments not tried come back without a frame, to read later.
     */
    fun momentFrames(
        context: Context,
        uri: Uri,
        everySec: Int,
        ctx: CoroutineContext,
        log: MediaLog,
        maxSide: Int = 512,
        head: Boolean = false,
    ): List<Pair<Moment, ByteArray?>> {
        if (everySec <= 0 || definitelyUnsupported(context, uri, log)) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val all = moments(durationMs, everySec)
            fun frame(m: Moment): ByteArray? {
                ctx.ensureActive()
                return listOf(m.frameMs, m.frameMs - 1000, m.frameMs + 1000)
                    .filter { it >= m.startMs && it < m.endMs }
                    .firstNotNullOfOrNull { brightJpeg(retriever, it, maxSide) }
            }
            if (!head) return all.mapNotNull { m -> frame(m)?.let { m to it } }
            val black = mutableSetOf<Int>()
            for (i in headOrder(all.size)) {
                val jpeg = frame(all[i])
                if (jpeg == null) {
                    black += i
                    continue
                }
                return all.withIndex().filter { it.index !in black }.map { (j, m) -> m to jpeg.takeIf { j == i } }
            }
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("video frames failed", e)
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * A stored video locator, in the deepen pass or a model change; no sound or OCR is read again. A black frame tries
     * its ±1 s neighbours, as the first read did; null when all are black.
     */
    fun frameJpeg(context: Context, uri: Uri, ms: Long): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            listOf(ms, ms + 1000, ms - 1000).filter { it >= 0 }.firstNotNullOfOrNull { brightJpeg(retriever, it, 512) }
        } finally {
            retriever.release()
        }
    }

    private fun brightJpeg(retriever: MediaMetadataRetriever, ms: Long, maxSide: Int): ByteArray? {
        val bmp = frameAt(retriever, ms * 1000, maxSide) ?: return null
        return try {
            if (measureBrightness(bmp).isEffectivelyBlack) {
                null
            } else {
                ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            }
        } finally {
            bmp.recycle()
        }
    }

    /** Some devices return null from getScaledFrameAtTime for frames getFrameAtTime can still decode. */
    private fun frameAt(retriever: MediaMetadataRetriever, timeUs: Long, maxSide: Int): Bitmap? =
        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
            ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                val longer = maxOf(full.width, full.height)
                if (longer <= maxSide) return@let full
                val scale = maxSide.toFloat() / longer
                full.scale(
                    (full.width * scale).toInt().coerceAtLeast(1),
                    (full.height * scale).toInt().coerceAtLeast(1),
                ).also { if (it !== full) full.recycle() }
            }

    // Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/VideoDecoderSupport.kt checkVideoDecoderSupport/readVideoTrackFormat (Apache-2.0). Modified for Retriever: one Boolean; anything that can't be inspected counts as supported.
    private fun definitelyUnsupported(context: Context, uri: Uri, log: MediaLog): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).asSequence()
                .map { extractor.getTrackFormat(it) }
                .firstOrNull {
                    it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true &&
                        it.containsKey(MediaFormat.KEY_WIDTH) && it.containsKey(MediaFormat.KEY_HEIGHT)
                } ?: return false
            val format = MediaFormat.createVideoFormat(
                track.getString(MediaFormat.KEY_MIME)!!,
                track.getInteger(MediaFormat.KEY_WIDTH),
                track.getInteger(MediaFormat.KEY_HEIGHT),
            )
            val unsupported = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) == null
            if (unsupported) log("no decoder on this phone for ${format.getString(MediaFormat.KEY_MIME)}", null)
            unsupported
        } catch (e: Exception) {
            log("couldn't inspect video track", e)
            false
        } finally {
            runCatching { extractor.release() }
        }
    }
}

/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/VideoThumbnails.kt (Apache-2.0). Modified for Retriever: private, comments trimmed.

private const val MIN_MEAN_LUMA = 12
private const val MIN_PEAK_LUMA = 64

private data class FrameBrightness(val meanLuma: Int, val peakLuma: Int) {
    val isEffectivelyBlack: Boolean
        get() = meanLuma < MIN_MEAN_LUMA && peakLuma < MIN_PEAK_LUMA
}

private fun measureBrightness(pixels: IntArray): FrameBrightness {
    if (pixels.isEmpty()) return FrameBrightness(meanLuma = 0, peakLuma = 0)
    var total = 0L
    var peak = 0
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val luma = (r * 77 + g * 151 + b * 28) shr 8
        total += luma
        if (luma > peak) peak = luma
    }
    return FrameBrightness(meanLuma = (total / pixels.size).toInt(), peakLuma = peak)
}

private fun measureBrightness(bitmap: Bitmap): FrameBrightness {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return measureBrightness(pixels)
}
