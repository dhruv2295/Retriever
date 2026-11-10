/*
 * Copyright 2025 Google LLC
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
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt (Apache-2.0). Modified for Retriever: DownloadManager request and polling pulled out of the UI into one class, with a free-space check and allocation, sha256 verification against the pinned build, pruning of other builds, and Wi-Fi only unless the user allows mobile data.

package app.retriever.model

import android.app.DownloadManager
import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.net.toUri
import app.retriever.core.ModelBuild
import app.retriever.core.Prefs
import app.retriever.core.Words
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class ModelDownload(private val context: Context, private val modelDir: File) {
    /** [noSpace]: DownloadManager failed it for space (ERROR_INSUFFICIENT_SPACE). */
    data class Progress(
        val bytes: Long,
        val total: Long,
        val done: Boolean,
        val failed: Boolean,
        val waitingForWifi: Boolean = false,
        val noSpace: Boolean = false,
    )

    /** Too little room for the model even after Android cleared other apps' caches. */
    class NoSpace(cause: Throwable) : IOException("Not enough free space for the model", cause)

    companion object {
        /** Covers the index DB's first growth. */
        const val MARGIN_BYTES = 300L * 1024 * 1024

        fun neededBytes(b: ModelBuild): Long = b.sizeBytes + MARGIN_BYTES
    }

    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val storage = context.getSystemService(StorageManager::class.java)

    fun file(b: ModelBuild): File = File(modelDir, b.fileName)

    private fun marker(b: ModelBuild): File = File(modelDir, b.fileName + ".sha256")

    /** Cheap on purpose: size plus the marker that [verify] wrote. No hashing here. */
    fun ready(b: ModelBuild): Boolean = file(b).length() == b.sizeBytes && marker(b).let { it.exists() && it.readText().trim() == b.sha256 }

    /** The model folder's volume; null when it has no UUID, where StatFs stands in. */
    private fun volume(): UUID? = try {
        storage.getUuidForPath(modelDir.apply { mkdirs() })
    } catch (_: IOException) {
        null
    }

    /** Free space plus other apps' caches Android can clear for us (getAllocatableBytes); StatFs where the volume has no UUID. */
    fun freeBytes(): Long = try {
        volume()?.let(storage::getAllocatableBytes)
    } catch (_: IOException) {
        null
    } ?: StatFs(modelDir.path).availableBytes

    fun hasSpaceFor(b: ModelBuild): Boolean = freeBytes() >= neededBytes(b)

    /**
     * Without [Prefs.allowMobileData], DownloadManager holds the download until Wi-Fi (reported as [Progress.waitingForWifi]).
     * Throws [NoSpace] when the volume can't make room for it.
     */
    fun start(b: ModelBuild, prefs: Prefs): Long {
        file(b).let { if (it.exists() && it.length() != b.sizeBytes) it.delete() }
        marker(b).delete()
        reserve(neededBytes(b))
        val request = DownloadManager.Request(b.url.toUri())
            .setTitle(Words.strings.searchModelDownloadTitle)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "models", b.fileName)
            .setAllowedOverMetered(prefs.allowMobileData)
        return downloads.enqueue(request)
    }

    /**
     * Has Android clear other apps' caches now if [bytes] need their room, so the download doesn't run out of it halfway.
     * Its refusal stands: StatFs ignores the low-storage reserve, below which every index run waits.
     */
    private fun reserve(bytes: Long) {
        val id = volume()
        if (id == null) {
            val free = StatFs(modelDir.path).availableBytes
            if (free < bytes) throw NoSpace(IOException("$free bytes free, $bytes needed"))
            return
        }
        try {
            storage.allocateBytes(id, bytes)
        } catch (e: IOException) {
            throw NoSpace(e)
        }
    }

    /** Stops download [id] and drops its partial file. */
    fun remove(id: Long) {
        downloads.remove(id)
    }

    /** The download of [b] still queued or running, e.g. one a killed process started; null if there is none. */
    fun active(b: ModelBuild): Long? {
        val query = DownloadManager.Query().setFilterByStatus(
            DownloadManager.STATUS_PENDING or DownloadManager.STATUS_RUNNING or DownloadManager.STATUS_PAUSED,
        )
        // query() is nullable: a null cursor means no download we can see.
        downloads.query(query)?.use { c ->
            val uri = c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val id = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (c.moveToNext()) if (c.getString(uri) == b.url) return c.getLong(id)
        }
        return null
    }

    /** Polls once a second; completes after emitting a done or failed value. */
    fun progress(id: Long): Flow<Progress> = flow {
        while (true) {
            var p: Progress? = null
            downloads.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                p = if (!c.moveToFirst()) {
                    Progress(0, 0, done = false, failed = true)
                } else {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    Progress(
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                        done = status == DownloadManager.STATUS_SUCCESSFUL,
                        failed = status == DownloadManager.STATUS_FAILED,
                        waitingForWifi = status == DownloadManager.STATUS_PAUSED &&
                            reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI,
                        noSpace = status == DownloadManager.STATUS_FAILED && reason == DownloadManager.ERROR_INSUFFICIENT_SPACE,
                    )
                }
            }
            val next = p ?: Progress(0, 0, done = false, failed = true)
            emit(next)
            if (next.done || next.failed) return@flow
            delay(1000)
        }
    }.flowOn(Dispatchers.IO)

    /** Hashes the file; a match writes the marker, a mismatch deletes the file. */
    suspend fun verify(b: ModelBuild): Boolean = withContext(Dispatchers.IO) {
        val f = file(b)
        if (!f.exists()) return@withContext false
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val hex = md.digest().toHexString()
        if (hex == b.sha256.lowercase()) {
            marker(b).writeText(b.sha256)
            true
        } else {
            f.delete()
            marker(b).delete()
            false
        }
    }

    /** Removes the model, its marker and that build's compiled-program caches (named as MediaPipeEmbedder.cache does). */
    fun deleteModel(b: ModelBuild) {
        file(b).delete()
        marker(b).delete()
        val mine = Regex(Regex.escape(b.fileName.substringBeforeLast('.')) + "-[A-Za-z]+-vt\\d+-t\\d+")
        File(context.cacheDir, "embed").listFiles()?.filter { mine.matches(it.name) }?.forEach { it.deleteRecursively() }
    }

    /** Deletes every other model file, e.g. the NPU build after a fallback or an old unpinned download. */
    fun prune(keep: ModelBuild) {
        modelDir.listFiles()?.forEach {
            if ((it.name.endsWith(".litertlm") || it.name.endsWith(".sha256")) &&
                it.name != keep.fileName && it.name != keep.fileName + ".sha256"
            ) {
                it.delete()
            }
        }
    }
}
