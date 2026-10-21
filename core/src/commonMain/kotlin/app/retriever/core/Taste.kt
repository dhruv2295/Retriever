package app.retriever.core

import kotlinx.serialization.json.Json
import kotlin.math.ceil

/**
 * What the index reads: the first run's planned files only ([SAMPLE]) or everything ([ALL]). Kept in Store meta, not
 * Prefs: a Prefs write before the first tune would cost a slow phone its tier defaults (onboarding §3.1).
 */
enum class Scope { SAMPLE, ALL }

/**
 * The demo's progress, by the one count rule: [done] of [planned] files processed, and the bar is exactly done / planned.
 * [planned] is fixed when the files are picked; a file found by name only, one Retriever couldn't open, and one deleted
 * since all count as done. [byKind]: (read, planned) per kind, for a screen reader; [readyKinds]: kinds with nothing
 * left; [read]: files whose insides are searchable; [pending]: files still waiting; [secondsLeft]: the pending files at
 * this phone's measured pace in wall time, null until that is measured ([Words.demoNote]).
 */
data class TasteProgress(
    val byKind: Map<Kind, Pair<Int, Int>>,
    val readyKinds: Set<Kind>,
    val read: Int,
    val secondsLeft: Int?,
    val pending: Int,
    val planned: Int,
) {
    val done: Int get() = planned - pending

    internal companion object {
        /**
         * [total]: the plan's size; [planned] and [pending]: files per kind; [pendingVideoBytes] reads videos by size, as the
         * ETA does. [wallRatio]: [Eta.shownRatio], null before a share of the budget is read; the time also waits for every
         * pending kind's pace.
         */
        fun of(
            total: Int,
            planned: Map<Kind, Int>,
            pending: Map<Kind, Int>,
            pendingVideoBytes: Long,
            book: SpeedBook,
            searchable: Map<Kind, Int>,
            wallRatio: Double?,
        ): TasteProgress {
            val byKind = planned.mapValues { (k, n) -> (searchable[k] ?: 0) to n }
            val pendingFiles = pending.mapValues { it.value.toLong() }
            return TasteProgress(
                byKind = byKind,
                readyKinds = planned.keys - pending.filterValues { it > 0 }.keys,
                read = byKind.values.sumOf { it.first },
                secondsLeft = wallRatio?.takeIf { planned.isNotEmpty() && book.measures(pendingFiles) }
                    ?.let { ceil(book.ms(pendingFiles, pendingVideoBytes) * it / 1000).toInt() },
                pending = pending.values.sum(),
                planned = total,
            )
        }
    }
}

/** A planned file. [index]: its plan position; [read]: its content is searchable; [note]: [Words.coverage], null when all of it is. */
data class SampleFile(val index: Int, val file: SourceFile, val read: Boolean, val note: String?)

// Adapted from google-ai-edge/gallery SmartAlbumScreen.kt (Apache-2.0): STACK_ANGLES and STACK_OFFSETS.

/** The demo's pile: each card's slot follows its plan index, so a card never moves once it lands. Offsets in dp. */
object SamplePile {
    const val SHOWN = 3
    const val CARD_DP = 96
    const val FOOTPRINT_DP = 128

    private val ANGLES = floatArrayOf(0f, 4f, -4f)
    private val DX = intArrayOf(0, 6, -6)
    private val DY = intArrayOf(0, -4, 4)

    fun angle(i: Int): Float = ANGLES[i % SHOWN]

    fun dx(i: Int): Int = DX[i % SHOWN]

    fun dy(i: Int): Int = DY[i % SHOWN]

    /** The cards on the pile: the last [SHOWN] read, in plan order. */
    fun shown(files: List<SampleFile>): List<SampleFile> = files.filter { it.read }.takeLast(SHOWN)
}

/** The demo's size: [FileRules.planTaste] picks at most this many, which also fit its time budget. */
const val DEMO_FILES = 30

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
