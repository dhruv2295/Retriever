package app.retriever.core

import kotlinx.serialization.Serializable

/**
 * What the person made, kept in a file beside the index: an index reset (a deleted retriever.db) or a new phone
 * (device transfer, backup) keeps the settings and stars. Store writes it on every change and reads it back into a
 * database that has no settings yet.
 */
@Serializable
internal data class UserState(val prefs: Prefs? = null, val starred: List<StarredRow> = emptyList())

/** name, size and modifiedAt find the file again when its uri changes (Store.relinkStars). */
@Serializable
internal data class StarredRow(val uri: String, val starredAt: Long, val name: String, val size: Long, val modifiedAt: Long)

/** The snapshot's file. Each platform replaces it whole (temp file, then rename); [read] is null when there is none. */
class UserStateFile(internal val read: () -> String?, internal val write: (String) -> Unit)
