package app.retriever.core

/** Everything that changes what a search should return. Equal questions get the same answer. */
data class Question(val text: String, val kind: Kind?, val similarTo: String?, val starredOnly: Boolean, val literal: Boolean, val scope: List<Long>? = null)

/** What the results area shows. "Nothing found" is only [showsEmpty]: a finished answer to the current question. */
sealed interface SearchPhase {
    data object Idle : SearchPhase

    data class Searching(val question: Question) : SearchPhase

    data class Results(val question: Question, val result: SearchEngine.SearchResult, val complete: Boolean) : SearchPhase

    data class Failed(val question: Question) : SearchPhase
}

private val SearchPhase.question: Question?
    get() = when (this) {
        SearchPhase.Idle -> null
        is SearchPhase.Searching -> question
        is SearchPhase.Results -> question
        is SearchPhase.Failed -> question
    }

/** A retry of the same question keeps its answer on screen; anything else is a new search. */
fun SearchPhase.onAsk(q: Question): SearchPhase = if (this is SearchPhase.Results && question == q) this else SearchPhase.Searching(q)

/** The fast names-only answer. An empty one says nothing, and it never replaces a fuller answer to the same question. */
fun SearchPhase.onNames(q: Question, r: SearchEngine.SearchResult): SearchPhase = when {
    question == q && this is SearchPhase.Results -> this
    r.hits.isEmpty() -> if (question == q && this is SearchPhase.Searching) this else SearchPhase.Searching(q)
    else -> SearchPhase.Results(q, r, complete = false)
}

/** The full answer; [willRetry] means a words-only answer will be asked again once the model is ready. */
fun SearchPhase.onFull(q: Question, r: SearchEngine.SearchResult, willRetry: Boolean): SearchPhase = SearchPhase.Results(q, r, complete = !willRetry)

/** A failure for a question that was since replaced is ignored. */
fun SearchPhase.onError(q: Question): SearchPhase = if (question == q) SearchPhase.Failed(q) else this

fun SearchPhase.showsEmpty(current: Question): Boolean = this is SearchPhase.Results && question == current && complete && result.hits.isEmpty()

/** The phase answers a question other than [current], so its content must not be shown as the answer. */
fun SearchPhase.isStale(current: Question): Boolean = question.let { it != null && it != current }

/** The hits to show for [current]: the answer to that question only. */
fun SearchPhase.answerFor(current: Question): SearchEngine.SearchResult? = (this as? SearchPhase.Results)?.takeIf { it.question == current }?.result

/** Search timings shared by both apps; the Android values, which the iOS model matches except where noted. */
object SearchTiming {
    const val NAMES_MS = 20L
    const val FULL_MS = 200L
    const val TYPING_MS = 1_500L
    const val WORDS_ONLY_WAIT_MS = 2_000L
    const val WORDS_ONLY_RETRIES = 5
    const val LIMIT = 60

    /** Android shows its bar after this; iOS SearchModel uses 300 too (spinner task). */
    const val SEARCHING_BAR_MS = 300L

    /** Spec value for the mascot's searching pose (no VM constant exists yet). */
    const val SEARCHING_MASCOT_MS = 400L
    const val NO_MATCH_QUIET_AFTER = 3
}
