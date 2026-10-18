package app.retriever.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

data class SyncResult(val added: Int, val changed: Int, val removed: Int, val fullScan: Boolean)

class LibrarySync(private val store: Store, private val source: FileSource, private val now: () -> Long) {
    /** File names in memory once [loadNames] ran; each sync then swaps in a new snapshot, so running searches keep theirs. */
    @Volatile
    var names: NameIndex? = null
        private set
    private val namesLock = Mutex()

    suspend fun loadNames() = namesLock.withLock {
        if (names == null) names = NameIndex.build(store.nameEntries())
    }

    suspend fun sync(forceFull: Boolean = false): SyncResult {
        val prefs = store.prefs()
        val token = store.meta(TOKEN)
        val lastFull = store.meta(LAST_FULL)?.toLongOrNull()
        val wantFull = forceFull || token == null || lastFull == null || now() - lastFull > DAY_MS ||
            prefs.excludedFolders != scannedExcludes() || store.meta(RULES) != source.rulesVersion.toString()

        val changes = source.changes(if (wantFull) null else token)
        // A full scan compares every file; an incremental one looks up only what changed, in one bulk read.
        val known = if (changes.isFullScan) {
            store.knownFiles()
        } else {
            store.filesByUris(changes.upserted.map { it.uri }).mapValues { it.value.file }
        }
        // Only files this scan could see may be removed: an unmounted volume's files wait for it to come back.
        val knownInScope by lazy {
            (if (changes.isFullScan) known.keys else store.knownUris())
                .filterTo(HashSet()) { uri -> changes.scope?.any { uri.startsWith(it) } ?: true }
        }
        // Sample mode never trims here: it only limits what IndexEngine reads (Store.pending), so nothing read is lost.
        val keep = changes.upserted.filter { !prefs.excludes(it.folder) }
        // SourceFile mirrors the stored columns, so equality is the change test: kind, mime and takenAt count too.
        val touched = keep.filter { known[it.uri] != it }
        val added = touched.count { it.uri !in known }

        val removed = if (changes.isFullScan) {
            knownInScope - keep.map { it.uri }.toSet() - changes.present.orEmpty()
        } else {
            changes.removed.toSet() +
                changes.upserted.filter { it.uri in known && prefs.excludes(it.folder) }.map { it.uri } +
                changes.present?.let { knownInScope - it }.orEmpty()
        }.toList()

        val excludedMeanwhile = namesLock.withLock {
            // A folder excluded during the scan was dropped already (dropExcluded): this scan would bring it back.
            if (store.prefs().excludedFolders != prefs.excludedFolders) return@withLock true
            store.upsertFiles(touched)
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
        // The token was not advanced, so the scan runs again under the new folders.
        if (excludedMeanwhile) return sync(forceFull)
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
            val uris = store.knownFiles().values.filter { prefs.excludes(it.folder) }.map { it.uri }
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
        store.removeFiles(uris)
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
        const val LAST_FULL = "last_full_scan_ms"
        const val EXCLUDES = "scan_excludes"
        const val RULES = "scan_rules_version"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
