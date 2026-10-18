package app.retriever.core

/** [at]: the capture time (taken, else modified), so the newest file comes first within a match class. */
class NameEntry(val id: Long, val name: String, val folder: String, val kind: Kind, val at: Long = 0L)

/** Best first. */
enum class MatchClass { EXACT, PREFIX, SUBSTRING, TYPO }

/** [exactName]: an EXACT match whose words are all tokens of the file name itself, not just of its folder. */
data class NameHit(val id: Long, val match: MatchClass, val exactName: Boolean = false)

/**
 * File names and folders searched in memory while the user types. Snapshots are immutable: [update] returns a new index
 * and leaves the old one valid for searches already running. Slots are newest first.
 *
 * Structures, all over the distinct tokens of "name folder" (Store.ftsTokens, then [foldLatin]):
 *  - [tokens]: sorted, so exact is a binary search and prefix is the range that follows it;
 *  - [postings]: slots (positions in [entries]) per token;
 *  - [grams]: character trigram -> sorted token numbers, for substring and typo candidates.
 */
class NameIndex private constructor(
    private val entries: List<NameEntry>,
    private val tokens: Array<String>,
    private val postings: Array<IntArray>,
    private val grams: HashMap<Int, IntArray>,
) {
    val size get() = entries.size

    /** Bytes held in the int and reference arrays (not the strings), for the memory budget. */
    val arrayBytes: Long
        get() = postings.sumOf { it.size * 4L } + grams.values.sumOf { it.size * 4L } + tokens.size * 8L

    /** Drops [removed] and adds or replaces [upserts], in one build. */
    // ponytail: a batch rebuilds everything (about 0.1 s for 50k names); patch the posting arrays if batches get frequent.
    fun update(removed: Collection<Long>, upserts: Collection<NameEntry>): NameIndex {
        if (removed.isEmpty() && upserts.isEmpty()) return this
        val gone = removed.toHashSet().also { s -> upserts.mapTo(s) { it.id } }
        return build(entries.filter { it.id !in gone } + upserts.distinctBy { it.id })
    }

    /**
     * Files whose name or folder matches every word of [query] (else all but the most common words, one dropped at a time) and that [admit] lets through, best match class first
     * and newest first within one, capped at [limit]. [admit] runs before the cap, so a filter never empties a full page.
     */
    fun search(query: String, kinds: Set<Kind>? = null, limit: Int = 100, admit: ((NameEntry) -> Boolean)? = null): List<NameHit> {
        if (limit <= 0) return emptyList()
        val words = ArrayList<String>().also { tokenize(query) { t -> it += t } }
        if (words.isEmpty() || entries.isEmpty()) return emptyList()
        val per = words.map(::classify)
        val live = words.indices.toMutableList()
        while (true) {
            val out = hits(live.map { words[it] }, live.map { per[it] }, kinds, limit, admit, relaxed = live.size < words.size)
            if (out.isNotEmpty() || live.size < 2) return out
            // Nothing matched every word: drop the word that matches the most files, as Store's token retry does.
            live.remove(live.maxBy { w -> per[w].count { it != NONE } })
        }
    }

    /** [relaxed]: a word was dropped, so no hit holds every query word and none is an exact name. */
    private fun hits(
        words: List<String>,
        classes: List<ByteArray>,
        kinds: Set<Kind>?,
        limit: Int,
        admit: ((NameEntry) -> Boolean)?,
        relaxed: Boolean,
    ): List<NameHit> {
        val rank = classes[0].copyOf()
        for (k in 1 until classes.size) {
            for (s in rank.indices) {
                val cur = classes[k][s]
                rank[s] = if (rank[s] == NONE || cur == NONE) NONE else maxOf(rank[s], cur)
            }
        }
        val out = ArrayList<NameHit>()
        for (c in MatchClass.entries) {
            for (s in rank.indices) {
                if (rank[s] != (c.ordinal + 1).toByte()) continue
                val e = entries[s]
                if (kinds != null && e.kind !in kinds) continue
                if (admit != null && !admit(e)) continue
                val inName = !relaxed && c == MatchClass.EXACT && ArrayList<String>().also { tokenize(e.name) { t -> it += t } }.containsAll(words)
                out += NameHit(e.id, c, inName)
                if (out.size >= limit) return out
            }
        }
        return out
    }

    /** Per slot, the best class of [q] (class ordinal + 1, or NONE). Typos run only when nothing closer matched. */
    private fun classify(q: String): ByteArray {
        val r = ByteArray(entries.size)
        var found = false
        fun mark(token: Int, c: MatchClass) {
            val v = (c.ordinal + 1).toByte()
            for (s in postings[token]) if (r[s] == NONE || r[s] > v) r[s] = v
            found = true
        }
        val lo = lowerBound(q)
        var i = lo
        while (i < tokens.size && tokens[i].startsWith(q)) {
            mark(i, if (tokens[i].length == q.length) MatchClass.EXACT else MatchClass.PREFIX)
            i++
        }
        if (q.length >= 3) {
            for (t in withAllGrams(q)) if (!tokens[t].startsWith(q) && tokens[t].contains(q)) mark(t, MatchClass.SUBSTRING)
        }
        val edits = if (q.length >= 6) {
            2
        } else if (q.length >= 3) {
            1
        } else {
            0
        }
        if (!found && edits > 0) {
            // An edit spoils at most 3 trigrams, but an adjacent swap (one OSA edit) spoils 4.
            val need = maxOf(1, q.length - 2 - 4 * edits)
            if (q.length - 2 - 4 * edits < 1) {
                // A short word can lose every trigram to one edit ('tlak' vs 'talk'), so compare by length instead.
                for (t in tokens.indices) {
                    if (kotlin.math.abs(tokens[t].length - q.length) <= edits &&
                        withinEdits(q, tokens[t], edits)
                    ) {
                        mark(t, MatchClass.TYPO)
                    }
                }
            } else {
                val shared = IntArray(tokens.size)
                for (g in 0..q.length - 3) {
                    for (t in grams[gram(q, g)] ?: continue) {
                        if (++shared[t] == need && kotlin.math.abs(tokens[t].length - q.length) <= edits &&
                            withinEdits(q, tokens[t], edits)
                        ) {
                            mark(t, MatchClass.TYPO)
                        }
                    }
                }
            }
        }
        return r
    }

    /** Tokens containing every trigram of [q] (a superset of those containing [q]); lists are sorted, so intersect. */
    private fun withAllGrams(q: String): IntArray {
        var cur: IntArray? = null
        for (g in 0..q.length - 3) {
            val list = grams[gram(q, g)] ?: return IntArray(0)
            val c = cur
            cur = if (c == null) list else intersect(c, list)
            if (cur.isEmpty()) break
        }
        return cur ?: IntArray(0)
    }

    private fun intersect(a: IntArray, b: IntArray): IntArray {
        val out = IntArray(minOf(a.size, b.size))
        var i = 0
        var j = 0
        var n = 0
        while (i < a.size && j < b.size) {
            if (a[i] < b[j]) {
                i++
            } else if (a[i] > b[j]) {
                j++
            } else {
                out[n++] = a[i]
                i++
                j++
            }
        }
        return out.copyOf(n)
    }

    private fun lowerBound(q: String): Int {
        var lo = 0
        var hi = tokens.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tokens[mid] < q) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val NONE: Byte = 0
        val EMPTY = build(emptyList())

        fun build(unsorted: List<NameEntry>): NameIndex {
            // Stable, and near-linear on an update's already sorted entries.
            val entries = unsorted.sortedByDescending { it.at }
            val byToken = HashMap<String, Ints>()
            entries.forEachIndexed { slot, e ->
                val add = { t: String ->
                    val list = byToken.getOrPut(t) { Ints() }
                    if (list.last() != slot) list.add(slot) // a token twice in one name counts once
                }
                tokenize(e.name, add)
                tokenize(e.folder, add)
            }
            val tokens = byToken.keys.sorted().toTypedArray()
            val postings = Array(tokens.size) { byToken.getValue(tokens[it]).toArray() }
            val gramLists = HashMap<Int, Ints>()
            tokens.forEachIndexed { n, t ->
                for (g in 0..t.length - 3) {
                    val list = gramLists.getOrPut(gram(t, g)) { Ints() }
                    if (list.last() != n) list.add(n)
                }
            }
            val grams = HashMap<Int, IntArray>(gramLists.size * 2)
            for ((k, v) in gramLists) grams[k] = v.toArray()
            return NameIndex(entries, tokens, postings, grams)
        }

        private fun isWordChar(c: Char) = c.isLetter() || when (c.category) {
            CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK,
            CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER,
            -> true

            else -> false
        }

        /**
         * Folded tokens of [s]: the same split as Store.ftsTokens (runs of letters, marks and digits) followed by
         * [foldLatin]. It is a loop rather than that regex because the regex took about half of the 50k-name build.
         */
        internal fun tokenize(s: String, out: (String) -> Unit) {
            var start = -1
            for (i in 0..s.length) {
                if (i < s.length && isWordChar(s[i])) {
                    if (start < 0) start = i
                } else if (start >= 0) {
                    out(foldLatin(s.substring(start, i)))
                    start = -1
                }
            }
        }

        // A hash, not a pack: a collision only adds a candidate that the contains/edit check then rejects.
        private fun gram(s: String, at: Int) = (s[at].code * 31 + s[at + 1].code) * 31 + s[at + 2].code

        /**
         * Optimal string alignment distance of [a] and [b] is at most [max]: Levenshtein where swapping two adjacent
         * letters costs 1. It stops once two whole rows exceed [max] (a swap reaches back two rows).
         */
        fun withinEdits(a: String, b: String, max: Int): Boolean {
            if (kotlin.math.abs(a.length - b.length) > max) return false
            var prev2 = IntArray(b.length + 1)
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            var prevMin = 0
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var d = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d = minOf(d, prev2[j - 2] + 1)
                    cur[j] = d
                    if (d < rowMin) rowMin = d
                }
                if (rowMin > max && prevMin > max) return false
                prevMin = rowMin
                val t = prev2
                prev2 = prev
                prev = cur
                cur = t
            }
            return prev[b.length] <= max
        }
    }

    private class Ints {
        private var a = IntArray(2)
        private var n = 0
        fun last() = if (n == 0) -1 else a[n - 1]
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun toArray() = a.copyOf(n)
    }
}
