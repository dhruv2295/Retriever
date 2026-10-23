package app.retriever.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException

interface ContentHasher {
    suspend fun quickHash(uri: String, size: Long): String?

    suspend fun fullHash(uri: String): String?
}

data class DuplicateGroup(val size: Long, val files: List<SourceFile>) {
    val wastedBytes: Long get() = size * (files.size - 1)
}

class DuplicateFinder(private val store: Store, private val hasher: ContentHasher) {
    companion object {
        /** Smaller files are never compared. */
        const val MIN_SIZE = 64L * 1024

        /** Files read at once: flash storage serves a few parallel reads faster than one at a time. */
        internal const val HASH_READS = 4
    }

    /** Only files that share a size are read, and only those that also share a quick hash are read in full. */
    suspend fun find(minSize: Long = MIN_SIZE): List<DuplicateGroup> {
        val collisions = store.sizeCollisions(minSize)
        val quick = hashes(collisions.flatten(), { it.quickHash }, store::setQuickHash) { hasher.quickHash(it.uri, it.size) }
        val candidates = collisions.flatMap { it.sameBy(quick) }
        val full = hashes(candidates.flatten(), { it.fullHash }, store::setFullHash) { hasher.fullHash(it.uri) }
        return candidates.flatMap { it.sameBy(full) }
            .map { same -> DuplicateGroup(same[0].file.size, same.map { it.file }.sortedBy { it.modifiedAt }) }
            .sortedByDescending { it.wastedBytes }
    }

    /** The groups of two or more of these files that share a hash; a file without one is in none. */
    private fun List<Store.StoredFile>.sameBy(hash: Map<Long, String>) = filter { it.id in hash }.groupBy { hash.getValue(it.id) }.values.filter { it.size > 1 }

    /**
     * Each file's hash by id: the one stored on its row, else read [HASH_READS] files at a time, and the new ones saved in
     * one transaction. A file whose read fails has none, and is read again next time.
     */
    private suspend fun hashes(
        files: List<Store.StoredFile>,
        stored: (Store.StoredFile) -> String?,
        save: (Long, String) -> Unit,
        read: suspend (SourceFile) -> String?,
    ): Map<Long, String> {
        val known = files.mapNotNull { f -> stored(f)?.let { f.id to it } }
        val queue = Channel<Store.StoredFile>(Channel.UNLIMITED)
        files.filter { stored(it) == null }.forEach { queue.trySend(it) }
        queue.close()
        // A few readers take the next file as each finishes, so one long full hash holds up only its own reader.
        val fresh = coroutineScope {
            List(HASH_READS) { async { buildList { for (f in queue) safe { read(f.file) }?.let { add(f.id to it) } } } }.awaitAll().flatten()
        }
        if (fresh.isNotEmpty()) store.inTransaction { for ((id, hash) in fresh) save(id, hash) }
        return (known + fresh).toMap()
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
