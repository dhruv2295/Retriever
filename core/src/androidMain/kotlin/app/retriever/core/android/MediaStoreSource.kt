/*
 * Copyright 2023 The Android Open Source Project
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

// Cursor loop adapted from Google AI Edge Gallery (Apache-2.0):
// Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt
// Modified for Retriever: Files collection per storage volume, MediaColumns projection, generation-based
// incremental scans reset when access changes, null-MIME fallback, query failures rethrown and a missing cursor reporting no
// change, never an empty result.
// Query and projection pattern also follows android/platform-samples
// samples/storage/src/main/java/com/example/platform/storage/mediastore/MediaStoreQuery.kt (Apache-2.0).
package app.retriever.core.android

import android.content.ContentUris
import android.content.Context
import android.mtp.MtpConstants
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import app.retriever.core.Changes
import app.retriever.core.FileRules
import app.retriever.core.FileSource
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** [access] names what the app may see (all files, or which media permissions); the token records it. */
class MediaStoreSource(private val context: Context, private val access: () -> String = { "" }, private val log: (String, Throwable?) -> Unit = { _, _ -> }) :
    FileSource {
    // 3: every storage volume, deletions, and files with no MIME type (kept as OTHER). 4: folders left out. 5: dates from
    // file names, so one full scan fills them in for files already indexed.
    override val rulesVersion = 5

    override suspend fun changes(since: String?): Changes = withContext(Dispatchers.IO) {
        val seeing = access()
        val old = resumable(parse(since), seeing)
        val volumes = MediaStore.getExternalVolumeNames(context).toList()
        // Storage not mounted yet (early boot), or MediaStore gave no cursor: report nothing rather than a scan that finds no files.
        val nothing = Changes(emptyList(), emptyList(), since.orEmpty(), present = null, scope = emptySet())
        if (volumes.isEmpty()) return@withContext nothing
        // An unmounted volume (an ejected SD card) keeps its token, so it resumes incrementally when it is back.
        val tokens = JSONObject(old.filterKeys { it !in volumes } + (ACCESS to seeing))
        val upserted = ArrayList<SourceFile>()
        val removed = ArrayList<String>()
        val skips = HashMap<String, Int>()
        var fullVolumes = 0
        for (volume in volumes) {
            // Read the token before querying so a change landing mid-scan is picked up next time.
            val version = MediaStore.getVersion(context, volume)
            tokens.put(volume, "$version:${MediaStore.getGeneration(context, volume)}")
            val prior = old[volume]?.split(':')
            val sinceGeneration = if (prior?.size == 2 && prior[0] == version) prior[1].toLongOrNull() else null
            if (sinceGeneration == null) fullVolumes++
            upserted += query(volume, sinceGeneration, skips, removed) ?: return@withContext nothing
        }
        val isFull = fullVolumes == volumes.size
        log("MediaStore scan: ${upserted.size} files, skipped $skips, full=$isFull", null)
        Changes(
            upserted = upserted,
            removed = removed,
            nextToken = tokens.toString(),
            isFullScan = isFull,
            present = if (isFull) null else volumes.flatMapTo(HashSet()) { presentUris(it) ?: return@withContext nothing },
            scope = volumes.mapTo(HashSet()) { MediaStore.Files.getContentUri(it).toString() + "/" },
        )
    }

    companion object {
        private const val ACCESS = "access"

        /** With all-files access the Files collection also lists folders; "format" is the hidden FileColumns.FORMAT. */
        private const val NOT_FOLDER = "format != ${MtpConstants.FORMAT_ASSOCIATION}"

        /**
         * The per-volume tokens to resume from. Rows that wider access reveals keep their old generation, so an
         * incremental scan would never list them: tokens taken under other access count as none (a full scan).
         */
        fun resumable(tokens: Map<String, String>, access: String): Map<String, String> = if (tokens[ACCESS] == access) tokens else emptyMap()
    }

    private fun parse(token: String?): Map<String, String> = runCatching {
        val json = JSONObject(token ?: return emptyMap())
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())

    /** Null when MediaStore gives no cursor. */
    private fun presentUris(volume: String): List<String>? {
        val collection = MediaStore.Files.getContentUri(volume)
        val cursor = context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), NOT_FOLDER, null, null)
            ?: run {
                log("MediaStore gave no cursor for $volume", null)
                return null
            }
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) add(ContentUris.withAppendedId(collection, c.getLong(0)).toString())
            }
        }
    }

    /** On an incremental scan, a changed row that no longer qualifies (now hidden, now empty) goes to [removed]. Null when MediaStore gives no cursor. */
    private fun query(volume: String, sinceGeneration: Long?, skips: MutableMap<String, Int>, removed: MutableList<String>): List<SourceFile>? {
        val collection = MediaStore.Files.getContentUri(volume)
        val volumeLabel = volumeLabel(collection)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
        val incremental = sinceGeneration != null
        val selection = (if (incremental) "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?" else "${MediaStore.MediaColumns.SIZE} > 0") +
            " AND $NOT_FOLDER"
        val args = if (incremental) arrayOf(sinceGeneration.toString()) else null
        val out = ArrayList<SourceFile>()
        try {
            val cursor = context.contentResolver.query(collection, projection, selection, args, null)
                ?: run {
                    log("MediaStore gave no cursor for $volume", null)
                    return null
                }
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                while (c.moveToNext()) {
                    try {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(idCol)).toString()
                        val name = c.getString(nameCol).orEmpty()
                        val relativePath = c.getString(pathCol).orEmpty()
                        val size = c.getLong(sizeCol)
                        if (size <= 0 || FileRules.shouldSkip(relativePath, name)) {
                            skips.merge("rules", 1, Int::plus)
                            if (incremental) removed += uri
                            continue
                        }
                        val mime = mimeOf(c.getString(mimeCol), name)
                        val kind = FileRules.classify(mime, relativePath, name)
                        if (kind == Kind.OTHER) skips.merge("other", 1, Int::plus)
                        out += SourceFile(
                            uri = uri,
                            name = name,
                            folder = (volumeLabel?.let { "$it/" }.orEmpty() + relativePath).removeSuffix("/"),
                            mime = mime,
                            kind = kind,
                            size = size,
                            // DATE_MODIFIED is in seconds, DATE_TAKEN in ms.
                            modifiedAt = c.getLong(dateCol) * 1000,
                            takenAt = (if (c.isNull(takenCol)) null else c.getLong(takenCol).takeIf { it > 0 })
                                ?: dateInName(name, c.getLong(dateCol) * 1000).takeIf { kind in CAPTURED },
                        )
                    } catch (e: Exception) {
                        skips.merge("unreadable", 1, Int::plus)
                        log("Skipping unreadable MediaStore row", e)
                    }
                }
            }
        } catch (e: Exception) {
            log("MediaStore.Files query failed", e)
            throw IllegalStateException("MediaStore query failed", e)
        }
        return out
    }

    private fun mimeOf(mime: String?, name: String): String {
        if (!mime.isNullOrEmpty() && mime != "application/octet-stream") return mime
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: FileRules.mimeForExtension(ext)
            ?: mime
            ?: "application/octet-stream"
    }

    /** Null for the primary volume; "SD card" and the like otherwise. */
    private fun volumeLabel(collection: Uri): String? = runCatching {
        context.getSystemService(StorageManager::class.java).getStorageVolume(collection)
            .takeIf { !it.isPrimary }?.getDescription(context)
    }.getOrNull()
}

// A date anywhere in a name, with or without separators, and the local time after it if there is one: IMG-20241024-WA0009,
// Screenshot_20241024-153012, signal-2024-10-24-153012. Not inside a longer run of digits.
private val NAME_DATE = Regex("""(?<!\d)(20\d{2})[-_.]?(\d{2})[-_.]?(\d{2})(?:[-_ .]?(\d{2})[-_.]?(\d{2})[-_.]?(\d{2}))?(?!\d)""")
private val EARLIEST = LocalDate.of(2000, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * When a photo or video has no capture date, the date its name gives: WhatsApp strips it from what it saves, and copying
 * files to a new phone resets their modified date, while the name survives. A day with no time is taken at noon. Null
 * without a real date, or for one before 2000 or after [modifiedAt] (a day's slack for a day-only name in another zone).
 */
internal fun dateInName(name: String, modifiedAt: Long, zone: ZoneId = ZoneId.systemDefault()): Long? = NAME_DATE.findAll(name).firstNotNullOfOrNull { m ->
    val (y, mo, d, h, mi, s) = m.destructured
    try {
        val day = LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
        if (h.isEmpty()) day.atTime(LocalTime.NOON) else LocalDateTime.of(day, LocalTime.of(h.toInt(), mi.toInt(), s.toInt()))
    } catch (_: DateTimeException) {
        null
    }?.atZone(zone)?.toInstant()?.toEpochMilli()?.takeIf { it in EARLIEST..modifiedAt + DAY_MS }
}

private const val DAY_MS = 86_400_000L

/** What has a capture date to stand in for; a document's dated name ("Report 2019-03-01.pdf") is no "Taken". */
private val CAPTURED = setOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.VIDEO)
