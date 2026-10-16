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
    /** On incremental scans: every URI the source currently sees, so deletions can be diffed out. */
    val present: Set<String>? = null,
    /**
     * URI prefixes this scan covered (null: everything). Known files outside them, such as those on an
     * unmounted SD card, are kept rather than removed.
     */
    val scope: Set<String>? = null,
)

interface Extractor {
    /** One file in, searchable pieces out. Chunk 0 (name and metadata) is added by the core. */
    suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece>
}

interface EmbedderFactory {
    /** An NPU build lists only [Backend.NPU]: it is compiled for one chip and can't run elsewhere. */
    fun backends(build: ModelBuild): List<Backend>

    /** [build].variant must equal [profile].model. Throws when the model can't load; Swift implements it as `throws`. */
    @Throws(Throwable::class)
    fun open(profile: DeviceProfile, build: ModelBuild): Embedder
}

interface Embedder : AutoCloseable {
    val signature: Signature
    val backend: Backend

    /** Anything that ran differently from [backend], such as an encoder that fell back to CPU. */
    val detail: String? get() = null

    /** Raw model output for [parts] in order. Use [embed] to get a stored [Vector]. */
    suspend fun embedRaw(parts: List<Part>): FloatArray
}

/** The input is longer than the model's token window. The caller should split it; the embedder is fine. */
class InputTooLongException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * The embedder can't embed anything any more: it was closed, or its backend broke (a known-good input fails
 * too). The caller must open a new one. Not the file's fault.
 */
class EmbedderClosedException(message: String = "embedder closed", cause: Throwable? = null) :
    IllegalStateException(message, cause)

suspend fun Embedder.embed(parts: List<Part>): Vector = Vector.of(embedRaw(parts), signature)

interface DeviceMonitor {
    fun state(): DeviceState
}
