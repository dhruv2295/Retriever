package app.retriever.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SyncResult(val added: Int, val changed: Int, val removed: Int, val fullScan: Boolean)

class LibrarySync(private val store: Store, private val source: FileSource, private val now: () -> Long) {
    suspend fun sync(forceFull: Boolean = false): SyncResult {
        val prefs = store.prefs()
        val token = store.meta(TOKEN)
        val lastFull = store.meta(LAST_FULL)?.toLongOrNull()
        val scannedExcludes = store.meta(EXCLUDES)
            ?.let { runCatching { Json.decodeFromString<Set<String>>(it) }.getOrNull() } ?: emptySet()
        val wantFull = forceFull || token == null || lastFull == null || now() - lastFull > DAY_MS ||
            prefs.excludedFolders != scannedExcludes || store.meta(RULES) != source.rulesVersion.toString() ||
            store.meta(SAMPLE) != prefs.sampleSize?.toString().orEmpty()

        val changes = source.changes(if (wantFull) null else token)
        // A full scan compares every file; an incremental one only looks up what changed.
        val known = if (changes.isFullScan) {
            store.knownFiles()
        } else {
            changes.upserted.mapNotNull { store.fileByUri(it.uri)?.file }.associateBy { it.uri }
        }
        // Only files this scan could see may be removed: an unmounted volume's files wait for it to come back.
        val knownInScope = store.knownUris().filterTo(HashSet()) { uri -> changes.scope?.any { uri.startsWith(it) } ?: true }
        val eligible = changes.upserted.filter { !prefs.excludes(it.folder) }
        // Sample mode trims on full scans only; files arriving later are the newest, so they belong anyway.
        val keep = prefs.sampleSize?.takeIf { changes.isFullScan }?.let { n ->
            eligible.groupBy { it.kind }.values.flatMap { group -> group.sortedByDescending { it.modifiedAt }.take(n) }
        } ?: eligible

        var added = 0
        var changed = 0
        for (f in keep) {
            val old = known[f.uri]
            if (old == null) {
                added++
            } else if (old.size != f.size || old.modifiedAt != f.modifiedAt || old.name != f.name || old.folder != f.folder) {
                changed++
            }
        }

        val removed = if (changes.isFullScan) {
            knownInScope - keep.map { it.uri }.toSet()
        } else {
            changes.removed.toSet() +
                changes.upserted.filter { it.uri in known && prefs.excludes(it.folder) }.map { it.uri } +
                changes.present?.let { knownInScope - it }.orEmpty()
        }

        store.upsertFiles(keep)
        store.removeFiles(removed.toList())
        store.setMeta(TOKEN, changes.nextToken)
        if (changes.isFullScan) {
            store.setMeta(LAST_FULL, now().toString())
            store.setMeta(EXCLUDES, Json.encodeToString(prefs.excludedFolders))
            store.setMeta(RULES, source.rulesVersion.toString())
            store.setMeta(SAMPLE, prefs.sampleSize?.toString().orEmpty())
        }
        return SyncResult(added, changed, removed.size, changes.isFullScan)
    }

    private companion object {
        const val TOKEN = "source_token"
        const val LAST_FULL = "last_full_scan_ms"
        const val EXCLUDES = "scan_excludes"
        const val RULES = "scan_rules_version"
        const val SAMPLE = "scan_sample_size"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
