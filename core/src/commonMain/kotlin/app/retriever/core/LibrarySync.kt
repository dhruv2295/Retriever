package app.retriever.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

data class SyncResult(val added: Int, val changed: Int, val removed: Int, val fullScan: Boolean)

/** Written once a full scan's files are all in the store: from then on the counts cover the whole phone. */
internal const val LAST_FULL_SCAN_KEY = "last_full_scan_ms"

class LibrarySync(private val store: Store, private val source: FileSource, private val now: () -> Long) {
    /** File names in memory once [loadNames] ran; each sync then swaps in a new snapshot, so running searches keep theirs. */
    @Volatile
    var names: NameIndex? = null
        private set
    private val namesLock = Mutex()

    suspend fun loadNames() = namesLock.withLock {
        if (names == null) names = NameIndex.build(store.nameEntries())
    }

    /** Held from the scan to the last write, so no second scan slips into a running sync's tail. */
    private val syncLock = Mutex()

    /**
     * [first], on a full scan only: gets every file the scan keeps before any is written, to write the few it needs at
     * once (the sample's plan). The rest follow [WRITE_ROWS] at a time, so a reader's writes interleave with them.
     */
    suspend fun sync(forceFull: Boolean = false, first: (suspend (List<SourceFile>) -> Unit)? = null): SyncResult =
        syncLock.withLock { syncLocked(forceFull, first) }

    /** Null when a sync is running already: it covers the caller, who need not wait for its tail. */
    suspend fun syncUnlessRunning(): SyncResult? {
        if (!syncLock.tryLock()) return null
        return try {
            syncLocked(false, null)
        } finally {
            syncLock.unlock()
        }
    }

    private suspend fun syncLocked(forceFull: Boolean, first: (suspend (List<SourceFile>) -> Unit)?): SyncResult {
        val prefs = store.prefs()
        val token = store.meta(TOKEN)
        val lastFull = store.meta(LAST_FULL)?.toLongOrNull()
        val wantFull = forceFull || token == null || lastFull == null || now() - lastFull > DAY_MS ||
            prefs.excludedFolders != scannedExcludes() || store.meta(RULES) != source.rulesVersion.toString()

        val changes = source.changes(if (wantFull) null else token)
        // Only files this scan could see may be removed: an unmounted volume's files wait for it to come back.
        val inScope = { uri: String -> changes.scope?.any { uri.startsWith(it) } ?: true }
        // Sample mode never trims here: it only limits what IndexEngine reads (Store.pending), so nothing read is lost.
        val keep = changes.upserted.filter { !prefs.excludes(it.folder) }
        val touched = ArrayList<SourceFile>()
        var added = 0
        val removed = if (changes.isFullScan) {
            // A full scan compares every file against a fingerprint of its stored row, not a second copy of the library.
            // Each file the scan keeps leaves the map; what is left is gone, or excluded now.
            val known = store.knownFingerprints()
            for (f in keep) {
                val stored = known.remove(f.uri)
                if (stored == null) added++
                if (stored != f.fingerprint()) touched += f
            }
            val present = changes.present.orEmpty()
            known.keys.filter { inScope(it) && it !in present }
        } else {
            // An incremental one looks up only what changed, in one bulk read. SourceFile mirrors the stored columns, so
            // equality is the change test: kind, mime and takenAt count too.
            val known = store.filesByUris(changes.upserted.map { it.uri }).mapValues { it.value.file }
            keep.filterTo(touched) { known[it.uri] != it }
            added = touched.count { it.uri !in known }
            val excluded = changes.upserted.filter { it.uri in known && prefs.excludes(it.folder) }.map { it.uri }
            // Read a page at a time, so only the vanished URIs are held beside the source's.
            val vanished = changes.present?.let { present -> store.knownUris { inScope(it) && it !in present } }.orEmpty()
            (changes.removed.toSet() + excluded + vanished).toList()
        }

        if (changes.isFullScan) first?.invoke(keep)
        val excludedMeanwhile = namesLock.withLock {
            // A folder excluded during the scan was dropped already (dropExcluded): this scan would bring it back.
            if (store.prefs().excludedFolders != prefs.excludedFolders) return@withLock true
            // Rows [first] wrote are unchanged now, so their upsert writes nothing.
            touched.chunked(WRITE_ROWS).forEach(store::upsertFiles)
            // An incremental sync is small, and a full scan rebuilds the names instead.
            val removedIds = removeLocked(removed, needIds = !changes.isFullScan)
            // A starred file can only come back under a new uri, so syncs that add nothing skip the search.
            if (added > 0) store.relinkStars()
            val current = names
            if (current != null && (touched.isNotEmpty() || removed.isNotEmpty())) {
                names = if (changes.isFullScan) {
                    NameIndex.build(store.nameEntries())
                } else {
                    current.update(removedIds, store.nameEntries(touched.map { it.uri }))
                }
            }
            false
        }
        // The token was not advanced, so the scan runs again under the new folders; [first] has had its files.
        if (excludedMeanwhile) return syncLocked(forceFull, null)
        putMeta(TOKEN, changes.nextToken)
        if (changes.isFullScan) {
            putMeta(LAST_FULL, now().toString())
            putMeta(EXCLUDES, Json.encodeToString(prefs.excludedFolders))
            putMeta(RULES, source.rulesVersion.toString())
        }
        return SyncResult(added, touched.size - added, removed.size, changes.isFullScan)
    }

    suspend fun rename(uri: String, name: String, newUri: String, modifiedAt: Long): SourceFile? = namesLock.withLock {
        val file = store.renameFile(uri, name, newUri, modifiedAt) ?: return@withLock null
        names = names?.update(emptyList(), store.nameEntries(listOf(newUri)))
        file
    }

    suspend fun remove(uris: List<String>) = namesLock.withLock {
        val ids = removeLocked(uris)
        names = names?.update(ids, emptyList())
    }

    /** Removes the files in folders [prefs] excludes at once, with no scan, so it works while paused. Returns how many. */
    suspend fun dropExcluded(prefs: Prefs): Int {
        val gone = namesLock.withLock {
            val uris = prefs.excludedFolders.flatMap(store::urisInFolder).distinct()
            // Not inside `names?.update(...)`: a safe call skips its arguments, so nothing was removed before names loaded.
            val ids = removeLocked(uris)
            names = names?.update(ids, emptyList())
            uris.size
        }
        // The store now holds what a scan under both sets would, so only a folder put back later forces a full scan.
        store.setMeta(EXCLUDES, Json.encodeToString(scannedExcludes() + prefs.excludedFolders))
        return gone
    }

    /**
     * The one way files leave, under [namesLock]. Returns their ids, read before the rows go, for patching [names].
     * Stars stay: a file restored from the trash, or back from an excluded folder, comes back starred.
     */
    private fun removeLocked(uris: List<String>, needIds: Boolean = true): List<Long> {
        if (uris.isEmpty()) return emptyList()
        val ids = if (needIds && names != null) store.fileIds(uris) else emptyList()
        // In parts, as the upserts are, so a reader's write waits for one part instead of the whole removal.
        uris.chunked(WRITE_ROWS).forEach(store::removeFiles)
        return ids
    }

    /** An unchanged sync writes nothing. */
    private fun putMeta(key: String, value: String) {
        if (store.meta(key) != value) store.setMeta(key, value)
    }

    /** The excluded folders the store reflects: the last full scan's, plus any dropped since. */
    private fun scannedExcludes(): Set<String> = store.meta(EXCLUDES)?.let { runCatching { Json.decodeFromString<Set<String>>(it) }.getOrNull() } ?: emptySet()

    private companion object {
        const val TOKEN = "source_token"
        const val LAST_FULL = LAST_FULL_SCAN_KEY
        const val EXCLUDES = "scan_excludes"
        const val RULES = "scan_rules_version"
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** Rows per write transaction of a sync: SQLite's own transactions, sized so a reader's write waits for one. */
        const val WRITE_ROWS = 1_000
    }
}

/**
 * The columns [SourceFile] compares besides its uri, as one 64-bit FNV-1a hash over UTF-16 units and whole longs. Each
 * step is a bijection, so files that differ in one field's value at one place never collide; any other pair does with
 * odds of about 2^-64.
 */
internal fun SourceFile.fingerprint(): Long {
    // FNV-1a's 64-bit offset basis (0xcbf29ce484222325) and prime.
    val prime = 0x100000001b3L
    var h = -0x340d631b7bdddcdbL
    for (s in arrayOf(name, folder, mime, kind.name)) {
        h = (h xor s.length.toLong()) * prime
        for (c in s) h = (h xor c.code.toLong()) * prime
    }
    for (v in longArrayOf(size, modifiedAt, if (takenAt == null) 0L else 1L, takenAt ?: 0L)) h = (h xor v) * prime
    return h
}
