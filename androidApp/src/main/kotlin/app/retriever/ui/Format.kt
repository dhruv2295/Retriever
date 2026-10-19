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
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/Utils.kt (Apache-2.0). Modified for Retriever: humanReadableSize became Format.bytes, SI only, explicit Locale.

package app.retriever.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.ui.text.TextStyle
import app.retriever.access.Photos
import app.retriever.core.ESTIMATE_CALIBRATED
import app.retriever.core.Kind
import app.retriever.core.Locator
import app.retriever.core.ReadEstimate
import app.retriever.core.ReadExtent
import app.retriever.core.SourceFile
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Format {
    fun downloadSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun bytes(n: Long): String {
        if (n < 1000) return "$n B"
        val exp = (ln(n.toDouble()) / ln(1000.0)).toInt()
        val pre = "kMGTPE"[exp - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", n / 1000.0.pow(exp), pre)
    }

    /** Said under a result whose file was not read whole; null when it was. */
    fun readNote(extent: ReadExtent?): String? = when (extent) {
        ReadExtent.FIRST_PART -> "Partly read · only the beginning is included"
        ReadExtent.MEANING_LIMITED -> "Partly read · couldn’t finish meaning search"
        ReadExtent.FIRST_PART_MEANING_LIMITED -> "Partly read · only the beginning is included; couldn’t finish meaning search"
        ReadExtent.FIRST_PART_DEEPENING -> "Partly read · only the beginning is included; more meaning search to come"
        ReadExtent.DEEPENING -> "Partly read · more meaning search to come"
        else -> null
    }

    /** What a search kept when the chips narrow it, as a plural noun ("starred PDFs", "photos"); null when it kept everything. */
    fun scope(chip: Kind?, onlyStarred: Boolean): String? {
        val kind = chip?.let(::kindLabelPlural)?.let { k -> if (k[1].isUpperCase()) k else k.lowercase() }
        return when {
            onlyStarred -> "starred ${kind ?: "files"}"
            else -> kind
        }
    }

    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    fun kindLabel(kind: Kind): String = when (kind) {
        Kind.PDF -> "PDF"
        Kind.DOC -> "Document"
        Kind.SCREENSHOT -> "Screenshot"
        Kind.PHOTO -> "Photo"
        Kind.AUDIO -> "Audio"
        Kind.VIDEO -> "Video"
        Kind.OTHER -> "File"
    }

    /** "p. 12" becomes "Page 12", a time stays as it is, anything else has no label. */
    fun locatorLabel(locator: String): String? = Locator.pageOf(locator)?.let { "Page $it" } ?: locator.takeIf { Locator.msOf(it) != null }

    /** The locator as a screen reader should say it. */
    fun locatorSpoken(locator: String): String? {
        Locator.pageOf(locator)?.let { return "page $it" }
        val total = (Locator.msOf(locator) ?: return null) / 1000
        val parts = listOf(total / 3600 to "hour", total % 3600 / 60 to "minute", total % 60 to "second")
            .filter { it.first > 0 }
            .map { (n, unit) -> "$n $unit${if (n == 1L) "" else "s"}" }
        return parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    fun readCount(read: Long, partlyRead: Long = 0): String =
        "${count(read)} ${if (read == 1L) "file" else "files"} read" + if (partlyRead > 0) " · ${count(partlyRead)} partly read" else ""

    fun count(n: Long): String = NumberFormat.getIntegerInstance().format(n)

    /** The try-it results header: it claims nothing about quality, only where the search looks. */
    fun closestIn(files: Int): String = "Closest in these ${count(files.toLong())} files"

    fun stillReading(files: Int): String = "Still reading ${count(files.toLong())} more"

    /**
     * The commit screen's sentence about the rest of the library. Until [ESTIMATE_CALIBRATED], it names no time: the
     * placeholders behind [ReadEstimate] are not yet checked against a full read on a device.
     */
    fun restSentence(rest: Long?, estimate: ReadEstimate?): String {
        val others = if (rest == null) "the rest" else "the other ${count(rest)}"
        return if (ESTIMATE_CALIBRATED && estimate != null) {
            "At this pace, $others take about ${minutesRange(estimate.lowMin, estimate.highMin)}."
        } else {
            "Reading $others takes hours; Retriever measures this phone as it goes."
        }
    }

    /** "5 to 7 hours", "40 to 55 minutes"; one number when both round to the same. Only behind [ESTIMATE_CALIBRATED]. */
    fun minutesRange(low: Int, high: Int): String {
        if (high < 90) return span(low, high, "minute")
        return span((low + 30) / 60, (high + 30) / 60, "hour")
    }

    private fun span(low: Int, high: Int, unit: String): String = if (low == high) "$low $unit${if (low == 1) "" else "s"}" else "$low to $high ${unit}s"

    fun eta(minutes: Int): String = when {
        minutes < 1 -> "less than a minute left"
        minutes == 1 -> "about a minute left"
        minutes < 60 -> "about $minutes minutes left"
        minutes < 90 -> "about an hour left"
        minutes < 48 * 60 -> "about ${(minutes + 30) / 60} hours left"
        else -> "about ${(minutes + 12 * 60) / (24 * 60)} days left"
    }

    /** "Download · Today · 2.1 MB": the last folder, the file's modified day in the system's words, its size. */
    fun fileLine(file: SourceFile, now: Long = System.currentTimeMillis()): String {
        val day = DateUtils.getRelativeTimeSpanString(file.modifiedAt, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_MONTH)
        return listOf(file.folder.trimEnd('/').substringAfterLast('/'), day.toString(), bytes(file.size)).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    fun kindLabelPlural(kind: Kind): String = when (kind) {
        Kind.PDF -> "PDFs"
        Kind.DOC -> "Documents"
        Kind.SCREENSHOT -> "Screenshots"
        Kind.PHOTO -> "Photos"
        Kind.AUDIO -> "Audio"
        Kind.VIDEO -> "Videos"
        Kind.OTHER -> "Other files"
    }
}

/** Equal-width digits, so counts that tick up don't nudge the text around them. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
