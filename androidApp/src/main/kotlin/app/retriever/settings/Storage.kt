package app.retriever.settings

import android.content.Context
import app.retriever.core.ModelBuild
import app.retriever.core.Retriever
import app.retriever.core.Store
import app.retriever.model.ModelDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the app holds on disk, and giving the model's share of it back. */
class StorageService(private val context: Context, private val retriever: Retriever, private val download: ModelDownload, private val modelDir: File) {
    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) { storageUsage(context, modelDir, retriever.requiredBuild.value) }

    /** Stops the running batch and frees the model, then deletes it; names and words keep searching. */
    suspend fun freeUpSpace() {
        retriever.cancelBatch()
        retriever.release()
        withContext(Dispatchers.IO) { download.deleteModel(retriever.requiredBuild.value) }
        retriever.refreshSetUp()
    }
}

/** What the app holds on disk. The embedder cache is rebuilt on demand, and Free up space frees it too. */
data class StorageUsage(val indexBytes: Long, val modelBytes: Long, val cacheBytes: Long)

fun storageUsage(context: Context, modelDir: File, build: ModelBuild): StorageUsage {
    val db = context.getDatabasePath(Store.DB_NAME)
    return StorageUsage(
        indexBytes = listOf("", "-wal", "-shm").sumOf { File(db.path + it).length() },
        modelBytes = File(modelDir, build.fileName).length(),
        cacheBytes = File(context.cacheDir, "embed").walkTopDown().filter { it.isFile }.sumOf { it.length() },
    )
}
