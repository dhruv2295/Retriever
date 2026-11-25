package app.retriever.duplicates

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import app.retriever.BuildConfig
import app.retriever.core.android.SafSource
import app.retriever.diagnostics.LogSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What [MediaDeleter.deleteOrAsk] deleted, and the files [consent] asks the person about; none when Android can't ask. */
class Deletion(val deleted: List<String>, val asked: List<String>, val consent: IntentSender?)

/** Deletes files the app found, through the provider each one came from. */
class MediaDeleter(context: Context) {
    private val resolver = context.contentResolver

    /** Deletes what the app may delete itself; returns the uris that went. */
    suspend fun delete(uris: List<String>): List<String> = withContext(Dispatchers.IO) {
        uris.filter { uri ->
            try {
                if (uri.startsWith(SafSource.PREFIX)) {
                    DocumentsContract.deleteDocument(resolver, uri.toUri())
                } else {
                    resolver.delete(uri.toUri(), null, null) > 0
                }
            } catch (e: Exception) {
                LogSink.write(BuildConfig.DEBUG, "delete failed", e)
                false
            }
        }
    }

    /**
     * Deletes [uris]: at once what the app may delete itself, and Android's confirmation for the rest, which goes in
     * [Deletion.consent]. Without all-files access, Android itself asks the person before others' photos and videos go.
     */
    suspend fun deleteOrAsk(uris: List<String>, allFiles: Boolean): Deletion {
        val ask = if (allFiles) emptyList() else uris.filterNot { it.startsWith(SafSource.PREFIX) }
        val deleted = delete(uris - ask.toSet())
        val consent = if (ask.isEmpty()) null else request(ask)
        return Deletion(deleted, if (consent == null) emptyList() else ask, consent)
    }

    /** Android's own confirmation for deleting others' photos and videos, or null when it can't ask. */
    fun request(uris: List<String>): IntentSender? = try {
        val items = uris.map { mediaItemUri(resolver, it) ?: return null }
        MediaStore.createDeleteRequest(resolver, items).intentSender
    } catch (e: Exception) {
        LogSink.write(BuildConfig.DEBUG, "delete request failed", e)
        null
    }
}

/**
 * The app keeps MediaStore files by their Files address. Android's confirmations (createDeleteRequest, createWriteRequest),
 * and the access they grant, take only a photo's, video's or audio file's own address: a Files one threw
 * IllegalArgumentException, and every delete ended in an error. Null for anything else, which Android can't ask about.
 */
fun mediaItemUri(resolver: ContentResolver, uri: String): Uri? {
    val files = uri.toUri()
    val type = resolver.query(files, arrayOf(MediaStore.Files.FileColumns.MEDIA_TYPE), null, null, null)
        ?.use { if (it.moveToFirst()) it.getInt(0) else null } ?: return null
    val volume = MediaStore.getVolumeName(files)
    val collection = when (type) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.getContentUri(volume)
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.getContentUri(volume)
        MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(volume)
        else -> return null
    }
    return ContentUris.withAppendedId(collection, ContentUris.parseId(files))
}
