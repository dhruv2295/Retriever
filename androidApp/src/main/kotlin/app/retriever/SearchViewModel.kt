package app.retriever

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.core.FileState
import app.retriever.core.HomeFacts
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ParsedQuery
import app.retriever.core.ReadExtent
import app.retriever.core.Retriever
import app.retriever.core.SearchEngine
import app.retriever.core.SourceFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

// Not a data class: equality is identity, so one photo search is one search key.
class Similar(val label: String, val jpeg: ByteArray, val thumb: ImageBitmap? = null)

data class SearchUi(
    val query: String = "",
    val chip: Kind? = null,
    val similar: Similar? = null,
    /** null: nothing searched. */
    val hits: List<SearchEngine.Hit>? = null,
    /** Counts the searches that set [hits], so the screen can start each new answer at its top. */
    val answer: Int = 0,
    /** The trimmed query [hits] answer; a search by photo answers "". */
    val hitsFor: String = "",
    /** hits[0 until strongCount] are strong matches, the rest less likely. The default makes every hit strong. */
    val strongCount: Int = Int.MAX_VALUE,
    /** Files in [hits] that were not read whole. */
    val extents: Map<Long, ReadExtent> = emptyMap(),
    /** This phone finds files by their names and words only. */
    val textOnly: Boolean = false,
    val searching: Boolean = false,
    val failed: Boolean = false,
    val vectorLane: Boolean = true,
    /** The model was removed in Settings: names and words answer until it downloads again. */
    val modelMissing: Boolean = false,
    val setUp: Boolean = false,
    val recent: List<String> = emptyList(),
    /** null until the store has answered. */
    val counts: Map<Kind, Long>? = null,
    val searchable: Long = 0L,
    val waiting: Long = 0L,
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
    val starredUris: Set<String> = emptySet(),
    /** Only starred files. */
    val onlyStarred: Boolean = false,
    /** Search the words as typed, without turning "screenshots last week" into filters. */
    val literal: Boolean = false,
    /** What [hits] understood the words to ask for, and whether a filter found nothing so it was dropped. */
    val parsed: ParsedQuery? = null,
    val relaxed: Boolean = false,
    /** [hits] are the quick answer from file names; the full one is on its way. */
    val nameOnly: Boolean = false,
    /** Counts the searches handed in from outside, so the screen opens each one once. */
    val launched: Int = 0,
)

private const val NAMES_MS = 20L
private const val FULL_MS = 200L

/** How long after the last keystroke indexing stays out of the way. */
private const val TYPING_MS = 1_500L

/** A words-only answer while the model opens is asked again this often, at most [WORDS_ONLY_RETRIES] times. */
private const val WORDS_ONLY_WAIT_MS = 2_000L
private const val WORDS_ONLY_RETRIES = 5

/**
 * [jpegOf] turns a content uri into a JPEG at most 1024 px on its longest side, or null. [onRecent] gets the latest
 * searches after they change. [setBusy] and [warm] default to the retriever's own.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val retriever: Retriever,
    private val hasAccess: () -> Boolean = { true },
    private val onRecent: (List<String>) -> Unit = {},
    private val setBusy: (Boolean) -> Unit = retriever::setUiBusy,
    private val warm: suspend () -> Unit = retriever::warmSearch,
    private val jpegOf: (String) -> ByteArray?,
) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi(textOnly = retriever.textOnly, hasAccess = hasAccess()))
    val ui: StateFlow<SearchUi> = _ui.asStateFlow()
    val indexState: StateFlow<IndexState> = retriever.state
    private val retries = MutableStateFlow(0)

    /** The key whose first answer is on screen, and the one whose full answer is. */
    private var shown: Key? = null
    private var complete: Key? = null
    private var typing: Job? = null
    private var scrolling = false

    /** Words-only answers to [wordsOnlyFor] asked again so far. */
    private var wordsOnlyFor: Key? = null
    private var wordsOnly = 0

    init {
        // Two phases per key: the file names a moment after typing, the full search once the typing has paused.
        viewModelScope.launch { keys(NAMES_MS).collectLatest { search(it, namesOnly = true) } }
        viewModelScope.launch { keys(FULL_MS).collectLatest { search(it, namesOnly = false) } }
        viewModelScope.launch {
            LaunchQuery.pending.filterNotNull().collect { q ->
                LaunchQuery.consume()
                _ui.update { it.copy(query = q, chip = null, similar = null, onlyStarred = false, literal = false, launched = it.launched + 1) }
            }
        }
        viewModelScope.launch { retriever.setUp.collect { s -> _ui.update { it.copy(setUp = s == true) } } }
        viewModelScope.launch { retriever.modelMissing.collect { m -> _ui.update { it.copy(modelMissing = m) } } }
        viewModelScope.launch {
            retriever.counts().collect { c ->
                _ui.update {
                    it.copy(
                        counts = c.byKind,
                        searchable = c.byState[FileState.INDEXED] ?: 0L,
                        waiting = c.byState[FileState.PENDING] ?: 0L,
                        pending = c.byKindState.filterKeys { k -> k.second == FileState.PENDING }.mapKeys { e -> e.key.first },
                        suggestions = retriever.suggestions(c.byKindState),
                    )
                }
            }
        }
        viewModelScope.launch { retriever.prefs.collect { p -> _ui.update { it.copy(paused = p.paused, inside = retriever.readInside()) } } }
        viewModelScope.launch { retriever.starred.collect { s -> _ui.update { it.copy(starred = s.size, starredUris = s) } } }
        // At most one reload every 2 s while files are being counted, and always one after the last change.
        viewModelScope.launch {
            retriever.counts().conflate().collect {
                quietly { retriever.home().let { h -> _ui.update { it.copy(home = h) } } }
                delay(2_000)
            }
        }
        viewModelScope.launch { loadRecent() }
    }

    private data class Key(val q: String, val chip: Kind?, val similar: Similar?, val retry: Int, val starred: Boolean, val literal: Boolean) {
        /** The same question asked again by a retry is still this one. */
        val question get() = copy(retry = 0)
    }

    // A search by photo ignores the words, so the query settling to "" must not start it again.
    private fun keyNow(): Key = _ui.value.let { Key(if (it.similar != null) "" else it.query.trim(), it.chip, it.similar, retries.value, it.onlyStarred, it.literal) }

    /** The search key as it changes: the words wait [wait] ms for more typing, everything else counts at once. */
    private fun keys(wait: Long) = combine(
        _ui.map { it.query.trim() }.distinctUntilChanged().debounce(wait),
        _ui.map { Filters(it.chip, it.similar, it.onlyStarred, it.literal) }.distinctUntilChanged(),
        retries,
    ) { q, f, retry -> Key(if (f.similar != null) "" else q, f.chip, f.similar, retry, f.starred, f.literal) }.distinctUntilChanged()

    private data class Filters(val chip: Kind?, val similar: Similar?, val starred: Boolean, val literal: Boolean)

    /**
     * [namesOnly] answers from file names alone, in memory. The full search follows; whichever answers a key first
     * starts a new [SearchUi.answer] (the screen goes to its top), the other one only refreshes the rows in place.
     */
    private suspend fun search(k: Key, namesOnly: Boolean) {
        if (namesOnly && k.similar != null) return
        if (k.similar == null && k.q.isEmpty()) {
            _ui.update { it.copy(hits = null, hitsFor = "", searching = false, failed = false, nameOnly = false, parsed = null, relaxed = false) }
            return
        }
        if (namesOnly && complete?.question == k.question) return
        _ui.update { it.copy(searching = true, failed = false) }
        val kinds = k.chip?.let(::setOf)
        try {
            val r = when {
                k.similar != null -> retriever.searchImage(k.similar.jpeg, kinds, 60, k.starred)
                namesOnly -> retriever.searchNames(k.q, kinds, 60, k.starred, k.literal)
                else -> retriever.search(k.q, kinds, 60, k.starred, k.literal)
            }
            // Typing went on, or the full answer already came: this one is old.
            if (k != keyNow() || (namesOnly && complete?.question == k.question)) return
            // No name matches yet isn't "no matches": keep searching until the full answer says so.
            if (namesOnly && r.hits.isEmpty()) return
            val fresh = shown?.question != k.question
            shown = k
            if (!namesOnly) complete = k
            val ids = r.hits.mapTo(HashSet()) { it.fileId }
            // A search by photo has no weak tail.
            val strong = if (k.similar != null) r.hits.size else r.strongCount
            _ui.update {
                it.copy(
                    hits = r.hits, answer = if (fresh) it.answer + 1 else it.answer, hitsFor = k.q, strongCount = strong,
                    extents = it.extents.filterKeys { id -> id in ids }, parsed = r.parsed, relaxed = r.relaxed, nameOnly = namesOnly,
                    vectorLane = if (namesOnly) it.vectorLane else r.vectorLane, searching = namesOnly,
                )
            }
            if (namesOnly) return
            // After the rows are up; the read notes join them a moment later.
            quietly {
                val extents = retriever.readExtents(r.hits.map { it.fileId })
                if (k == keyNow()) _ui.update { it.copy(extents = extents) }
            }
            askAgainIfWordsOnly(k, r.vectorLane)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Retriever", "search failed", e)
            // The quick answer failing is no news: the full one is on its way.
            if (!namesOnly) _ui.update { it.copy(searching = false, failed = true) }
        }
    }

    /**
     * Words only, because the model was still opening (it opens on focus and holds the lock for seconds): ask again in a
     * moment, a few times, for results by meaning. Not when there is no model to wait for. Cancelled by any new key.
     */
    private suspend fun askAgainIfWordsOnly(k: Key, vectorLane: Boolean) {
        if (wordsOnlyFor != k.question) {
            wordsOnlyFor = k.question
            wordsOnly = 0
        }
        val noModelComing = retriever.textOnly || retriever.modelMissing.value || retriever.setUp.value != true
        if (vectorLane || noModelComing || wordsOnly >= WORDS_ONLY_RETRIES) return
        wordsOnly++
        delay(WORDS_ONLY_WAIT_MS)
        retries.update { it + 1 }
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
        if (q != before) typed()
    }

    /** Indexing stays out of the way for a moment after each keystroke; a search running holds it off in core. */
    private fun typed() {
        typing?.cancel()
        setBusy(true)
        typing = viewModelScope.launch {
            delay(TYPING_MS)
            typing = null
            setBusy(scrolling)
        }
    }

    fun setChip(kind: Kind?) = _ui.update { it.copy(chip = kind) }

    fun setOnlyStarred(on: Boolean) = _ui.update { it.copy(onlyStarred = on) }

    /** The X on the understood filters: search the words as typed. */
    fun searchWordsAsTyped() = _ui.update { it.copy(literal = true) }

    fun setStarred(file: SourceFile, on: Boolean) = viewModelScope.launch { quietly { retriever.setStarred(file.uri, on) } }

    /** The search bar took focus: the first touch is the time to ready search. Focus alone, e.g. browsing results, isn't busy. */
    fun setFocused(on: Boolean) {
        if (on) viewModelScope.launch { quietly { warm() } }
    }

    fun setScrolling(on: Boolean) {
        scrolling = on
        setBusy(scrolling || typing != null)
    }

    fun retry() = retries.update { it + 1 }

    /** The keyboard's search key, or a suggestion picked. */
    fun submit(q: String = _ui.value.query) {
        setQuery(q)
        record(q)
    }

    fun opened(hit: SearchEngine.Hit) {
        if (_ui.value.similar == null) record(_ui.value.query)
    }

    private fun record(q: String) {
        if (q.isBlank()) return
        viewModelScope.launch {
            quietly { retriever.recordQuery(q) }
            loadRecent()
            if (retriever.prefs.value.rememberSearches) quietly { onRecent(_ui.value.recent) }
        }
    }

    fun removeRecent(q: String) = viewModelScope.launch {
        quietly { retriever.removeRecentQuery(q) }
        loadRecent()
        quietly { onRecent(_ui.value.recent) }
    }

    fun clearRecent() = viewModelScope.launch {
        quietly { retriever.clearRecentQueries() }
        loadRecent()
        quietly { onRecent(_ui.value.recent) }
    }

    private suspend fun loadRecent() = quietly { _ui.update { it.copy(recent = retriever.recentQueries()) } }

    fun findSimilar(file: SourceFile) = showSimilar(file.uri, file.name)

    /** A photo from the picker. */
    fun searchPhoto(uri: String) = showSimilar(uri, "your photo")

    private fun showSimilar(uri: String, label: String) = viewModelScope.launch {
        val jpeg = withContext(Dispatchers.IO) { runCatching { jpegOf(uri) }.getOrNull() } ?: return@launch
        // Decoded once here, not in composition: the chip shows it as its avatar.
        val thumb = withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }.getOrNull() }
        _ui.update { it.copy(similar = Similar(label, jpeg, thumb), query = "") }
    }

    fun clearSimilar() = _ui.update { it.copy(similar = null) }

    /** Access can be taken away in Settings while the app is away, so Home asks again on every return. */
    fun checkAccess() = _ui.update { it.copy(hasAccess = hasAccess()) }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Retriever", "search screen", e)
        }
    }
}
