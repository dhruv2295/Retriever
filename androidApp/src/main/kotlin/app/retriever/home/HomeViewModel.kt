package app.retriever.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.access.AccessService
import app.retriever.core.ESTIMATE_CALIBRATED
import app.retriever.core.FileState
import app.retriever.core.HomeFacts
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ReadEstimate
import app.retriever.core.ReadingProgress
import app.retriever.core.Retriever
import app.retriever.core.Store
import app.retriever.core.YearMemory
import app.retriever.indexing.IndexService
import app.retriever.ui.Format
import app.retriever.ui.launchCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Year
import java.time.ZoneId
import java.time.ZonedDateTime

/** What Home and the search screen's kind chips and empty answers know about the phone's files. */
@Immutable
data class HomeUi(
    /** null until the store has answered. */
    val counts: Map<Kind, Long>? = null,
    val searchable: Long = 0L,
    val partlyRead: Long = 0L,
    val skipped: Long = 0L,
    val readByKind: Map<Kind, Long> = emptyMap(),
    val caughtUpDismissed: Boolean = false,
    val waiting: Long = 0L,
    val trial: Boolean = false,
    /** Files outside the first run's short read that nobody has chosen to read yet; 0 once the scope is everything. */
    val rest: Long = 0L,
    /** What reading [rest] takes; null until [ESTIMATE_CALIBRATED], so no hours are drawn before then. */
    val estimate: ReadEstimate? = null,
    /** Files still waiting to be read, by kind. */
    val pending: Map<Kind, Long> = emptyMap(),
    /** "Try searching" examples for the kinds that are fully read. */
    val suggestions: List<String> = emptyList(),
    val home: HomeFacts? = null,
    val paused: Boolean = false,
    /** The kinds whose insides are read; the rest are found by name. */
    val inside: Set<Kind> = Kind.entries.toSet(),
    /** Some access to files; false once it was taken away in Settings. */
    val hasAccess: Boolean = true,
    val starred: Int = 0,
    val setUp: Boolean = false,
    /** Past years' photos and videos on [memoriesDay], newest year first. */
    val memories: List<YearMemory> = emptyList(),
    val memoriesDay: LocalDate? = null,
    /** The person can hide On this day, here or in Status. */
    val showOnThisDay: Boolean = true,
) {
    val progress: ReadingProgress get() = ReadingProgress(searchable, waiting, skipped, partlyRead, rest, trial)

    fun withCounts(c: Store.Counts): HomeUi = copy(
        counts = c.byKind,
        readByKind = c.byKindState.filterKeys { it.second == FileState.INDEXED }.mapKeys { it.key.first },
        searchable = c.progress.searchable,
        partlyRead = c.progress.partlyRead,
        skipped = c.progress.skipped,
        waiting = c.progress.pending,
        rest = c.waiting,
        trial = c.trial,
        pending = c.byKindState.filterKeys { it.second == FileState.PENDING }.mapKeys { it.key.first },
    )

    val trialTitle: String? get() = if (trial && waiting > 0) "Finishing your trial · ${Format.count(waiting)} files left" else null

    /**
     * Where reading stands for [kinds] as a section's subtitle says it: "Ready", "1,200 to go", "Waiting", "Found by
     * name" for kinds whose insides aren't read; null when nothing waits at all.
     */
    fun readiness(vararg kinds: Kind): String? {
        val read = kinds.filter { it in inside && it != Kind.OTHER }
        if (read.isEmpty()) return "Found by name"
        if (waiting == 0L) return null
        val total = read.sumOf { counts?.get(it) ?: 0L }
        val left = read.sumOf { pending[it] ?: 0L }
        return when {
            left == 0L -> "Ready"
            left < total -> "${Format.count(left)} to go"
            else -> "Waiting"
        }
    }
}

/**
 * The caught-up pop, once ever. [due] while the first full read has not been celebrated; [done] marks it in the store,
 * which is why a later catch-up or a relaunch finds it spent. Home's status and Status's header ask the same pair.
 */
class Celebration(val due: suspend () -> Boolean = { false }, val done: () -> Unit = {})

internal fun ViewModel.celebrationOf(retriever: Retriever) = Celebration(
    due = { !retriever.celebrated() },
    done = { launchCatching { retriever.markCelebrated() } },
)

/** The years looked back over for [onThisDayRanges]; core stops at five with something and skips years before the oldest photo. */
private const val MEMORY_YEARS = 30

/** [date]'s whole local day as (start ms, end ms): 23 or 25 hours long where the clocks change. */
internal fun dayRange(date: LocalDate, zone: ZoneId): Pair<Long, Long> =
    date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

/** What [Retriever.onThisDay] takes: the same month and day in each past year, newest first; 29 February only in leap years. */
internal fun onThisDayRanges(today: LocalDate, zone: ZoneId): List<Triple<Int, Long, Long>> =
    (today.year - 1 downTo today.year - MEMORY_YEARS).filter { today.monthValue != 2 || today.dayOfMonth != 29 || Year.isLeap(it.toLong()) }.map {
        val (from, to) = dayRange(LocalDate.of(it, today.monthValue, today.dayOfMonth), zone)
        Triple(it, from, to)
    }

/** The lists that did not change are the old instances, so the strips showing them skip recomposing on a refresh. */
internal fun HomeFacts.reusing(old: HomeFacts?): HomeFacts = old?.let {
    copy(
        newMedia = if (newMedia == it.newMedia) it.newMedia else newMedia,
        newShots = if (newShots == it.newShots) it.newShots else newShots,
        newDocs = if (newDocs == it.newDocs) it.newDocs else newDocs,
        starred = if (starred == it.starred) it.starred else starred,
    )
} ?: this

class HomeViewModel(private val retriever: Retriever, private val access: AccessService, private val indexer: IndexService) : ViewModel() {
    /** The local clock; replaced in tests. */
    internal var now: suspend () -> ZonedDateTime = { withContext(Dispatchers.IO) { ZonedDateTime.now() } }

    private val today = MutableStateFlow<LocalDate?>(null)

    private val _ui =
        MutableStateFlow(
            HomeUi(
                hasAccess = access.state.value.ready,
                showOnThisDay = retriever.prefs.value.showOnThisDay,
                caughtUpDismissed = retriever.prefs.value.caughtUpDismissed,
            ),
        )
    val ui: StateFlow<HomeUi> = _ui.asStateFlow()
    val indexState: StateFlow<IndexState> = retriever.state

    /** One store subscription for every collector below. */
    private val counts = retriever.counts().shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    init {
        viewModelScope.launch { access.state.collect { a -> _ui.update { it.copy(hasAccess = a.ready) } } }
        viewModelScope.launch { retriever.setUp.collect { s -> _ui.update { it.copy(setUp = s == true) } } }
        viewModelScope.launch {
            counts.collect { c ->
                _ui.update { it.withCounts(c) }
            }
        }
        viewModelScope.launch {
            retriever.prefs.collect { p ->
                _ui.update {
                    it.copy(paused = p.paused, inside = retriever.readInside(), showOnThisDay = p.showOnThisDay, caughtUpDismissed = p.caughtUpDismissed)
                }
            }
        }
        // Once per local date, again only when the date changed while the app was away; not while hidden.
        viewModelScope.launch {
            combine(today.filterNotNull().distinctUntilChanged(), ui.map { it.showOnThisDay }.distinctUntilChanged()) { day, on -> day.takeIf { on } }
                .filterNotNull().distinctUntilChanged().collect { loadMemories(it) }
        }
        viewModelScope.launch { retriever.starred.collect { s -> _ui.update { it.copy(starred = s.size) } } }
        // At most one reload every 2 s while files are being counted, and always one after the last change.
        viewModelScope.launch {
            counts.conflate().collect { c ->
                launchCatching {
                    val estimate = if (ESTIMATE_CALIBRATED && c.waiting > 0) retriever.estimateAll() else null
                    val suggestions = retriever.suggestions()
                    retriever.home().let { h -> _ui.update { it.copy(home = h.reusing(it.home), estimate = estimate, suggestions = suggestions) } }
                }
                // Photos from past years can arrive today (a restored phone): look again while there are none.
                ui.value.let { u ->
                    if (u.showOnThisDay && u.memoriesDay != null && u.memoriesDay == today.value &&
                        u.memories.isEmpty()
                    ) {
                        loadMemories(u.memoriesDay)
                    }
                }
                delay(2_000)
            }
        }
    }

    private fun loadMemories(day: LocalDate) = launchCatching {
        val found = retriever.onThisDay(onThisDayRanges(day, now().zone))
        _ui.update { it.copy(memories = found, memoriesDay = day) }
    }

    /** Access can be taken away in Settings while the app is away, so Home asks again on every return; so does the date. */
    fun onResume() {
        access.refresh()
        launchCatching { today.value = now().toLocalDate() }
    }

    /** Long press on a year card. The switch in Status brings it back. */
    fun hideOnThisDay() {
        _ui.update { it.copy(showOnThisDay = false) }
        launchCatching { retriever.updatePrefs { it.copy(showOnThisDay = false) } }
    }

    fun dismissCaughtUp() = launchCatching { retriever.dismissCaughtUp() }

    val celebration = celebrationOf(retriever)

    /** "Read everything" on Home: the commit choice made after the first run; WorkManager reads on past this screen. */
    fun commit(whileCharging: Boolean) = launchCatching {
        retriever.commit(whileCharging)
        indexer.schedulePeriodic(whileCharging)
        indexer.indexNow()
    }

    /** "Index now": lifts a pause and starts a batch, which WorkManager runs past this screen. */
    fun resume() = launchCatching { indexer.resume() }
}
