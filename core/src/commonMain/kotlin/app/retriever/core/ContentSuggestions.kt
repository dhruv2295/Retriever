package app.retriever.core

import kotlin.math.ln

/** TF-IDF is per file, so a PDF with many chunks does not masquerade as many documents. */
internal class ContentSuggestions {
    private val versions = mutableMapOf<Long, Long>()
    private val cached = mutableMapOf<Set<Long>?, List<String>>()
    private val terms = mutableMapOf<Long, Map<String, Int>>()

    fun refresh(current: Map<Long, Long>, mime: (Long) -> String = { "" }, text: (Long) -> List<String>) {
        if (current == versions) return
        cached.clear()
        terms.keys.retainAll(current.keys)
        for ((id, version) in current) {
            if (versions[id] != version) terms[id] = frequencies(text(id), mime(id))
        }
        versions.clear()
        versions.putAll(current)
    }

    fun suggestions(ids: Set<Long>?): List<String> = cached.getOrPut(ids) { distinctiveTerms(terms, ids) }
}

private val suggestionWord = Regex("[\\p{L}\\p{M}\\p{N}_]+")
private val programmingWords = """
    true false null nil none undefined nan api set get id ids key keys value values code codes success error
    boolean bool string int integer float double object array enum class function fun def var val const let return
    import export public private protected static void if else for while switch case break continue try catch throw
    async await self this new json csv xml http https url uri sql select insert update delete from where
""".trimIndent().split(Regex("\\s+")).toSet()

private fun camelCase(word: String): Boolean = word.zipWithNext().any { (a, b) -> a.isLowerCase() && b.isUpperCase() }

/** Ratios apply per chunk, so a table cannot drown out a prose paragraph elsewhere in the file. */
internal fun structuredText(text: String): Boolean {
    val visible = text.filterNot(Char::isWhitespace)
    if (visible.isEmpty()) return true
    val words = suggestionWord.findAll(text).map { it.value }.toList()
    val structured = visible.count { it.isDigit() || it in ",:;{}[]|=<>\\\"_`" }
    return structured.toDouble() / visible.length >= 0.2 ||
        (words.isNotEmpty() && words.count(::camelCase).toDouble() / words.size >= 0.15)
}

internal fun frequencies(texts: List<String>, mime: String = ""): Map<String, Int> {
    val type = mime.lowercase()
    if (listOf("spreadsheet", "excel", "csv", "json", "javascript", "typescript", "python", "java", "kotlin", "swift", "x-c", "sql", "xml", "shell")
            .any { it in type }
    ) {
        return emptyMap()
    }
    val terms = mutableListOf<String>()
    for (text in texts.filterNot(::structuredText)) {
        var previous: MatchResult? = null
        for (match in suggestionWord.findAll(text)) {
            val raw = match.value
            val term = raw.lowercase()
            val letters = raw.filter(Char::isLetter)
            if (term.length <= 2 || raw.any(Char::isDigit) || '_' in raw || camelCase(raw) ||
                (letters.isNotEmpty() && letters.length <= 5 && letters.all(Char::isUpperCase)) ||
                term in suggestionStopwords || term in programmingWords
            ) {
                previous = null
                continue
            }
            terms += term
            previous?.let { before ->
                val gap = text.substring(before.range.last + 1, match.range.first)
                if (gap.isNotEmpty() && gap.all { it == ' ' }) terms += "${before.value.lowercase()} $term"
            }
            previous = match
        }
    }
    return terms.groupingBy { it }.eachCount()
}

internal fun distinctiveTerms(files: Map<Long, Map<String, Int>>, ids: Set<Long>? = null): List<String> {
    val documentFrequency = mutableMapOf<String, Int>()
    files.values.forEach { terms -> terms.keys.forEach { documentFrequency[it] = (documentFrequency[it] ?: 0) + 1 } }
    val selected = files.filterKeys { ids == null || it in ids }
    val recurring = selected.values.flatMap { it.keys }.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
    val scores = mutableMapOf<String, Double>()
    for (terms in selected.values) {
        val total = terms.values.sum().toDouble()
        for ((term, count) in terms) {
            if (term !in recurring) continue
            val tfIdf = count / total * (1 + ln((files.size + 1.0) / (documentFrequency.getValue(term) + 1.0)))
            val score = tfIdf * ln(1.0 + term.count(Char::isLetter))
            scores[term] = maxOf(scores[term] ?: 0.0, score)
        }
    }
    return scores.keys.sortedWith(compareByDescending<String> { scores.getValue(it) }.thenBy { it }).take(6)
        .takeIf { it.size >= 3 }.orEmpty()
}
