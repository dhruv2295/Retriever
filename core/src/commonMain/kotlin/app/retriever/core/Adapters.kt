package app.retriever.core

/** Files added, changed or removed since a saved token (MediaStore generation, PhotoKit token). */
interface FileSource {
    /** Bump when the rules for which files count change, so the next sync rescans everything. */
    val rulesVersion: Int get() = 1

    suspend fun changes(since: String?): Changes
}

data class Changes(
    val upserted: List<SourceFile>,
    val removed: List<String>,
    val nextToken: String,
    val isFullScan: Boolean = false,
    /**
     * On incremental scans: every URI the source currently sees, so deletions can be diffed out. On full scans:
     * URIs seen but left out of [upserted] (an iCloud placeholder), which keep what is already indexed.
     */
    val present: Set<String>? = null,
    /**
     * URI prefixes this scan covered (null: everything). Known files outside them, such as those on an
     * unmounted SD card, are kept rather than removed.
     */
    val scope: Set<String>? = null,
)

interface Extractor {
    /** One file in, searchable pieces out, on the caller's worker context. Chunk 0 is added by the core. */
    suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece>

    /**
     * False while a reader it needs is still downloading, which it has asked for: Android's text and label readers come
     * from Play services after install. A file read without them would keep no words or labels, and isn't read again.
     * True once they're here, or when they can't come (no Play services), so reading never waits for nothing.
     */
    @Throws(Throwable::class)
    suspend fun ready(): Boolean = true

    /**
     * The first pass's read while the model is there to deepen later: a photo's image ([imageFirst]), a video's one moment
     * and a scanned PDF's first [PDF_HEAD_OCR_PAGES] pages, the rest left as [later] placeholders. By default, the whole file now.
     */
    suspend fun extractHead(file: SourceFile, profile: DeviceProfile): List<Piece> = extract(file, profile)

    /** Re-reads only the pixels at a stored locator, never OCR or document text. */
    suspend fun visual(file: SourceFile, locator: String): Part.Image? = null

    /**
     * What a [later] placeholder at [locator] holds, read in the deepen pass ([WORDS]: as one read gives a photo, once the
     * first pass has no files left): empty when there is nothing there (the
     * placeholder goes), null when it can't be read now (a failed try; after three the placeholder stays as it is).
     */
    suspend fun later(file: SourceFile, locator: String, profile: DeviceProfile): List<Piece>? =
        visual(file, locator)?.let { listOf(Piece(locator, listOf(it), null)) }
}

interface EmbedderFactory {
    /** An NPU build lists only [Backend.NPU]: it is compiled for one chip and can't run elsewhere. */
    fun backends(build: ModelBuild): List<Backend>

    /** The backend to open [profile] on right now; iOS answers CPU for a GPU profile while the app is in the background. */
    fun backendNow(profile: DeviceProfile): Backend = profile.backend

    fun profileNow(profile: DeviceProfile, visible: Boolean): DeviceProfile {
        val current = profile.copy(backend = backendNow(profile))
        val backend = inferenceBackendFor(current, visible)
        return current.copy(backend = backend, visionBackend = backend)
    }

    /** [build].variant must equal [profile].model. Throws when the model can't load; Swift implements it as `throws`. */
    @Throws(Throwable::class)
    fun open(profile: DeviceProfile, build: ModelBuild): Embedder

    /** [inUse] is what search and indexing run on now: drop caches built for any other configuration. */
    fun keepOnly(inUse: Embedder) {}
}

interface Embedder : AutoCloseable {
    val signature: Signature
    val backend: Backend

    val visionBackend: Backend get() = backend

    /** Anything that ran differently from [backend], such as an encoder that fell back to CPU. */
    val detail: String? get() = null

    /** Raw output on the caller's worker context, for [parts] in order. Use [embed] for a stored [Vector]. */
    suspend fun embedRaw(parts: List<Part>): FloatArray
}

/** The input is longer than the model's token window. The caller should split it; the embedder is fine. */
class InputTooLongException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * The embedder can't embed anything any more: it was closed, or its backend broke (a known-good input fails
 * too). The caller must open a new one. Not the file's fault. [broken] false: the backend is fine but can't run
 * right now (iOS refuses GPU work from a backgrounded app), so the caller reopens it later without falling back.
 */
class EmbedderClosedException(message: String = "embedder closed", cause: Throwable? = null, val broken: Boolean = true) :
    IllegalStateException(message, cause)

suspend fun Embedder.embed(parts: List<Part>): Vector = Vector.of(embedRaw(parts), signature)

interface DeviceMonitor {
    fun state(): DeviceState

    /** The system says memory is low now (Android's `MemoryInfo.lowMemory`): no file is read ahead beside the model. */
    fun lowMemory(): Boolean = false
}
