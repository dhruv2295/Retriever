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

// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/AudioInterleaving.kt (Apache-2.0). Modified for Retriever: one keyframe per moment, token budget derived from the profile's window.

package app.retriever.core

/** Gallery derives 6.25/s; the larger estimate keeps a window from ever overflowing. */
const val AUDIO_TOKENS_PER_SEC = 25
const val MARKER_TOKENS = 8
const val SEQUENCE_TOKENS = 2
const val MAX_VIDEO_MOMENTS = 8

fun audioWindowSec(maxTokens: Int): Int = ((maxTokens - MARKER_TOKENS - SEQUENCE_TOKENS) / AUDIO_TOKENS_PER_SEC).coerceIn(1, AUDIO_WINDOW_SEC)

data class Moment(val startMs: Long, val endMs: Long, val frameMs: Long)

/**
 * The first pass embeds one moment of a video and leaves the rest [later]: the middle one, the likeliest to show what the
 * video is about, then outwards past black frames. Indexes into [moments]' n.
 */
fun headOrder(n: Int): List<Int> = (0 until n).sortedBy { kotlin.math.abs(it - n / 2) }

fun moments(durationMs: Long, everySec: Int): List<Moment> {
    if (durationMs <= 0 || everySec <= 0) return emptyList()
    val n = (durationMs / 1000 / everySec).coerceIn(1, MAX_VIDEO_MOMENTS.toLong())
    return (0 until n).map { i ->
        val start = i * durationMs / n
        val end = (i + 1) * durationMs / n
        Moment(start, end, (start + end) / 2)
    }
}

/** Where a result jumps: the frame the model saw, not the segment start. */
fun momentLocator(m: Moment): String = timeLocator((m.frameMs / 1000).toInt())

/** UI locator ('3:45', '1:02:03'); see [Locator.time]. */
fun timeLocator(sec: Int): String = Locator.time(sec)
