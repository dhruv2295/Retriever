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
import app.retriever.core.Kind
import app.retriever.core.Locator
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.core.formatCount
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object Format {
    fun downloadSize(context: Context, bytes: Long): String = Words.keepTogether(Formatter.formatShortFileSize(context, bytes))

    fun bytes(n: Long): String {
        if (n < 1000) return "$n${Words.NBSP}B"
        val exp = (ln(n.toDouble()) / ln(1000.0)).toInt()
        val pre = "kMGTPE"[exp - 1]
        return String.format(Locale.getDefault(), "%.1f${Words.NBSP}%sB", n / 1000.0.pow(exp), pre)
    }

    /** What a search kept when the chips narrow it, as a plural noun ("starred PDFs", "photos"); null when it kept everything. */
    fun scope(chip: Kind?, onlyStarred: Boolean): String? {
        val kind = chip?.let(Words::kindLabelPlural)?.let { k -> if (k[1].isUpperCase()) k else k.lowercase() }
        return when {
            onlyStarred -> "starred ${kind ?: "files"}"
            else -> kind
        }
    }

    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

    /** "p. 12" becomes "Page 12", a time stays as it is, anything else has no label. */
    fun locatorLabel(locator: String): String? = Locator.pageOf(locator)?.let { "Page $it" } ?: locator.takeIf { Locator.msOf(it) != null }

    fun count(n: Long): String = formatCount(n)

    /** "Download · Today · 2.1 MB": the last folder, the file's modified day in the system's words, its size. */
    fun fileLine(file: SourceFile, now: Long = System.currentTimeMillis()): String {
        val day = DateUtils.getRelativeTimeSpanString(file.modifiedAt, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_MONTH)
        return listOf(file.folder.trimEnd('/').substringAfterLast('/'), day.toString(), bytes(file.size)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

/** Equal-width digits, so counts that tick up don't nudge the text around them. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")
