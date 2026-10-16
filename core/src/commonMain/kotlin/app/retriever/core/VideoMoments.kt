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

// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/AudioInterleaving.kt and Common.kt formatTime (Apache-2.0). Modified for Retriever: one keyframe per moment, token budget derived from the profile's window, padStart formatting for commonMain.

package app.retriever.core

/** Gallery derives 6.25/s; the larger estimate keeps a window from ever overflowing. */
const val AUDIO_TOKENS_PER_SEC = 25
const val MARKER_TOKENS = 8
const val SEQUENCE_TOKENS = 2
const val MAX_VIDEO_MOMENTS = 8

fun audioWindowSec(maxTokens: Int): Int =
    ((maxTokens - MARKER_TOKENS - SEQUENCE_TOKENS) / AUDIO_TOKENS_PER_SEC).coerceIn(1, AUDIO_WINDOW_SEC)

/** 0 or 1 means moments carry no audio. */
fun momentAudioSec(maxTokens: Int, visionTokens: Int): Int =
    ((maxTokens - visionTokens - 2 * MARKER_TOKENS - SEQUENCE_TOKENS) / AUDIO_TOKENS_PER_SEC).coerceIn(0, AUDIO_WINDOW_SEC)

fun estimateTokens(visionTokens: Int, audioSec: Int): Int =
    visionTokens + audioSec * AUDIO_TOKENS_PER_SEC + (if (audioSec > 0) 2 else 1) * MARKER_TOKENS + SEQUENCE_TOKENS

data class Moment(val startMs: Long, val endMs: Long, val frameMs: Long, val audioFromMs: Long, val audioToMs: Long)

fun moments(durationMs: Long, everySec: Int, audioSec: Int): List<Moment> {
    if (durationMs <= 0 || everySec <= 0) return emptyList()
    val n = (durationMs / 1000 / everySec).coerceIn(1, MAX_VIDEO_MOMENTS.toLong())
    val half = audioSec * 500L
    return (0 until n).map { i ->
        val start = i * durationMs / n
        val end = (i + 1) * durationMs / n
        val frame = (start + end) / 2
        Moment(start, end, frame, maxOf(start, frame - half), minOf(end, frame + half))
    }
}

/** Gallery's TAV layout when audio exists; its frame-only path was trained without timestamps. */
fun momentParts(frameJpeg: ByteArray, frameMs: Long, audio: ShortArray?): List<Part> =
    if (audio == null || audio.isEmpty()) {
        listOf(Part.Image(frameJpeg))
    } else {
        val stamp = Part.Text(modelTimestamp(frameMs))
        listOf(stamp, Part.Audio(audio), stamp, Part.Image(frameJpeg))
    }

/** Where a result jumps: the start of what the model heard, else the frame it saw, not the segment start. */
fun momentLocator(m: Moment, withAudio: Boolean): String = timeLocator(((if (withAudio) m.audioFromMs else m.frameMs) / 1000).toInt())

/** The timestamp text the model sees: 'mm:ss' or 'h:mm:ss'. */
fun modelTimestamp(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    val h = s / 3600
    return if (h > 0) "$h:${pad(s % 3600 / 60)}:${pad(s % 60)}" else "${pad(s / 60)}:${pad(s % 60)}"
}

/** UI locator ('3:45', '1:02:03'); a moment's locator is where its audio window starts, else its frame. */
fun timeLocator(sec: Int): String =
    if (sec >= 3600) "${sec / 3600}:${pad(sec % 3600 / 60L)}:${pad(sec % 60L)}" else "${sec / 60}:${pad(sec % 60L)}"

private fun pad(n: Long): String = n.toString().padStart(2, '0')
