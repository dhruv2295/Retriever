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

package app.retriever

import android.text.format.DateUtils
import androidx.compose.ui.text.TextStyle
import app.retriever.core.Kind
import app.retriever.core.ReadExtent
import app.retriever.core.SourceFile
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Format {
    fun bytes(n: Long): String {
        if (n < 1000) return "$n B"
        val exp = (ln(n.toDouble()) / ln(1000.0)).toInt()
        val pre = "kMGTPE"[exp - 1]
        return String.format(Locale.getDefault(), "%.1f %sB", n / 1000.0.pow(exp), pre)
    }

    /** Said under a result whose file was not read whole; null when it was. */
    fun readNote(extent: ReadExtent?): String? = when (extent) {
        ReadExtent.FIRST_PART -> "Only the first part of this file was read"
        ReadExtent.DEEPENING -> "Still reading the rest of this file"
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
    fun locatorLabel(locator: String): String? = when {
        Opener.locatorToPage(locator) != null -> "Page ${Opener.locatorToPage(locator)}"
        Opener.locatorToMs(locator) != null -> locator
        else -> null
    }

    /** Milliseconds as "m:ss" or "h:mm:ss", the same shape as a time locator. */
    fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** The locator as a screen reader should say it. */
    fun locatorSpoken(locator: String): String? {
        Opener.locatorToPage(locator)?.let { return "page $it" }
        val total = (Opener.locatorToMs(locator) ?: return null) / 1000
        val parts = listOf(total / 3600 to "hour", total % 3600 / 60 to "minute", total % 60 to "second")
            .filter { it.first > 0 }
            .map { (n, unit) -> "$n $unit${if (n == 1L) "" else "s"}" }
        return parts.joinToString(" ").ifEmpty { "0 seconds" }
    }

    fun count(n: Long): String = NumberFormat.getIntegerInstance().format(n)

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
