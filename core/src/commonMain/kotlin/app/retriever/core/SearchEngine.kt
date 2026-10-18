package app.retriever.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * One search. [from] (inclusive) and [to] (exclusive) are epoch ms, matched against a file's capture time (taken, else
 * modified); [fileIds] keeps only those files, e.g. the starred ones. Null: no such filter. [onlyStarred] says
 * [fileIds] are the starred files, so the keyword lane can filter by them in SQL before its cut.
 */
data class SearchRequest(
    val text: String,
    val kinds: Set<Kind>? = null,
    val from: Long? = null,
    val to: Long? = null,
    val fileIds: Set<Long>? = null,
    val limit: Int = 20,
    val mode: SearchEngine.Mode = SearchEngine.Mode.MERGED,
    val onlyStarred: Boolean = false,
)

/** Whether sorted [a] holds [x]; common Kotlin has no binarySearch on primitive arrays. */
private fun sortedHas(a: LongArray, x: Long): Boolean {
    var lo = 0
    var hi = a.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (a[mid] < x) {
            lo = mid + 1
        } else if (a[mid] > x) {
            hi = mid - 1
        } else {
            return true
        }
    }
    return false
}

/** [embedder] is swapped when the model reopens, so the loaded vector table outlives any one embedder. */
class SearchEngine(
    private val store: Store,
    @Volatile var embedder: Embedder,
    private val tuning: SearchTuning = SearchTuning(),
    /** Splits files found only by meaning into strong and weak; null counts them all strong. */
    private val rule: RelevanceRule? = RelevanceRule.shipped,
    /** The in-memory name index once it is built; null before, when names come from the file keyword table. */
    private val nameIndex: () -> NameIndex? = { null },
) {
    enum class Mode { VECTOR, KEYWORD, MERGED }

    data class Hit(
        val file: SourceFile,
        val fileId: Long,
        /** 0 when only the file's name or folder matched and it has no chunk to point at. */
        val chunkId: Long,
        val locator: String,
        /** Keyword hits mark matched words with \u0002 and \u0003. */
        val snippet: String?,
        val score: Double,
        /** Found by its name or folder, so the matched words are in the name. */
        val byName: Boolean,
    ) {
        // Swift gets no default arguments, so six-argument callers (the iOS fixtures) use this one.
        constructor(file: SourceFile, fileId: Long, chunkId: Long, locator: String, snippet: String?, score: Double) :
            this(file, fileId, chunkId, locator, snippet, score, false)
    }

    /**
     * [vectorLane] is false when results are keyword-only (other model's index, closed or failed embedder).
     * hits[0 until strongCount] match well; the rest are weak, listed after them.
     */
    data class SearchResult(
        val hits: List<Hit>,
        val vectorLane: Boolean,
        val strongCount: Int = hits.size,
        /** What the query was understood to ask for; the facade fills it. */
        val parsed: ParsedQuery? = null,
        /** A filter found nothing, so the facade searched again without it. */
        val relaxed: Boolean = false,
    )

    /**
     * [backing] grows in place under [loadLock]; searches scan [view], whose arrays and size were fixed when it
     * was published, so later appends (beyond its size) never race with a scan.
     */
    private class Loaded(val backing: VectorTable, val deletedAtLoad: Long, val backfillsAtLoad: Long, prev: Loaded? = null) {
        val view = VectorTable(backing.chunkIds, backing.fileIds, backing.bytes, backing.scales, backing.size, backing.kinds, backing.modalities, backing.days)

        /**
         * Rows whose chunk a lookup found deleted; [scan] skips them. Carried across appends. Set from concurrent searches
         * without a lock: every write stores true, so a lost one only costs a later search another pass.
         */
        val dead: BooleanArray = prev?.dead?.takeIf { it.size >= backing.chunkIds.size }
            ?: BooleanArray(backing.chunkIds.size).also { prev?.dead?.copyInto(it) }

        @Volatile
        var anyDead: Boolean = prev?.anyDead ?: false
    }

    private val loadLock = Mutex()

    @Volatile
    private var loaded: Loaded? = null

    /** Full loads so far; tests check that concurrent first searches share one. */
    internal var fullLoads = 0
        private set

    /**
     * Loads vectors added since the last call. Rebuilds from scratch once more than 10% of the loaded rows were deleted,
     * as compaction; until then [meaningLane] marks dead rows as its lookups find them. A capture-day backfill also
     * rebuilds, since loaded rows keep the day they were loaded with. [full] forces the rebuild.
     */
    suspend fun refresh(full: Boolean = false) {
        loadLock.withLock {
            traced("vec.refresh") {
                val current = loaded
                val deleted = store.vectorsDeleted()
                val backfills = store.takenAtBackfills()
                loaded = if (full || current == null || current.backfillsAtLoad != backfills ||
                    (deleted - current.deletedAtLoad) * 10 > current.backing.size
                ) {
                    fullLoads++
                    // Drop the old table first and size the new one exactly, so a rebuild never holds three copies.
                    loaded = null
                    Loaded(store.loadVectors(into = VectorTable.withCapacity(store.vectorCount())), deleted, backfills)
                } else {
                    Loaded(store.loadVectors(afterId = current.backing.lastId, into = current.backing), current.deletedAtLoad, backfills, current)
                }
            }
        }
    }

    suspend fun search(query: String, mode: Mode = Mode.MERGED, limit: Int = 20, kinds: Set<Kind>? = null): SearchResult =
        search(SearchRequest(query, kinds, limit = limit, mode = mode))

    /** The keyword and name lanes run beside the query embed; the scan and fusion follow it. */
    suspend fun search(req: SearchRequest): SearchResult = coroutineScope {
        val q = req.text.trim()
        if (q.isEmpty()) return@coroutineScope SearchResult(emptyList(), false)
        val f = Filter(req)
        val embedder = embedder.takeIf { req.mode != Mode.KEYWORD && store.signature() == it.signature }
        val words = if (req.mode == Mode.VECTOR) null else async { wordLanes(q, f) }
        val qv = embedder?.let { embedQuery(it, q) }
        // Awaited before the scan, so the two never use the database at once.
        val wordLanes = words?.await().orEmpty()
        val lanes = listOfNotNull(qv?.let { meaningLane(it, f) }) + wordLanes
        val (hits, strong) = toHits(lanes, f, req.limit, folded(q))
        SearchResult(hits, qv != null, strong)
    }

    /** Phase one of a search, as the user types: the name lanes alone, no model. */
    fun names(req: SearchRequest): SearchResult {
        val q = req.text.trim()
        if (q.isEmpty()) return SearchResult(emptyList(), false)
        val f = Filter(req)
        val (hits, strong) = toHits(nameLanes(q, f), f, req.limit, folded(q))
        return SearchResult(hits, false, strong)
    }

    /** Search by example: the photo is the query, passed raw (no task prefix), vector lane only. */
    suspend fun searchImage(jpeg: ByteArray, limit: Int = 20, kinds: Set<Kind>? = null, fileIds: Set<Long>? = null): SearchResult {
        val embedder = embedder.takeIf { store.signature() == it.signature } ?: return SearchResult(emptyList(), false)
        val qv = embed(embedder, listOf(Part.Image(jpeg))) ?: return SearchResult(emptyList(), false)
        val f = Filter(SearchRequest("", kinds, fileIds = fileIds))
        val (hits, strong) = toHits(listOf(meaningLane(qv, f)), f, limit, emptyList())
        return SearchResult(hits, true, strong)
    }

    /** The last query's vector, so a search run again without its filters (the relaxed retry) embeds once. */
    private class LastQuery(val embedder: Embedder, val text: String, val vector: Vector)

    @Volatile
    private var lastQuery: LastQuery? = null

    private suspend fun embedQuery(embedder: Embedder, q: String): Vector? {
        lastQuery?.let { if (it.embedder === embedder && it.text == q) return it.vector }
        return embed(embedder, listOf(Part.Text(Prefix.query(q))))?.also { lastQuery = LastQuery(embedder, q, it) }
    }

    /** Null when the embed failed. An [EmbedderClosedException] propagates, so the caller can retire the broken model. */
    private suspend fun embed(embedder: Embedder, parts: List<Part>): Vector? = try {
        // Not traced: Trace sections must end on the thread that began them, and this call suspends.
        embedder.embed(parts)
    } catch (e: CancellationException) {
        throw e
    } catch (e: EmbedderClosedException) {
        throw e
    } catch (e: Exception) {
        store.log("query embed failed; keyword results only", e)
        null
    }

    private suspend fun meaningLane(q: Vector, f: Filter): Lane {
        refresh()
        val rule = rule
        var pass = 0
        while (true) {
            val l = loaded ?: return Lane(emptyList(), Source.MEANING)
            val histogram = rule?.histogram()
            val dead = l.dead.takeIf { l.anyDead }
            val top = traced("vec.scan") { scan(q, CANDIDATES, l.view, f.allowed, f.days, f.ids, histogram, dead) }
            val rows = traced("fuse.lookups") { store.chunks(top.rows.map { l.view.chunkIds[it] }) }
            // A picked row whose chunk is gone was deleted after the load: mark it and scan again, so it frees its slot.
            var missed = false
            for (row in top.rows) {
                if (l.view.chunkIds[row] !in rows) {
                    l.dead[row] = true
                    missed = true
                }
            }
            if (missed) l.anyDead = true
            // Past MAX_PASSES that kept finding dead rows, reload in full once; the scan after the reload is the last.
            if (missed && ++pass <= MAX_PASSES) {
                if (pass == MAX_PASSES) refresh(full = true)
                continue
            }
            val hits = top.rows.indices.mapNotNull { i ->
                val row = top.rows[i]
                rows[l.view.chunkIds[row]]?.let {
                    Candidate(it.fileId, it.id, it.locator, text = it.text, cos = top.cos[i], modality = l.view.modalities[row].toInt())
                }
            }
            return Lane(hits, Source.MEANING, if (rule != null && histogram != null) rule.minStrong(histogram) else null)
        }
    }

    private fun wordLanes(q: String, f: Filter): List<Lane> {
        // One letter matches almost every chunk (28-46 ms while typing); the names lane already answers it in about 1 ms.
        val words = if (q.length < 2) {
            emptyList()
        } else {
            traced("kw.fts") {
                val hits = store.keywordSearch(q, CANDIDATES, f.kinds, f.dates, f.onlyStarred)
                val rows = store.chunks(hits.map { it.chunkId })
                hits.mapNotNull { k -> rows[k.chunkId]?.takeIf { f.admits(it.fileId) }?.let { Candidate(it.fileId, it.id, it.locator, k.snippet) } }
            }
        }
        return listOf(Lane(words, Source.WORDS)) + nameLanes(q, f)
    }

    /**
     * Exact and prefix matches are evidence like a keyword's; substring and typo matches are a lane of their own. A file
     * whose own name holds every query word gets a lane too, which fusion puts first.
     */
    private fun nameLanes(q: String, f: Filter): List<Lane> = traced("kw.names") {
        val index = nameIndex()
        if (index == null) {
            listOf(Lane(store.fileKeywordSearch(q, CANDIDATES, f.kinds, f.dates, f::admits).map { Candidate(it) }, Source.NAME))
        } else {
            val dates = f.dates
            val hits = index.search(q, f.kinds, CANDIDATES) { e -> f.admits(e.id) && (dates == null || e.at in dates) }
            val (exact, rest) = hits.partition { it.exactName }
            val (close, loose) = rest.partition { it.match <= MatchClass.PREFIX }
            listOf(
                Lane(exact.map { Candidate(it.id) }, Source.NAME_EXACT),
                Lane(close.map { Candidate(it.id) }, Source.NAME),
                Lane(loose.map { Candidate(it.id) }, Source.NAME_LOOSE),
            )
        }
    }

    /** Rows [scan] picked, best first, with their cosines. */
    internal class Scan(val rows: IntArray, val cos: FloatArray)

    /**
     * The [n] best rows by cosine, best first, among rows whose kind [allowed] marks, whose day is in [days] and whose
     * file is in [fileIds] (sorted; null: no such filter); a min-heap over primitive arrays, so no boxing or full sort.
     * [histogram], when given, counts every admitted row's cosine by modality for [RelevanceRule]. Rows [dead] marks
     * are skipped before either.
     */
    internal fun scan(
        q: Vector,
        n: Int,
        table: VectorTable,
        allowed: BooleanArray? = null,
        days: IntRange? = null,
        fileIds: LongArray? = null,
        histogram: IntArray? = null,
        dead: BooleanArray? = null,
    ): Scan {
        val size = table.size
        val bytes = table.bytes
        val scales = table.scales
        val kinds = table.kinds
        val rowDays = table.days
        val rowFiles = table.fileIds
        val modalities = table.modalities
        val firstDay = days?.first ?: 0
        val lastDay = days?.last ?: 0
        val k = minOf(n, size)
        if (k <= 0) return Scan(IntArray(0), FloatArray(0))
        val heapScore = FloatArray(k)
        val heapRow = IntArray(k)
        var filled = 0
        if (allowed == null && days == null && fileIds == null && histogram == null && dead == null) {
            // The usual search gets a loop with no per-row checks: they cost 2-5% of the scan on the JVM bench.
            for (row in 0 until size) {
                filled = offer(heapScore, heapRow, filled, row, dotInt8(bytes, row * DIMS, q.bytes, q.scale, scales[row]))
            }
        } else {
            for (row in 0 until size) {
                if (dead != null && dead[row]) continue
                if (allowed != null && !allowed[kinds[row].toInt()]) continue
                if (days != null && (rowDays[row] < firstDay || rowDays[row] > lastDay)) continue
                if (fileIds != null && !sortedHas(fileIds, rowFiles[row])) continue
                val s = dotInt8(bytes, row * DIMS, q.bytes, q.scale, scales[row])
                if (histogram != null) histogram[modalities[row] * RelevanceRule.BINS + RelevanceRule.bin(s)]++
                filled = offer(heapScore, heapRow, filled, row, s)
            }
        }
        val order = (0 until filled).sortedByDescending { heapScore[it] }
        return Scan(IntArray(filled) { heapRow[order[it]] }, FloatArray(filled) { heapScore[order[it]] })
    }

    /** Offers [row] scored [s] to the min-heap of the best [heapScore].size rows, [filled] of them used; returns the new fill. */
    @Suppress("NOTHING_TO_INLINE") // Inlined into both scan loops, so the heap code is written once and costs no call.
    private inline fun offer(heapScore: FloatArray, heapRow: IntArray, filled: Int, row: Int, s: Float): Int {
        val k = heapScore.size
        if (filled < k) {
            var i = filled
            while (i > 0) {
                val parent = (i - 1) / 2
                if (heapScore[parent] <= s) break
                heapScore[i] = heapScore[parent]
                heapRow[i] = heapRow[parent]
                i = parent
            }
            heapScore[i] = s
            heapRow[i] = row
            return filled + 1
        }
        if (s > heapScore[0]) {
            var i = 0
            while (true) {
                val l = 2 * i + 1
                if (l >= k) break
                val c = if (l + 1 < k && heapScore[l + 1] < heapScore[l]) l + 1 else l
                if (heapScore[c] >= s) break
                heapScore[i] = heapScore[c]
                heapRow[i] = heapRow[c]
                i = c
            }
            heapScore[i] = s
            heapRow[i] = row
        }
        return filled
    }

    /** [scan] as (chunk id, cosine) pairs, for benches. */
    fun scanTop(q: Vector, n: Int, table: VectorTable, allowed: BooleanArray? = null): List<Pair<Long, Float>> =
        scan(q, n, table, allowed).let { s -> List(s.rows.size) { table.chunkIds[s.rows[it]] to s.cos[it] } }

    private enum class Source { MEANING, WORDS, NAME_EXACT, NAME, NAME_LOOSE }

    /** [minStrong]: a meaning lane's per-modality strong line from [RelevanceRule.minStrong]; null calls every hit strong. */
    private class Lane(val hits: List<Candidate>, val source: Source, val minStrong: FloatArray? = null)

    /** [text] is a vector candidate's chunk text, [snippet] a keyword candidate's; [cos] is NaN outside the vector lane. */
    private class Candidate(
        val fileId: Long,
        val chunkId: Long = 0L,
        val locator: String = "",
        val snippet: String? = null,
        val text: String? = null,
        val cos: Float = Float.NaN,
        val modality: Int = 0,
    )

    /**
     * The single per-query eligibility rule: the request's kinds, dates and starred/fileIds, in the forms the lanes check
     * them. Which files and which of their text are searchable at all is decided once at index level, by [LibrarySync]
     * and [Store.upsertFiles]; no lane adds a filter of its own.
     */
    private class Filter(req: SearchRequest) {
        val kinds = req.kinds
        val allowed = kinds?.let { k -> BooleanArray(Kind.entries.size) { Kind.entries[it] in k } }
        val dates = if (req.from == null && req.to == null) null else (req.from ?: Long.MIN_VALUE)..((req.to ?: Long.MAX_VALUE) - 1)

        // The table keeps whole UTC days, so a local day's ends are floored to the UTC days that hold them: the scan may
        // admit up to a day too much at the edges, never too little, and [admits] then cuts by the exact time.
        val days = dates?.let { day(it.first)..day(it.last) }
        val ids = req.fileIds?.toLongArray()?.apply { sort() }
        val onlyStarred = req.onlyStarred

        fun admits(fileId: Long) = ids == null || sortedHas(ids, fileId)

        fun admits(file: SourceFile) = (kinds == null || file.kind in kinds) && (dates == null || (file.takenAt ?: file.modifiedAt) in dates)

        private fun day(ms: Long) = ms.floorDiv(DAY_MS).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Fuses the lanes per file with reciprocal rank fusion. A file shows its best-ranked chunk; a name-only match
     * shows no chunk and opens the file at the start. The first [limit] fused files that pass [f] are then split:
     * strong (found by words, an exact or prefix name match, or by meaning with a chunk on or above its group's strong
     * line), then weak, each in fused order. Files named exactly by the query come first, before the [limit] cut.
     * Returns the hits and the strong count.
     */
    private fun toHits(lanes: List<Lane>, f: Filter, limit: Int, words: List<String>): Pair<List<Hit>, Int> {
        val best = HashMap<Long, Pair<Int, Candidate>>()
        val strong = HashSet<Long>()
        val byName = HashSet<Long>()
        val exact = HashSet<Long>()
        val perLane = lanes.map { lane ->
            val seen = LinkedHashSet<Long>()
            for (c in lane.hits) {
                when (lane.source) {
                    Source.MEANING -> if (lane.minStrong == null || c.cos >= lane.minStrong[c.modality]) strong += c.fileId

                    Source.WORDS -> strong += c.fileId

                    Source.NAME_EXACT -> {
                        strong += c.fileId
                        byName += c.fileId
                        exact += c.fileId
                    }

                    Source.NAME -> {
                        strong += c.fileId
                        byName += c.fileId
                    }

                    Source.NAME_LOOSE -> byName += c.fileId
                }
                if (!seen.add(c.fileId)) continue
                val rank = seen.size - 1
                val prev = best[c.fileId]
                // On a rank tie the words chunk wins: its snippet carries the highlight, a meaning chunk's does not.
                val tieToWords = rank == prev?.first && lane.source != Source.MEANING && prev.second.cos.isFinite()
                if (prev == null || (c.chunkId != 0L && (prev.second.chunkId == 0L || rank < prev.first || tieToWords))) best[c.fileId] = rank to c
            }
            seen.toList()
        }
        val fused = if (lanes.size == 1) {
            perLane[0].mapIndexed { i, id -> id to 1.0 / (i + 1) }
        } else {
            rrfMerge(perLane, tuning.rrfK, lanes.map { if (it.source == Source.MEANING) 1.0 else tuning.keywordWeight })
        }
        val ranked = fused.sortedByDescending { it.first in exact } // stable: fused order holds within each group
        return traced("fuse.lookups") {
            val picked = ArrayList<Pair<Pair<Long, Double>, SourceFile>>()
            var at = 0
            while (picked.size < limit && at < ranked.size) {
                val window = ranked.subList(at, minOf(ranked.size, at + limit))
                at += window.size
                val files = store.files(window.map { it.first })
                for (r in window) {
                    if (picked.size >= limit) break
                    val file = files[r.first] ?: continue
                    if (f.admits(file)) picked += r to file
                }
            }
            // A PDF page wins on its page image, which has no words: show that page's text instead.
            val pageText = store.firstTextAt(
                picked.mapNotNull { (r, file) ->
                    val c = best.getValue(r.first).second
                    if (file.kind == Kind.PDF && c.chunkId != 0L && c.snippet == null && c.text == null) r.first to c.locator else null
                },
            )
            val hits = picked.map { (r, file) ->
                val c = best.getValue(r.first).second
                val snippet = c.snippet ?: (c.text ?: pageText[r.first to c.locator])?.let { bestSentence(it, words) }
                Hit(file, r.first, c.chunkId, c.locator, snippet, r.second, r.first in byName)
            }
            val (s, weak) = hits.partition { it.fileId in strong }
            (s + weak) to s.size
        }
    }

    internal companion object {
        /** Rows each lane hands to fusion; RetrievalEval cuts its lanes the same way. */
        const val CANDIDATES = 100

        /** Scans that may each find dead rows before [meaningLane] reloads in full instead. */
        private const val MAX_PASSES = 3
        const val SNIPPET = 160
        private const val DAY_MS = 86_400_000L

        /** Query words as the snippet compares them: tokens, lowercased, Latin accents dropped. */
        fun folded(q: String): List<String> = Store.ftsTokens(foldLatin(q)).distinct()

        /**
         * The sentence of [text] that holds the most of [words] (a word of 3+ letters also matches as a prefix, as the
         * keyword lane's last word does), cut to [SNIPPET] characters from a little before its first matched word, so a
         * match deep in an unpunctuated run still shows; the start of [text] when none holds any.
         */
        fun bestSentence(text: String, words: List<String>): String {
            var best: String? = null
            var bestScore = 0
            if (words.isNotEmpty()) {
                for (s in sentences(text)) {
                    val tokens = Store.ftsTokens(foldLatin(s))
                    val score = words.count { w -> tokens.any { matches(it, w) } }
                    if (score > bestScore) {
                        best = s
                        bestScore = score
                    }
                }
            }
            val s = best ?: return text.take(SNIPPET)
            // Positions come from the unfolded sentence: folding can change its length.
            val at = WORD.findAll(s).firstOrNull { m -> foldLatin(m.value).let { t -> words.any { matches(t, it) } } }?.range?.first ?: 0
            return s.substring(maxOf(0, at - 40)).take(SNIPPET)
        }

        private fun matches(token: String, word: String) = token == word || (word.length >= 3 && token.startsWith(word))

        private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

        private fun sentences(text: String): List<String> {
            val out = ArrayList<String>()
            var start = 0
            fun cut(end: Int) {
                val s = text.substring(start, end).trim()
                if (s.isNotEmpty()) out += s
                start = end
            }
            for (i in text.indices) {
                val c = text[i]
                if (c == '\n' || c == '।' || c == '。' ||
                    ((c == '.' || c == '!' || c == '?') && (i + 1 == text.length || text[i + 1].isWhitespace()))
                ) {
                    cut(i + 1)
                }
            }
            cut(text.length)
            return out
        }
    }
}
