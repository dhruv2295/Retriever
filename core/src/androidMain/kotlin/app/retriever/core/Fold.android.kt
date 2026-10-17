package app.retriever.core

import java.text.Normalizer

actual fun foldLatin(s: String): String {
    val lower = s.lowercase()
    if (lower.all { it.code < 0x80 }) return lower
    return Normalizer.normalize(lower, Normalizer.Form.NFD).filterNot(::isLatinMark)
}
