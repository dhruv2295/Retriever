package app.retriever.core

private val NAME_WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

/**
 * The parts of a file name keyword search matched: each whole word equal to a query word, or starting with the
 * last one when it has three letters or more, and each CJK query word where it occurs inside a run. Call it for a file
 * found by its name only.
 */
fun nameMatchRanges(name: String, query: String): List<IntRange> {
    val tokens = Store.ftsTokens(query).map(::foldWord)
    if (tokens.isEmpty()) return emptyList()
    val words = highlightable(tokens).toSet()
    val prefix = tokens.last().takeIf { it.length >= 3 && it in words }
    val whole = NAME_WORD.findAll(name).filter { m ->
        val w = foldWord(m.value)
        w in words || (prefix != null && w.startsWith(prefix))
    }.map { it.range }.toList()
    return withCjk(whole, name, words)
}

/**
 * The query's words to mark inside an opened file, as typed (lowercased, accents kept): iOS matches them literally. Which
 * words count follows the snippet rule: a stopword or a short word of a longer query is left out.
 */
fun highlightTerms(query: String): List<String> {
    val tokens = Store.ftsTokens(query)
    val keep = highlightable(tokens.map(::foldWord)).toSet()
    return tokens.filter { foldWord(it) in keep }
}

/** A query word as the highlighter compares it: [foldLatin], except CJK, kept composed so it is found inside a run. */
internal fun foldWord(word: String): String = if (hasCjk(word)) word.lowercase() else foldLatin(word)

private fun highlightable(words: List<String>): List<String> =
    if (words.size == 1) words else words.filter { (it.length > 2 || hasCjk(it)) && it !in highlightStopwords }

internal fun wordMatchRanges(text: String, words: List<String>): List<IntRange> {
    val meaningful = highlightable(words)
    val whole = NAME_WORD.findAll(text)
        .filter { match ->
            val token = foldWord(match.value)
            meaningful.any { word -> token == word || (word.length >= 3 && token.startsWith(word)) }
        }.map { it.range }.toList()
    return withCjk(whole, text, meaningful)
}

/** [whole] plus where each CJK word of [words] occurs in [text], in order, overlaps joined. */
private fun withCjk(whole: List<IntRange>, text: String, words: Collection<String>): List<IntRange> {
    val inside = ArrayList<IntRange>()
    for (w in words) {
        if (!hasCjk(w)) continue
        var at = text.indexOf(w, ignoreCase = true)
        while (at >= 0) {
            inside += at until at + w.length
            at = text.indexOf(w, at + w.length, ignoreCase = true)
        }
    }
    if (inside.isEmpty()) return whole
    val out = ArrayList<IntRange>()
    for (r in (whole + inside).sortedBy { it.first }) {
        val last = out.lastOrNull()
        if (last != null && r.first <= last.last) out[out.lastIndex] = last.first..maxOf(last.last, r.last) else out += r
    }
    return out
}
