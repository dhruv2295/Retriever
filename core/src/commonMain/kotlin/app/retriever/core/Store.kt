package app.retriever.core

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.retriever.core.db.Files
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json

enum class FileState { PENDING, INDEXED, SKIPPED }

/** [userState]: where settings and stars outlive this database; see [UserState]. */
class Store(
    private val driver: SqlDriver,
    internal val log: (String, Throwable?) -> Unit = { _, _ -> },
    private val dispatchers: CoreDispatchers = CoreDispatchers(),
    private val userState: UserStateFile? = null,
) {
    private val q = IndexDb(driver, Files.Adapter(stateAdapter = EnumColumnAdapter())).indexQueries

    /** Retries run by [retryingMatch]; a test spy. */
    internal var retries = 0

    /**
     * SQLite's own per-term document counts over chunk_fts, created on first use (not in Index.sq, so an existing database
     * needs no migration). Lazily, so building a Store opens no database: the first resolution can be on the main thread.
     */
    private val chunkTerms by lazy {
        try {
            driver.execute(null, "CREATE VIRTUAL TABLE IF NOT EXISTS chunk_terms USING fts4aux(chunk_fts)", 0)
        } catch (e: Exception) {
            log("chunk_terms not created", e)
        }
        "chunk_terms"
    }

    /** Runs once, on the first read of settings or the first save, so a save never overwrites what is still to come back. */
    private val restored = lazy { restoreUserState() }

    /** [partial]: the extractor stopped at a cap, so only the first part of the file is searchable. */
    data class StoredFile(val id: Long, val file: SourceFile, val attempts: Int, val state: FileState = FileState.PENDING, val partial: Boolean = false)
    enum class Sort { NEWEST, LARGEST, NAME }

    /** A null [vector] is a text tail chunk: keyword-searchable at once, embedded later by the deepen pass. */
    data class NewChunk(val ordinal: Int, val locator: String, val text: String?, val vector: Vector?, val contentText: String? = text)
    data class ChunkRow(val id: Long, val fileId: Long, val ordinal: Int, val locator: String, val text: String?)
    data class TailChunk(val id: Long, val fileName: String, val text: String, val attempts: Int = 0)
    data class MediaSpan(val count: Long, val videos: Long, val coverId: Long?)
    data class KeywordHit(val chunkId: Long, val snippet: String?)

    /** [tailFiles]: files with a chunk still waiting for the deepen pass. [byKind] includes the whole library. */
    data class Counts(
        val byState: Map<FileState, Long>,
        val byKind: Map<Kind, Long>,
        val tailChunks: Long,
        val tailFiles: Long = 0,
        val byKindState: Map<Pair<Kind, FileState>, Long> = emptyMap(),
        /** Pending files outside the first run's plan, left out of [byState] and [byKindState]; set by Retriever.counts. */
        val waiting: Long = 0,
        val trial: Boolean = false,
    )

    internal fun <R> inTransaction(body: () -> R): R {
        // restoreUserState writes under the lazy's lock; taking it inside this transaction could deadlock against it.
        restored.value
        return q.transactionWithResult { body() }
    }

    fun upsertFiles(files: List<SourceFile>) {
        q.transaction {
            var backfilled = false
            for (f in files) {
                q.upsertFile(f.uri, f.name, f.folder, f.mime, f.kind.name, f.size, f.modifiedAt, f.takenAt)
                val changed = q.changes().executeAsOne() != 0L
                // Fills rows synced before taken_at existed, and leaves state alone so nothing is read again.
                f.takenAt?.let {
                    q.setTakenAt(it, f.uri)
                    if (q.changes().executeAsOne() != 0L) backfilled = true
                }
                if (!changed) continue
                val id = q.fileIdByUri(f.uri).executeAsOne()
                // The old text must not outlive the change, even if the re-read fails; the file is found by name till then.
                q.chunkIdsForFile(id).executeAsList().forEach { q.deleteFts(it) }
                q.deleteChunksForFile(id)
                q.deleteFileFts(id)
                q.insertFileFts(id, f.name, f.folder)
            }
            // Loaded vectors keep their capture day; the bump tells a warm SearchEngine to load them again.
            if (backfilled) q.bumpMeta(TAKEN_AT_KEY)
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

    /** A provider-confirmed rename keeps the file id, chunks, read state and star. */
    fun renameFile(uri: String, name: String, newUri: String, modifiedAt: Long): SourceFile? = inTransaction {
        val stored = fileByUri(uri) ?: return@inTransaction null
        q.renameFile(newUri, name, modifiedAt, stored.id)
        q.renameStar(newUri, name, modifiedAt, uri)
        q.deleteFileFts(stored.id)
        q.insertFileFts(stored.id, name, stored.file.folder)
        saveUserState()
        stored.file.copy(uri = newUri, name = name, modifiedAt = modifiedAt)
    }

    fun readContentVersions(): Map<Long, Long> = q.readContentVersions { id, revision -> id to (revision ?: 0L) }.executeAsList().toMap()

    fun contentTexts(ids: Collection<Long>): Map<Long, String?> = inChunks(ids) { q.contentByIds(it) { id, text -> id to text }.executeAsList() }.toMap()

    /** [sample]: only the newest [sample] files of each kind are work; the rest stay pending, found by name. */
    fun pending(limit: Int, sample: Int? = null): List<StoredFile> = if (sample == null) {
        q.pendingFiles(limit.toLong(), ::storedFile).executeAsList()
    } else {
        Kind.entries.flatMap { q.pendingNewestOfKind(it.name, sample.toLong(), ::storedFile).executeAsList() }.take(limit)
    }

    /** The pending files among [ids]. */
    fun pendingIn(ids: Collection<Long>): List<StoredFile> = inChunks(ids) { q.pendingIn(it, ::storedFile).executeAsList() }

    /** Bytes still to read of [kind]. */
    fun pendingBytes(kind: Kind): Long = q.pendingBytesOfKind(kind.name).executeAsOne()

    fun skipped(limit: Int): List<StoredFile> = q.skippedFiles(limit.toLong(), ::storedFile).executeAsList()

    fun requeue(kinds: Set<Kind>) {
        q.requeue(kinds.map { it.name })
    }

    /** Drops what was read inside files of [kinds] and keeps the files: what extraction gives a kind that is off. */
    fun deleteChunksOfKinds(kinds: Set<Kind>) {
        val k = kinds.map { it.name }
        q.transaction {
            q.deleteFtsOfKinds(k)
            q.deleteChunksOfKinds(k)
            q.clearPartialOfKinds(k)
        }
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
    fun sizeCollisions(minSize: Long): List<List<StoredFile>> = q.sizeCollisions(minSize, ::storedFile).executeAsList().groupBy { it.file.size }.values.toList()

    /** Every known file by URI, in one query. */
    fun knownFiles(): Map<String, SourceFile> = q.allFiles(::storedFile).executeAsList().associate { it.file.uri to it.file }

    /** Every known URI, without building a [SourceFile] per row. */
    fun knownUris(): Set<String> = q.allUris().executeAsList().toHashSet()

    fun fileByUri(uri: String): StoredFile? = q.fileByUri(uri, ::storedFile).executeAsOneOrNull()

    /** The known files among [uris], by URI, in one query per 500. */
    fun filesByUris(uris: Collection<String>): Map<String, StoredFile> =
        inChunks(uris) { q.filesByUris(it, ::storedFile).executeAsList() }.associateBy { it.file.uri }

    /** Ids of the known files among [uris]. */
    fun fileIds(uris: Collection<String>): List<Long> = inChunks(uris) { q.fileIdsByUris(it).executeAsList() }

    /** Every file as the name index keeps it. */
    fun nameEntries(): List<NameEntry> = q.nameEntries(::nameEntry).executeAsList()

    fun nameEntries(uris: Collection<String>): List<NameEntry> = inChunks(uris) { q.nameEntriesByUris(it, ::nameEntry).executeAsList() }

    /** Files of [kinds] captured in [[from], [to]), newest capture first; [starredOnly] keeps the starred ones. */
    fun captured(kinds: Set<Kind>, from: Long, to: Long, starredOnly: Boolean, limit: Int): List<StoredFile> =
        q.filesCaptured(kinds.map { it.name }, from, to, if (starredOnly) 1L else 0L, limit.toLong(), ::storedFile).executeAsList()

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

    /**
     * [partial]: the extractor stopped at a cap, so [chunks] cover only the first part of the file. [stillCurrent] runs
     * inside the write's transaction, so a settings change commits wholly before or after it: false (a kind switched
     * while the file was read) writes nothing and gives the attempt back, leaving the file pending to be read again.
     */
    fun replaceChunks(fileId: Long, chunks: List<NewChunk>, partial: Boolean = false, stillCurrent: () -> Boolean = { true }) {
        val index = signature()
        chunks.firstOrNull { it.vector != null && it.vector.signature != index }?.let {
            throw IllegalStateException("vector signature ${it.vector?.signature?.value} does not match index ${index?.value}")
        }
        q.transaction {
            // The file was removed (excluded folder, deleted duplicate) while it was being indexed.
            val stored = q.fileById(fileId, ::storedFile).executeAsOneOrNull() ?: return@transaction
            if (!stillCurrent()) return@transaction markFailed(fileId, (stored.attempts - 1).coerceAtLeast(0))
            q.chunkIdsForFile(fileId).executeAsList().forEach { q.deleteFts(it) }
            q.deleteChunksForFile(fileId)
            for (c in chunks) {
                q.insertChunk(fileId, c.ordinal.toLong(), c.locator, c.text, c.vector?.bytes, c.vector?.scale?.toDouble(), c.contentText)
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

    /** [tailChunks] of [fileIds] only, oldest chunk first. */
    fun tailChunksIn(fileIds: Collection<Long>): List<TailChunk> = inChunks(fileIds) { ids ->
        q.tailChunksIn(ids) { id, _, _, _, text, name, attempts -> TailChunk(id, name, text.orEmpty(), attempts.toInt()) }.executeAsList()
    }

    fun setTailAttempts(id: Long, attempts: Int) {
        q.setTailAttempts(attempts.toLong(), id)
    }

    /** Replaces a tail chunk too long for the model with [parts], each a new tail chunk at the same place. */
    fun splitTailChunk(id: Long, parts: List<String>) {
        q.transaction {
            val c = q.chunkById(id).executeAsOneOrNull() ?: return@transaction
            for (text in parts) {
                q.insertChunk(c.file_id, c.ordinal, c.locator, text, null, null, text.takeIf { c.content_text == c.text })
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
            q.insertChunk(c.file_id, c.ordinal, c.locator, c.text, vector.bytes, vector.scale.toDouble(), c.content_text)
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
                val kind = Kind.valueOf(r.kind)
                table.append(
                    r.id,
                    r.file_id,
                    r.vector,
                    (r.scale ?: 0.0).toFloat(),
                    kind,
                    Modality.of(kind, r.hasText, r.ordinal),
                    r.at.floorDiv(DAY_MS).toInt(),
                )
            }
            last = rows.last().id
        }
    }

    /** How many vectors were ever deleted; grows monotonically (kept by a trigger). */
    fun vectorsDeleted(): Long = q.vectorsDeleted().executeAsOneOrNull()?.toLongOrNull() ?: 0L

    /** How many syncs backfilled a capture day; grows monotonically. */
    fun takenAtBackfills(): Long = q.getMeta(TAKEN_AT_KEY).executeAsOneOrNull()?.toLongOrNull() ?: 0L

    /**
     * The [limit] best chunks of files of [kinds] (null: any kind) captured within [dates] (ms, taken or else
     * modified; null: any time), with snippets cut for those only.
     */
    fun keywordSearch(query: String, limit: Int, kinds: Set<Kind>? = null, dates: LongRange? = null, starredOnly: Boolean = false): List<KeywordHit> =
        ftsQuery {
            retryingMatch(query) { match ->
                // ponytail: ranks only the FTS_CAP most recently indexed matches (new and changed files first), so a
                // common word's best chunk in an older file can be missed; rank in SQL if keyword recall on stop words matters.
                val scored: (Long, ByteArray?) -> Pair<Long, Double> = { id, info -> id to (info?.let(::bm25) ?: 0.0) }
                val rows = if (kinds == null && dates == null && !starredOnly) {
                    q.searchFts(match, FTS_CAP, scored)
                } else {
                    val range = dates ?: Long.MIN_VALUE..Long.MAX_VALUE
                    q.searchFtsOfKinds(match, (kinds ?: Kind.entries).map { it.name }, range.first, range.last, if (starredOnly) 1L else 0L, FTS_CAP, scored)
                }
                val top = rows.executeAsList().sortedByDescending { it.second }.take(limit).map { it.first }
                val snippets = if (top.isEmpty()) emptyMap() else q.ftsSnippets(match, top).executeAsList().associate { it.chunkId to it.snippet }
                top.map { KeywordHit(it, snippets[it]) }
            }
        }

    /**
     * File ids of [kinds] (null: any kind) captured within [dates] (as [keywordSearch]) that [keep] lets through, whose
     * name or folder matches, best first.
     */
    fun fileKeywordSearch(query: String, limit: Int, kinds: Set<Kind>? = null, dates: LongRange? = null, keep: (Long) -> Boolean = { true }): List<Long> =
        ftsQuery {
            retryingMatch(query) { match ->
                val range = dates ?: Long.MIN_VALUE..Long.MAX_VALUE
                // ponytail: ranks only the FTS_CAP newest name matches in scope, as keywordSearch does.
                q.searchFileFts(match, (kinds ?: Kind.entries).map { it.name }, range.first, range.last, FTS_CAP).executeAsList()
                    .filter { keep(it.fileId) }
                    .sortedByDescending { it.info?.let(::bm25) ?: 0.0 }
                    .take(limit)
                    .map { it.fileId }
            }
        }

    /**
     * Runs [run] on the AND match of [query]; while that finds nothing and 2+ tokens remain, drops the token found in the
     * most chunks (fts4aux's document count, so no language-specific stop list) and runs again.
     * ponytail: a prefix token counts by its exact term only.
     */
    private inline fun <T> retryingMatch(query: String, run: (String) -> List<T>): List<T> {
        val tokens = ftsTokens(query)
        if (tokens.isEmpty()) return emptyList()
        val terms = matchTerms(tokens).toMutableList()
        var out = run(terms.joinToString(" "))
        if (out.isNotEmpty() || terms.size < 2) return out
        val df = docFreq(tokens).toMutableList()
        while (out.isEmpty() && terms.size > 1) {
            val drop = df.indices.maxBy { df[it] }
            terms.removeAt(drop)
            df.removeAt(drop)
            retries++
            out = run(terms.joinToString(" "))
        }
        return out
    }

    private fun docFreq(tokens: List<String>): List<Long> {
        val folded = tokens.map(::foldLatin)
        val sql = "SELECT term, documents FROM $chunkTerms WHERE col = '*' AND term IN (${folded.joinToString { "?" }})"
        val df = driver.executeQuery(null, sql, { c ->
            QueryResult.Value(buildMap { while (c.next().value) put(c.getString(0)!!, c.getLong(1)!!) })
        }, folded.size) { folded.forEachIndexed { i, t -> bindString(i, t) } }.value
        return folded.map { df[it] ?: 0L }
    }

    private inline fun <T> ftsQuery(run: () -> List<T>): List<T> = try {
        run()
    } catch (e: Exception) {
        // Tokens can't carry FTS operators, so a malformed MATCH is rare and harmless; anything else is a real failure.
        if (e.message?.contains("MATCH") != true) log("keyword search failed", e)
        emptyList()
    }

    fun chunks(ids: Collection<Long>): Map<Long, ChunkRow> = if (ids.isEmpty()) {
        emptyMap()
    } else {
        q.chunksByIds(ids) { cid, fid, locator, text -> ChunkRow(cid, fid, 0, locator, text) }
            .executeAsList().associateBy { it.id }
    }

    /**
     * The text of each place's first chunk that has any, e.g. a PDF page's words beside its page image, for many
     * (file id, locator) places in one query; places with no text are left out.
     */
    fun firstTextAt(places: Collection<Pair<Long, String>>): Map<Pair<Long, String>, String> {
        if (places.isEmpty()) return emptyMap()
        val wanted = places.toHashSet()
        return q.firstTextAtMany(places.mapTo(HashSet()) { it.first }, places.mapTo(HashSet()) { it.second }) { fileId, locator, text, _ ->
            (fileId to locator) to text.orEmpty()
        }.executeAsList().filter { it.first in wanted }.toMap()
    }

    /** [fileId]'s text chunks in reading order, for the in-app reader. */
    fun chunkTexts(fileId: Long): List<ChunkRow> =
        q.chunkTextsOfFile(fileId) { id, ordinal, locator, text -> ChunkRow(id, fileId, ordinal.toInt(), locator, text) }.executeAsList()

    fun file(id: Long): SourceFile? = storedById(id)?.file

    fun storedById(id: Long): StoredFile? = q.fileById(id, ::storedFile).executeAsOneOrNull()

    /** The files of [ids] that still exist, by id, in one query. */
    fun files(ids: Collection<Long>): Map<Long, SourceFile> =
        if (ids.isEmpty()) emptyMap() else q.filesByIds(ids, ::storedFile).executeAsList().associate { it.id to it.file }

    internal fun indexedIn(ids: Collection<Long>): Map<Long, SourceFile> =
        inChunks(ids) { q.filesByIds(it, ::storedFile).executeAsList() }.filter { it.state == FileState.INDEXED }.associate { it.id to it.file }

    fun countsByState(): Map<FileState, Long> = q.countByState { state, count -> state to count }.executeAsList().toMap()

    /** Pending files per kind, read once on the caller's thread (unlike [counts], which emits on its own dispatcher). */
    fun pendingByKind(): Map<Kind, Long> = q.countByKindState { kind, state, count -> Triple(Kind.valueOf(kind), state, count) }.executeAsList()
        .filter { it.second == FileState.PENDING }.associate { it.first to it.third }

    /** Re-emits whenever files or chunks change; one scan of files per change. */
    fun counts(): Flow<Counts> = combine(
        q.countByKindState { kind, state, count -> (Kind.valueOf(kind) to state) to count }.asFlow().mapToList(dispatchers.default),
        q.tailChunkCount().asFlow().mapToOne(dispatchers.default),
        q.tailFileCount().asFlow().mapToOne(dispatchers.default),
    ) { rows, tail, tailFiles ->
        val byKindState = rows.toMap()
        Counts(
            byState = byKindState.entries.groupingBy { it.key.second }.fold(0L) { n, e -> n + e.value },
            byKind = byKindState.entries.groupingBy { it.key.first }.fold(0L) { n, e -> n + e.value },
            tailChunks = tail,
            tailFiles = tailFiles,
            byKindState = byKindState,
        )
    }

    /** The [limit] newest files of [kinds], newest first. */
    fun newestFiles(kinds: Set<Kind>, limit: Int): List<StoredFile> = q.filesNewestOfKinds(kinds.map { it.name }, limit.toLong(), ::storedFile).executeAsList()

    fun folderCount(): Long = q.folderCount().executeAsOne()

    /** How many files are at least [minSize] bytes, and their total size. */
    fun largeFiles(minSize: Long): Pair<Long, Long> = q.largeFiles(minSize) { n, bytes -> n to (bytes ?: 0L) }.executeAsOne()

    /** Indexed files of which only the name is searchable: nothing inside them became a chunk. */
    fun nameOnlyCount(): Long = q.nameOnlyCount().executeAsOne()

    fun setStarred(uri: String, starred: Boolean, at: Long) {
        if (starred) q.star(at, uri) else q.unstar(uri)
        saveUserState()
    }

    /** Moves stars of files that are gone to the same file under a new uri; see Index.sq. */
    internal fun relinkStars() {
        // Counts every orphaned star, moved or not: a spare save at worst.
        val orphans = q.transactionWithResult {
            q.relinkStars()
            q.changes().executeAsOne()
        }
        if (orphans > 0) saveUserState()
    }

    /** Every starred URI of a file still in the index, as a HashSet so a row's star is one lookup. */
    fun starredUris(): Set<String> = q.starredUris().executeAsList().toHashSet()

    /** Starred files that are still in the index, most recently starred first. */
    fun starredFiles(limit: Int, offset: Int = 0): List<StoredFile> = q.starredFiles(limit.toLong(), offset.toLong(), ::storedFile).executeAsList()

    fun starredCount(): Long = q.starredCount().executeAsOne()

    /** [starredFiles] without OTHER, for Home's strip. */
    fun starredVisible(limit: Int): List<StoredFile> = q.starredVisible(limit.toLong(), ::storedFile).executeAsList()

    fun starredVisibleCount(): Long = q.starredVisibleCount().executeAsOne()

    /** The earliest capture time of a photo that has one, or null. */
    fun mediaTakenSince(): Long? = q.mediaTakenSince().executeAsOne().MIN

    /** Photos and videos captured in [[from], [to]]: count, videos among them, and a cover id (a photo if any). */
    fun mediaTakenBetween(from: Long, to: Long): MediaSpan =
        q.mediaTakenBetween(from, to) { n, videos, cover -> MediaSpan(n, videos ?: 0L, cover) }.executeAsOne()

    /** Photos and videos captured in [[from], [to]], oldest first. */
    fun filesTakenBetween(from: Long, to: Long, limit: Int, offset: Int = 0): List<StoredFile> =
        q.filesTakenBetween(from, to, limit.toLong(), offset.toLong(), ::storedFile).executeAsList()

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

    fun meta(key: String): String? {
        restored.value
        return q.getMeta(key).executeAsOneOrNull()
    }

    fun setMeta(key: String, value: String) {
        q.setMeta(key, value)
        if (key == PREFS_KEY) saveUserState()
    }

    /** Into a database with no settings yet (new, or deleted and made again); never through [meta] or [setMeta]. */
    private fun restoreUserState() {
        val file = userState ?: return
        if (q.getMeta(PREFS_KEY).executeAsOneOrNull() != null) return
        val state = try {
            file.read()?.let { JSON.decodeFromString<UserState>(it) } ?: return
        } catch (e: Exception) {
            // The class only: a parse error quotes the file, and the file holds file names.
            log("user state is unreadable (${e::class.simpleName}); starting without it", null)
            return
        }
        q.transaction {
            state.prefs?.let { q.setMeta(PREFS_KEY, JSON.encodeToString(it)) }
            state.starred.forEach { q.restoreStar(it.uri, it.starredAt, it.name, it.size, it.modifiedAt) }
        }
    }

    private fun saveUserState() {
        val file = userState ?: return
        restored.value
        val prefs = q.getMeta(PREFS_KEY).executeAsOneOrNull()?.let { runCatching { JSON.decodeFromString<Prefs>(it) }.getOrNull() }
        val starred = q.allStars { uri, at, name, size, modifiedAt -> StarredRow(uri, at, name, size, modifiedAt) }.executeAsList()
        try {
            file.write(JSON.encodeToString(UserState(prefs, starred)))
        } catch (e: Exception) {
            log("user state not saved (${e::class.simpleName})", null)
        }
    }

    /** Refills the keyword tables once after schema 3's upgrade recreated them empty; true if it ran. */
    fun rebuildFtsIfPending(): Boolean {
        if (meta("fts_rebuild") == null) return false
        q.rebuildFts()
        return true
    }

    companion object {
        const val DB_NAME = "retriever.db"

        private const val SIGNATURE_KEY = "model_signature"
        private const val PROFILE_KEY = "profile"
        private const val TAKEN_AT_KEY = "taken_at_backfills"

        /** The key Prefs.kt keeps settings under. */
        private const val PREFS_KEY = "prefs"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")

        /** Keyword matches ranked per query; see eval/REPORT.md, "Search speed at scale (wave 4)". */
        internal const val FTS_CAP = 5_000L

        /** Query tokens; the FTS tables fold case and accents themselves with the same unicode61 tokenizer. */
        fun ftsTokens(s: String): List<String> = s.lowercase().split(NON_WORD).filter { it.isNotEmpty() }

        /** The last token matches as a prefix; a one- or two-letter prefix ("a*") matches almost every row, so short ones match exactly. */
        private fun matchTerms(tokens: List<String>) = tokens.mapIndexed { i, t -> if (i == tokens.lastIndex && t.length >= 3) "$t*" else "\"$t\"" }

        /** BM25 (k1 = 1.2, b = 0.75) from FTS4 matchinfo 'pcnalx': little-endian uint32s. */
        fun bm25(info: ByteArray): Double {
            fun u(i: Int): Double = (
                (info[i * 4].toLong() and 0xFF) or
                    ((info[i * 4 + 1].toLong() and 0xFF) shl 8) or
                    ((info[i * 4 + 2].toLong() and 0xFF) shl 16) or
                    ((info[i * 4 + 3].toLong() and 0xFF) shl 24)
                ).toDouble()
            val phrases = u(0).toInt()
            val cols = u(1).toInt()
            val n = u(2)
            var score = 0.0
            for (p in 0 until phrases) {
                for (c in 0 until cols) {
                    val avg = u(3 + c).coerceAtLeast(1.0)
                    val len = u(3 + cols + c)
                    val x = 3 + 2 * cols + 3 * (p * cols + c)
                    val tf = u(x)
                    val df = u(x + 2)
                    val idf = kotlin.math.ln((n - df + 0.5) / (df + 0.5) + 1)
                    score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * len / avg))
                }
            }
            return score
        }
    }
}

/** Android 11's SQLite binds at most 999 variables, so a long `IN ?` list runs in parts. */
private inline fun <K, T> inChunks(items: Collection<K>, run: (Collection<K>) -> List<T>): List<T> =
    if (items.isEmpty()) emptyList() else items.chunked(500).flatMap(run)

private fun nameEntry(id: Long, name: String, folder: String, kind: String, at: Long): NameEntry = NameEntry(id, name, folder, Kind.valueOf(kind), at)

private fun storedFile(
    id: Long,
    uri: String,
    name: String,
    folder: String,
    mime: String,
    kind: String,
    size: Long,
    modifiedAt: Long,
    state: FileState,
    attempts: Long,
    partial: Long,
    takenAt: Long?,
): Store.StoredFile = Store.StoredFile(
    id,
    SourceFile(uri, name, folder, mime, Kind.valueOf(kind), size, modifiedAt, takenAt),
    attempts.toInt(),
    state,
    partial != 0L,
)

private const val DAY_MS = 86_400_000L
