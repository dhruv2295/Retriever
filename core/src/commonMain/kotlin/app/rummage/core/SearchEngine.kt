package app.rummage.core

import kotlin.jvm.Synchronized

class SearchEngine(private val store: Store, private val embedder: Embedder) {
    enum class Mode { VECTOR, KEYWORD, MERGED }

    data class Hit(
        val file: SourceFile,
        val fileId: Long,
        val chunkId: Long,
        val locator: String,
        val snippet: String?,
        val score: Double,
    )

    @Volatile
    private var table: VectorTable? = null

    @Synchronized
    fun reload() {
        table = store.loadVectors()
    }

    private fun loaded(): VectorTable = table ?: loadOnce()

    // Searches that start while the table loads wait for that load instead of starting their own.
    @Synchronized
    private fun loadOnce(): VectorTable = table ?: store.loadVectors().also { table = it }

    suspend fun search(
        query: String,
        mode: Mode = Mode.MERGED,
        limit: Int = 20,
        kinds: Set<Kind>? = null,
    ): List<Hit> {
        val vectorLane = if (mode == Mode.KEYWORD || query.isBlank()) {
            emptyList()
        } else {
            checkSignature()
            scanTop(embedder.embed(listOf(Part.Text(Prefix.query(query)))), 100)
        }
        val keywordLane = if (mode == Mode.VECTOR) emptyList() else store.keywordSearch(query, 100)
        val ranked: List<Pair<Long, Double>> = when (mode) {
            Mode.VECTOR -> vectorLane.map { it.first to it.second.toDouble() }
            Mode.KEYWORD -> keywordLane.mapIndexed { i, id -> id to 1.0 / (i + 1) }
            Mode.MERGED -> rrfMerge(listOf(vectorLane.map { it.first }, keywordLane))
        }
        return toHits(ranked, limit, kinds)
    }

    suspend fun searchImage(jpeg: ByteArray, limit: Int = 20): List<Hit> {
        checkSignature()
        val q = embedder.embed(listOf(Part.Image(jpeg)))
        return toHits(scanTop(q, 100).map { it.first to it.second.toDouble() }, limit, null)
    }

    fun scanTop(q: Vector, n: Int, table: VectorTable = loaded()): List<Pair<Long, Float>> {
        val scores = FloatArray(table.size) { dotInt8(table.bytes, it * DIMS, q.bytes, q.scale, table.scales[it]) }
        // ponytail: full sort, O(n log n); fine at 50k rows, swap for a heap if the index grows 10x
        return scores.indices.sortedByDescending { scores[it] }.take(n).map { table.chunkIds[it] to scores[it] }
    }

    private fun checkSignature() {
        val index = store.signature() ?: return
        check(index == embedder.signature) {
            "index was built with ${index.value} but search uses ${embedder.signature.value}; reindex needed"
        }
    }

    private fun toHits(ranked: List<Pair<Long, Double>>, limit: Int, kinds: Set<Kind>?): List<Hit> {
        val files = HashMap<Long, SourceFile?>()
        val seen = HashSet<Long>()
        val hits = ArrayList<Hit>()
        for ((chunkId, score) in ranked) {
            if (hits.size >= limit) break
            val chunk = store.chunk(chunkId) ?: continue
            if (chunk.fileId in seen) continue
            val file = files.getOrPut(chunk.fileId) { store.file(chunk.fileId) } ?: continue
            if (kinds != null && file.kind !in kinds) continue
            seen += chunk.fileId
            hits += Hit(file, chunk.fileId, chunkId, chunk.locator, chunk.text?.take(160), score)
        }
        return hits
    }
}
