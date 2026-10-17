package app.retriever.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/** [embedder] is swapped when the model reopens, so the loaded vector table outlives any one embedder. */
class SearchEngine(
    private val store: Store,
    @Volatile var embedder: Embedder,
    private val tuning: SearchTuning = SearchTuning(),
    /** The floor for the index's model; null counts every hit strong. */
    private val floorFor: (Signature) -> RelevanceFloor? = RelevanceFloor::measuredFor,
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
    )

    /**
     * [vectorLane] is false when results are keyword-only (other model's index, closed or failed embedder).
     * hits[0 until strongCount] match well; the rest are weak, listed after them.
     */
    data class SearchResult(val hits: List<Hit>, val vectorLane: Boolean, val strongCount: Int = hits.size)

    /**
     * [backing] grows in place under [loadLock]; searches scan [view], whose arrays and size were fixed when it
     * was published, so later appends (beyond its size) never race with a scan.
     */
    private class Loaded(val backing: VectorTable, val deletedAtLoad: Long) {
        val view = VectorTable(backing.chunkIds, backing.fileIds, backing.bytes, backing.scales, backing.size, backing.kinds)
    }

    private val loadLock = Mutex()

    @Volatile
    private var loaded: Loaded? = null

    /** Full loads so far; tests check that concurrent first searches share one. */
    internal var fullLoads = 0
        private set

    /**
     * Loads vectors added since the last call. Rebuilds from scratch once more than 10% of the loaded rows
     * were deleted, so dead rows stop taking top-k slots.
     */
    suspend fun refresh() {
        loadLock.withLock {
            val current = loaded
            val deleted = store.vectorsDeleted()
            loaded = if (current == null || (deleted - current.deletedAtLoad) * 10 > current.backing.size) {
                fullLoads++
                // Drop the old table first and size the new one exactly, so a rebuild never holds three copies.
                loaded = null
                Loaded(store.loadVectors(into = VectorTable.withCapacity(store.vectorCount())), deleted)
            } else {
                Loaded(store.loadVectors(afterId = current.backing.lastId, into = current.backing), current.deletedAtLoad)
            }
        }
    }

    suspend fun search(
        query: String,
        mode: Mode = Mode.MERGED,
        limit: Int = 20,
        kinds: Set<Kind>? = null,
    ): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) return SearchResult(emptyList(), false)
        val vectorLane = if (mode == Mode.KEYWORD) null else vectorLane(listOf(Part.Text(Prefix.query(q))), kinds)
        val lanes = mutableListOf<List<Candidate>>()
        val weights = mutableListOf<Double>()
        if (vectorLane != null) {
            lanes += vectorLane
            weights += 1.0
        }
        if (mode != Mode.VECTOR) {
            val keyword = store.keywordSearch(q, CANDIDATES, kinds)
            val rows = store.chunks(keyword.map { it.chunkId })
            lanes += keyword.mapNotNull { k ->
                rows[k.chunkId]?.let { Candidate(it.fileId, it.id, it.locator, k.snippet) }
            }
            lanes += store.fileKeywordSearch(q, CANDIDATES, kinds).map { Candidate(it, 0L, "", null) }
            weights += listOf(tuning.keywordWeight, tuning.keywordWeight)
        }
        val (hits, strong) = toHits(lanes, weights, limit, kinds, if (vectorLane == null) null else floorFor(embedder.signature))
        return SearchResult(hits, vectorLane != null, strong)
    }

    /**
     * Search by example: the photo is the query, passed raw (no task prefix), vector lane only. Every hit counts as
     * strong: the floor was measured for text queries and there is no image-to-image data yet.
     */
    suspend fun searchImage(jpeg: ByteArray, limit: Int = 20, kinds: Set<Kind>? = null): SearchResult {
        val lane = vectorLane(listOf(Part.Image(jpeg)), kinds) ?: return SearchResult(emptyList(), false)
        return SearchResult(toHits(listOf(lane), listOf(1.0), limit, kinds, null).first, true)
    }

    /**
     * Null when the vector lane can't run: no index yet, another model's index, or the embed failed. An
     * [EmbedderClosedException] propagates, so the caller can retire the broken model.
     */
    private suspend fun vectorLane(parts: List<Part>, kinds: Set<Kind>?): List<Candidate>? {
        val embedder = embedder
        if (store.signature() != embedder.signature) return null
        val q = try {
            embedder.embed(parts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EmbedderClosedException) {
            throw e
        } catch (e: Exception) {
            store.log("query embed failed; keyword results only", e)
            return null
        }
        refresh()
        val l = loaded ?: return emptyList()
        val allowed = kinds?.let { k -> BooleanArray(Kind.entries.size) { Kind.entries[it] in k } }
        val top = scanTop(q, CANDIDATES, l.view, allowed)
        val rows = store.chunks(top.map { it.first })
        return top.mapNotNull { (id, cos) -> rows[id]?.let { Candidate(it.fileId, it.id, it.locator, it.text?.take(160), cos) } }
    }

    /**
     * The [n] best rows by cosine, best first, among rows whose kind [allowed] marks (null: all); a min-heap
     * over primitive arrays, so no boxing or full sort.
     */
    fun scanTop(q: Vector, n: Int, table: VectorTable, allowed: BooleanArray? = null): List<Pair<Long, Float>> {
        val size = table.size
        val bytes = table.bytes
        val scales = table.scales
        val ids = table.chunkIds
        val kinds = table.kinds
        val k = minOf(n, size)
        if (k <= 0) return emptyList()
        val heapScore = FloatArray(k)
        val heapRow = IntArray(k)
        var filled = 0
        for (row in 0 until size) {
            if (allowed != null && !allowed[kinds[row].toInt()]) continue
            val s = dotInt8(bytes, row * DIMS, q.bytes, q.scale, scales[row])
            if (filled < k) {
                var i = filled++
                while (i > 0) {
                    val parent = (i - 1) / 2
                    if (heapScore[parent] <= s) break
                    heapScore[i] = heapScore[parent]
                    heapRow[i] = heapRow[parent]
                    i = parent
                }
                heapScore[i] = s
                heapRow[i] = row
            } else if (s > heapScore[0]) {
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
        }
        return (0 until filled).sortedByDescending { heapScore[it] }.map { ids[heapRow[it]] to heapScore[it] }
    }

    /** [cos] is the vector lane's cosine for this chunk; NaN for a keyword lane's candidate. */
    private class Candidate(val fileId: Long, val chunkId: Long, val locator: String, val snippet: String?, val cos: Float = Float.NaN)

    /**
     * Fuses the lanes per file with reciprocal rank fusion. A file shows its best-ranked chunk; a name-only
     * match shows no chunk and opens the file at the start. The first [limit] fused files are then split by
     * [floor] (null: all strong): strong first, then weak, each in fused order. Returns the hits and the strong count.
     */
    private fun toHits(lanes: List<List<Candidate>>, weights: List<Double>, limit: Int, kinds: Set<Kind>?, floor: RelevanceFloor?): Pair<List<Hit>, Int> {
        val best = HashMap<Long, Pair<Int, Candidate>>()
        // A file's first vector candidate is its best chunk: the lane is sorted by cosine.
        val cos = HashMap<Long, Float>()
        val byKeyword = HashSet<Long>()
        val perLane = lanes.map { lane ->
            val seen = LinkedHashSet<Long>()
            for (c in lane) {
                if (c.cos.isNaN()) byKeyword += c.fileId else if (c.fileId !in cos) cos[c.fileId] = c.cos
                if (!seen.add(c.fileId)) continue
                val rank = seen.size - 1
                val prev = best[c.fileId]
                if (prev == null || (c.chunkId != 0L && (prev.second.chunkId == 0L || rank < prev.first))) best[c.fileId] = rank to c
            }
            seen.toList()
        }
        val ranked = if (lanes.size == 1) perLane[0].mapIndexed { i, id -> id to 1.0 / (i + 1) } else rrfMerge(perLane, tuning.rrfK, weights)
        val hits = ArrayList<Hit>()
        for ((fileId, score) in ranked) {
            if (hits.size >= limit) break
            val file = store.file(fileId) ?: continue
            if (kinds != null && file.kind !in kinds) continue
            val c = best.getValue(fileId).second
            // A PDF page wins on its page image, which has no words: show that page's text instead.
            val snippet = c.snippet ?: if (file.kind == Kind.PDF && c.chunkId != 0L) store.firstTextAt(fileId, c.locator)?.take(160) else null
            hits += Hit(file, fileId, c.chunkId, c.locator, snippet, score)
        }
        if (floor == null) return hits to hits.size
        val (strong, weak) = hits.partition { it.fileId in byKeyword || floor.passes(it.file.kind, cos.getValue(it.fileId)) }
        return (strong + weak) to strong.size
    }

    internal companion object {
        /** Rows each lane hands to fusion; RetrievalEval cuts its lanes the same way. */
        const val CANDIDATES = 100
    }
}

/**
 * When a file found only by meaning (no keyword match) counts as a strong match: its best chunk's int8 cosine is at
 * least its kind's floor. Absolute, per kind: a no-match query's top file always passes a cut relative to the top, and
 * one flat floor would have a 0.004 window. Each default sits between the lowest right file found by meaning and the
 * highest no-match file of its kind (eval/REPORT.md, "Relevance floor (wave 4)"). Measured for M440, 70 vision tokens,
 * 256 int8 dims, CPU on the iOS simulator: re-measure when the model, dims or vision tokens change.
 */
data class RelevanceFloor(
    /**
     * PHOTO and VIDEO, text against an image: lowest right 0.707 (0.697 on the same photo at 1024 px), highest no-match
     * 0.691. Set under the lower right cosine, so a right photo stays strong at the cost of that one no-match photo.
     */
    val photo: Float = 0.69f,
    /** SCREENSHOT, an image with its OCR text: lowest right 0.754, highest no-match 0.703. */
    val screenshot: Float = 0.73f,
    /** PDF, DOC and the rest: lowest right 0.703, highest no-match 0.675. An English query for a Hindi letter (0.620) is left weak. */
    val text: Float = 0.69f,
) {
    fun of(kind: Kind): Float = when (kind) {
        Kind.PHOTO, Kind.VIDEO -> photo
        Kind.SCREENSHOT -> screenshot
        else -> text
    }

    fun passes(kind: Kind, cos: Float): Boolean = cos >= of(kind)

    companion object {
        /** The floor measured for [signature]'s model and vision tokens; null where none was, so no hit is called weak. */
        fun measuredFor(signature: Signature): RelevanceFloor? =
            RelevanceFloor().takeIf { signature.value.startsWith("${ModelVariant.M440.name}/") && "/vt70/" in signature.value }
    }
}
