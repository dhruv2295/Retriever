package app.retriever.core

import kotlin.math.ln

/** TF-IDF is per file, so a PDF with many chunks does not masquerade as many documents. */
internal class ContentSuggestions {
    private val versions = mutableMapOf<Long, Long>()
    private val cached = mutableMapOf<Set<Long>?, List<String>>()
    private val terms = mutableMapOf<Long, Map<String, Int>>()

    fun refresh(current: Map<Long, Long>, text: (Long) -> List<String>) {
        if (current == versions) return
        cached.clear()
        terms.keys.retainAll(current.keys)
        for ((id, version) in current) {
            if (versions[id] != version) terms[id] = frequencies(text(id))
        }
        versions.clear()
        versions.putAll(current)
    }

    fun suggestions(ids: Set<Long>?): List<String> = cached.getOrPut(ids) { distinctiveTerms(terms, ids) }
}

internal fun frequencies(texts: List<String>): Map<String, Int> = texts.asSequence()
    .flatMap { Store.ftsTokens(it).asSequence() }
    .filter { it.length >= 3 && it.none(Char::isDigit) && it !in suggestionStopwords }
    .groupingBy { it }.eachCount()

internal fun distinctiveTerms(files: Map<Long, Map<String, Int>>, ids: Set<Long>? = null): List<String> {
    val documentFrequency = mutableMapOf<String, Int>()
    files.values.forEach { terms -> terms.keys.forEach { documentFrequency[it] = (documentFrequency[it] ?: 0) + 1 } }
    val scores = mutableMapOf<String, Double>()
    for ((id, terms) in files) {
        if (ids != null && id !in ids) continue
        val total = terms.values.sum().toDouble()
        for ((term, count) in terms) {
            val tfIdf = count / total * (1 + ln((files.size + 1.0) / (documentFrequency.getValue(term) + 1.0)))
            scores[term] = maxOf(scores[term] ?: 0.0, tfIdf)
        }
    }
    return scores.keys.sortedWith(compareByDescending<String> { scores.getValue(it) }.thenBy { it }).take(6)
        .takeIf { it.size >= 4 }.orEmpty()
}
