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
 * The recognizers to try on one image, in order: the phone's own languages' scripts. A phone set to Hindi or Chinese
 * starts with that script (its recognizer reads Latin too), others with Latin. A script the phone doesn't list is tried
 * only when a reading shows its letters ([readScripts]), never just because a reading was unsure: most unsure readings
 * are photos with a little text, and a second recognizer doubled their cost.
 */
fun ocrOrder(languageTags: List<String>): List<OcrScript> {
    val mine = ocrScripts(languageTags) - OcrScript.LATIN
    val first = ocrScripts(languageTags.take(1)) - OcrScript.LATIN
    return (first + OcrScript.LATIN + mine).distinct()
}

private val DEVANAGARI_LETTERS = Regex("[\\u0900-\\u097F\\uA8E0-\\uA8FF]")

/** One recognizer's text and its own confidence in it (0 to 1, averaged over the text). */
class OcrReading(val text: String, val confidence: Float) {
    /** Text that looks broken, or that the recognizer itself doubts: worth trying the next script. A short, sure reading is not (a one-line screenshot). */
    val sparse: Boolean get() = looksBroken(text) || confidence < SURE

    companion object {
        val NONE = OcrReading("", 0f)

        /**
         * Below this the recognizer itself doubts its reading. ML Kit's line confidences, on the emulator: about 0.85
         * for a script read by its own recognizer, about 0.4 for Hindi read by the Latin one (letters it guessed).
         */
        const val SURE = 0.6f
    }
}

/**
 * Reads with each script of [order] until one gives clean, confident text, keeping the best reading so far. A blank
 * reading means the image holds no text (or a recognizer is not there yet), so the other scripts are not tried. An
 * unsure reading that holds Devanagari letters (a Hindi bill on an English phone) tries Devanagari next, when [order]
 * doesn't have it.
 */
suspend fun readScripts(order: List<OcrScript>, read: suspend (OcrScript) -> OcrReading): String {
    var best = OcrReading.NONE
    val queue = ArrayDeque(order)
    var devanagari = OcrScript.DEVANAGARI in order
    while (true) {
        val script = queue.removeFirstOrNull() ?: break
        val reading = read(script)
        best = maxOf(best, reading, compareBy({ !it.sparse }, { it.text.isNotBlank() && !looksBroken(it.text) }, { it.confidence }))
        if (!best.sparse || reading.text.isBlank()) break
        if (!devanagari && DEVANAGARI_LETTERS.containsMatchIn(reading.text)) {
            devanagari = true
            queue += OcrScript.DEVANAGARI
        }
    }
    return best.text
}
