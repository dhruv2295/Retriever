package app.rummage.core

import app.cash.sqldelight.db.SqlDriver
import app.rummage.core.db.IndexDb
import kotlinx.serialization.json.Json

class Store(driver: SqlDriver) {
    private val q = IndexDb(driver).indexQueries

    data class StoredFile(val id: Long, val file: SourceFile, val attempts: Int, val state: String = "pending")
    enum class Sort { NEWEST, LARGEST, NAME }
    data class NewChunk(val ordinal: Int, val locator: String, val text: String?, val vector: Vector)
    data class ChunkRow(val id: Long, val fileId: Long, val ordinal: Int, val locator: String, val text: String?)

    fun upsertFiles(files: List<SourceFile>) {
        q.transaction {
            for (f in files) {
                q.upsertFile(f.uri, f.name, f.folder, f.mime, f.kind.name, f.size, f.modifiedAt)
            }
        }
    }

    fun removeFiles(uris: List<String>) {
        q.transaction {
            for (uri in uris) {
                val id = q.fileByUri(uri).executeAsOneOrNull()?.id ?: continue
                q.chunkIdsForFile(id).executeAsList().forEach { q.deleteFts(it) }
                q.deleteChunksForFile(id)
                q.deleteFileByUri(uri)
            }
        }
    }

    fun pending(limit: Int): List<StoredFile> =
        q.pendingFiles(limit.toLong(), ::storedFile).executeAsList()

    fun requeue(kinds: Set<Kind>) {
        q.requeue(kinds.map { it.name })
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

    fun knownUris(): Set<String> = q.allUris().executeAsList().toSet()

    fun fileByUri(uri: String): StoredFile? = q.fileByUri(uri, ::storedFile).executeAsOneOrNull()

    fun fileCount(): Long = q.fileCount().executeAsOne()

    fun profile(): DeviceProfile? = meta(PROFILE_KEY)?.let { runCatching { JSON.decodeFromString<DeviceProfile>(it) }.getOrNull() }

    fun setProfile(p: DeviceProfile) {
        setMeta(PROFILE_KEY, JSON.encodeToString(p))
    }

    fun signature(): Signature? = meta(SIGNATURE_KEY)?.let { Signature(it) }

    fun useSignature(sig: Signature) {
        if (signature() == sig) return
        q.transaction {
            q.resetForNewModel()
            q.setMeta(SIGNATURE_KEY, sig.value)
        }
    }

    fun replaceChunks(fileId: Long, chunks: List<NewChunk>) {
        val index = signature()
        chunks.firstOrNull { it.vector.signature != index }?.let {
            throw IllegalStateException("vector signature ${it.vector.signature.value} does not match index ${index?.value}")
        }
        q.transaction {
            // The file was removed (excluded folder, deleted duplicate) while it was being indexed.
            val attempts = q.fileById(fileId) { _, _, _, _, _, _, _, _, _, a -> a }.executeAsOneOrNull() ?: return@transaction
            q.chunkIdsForFile(fileId).executeAsList().forEach { q.deleteFts(it) }
            q.deleteChunksForFile(fileId)
            for (c in chunks) {
                q.insertChunk(fileId, c.ordinal.toLong(), c.locator, c.text, c.vector.bytes, c.vector.scale.toDouble())
                val chunkId = q.lastId().executeAsOne()
                if (c.text != null) q.insertFts(chunkId, ftsTokens(c.text).joinToString(" "))
            }
            q.setState("indexed", attempts, fileId)
        }
    }

    fun markFailed(fileId: Long, attempts: Int) {
        q.setState(if (attempts < 3) "pending" else "skipped", attempts.toLong(), fileId)
    }

    fun loadVectors(): VectorTable {
        val rows = q.allVectors().executeAsList()
        val n = rows.size
        val chunkIds = LongArray(n)
        val fileIds = LongArray(n)
        val bytes = ByteArray(n * DIMS)
        val scales = FloatArray(n)
        rows.forEachIndexed { i, r ->
            chunkIds[i] = r.id
            fileIds[i] = r.file_id
            r.vector.copyInto(bytes, i * DIMS)
            scales[i] = r.scale.toFloat()
        }
        return VectorTable(chunkIds, fileIds, bytes, scales)
    }

    fun keywordSearch(query: String, limit: Int): List<Long> = try {
        val tokens = ftsTokens(query)
        if (tokens.isEmpty()) {
            emptyList()
        } else {
            // A one- or two-letter prefix ("a*") matches almost every chunk, so short last tokens match exactly.
            val match = tokens.mapIndexed { i, t -> if (i == tokens.lastIndex && t.length >= 3) "$t*" else "\"$t\"" }
                .joinToString(" ")
            // ponytail: scores every match in Kotlin; cap candidates if a common word matches 100k rows
            q.searchFts(match).executeAsList()
                .sortedByDescending { it.info?.let(::bm25) ?: 0.0 }
                .take(limit)
                .map { it.chunkId }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun chunk(id: Long): ChunkRow? = q.chunkById(id) { cid, fid, ordinal, locator, text, _, _ ->
        ChunkRow(cid, fid, ordinal.toInt(), locator, text)
    }.executeAsOneOrNull()

    fun file(id: Long): SourceFile? = q.fileById(id) { _, uri, name, folder, mime, kind, size, modifiedAt, _, _ ->
        SourceFile(uri, name, folder, mime, Kind.valueOf(kind), size, modifiedAt)
    }.executeAsOneOrNull()

    fun countsByState(): Map<String, Long> = q.countByState { state, count -> state to count }.executeAsList().toMap()

    fun countsByKind(): Map<Kind, Long> = q.countByKind { kind, count -> Kind.valueOf(kind) to count }.executeAsList().toMap()

    fun chunkCount(): Long = q.chunkCount().executeAsOne()

    fun meta(key: String): String? = q.getMeta(key).executeAsOneOrNull()

    fun setMeta(key: String, value: String) {
        q.setMeta(key, value)
    }

    fun clearAll() {
        q.clearAll()
    }

    private companion object {
        const val SIGNATURE_KEY = "model_signature"
        const val PROFILE_KEY = "profile"
        val JSON = Json { ignoreUnknownKeys = true }
        val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** One rule for indexed text and queries, so FTS4's ASCII-only "simple" tokenizer sees the same tokens. */
        fun ftsTokens(s: String): List<String> = s.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

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
    size: Long, modifiedAt: Long, state: String, attempts: Long,
): Store.StoredFile = Store.StoredFile(
    id, SourceFile(uri, name, folder, mime, Kind.valueOf(kind), size, modifiedAt), attempts.toInt(), state,
)

class VectorTable(
    val chunkIds: LongArray,
    val fileIds: LongArray,
    val bytes: ByteArray,
    val scales: FloatArray,
) {
    val size: Int get() = chunkIds.size
}
