package app.rummage.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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

enum class StopReason { DONE, HOT, UNPLUGGED, BUDGET }

data class BatchResult(val processed: Int, val failed: Int, val stopReason: StopReason)

class IndexEngine(
    private val store: Store,
    private val extractor: Extractor,
    private val embedder: Embedder,
    private val monitor: DeviceMonitor,
    private val profile: DeviceProfile,
    private val onFile: (FileMetric) -> Unit = {},
    /** Read per file, so a "Search inside" switch or prefs change applies mid-batch. */
    private val kinds: () -> Set<Kind> = { Kind.entries.toSet() },
    private val currentProfile: () -> DeviceProfile = { profile },
) {
    init {
        store.useSignature(embedder.signature)
    }

    suspend fun runBatch(
        budget: Duration = 9.minutes,
        requireCharging: Boolean = profile.bulkOnlyWhenCharging,
        pauseWhenHot: Boolean = true,
    ): BatchResult {
        val start = TimeSource.Monotonic.markNow()
        var processed = 0
        var failed = 0
        while (true) {
            if (start.elapsedNow() >= budget) return BatchResult(processed, failed, StopReason.BUDGET)
            val state = monitor.state()
            if (state.hot && pauseWhenHot) return BatchResult(processed, failed, StopReason.HOT)
            if (requireCharging && !state.charging) return BatchResult(processed, failed, StopReason.UNPLUGGED)
            val next = store.pending(1).firstOrNull() ?: return BatchResult(processed, failed, StopReason.DONE)
            val metric = index(next, state)
            if (metric.ok) processed++ else failed++
            onFile(metric)
        }
    }

    private suspend fun index(stored: Store.StoredFile, state: DeviceState): FileMetric {
        val file = stored.file
        var extractMs = 0.0
        var embedMs = 0.0
        // Count the attempt up front so a file that kills the process (OOM, native crash) still ends up skipped.
        store.markFailed(stored.id, stored.attempts + 1)
        try {
            val chunks = mutableListOf<Store.NewChunk>()

            val chunk0Text = file.name + "\n" + file.folder + "\n" + file.kind.name.lowercase() + " " + file.mime
            val first = measureTimedValue { embedder.embed(listOf(Part.Text(Prefix.document(file.name, chunk0Text)))) }
            embedMs += first.duration.inWholeNanoseconds / 1e6
            chunks += Store.NewChunk(0, "", chunk0Text, first.value)

            val extracted = measureTimedValue {
                if (file.kind in kinds()) extractor.extract(file, currentProfile()) else emptyList()
            }
            extractMs = extracted.duration.inWholeNanoseconds / 1e6
            val pieces = extracted.value

            suspend fun add(piece: Piece, depth: Int = 0) {
                val parts = piece.parts.map { if (it is Part.Text) Part.Text(Prefix.document(file.name, it.text)) else it }
                val timed = try {
                    measureTimedValue { embedder.embed(parts) }
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
                embedMs += timed.duration.inWholeNanoseconds / 1e6
                val text = piece.keywordText
                    ?: piece.parts.filterIsInstance<Part.Text>().takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
                chunks += Store.NewChunk(chunks.size, piece.locator, text, timed.value)
            }
            for (piece in pieces) if (piece.parts.isNotEmpty()) add(piece)

            store.replaceChunks(stored.id, chunks)
            return FileMetric(file, pieces.size, chunks.size, extractMs, embedMs, true, null, state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EmbedderClosedException) {
            // Not this file's fault: give the attempt back and stop the batch.
            store.markFailed(stored.id, stored.attempts)
            throw e
        } catch (e: Throwable) {
            return FileMetric(file, 0, 0, extractMs, embedMs, false, e.message ?: e::class.simpleName, state, cause = e)
        }
    }
}
