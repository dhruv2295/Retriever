package app.retriever.core

import kotlinx.serialization.json.Json
import kotlin.math.ceil

/**
 * What the index reads: the first run's planned files only ([SAMPLE]) or everything ([ALL]). Kept in Store meta, not
 * Prefs: a Prefs write before the first tune would cost a slow phone its tier defaults (onboarding §3.1).
 */
enum class Scope { SAMPLE, ALL }

/**
 * The short read's progress. [byKind]: (read, planned) per kind; [readyKinds]: kinds with nothing left to read; [secondsLeft]:
 * the plan's pending files at this phone's pace, null with no plan. [readyToTry]: [TRY_AFTER_FILES] read, or the plan done.
 * [pending]: planned files still to read. A Name only or Couldn't read file is done without being read, so this is not
 * planned minus [read].
 */
data class TasteProgress(
    val byKind: Map<Kind, Pair<Int, Int>>,
    val readyKinds: Set<Kind>,
    val read: Int,
    val secondsLeft: Int?,
    val readyToTry: Boolean,
    val pending: Int = 0,
) {
    internal companion object {
        /** [planned] and [pending]: files per kind; [pendingVideoBytes] reads videos by size, as the ETA does. */
        fun of(planned: Map<Kind, Int>, pending: Map<Kind, Int>, pendingVideoBytes: Long, book: SpeedBook, searchable: Map<Kind, Int>): TasteProgress {
            val byKind = planned.mapValues { (k, n) -> (searchable[k] ?: 0) to n }
            val read = byKind.values.sumOf { it.first }
            val left = pending.values.sum()
            return TasteProgress(
                byKind = byKind,
                readyKinds = planned.keys - pending.filterValues { it > 0 }.keys,
                read = read,
                secondsLeft = if (planned.isEmpty()) null else ceil(book.ms(pending.mapValues { it.value.toLong() }, pendingVideoBytes) / 1000).toInt(),
                readyToTry = read > 0 && (left == 0 || read >= TRY_AFTER_FILES),
                pending = left,
            )
        }
    }
}

/** Files read before "Try it now" shows, unless the plan is smaller. */
const val TRY_AFTER_FILES = 20

internal const val TASTE_SCOPE_KEY = "taste_scope"
internal const val TASTE_BUDGET_KEY = "taste_budget_ms"
internal const val TASTE_IDS_KEY = "taste_ids"
internal const val TASTE_DECIDED_KEY = "taste_decided"
internal const val CELEBRATED_KEY = "celebrated"

private val JSON = Json { ignoreUnknownKeys = true }

/** No key reads as ALL, so an install set up before the first run existed never sees it. */
internal fun Store.tasteScope(): Scope = if (meta(TASTE_SCOPE_KEY) == Scope.SAMPLE.name) Scope.SAMPLE else Scope.ALL

/** The planned file ids in read order, empty with no plan. */
internal fun Store.tasteIds(): List<Long> = meta(TASTE_IDS_KEY)?.let { runCatching { JSON.decodeFromString<List<Long>>(it) }.getOrNull() }.orEmpty()

/** The planned ids while the scope is SAMPLE; null reads everything. */
internal fun Store.sampleIds(): List<Long>? = if (tasteScope() == Scope.SAMPLE) tasteIds() else null

internal fun Store.setTaste(ids: List<Long>) = inTransaction {
    setMeta(TASTE_IDS_KEY, JSON.encodeToString(ids))
    setMeta(TASTE_SCOPE_KEY, Scope.SAMPLE.name)
}

internal fun Store.flag(key: String): Boolean = meta(key) != null

internal fun Store.setFlag(key: String) = setMeta(key, "1")

/** Files per kind, and the bytes of the videos among them. */
internal fun List<Store.StoredFile>.byKind(): Pair<Map<Kind, Int>, Long> =
    groupingBy { it.file.kind }.eachCount() to sumOf { if (it.file.kind == Kind.VIDEO) it.file.size else 0L }
