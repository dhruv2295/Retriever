package app.retriever.search

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.core.FileState
import app.retriever.core.Kind
import app.retriever.core.Question
import app.retriever.core.ReadExtent
import app.retriever.core.Retriever
import app.retriever.core.SearchEngine
import app.retriever.core.SearchPhase
import app.retriever.core.SearchTiming
import app.retriever.core.SourceFile
import app.retriever.core.UpgradeStage
import app.retriever.core.Words
import app.retriever.core.android.ImageBytes
import app.retriever.core.isStale
import app.retriever.core.midWord
import app.retriever.core.onFull
import app.retriever.core.onNames
import app.retriever.core.showsEmpty
import app.retriever.nav.SearchRoute
import app.retriever.ui.components.DocPage
import app.retriever.ui.launchCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

/** A photo to search by; [uri] names it in the [Question]. Not a data class: equality is identity, so each pick searches. */
@Immutable
class Similar(val uri: String, val label: String, val thumb: ImageBitmap? = null)

/** Search input owned by the ViewModel; the UI receives only [similar], whose thumbnail is never modified. */
class PickedPhoto(val similar: Similar, val jpeg: ByteArray)

data class SearchUi(
    val query: String = "",
    val chip: Kind? = null,
    val similar: Similar? = null,
    /** What the results area shows: "nothing found" only when [SearchPhase.showsEmpty] for [question]. */
    val phase: SearchPhase = SearchPhase.Idle,
    /** Counts the answers to new questions, so the screen starts each at its top; a better answer to the same one doesn't. */
    val answer: Int = 0,
    /** Files in the answer that were not read whole. */
    val extents: Map<Long, ReadExtent> = emptyMap(),
    /** This phone finds files by their names and words only. */
    val textOnly: Boolean = false,
    /** A pass for the question is running. A words-only answer waiting to be asked again is not, so its hits stay undimmed. */
    val searching: Boolean = false,
    val vectorLane: Boolean = true,
    /** "No matches" was seen often enough to show it without motion. */
    val quietNoMatch: Boolean = false,
    /** The model was removed in Settings: names and words answer until it downloads again. */
    val modelMissing: Boolean = false,
    /** The model is in place and tuned ([Retriever.setUp]); before that, reading adds no meaning. */
    val setUp: Boolean = false,
    /** The meaning upgrade's stage, for the hint under few results; the screen fills it in. */
    val upgrade: UpgradeStage = UpgradeStage.UNAVAILABLE,
    val recent: List<String> = emptyList(),
    val starredUris: Set<String> = emptySet(),
    /** Only starred files. */
    val onlyStarred: Boolean = false,
    /** Search the words as typed, without turning "screenshots last week" into filters. */
    val literal: Boolean = false,
    /** Something the person asked for failed; the screen says so once and calls [SearchViewModel.errorShown]. */
    val error: String? = null,
    /** The demo's planned files: every search stays inside them (the demo screen). Null: the whole library. */
    val scope: List<Long>? = null,
    /** The demo's files still waiting, processed and planned ([TasteProgress]), and its photos read; only while [scope] is set. */
    val unread: Int = 0,
    val sampleDone: Int = 0,
    val samplePlanned: Int = 0,
    val photosRead: Int = 0,
) {
    /** Searching by photo needs the model and, on the first run, enough photos read for it to mean something. */
    val canSearchByPhoto get() = !wordsOnly && (scope == null || photosRead >= PHOTOS_FOR_PHOTO_SEARCH)

    /** No model to search by meaning: a words-only phone, or the optional download not in place and tuned. */
    val wordsOnly get() = Words.wordsOnly(textOnly, modelMissing, setUp)

    /** What the fields ask now. A search by photo ignores the words. */
    val question: Question get() = Question(if (similar != null) "" else query.trim(), chip, similar?.uri, onlyStarred, literal, scope)
}

/** Photos read before the first run's search offers the photo button. */
const val PHOTOS_FOR_PHOTO_SEARCH = 5

/** Files read in a burst ask an empty answer again once, after the burst. */
private const val INDEXED_SETTLE_MS = 2_000L

/** What the person set. [photo] carries the bytes of the photo [SearchUi.similar] shows. */
private data class Input(
    val query: String = "",
    val chip: Kind? = null,
    val photo: PickedPhoto? = null,
    val onlyStarred: Boolean = false,
    val literal: Boolean = false,
    val scope: List<Long>? = null,
) {
    val question get() = Question(if (photo != null) "" else query.trim(), chip, photo?.similar?.uri, onlyStarred, literal, scope)
}

/** A question as a search asks it: [photo] by identity, so picking the same photo again searches its new bytes. */
private data class Ask(val question: Question, val photo: PickedPhoto?) {
    val blank get() = photo == null && question.text.isEmpty()
}

/** What a search said about [question], in the order it said it. */
private sealed interface Step {
    val question: Question

    /** The fields ask something new; steps for any other question are old from here on. */
    data class Asked(override val question: Question) : Step

    data class Names(override val question: Question, val result: SearchEngine.SearchResult) : Step

    /** A full pass started: the first, a words-only answer asked again, or a retry. */
    data class Started(override val question: Question) : Step

    /** Nothing yet for a word still being typed: the answer on screen stays until typing pauses. */
    data class Waiting(override val question: Question) : Step

    data class Full(override val question: Question, val result: SearchEngine.SearchResult, val askAgain: Boolean) : Step

    data class Read(override val question: Question, val extents: Map<Long, ReadExtent>) : Step

    data class Failed(override val question: Question) : Step
}

/** What the steps so far put on screen. [first] is the question whose first answer counted in [answer]. */
private data class Shown(
    val asked: Question? = null,
    val phase: SearchPhase = SearchPhase.Idle,
    val first: Question? = null,
    val answer: Int = 0,
    val searching: Boolean = false,
    val vectorLane: Boolean = true,
    val extents: Map<Long, ReadExtent> = emptyMap(),
    val quietNoMatch: Boolean = false,
    /** This step put up a "no matches" for its question; [quiet] says whether it was seen often enough before. */
    val noMatch: Boolean = false,
) {
    fun after(step: Step, quiet: () -> Boolean): Shown {
        val next = take(step)
        val q = next.asked
        val noMatch = q != null && next.phase.showsEmpty(q) && !phase.showsEmpty(q)
        return next.copy(noMatch = noMatch, quietNoMatch = if (noMatch) quiet() else quietNoMatch)
    }

    private fun take(step: Step): Shown {
        val q = step.question
        if (step is Step.Asked) {
            // Retyping the same words is a new search: its first answer counts again.
            return if (q.text.isEmpty() && q.similarTo == null) Shown(q, answer = answer, quietNoMatch = quietNoMatch) else copy(asked = q, searching = true)
        }
        if (q != asked) return this
        return when (step) {
            is Step.Started -> copy(searching = true)

            is Step.Waiting -> copy(searching = false)

            is Step.Read -> copy(extents = step.extents)

            is Step.Failed -> copy(phase = SearchPhase.Failed(q), searching = false)

            // A names answer that comes after the full one (or its failure) says nothing new.
            is Step.Names -> if ((phase is SearchPhase.Results || phase is SearchPhase.Failed) && !phase.isStale(q)) {
                this
            } else {
                answered(phase.onNames(q, step.result), step.result).copy(searching = true)
            }

            is Step.Full -> answered(phase.onFull(q, step.result, step.askAgain), step.result).copy(searching = false, vectorLane = step.result.vectorLane)
        }
    }

    /** An empty names answer leaves the earlier words' answer up: it answers nothing here, so the screen keeps its place. */
    private fun answered(next: SearchPhase, r: SearchEngine.SearchResult): Shown {
        val q = asked
        if (q == null || next !is SearchPhase.Results || next.isStale(q)) return copy(phase = next)
        val ids = r.hits.mapTo(HashSet()) { it.fileId }
        return copy(phase = next, first = q, answer = if (first == q) answer else answer + 1, extents = extents.filterKeys { it in ids })
    }
}

/** [recentShortcuts] gets the latest searches after they change. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val retriever: Retriever, private val recentShortcuts: RecentShortcuts, private val photos: PhotoReader) : ViewModel() {
    private val input = MutableStateFlow(Input())
    private val retries = MutableStateFlow(0)
    private val recent = MutableStateFlow(emptyList<String>())
    private val error = MutableStateFlow<String?>(null)
    private var typing: Job? = null
    private var scrolling = false
    private var started = false

    /** Once per ViewModel: a retained screen keeps its edits; after process death its route restores the original search. */
    fun start(route: SearchRoute) {
        if (started) return
        started = true
        when {
            route.photo != null -> searchPhoto(route.photo)

            route.run -> {
                recentShortcuts.used(route.query)
                submit(route.query)
            }
        }
    }

    /**
     * The question once it held for [wait] ms. Not made distinct after the wait: a key typed and taken back settles on the
     * question already answered, and asks it again so a pass ends what the key started.
     */
    private fun asks(wait: Long) = input.map { Ask(it.question, it.photo) }.distinctUntilChanged().debounce(wait)

    /** File names alone, in memory, a moment after typing; a search by photo has none. */
    private val names: Flow<Step> = asks(SearchTiming.NAMES_MS).mapLatest { a ->
        val q = a.question
        if (a.blank || a.photo != null) return@mapLatest null
        // The quick answer failing is no news: the full one is on its way.
        logged { retriever.searchNames(q.text, q.kind?.let(::setOf), SearchTiming.LIMIT, q.starredOnly, q.literal, q.scope) }?.let { Step.Names(q, it) }
    }.filterNotNull()

    /** The full search once typing paused, asked again on Retry, when files change and when files read may fill an empty answer. */
    private val full: Flow<Step> = combine(asks(SearchTiming.FULL_MS), retries, retriever.fileRevision) { a, _, _ -> a }.flatMapLatest(::fullSearch)

    private val shown: StateFlow<Shown> = merge(input.map { Step.Asked(it.question) }.distinctUntilChanged(), names, full)
        .scan(Shown()) { s, step -> s.after(step) { retriever.prefs.value.noMatchSeen >= SearchTiming.NO_MATCH_QUIET_AFTER } }
        .onEach { if (it.noMatch) launchCatching { retriever.updatePrefs { p -> p.copy(noMatchSeen = p.noMatchSeen + 1) } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Shown())

    /** The short read's progress, only while a screen scopes the search to it. */
    private val library = combine(
        retriever.setUp,
        retriever.modelMissing,
        retriever.starred,
        input.map { it.scope != null }.distinctUntilChanged().flatMapLatest { if (it) retriever.tasteProgress() else flowOf(null) },
    ) { setUp, missing, starred, p ->
        SearchUi(
            textOnly = retriever.textOnly,
            setUp = setUp == true,
            modelMissing = missing,
            starredUris = starred,
            unread = p?.pending ?: 0,
            sampleDone = p?.done ?: 0,
            samplePlanned = p?.planned ?: 0,
            photosRead = p?.byKind[Kind.PHOTO]?.first ?: 0,
        )
    }

    val ui: StateFlow<SearchUi> = combine(input, shown, recent, error, library) { i, s, recent, error, lib ->
        lib.copy(
            query = i.query,
            chip = i.chip,
            similar = i.photo?.similar,
            onlyStarred = i.onlyStarred,
            literal = i.literal,
            scope = i.scope,
            phase = s.phase,
            answer = s.answer,
            extents = s.extents,
            searching = s.searching,
            vectorLane = s.vectorLane,
            quietNoMatch = s.quietNoMatch,
            recent = recent,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUi(textOnly = retriever.textOnly))

    init {
        // Files read since an answer found nothing may hold the match: ask the same question again. Only an empty
        // answer, so a list being read never reshuffles; and core's counts are watched only while one is on screen, not
        // for every write of a reading run behind other screens or in the background. Reading waits while a search
        // runs, so the count read on subscribing is the one the answer saw, and is dropped.
        viewModelScope.launch {
            ui.map { it.phase.showsEmpty(it.question) }.distinctUntilChanged().flatMapLatest { empty ->
                if (!empty) {
                    emptyFlow()
                } else {
                    retriever.counts().map { it.byState[FileState.INDEXED] }.distinctUntilChanged().drop(1).debounce(INDEXED_SETTLE_MS)
                }
            }.collect { retry() }
        }
        launchCatching { loadRecent() }
    }

    /**
     * One full pass for [a], cancelled by the next question. Words only, because the model was still opening (it opens on
     * focus and holds the lock for seconds): asked again in a moment, a few times, for results by meaning; not when there
     * is no model to wait for.
     */
    private fun fullSearch(a: Ask): Flow<Step> = flow {
        if (a.blank) return@flow
        val q = a.question
        val kinds = q.kind?.let(::setOf)
        var askedAgain = 0
        while (true) {
            emit(Step.Started(q))
            val r = logged {
                if (a.photo != null) {
                    // A search by photo has no weak tail.
                    retriever.searchImage(a.photo.jpeg, kinds, SearchTiming.LIMIT, q.starredOnly, q.scope).let { it.copy(strongCount = it.hits.size) }
                } else {
                    retriever.search(q.text, kinds, SearchTiming.LIMIT, q.starredOnly, q.literal, q.scope)
                }
            }
            if (r == null) {
                emit(Step.Failed(q))
                return@flow
            }
            if (r.hits.isEmpty() && a.photo == null && midWord(input.value.query)) {
                emit(Step.Waiting(q))
                delay(SearchTiming.TYPING_MS.milliseconds)
            }
            val noModelComing = retriever.textOnly || retriever.modelMissing.value || retriever.setUp.value != true
            val again = !r.vectorLane && !noModelComing && askedAgain < SearchTiming.WORDS_ONLY_RETRIES
            emit(Step.Full(q, r, again))
            // After the rows are up; the read notes join them a moment later.
            logged { retriever.readExtents(r.hits.map { it.fileId }) }?.let { emit(Step.Read(q, it)) }
            if (!again) return@flow
            askedAgain++
            delay(SearchTiming.WORDS_ONLY_WAIT_MS.milliseconds)
        }
    }

    /** [block]'s value, or null when it failed, logged. A cancellation (a newer question) goes through. */
    private suspend fun <T> logged(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("Retriever", "search failed", e)
        null
    }

    /** Typing words ends a search by photo; clearing the words drops the filters with them. */
    fun setQuery(q: String) {
        val before = input.value.query
        input.update {
            when {
                q.isBlank() -> if (it.query.isNotBlank()) it.copy(query = q, chip = null, onlyStarred = false, literal = false) else it.copy(query = q)
                q.trim() == it.query.trim() -> it.copy(query = q, photo = null)
                else -> it.copy(query = q, photo = null, literal = false)
            }
        }
        if (q != before) typed()
    }

    /** Indexing stays out of the way for a moment after each keystroke; a search running holds it off in core. */
    private fun typed() {
        typing?.cancel()
        retriever.setUiBusy(true)
        typing = viewModelScope.launch {
            delay(SearchTiming.TYPING_MS)
            typing = null
            retriever.setUiBusy(scrolling)
        }
    }

    /** Keeps every search inside [ids] (the first run's planned files); null lifts it. */
    fun setScope(ids: List<Long>?) = input.update { it.copy(scope = ids) }

    fun setChip(kind: Kind?) = input.update { it.copy(chip = kind) }

    fun setOnlyStarred(on: Boolean) = input.update { it.copy(onlyStarred = on) }

    /** The X on the understood filters: search the words as typed. */
    fun searchWordsAsTyped() = input.update { it.copy(literal = true) }

    fun setStarred(file: SourceFile, on: Boolean) = attempt { retriever.setStarred(file.uri, on) }

    /** The search bar took focus: the first touch is the time to ready search. Focus alone, e.g. browsing results, isn't busy. */
    fun setFocused(on: Boolean) {
        if (on) launchCatching { retriever.warmSearch() }
    }

    fun setScrolling(on: Boolean) {
        scrolling = on
        retriever.setUiBusy(scrolling || typing != null)
    }

    fun retry() = retries.update { it + 1 }

    /** The keyboard's search key, or a suggestion picked. */
    fun submit(q: String = input.value.query) {
        setQuery(q)
        record(q)
    }

    fun opened() {
        input.value.let { if (it.photo == null) record(it.query) }
    }

    private fun record(q: String) {
        if (q.isBlank()) return
        attempt {
            retriever.recordQuery(q)
            loadRecent()
            if (retriever.prefs.value.rememberSearches) recentShortcuts.push(recent.value)
        }
    }

    fun removeRecent(q: String) = attempt {
        retriever.removeRecentQuery(q)
        loadRecent()
        recentShortcuts.push(recent.value)
    }

    fun clearRecent() = attempt {
        retriever.clearRecentQueries()
        loadRecent()
        recentShortcuts.push(recent.value)
    }

    /** Settings can clear the searches while this screen stays alive behind it. */
    fun refreshRecent() = launchCatching { loadRecent() }

    private suspend fun loadRecent() {
        recent.value = retriever.recentQueries()
    }

    fun findSimilar(file: SourceFile) = showSimilar(file.uri, file.name)

    /** A photo from the picker. */
    fun searchPhoto(uri: String) = showSimilar(uri, "your photo")

    private fun showSimilar(uri: String, label: String) = attempt {
        val picked = photos.read(uri, label)
        if (picked == null) {
            error.value = "Couldn't open this photo. Choose another one."
            return@attempt
        }
        input.update { it.copy(photo = picked, query = "") }
    }

    fun clearSimilar() = input.update { it.copy(photo = null) }

    fun errorShown() {
        error.value = null
    }

    /** Cleared while typing, the typing pause would never end: reading would wait for the UI for good. */
    override fun onCleared() = retriever.setUiBusy(false)

    /** Something the person asked for: a failure is logged and said on screen. */
    private fun attempt(block: suspend () -> Unit) = launchCatching(onError = {
        Log.e("Retriever", "SearchViewModel", it)
        error.value = "That didn't work. Try again."
    }) { block() }
}

/** A picked photo or a file as the search by photo needs it, or null when it can't be read. */
fun interface PhotoReader {
    suspend fun read(uri: String, label: String): PickedPhoto?
}

/** A JPEG at most 1024 px on its longest side, decoded once here, not in composition: the chip shows it as its avatar. */
fun photoReader(context: Context) = PhotoReader { uri, label ->
    withContext(Dispatchers.IO) { ImageBytes.jpeg(context, uri.toUri(), 1024) }?.let { jpeg ->
        val thumb = withContext(Dispatchers.Default) { avatar(jpeg) }
        PickedPhoto(Similar(uri, label, thumb), jpeg)
    }
}

/** The avatar is 24 dp: decoded subsampled to at least [AVATAR_PX] on its short side, not the whole 1024 px (about 3 MB). */
internal fun avatar(jpeg: ByteArray): ImageBitmap? = DocPage.decodeSampled(jpeg, AVATAR_PX, ::minOf)?.asImageBitmap()

/** 24 dp on the densest screens, with room to spare. */
private const val AVATAR_PX = 128
