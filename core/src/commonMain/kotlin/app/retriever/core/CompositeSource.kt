package app.retriever.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Several sources as one, keyed by the URI prefix each owns: "ph://" and "folder://" on iOS; "content://media/"
 * (MediaStore) and "content://com.android.externalstorage.documents/" (picked folders) on Android. The token holds
 * each child's own token. Presence and scope are reported only for children that listed everything they see, so
 * LibrarySync removes vanished files only there: an incremental Photos sync never deletes folder files.
 */
class CompositeSource(private val sources: Map<String, FileSource>) : FileSource {
    override val rulesVersion: Int get() = sources.values.sumOf { it.rulesVersion }

    override suspend fun changes(since: String?): Changes {
        val tokens = since?.let { runCatching { Json.decodeFromString<Map<String, String>>(it) }.getOrNull() }.orEmpty()
        val results = sources.mapValues { (prefix, source) ->
            source.changes(if (since == null) null else tokens[prefix]).also { c ->
                (c.upserted.map { it.uri } + c.removed).forEach { uri ->
                    require(uri.startsWith(prefix)) { "$uri is outside its source's prefix $prefix" }
                }
            }
        }
        val token = Json.encodeToString(results.mapValues { it.value.nextToken })
        val upserted = results.values.flatMap { it.upserted }
        if (since == null) {
            val scope = results.flatMapTo(HashSet()) { (prefix, c) -> c.scope ?: setOf(prefix) }
            val kept = results.values.flatMapTo(HashSet()) { it.present.orEmpty() }
            return Changes(upserted, emptyList(), token, isFullScan = true, present = kept.ifEmpty { null }, scope = scope)
        }

        val presence = results.mapNotNull { (prefix, c) ->
            val seen = if (c.isFullScan) c.upserted.mapTo(HashSet()) { it.uri } + c.present.orEmpty() else c.present ?: return@mapNotNull null
            (c.scope ?: setOf(prefix)) to seen
        }
        return Changes(
            upserted = upserted,
            removed = results.values.flatMap { it.removed }.distinct(),
            nextToken = token,
            isFullScan = false,
            present = presence.takeIf { it.isNotEmpty() }?.flatMapTo(HashSet()) { it.second },
            scope = presence.takeIf { it.isNotEmpty() }?.flatMapTo(HashSet()) { it.first },
        )
    }
}
