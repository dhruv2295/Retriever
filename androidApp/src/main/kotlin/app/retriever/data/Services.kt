package app.retriever.data

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE
import android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
import android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
import android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.retriever.Access
import app.retriever.BuildConfig
import app.retriever.IndexScheduler
import app.retriever.ModelDownload
import app.retriever.Opener
import app.retriever.Photos
import app.retriever.Route
import app.retriever.StorageUsage
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.android.SafSource
import app.retriever.storageUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Background indexing as the screens see it. */
class IndexService(private val context: Context, private val retriever: Retriever) {
    fun indexNow() = IndexScheduler.indexNow(context)

    fun schedulePeriodic(onlyWhileCharging: Boolean) = IndexScheduler.schedulePeriodic(context, onlyWhileCharging)

    /** Drops the queued and running runs and starts a fresh one, so changed settings apply to the next sync at once. */
    fun restart(onlyWhileCharging: Boolean) {
        IndexScheduler.pause(context)
        indexNow()
        schedulePeriodic(onlyWhileCharging)
    }

    /** The user's Pause: a running batch stops within one file, and later runs wait until [resume]. */
    suspend fun pause() {
        retriever.pause()
        IndexScheduler.pause(context)
    }

    /** "Index now": lifts a user pause and starts a batch. */
    suspend fun resume() {
        retriever.resume()
        indexNow()
    }
}

/** What the app holds on disk, and giving the model's share of it back. */
class StorageService(
    private val context: Context,
    private val retriever: Retriever,
    private val download: ModelDownload,
    private val modelDir: File,
) {
    suspend fun usage(): StorageUsage =
        withContext(Dispatchers.IO) { storageUsage(context, modelDir, retriever.requiredBuild.value) }

    /** Stops the running batch and frees the model, then deletes it; names and words keep searching. */
    suspend fun freeUpSpace() {
        retriever.cancelBatch()
        retriever.release()
        withContext(Dispatchers.IO) { download.deleteModel(retriever.requiredBuild.value) }
        retriever.refreshSetUp()
    }
}

/** What the app may read, as a flow the screens watch; [refresh] after the person may have changed it in Settings. */
class AccessService(
    private val context: Context,
    /** Demo mode sees everything and touches no permission. */
    private val demo: Boolean = false,
    /** A folder or permission changed; the app module syncs the index. */
    private val onChanged: () -> Unit = {},
) {
    private val resolver = context.contentResolver
    private val _state = MutableStateFlow(access())
    val state: StateFlow<Access> = _state.asStateFlow()

    /** Read fresh on every call: the person can change any of it in Settings while the app runs. */
    fun access(): Access = if (demo) Access.ALL else Access.of(
        Build.VERSION.SDK_INT,
        Environment.isExternalStorageManager(),
        { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED },
        resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() },
        SafSource.volumeDescriber(context),
    )

    fun refresh() {
        _state.value = access()
    }

    /** A folder or permission changed: sync now, or at the end of the batch already running. */
    fun changed() {
        refresh()
        onChanged()
    }

    /** Keeps a folder from the system picker; returns why it can't be added, or null. */
    fun addFolder(uri: Uri): String? {
        val folders = access().folders
        Access.refusePick(uri.toString(), folders)?.let { return it }
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            // Write lets Duplicates delete copies there; a provider may grant read only.
            runCatching { resolver.takePersistableUriPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .getOrElse { resolver.takePersistableUriPermission(uri, read) }
        } catch (e: SecurityException) {
            LogSink.write(BuildConfig.DEBUG, "folder not kept", e)
            return "Couldn't keep access to that folder. Try picking it again."
        }
        // The new folder covers any added inside it.
        Access.inside(uri.toString(), folders).forEach { release(it.uri) }
        changed()
        return null
    }

    fun removeFolder(uri: String) {
        release(uri)
        changed()
    }

    private fun release(uri: String) {
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri.toString() == uri } ?: return
        val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        resolver.releasePersistableUriPermission(grant.uri, flags)
    }

    /** Picking more photos reveals old rows without changing a permission, so the picked set is part of it. */
    fun mediaAccess(): String {
        val a = access()
        if (a.photos != Photos.SELECTED) return a.fingerprint
        // Videos too unless all of them are allowed: Android's picker hands out both.
        val types = if (a.videos) "$MEDIA_TYPE_IMAGE" else "$MEDIA_TYPE_IMAGE, $MEDIA_TYPE_VIDEO"
        var picked = 0L
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns._ID),
            "$MEDIA_TYPE IN ($types)",
            null,
            MediaStore.MediaColumns._ID,
        )?.use { c -> while (c.moveToNext()) picked = picked * 31 + c.getLong(0) }
        return "${a.fingerprint}-$picked"
    }

    /**
     * What MediaStore lists beyond the kinds it covers in full ([Access.mediaKinds]), e.g. the photos the person
     * picked, as [SafSource.mediaKey]s: a picked folder skips them, so one photo never has two rows.
     */
    fun mediaListed(): Set<String> {
        val a = access()
        val covered = buildList {
            if (a.photos == Photos.ALL) add(MEDIA_TYPE_IMAGE)
            if (a.videos) add(MEDIA_TYPE_VIDEO)
            if (a.audio) add(MEDIA_TYPE_AUDIO)
        }
        val keys = HashSet<String>()
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME),
            if (covered.isEmpty()) null else "$MEDIA_TYPE NOT IN (${covered.joinToString()})",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) keys += SafSource.mediaKey(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getString(2).orEmpty())
        }
        return keys
    }
}

/** Opens a file in the app's own viewer when the match has a place in it, or in whichever app opens it. */
class FileOpener(private val context: Context) {
    fun open(file: SourceFile, locator: String = "", navigate: (Route) -> Unit) = Opener.open(context, file, locator, navigate)

    fun view(uri: String, mime: String) = Opener.view(context, uri, mime)
}

/** Deletes files the app found, through the provider each one came from. */
class MediaDeleter(context: Context) {
    private val resolver = context.contentResolver

    /** Deletes what the app may delete itself; returns the uris that went. */
    suspend fun delete(uris: List<String>): List<String> = withContext(Dispatchers.IO) {
        uris.filter { uri ->
            try {
                if (uri.startsWith(SafSource.PREFIX)) DocumentsContract.deleteDocument(resolver, uri.toUri())
                else resolver.delete(uri.toUri(), null, null) > 0
            } catch (e: Exception) {
                Log.w("Retriever", "delete failed: $uri", e)
                false
            }
        }
    }

    /** Android's own confirmation for deleting others' photos and videos, or null when it can't ask. */
    fun request(uris: List<String>): IntentSender? = try {
        MediaStore.createDeleteRequest(resolver, uris.map(Uri::parse)).intentSender
    } catch (e: Exception) {
        Log.w("Retriever", "delete request failed", e)
        null
    }
}
