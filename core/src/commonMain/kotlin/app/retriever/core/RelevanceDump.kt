package app.retriever.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * The relevance-fit dump (relevance-method.md section 4.6): a public library indexed once on a real model, so the
 * strong/weak rule can be fitted and swept offline (RelevanceReplayTest). Little-endian, append-only:
 *
 * - header: "RVDUMP01", signature (u16 length + UTF-8; the model, its sha prefix, vision tokens, dims, max tokens),
 *   vision tokens (i32), dims (i32)
 * - 'F' record, one per library file, written once the file is done (no chunks when it couldn't be read): id
 *   (u16 + UTF-8), kind ordinal (u8), chunk count (u16), then per chunk its [Modality] ordinal (u8, the group the
 *   rule's histogram puts it in), scale (f32) and [DIMS] int8 bytes
 * - 'Q' record, one per query: id, scale, [DIMS] bytes
 *
 * A run killed mid-record leaves a torn tail; [read] stops before it and reports where, so the writer truncates
 * there and resumes after the ids already written.
 */
object RelevanceDump {
    const val MAGIC = "RVDUMP01"
    private const val FILE: Byte = 'F'.code.toByte()
    private const val QUERY: Byte = 'Q'.code.toByte()

    class Chunk(val modality: Modality, val scale: Float, val bytes: ByteArray)

    class FileRecord(val id: String, val kind: Kind, val chunks: List<Chunk>)

    class QueryRecord(val id: String, val scale: Float, val bytes: ByteArray)

    /** [validBytes]: where the last whole record ends. */
    class Dump(
        val signature: String,
        val visionTokens: Int,
        val files: List<FileRecord>,
        val queries: List<QueryRecord>,
        val validBytes: Int,
    )

    fun header(signature: Signature, visionTokens: Int): ByteArray =
        Out().apply { raw(MAGIC.encodeToByteArray()); str(signature.value); i32(visionTokens); i32(DIMS) }.bytes()

    fun file(r: FileRecord): ByteArray = Out().apply {
        u8(FILE.toInt()); str(r.id); u8(r.kind.ordinal); u16(r.chunks.size)
        for (c in r.chunks) { u8(c.modality.ordinal); f32(c.scale); raw(c.bytes) }
    }.bytes()

    fun query(r: QueryRecord): ByteArray = Out().apply { u8(QUERY.toInt()); str(r.id); f32(r.scale); raw(r.bytes) }.bytes()

    /** Every whole record of [bytes]; throws only on a bad header or an unknown record type. */
    fun read(bytes: ByteArray): Dump {
        val inp = In(bytes)
        require(bytes.size >= MAGIC.length && bytes.copyOf(MAGIC.length).decodeToString() == MAGIC) { "not a relevance dump" }
        inp.pos = MAGIC.length
        val signature = inp.str()
        val vt = inp.i32()
        require(inp.i32() == DIMS) { "dump dims differ from $DIMS" }
        val files = ArrayList<FileRecord>()
        val queries = ArrayList<QueryRecord>()
        var valid = inp.pos
        try {
            while (inp.pos < bytes.size) {
                when (val type = inp.u8().toByte()) {
                    FILE -> {
                        val id = inp.str()
                        val kind = Kind.entries[inp.u8()]
                        files += FileRecord(id, kind, List(inp.u16()) { Chunk(Modality.entries[inp.u8()], inp.f32(), inp.raw(DIMS)) })
                    }
                    QUERY -> queries += QueryRecord(inp.str(), inp.f32(), inp.raw(DIMS))
                    else -> error("unknown record type $type at $valid")
                }
                valid = inp.pos
            }
        } catch (_: IndexOutOfBoundsException) {
            // A torn last record: keep what came before it.
        }
        return Dump(signature, vt, files, queries, valid)
    }

    private class Out {
        private var buf = ByteArray(512)
        private var n = 0

        private fun room(k: Int) { if (n + k > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + k)) }
        fun u8(v: Int) { room(1); buf[n++] = v.toByte() }
        fun u16(v: Int) { require(v in 0..0xFFFF); u8(v and 0xFF); u8(v ushr 8) }
        fun i32(v: Int) { for (s in 0 until 32 step 8) u8((v ushr s) and 0xFF) }
        fun f32(v: Float) = i32(v.toRawBits())
        fun raw(b: ByteArray) { room(b.size); b.copyInto(buf, n); n += b.size }
        fun str(s: String) = s.encodeToByteArray().let { u16(it.size); raw(it) }
        fun bytes() = buf.copyOf(n)
    }

    private class In(private val b: ByteArray) {
        var pos = 0

        private fun need(k: Int) { if (pos + k > b.size) throw IndexOutOfBoundsException() }
        fun u8(): Int { need(1); return b[pos++].toInt() and 0xFF }
        fun u16(): Int = u8() or (u8() shl 8)
        fun i32(): Int = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
        fun f32(): Float = Float.fromBits(i32())
        fun raw(k: Int): ByteArray { need(k); return b.copyOfRange(pos, pos + k).also { pos += k } }
        fun str(): String = raw(u16()).decodeToString()
    }
}

/** One library file of the prepared public set (retriever-eval-data/prepared/manifest.json). */
@Serializable
data class DumpFile(val file: String, val kind: String, val dataset: String, val id: String)

/** One query of prepared/queries.json; the no-match constructions say which files to leave out of its library. */
@Serializable
data class DumpQuery(
    val id: String,
    val text: String,
    val lang: String,
    val dataset: String,
    val split: String,
    val relevant: List<String>,
    val kind: String,
    val construction: String? = null,
    @SerialName("exclude_files") val excludeFiles: List<String> = emptyList(),
    @SerialName("exclude_dataset") val excludeDataset: String? = null,
    val noisy: Boolean = false,
)

private val dumpJson = Json { ignoreUnknownKeys = true }

fun parseDumpManifest(json: String): List<DumpFile> = dumpJson.decodeFromString(json)

fun parseDumpQueries(json: String): List<DumpQuery> = dumpJson.decodeFromString(json)

/** The [SourceFile] a manifest entry is indexed as: folder://documents/library/photos|docs/<file>. */
fun DumpFile.source(): SourceFile = when (kind) {
    "photo" -> SourceFile("folder://documents/library/photos/$file", file, "library/photos", "image/jpeg", Kind.PHOTO, 0, 0)
    "doc" -> SourceFile("folder://documents/library/docs/$file", file, "library/docs", "text/plain", Kind.DOC, 0, 0)
    else -> error("manifest kind $kind")
}

/**
 * Indexes [files] and embeds [queries] as production does, appending one record per file and per query through
 * [append] (each write is a checkpoint). Ids in [doneFiles] and [doneQueries] were written by an earlier run and are
 * skipped. Files go through the production extractor and IndexEngine's [draftChunks]; queries are embedded as
 * SearchEngine embeds them. A file that fails is written with no chunks, as production keeps only its name.
 */
suspend fun dumpLibrary(
    files: List<DumpFile>,
    queries: List<DumpQuery>,
    extractor: Extractor,
    embedder: Embedder,
    profile: DeviceProfile,
    doneFiles: Set<String>,
    doneQueries: Set<String>,
    append: (ByteArray) -> Unit,
    log: (String) -> Unit,
) {
    var failed = 0
    var keywordOnly = 0
    val todo = files.filter { it.file !in doneFiles }
    log("files: ${files.size - todo.size} done before, ${todo.size} to go")
    todo.forEachIndexed { i, f ->
        val file = f.source()
        val chunks = try {
            draftChunks(file, extractor.extract(file, profile)) { embedder.embed(it) }.mapNotNull { c ->
                // Past the first text chunks, the deepen pass embeds the rest as IndexEngine.embedTail does.
                val v = c.vector ?: c.text?.let { t ->
                    try {
                        embedder.embed(listOf(Part.Text(Prefix.document(file.name, t))))
                    } catch (e: InputTooLongException) {
                        null
                    }
                }
                if (v == null) keywordOnly++
                v?.let { RelevanceDump.Chunk(Modality.of(file.kind, c.text != null, c.ordinal.toLong()), it.scale, it.bytes) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: EmbedderClosedException) {
            throw e
        } catch (e: Exception) {
            failed++
            log("couldn't index ${f.file}: ${e.message}")
            emptyList()
        }
        append(RelevanceDump.file(RelevanceDump.FileRecord(f.file, file.kind, chunks)))
        if ((i + 1) % 200 == 0 || i == todo.lastIndex) log("files ${i + 1}/${todo.size}, failed $failed, keyword-only chunks $keywordOnly")
    }
    val memo = HashMap<String, Vector>()
    val left = queries.filter { it.id !in doneQueries }
    log("queries: ${queries.size - left.size} done before, ${left.size} to go")
    left.forEachIndexed { i, q ->
        val text = q.text.trim()
        val v = memo.getOrPut(text) { embedder.embed(listOf(Part.Text(Prefix.query(text)))) }
        append(RelevanceDump.query(RelevanceDump.QueryRecord(q.id, v.scale, v.bytes)))
        if ((i + 1) % 500 == 0 || i == left.lastIndex) log("queries ${i + 1}/${left.size}")
    }
}
