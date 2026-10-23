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
import android.text.format.Formatter
import androidx.compose.ui.text.TextStyle
import app.retriever.core.Kind
import app.retriever.core.Locator
import app.retriever.core.Words
import app.retriever.core.formatCount
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

object Format {
    fun downloadSize(context: Context, bytes: Long): String = Words.keepTogether(Formatter.formatShortFileSize(context, bytes))

    /**
     * One decimal, SI: the index's sizes keep the precision the platform's short format drops ("484.6 MB", not "485 MB").
     * A figure that would round to 1000.0 moves up a unit, so 999,999 bytes is 1.0 MB.
     */
    fun bytes(n: Long): String {
        if (n < 1000) return "$n${Words.NBSP}B"
        var value = n / 1000.0
        var unit = 0
        while (unit < 5 && (value * 10).roundToLong() >= 10_000) {
            value /= 1000
            unit++
        }
        return String.format(Locale.getDefault(), "%.1f${Words.NBSP}%sB", value, "kMGTPE"[unit])
    }

    /** What a search kept when the chips narrow it, as a plural noun ("starred PDFs", "photos"); null when it kept everything. */
    fun scope(chip: Kind?, onlyStarred: Boolean): String? {
        val kind = chip?.let(Words::kindLabelPlural)?.let { k -> if (k[1].isUpperCase()) k else k.lowercase() }
        return when {
            onlyStarred -> "starred ${kind ?: "files"}"
            else -> kind
        }
    }

    /** The last formatter, for its locale: immutable, so every row shares it, where DateFormat built one per call. */
    @Volatile private var dates: Pair<Locale, DateTimeFormatter>? = null

    /** The locale's medium date ("Oct 9, 2025"), in the phone's time zone. */
    fun date(ms: Long): String {
        val locale = Locale.getDefault(Locale.Category.FORMAT)
        val formatter = dates?.takeIf { it.first == locale }?.second
            ?: DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).also { dates = locale to it }
        return formatter.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }

    /** "p. 12" becomes "Page 12", a time stays as it is, anything else has no label. */
    fun locatorLabel(locator: String): String? = Locator.pageOf(locator)?.let { "Page $it" } ?: locator.takeIf { Locator.msOf(it) != null }

    fun count(n: Long): String = formatCount(n)
}

/** Equal-width digits, so counts that tick up don't nudge the text around them. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
