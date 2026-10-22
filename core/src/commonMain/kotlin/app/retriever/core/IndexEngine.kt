package app.retriever.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

data class FileMetric(
    val file: SourceFile,
    val pieces: Int,
    val chunks: Int,
    val extractMs: Double,
    val embedMs: Double,
    val ok: Boolean,
    val error: String?,
    val state: DeviceState,
    /** The failure itself, so the platform can log its stack trace. */
    val cause: Throwable? = null,
)

enum class StopReason { DONE, HOT, UNPLUGGED, BUDGET, BUSY, PAUSED, POWER_SAVE, LOW_BATTERY, CHARGING_UP, MANUAL }

private class ReadingStopped(val reason: StopReason) : CancellationException()

data class BatchResult(val processed: Int, val failed: Int, val stopReason: StopReason)

/** Read before every file, so a prefs change or Pause applies mid-batch. */
data class IndexSettings(val profile: DeviceProfile, val kinds: Set<Kind>, val paused: Boolean = false)

class IndexEngine(
    private val store: Store,
    private val extractor: Extractor,
    /** Null in text-only mode: every chunk is stored for keyword search, none gets a vector. */
    private val embedder: Embedder?,
    private val monitor: DeviceMonitor,
    private val settings: () -> IndexSettings,
    private val onFile: (FileMetric) -> Unit = {},
    private val busy: () -> Boolean = { false },
    /** Suspends while the UI needs the model (a search in flight), so indexing never delays a query. */
    private val awaitUi: suspend () -> Unit = {},
    /** The next files to read, e.g. the first run's plan only; empty ends the reading. */
    private val pendingPage: () -> List<Store.StoredFile> = { store.pending(PAGE, store.prefs().sampleSize) },
    /** The next text chunks to embed; empty ends the deepen pass. */
    private val tailPage: () -> List<Store.TailChunk> = { store.tailChunks(PAGE) },
    /**
     * Before extraction starts, after any wait for the UI. Never cleared between two files, so every surface keeps saying
     * the batch reads (no "Getting started…" blink per file); the batch's end clears it.
     */
    private val onCurrent: (SourceFile) -> Unit = {},
    /** Finish this batch between files so the next one can open the new delegate configuration. */
    private val configurationChanged: () -> Boolean = { false },
    private val policyStop: (() -> StopReason?)? = null,
    /** Asked per file: how many of the next files may be read while this one embeds (see readAhead, [readAheadDepth])? */
    private val readAheadDepth: (DeviceState) -> Int = { 0 },
    private val readAheadContext: CoroutineContext = Dispatchers.Default,
) {
    /** [requireCharging] is asked on every file, so a policy that depends on the app being visible or the battery applies mid-batch. */
    suspend fun runBatch(budget: Duration, requireCharging: (DeviceState) -> Boolean, pauseWhenHot: Boolean = true, deepen: Boolean = false): BatchResult {
        val start = TimeSource.Monotonic.markNow()
        var processed = 0
        var failed = 0
        // Pages, so the queue's sort runs once per PAGE files rather than once per file.
        val files = ArrayDeque<Store.StoredFile>()
        val tails = ArrayDeque<Store.TailChunk>()
        // The files read ahead, in order: always the first ones of [files].
        val ahead = ArrayDeque<Ahead>()
        val scope = CoroutineScope(currentCoroutineContext())

        // While a file embeds on the GPU or NPU the CPU is free, so the next files' decode and OCR run beside it. Never on CPU
        // inference (the two would share cores) and never while the app shows (its frames come first). The tail
        // interleaved below may call extractor.visual at the same time; Android's extractor allows that, and iOS never
        // gets here (a backgrounded iOS app infers on CPU).
        fun readAhead(state: DeviceState) {
            val e = embedder ?: return
            if (e.backend == Backend.CPU || e.visionBackend == Backend.CPU) return
            val depth = readAheadDepth(state)
            while (ahead.size < depth) {
                val next = files.getOrNull(ahead.size)?.takeIf { it.attempts < MAX_FILE_ATTEMPTS } ?: return
                val now = settings()
                val inside = readsInside(next.file, now)
                store.recordAttempt(next.id, next.attempts + 1)
                ahead += Ahead(
                    next,
                    inside,
                    scope.async(readAheadContext) { runCatching { extract(next.file, inside, now.profile) } },
                )
            }
        }
        try {
            while (true) {
                fun stop(reason: StopReason) = BatchResult(processed, failed, reason)
                if (start.elapsedNow() >= budget) return stop(StopReason.BUDGET)
                val now = settings()
                val state = monitor.state()
                (policyStop?.invoke() ?: stopReason(now, state, busy(), requireCharging(state), pauseWhenHot))?.let { return stop(it) }
                if (configurationChanged()) return stop(StopReason.BUDGET)
                if (files.isEmpty()) files += pendingPage()
                val next = files.removeFirstOrNull()
                if (next != null) {
                    val mine = ahead.firstOrNull()?.takeIf { it.stored.id == next.id }?.also { ahead.removeFirst() }
                    val metric = try {
                        index(next, now, state, mine) { readAhead(state) }
                    } catch (s: ReadingStopped) {
                        return stop(s.reason)
                    }
                    if (metric.ok) processed++ else failed++
                    onFile(metric)
                    if (deepen && embedder != null && (processed + failed) % TAIL_EVERY == 0) {
                        try {
                            tailPage().firstOrNull()?.let { embedTail(it, embedder) }
                        } catch (s: ReadingStopped) {
                            return stop(s.reason)
                        }
                    }
                    continue
                }
                if (!deepen || embedder == null) return stop(StopReason.DONE)
                if (tails.isEmpty()) tails += tailPage()
                try {
                    embedTail(tails.removeFirstOrNull() ?: return stop(StopReason.DONE), embedder)
                } catch (s: ReadingStopped) {
                    return stop(s.reason)
                }
            }
        } finally {
            // A stop never charges a file, including the ones read ahead.
            for (it in ahead) {
                it.pieces.cancel()
                store.markFailed(it.stored.id, it.stored.attempts)
            }
        }
    }

    private suspend fun checkpoint() {
        awaitUi()
        policyStop?.invoke()?.let { throw ReadingStopped(it) }
    }

    /**
     * Embeds one tail chunk. The attempt is counted first, so a chunk that fails, or kills the process
     * natively, drops out of the queue after three tries and stays keyword-only.
     */
    private suspend fun embedTail(tail: Store.TailChunk, embedder: Embedder) {
        store.setTailAttempts(tail.id, tail.attempts + 1)
        try {
            checkpoint()
            val file = store.file(tail.fileId) ?: return
            onCurrent(file)
            if (tail.locator.startsWith(LATER)) {
                val pieces = extractor.later(file, tail.locator.removePrefix(LATER), settings().profile)
                val chunks = if (pieces.isEmpty()) {
                    emptyList()
                } else {
                    draftChunks(file, pieces) { parts ->
                        checkpoint()
                        embedder.embed(parts)
                    }
                }
                store.replaceTailChunk(tail.id, chunks.map { Store.NewChunk(it.ordinal, it.locator, it.text, it.vector, it.contentText, it.labels, it.visual) })
                return
            }
            val parts = when {
                // The image alone, as the first pass embedded it: words on an image chunk are an OCR scrap for keyword search.
                tail.visual -> listOf(checkNotNull(extractor.visual(file, tail.locator)) { "image unavailable" })

                tail.ordinal == 0 -> file.namePiece().modelParts(file.name)

                else -> listOf(Part.Text(Prefix.document(file.name, tail.text.orEmpty())))
            }
            store.setChunkVector(tail.id, embedder.embed(parts))
        } catch (e: InputTooLongException) {
            // Split like add() does, so both halves get a vector instead of the second half being lost.
            val text = tail.text.orEmpty()
            val halves = if (tail.visual || tail.ordinal == 0) emptyList() else Chunker.chunks(text, maxChars = text.length / 2 + 1)
            if (halves.size > 1) store.splitTailChunk(tail.id, halves) else store.setTailAttempts(tail.id, MAX_TAIL_ATTEMPTS)
        } catch (e: CancellationException) {
            store.setTailAttempts(tail.id, tail.attempts)
            throw e
        } catch (e: EmbedderClosedException) {
            store.setTailAttempts(tail.id, tail.attempts)
            throw e
        } catch (e: Exception) {
            // Counted above; a later pass tries again.
        }
    }

    /** Video frames are only ever model input: without the model a video is found by its name, and read when the model comes. */
    private fun readsInside(file: SourceFile, settings: IndexSettings) =
        file.kind in settings.kinds && file.kind != Kind.AUDIO && (embedder != null || file.kind != Kind.VIDEO)

    /** With the model here, the deepen pass will follow, so a video or scanned PDF may leave its rest for it. */
    private suspend fun extract(file: SourceFile, inside: Boolean, profile: DeviceProfile): List<Piece> = when {
        !inside -> emptyList()
        embedder != null -> tracedAsync("extract") { extractor.extractHead(file, profile) }
        else -> tracedAsync("extract") { extractor.extract(file, profile) }
    }

    /** A file whose extraction started while the one before it was embedding; its attempt is already counted. */
    private class Ahead(val stored: Store.StoredFile, val readInside: Boolean, val pieces: Deferred<Result<List<Piece>>>)

    private suspend fun index(stored: Store.StoredFile, settings: IndexSettings, state: DeviceState, ahead: Ahead?, readAhead: () -> Unit): FileMetric {
        val file = stored.file
        var extractMs = 0.0
        var embedMs = 0.0
        // Before the attempt is counted: a search running now would otherwise wait behind this file's extraction.
        if (ahead == null) checkpoint()
        // Three attempts that never came back (OOM, native crash, LMK): skip it rather than crash again.
        if (ahead == null && stored.attempts >= MAX_FILE_ATTEMPTS) {
            store.markFailed(stored.id, stored.attempts)
            return FileMetric(file, 0, 0, 0.0, 0.0, false, "stopped the app $MAX_FILE_ATTEMPTS times", state)
        }
        onCurrent(file)
        val readInside = ahead?.readInside ?: readsInside(file, settings)
        // Count the attempt up front so a file that kills the process still counts it; the file stays pending (Waiting) meanwhile.
        // A file read ahead counted its attempt when that read began.
        if (ahead == null) store.recordAttempt(stored.id, stored.attempts + 1)
        try {
            val extracted = measureTimedValue {
                ahead?.pieces?.await()?.getOrThrow() ?: extract(file, readInside, settings.profile)
            }
            // For a file read ahead, only the wait: the pace books want this file's share of the wall clock.
            extractMs = extracted.duration.inWholeNanoseconds / 1e6
            val pieces = extracted.value
            readAhead()

            val e = embedder
            val chunks = if (e == null) {
                draftChunks<Vector>(file, pieces, embed = null)
            } else {
                draftChunks(file, pieces) { parts ->
                    checkpoint()
                    val timed = measureTimedValue { e.embed(parts) }
                    embedMs += timed.duration.inWholeNanoseconds / 1e6
                    timed.value
                }
            }
            store.replaceChunks(
                stored.id,
                chunks.map { Store.NewChunk(it.ordinal, it.locator, it.text, it.vector, it.contentText, it.labels, it.visual) },
                partial = pieces.any { it.locator == CUT_SHORT },
                // A kind switched mid-read: what was read no longer matches what the setting asks for.
                stillCurrent = { readsInside(file, this.settings()) == readInside },
            )
            return FileMetric(file, pieces.size, chunks.size, extractMs, embedMs, true, null, state)
        } catch (e: CancellationException) {
            // A stop (unplugged, time limit, Pause) is not the file's fault: give the attempt back.
            store.markFailed(stored.id, stored.attempts)
            throw e
        } catch (e: EmbedderClosedException) {
            // Not this file's fault: give the attempt back and stop the batch.
            store.markFailed(stored.id, stored.attempts)
            throw e
        } catch (e: Throwable) {
            store.markFailed(stored.id, stored.attempts + 1)
            return FileMetric(file, 0, 0, extractMs, embedMs, false, e.message ?: e::class.simpleName, state, cause = e)
        }
    }

    /** A policy that never changes mid-batch (tests, the eval). */
    internal suspend fun runBatch(budget: Duration, requireCharging: Boolean, pauseWhenHot: Boolean = true, deepen: Boolean = false) =
        runBatch(budget, { requireCharging }, pauseWhenHot, deepen)

    companion object {
        /** Why a batch must not go on now, or null to carry on: checked before every file, and by Retriever before it opens the model. */
        fun stopReason(settings: IndexSettings, state: DeviceState, busy: Boolean, requireCharging: Boolean, pauseWhenHot: Boolean = true): StopReason? = when {
            settings.paused -> StopReason.PAUSED
            busy -> StopReason.BUSY
            state.powerSave -> StopReason.POWER_SAVE
            state.hot && pauseWhenHot -> StopReason.HOT
            requireCharging && !state.pluggedIn -> StopReason.UNPLUGGED
            else -> null
        }

        /** Text-only pieces embedded per file on the first pass (a screenshot's OCR); the rest wait for the deepen pass. */
        const val HEAD_TEXT_CHUNKS = 8

        /** A document's or PDF's opening embedded on the first pass; the rest of it waits for the deepen pass. */
        const val DOC_HEAD_CHUNKS = 2

        fun headTextChunks(kind: Kind) = if (kind == Kind.DOC || kind == Kind.PDF) DOC_HEAD_CHUNKS else HEAD_TEXT_CHUNKS

        /** One tail chunk is embedded after every this many first-pass files, so a long backlog still deepens. */
        const val TAIL_EVERY = 8

        /** Files read while one embeds: two on a charger, one on battery, fewer as the phone warms; none while the app shows. */
        fun readAheadDepth(state: DeviceState, visible: Boolean): Int =
            if (visible) 0 else minOf(state.aheadRoom, if (state.pluggedIn) ThermalRest.MAX_READ_AHEAD else 1)

        /** Attempts before a file is skipped (Couldn't read). */
        const val MAX_FILE_ATTEMPTS = 3

        /** Matches the tail_attempts cutoff in Index.sq. */
        const val MAX_TAIL_ATTEMPTS = 3
        private const val PAGE = 64
    }
}

/** One chunk as [draftChunks] decides it; [vector] null leaves it keyword-only. */
internal class ChunkDraft<V : Any>(
    val ordinal: Int,
    val locator: String,
    val text: String?,
    val vector: V?,
    val contentText: String? = null,
    val labels: String? = null,
    val visual: Boolean = false,
)

/**
 * What a file stores, shared by IndexEngine and RetrievalEval so the eval measures production's rules: the first
 * [IndexEngine.headTextChunks] text pieces get vectors (the rest wait for the deepen pass), an over-long text
 * piece is split in half, and the name gets a vector only when nothing else could be embedded. [embed] returns
 * what the caller keeps: a stored [Vector], or raw floats in the eval. With no [embed] (text-only mode) every
 * piece is stored without a vector; image locators are kept for a later pixels-only read. A [later] piece is stored
 * as an empty placeholder for the deepen pass.
 */
internal suspend fun <V : Any> draftChunks(
    file: SourceFile,
    pieces: List<Piece>,
    prefixes: Boolean = true,
    embed: (suspend (List<Part>) -> V)?,
): List<ChunkDraft<V>> {
    val chunks = mutableListOf<ChunkDraft<V>>()
    var headText = 0
    val head = IndexEngine.headTextChunks(file.kind)

    suspend fun add(piece: Piece, depth: Int = 0) {
        val textOnly = piece.parts.all { it is Part.Text }
        // A mixed piece's text parts are model input (a video moment's timestamps), not words to search.
        val text = piece.keywordText ?: if (textOnly) piece.parts.joinToString("\n") { (it as Part.Text).text } else null
        if (embed == null) {
            chunks += ChunkDraft(chunks.size + 1, piece.locator, text, null, piece.contentText, piece.labels, !textOnly)
            return
        }
        if (textOnly && headText >= head) {
            chunks += ChunkDraft(chunks.size + 1, piece.locator, text, null, piece.contentText)
            return
        }
        val vector = try {
            embed(piece.modelParts(file.name, prefixes))
        } catch (e: InputTooLongException) {
            // Dense text (numbers, non-Latin scripts) can exceed the token window even under the
            // character cap. Split a text-only piece in half and embed the halves instead.
            val only = piece.parts.singleOrNull() as? Part.Text
            if (only == null || depth >= 3) throw e
            for (half in Chunker.chunks(only.text, maxChars = only.text.length / 2 + 1)) {
                add(Piece(piece.locator, listOf(Part.Text(half)), half, half.takeIf { piece.contentText == only.text }), depth + 1)
            }
            return
        }
        if (textOnly) headText++
        chunks += ChunkDraft(chunks.size + 1, piece.locator, text, vector, piece.contentText, piece.labels, !textOnly)
    }
    for (piece in pieces) {
        when {
            piece.locator.startsWith(LATER) -> chunks += ChunkDraft(chunks.size + 1, piece.locator, null, null, visual = true)
            piece.parts.isNotEmpty() -> add(piece)
        }
    }

    // Names reach keyword search through file_fts, so a name vector is only worth a model call when
    // nothing else of the file could be embedded (as Gallery's SmartAlbumIndexingWorker keeps one
    // image-only record per photo).
    if (chunks.isEmpty()) {
        chunks.add(0, ChunkDraft(0, "", null, embed?.invoke(file.namePiece().modelParts(file.name, prefixes))))
    }
    return chunks
}

/** The text a name vector embeds. */
internal fun SourceFile.namePiece() = Piece("", listOf(Part.Text(name + "\n" + folder + "\n" + kind.name.lowercase() + " " + mime)), null)
