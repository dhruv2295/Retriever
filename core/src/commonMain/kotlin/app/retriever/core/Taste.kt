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
 * the plan's pending files at this phone's measured pace in wall time, null until that is measured ([Words.sampleNote]).
 * [readyToTry]: [TRY_AFTER_FILES] read, or the plan done. [pending]: planned files still to read. A Name only or Couldn't
 * read file is done without being read, so this is not planned minus [read].
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
        /**
         * [planned] and [pending]: files per kind; [pendingVideoBytes] reads videos by size, as the ETA does. [wallRatio]:
         * [Eta.shownRatio], null before it is measured; the time also waits for every pending kind's pace.
         */
        fun of(
            planned: Map<Kind, Int>,
            pending: Map<Kind, Int>,
            pendingVideoBytes: Long,
            book: SpeedBook,
            searchable: Map<Kind, Int>,
            wallRatio: Double?,
        ): TasteProgress {
            val byKind = planned.mapValues { (k, n) -> (searchable[k] ?: 0) to n }
            val read = byKind.values.sumOf { it.first }
            val left = pending.values.sum()
            val pendingFiles = pending.mapValues { it.value.toLong() }
            return TasteProgress(
                byKind = byKind,
                readyKinds = planned.keys - pending.filterValues { it > 0 }.keys,
                read = read,
                secondsLeft = wallRatio?.takeIf { planned.isNotEmpty() && book.measures(pendingFiles) }
                    ?.let { ceil(book.ms(pendingFiles, pendingVideoBytes) * it / 1000).toInt() },
                readyToTry = read > 0 && (left == 0 || read >= TRY_AFTER_FILES),
                pending = left,
            )
        }
    }
}

/** A planned file. [index]: its plan position; [read]: its content is searchable; [note]: [Words.coverage], null when all of it is. */
data class SampleFile(val index: Int, val file: SourceFile, val read: Boolean, val note: String?)

// Adapted from google-ai-edge/gallery SmartAlbumScreen.kt (Apache-2.0): STACK_ANGLES and STACK_OFFSETS.

/** The sample's pile: each card's slot follows its plan index, so a card never moves once it lands. Offsets in dp. */
object SamplePile {
    const val SHOWN = 12
    const val CARD_DP = 168
    const val FOOTPRINT_DP = 224

    private val ANGLES = floatArrayOf(0f, 4f, 8f, 12f, 7f, 2f, -3f, -8f, -12f, -7f, -2f, 3f)
    private val DX = intArrayOf(0, 9, -9, 11, -8, 7, -11, 9, -9, 8, -7, 10)
    private val DY = intArrayOf(0, -7, -5, 6, 8, -9, -7, 8, 6, -8, -9, 7)

    fun angle(i: Int): Float = ANGLES[i % SHOWN]

    fun dx(i: Int): Int = DX[i % SHOWN]

    fun dy(i: Int): Int = DY[i % SHOWN]

    /** The cards on the pile: the last [SHOWN] read, in plan order. */
    fun shown(files: List<SampleFile>): List<SampleFile> = files.filter { it.read }.takeLast(SHOWN)
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

/**
 * The pending planned files to drop so the rest fits the budget: videos first, then the costliest, keeping one when
 * nothing is read yet (an over-budget file is still a trial). [ratio] is wall time per ms of inference this session
 * ([Eta.wallRatio]), given once every pending kind has a measured pace: then what is left, at [costs] × [ratio], must fit
 * [budgetMs] less the [wallMs] already spent. Without it, seeds price the rest, with 1.5× slack before anything goes.
 */
internal fun tasteDrops(
    pending: List<Store.StoredFile>,
    costs: Map<Long, Double>,
    budgetMs: Double,
    wallMs: Double,
    ratio: Double?,
    keepOne: Boolean,
): Set<Long> {
    var left = costs.values.sum()
    val target = if (ratio == null) budgetMs else (budgetMs - wallMs) / ratio
    if (left <= if (ratio == null) budgetMs * 1.5 else target) return emptySet()
    val minimum = if (keepOne) 1 else 0
    val drop = mutableSetOf<Long>()
    for (file in pending.sortedWith(compareByDescending<Store.StoredFile> { it.file.kind == Kind.VIDEO }.thenByDescending { costs.getValue(it.id) })) {
        if (left <= target || pending.size - drop.size <= minimum) break
        drop += file.id
        left -= costs.getValue(file.id)
    }
    return drop
}

/** Files per kind, and the bytes of the videos among them. */
internal fun List<Store.StoredFile>.byKind(): Pair<Map<Kind, Int>, Long> =
    groupingBy { it.file.kind }.eachCount() to sumOf { if (it.file.kind == Kind.VIDEO) it.file.size else 0L }
