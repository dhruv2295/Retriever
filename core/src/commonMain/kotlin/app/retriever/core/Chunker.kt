package app.retriever.core

object Chunker {
    private val PARAGRAPHS = Regex("\\n\\s*\\n")
    private val WORDS = Regex("\\s+")

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
        for (paragraph in text.split(PARAGRAPHS)) {
            for (word in paragraph.split(WORDS).flatMap { it.pieces(maxChars) }) {
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
        // A last chunk of a few words would embed as little more than the file's name, close to any query: it joins the
        // one before, or the two split evenly when together they pass the caps.
        if (out.size > 1 && out.last().length < maxChars / 4) {
            val last = out.removeAt(out.lastIndex)
            val words = out.removeAt(out.lastIndex).split(' ') + last.split(' ')
            val joined = words.joinToString(" ")
            if (joined.length <= maxChars && words.size <= maxWords) {
                out += joined
            } else {
                var cut = 0
                var length = 0
                while (cut < words.size - 1 && length < joined.length / 2) length += words[cut++].length + 1
                out += words.subList(0, cut).joinToString(" ")
                out += words.subList(cut, words.size).joinToString(" ")
            }
        }
        return out
    }

    /** An unspaced run (CJK, Thai, a long URL) in pieces of at most [max] characters, cut between grapheme-safe units. */
    private fun String.pieces(max: Int): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        while (start < length) {
            var end = minOf(start + max, length)
            if (end < length) {
                var safe = end
                while (safe > start && (this[safe - 1].isHighSurrogate() || this[safe].isContinuation())) safe--
                end = if (safe > start) {
                    safe
                } else if (this[end - 1].isHighSurrogate()) {
                    end - 1
                } else {
                    end
                }
            }
            out += substring(start, end)
            start = end
        }
        return out
    }

    private fun Char.isContinuation() = isLowSurrogate() || category == CharCategory.NON_SPACING_MARK || category == CharCategory.COMBINING_SPACING_MARK
}

/**
 * What the model is fed for a piece. EmbeddingGemma 2 model card: task prefixes apply to text only; image, video
 * and audio inputs (with their timestamp text) go in raw. [prefixes] false is for RetrievalEval's comparison.
 */
fun Piece.modelParts(fileName: String, prefixes: Boolean = true): List<Part> =
    if (prefixes && parts.all { it is Part.Text }) parts.map { Part.Text(Prefix.document(fileName, (it as Part.Text).text)) } else parts

/**
 * Prompt prefixes, sourced from the HF config_sentence_transformers.json and the EmbeddingGemma 2 model card.
 * The LiteRT-LM page's prefixes differ from these. Images and audio take none.
 */
object Prefix {
    fun document(title: String, text: String) = "title: ${title.ifBlank { "none" }} | text: $text"

    fun query(q: String) = "task: search result | query: $q"
}
