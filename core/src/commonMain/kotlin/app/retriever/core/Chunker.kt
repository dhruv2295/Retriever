package app.retriever.core

object Chunker {
    /** About 300 model tokens; English runs roughly 0.75 words per token. */
    const val WORDS_PER_CHUNK = 220

    /**
     * Code, URLs and tables run far more tokens per word; a character cap keeps them under the model's token window.
     * Real PDFs measured ~2.2 chars per token; IndexEngine splits any chunk that still overflows.
     */
    const val CHARS_PER_CHUNK = 800

    /** Splits [text] into chunks of at most [maxWords] words and [maxChars] characters, keeping paragraph breaks where possible. */
    fun chunks(text: String, maxWords: Int = WORDS_PER_CHUNK, maxChars: Int = CHARS_PER_CHUNK): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var words = 0
        for (paragraph in text.split(Regex("\\n\\s*\\n"))) {
            for (rawWord in paragraph.split(Regex("\\s+"))) {
                if (rawWord.isEmpty()) continue
                val word = rawWord.take(maxChars)
                if (current.isNotEmpty() && (words == maxWords || current.length + 1 + word.length > maxChars)) {
                    out += current.toString()
                    current.clear()
                    words = 0
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
                words++
            }
            if (words >= maxWords * 3 / 4) {
                out += current.toString()
                current.clear()
                words = 0
            }
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }
}

/** Prompt prefixes from the EmbeddingGemma 2 model card. Images and audio take none. */
object Prefix {
    fun document(title: String, text: String) = "title: ${title.ifBlank { "none" }} | text: $text"

    fun query(q: String) = "task: search result | query: $q"
}
