package app.retriever.duplicates

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

    /** Android's own confirmation for deleting others' photos and videos, or null when it can't ask. */
    fun request(uris: List<String>): IntentSender? = try {
        MediaStore.createDeleteRequest(resolver, uris.map(Uri::parse)).intentSender
    } catch (e: Exception) {
        LogSink.write(BuildConfig.DEBUG, "delete request failed", e)
        null
    }
}
