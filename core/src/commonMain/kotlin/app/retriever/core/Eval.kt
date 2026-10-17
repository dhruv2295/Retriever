package app.retriever.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.measureTimedValue

/** A query and the name of the corpus file it should find; [kind] is only cross-checked against that file. */
@Serializable
data class EvalQuery(val q: String, val file: String, val kind: Kind? = null)

fun parseEvalQueries(json: String): List<EvalQuery> = Json { ignoreUnknownKeys = true }.decodeFromString(json)

/** One way to rank. The defaults are production's, except [visionTokens], which comes from the device's tier. */
data class EvalSetting(
    val visionTokens: Int,
    val dims: Int = DIMS,
    val int8: Boolean = true,
    val prefixes: Boolean = true,
    val tuning: SearchTuning = SearchTuning(),
) {
    val label: String
        get() = "d$dims ${if (int8) "int8" else "float"} · ${if (prefixes) "prefixes" else "no prefixes"} · vt$visionTokens · " +
            "k${tuning.rrfK} · kw${tuning.keywordWeight}"
}

/** [ranks]: each query's file, 1-based, in the top [RetrievalEval.LIMIT] (null: not there). [top]: corpus indexes, best first. */
data class EvalRow(val setting: EvalSetting, val ranks: List<Int?>, val top: List<List<Int>>)

data class EvalRun(
    val corpus: List<SourceFile>,
    val queries: List<EvalQuery>,
    val rows: List<EvalRow>,
    /** Median ms per model call by vision tokens: (image piece, text piece). */
    val embedMs: Map<Int, Pair<Double, Double>>,
    /** Refused settings, unreadable files, kind mismatches. */
    val notes: List<String>,
) {
    /** The expected file's kind for each query. */
    val kinds: List<Kind> = queries.map { q -> corpus.first { it.name == q.file }.kind }
}

data class Metrics(val n: Int, val at1: Int, val at5: Int, val mrr: Double)

fun metrics(ranks: List<Int?>): Metrics = Metrics(
    ranks.size,
    ranks.count { it == 1 },
    ranks.count { it != null && it <= 5 },
    if (ranks.isEmpty()) 0.0 else ranks.sumOf { r -> r?.let { 1.0 / it } ?: 0.0 } / ranks.size,
)

/**
 * The defaults and one-factor sweeps around them, crossed into a full grid: embedding cost is per (prefixes, vision
 * tokens) pair, and ranking from cached vectors is cheap, so the grid only adds a best-combination row. Keyword
 * weight 0 leaves the vector lane alone in charge: a diagnostic of the model's own ranking, not a production option.
 */
fun evalGrid(visionTokens: List<Int>): List<EvalSetting> {
    val dims = listOf(128 to false, 256 to false, 512 to false, 768 to false, DIMS to true)
    return dims.flatMap { (d, int8) ->
        listOf(true, false).flatMap { prefixes ->
            visionTokens.flatMap { vt ->
                listOf(10, 30, 60, 100).flatMap { k ->
                    listOf(0.0, 0.5, 1.0, 2.0).map { w -> EvalSetting(vt, d, int8, prefixes, SearchTuning(k, w)) }
                }
            }
        }
    }
}

/**
 * Ranks [corpus] for each query under many settings, reproducing SearchEngine's fusion in memory instead of
 * re-indexing per setting: every file is read once, every piece embedded raw once per (prefixes, vision tokens)
 * pair through IndexEngine's own [draftChunks], the keyword lanes come from [store] (empty, throwaway) filled with
 * the same chunks, and lanes are cut and fused as SearchEngine does. EvalTest holds the default row to
 * SearchEngine's file order.
 */
class RetrievalEval(
    private val extractor: Extractor,
    private val factory: EmbedderFactory,
    private val build: ModelBuild,
    /** Production's profile on this device; settings vary its vision tokens. */
    private val profile: DeviceProfile,
    private val store: Store,
    private val log: (String) -> Unit = {},
) {
    private class Group(val chunks: List<Pair<Int, FloatArray>>, val queries: List<FloatArray>, val keyword: List<List<Int>>, val names: List<List<Int>>)

    @Throws(Throwable::class)
    suspend fun run(corpus: List<SourceFile>, queries: List<EvalQuery>, settings: List<EvalSetting>): EvalRun {
        val notes = mutableListOf<String>()
        val targets = queries.map { q ->
            corpus.indexOfFirst { it.name == q.file }.also { require(it >= 0) { "\"${q.q}\" wants ${q.file}, which is not in the corpus" } }
        }
        queries.forEachIndexed { i, q ->
            if (q.kind != null && q.kind != corpus[targets[i]].kind) notes += "\"${q.q}\": queries.json says ${q.kind}, ${q.file} is ${corpus[targets[i]].kind}"
        }
        store.upsertFiles(corpus)
        val ids = corpus.map { checkNotNull(store.fileByUri(it.uri)).id }
        val indexOf = ids.withIndex().associate { (i, id) -> id to i }
        val pieces = corpus.map { file ->
            try {
                extractor.extract(file, profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notes += "couldn't read ${file.name}: ${e.message}"
                emptyList()
            }
        }
        log("read ${corpus.size} files: ${pieces.sumOf { it.size }} pieces")

        val groups = HashMap<Pair<Boolean, Int>, Group>()
        val embedMs = LinkedHashMap<Int, Pair<Double, Double>>()
        for (vt in settings.map { it.visionTokens }.distinct()) {
            val embedder = try {
                factory.open(profile.copy(visionTokens = vt), build)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                notes += "vision tokens $vt: the model didn't open (${e.message})"
                continue
            }
            embedder.use {
                val memo = HashMap<List<Part>, FloatArray>()
                val imageMs = mutableListOf<Double>()
                val textMs = mutableListOf<Double>()
                suspend fun raw(parts: List<Part>): FloatArray = memo[parts] ?: run {
                    val timed = measureTimedValue { embedder.embedRaw(parts) }
                    (if (parts.any { it is Part.Image }) imageMs else textMs) += timed.duration.inWholeNanoseconds / 1e6
                    timed.value.also { memo[parts] = it }
                }
                for (prefixes in settings.filter { it.visionTokens == vt }.map { it.prefixes }.distinct()) {
                    log("embedding: vision tokens $vt, prefixes $prefixes")
                    val drafts = corpus.indices.map { i ->
                        try {
                            draftChunks(corpus[i], pieces[i], prefixes) { raw(it) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: EmbedderClosedException) {
                            throw e
                        } catch (e: Exception) {
                            // As in production, a file the model rejects keeps only its name in the keyword lane.
                            notes += "vision tokens $vt, prefixes $prefixes: ${corpus[i].name} failed (${e.message})"
                            emptyList()
                        }
                    }
                    for (i in corpus.indices) store.replaceChunks(ids[i], drafts[i].map { Store.NewChunk(it.ordinal, it.locator, it.text, null) })
                    groups[prefixes to vt] = Group(
                        chunks = drafts.flatMapIndexed { i, file -> file.mapNotNull { c -> c.vector?.let { i to it } } },
                        queries = queries.map { raw(listOf(Part.Text(if (prefixes) Prefix.query(it.q.trim()) else it.q.trim()))) },
                        keyword = queries.map { q ->
                            val hits = store.keywordSearch(q.q.trim(), SearchEngine.CANDIDATES)
                            val rows = store.chunks(hits.map { it.chunkId })
                            hits.mapNotNull { rows[it.chunkId]?.fileId }.distinct().map { indexOf.getValue(it) }
                        },
                        names = queries.map { q -> store.fileKeywordSearch(q.q.trim(), SearchEngine.CANDIDATES).map { indexOf.getValue(it) } },
                    )
                }
                embedMs[vt] = median(imageMs) to median(textMs)
            }
        }

        val rawDims = groups.values.firstOrNull()?.queries?.firstOrNull()?.size ?: 0
        // The vector lane doesn't depend on the fusion settings, so each one is scored once per (group, dims, int8).
        val vectorLanes = HashMap<List<Any>, List<List<Int>>>()
        val rows = settings.mapNotNull { s ->
            val group = groups[s.prefixes to s.visionTokens] ?: return@mapNotNull null
            if (s.dims > rawDims) {
                notes += "d${s.dims}: the model returns $rawDims dims"
                return@mapNotNull null
            }
            val lanes = vectorLanes.getOrPut(listOf(s.prefixes, s.visionTokens, s.dims, s.int8)) { vectorLanes(group, s) }
            val top = queries.indices.map { qi ->
                val fused = listOf(lanes[qi], group.keyword[qi], group.names[qi]).map { lane -> lane.map { it.toLong() } }
                rrfMerge(fused, s.tuning.rrfK, listOf(1.0, s.tuning.keywordWeight, s.tuning.keywordWeight)).take(LIMIT).map { it.first.toInt() }
            }
            EvalRow(s, top.mapIndexed { qi, files -> files.indexOf(targets[qi]).takeIf { it >= 0 }?.plus(1) }, top)
        }
        return EvalRun(corpus, queries, rows, embedMs, notes.distinct())
    }

    /** Production's vector lane: the best [SearchEngine.CANDIDATES] chunks, then each file at its best chunk. */
    private fun vectorLanes(group: Group, s: EvalSetting): List<List<Int>> {
        val quantized = s.int8 && s.dims == DIMS
        val sig = Signature("eval")
        val chunkVectors = if (quantized) group.chunks.map { Vector.of(it.second, sig) } else emptyList()
        return group.queries.map { q ->
            val qv = if (quantized) Vector.of(q, sig) else null
            val scores = group.chunks.mapIndexed { i, (_, raw) ->
                if (qv != null) dotInt8(chunkVectors[i].bytes, 0, qv.bytes, qv.scale, chunkVectors[i].scale) else cosine(q, raw, s.dims)
            }
            scores.indices.sortedByDescending { scores[it] }.take(SearchEngine.CANDIDATES).map { group.chunks[it].first }.distinct()
        }
    }

    companion object {
        /** Retriever.search's default limit: a file past it is not found. */
        const val LIMIT = 20
    }
}

private fun median(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

/**
 * The markdown report: per-factor tables around [default], the best row of the grid, per-query ranks and timings.
 * [production] is the same queries through the real app, when the platform ran it.
 */
fun EvalRun.report(default: EvalSetting, production: List<Int?>? = null): String {
    val byKind = kinds.distinct().sortedBy { it.ordinal }
    val defaultRow = rows.firstOrNull { it.setting == default } ?: return "No default row: ${notes.joinToString("; ")}"
    val best = rows.maxWith(compareBy<EvalRow>({ metrics(it.ranks).at5 }, { metrics(it.ranks).mrr }, { metrics(it.ranks).at1 }))
    fun f2(x: Double) = ((x * 100).roundToInt() / 100.0).toString().let { if (it.substringAfter('.').length < 2) it + "0" else it }
    fun cell(m: Metrics) = "${m.at1} · ${m.at5} · ${f2(m.mrr)}"
    fun kindRanks(ranks: List<Int?>, k: Kind) = ranks.filterIndexed { i, _ -> kinds[i] == k }

    /** The decision rule: recall@5 up by 2+ queries, MRR not down, no kind down by more than one query at recall@5. */
    fun verdict(row: EvalRow): String {
        if (row.setting == default) return "default"
        val a = metrics(defaultRow.ranks)
        val b = metrics(row.ranks)
        val worst = byKind.minOf { metrics(kindRanks(row.ranks, it)).at5 - metrics(kindRanks(defaultRow.ranks, it)).at5 }
        val pass = b.at5 - a.at5 >= 2 && b.mrr >= a.mrr && worst >= -1
        return "${if (b.at5 >= a.at5) "+" else ""}${b.at5 - a.at5} r@5, ${if (b.mrr >= a.mrr) "+" else ""}${f2(b.mrr - a.mrr)} MRR, worst kind $worst: " +
            if (pass) "**passes**" else "no"
    }

    val header = "| Setting | All (n=${queries.size}) | " + byKind.joinToString(" | ") { "$it (n=${kinds.count { k -> k == it }})" } + " | vs default |\n" +
        "|---|---|" + byKind.joinToString("") { "---|" } + "---|\n"
    fun line(label: String, ranks: List<Int?>, verdict: String) =
        "| $label | ${cell(metrics(ranks))} | " + byKind.joinToString(" | ") { cell(metrics(kindRanks(ranks, it))) } + " | $verdict |\n"
    fun table(rs: List<EvalRow>) = header + rs.joinToString("") { line(it.setting.label, it.ranks, verdict(it)) }

    val out = StringBuilder()
    out.appendLine("Cells: recall@1 · recall@5 (queries) · MRR. Ranks count within the top ${RetrievalEval.LIMIT}, as search returns them.")
    out.appendLine()
    out.appendLine("### Corpus")
    out.appendLine(corpus.groupBy { it.kind }.entries.joinToString(", ") { "${it.value.size} ${it.key}" } + " files; ${queries.size} queries.")
    if (notes.isNotEmpty()) out.appendLine(notes.joinToString("\n") { "- $it" })
    out.appendLine()
    out.appendLine("### Embed time (median ms per model call)")
    out.appendLine("| Vision tokens | Image piece | Text piece |\n|---|---|---|")
    embedMs.forEach { (vt, ms) -> out.appendLine("| $vt | ${ms.first.roundToInt()} | ${ms.second.roundToInt()} |") }
    out.appendLine()
    out.appendLine("### Default")
    out.append(header)
    out.append(line(default.label, defaultRow.ranks, "default"))
    if (production != null) out.append(line("production path (Retriever.search)", production, "agrees on ${production.indices.count { production[it] == defaultRow.ranks[it] }}/${queries.size} ranks"))
    out.appendLine()
    val factors = listOf<Pair<String, (EvalSetting) -> EvalSetting>>(
        "Dimensions and int8" to { it.copy(dims = default.dims, int8 = default.int8) },
        "Task prefixes" to { it.copy(prefixes = default.prefixes) },
        "Vision tokens per image" to { it.copy(visionTokens = default.visionTokens) },
        "RRF k" to { it.copy(tuning = it.tuning.copy(rrfK = default.tuning.rrfK)) },
        "Keyword weight" to { it.copy(tuning = it.tuning.copy(keywordWeight = default.tuning.keywordWeight)) },
    )
    for ((name, reset) in factors) {
        out.appendLine("### $name (all else default)")
        out.append(table(rows.filter { reset(it.setting) == default }))
        out.appendLine()
    }
    out.appendLine("### Vector lane alone (keyword weight 0): the model's own ranking for each model-side setting")
    out.append(table(rows.filter { it.setting.tuning == default.tuning.copy(keywordWeight = 0.0) }))
    out.appendLine()
    out.appendLine("### Best of ${rows.size} combinations (recall@5, then MRR, then recall@1); ten best follow it")
    out.append(table(rows.sortedWith(compareByDescending<EvalRow>({ metrics(it.ranks).at5 }).thenByDescending { metrics(it.ranks).mrr }.thenByDescending { metrics(it.ranks).at1 }).take(10)))
    out.appendLine()
    out.appendLine("### Per-query ranks (- means not in the top ${RetrievalEval.LIMIT})")
    val alone = rows.firstOrNull { it.setting == default.copy(tuning = default.tuning.copy(keywordWeight = 0.0)) }
    out.appendLine("| Query | File | Kind | Default | Vector alone | Best |" + (if (production != null) " Production |" else "") + " Default top 3 |")
    out.appendLine("|---|---|---|---|---|---|" + (if (production != null) "---|" else "") + "---|")
    queries.forEachIndexed { i, q ->
        val prod = if (production != null) " ${production[i] ?: "-"} |" else ""
        out.appendLine(
            "| ${q.q} | ${q.file} | ${kinds[i]} | ${defaultRow.ranks[i] ?: "-"} | ${alone?.ranks?.get(i) ?: "-"} | ${best.ranks[i] ?: "-"} |$prod " +
                "${defaultRow.top[i].take(3).joinToString(", ") { corpus[it].name }} |",
        )
    }
    out.appendLine()
    out.appendLine("Best: ${best.setting.label}")
    return out.toString()
}
