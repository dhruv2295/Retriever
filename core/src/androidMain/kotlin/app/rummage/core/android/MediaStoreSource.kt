// Cursor loop adapted from Google AI Edge Gallery (Apache-2.0):
// Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt
// Modified for Rummage: Files collection, MediaColumns projection, generation-based incremental scans,
// and query failures are rethrown instead of returning an empty result.
package app.rummage.core.android

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import app.rummage.core.Changes
import app.rummage.core.FileSource
import app.rummage.core.SourceFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreSource(private val context: Context) : FileSource {
    // 2: Android/media (WhatsApp and other apps' shared media) is no longer skipped.
    override val rulesVersion = 2

    override suspend fun changes(since: String?): Changes = withContext(Dispatchers.IO) {
        // Read the token before querying so a change landing mid-scan is picked up next time.
        val version = MediaStore.getVersion(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val generation = MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val token = "$version:$generation"
        val parts = since?.split(':')
        val sinceGeneration = if (parts?.size == 2 && parts[0] == version) parts[1].toLongOrNull() else null
        Changes(
            upserted = query(sinceGeneration),
            removed = emptyList(),
            nextToken = token,
            isFullScan = sinceGeneration == null,
        )
    }

    private fun query(sinceGeneration: Long?): List<SourceFile> {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (sinceGeneration != null) add(MediaStore.MediaColumns.GENERATION_MODIFIED)
        }.toTypedArray()
        var selection = "${MediaStore.MediaColumns.SIZE} > 0 AND ${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL"
        var args: Array<String>? = null
        if (sinceGeneration != null) {
            selection += " AND ${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?"
            args = arrayOf(sinceGeneration.toString())
        }
        val out = ArrayList<SourceFile>()
        try {
            val cursor = context.contentResolver.query(collection, projection, selection, args, null)
                ?: error("MediaStore query returned no cursor")
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (c.moveToNext()) {
                    try {
                        val name = c.getString(nameCol).orEmpty()
                        val relativePath = c.getString(pathCol).orEmpty()
                        val mime = c.getString(mimeCol) ?: continue
                        if (MediaStoreSampler.shouldSkip(name, relativePath)) continue
                        out += SourceFile(
                            uri = ContentUris.withAppendedId(collection, c.getLong(idCol)).toString(),
                            name = name,
                            folder = relativePath.removeSuffix("/"),
                            mime = mime,
                            kind = MediaStoreSampler.classify(mime, relativePath, name),
                            size = c.getLong(sizeCol),
                            modifiedAt = c.getLong(dateCol) * 1000,
                        )
                    } catch (e: Exception) {
                        Log.w("Rummage", "Skipping unreadable MediaStore row: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Rummage", "MediaStore.Files query failed: ${e.message}")
            throw IllegalStateException("MediaStore query failed", e)
        }
        return out
    }
}
