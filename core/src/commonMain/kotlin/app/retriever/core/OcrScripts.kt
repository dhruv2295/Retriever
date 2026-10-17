package app.retriever.core

/** The scripts ML Kit's text recognition ships recognizers for. */
enum class OcrScript { LATIN, DEVANAGARI, CHINESE, JAPANESE, KOREAN }

/** Latin always (UI text, URLs, numbers), plus the script of each language tag the user's phone is set to. */
fun ocrScripts(languageTags: List<String>): Set<OcrScript> {
    val scripts = mutableSetOf(OcrScript.LATIN)
    for (tag in languageTags) {
        when (tag.substringBefore('-').substringBefore('_').lowercase()) {
            "hi", "mr", "ne", "sa", "kok" -> scripts += OcrScript.DEVANAGARI
            "zh" -> scripts += OcrScript.CHINESE
            "ja" -> scripts += OcrScript.JAPANESE
            "ko" -> scripts += OcrScript.KOREAN
        }
    }
    return scripts
}

/**
 * The recognizers to try on one image, in order. The phone's own languages only pick what goes first: a phone set to
 * Hindi or Chinese starts with that script (its recognizer reads Latin too), others with Latin. Devanagari is always a
 * fallback, as an English phone in India still holds Hindi bills and chats.
 */
fun ocrOrder(languageTags: List<String>): List<OcrScript> {
    val mine = ocrScripts(languageTags) - OcrScript.LATIN
    val first = ocrScripts(languageTags.take(1)) - OcrScript.LATIN
    return (first + OcrScript.LATIN + mine + OcrScript.DEVANAGARI).distinct()
}

/** One recognizer's text and its own confidence in it (0 to 1, averaged over the text). */
class OcrReading(val text: String, val confidence: Float) {
    /** Too little text, text that looks broken, or text the recognizer itself doubts: worth trying the next script. */
    val sparse: Boolean get() = text.trim().length < MIN_TEXT_CHARS || looksBroken(text) || confidence < SURE

    companion object {
        val NONE = OcrReading("", 0f)

        /**
         * Below this the recognizer itself doubts its reading. ML Kit's line confidences, on the emulator: about 0.85
         * for a script read by its own recognizer, about 0.4 for Hindi read by the Latin one (letters it guessed).
         */
        const val SURE = 0.6f
    }
}

/** Reads with each script of [order] until one gives enough clean, confident text, keeping the best reading so far. */
suspend fun readScripts(order: List<OcrScript>, read: suspend (OcrScript) -> OcrReading): String {
    var best = OcrReading.NONE
    for (script in order) {
        best = maxOf(best, read(script), compareBy({ !it.sparse }, { it.text.isNotBlank() && !looksBroken(it.text) }, { it.confidence }))
        if (!best.sparse) break
    }
    return best.text
}
