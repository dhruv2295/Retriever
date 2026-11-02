package app.retriever.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * One search. [from] (inclusive) and [to] (exclusive) are epoch ms, matched against a file's capture time (taken, else
 * modified); [fileIds] keeps only those files, e.g. the starred ones. Null: no such filter. [onlyStarred] says
 * [fileIds] are the starred files, so the keyword lane can filter by them in SQL before its cut. [contentIds] narrows
 * only the words and meaning lanes (the sample), so names still cover every file [fileIds] allows.
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
    val contentIds: Set<Long>? = null,
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
    /** Optional absolute rule for evaluation; the shipped null uses relative score gaps. */
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
        /** Text around a word of the query, matched words marked with \u0002 and \u0003; null when the text holds none. */
        val snippet: String?,
        val score: Double,
        /** Found by its name or folder, so the matched words are in the name. */
        val byName: Boolean,
        val matchReason: String? = null,
        /** Exact copies of this file in other folders, folded into it ([foldCopies]); it opens itself, a long press reaches these. */
        val copies: List<Hit> = emptyList(),
    ) {
        // Swift gets no default arguments, so six-argument callers (the iOS fixtures) use this one.
        constructor(file: SourceFile, fileId: Long, chunkId: Long, locator: String, snippet: String?, score: Double) :
            this(file, fileId, chunkId, locator, snippet, score, false)
    }

    /**
     * [vectorLane] is false when results are keyword-only (other model's index, closed or failed embedder).
     * hits[0 until strongCount] stay visible; the rest form the collapsed tail, without an absolute confidence claim.
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
     * [backing] grows under [loadLock]; searches scan [view], whose blocks and size were fixed when it was published, so
     * later appends (beyond its size, or into blocks it does not hold) never race with a scan.
     */
    private class Loaded(val backing: VectorTable, val deletedAtLoad: Long, val backfillsAtLoad: Long, prev: Loaded? = null) {
        val view = backing.view()

        /**
         * Rows whose chunk a lookup found deleted; [scan] skips them. Carried across appends, and grown a block at a time.
         * Set from concurrent searches without a lock: every write stores true, so a lost one only costs a later search
         * another pass.
         */
        val dead: BooleanArray = prev?.dead?.takeIf { it.size >= view.size }
            ?: BooleanArray(view.blockCount * VectorTable.BLOCK).also { prev?.dead?.copyInto(it) }

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
                    // Drop the old table first, so a rebuild never holds three copies.
                    loaded = null
                    Loaded(store.loadVectors(), deleted, backfills)
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
            val top = scanSliced(q, CANDIDATES, l.view, f.allowed, f.days, f.contentIds, histogram, dead)
            val rows = traced("fuse.lookups") { store.chunks(top.rows.map { l.view.chunkId(it) }) }
            // A picked row whose chunk is gone was deleted after the load: mark it and scan again, so it frees its slot.
            var missed = false
            for (row in top.rows) {
                if (l.view.chunkId(row) !in rows) {
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
                rows[l.view.chunkId(row)]?.let {
                    Candidate(it.fileId, it.id, it.locator, cos = top.cos[i], modality = l.view.modality(row).ordinal)
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
                val hits = store.keywordSearch(q, CANDIDATES, f.kinds, f.dates, f.onlyStarred, f.scope)
                val rows = store.chunks(hits)
                hits.mapNotNull { id -> rows[id]?.takeIf { f.admitsContent(it.fileId) }?.let { Candidate(it.fileId, it.id, it.locator) } }
            }
        }
        return listOf(Lane(words, Source.WORDS)) + nameLanes(q, f)
    }

    /**
     * Exact and prefix matches are evidence like a keyword's; substring and typo matches follow them in the same lane,
     * in NameIndex's order, marked [Candidate.loose]. A lane of their own would tie each with a prefix match of the same
     * rank and cut prefix matches for them. A file whose own name holds every query word gets a lane too, which fusion
     * puts first.
     */
    private fun nameLanes(q: String, f: Filter): List<Lane> = traced("kw.names") {
        val index = nameIndex()
        if (index == null) {
            listOf(Lane(store.fileKeywordSearch(q, CANDIDATES, f.kinds, f.dates, f.onlyStarred, f::admits).map { Candidate(it) }, Source.NAME))
        } else {
            val dates = f.dates
            val hits = index.search(q, f.kinds, CANDIDATES) { e -> f.admits(e.id) && (dates == null || e.at in dates) }
            val (exact, rest) = hits.partition { it.exactName }
            listOf(
                Lane(exact.map { Candidate(it.id) }, Source.NAME_EXACT),
                Lane(rest.map { Candidate(it.id, loose = it.match > MatchClass.PREFIX) }, Source.NAME),
            )
        }
    }

    /** Rows [scan] picked, best first, with their cosines. */
    internal class Scan(val rows: IntArray, val cos: FloatArray)

    /**
     * The [n] best rows by cosine, best first (an equal cosine goes to the earlier row), among rows whose kind [allowed]
     * marks, whose day is in [days] and whose file is in [fileIds] (sorted; null: no such filter); a min-heap over
     * primitive arrays, so no boxing or full sort. [histogram], when given, counts every admitted row's cosine by
     * modality for [RelevanceRule]. Rows [dead] marks are skipped before either. Only blocks [fromBlock] until [toBlock]
     * are read.
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
        fromBlock: Int = 0,
        toBlock: Int = table.blockCount,
    ): Scan {
        val size = table.size
        val firstDay = days?.first ?: 0
        val lastDay = days?.last ?: 0
        val k = minOf(n, minOf(size, toBlock * VectorTable.BLOCK) - fromBlock * VectorTable.BLOCK)
        if (k <= 0) return Scan(IntArray(0), FloatArray(0))
        val heapScore = FloatArray(k)
        val heapRow = IntArray(k)
        var filled = 0
        val kernel = dotRows
        val scores = FloatArray(if (kernel != null) VectorTable.BLOCK else 0)
        val unfiltered = allowed == null && days == null && fileIds == null && histogram == null && dead == null
        for (b in fromBlock until toBlock) {
            val block = table.block(b)
            val base = b * VectorTable.BLOCK
            val count = minOf(VectorTable.BLOCK, size - base)
            val bytes = block.bytes
            val scales = block.scales
            // A native kernel scores the whole block at once; that beats Kotlin/Native's per-row loop even for rows a
            // filter then skips.
            kernel?.score(bytes, scales, count, q.bytes, q.scale, scores)
            if (unfiltered) {
                // The usual search gets a loop with no per-row checks: they cost 2-5% of the scan on the JVM bench.
                if (kernel != null) {
                    for (i in 0 until count) filled = offer(heapScore, heapRow, filled, base + i, scores[i])
                } else {
                    for (i in 0 until count) filled = offer(heapScore, heapRow, filled, base + i, dotInt8(bytes, i * DIMS, q.bytes, q.scale, scales[i]))
                }
                continue
            }
            val kinds = block.kinds
            val rowDays = block.days
            val rowFiles = block.fileIds
            val modalities = block.modalities
            for (i in 0 until count) {
                if (dead != null && dead[base + i]) continue
                if (allowed != null && !allowed[kinds[i].toInt()]) continue
                if (days != null && (rowDays[i] < firstDay || rowDays[i] > lastDay)) continue
                if (fileIds != null && !sortedHas(fileIds, rowFiles[i])) continue
                val s = if (kernel != null) scores[i] else dotInt8(bytes, i * DIMS, q.bytes, q.scale, scales[i])
                if (histogram != null) histogram[modalities[i] * RelevanceRule.BINS + RelevanceRule.bin(s)]++
                filled = offer(heapScore, heapRow, filled, base + i, s)
            }
        }
        return best(heapScore, heapRow, filled, k)
    }

    /**
     * [scan] across cores from two slices' worth of rows: min([threads], size / [SLICE_ROWS]) slices of whole
     * blocks, each with its own heap and histogram, run on the caller's dispatcher and merged by cosine, then row, so the
     * result is [scan]'s. A smaller table is scanned as before.
     */
    internal suspend fun scanSliced(
        q: Vector,
        n: Int,
        table: VectorTable,
        allowed: BooleanArray? = null,
        days: IntRange? = null,
        fileIds: LongArray? = null,
        histogram: IntArray? = null,
        dead: BooleanArray? = null,
        threads: Int = cpuCores,
    ): Scan {
        val slices = minOf(threads, table.size / SLICE_ROWS)
        if (slices < 2) return traced("vec.scan") { scan(q, n, table, allowed, days, fileIds, histogram, dead) }
        val blocks = table.blockCount
        val parts = coroutineScope {
            List(slices) { s ->
                async {
                    val counts = histogram?.let { IntArray(it.size) }
                    val top = traced("vec.scan") { scan(q, n, table, allowed, days, fileIds, counts, dead, blocks * s / slices, blocks * (s + 1) / slices) }
                    top to counts
                }
            }.awaitAll()
        }
        if (histogram != null) for ((_, counts) in parts) counts?.forEachIndexed { i, c -> histogram[i] += c }
        val rows = IntArray(parts.sumOf { it.first.rows.size })
        val cos = FloatArray(rows.size)
        var at = 0
        for ((top, _) in parts) {
            top.rows.copyInto(rows, at)
            top.cos.copyInto(cos, at)
            at += top.rows.size
        }
        return best(cos, rows, rows.size, n)
    }

    /** The first [n] of the [count] rows in [rows], by cosine in [cos] and then by row. */
    private fun best(cos: FloatArray, rows: IntArray, count: Int, n: Int): Scan {
        val order = (0 until count).sortedWith(compareByDescending<Int> { cos[it] }.thenBy { rows[it] })
        val m = minOf(n, count)
        return Scan(IntArray(m) { rows[order[it]] }, FloatArray(m) { cos[order[it]] })
    }

    /**
     * Offers [row] scored [s] to the min-heap of the best [heapScore].size rows, [filled] of them used; returns the new
     * fill. Rows rank by score, then the earlier row first, so equal scores keep the same rows in any scan order.
     */
    @Suppress("NOTHING_TO_INLINE") // Inlined into both scan loops, so the heap code is written once and costs no call.
    private inline fun offer(heapScore: FloatArray, heapRow: IntArray, filled: Int, row: Int, s: Float): Int {
        val k = heapScore.size
        if (filled < k) {
            var i = filled
            while (i > 0) {
                val parent = (i - 1) / 2
                if (!below(s, row, heapScore[parent], heapRow[parent])) break
                heapScore[i] = heapScore[parent]
                heapRow[i] = heapRow[parent]
                i = parent
            }
            heapScore[i] = s
            heapRow[i] = row
            return filled + 1
        }
        if (below(heapScore[0], heapRow[0], s, row)) {
            var i = 0
            while (true) {
                val l = 2 * i + 1
                if (l >= k) break
                val c = if (l + 1 < k && below(heapScore[l + 1], heapRow[l + 1], heapScore[l], heapRow[l])) l + 1 else l
                if (!below(heapScore[c], heapRow[c], s, row)) break
                heapScore[i] = heapScore[c]
                heapRow[i] = heapRow[c]
                i = c
            }
            heapScore[i] = s
            heapRow[i] = row
        }
        return filled
    }

    /** Whether row [r] scored [s] ranks below row [r2] scored [s2]. */
    @Suppress("NOTHING_TO_INLINE") // Checked per row by [offer], so it costs no call either.
    private inline fun below(s: Float, r: Int, s2: Float, r2: Int) = s < s2 || (s == s2 && r > r2)

    /** [scan] as (chunk id, cosine) pairs, for benches. */
    fun scanTop(q: Vector, n: Int, table: VectorTable, allowed: BooleanArray? = null): List<Pair<Long, Float>> =
        scan(q, n, table, allowed).let { s -> List(s.rows.size) { table.chunkId(s.rows[it]) to s.cos[it] } }

    private enum class Source { MEANING, WORDS, NAME_EXACT, NAME }

    /** Optional per-modality absolute lines; null splits the lane at its relative score gaps. */
    private class Lane(val hits: List<Candidate>, val source: Source, val minStrong: FloatArray? = null)

    /** [cos] is NaN outside the vector lane. [loose]: a substring or typo name match, weak evidence. */
    private class Candidate(
        val fileId: Long,
        val chunkId: Long = 0L,
        val locator: String = "",
        val cos: Float = Float.NaN,
        val modality: Int = 0,
        val loose: Boolean = false,
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

        /** What the words and meaning lanes may return: [ids] narrowed to the request's content ids. */
        val contentIds = req.contentIds?.filter { admits(it) }?.toLongArray()?.apply { sort() } ?: ids
        val onlyStarred = req.onlyStarred

        /** The content scope for the keyword lane's SQL, so it applies before the cap; null when unscoped or too big to bind. */
        val scope = if (req.contentIds == null) null else contentIds?.takeIf { it.size <= MAX_BOUND_IDS }?.asList()

        fun admits(fileId: Long) = ids == null || sortedHas(ids, fileId)

        fun admitsContent(fileId: Long) = contentIds == null || sortedHas(contentIds, fileId)

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
            val relativeStrong = if (lane.source == Source.MEANING && lane.minStrong == null) {
                lane.hits.sortedByDescending { it.cos }.distinctBy { it.fileId }.take(LIKELY_MEANING_FILES).map { it.fileId }.toSet()
            } else {
                emptySet()
            }
            val seen = LinkedHashSet<Long>()
            for (c in lane.hits) {
                when (lane.source) {
                    Source.MEANING -> if (c.fileId in relativeStrong || (lane.minStrong != null && c.cos >= lane.minStrong[c.modality])) strong += c.fileId

                    Source.WORDS -> strong += c.fileId

                    Source.NAME_EXACT -> {
                        strong += c.fileId
                        byName += c.fileId
                        exact += c.fileId
                    }

                    Source.NAME -> {
                        if (!c.loose) strong += c.fileId
                        byName += c.fileId
                    }
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
            // Only extractor-proven content may become a snippet; keyword and embedding text may be metadata.
            val content = store.contentTexts(picked.map { best.getValue(it.first.first).second.chunkId })
            val pageText = store.firstTextAt(picked.map { (r, _) -> r.first to best.getValue(r.first).second.locator })
            val keywordCandidates = lanes.filter { it.source == Source.WORDS }.flatMap { it.hits }
            val labels = store.chunkLabels(keywordCandidates.map { it.chunkId })
            val keywordContent = store.contentTexts(keywordCandidates.map { it.chunkId })
            val labelMatches = keywordCandidates.groupBy { it.fileId }.mapValues { (_, candidates) ->
                if (candidates.any { wordMatchRanges(keywordContent[it.chunkId].orEmpty(), words).isNotEmpty() }) {
                    emptyList()
                } else {
                    candidates.flatMap { labels[it.chunkId]?.split("\n").orEmpty() }
                        .filter { wordMatchRanges(it, words).isNotEmpty() }.distinct()
                }
            }
            val hits = picked.map { (r, file) ->
                val c = best.getValue(r.first).second
                val raw = (content[c.chunkId] ?: pageText[r.first to c.locator])?.let { contentSnippet(it, words) }
                // A run of numbers or symbols says nothing: no snippet, and the reason says what matched.
                val unreadable = raw != null && numericSnippet(raw.filterNot { it == '\u0002' || it == '\u0003' })
                // Only text holding a word of the query: a passage found by meaning alone, or a screenshot's status bar, is
                // noise under a result.
                val snippet = raw?.takeUnless { unreadable || '\u0002' !in it }
                val matched = labelMatches[r.first].orEmpty()
                val reason = when {
                    r.first in byName -> null
                    matched.isNotEmpty() && snippet == null -> Words.matches(matched)
                    unreadable && '\u0002' in raw.orEmpty() -> Words.TEXT_MATCHES
                    else -> null
                }
                Hit(file, r.first, c.chunkId, c.locator, snippet, r.second, r.first in byName, reason)
            }
            val (s, weak) = hits.partition { it.fileId in strong }
            // A group's first copy is its best-ranked, so the strong ones stay first.
            val folded = foldCopies(s + weak)
            folded to folded.count { it.fileId in strong }
        }
    }

    internal companion object {
        /** Rows each lane hands to fusion; RetrievalEval cuts its lanes the same way. */
        const val CANDIDATES = 100

        /** Under SQLite's 999-variable limit on older Android, with room for the query's other arguments. */
        private const val MAX_BOUND_IDS = 500

        /** Scans that may each find dead rows before [meaningLane] reloads in full instead. */
        private const val MAX_PASSES = 3

        /** The fewest rows a slice of [scanSliced] gets, so a scan splits from twice this (eval/REPORT.md, "Scan in blocks"). */
        const val SLICE_ROWS = 65_536
        const val SNIPPET = 160
        private const val DAY_MS = 86_400_000L

        /**
         * One result per file kept in several folders: hits of the same name, size and modified second fold into the
         * first, which lists the rest in [Hit.copies]. A file of unknown size (0) never folds.
         * ponytail: no content hash is stored, so two different files agreeing on all three fold too, and a fold leaves
         * fewer than the limit shown; Find duplicate files hashes, add a stored hash if that ever misleads.
         */
        fun foldCopies(hits: List<Hit>): List<Hit> {
            val groups = LinkedHashMap<Any, MutableList<Hit>>()
            for (h in hits) {
                val f = h.file
                val key: Any = if (f.size > 0) Triple(f.name, f.size, f.modifiedAt.floorDiv(1000)) else h.fileId
                groups.getOrPut(key) { ArrayList() } += h
            }
            return groups.values.map { g -> if (g.size == 1) g[0] else g[0].copy(copies = g.drop(1)) }
        }

        internal fun contentSnippet(text: String, words: List<String>): String {
            val snippet = bestSentence(text, words)
            val ranges = wordMatchRanges(snippet, words)
            return buildString {
                var at = 0
                for (range in ranges) {
                    append(snippet.substring(at, range.first))
                    append('\u0002')
                    append(snippet.substring(range.first, range.last + 1))
                    append('\u0003')
                    at = range.last + 1
                }
                append(snippet.substring(at))
            }
        }

        /** Query words as the snippet compares them: tokens, lowercased, Latin accents dropped ([foldWord]). */
        fun folded(q: String): List<String> = Store.ftsTokens(q).map(::foldWord).distinct()

        /**
         * The sentence of [text] that holds the most of [words] (a word of 3+ letters also matches as a prefix, as the
         * keyword lane's last word does), cut to [SNIPPET] characters from a little before its first matched word, so a
         * match deep in an unpunctuated run still shows; the start of [text] when none holds any.
         */
        fun bestSentence(text: String, words: List<String>): String {
            val candidates = sentences(text).flatMap { sentence ->
                wordMatchRanges(sentence, words).map { range ->
                    // A word or two before the match: a result shows one line, and the match must be on it.
                    var start = maxOf(0, range.first - 16)
                    // A cut inside a word must not create a new word boundary for the highlighter.
                    while (start in 1 until range.first && wordCharacter(sentence[start - 1]) && wordCharacter(sentence[start])) start++
                    sentence.substring(start).trimStart().take(SNIPPET)
                }
            }.distinct()
            val readable = candidates.filterNot(::numericSnippet)
            return (readable.ifEmpty { candidates }).maxByOrNull { window ->
                val tokens = Store.ftsTokens(window).map(::foldWord)
                words.count { w -> tokens.any { matches(it, w) } }
            } ?: text.take(SNIPPET)
        }

        private fun wordCharacter(char: Char): Boolean = char.isLetterOrDigit() ||
            char.category == CharCategory.NON_SPACING_MARK || char.category == CharCategory.COMBINING_SPACING_MARK

        private fun numericSnippet(text: String): Boolean {
            val visible = text.filterNot(Char::isWhitespace)
            return visible.isNotEmpty() &&
                visible.count { it.isDigit() || !wordCharacter(it) } >
                visible.length / 2
        }

        private fun matches(token: String, word: String) = token == word || (word.length >= 3 && token.startsWith(word)) || (hasCjk(word) && word in token)

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
