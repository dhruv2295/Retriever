package app.retriever.core

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

enum class StopReason { DONE, HOT, UNPLUGGED, BUDGET, BUSY, PAUSED, POWER_SAVE }

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
    /** Before extraction starts, after any wait for the UI. Null clears the current file, including on cancellation. */
    private val onCurrent: (SourceFile?) -> Unit = {},
) {
    /** [requireCharging] is asked on every file, so a policy that depends on the app being visible or the battery applies mid-batch. */
    suspend fun runBatch(budget: Duration, requireCharging: (DeviceState) -> Boolean, pauseWhenHot: Boolean = true, deepen: Boolean = false): BatchResult {
        val start = TimeSource.Monotonic.markNow()
        var processed = 0
        var failed = 0
        // Pages, so the queue's sort runs once per PAGE files rather than once per file.
        val files = ArrayDeque<Store.StoredFile>()
        val tails = ArrayDeque<Store.TailChunk>()
        while (true) {
            fun stop(reason: StopReason) = BatchResult(processed, failed, reason)
            if (start.elapsedNow() >= budget) return stop(StopReason.BUDGET)
            val now = settings()
            val state = monitor.state()
            stopReason(now, state, busy(), requireCharging(state), pauseWhenHot)?.let { return stop(it) }
            if (files.isEmpty()) files += pendingPage()
            val next = files.removeFirstOrNull()
            if (next != null) {
                val metric = index(next, now, state)
                if (metric.ok) processed++ else failed++
                onFile(metric)
                continue
            }
            if (!deepen || embedder == null) return stop(StopReason.DONE)
            if (tails.isEmpty()) tails += tailPage()
            embedTail(tails.removeFirstOrNull() ?: return stop(StopReason.DONE), embedder)
        }
    }

    /**
     * Embeds one tail chunk. The attempt is counted first, so a chunk that fails, or kills the process
     * natively, drops out of the queue after three tries and stays keyword-only.
     */
    private suspend fun embedTail(tail: Store.TailChunk, embedder: Embedder) {
        store.setTailAttempts(tail.id, tail.attempts + 1)
        try {
            awaitUi()
            store.setChunkVector(tail.id, embedder.embed(listOf(Part.Text(Prefix.document(tail.fileName, tail.text)))))
        } catch (e: InputTooLongException) {
            // Split like add() does, so both halves get a vector instead of the second half being lost.
            val halves = Chunker.chunks(tail.text, maxChars = tail.text.length / 2 + 1)
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

    private suspend fun index(stored: Store.StoredFile, settings: IndexSettings, state: DeviceState): FileMetric {
        val file = stored.file
        var extractMs = 0.0
        var embedMs = 0.0
        // Before the attempt is counted: a search running now would otherwise wait behind this file's extraction.
        awaitUi()
        onCurrent(file)
        // Count the attempt up front so a file that kills the process (OOM, native crash, LMK) still ends up skipped.
        store.markFailed(stored.id, stored.attempts + 1)
        val readInside = file.kind in settings.kinds
        try {
            val extracted = measureTimedValue {
                if (readInside) extractor.extract(file, settings.profile) else emptyList()
            }
            extractMs = extracted.duration.inWholeNanoseconds / 1e6
            val pieces = extracted.value

            val e = embedder
            val chunks = if (e == null) {
                draftChunks<Vector>(file, pieces, embed = null)
            } else {
                draftChunks(file, pieces) { parts ->
                    awaitUi()
                    val timed = measureTimedValue { e.embed(parts) }
                    embedMs += timed.duration.inWholeNanoseconds / 1e6
                    timed.value
                }
            }
            store.replaceChunks(
                stored.id,
                chunks.map { Store.NewChunk(it.ordinal, it.locator, it.text, it.vector) },
                partial = pieces.any { it.locator == CUT_SHORT },
                // A kind switched mid-read: what was read no longer matches what the setting asks for.
                stillCurrent = { (file.kind in this.settings().kinds) == readInside },
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
            return FileMetric(file, 0, 0, extractMs, embedMs, false, e.message ?: e::class.simpleName, state, cause = e)
        } finally {
            onCurrent(null)
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
            requireCharging && !state.charging -> StopReason.UNPLUGGED
            else -> null
        }

        /** Text-only pieces embedded per file on the first pass; the rest wait for the deepen pass. */
        const val HEAD_TEXT_CHUNKS = 8

        /** Matches the tail_attempts cutoff in Index.sq. */
        const val MAX_TAIL_ATTEMPTS = 3
        private const val PAGE = 64
    }
}

/** One chunk as [draftChunks] decides it; [vector] null leaves it keyword-only. */
internal class ChunkDraft<V : Any>(val ordinal: Int, val locator: String, val text: String?, val vector: V?)

/**
 * What a file stores, shared by IndexEngine and RetrievalEval so the eval measures production's rules: the first
 * [IndexEngine.HEAD_TEXT_CHUNKS] text pieces get vectors (the rest wait for the deepen pass), an over-long text
 * piece is split in half, and the name gets a vector only when nothing else could be embedded. [embed] returns
 * what the caller keeps: a stored [Vector], or raw floats in the eval. With no [embed] (text-only mode) every
 * piece with words is stored without a vector, and image or audio pieces with none are dropped.
 */
internal suspend fun <V : Any> draftChunks(
    file: SourceFile,
    pieces: List<Piece>,
    prefixes: Boolean = true,
    embed: (suspend (List<Part>) -> V)?,
): List<ChunkDraft<V>> {
    val chunks = mutableListOf<ChunkDraft<V>>()
    var headText = 0

    suspend fun add(piece: Piece, depth: Int = 0) {
        val textOnly = piece.parts.all { it is Part.Text }
        // A mixed piece's text parts are model input (a video moment's timestamps), not words to search.
        val text = piece.keywordText ?: if (textOnly) piece.parts.joinToString("\n") { (it as Part.Text).text } else null
        if (embed == null) {
            if (text != null) chunks += ChunkDraft(chunks.size + 1, piece.locator, text, null)
            return
        }
        if (textOnly && headText >= IndexEngine.HEAD_TEXT_CHUNKS) {
            chunks += ChunkDraft(chunks.size + 1, piece.locator, text, null)
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
                add(Piece(piece.locator, listOf(Part.Text(half)), half), depth + 1)
            }
            return
        }
        if (textOnly) headText++
        chunks += ChunkDraft(chunks.size + 1, piece.locator, text, vector)
    }
    for (piece in pieces) if (piece.parts.isNotEmpty()) add(piece)

    // Names reach keyword search through file_fts, so a name vector is only worth a model call when
    // nothing else of the file could be embedded (as Gallery's SmartAlbumIndexingWorker keeps one
    // image-only record per photo).
    if (embed != null && chunks.none { it.vector != null }) {
        chunks.add(0, ChunkDraft(0, "", null, embed(file.namePiece().modelParts(file.name, prefixes))))
    }
    return chunks
}

/** The text a name vector embeds. */
internal fun SourceFile.namePiece() = Piece("", listOf(Part.Text(name + "\n" + folder + "\n" + kind.name.lowercase() + " " + mime)), null)
