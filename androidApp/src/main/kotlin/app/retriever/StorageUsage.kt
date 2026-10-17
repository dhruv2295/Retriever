package app.retriever

import android.content.Context
import app.retriever.core.ModelBuild
import app.retriever.core.Store
import java.io.File

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
