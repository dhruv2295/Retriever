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

enum class StopReason { DONE, HOT, UNPLUGGED, BUDGET, BUSY, PAUSED }

data class BatchResult(val processed: Int, val failed: Int, val stopReason: StopReason)

/** Read before every file, so a prefs change or Pause applies mid-batch. */
data class IndexSettings(val profile: DeviceProfile, val kinds: Set<Kind>, val paused: Boolean = false)

class IndexEngine(
    private val store: Store,
    private val extractor: Extractor,
    private val embedder: Embedder,
    private val monitor: DeviceMonitor,
    private val settings: () -> IndexSettings,
    private val onFile: (FileMetric) -> Unit = {},
    private val busy: () -> Boolean = { false },
    /** Suspends while the UI needs the model (a search in flight), so indexing never delays a query. */
    private val awaitUi: suspend () -> Unit = {},
) {
    suspend fun runBatch(
        budget: Duration,
        requireCharging: Boolean,
        pauseWhenHot: Boolean = true,
        deepen: Boolean = false,
    ): BatchResult {
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
            if (now.paused) return stop(StopReason.PAUSED)
            if (busy()) return stop(StopReason.BUSY)
            val state = monitor.state()
            if (state.hot && pauseWhenHot) return stop(StopReason.HOT)
            if (requireCharging && !state.charging) return stop(StopReason.UNPLUGGED)
            if (files.isEmpty()) files += store.pending(PAGE)
            val next = files.removeFirstOrNull()
            if (next != null) {
                val metric = index(next, now, state)
                if (metric.ok) processed++ else failed++
                onFile(metric)
                continue
            }
            if (!deepen) return stop(StopReason.DONE)
            if (tails.isEmpty()) tails += store.tailChunks(PAGE)
            embedTail(tails.removeFirstOrNull() ?: return stop(StopReason.DONE))
        }
    }

    /**
     * Embeds one tail chunk. The attempt is counted first, so a chunk that fails, or kills the process
     * natively, drops out of the queue after three tries and stays keyword-only.
     */
    private suspend fun embedTail(tail: Store.TailChunk) {
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
        // Count the attempt up front so a file that kills the process (OOM, native crash, LMK) still ends up skipped.
        store.markFailed(stored.id, stored.attempts + 1)
        try {
            val extracted = measureTimedValue {
                if (file.kind in settings.kinds) extractor.extract(file, settings.profile) else emptyList()
            }
            extractMs = extracted.duration.inWholeNanoseconds / 1e6
            val pieces = extracted.value

            val chunks = mutableListOf<Store.NewChunk>()
            var headText = 0

            suspend fun embedTimed(parts: List<Part>): Vector {
                awaitUi()
                val timed = measureTimedValue { embedder.embed(parts) }
                embedMs += timed.duration.inWholeNanoseconds / 1e6
                return timed.value
            }

            suspend fun add(piece: Piece, depth: Int = 0) {
                // EmbeddingGemma 2 model card: task prefixes apply to text only; image, video and audio
                // inputs (with their timestamp text) go in raw.
                val textOnly = piece.parts.all { it is Part.Text }
                // A mixed piece's text parts are model input (a video moment's timestamps), not words to search.
                val text = piece.keywordText ?: if (textOnly) piece.parts.joinToString("\n") { (it as Part.Text).text } else null
                if (textOnly && headText >= HEAD_TEXT_CHUNKS) {
                    chunks += Store.NewChunk(chunks.size + 1, piece.locator, text, null)
                    return
                }
                val parts = if (textOnly) piece.parts.map { Part.Text(Prefix.document(file.name, (it as Part.Text).text)) } else piece.parts
                val vector = try {
                    embedTimed(parts)
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
                chunks += Store.NewChunk(chunks.size + 1, piece.locator, text, vector)
            }
            for (piece in pieces) if (piece.parts.isNotEmpty()) add(piece)

            // Names reach keyword search through file_fts, so a name vector is only worth a model call when
            // nothing else of the file could be embedded (as Gallery's SmartAlbumIndexingWorker keeps one
            // image-only record per photo).
            if (chunks.none { it.vector != null }) {
                val nameText = file.name + "\n" + file.folder + "\n" + file.kind.name.lowercase() + " " + file.mime
                chunks.add(0, Store.NewChunk(0, "", null, embedTimed(listOf(Part.Text(Prefix.document(file.name, nameText))))))
            }

            store.replaceChunks(stored.id, chunks)
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
        }
    }

    companion object {
        /** Text-only pieces embedded per file on the first pass; the rest wait for the deepen pass. */
        const val HEAD_TEXT_CHUNKS = 8

        /** Matches the tail_attempts cutoff in Index.sq. */
        const val MAX_TAIL_ATTEMPTS = 3
        private const val PAGE = 64
    }
}
