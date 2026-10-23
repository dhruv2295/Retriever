package app.retriever.core

/*
 * Chinese, Japanese and Korean are written without spaces between words, so unicode61 keeps a whole run as one token
 * and a word inside it ("資料" in "会議の資料を送ります") is never found. As Lucene's CJKBigramFilter does, the keyword
 * tables hold each run as its overlapping two-character pieces, and a query run is searched as the phrase of its
 * pieces. Thai and other unspaced scripts have no such convention and are left whole.
 */

/** Han, Hiragana, Katakana and Hangul letters; punctuation in their blocks (・, 「, 。) is not. */
internal fun isCjk(codePoint: Int): Boolean = codePoint >= 0x1100 && when (codePoint) {
    in 0x1100..0x11FF, in 0x3005..0x3007, in 0x3041..0x309A, in 0x309D..0x309F, in 0x30A1..0x30FA, in 0x30FC..0x30FF,
    in 0x3131..0x318E, in 0x31F0..0x31FF, in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xAC00..0xD7A3, in 0xF900..0xFAFF,
    in 0xFF66..0xFF9F, in 0x20000..0x323AF,
    -> true

    else -> false
}

private fun codePointAt(s: String, i: Int): Int {
    val c = s[i]
    if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
        return 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
    }
    return c.code
}

private fun width(codePoint: Int) = if (codePoint >= 0x10000) 2 else 1

internal fun hasCjk(s: String?): Boolean {
    if (s == null) return false
    // A low surrogate on its own is no CJK code point, so stepping by code unit is safe.
    for (i in s.indices) if (s[i].code >= 0x1100 && isCjk(codePointAt(s, i))) return true
    return false
}

/** [s] with each CJK run written as its overlapping two-character pieces between spaces; one character stays as it is. */
internal fun cjkBigrams(s: String): String {
    if (!hasCjk(s)) return s
    val out = StringBuilder(s.length * 3)
    var i = 0
    while (i < s.length) {
        val cp = codePointAt(s, i)
        if (!isCjk(cp)) {
            out.appendRange(s, i, i + width(cp))
            i += width(cp)
            continue
        }
        // The run's characters, as their start offsets in s.
        val starts = ArrayList<Int>()
        var j = i
        while (j < s.length) {
            val c = codePointAt(s, j)
            if (!isCjk(c)) break
            starts += j
            j += width(c)
        }
        starts += j
        out.append(' ')
        if (starts.size == 2) {
            out.appendRange(s, i, j)
        } else {
            for (k in 0 until starts.size - 2) {
                if (k > 0) out.append(' ')
                out.appendRange(s, starts[k], starts[k + 2])
            }
        }
        out.append(' ')
        i = j
    }
    return out.toString()
}

/** A single CJK character, which matches as a prefix: its two-character pieces start with it. */
internal fun singleCjk(s: String): Boolean = s.isNotEmpty() && isCjk(codePointAt(s, 0)) && width(codePointAt(s, 0)) == s.length
