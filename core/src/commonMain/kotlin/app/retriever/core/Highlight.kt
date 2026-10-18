package app.retriever.core

private val NAME_WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

/**
 * The parts of a file name keyword search matched: each whole word equal to a query word, or starting with the
 * last one when it has three letters or more. Call it for a file found by its name only.
 */
fun nameMatchRanges(name: String, query: String): List<IntRange> {
    val tokens = Store.ftsTokens(query)
    if (tokens.isEmpty()) return emptyList()
    val words = tokens.toSet()
    val prefix = tokens.last().takeIf { it.length >= 3 }
    return NAME_WORD.findAll(name).filter { m ->
        val w = m.value.lowercase()
        w in words || (prefix != null && w.startsWith(prefix))
    }.map { it.range }.toList()
}

/** Unicode letters and combining marks stay together; a query never marks an interior fragment. */
internal fun wordMatchRanges(text: String, words: List<String>): List<IntRange> = NAME_WORD.findAll(text)
    .filter { match ->
        val token = foldLatin(match.value)
        words.any { word -> token == word || (word.length >= 3 && token.startsWith(word)) }
    }.map { it.range }.toList()
