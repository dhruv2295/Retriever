package app.retriever.search

import android.content.Context
import android.graphics.BitmapFactory
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
import app.retriever.core.onFull
import app.retriever.core.onNames
import app.retriever.core.showsEmpty
import app.retriever.ui.launchCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

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
    /** [recent] has answered once (or failed to), so Home's sections can draw with its chip row in place. */
    val recentLoaded: Boolean = false,
    val starredUris: Set<String> = emptySet(),
    /** Only starred files. */
    val onlyStarred: Boolean = false,
    /** Search the words as typed, without turning "screenshots last week" into filters. */
    val literal: Boolean = false,
    /** Counts the searches handed in from outside, so the screen opens each one once. */
    val launched: Int = 0,
    /** Something the person asked for failed; the screen says so once and calls [SearchViewModel.errorShown]. */
    val error: String? = null,
    /** The first run's planned files: every search stays inside them (the try-it screen). Null: the whole library. */
    val scope: List<Long>? = null,
    /** Planned files not read yet, the files and photos read so far; only while [scope] is set. */
    val unread: Int = 0,
    val sampleRead: Int = 0,
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

/** [launchQuery] hands in searches from shortcuts; [recentShortcuts] gets the latest searches after they change. */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val retriever: Retriever,
    private val launchQuery: LaunchQuery,
    private val recentShortcuts: RecentShortcuts,
    private val photos: PhotoReader,
) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi(textOnly = retriever.textOnly))
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()
    private val retries = MutableStateFlow(0)
    private var photo: PickedPhoto? = null

    /** The question whose first answer is on screen, and the one whose full answer is. */
    private var shown: Question? = null
    private var complete: Question? = null
    private var typing: Job? = null
    private var scrolling = false

    /** Words-only answers to [wordsOnlyFor] asked again so far. */
    private var wordsOnlyFor: Question? = null
    private var wordsOnly = 0

    init {
        viewModelScope.launch { retriever.fileRevision.collect { if (it > 0) retry() } }
        // Two phases per key: the file names a moment after typing, the full search once the typing has paused.
        viewModelScope.launch { keys(SearchTiming.NAMES_MS).collectLatest { search(it, namesOnly = true) } }
        viewModelScope.launch { keys(SearchTiming.FULL_MS).collectLatest { search(it, namesOnly = false) } }
        // Files read since an answer found nothing may hold the match: ask the same question again. Only an empty
        // answer, so a list being read never reshuffles.
        viewModelScope.launch {
            retriever.counts().map { it.byState[FileState.INDEXED] }.distinctUntilChanged().debounce(INDEXED_SETTLE_MS).collect {
                if (_ui.value.let { ui -> ui.phase.showsEmpty(ui.question) }) retries.update { it + 1 }
            }
        }
        viewModelScope.launch {
            launchQuery.pending.filterNotNull().collect { q ->
                launchQuery.consume()
                recentShortcuts.used(q)
                photo = null
                _ui.update { it.copy(query = q, chip = null, similar = null, onlyStarred = false, literal = false, launched = it.launched + 1) }
            }
        }
        viewModelScope.launch { retriever.setUp.collect { s -> _ui.update { it.copy(setUp = s == true) } } }
        viewModelScope.launch { retriever.modelMissing.collect { m -> _ui.update { it.copy(modelMissing = m) } } }
        viewModelScope.launch { retriever.starred.collect { s -> _ui.update { it.copy(starredUris = s) } } }
        // The short read's progress, only while a screen scopes the search to it.
        viewModelScope.launch {
            _ui.map { it.scope != null }.distinctUntilChanged().flatMapLatest { if (it) retriever.tasteProgress() else emptyFlow() }.collect { p ->
                _ui.update { it.copy(unread = p.pending, sampleRead = p.read, photosRead = p.byKind[Kind.PHOTO]?.first ?: 0) }
            }
        }
        // Loaded even when the read fails, so Home's sections never wait on it.
        launchCatching(onError = {
            Log.e("Retriever", "recent searches failed", it)
            _ui.update { u -> u.copy(recentLoaded = true) }
        }) { loadRecent() }
    }

    /** A question as asked: [photo] carries the photo's bytes, [retry] asks the same question again. */
    private data class Key(val question: Question, val photo: PickedPhoto?, val retry: Int)

    private fun SearchUi.pickedPhoto() = photo?.takeIf { it.similar === similar }

    private fun keyNow(): Key = _ui.value.let { Key(it.question, it.pickedPhoto(), retries.value) }

    /** The key as it changes: the words wait [wait] ms for more typing, everything else counts at once. */
    private fun keys(wait: Long) = combine(
        _ui.map { it.query.trim() }.distinctUntilChanged().debounce(wait),
        _ui.map { it.question.copy(text = "") to it.pickedPhoto() }.distinctUntilChanged(),
        retries,
    ) { text, (rest, similar), retry ->
        // A search by photo ignores the words, so the query settling to "" must not start it again.
        Key(if (similar != null) rest else rest.copy(text = text), similar, retry)
    }.distinctUntilChanged()

    /**
     * [namesOnly] answers from file names alone, in memory. The full search follows; whichever answers a question first
     * starts a new [SearchUi.answer] (the screen goes to its top), the other one only refreshes the rows in place.
     */
    private suspend fun search(k: Key, namesOnly: Boolean) {
        val q = k.question
        if (namesOnly && k.photo != null) return
        if (k.photo == null && q.text.isEmpty()) {
            // Retyping the same words is a new search: its names answer comes first again.
            shown = null
            complete = null
            _ui.update { it.copy(phase = SearchPhase.Idle, searching = false) }
            return
        }
        if (namesOnly && complete == q) return
        _ui.update { it.copy(searching = true) }
        val kinds = q.kind?.let(::setOf)
        try {
            val r = when {
                k.photo != null -> retriever.searchImage(k.photo.jpeg, kinds, SearchTiming.LIMIT, q.starredOnly, q.scope).let {
                    it.copy(strongCount = it.hits.size) // a search by photo has no weak tail
                }

                namesOnly -> retriever.searchNames(q.text, kinds, SearchTiming.LIMIT, q.starredOnly, q.literal, q.scope)

                else -> retriever.search(q.text, kinds, SearchTiming.LIMIT, q.starredOnly, q.literal, q.scope)
            }
            // Typing went on, or the full answer already came: this one is old.
            if (k != keyNow() || (namesOnly && complete == q)) return
            val again = !namesOnly && willAskAgain(q, r.vectorLane)
            val before = _ui.value.phase
            val phase = if (namesOnly) before.onNames(q, r) else before.onFull(q, r, again)
            val fresh = phase is SearchPhase.Results && shown != q
            if (phase is SearchPhase.Results) shown = q
            if (!namesOnly) complete = q
            val noMatch = phase.showsEmpty(q) && !before.showsEmpty(q)
            val quiet = retriever.prefs.value.noMatchSeen >= SearchTiming.NO_MATCH_QUIET_AFTER
            val ids = r.hits.mapTo(HashSet()) { it.fileId }
            _ui.update {
                it.copy(
                    phase = phase,
                    answer = if (fresh) it.answer + 1 else it.answer,
                    extents = it.extents.filterKeys { id -> id in ids },
                    vectorLane = if (namesOnly) it.vectorLane else r.vectorLane,
                    searching = namesOnly,
                    quietNoMatch = if (noMatch) quiet else it.quietNoMatch,
                )
            }
            if (noMatch) launchCatching { retriever.updatePrefs { p -> p.copy(noMatchSeen = p.noMatchSeen + 1) } }
            if (namesOnly) return
            // After the rows are up; the read notes join them a moment later. A newer key leaves them unshown.
            launchCatching {
                val extents = retriever.readExtents(r.hits.map { it.fileId })
                if (k == keyNow()) _ui.update { it.copy(extents = extents) }
            }
            if (again) {
                wordsOnly++
                delay(SearchTiming.WORDS_ONLY_WAIT_MS)
                retries.update { it + 1 }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Retriever", "search failed", e)
            // The quick answer failing is no news: the full one is on its way. A replaced question's failure is no news either.
            if (!namesOnly && k == keyNow()) _ui.update { it.copy(phase = SearchPhase.Failed(q), searching = false) }
        }
    }

    /**
     * Words only, because the model was still opening (it opens on focus and holds the lock for seconds): ask again in a
     * moment, a few times, for results by meaning. Not when there is no model to wait for. Cancelled by any new key.
     */
    private fun willAskAgain(q: Question, vectorLane: Boolean): Boolean {
        if (wordsOnlyFor != q) {
            wordsOnlyFor = q
            wordsOnly = 0
        }
        val noModelComing = retriever.textOnly || retriever.modelMissing.value || retriever.setUp.value != true
        return !vectorLane && !noModelComing && wordsOnly < SearchTiming.WORDS_ONLY_RETRIES
    }

    /** Typing words ends a search by photo; clearing the words drops the filters with them. */
    fun setQuery(q: String) {
        val before = _ui.value.query
        _ui.update {
            when {
                q.isBlank() -> if (it.query.isNotBlank()) it.copy(query = q, chip = null, onlyStarred = false, literal = false) else it.copy(query = q)
                q.trim() == it.query.trim() -> it.copy(query = q, similar = null)
                else -> it.copy(query = q, similar = null, literal = false)
            }
        }
        if (_ui.value.similar == null) photo = null
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
    fun setScope(ids: List<Long>?) = _ui.update { if (it.scope == ids) it else it.copy(scope = ids) }

    fun setChip(kind: Kind?) = _ui.update { it.copy(chip = kind) }

    fun setOnlyStarred(on: Boolean) = _ui.update { it.copy(onlyStarred = on) }

    /** The X on the understood filters: search the words as typed. */
    fun searchWordsAsTyped() = _ui.update { it.copy(literal = true) }

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
    fun submit(q: String = _ui.value.query) {
        setQuery(q)
        record(q)
    }

    fun opened() {
        if (_ui.value.similar == null) record(_ui.value.query)
    }

    private fun record(q: String) {
        if (q.isBlank()) return
        attempt {
            retriever.recordQuery(q)
            loadRecent()
            if (retriever.prefs.value.rememberSearches) recentShortcuts.push(_ui.value.recent)
        }
    }

    fun removeRecent(q: String) = attempt {
        retriever.removeRecentQuery(q)
        loadRecent()
        recentShortcuts.push(_ui.value.recent)
    }

    fun clearRecent() = attempt {
        retriever.clearRecentQueries()
        loadRecent()
        recentShortcuts.push(_ui.value.recent)
    }

    /** Settings can clear the searches while this screen stays alive behind it. */
    fun refreshRecent() = launchCatching { loadRecent() }

    private suspend fun loadRecent() = _ui.update { it.copy(recent = retriever.recentQueries(), recentLoaded = true) }

    fun findSimilar(file: SourceFile) = showSimilar(file.uri, file.name)

    /** A photo from the picker. */
    fun searchPhoto(uri: String) = showSimilar(uri, "your photo")

    private fun showSimilar(uri: String, label: String) = attempt {
        val picked = photos.read(uri, label) ?: return@attempt
        photo = picked
        _ui.update { it.copy(similar = picked.similar, query = "") }
    }

    fun clearSimilar() {
        photo = null
        _ui.update { it.copy(similar = null) }
    }

    fun errorShown() = _ui.update { it.copy(error = null) }

    /** Cleared while typing, the typing pause would never end: reading would wait for the UI for good. */
    override fun onCleared() = retriever.setUiBusy(false)

    /** Something the person asked for: a failure is logged and said on screen. */
    private fun attempt(block: suspend () -> Unit) = launchCatching(onError = {
        Log.e("Retriever", "SearchViewModel", it)
        _ui.update { s -> s.copy(error = "That didn't work. Try again.") }
    }) { block() }
}

/** A picked photo or a file as the search by photo needs it, or null when it can't be read. */
fun interface PhotoReader {
    suspend fun read(uri: String, label: String): PickedPhoto?
}

/** A JPEG at most 1024 px on its longest side, decoded once here, not in composition: the chip shows it as its avatar. */
fun photoReader(context: Context) = PhotoReader { uri, label ->
    withContext(Dispatchers.IO) { ImageBytes.jpeg(context, uri.toUri(), 1024) }?.let { jpeg ->
        val thumb = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
        PickedPhoto(Similar(uri, label, thumb), jpeg)
    }
}
