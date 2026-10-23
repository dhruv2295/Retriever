package app.retriever.core

import kotlin.math.ln

/**
 * TF-IDF is per file, so a PDF with many chunks does not masquerade as many documents. The caller passes a sample of
 * at most [FILES] files (the newest, plus those asked about) and each counts by its first [TEXT] characters, so the
 * maps and each recount stay small however large the library.
 */
internal class ContentSuggestions {
    private val versions = mutableMapOf<Long, Long>()
    private val cached = mutableMapOf<Set<Long>?, List<String>>()
    private val terms = mutableMapOf<Long, Map<String, Int>>()
    private val kinds = mutableMapOf<Long, Kind>()

    fun refresh(current: Map<Long, Long>, file: (Long) -> SourceFile? = { null }, text: (Long) -> List<String>) {
        if (current == versions) return
        cached.clear()
        terms.keys.retainAll(current.keys)
        kinds.keys.retainAll(current.keys)
        for ((id, version) in current) {
            if (versions[id] == version) continue
            val f = file(id)
            terms[id] = frequencies(opening(text(id)), f?.mime.orEmpty())
            f?.let { kinds[id] = it.kind }
        }
        versions.clear()
        versions.putAll(current)
    }

    fun suggestions(ids: Set<Long>?): List<String> = cached.getOrPut(ids) { distinctiveTerms(terms, ids, kinds) }

    companion object {
        /** Read files sampled, newest first: plenty for three chips. */
        const val FILES = 300

        /** Characters counted per file. */
        const val TEXT = 4_000

        /** Chunks read per file, enough to fill [TEXT]. */
        const val CHUNKS = 8

        /** The first [TEXT] characters of [texts]: whole chunks, then the last one cut at a space. */
        private fun opening(texts: List<String>): List<String> {
            var left = TEXT
            val out = ArrayList<String>()
            for (t in texts) {
                if (left <= 0) break
                out += if (t.length <= left) t else t.substring(0, left).substringBeforeLast(' ')
                left -= t.length
            }
            return out
        }
    }
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

/**
 * The three best terms, varied: none holds another's words ("garden hose" and "hose" never both), and each comes from a
 * kind of file the ones before it don't (by the file that scored it highest), while one is left.
 */
internal fun distinctiveTerms(files: Map<Long, Map<String, Int>>, ids: Set<Long>? = null, kinds: Map<Long, Kind> = emptyMap()): List<String> {
    val documentFrequency = mutableMapOf<String, Int>()
    files.values.forEach { terms -> terms.keys.forEach { documentFrequency[it] = (documentFrequency[it] ?: 0) + 1 } }
    val selected = files.filterKeys { ids == null || it in ids }
    val recurring = selected.values.flatMap { it.keys }.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
    val scores = mutableMapOf<String, Double>()
    val from = mutableMapOf<String, Kind?>()
    for ((id, terms) in selected) {
        val total = terms.values.sum().toDouble()
        for ((term, count) in terms) {
            if (term !in recurring) continue
            val tfIdf = count / total * (1 + ln((files.size + 1.0) / (documentFrequency.getValue(term) + 1.0)))
            val score = tfIdf * ln(1.0 + term.count(Char::isLetter))
            if (score > (scores[term] ?: 0.0)) {
                scores[term] = score
                from[term] = kinds[id]
            }
        }
    }
    val best = compareByDescending<String> { scores.getValue(it) }.thenBy { it }
    val picked = mutableListOf<String>()
    fun fits(term: String) = term !in picked && picked.none { p -> p.split(' ').containsAll(term.split(' ')) || term.split(' ').containsAll(p.split(' ')) }
    for (newKind in listOf(true, false)) {
        for (term in scores.keys.sortedWith(best)) {
            if (picked.size == 3) break
            if (fits(term) && (!newKind || picked.none { from[it] == from[term] })) picked += term
        }
    }
    return picked.sortedWith(best).takeIf { it.size >= 3 }.orEmpty()
}
