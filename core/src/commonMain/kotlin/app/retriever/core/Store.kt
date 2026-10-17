package app.retriever.core

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.db.SqlDriver
import app.retriever.core.db.Files
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json

enum class FileState { PENDING, INDEXED, SKIPPED }

class Store(driver: SqlDriver, internal val log: (String, Throwable?) -> Unit = { _, _ -> }) {
    private val q = IndexDb(driver, Files.Adapter(stateAdapter = EnumColumnAdapter())).indexQueries

    /** [partial]: the extractor stopped at a cap, so only the first part of the file is searchable. */
    data class StoredFile(
        val id: Long,
        val file: SourceFile,
        val attempts: Int,
        val state: FileState = FileState.PENDING,
        val partial: Boolean = false,
    )
    enum class Sort { NEWEST, LARGEST, NAME }

    /** A null [vector] is a text tail chunk: keyword-searchable at once, embedded later by the deepen pass. */
    data class NewChunk(val ordinal: Int, val locator: String, val text: String?, val vector: Vector?)
    data class ChunkRow(val id: Long, val fileId: Long, val ordinal: Int, val locator: String, val text: String?)
    data class TailChunk(val id: Long, val fileName: String, val text: String, val attempts: Int = 0)
    data class KeywordHit(val chunkId: Long, val snippet: String?)
    /** [tailFiles]: files with a chunk still waiting for the deepen pass. */
    data class Counts(val byState: Map<FileState, Long>, val byKind: Map<Kind, Long>, val tailChunks: Long, val tailFiles: Long = 0)

    internal fun <R> inTransaction(body: () -> R): R = q.transactionWithResult { body() }

    fun upsertFiles(files: List<SourceFile>) {
        q.transaction {
            for (f in files) {
                q.upsertFile(f.uri, f.name, f.folder, f.mime, f.kind.name, f.size, f.modifiedAt)
                if (q.changes().executeAsOne() == 0L) continue
                val id = q.fileIdByUri(f.uri).executeAsOne()
                q.deleteFileFts(id)
                q.insertFileFts(id, f.name, f.folder)
            }
        }
    }

    fun removeFiles(uris: List<String>) {
        q.transaction {
            for (uri in uris) {
                val id = q.fileIdByUri(uri).executeAsOneOrNull() ?: continue
                q.chunkIdsForFile(id).executeAsList().forEach { q.deleteFts(it) }
                q.deleteChunksForFile(id)
                q.deleteFileFts(id)
                q.deleteFileById(id)
            }
        }
    }

    fun pending(limit: Int): List<StoredFile> =
        q.pendingFiles(limit.toLong(), ::storedFile).executeAsList()

    fun skipped(limit: Int): List<StoredFile> =
        q.skippedFiles(limit.toLong(), ::storedFile).executeAsList()

    fun requeue(kinds: Set<Kind>) {
        q.requeue(kinds.map { it.name })
    }

    /** Gives every skipped file three fresh attempts, e.g. after a fix for whatever made them fail. */
    fun retrySkipped() {
        q.retrySkipped()
    }

    fun files(kind: Kind? = null, folder: String? = null, sort: Sort = Sort.NEWEST, limit: Int = 200, offset: Int = 0): List<StoredFile> {
        val k = kind?.name
        val l = limit.toLong()
        val o = offset.toLong()
        return when (sort) {
            Sort.NEWEST -> q.filesNewest(k, folder, l, o, ::storedFile)
            Sort.LARGEST -> q.filesLargest(k, folder, l, o, ::storedFile)
            Sort.NAME -> q.filesName(k, folder, l, o, ::storedFile)
        }.executeAsList()
    }

    fun folders(): List<Pair<String, Long>> = q.folderCounts { folder, n -> folder to n }.executeAsList()

    /** Groups of two or more files with the same size, at least [minSize] bytes, largest size first. */
    fun sizeCollisions(minSize: Long): List<List<StoredFile>> =
        q.sizeCollisions(minSize, ::storedFile).executeAsList().groupBy { it.file.size }.values.toList()

    /** Every known file by URI, in one query. */
    fun knownFiles(): Map<String, SourceFile> = q.allFiles(::storedFile).executeAsList().associate { it.file.uri to it.file }

    /** Every known URI, without building a [SourceFile] per row. */
    fun knownUris(): Set<String> = q.allUris().executeAsList().toHashSet()

    fun fileByUri(uri: String): StoredFile? = q.fileByUri(uri, ::storedFile).executeAsOneOrNull()

    fun fileCount(): Long = q.fileCount().executeAsOne()

    fun profile(): DeviceProfile? = meta(PROFILE_KEY)?.let {
        try {
            JSON.decodeFromString<DeviceProfile>(it)
        } catch (e: Exception) {
            log("stored profile is unreadable", e)
            null
        }
    }

    fun setProfile(p: DeviceProfile) {
        setMeta(PROFILE_KEY, JSON.encodeToString(p))
    }

    fun signature(): Signature? = meta(SIGNATURE_KEY)?.let { Signature(it) }

    /** Drops every chunk and queues every file again for [sig]. Callers compare [signature] first. */
    fun resetForModel(sig: Signature) {
        q.transaction {
            q.resetForNewModel()
            q.setMeta(SIGNATURE_KEY, sig.value)
        }
    }

    /** [partial]: the extractor stopped at a cap, so [chunks] cover only the first part of the file. */
    fun replaceChunks(fileId: Long, chunks: List<NewChunk>, partial: Boolean = false) {
        val index = signature()
        chunks.firstOrNull { it.vector != null && it.vector.signature != index }?.let {
            throw IllegalStateException("vector signature ${it.vector?.signature?.value} does not match index ${index?.value}")
        }
        q.transaction {
            // The file was removed (excluded folder, deleted duplicate) while it was being indexed.
            val stored = q.fileById(fileId, ::storedFile).executeAsOneOrNull() ?: return@transaction
            q.chunkIdsForFile(fileId).executeAsList().forEach { q.deleteFts(it) }
            q.deleteChunksForFile(fileId)
            for (c in chunks) {
                q.insertChunk(fileId, c.ordinal.toLong(), c.locator, c.text, c.vector?.bytes, c.vector?.scale?.toDouble())
                val chunkId = q.lastId().executeAsOne()
                if (c.text != null) q.insertFts(chunkId, c.text)
            }
            q.setIndexed(stored.attempts.toLong(), if (partial) 1L else 0L, fileId)
        }
    }

    /** How much of each of [fileIds] is searchable; files read whole and fully embedded are left out. */
    fun readExtents(fileIds: Collection<Long>): Map<Long, ReadExtent> {
        if (fileIds.isEmpty()) return emptyMap()
        val deepening = q.deepeningFiles(fileIds).executeAsList().associateWith { ReadExtent.DEEPENING }
        return deepening + q.partialFiles(fileIds).executeAsList().associateWith { ReadExtent.FIRST_PART }
    }

    /** Text chunks still waiting for a vector, in queue priority order. */
    fun tailChunks(limit: Int): List<TailChunk> =
        q.tailChunks(limit.toLong()) { id, _, _, _, text, name, attempts -> TailChunk(id, name, text.orEmpty(), attempts.toInt()) }
            .executeAsList()

    fun setTailAttempts(id: Long, attempts: Int) {
        q.setTailAttempts(attempts.toLong(), id)
    }

    /** Replaces a tail chunk too long for the model with [parts], each a new tail chunk at the same place. */
    fun splitTailChunk(id: Long, parts: List<String>) {
        q.transaction {
            val c = q.chunkById(id).executeAsOneOrNull() ?: return@transaction
            for (text in parts) {
                q.insertChunk(c.file_id, c.ordinal, c.locator, text, null, null)
                q.insertFts(q.lastId().executeAsOne(), text)
            }
            q.deleteFts(id)
            q.deleteChunk(id)
        }
    }

    fun tailChunkCount(): Long = q.tailChunkCount().executeAsOne()

    /**
     * Gives a tail chunk its vector. The chunk moves to a new id, so a loaded [VectorTable] picks it up by
     * paging past its last id instead of rescanning every row.
     */
    fun setChunkVector(id: Long, vector: Vector) {
        check(vector.signature == signature()) { "vector signature ${vector.signature.value} does not match index" }
        q.transaction {
            val c = q.chunkById(id).executeAsOneOrNull() ?: return@transaction
            q.insertChunk(c.file_id, c.ordinal, c.locator, c.text, vector.bytes, vector.scale.toDouble())
            val newId = q.lastId().executeAsOne()
            q.deleteFts(id)
            q.deleteChunk(id)
            c.text?.let { q.insertFts(newId, it) }
        }
    }

    fun markFailed(fileId: Long, attempts: Int) {
        q.setState(if (attempts < 3) FileState.PENDING else FileState.SKIPPED, attempts.toLong(), fileId)
    }

    fun vectorCount(): Int = q.vectorCount().executeAsOne().toInt()

    /** Vector rows with an id above [afterId], appended onto [into] (or a new table). */
    fun loadVectors(afterId: Long = 0, into: VectorTable? = null, pageSize: Int = 10_000): VectorTable {
        var table = into ?: VectorTable.empty()
        var last = afterId
        while (true) {
            val rows = q.vectorsAfter(last, pageSize.toLong()).executeAsList()
            if (rows.isEmpty()) return table
            table = table.ensureCapacity(table.size + rows.size)
            for (r in rows) {
                table.append(r.id, r.file_id, r.vector, (r.scale ?: 0.0).toFloat(), Kind.valueOf(r.kind))
            }
            last = rows.last().id
        }
    }

    /** How many vectors were ever deleted; grows monotonically (kept by a trigger). */
    fun vectorsDeleted(): Long = q.vectorsDeleted().executeAsOneOrNull()?.toLongOrNull() ?: 0L

    /** The [limit] best chunks of files of [kinds] (null: any kind), with snippets cut for those only. */
    fun keywordSearch(query: String, limit: Int, kinds: Set<Kind>? = null): List<KeywordHit> {
        val match = matchExpression(query) ?: return emptyList()
        return ftsQuery(query) {
            // ponytail: ranks only the FTS_CAP most recently indexed matches (new and changed files first), so a
            // common word's best chunk in an older file can be missed; rank in SQL if keyword recall on stop words matters.
            val scored: (Long, ByteArray?) -> Pair<Long, Double> = { id, info -> id to (info?.let(::bm25) ?: 0.0) }
            val rows = if (kinds == null) q.searchFts(match, FTS_CAP, scored) else q.searchFtsOfKinds(match, kinds.map { it.name }, FTS_CAP, scored)
            val top = rows.executeAsList().sortedByDescending { it.second }.take(limit).map { it.first }
            val snippets = if (top.isEmpty()) emptyMap() else q.ftsSnippets(match, top).executeAsList().associate { it.chunkId to it.snippet }
            top.map { KeywordHit(it, snippets[it]) }
        }
    }

    /** File ids of [kinds] (null: any kind) whose name or folder matches, best first. */
    fun fileKeywordSearch(query: String, limit: Int, kinds: Set<Kind>? = null): List<Long> {
        val match = matchExpression(query) ?: return emptyList()
        return ftsQuery(query) {
            q.searchFileFts(match).executeAsList()
                .filter { kinds == null || Kind.valueOf(it.kind) in kinds }
                .sortedByDescending { it.info?.let(::bm25) ?: 0.0 }
                .take(limit)
                .map { it.fileId }
        }
    }

    private inline fun <T> ftsQuery(query: String, run: () -> List<T>): List<T> = try {
        run()
    } catch (e: Exception) {
        // Tokens can't carry FTS operators, so a malformed MATCH is rare and harmless; anything else is a real failure.
        if (e.message?.contains("MATCH") != true) log("keyword search failed for \"$query\"", e)
        emptyList()
    }

    fun chunk(id: Long): ChunkRow? = q.chunkById(id) { cid, fid, ordinal, locator, text, _, _, _ ->
        ChunkRow(cid, fid, ordinal.toInt(), locator, text)
    }.executeAsOneOrNull()

    fun chunks(ids: Collection<Long>): Map<Long, ChunkRow> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            q.chunksByIds(ids) { cid, fid, locator, text -> ChunkRow(cid, fid, 0, locator, text) }
                .executeAsList().associateBy { it.id }
        }

    /** The text of [fileId]'s first chunk at [locator] that has any, e.g. a PDF page's words beside its page image. */
    fun firstTextAt(fileId: Long, locator: String): String? = q.firstTextAt(fileId, locator).executeAsOneOrNull()

    fun file(id: Long): SourceFile? = q.fileById(id, ::storedFile).executeAsOneOrNull()?.file

    fun countsByState(): Map<FileState, Long> = q.countByState { state, count -> state to count }.executeAsList().toMap()

    fun countsByKind(): Map<Kind, Long> = q.countByKind { kind, count -> Kind.valueOf(kind) to count }.executeAsList().toMap()

    /** Re-emits whenever files or chunks change. */
    fun counts(): Flow<Counts> = combine(
        q.countByState { state, count -> state to count }.asFlow().mapToList(Dispatchers.Default),
        q.countByKind { kind, count -> Kind.valueOf(kind) to count }.asFlow().mapToList(Dispatchers.Default),
        q.tailChunkCount().asFlow().mapToOne(Dispatchers.Default),
        q.tailFileCount().asFlow().mapToOne(Dispatchers.Default),
    ) { byState, byKind, tail, tailFiles -> Counts(byState.toMap(), byKind.toMap(), tail, tailFiles) }

    fun chunkCount(): Long = q.chunkCount().executeAsOne()

    fun addRecentQuery(query: String, at: Long) {
        q.addRecentQuery(query.trim(), at)
    }

    /** The newest ten, newest first. */
    fun recentQueries(): List<String> = q.recentQueries().executeAsList()

    fun removeRecentQuery(query: String) {
        q.removeRecentQuery(query.trim())
    }

    fun clearRecentQueries() {
        q.clearRecentQueries()
    }

    fun meta(key: String): String? = q.getMeta(key).executeAsOneOrNull()

    fun setMeta(key: String, value: String) {
        q.setMeta(key, value)
    }

    companion object {
        const val DB_NAME = "retriever.db"

        private const val SIGNATURE_KEY = "model_signature"
        private const val PROFILE_KEY = "profile"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")

        /** Keyword matches ranked per query; see eval/REPORT.md, "Search speed at scale (wave 4)". */
        internal const val FTS_CAP = 5_000L

        /** Query tokens; the FTS tables fold case and accents themselves with the same unicode61 tokenizer. */
        fun ftsTokens(s: String): List<String> = s.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

        /** A one- or two-letter prefix ("a*") matches almost every row, so short last tokens match exactly. */
        fun matchExpression(query: String): String? {
            val tokens = ftsTokens(query)
            if (tokens.isEmpty()) return null
            return tokens.mapIndexed { i, t -> if (i == tokens.lastIndex && t.length >= 3) "$t*" else "\"$t\"" }
                .joinToString(" ")
        }

        /** BM25 (k1 = 1.2, b = 0.75) from FTS4 matchinfo 'pcnalx': little-endian uint32s. */
        fun bm25(info: ByteArray): Double {
            fun u(i: Int): Double = ((info[i * 4].toLong() and 0xFF) or
                ((info[i * 4 + 1].toLong() and 0xFF) shl 8) or
                ((info[i * 4 + 2].toLong() and 0xFF) shl 16) or
                ((info[i * 4 + 3].toLong() and 0xFF) shl 24)).toDouble()
            val phrases = u(0).toInt()
            val cols = u(1).toInt()
            val n = u(2)
            var score = 0.0
            for (p in 0 until phrases) for (c in 0 until cols) {
                val avg = u(3 + c).coerceAtLeast(1.0)
                val len = u(3 + cols + c)
                val x = 3 + 2 * cols + 3 * (p * cols + c)
                val tf = u(x)
                val df = u(x + 2)
                val idf = kotlin.math.ln((n - df + 0.5) / (df + 0.5) + 1)
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * len / avg))
            }
            return score
        }
    }
}

private fun storedFile(
    id: Long, uri: String, name: String, folder: String, mime: String, kind: String,
    size: Long, modifiedAt: Long, state: FileState, attempts: Long, partial: Long,
): Store.StoredFile = Store.StoredFile(
    id, SourceFile(uri, name, folder, mime, Kind.valueOf(kind), size, modifiedAt), attempts.toInt(), state, partial != 0L,
)

/** Vectors held in flat arrays for the scan; grows with doubling capacity so appends stay cheap. */
class VectorTable(
    chunkIds: LongArray,
    fileIds: LongArray,
    bytes: ByteArray,
    scales: FloatArray,
    size: Int = chunkIds.size,
    /** [Kind] ordinal per row, so a kind filter applies inside the scan instead of after the top-k cut. */
    kinds: ByteArray = ByteArray(chunkIds.size),
) {
    var chunkIds = chunkIds
        private set
    var fileIds = fileIds
        private set
    var bytes = bytes
        private set
    var scales = scales
        private set
    var kinds = kinds
        private set
    var size = size
        private set

    val lastId: Long get() = if (size == 0) 0L else chunkIds[size - 1]

    internal fun ensureCapacity(n: Int): VectorTable {
        if (n <= chunkIds.size) return this
        val cap = maxOf(n, chunkIds.size * 2, 1024)
        chunkIds = chunkIds.copyOf(cap)
        fileIds = fileIds.copyOf(cap)
        bytes = bytes.copyOf(cap * DIMS)
        scales = scales.copyOf(cap)
        kinds = kinds.copyOf(cap)
        return this
    }

    internal fun append(chunkId: Long, fileId: Long, vector: ByteArray, scale: Float, kind: Kind) {
        chunkIds[size] = chunkId
        fileIds[size] = fileId
        vector.copyInto(bytes, size * DIMS)
        scales[size] = scale
        kinds[size] = kind.ordinal.toByte()
        size++
    }

    companion object {
        fun empty() = withCapacity(0)

        fun withCapacity(n: Int) = VectorTable(LongArray(n), LongArray(n), ByteArray(n * DIMS), FloatArray(n), size = 0)
    }
}
