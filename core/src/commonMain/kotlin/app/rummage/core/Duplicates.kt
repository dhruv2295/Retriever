package app.rummage.core

import kotlin.coroutines.cancellation.CancellationException

interface ContentHasher {
    suspend fun quickHash(uri: String, size: Long): String?

    suspend fun fullHash(uri: String): String?
}

data class DuplicateGroup(val size: Long, val files: List<SourceFile>) {
    val wastedBytes: Long get() = size * (files.size - 1)
}

class DuplicateFinder(private val store: Store, private val hasher: ContentHasher) {
    suspend fun find(
        minSize: Long = 64L * 1024,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<DuplicateGroup> {
        val collisions = store.sizeCollisions(minSize)
        val total = collisions.sumOf { it.size }
        var done = 0
        val result = mutableListOf<DuplicateGroup>()
        for (group in collisions) {
            val byQuick = mutableMapOf<String, MutableList<Store.StoredFile>>()
            for (f in group) {
                val h = safe { hasher.quickHash(f.file.uri, f.file.size) }
                if (h != null) byQuick.getOrPut(h) { mutableListOf() }.add(f)
                onProgress(++done, total)
            }
            for (same in byQuick.values.filter { it.size > 1 }) {
                val byFull = mutableMapOf<String, MutableList<SourceFile>>()
                for (f in same) {
                    val h = safe { hasher.fullHash(f.file.uri) } ?: continue
                    byFull.getOrPut(h) { mutableListOf() }.add(f.file)
                }
                for (files in byFull.values.filter { it.size > 1 }) {
                    result += DuplicateGroup(files[0].size, files.sortedBy { it.modifiedAt })
                }
            }
        }
        return result.sortedByDescending { it.wastedBytes }
    }

    // A hasher bug on one file must not abort the scan; cancellation still propagates.
    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
