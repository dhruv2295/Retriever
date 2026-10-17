package app.retriever.core

/**
 * Lowercases and strips Latin diacritics ("Café" -> "cafe"). Only the combining marks U+0300-036F go, so the vowel
 * signs of Devanagari and other scripts stay and Hindi words keep their spelling. commonMain has no Unicode
 * normaliser, so each platform decomposes (NFD) in its own actual.
 */
expect fun foldLatin(s: String): String

internal fun isLatinMark(c: Char) = c.code in 0x300..0x36F
