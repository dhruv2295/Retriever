package app.retriever.home

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.core.HomeFacts
import app.retriever.core.Kind
import app.retriever.core.Person
import app.retriever.core.ReadingProgress
import app.retriever.core.ReadingSchedule
import app.retriever.core.ReadingStatus
import app.retriever.core.Retriever
import app.retriever.core.SamePerson
import app.retriever.core.Stage
import app.retriever.core.StatusAction
import app.retriever.core.Store
import app.retriever.core.YearMemory
import app.retriever.indexing.IndexService
import app.retriever.ui.launchCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
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
    val progress: ReadingProgress = ReadingProgress(),
    val readingStatus: ReadingStatus? = null,
    val caughtUpDismissed: Boolean = false,
    /** core's [app.retriever.core.Prefs.stagesDone]: after the first run, Home's status line stays quiet for new files. */
    val stagesDone: Set<Stage>? = null,
    /** Words from the person's own files for the search bar's "Try:"; null until core has answered (the view model's start). */
    val suggestions: List<String>? = emptyList(),
    val home: HomeFacts? = null,
    /** Some access to files; false once it was taken away in Settings. */
    val hasAccess: Boolean = true,
    val starred: Int = 0,
    /** Setup's access step is done: core's [app.retriever.core.SetupInputs.libraryChosen]. */
    val libraryChosen: Boolean = false,
    /** Past years' photos and videos on [memoriesDay], newest year first. */
    val memories: List<YearMemory> = emptyList(),
    val memoriesDay: LocalDate? = null,
    /** The person can hide On this day, here or in Status. */
    val showOnThisDay: Boolean = true,
    /** Setup just finished: the one-time card about what happens next, core's [app.retriever.core.Prefs.homeIntroDue]. */
    val introDue: Boolean = false,
    /** The card distinguishes this active scan from the saved automatic policy. */
    val automaticReading: ReadingSchedule = ReadingSchedule.CHARGING,
    val sessionActive: Boolean = false,
    /** Setup's first search's files, core's [app.retriever.core.SetupInputs.planned]. */
    val planned: Long = 0,
    /** Grouped faces, most photos first; empty while Recognize people is off. */
    val people: List<Person> = emptyList(),
    /** Photos still to look at for faces. */
    val facesLeft: Long = 0,
    /** Pairs of people who may be one, closest first, for Home to ask about one at a time. */
    val samePeople: List<SamePerson> = emptyList(),
    /** People is off and photos are read: the one-time card offers it. */
    val peopleOffer: Boolean = false,
    val notificationsOfferDismissed: Boolean = false,
    /** What Retriever may read; [Access.ALL] until the service answers, so nothing offers more on a guess. */
    val access: Access = Access.ALL,
    val widenAccessOfferDismissed: Boolean = false,
) {
    fun withCounts(c: Store.Counts): HomeUi = copy(counts = c.byKind, progress = c.progress)

    /**
     * Every source of Home's sections has answered once: the facts, and On this day where it can show.
     * The sections draw together, in their fixed order, so none is inserted later above another.
     */
    val sectionsReady: Boolean get() = home != null && (!showOnThisDay || !hasAccess || memoriesDay != null)
}

internal fun HomeUi.showNotificationsOffer(sdk: Int, notificationsOn: Boolean, justAsked: Boolean): Boolean =
    sdk >= 33 && !notificationsOn && !notificationsOfferDismissed && !justAsked &&
        (progress.unfinished || progress.waiting > 0 || facesLeft > 0)

/**
 * "Search your whole phone" inside the done card: everything granted is processed ([status] says All done), and allowing
 * all files would find more. Not under the one-time card, which says what happens next.
 */
internal fun HomeUi.showWidenAccess(status: ReadingStatus): Boolean =
    status.action == StatusAction.DISMISS && access.partial && !widenAccessOfferDismissed && !introDue

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

/** Unchanged facts are the old instance, so the strips showing them skip recomposing on a refresh. */
internal fun HomeFacts.reusing(old: HomeFacts?): HomeFacts = if (this == old) old else this

class HomeViewModel(
    private val retriever: Retriever,
    private val access: AccessService,
    private val indexer: IndexService,
    private val appScope: CoroutineScope,
) : ViewModel() {
    /** The local clock; replaced in tests. */
    internal var now: suspend () -> ZonedDateTime = { withContext(Dispatchers.IO) { ZonedDateTime.now() } }

    private val today = MutableStateFlow<LocalDate?>(null)

    private val _ui =
        MutableStateFlow(
            HomeUi(
                hasAccess = access.state.value.ready,
                access = access.state.value,
                showOnThisDay = retriever.prefs.value.showOnThisDay,
                caughtUpDismissed = retriever.prefs.value.caughtUpDismissed,
                stagesDone = retriever.prefs.value.stagesDone,
                introDue = retriever.prefs.value.homeIntroDue,
                automaticReading = retriever.prefs.value.automaticReading,
                sessionActive = retriever.prefs.value.started,
                notificationsOfferDismissed = retriever.prefs.value.notificationsOfferDismissed,
                widenAccessOfferDismissed = retriever.prefs.value.widenAccessOfferDismissed,
                suggestions = null,
            ),
        )

    /**
     * Core's library flows run only while a screen collects [ui] (and five seconds past, for a rotation), not while Home
     * waits in the back stack behind another screen.
     */
    private val library = channelFlow<Nothing> {
        launch { retriever.readingStatus().collect { s -> _ui.update { it.copy(readingStatus = s) } } }
        launch { retriever.counts().collect { c -> _ui.update { it.withCounts(c) } } }
        launch { retriever.suggestions().collect { s -> _ui.update { it.copy(suggestions = s) } } }
        // The faces pass tells people() after each photo it groups, so the count to go follows it.
        launch {
            retriever.people().collect { p ->
                val left = if (retriever.prefs.value.recognizePeople) runCatching { retriever.facesProgress().pending }.getOrDefault(0L) else 0L
                _ui.update { it.copy(people = p, facesLeft = left) }
            }
        }
        launch { retriever.samePeople().collect { q -> _ui.update { it.copy(samePeople = q) } } }
        // Core's facts follow files coming, going and changing, never a file being read.
        launch {
            retriever.home().collect { h ->
                _ui.update { it.copy(home = h.reusing(it.home)) }
                // Photos from past years can arrive today (a restored phone): look again while there are none.
                _ui.value.let { u ->
                    if (u.showOnThisDay && u.memoriesDay != null && u.memoriesDay == today.value && u.memories.isEmpty()) loadMemories(u.memoriesDay)
                }
            }
        }
    }
    val ui: StateFlow<HomeUi> = merge(_ui, library).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), _ui.value)

    /** Whether reading runs, not each file's tick of [Retriever.state]: Home uses nothing else of it. */
    val indexRunning: StateFlow<Boolean> = retriever.state.map {
        it.running
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), retriever.state.value.running)

    init {
        viewModelScope.launch { access.state.collect { a -> _ui.update { it.copy(hasAccess = a.ready, access = a) } } }
        viewModelScope.launch {
            combine(retriever.setup.filterNotNull(), access.state) { s, a -> s.withAccess(a.ready) }.collect { s ->
                _ui.update { it.copy(libraryChosen = s.libraryChosen, planned = s.planned.toLong()) }
            }
        }
        viewModelScope.launch {
            retriever.prefs.collect { p ->
                _ui.update {
                    it.copy(
                        showOnThisDay = p.showOnThisDay,
                        caughtUpDismissed = p.caughtUpDismissed,
                        stagesDone = p.stagesDone,
                        introDue = p.homeIntroDue,
                        automaticReading = p.automaticReading,
                        sessionActive = p.started,
                        peopleOffer = !p.recognizePeople && Kind.PHOTO in p.kinds,
                        notificationsOfferDismissed = p.notificationsOfferDismissed,
                        widenAccessOfferDismissed = p.widenAccessOfferDismissed,
                    )
                }
            }
        }
        // Once per local date, again only when the date changed while the app was away; not while hidden.
        viewModelScope.launch {
            combine(today.filterNotNull().distinctUntilChanged(), _ui.map { it.showOnThisDay }.distinctUntilChanged()) { day, on -> day.takeIf { on } }
                .filterNotNull().distinctUntilChanged().collect { loadMemories(it) }
        }
        viewModelScope.launch { retriever.starred.collect { s -> _ui.update { it.copy(starred = s.size) } } }
    }

    /** A failed look still answers (with none), so Home's sections never wait on it. */
    private fun loadMemories(day: LocalDate) = launchCatching(onError = {
        Log.e("Retriever", "on this day failed", it)
        _ui.update { u -> u.copy(memories = emptyList(), memoriesDay = day) }
    }) {
        val found = retriever.onThisDay(onThisDayRanges(day, now().zone))
        _ui.update { it.copy(memories = found, memoriesDay = day) }
    }

    /** Access can be taken away in Settings while the app is away, so Home asks again on every return; so does the date. */
    fun onResume() {
        access.refresh()
        launchCatching { today.value = now().toLocalDate() }
    }

    /** Long press on a year card; the Undo, or the switch in Status, brings it back. */
    fun hideOnThisDay() = setShowOnThisDay(false)

    fun showOnThisDay() = setShowOnThisDay(true)

    private fun setShowOnThisDay(on: Boolean) {
        _ui.update { it.copy(showOnThisDay = on) }
        launchCatching { retriever.updatePrefs { it.copy(showOnThisDay = on) } }
    }

    fun dismissCaughtUp() = launchCatching { retriever.dismissCaughtUp() }

    /** The one-time card's Notify me: one notice once everything is searchable. */
    fun requestCaughtUpNotice() = launchCatching { retriever.requestCaughtUpNotice() }

    /** The one-time card's Got it. */
    fun dismissIntro() = launchCatching { retriever.dismissHomeIntro() }

    fun dismissStageNote() = launchCatching { retriever.dismissStageNote() }

    /** People's "Same person?": that pair hidden at once, then merged or never asked again. */
    fun answerSamePerson(question: SamePerson, same: Boolean) {
        _ui.update { it.copy(samePeople = it.samePeople - question) }
        launchCatching { retriever.answerSamePerson(question, same) }
    }

    /** The one-time card's Turn on, as Settings' switch: People appears at once, saying how many photos are left to look at. */
    fun turnOnPeople() = launchCatching {
        retriever.updatePrefs { it.copy(recognizePeople = true) }
        indexer.indexNow()
    }

    fun dismissNotificationsOffer() {
        _ui.update { it.copy(notificationsOfferDismissed = true) }
        launchCatching { retriever.updatePrefs { it.copy(notificationsOfferDismissed = true) } }
    }

    /** Not now on the done card's offer to allow all files: gone for good. */
    fun dismissWidenAccess() {
        _ui.update { it.copy(widenAccessOfferDismissed = true) }
        launchCatching { retriever.dismissWidenAccessOffer() }
    }

    val celebration = celebrationOf(retriever)

    /** The status line's Start processing and Resume. */
    fun readNow() = appScope.launchCatching { indexer.start() }

    fun stopReadNow() = appScope.launchCatching { indexer.pause() }
}
