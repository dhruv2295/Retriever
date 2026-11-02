package app.retriever.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.measureTimedValue

/**
 * A query and the name of the corpus file it should find; null: nothing in the corpus should match. [kind] is only
 * cross-checked against that file.
 */
@Serializable
data class EvalQuery(val q: String, val file: String? = null, val kind: Kind? = null)

private val evalJson = Json { ignoreUnknownKeys = true }

fun parseEvalQueries(json: String): List<EvalQuery> = evalJson.decodeFromString(json)

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

/**
 * Per query: [ranks], its file, 1-based, in the top [RetrievalEval.LIMIT] (null: not there, or a no-match query);
 * [top], corpus indexes, strong then weak as search orders them; [strong], how many of [top] are strong;
 * [cos], each file's best-chunk cosine in the vector lane, by corpus index; [keyword], the files the words lane or a
 * close (exact or prefix) name match found, which are strong whatever their cosine.
 */
data class EvalRow(
    val setting: EvalSetting,
    val ranks: List<Int?>,
    val top: List<List<Int>>,
    val strong: List<Int>,
    val cos: List<Map<Int, Float>>,
    val keyword: List<Set<Int>>,
)

data class EvalRun(
    val corpus: List<SourceFile>,
    val queries: List<EvalQuery>,
    val rows: List<EvalRow>,
    /** Median ms per model call by vision tokens: (image piece, text piece). */
    val embedMs: Map<Int, Pair<Double, Double>>,
    /** Refused settings, unreadable files, kind mismatches. */
    val notes: List<String>,
    /** The rule that split strong from weak, as search ran it; null: every file found by meaning is strong. */
    val rule: RelevanceRule?,
) {
    /** The expected file's kind for each query; null for a no-match query. */
    val kinds: List<Kind?> = queries.map { q -> q.file?.let { f -> corpus.first { it.name == f }.kind } }
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
    /** Production's relevance rule; every row splits strong from weak with it, as search does. */
    private val rule: RelevanceRule? = RelevanceRule.shipped,
) {
    /** A chunk's corpus index, raw vector and [Modality] ordinal. */
    private class Chunk(val file: Int, val raw: FloatArray, val modality: Int)

    private class Group(val chunks: List<Chunk>, val queries: List<FloatArray>, val keyword: List<List<Int>>)

    /** Per query: each file at its best chunk's cosine, best first, and the files with a chunk on its group's strong line. */
    private class MeaningLane(val cos: LinkedHashMap<Int, Float>, val strong: Set<Int>)

    @Throws(Throwable::class)
    suspend fun run(corpus: List<SourceFile>, queries: List<EvalQuery>, settings: List<EvalSetting>): EvalRun {
        val notes = mutableListOf<String>()
        val targets = queries.map { q ->
            q.file?.let { f -> corpus.indexOfFirst { it.name == f }.also { require(it >= 0) { "\"${q.q}\" wants $f, which is not in the corpus" } } }
        }
        queries.forEachIndexed { i, q ->
            val t = targets[i] ?: return@forEachIndexed
            if (q.kind != null && q.kind != corpus[t].kind) notes += "\"${q.q}\": queries.json says ${q.kind}, ${q.file} is ${corpus[t].kind}"
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
        // The name lane as search runs it with its in-memory index: every match in NameIndex's order, and the close ones.
        val nameIndex = NameIndex.build(corpus.mapIndexed { i, f -> NameEntry(ids[i], f.name, f.folder, f.kind) })
        val names = queries.map { q ->
            val hits = nameIndex.search(q.q.trim(), limit = SearchEngine.CANDIDATES)
            listOf(hits, hits.filter { it.match <= MatchClass.PREFIX }).map { lane -> lane.map { indexOf.getValue(it.id) } }
        }

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
                        chunks = drafts.flatMapIndexed { i, file ->
                            file.mapNotNull { c ->
                                c.vector?.let { Chunk(i, it, Modality.of(corpus[i].kind, c.text != null && !c.visual, c.ordinal.toLong()).ordinal) }
                            }
                        },
                        queries = queries.map { raw(listOf(Part.Text(if (prefixes) Prefix.query(it.q.trim()) else it.q.trim()))) },
                        keyword = queries.map { q ->
                            val hits = store.keywordSearch(q.q.trim(), SearchEngine.CANDIDATES)
                            val rows = store.chunks(hits)
                            hits.mapNotNull { rows[it]?.fileId }.distinct().map { indexOf.getValue(it) }
                        },
                    )
                }
                embedMs[vt] = median(imageMs) to median(textMs)
            }
        }

        val rawDims = groups.values.firstOrNull()?.queries?.firstOrNull()?.size ?: 0
        // The vector lane doesn't depend on the fusion settings, so each one is scored once per (group, dims, int8).
        val vectorLanes = HashMap<List<Any>, List<MeaningLane>>()
        val rows = settings.mapNotNull { s ->
            val group = groups[s.prefixes to s.visionTokens] ?: return@mapNotNull null
            if (s.dims > rawDims) {
                notes += "d${s.dims}: the model returns $rawDims dims"
                return@mapNotNull null
            }
            val lanes = vectorLanes.getOrPut(listOf(s.prefixes, s.visionTokens, s.dims, s.int8)) { vectorLanes(group, s) }
            val keyword = queries.indices.map { qi -> (group.keyword[qi] + names[qi][1]).toSet() }
            val ordered = queries.indices.map { qi ->
                val fused = listOf(lanes[qi].cos.keys.toList(), group.keyword[qi], names[qi][0]).map { lane -> lane.map { it.toLong() } }
                val w = s.tuning.keywordWeight
                val top = rrfMerge(fused, s.tuning.rrfK, listOf(1.0, w, w)).take(LIMIT).map { it.first.toInt() }
                // SearchEngine.toHits' split: words and close names are strong, meaning by the rule, a loose name alone never.
                val (strong, weak) = top.partition { it in keyword[qi] || it in lanes[qi].strong }
                (strong + weak) to strong.size
            }
            val top = ordered.map { it.first }
            EvalRow(
                s,
                top.mapIndexed { qi, files ->
                    targets[qi]?.let { files.indexOf(it) }?.takeIf { it >= 0 }?.plus(1)
                },
                top,
                ordered.map { it.second },
                lanes.map { it.cos },
                keyword,
            )
        }
        return EvalRun(corpus, queries, rows, embedMs, notes.distinct(), rule)
    }

    /**
     * Production's vector lane: the best [SearchEngine.CANDIDATES] chunks, then each file at its best chunk, as
     * corpus index to that chunk's cosine, best first. The default (d256 int8) scores as search does, with [dotInt8],
     * and every chunk's cosine feeds [rule]'s histograms, as the scan's rows do.
     */
    private fun vectorLanes(group: Group, s: EvalSetting): List<MeaningLane> {
        val quantized = s.int8 && s.dims == DIMS
        val sig = Signature("eval")
        val chunkVectors = if (quantized) group.chunks.map { Vector.of(it.raw, sig) } else emptyList()
        return group.queries.map { q ->
            val qv = if (quantized) Vector.of(q, sig) else null
            val scores = group.chunks.mapIndexed { i, c ->
                if (qv != null) dotInt8(chunkVectors[i].bytes, 0, qv.bytes, qv.scale, chunkVectors[i].scale) else cosine(q, c.raw, s.dims)
            }
            val minStrong = rule?.let { r ->
                val h = r.histogram()
                scores.forEachIndexed { i, cos -> h[group.chunks[i].modality * RelevanceRule.BINS + RelevanceRule.bin(cos)]++ }
                r.minStrong(h)
            }
            val lane = LinkedHashMap<Int, Float>()
            val strong = HashSet<Int>()
            val candidates = scores.indices.sortedByDescending { scores[it] }.take(SearchEngine.CANDIDATES)
            if (minStrong == null) {
                strong += candidates.map { group.chunks[it].file }.distinct().take(LIKELY_MEANING_FILES)
            }
            for (i in candidates) {
                val c = group.chunks[i]
                lane.getOrPut(c.file) { scores[i] }
                if (minStrong != null && scores[i] >= minStrong[c.modality]) strong += c.file
            }
            MeaningLane(lane, strong)
        }
    }

    companion object {
        /** Retriever.search's default limit: a file past it is not found. */
        const val LIMIT = 20
    }
}

private fun median(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

/**
 * The markdown report: the strong/weak split and its cosines, per-factor tables around [default], the best row of the
 * grid, per-query ranks and timings. [production] and [productionStrong] are the same queries through the real app, when
 * the platform ran it. Ranking metrics count only queries with a file; no-match queries feed the strong/weak section.
 */
fun EvalRun.report(default: EvalSetting, production: List<Int?>? = null, productionStrong: List<Int>? = null): String {
    val byKind = kinds.filterNotNull().distinct().sortedBy { it.ordinal }
    val matched = kinds.count { it != null }
    fun metrics(ranks: List<Int?>) = app.retriever.core.metrics(ranks.filterIndexed { i, _ -> kinds[i] != null })
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

    val header = "| Setting | All (n=$matched) | " + byKind.joinToString(" | ") { "$it (n=${kinds.count { k -> k == it }})" } + " | vs default |\n" +
        "|---|---|" + byKind.joinToString("") { "---|" } + "---|\n"
    fun line(label: String, ranks: List<Int?>, verdict: String) =
        "| $label | ${cell(metrics(ranks))} | " + byKind.joinToString(" | ") { cell(metrics(kindRanks(ranks, it))) } + " | $verdict |\n"
    fun table(rs: List<EvalRow>) = header + rs.joinToString("") { line(it.setting.label, it.ranks, verdict(it)) }

    val out = StringBuilder()
    out.appendLine("Cells: recall@1 · recall@5 (queries) · MRR. Ranks count within the top ${RetrievalEval.LIMIT}, as search returns them.")
    out.appendLine()
    out.appendLine("### Corpus")
    out.appendLine(
        corpus.groupBy { it.kind }.entries.joinToString(", ") { "${it.value.size} ${it.key}" } +
            " files; $matched queries with a file, ${queries.size - matched} with none.",
    )
    if (notes.isNotEmpty()) out.appendLine(notes.joinToString("\n") { "- $it" })
    out.appendLine()
    out.append(strengthSection(defaultRow, productionStrong))
    out.appendLine()
    out.appendLine("### Embed time (median ms per model call)")
    out.appendLine("| Vision tokens | Image piece | Text piece |\n|---|---|---|")
    embedMs.forEach { (vt, ms) -> out.appendLine("| $vt | ${ms.first.roundToInt()} | ${ms.second.roundToInt()} |") }
    out.appendLine()
    out.appendLine("### Default")
    out.append(header)
    out.append(line(default.label, defaultRow.ranks, "default"))
    if (production !=
        null
    ) {
        out.append(
            line(
                "production path (Retriever.search)",
                production,
                "agrees on ${production.indices.count {
                    kinds[it] != null && production[it] == defaultRow.ranks[it]
                }}/$matched ranks",
            ),
        )
    }
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
    out.append(
        table(
            rows.sortedWith(
                compareByDescending<EvalRow>({
                    metrics(it.ranks).at5
                }).thenByDescending { metrics(it.ranks).mrr }.thenByDescending { metrics(it.ranks).at1 },
            ).take(10),
        ),
    )
    out.appendLine()
    out.appendLine("### Per-query ranks (- means not in the top ${RetrievalEval.LIMIT})")
    val alone = rows.firstOrNull { it.setting == default.copy(tuning = default.tuning.copy(keywordWeight = 0.0)) }
    out.appendLine("| Query | File | Kind | Default | Vector alone | Best |" + (if (production != null) " Production |" else "") + " Default top 3 |")
    out.appendLine("|---|---|---|---|---|---|" + (if (production != null) "---|" else "") + "---|")
    queries.forEachIndexed { i, q ->
        if (q.file == null) return@forEachIndexed
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

/** Kind groups whose text-to-file cosines sit in different bands, for reading the cosine tables. */
private val kindGroups = listOf("text->photo", "text->screenshot", "text->text")

private fun groupOf(kind: Kind) = when (kind) {
    Kind.PHOTO, Kind.VIDEO -> "text->photo"
    Kind.SCREENSHOT -> "text->screenshot"
    else -> "text->text"
}

/**
 * Per query, from [row]'s vector lane: the right file's best-chunk cosine and, by group, the best other file's; for
 * a no-match query every cosine is a wrong one. Then the per-group gap and what [EvalRun.rule] makes of it.
 */
private fun EvalRun.strengthSection(row: EvalRow, productionStrong: List<Int>?): String {
    fun f3(x: Float?) = if (x == null || x.isNaN()) "-" else ((x * 1000).roundToInt() / 1000.0).toString()
    val target = queries.map { q -> q.file?.let { f -> corpus.indexOfFirst { it.name == f } } }

    /** The best cosine among files of [group] other than the query's own file. */
    fun wrong(qi: Int, group: String) = row.cos[qi].filterKeys { it != target[qi] && groupOf(corpus[it].kind) == group }.values.maxOrNull()
    val out = StringBuilder()
    out.appendLine("### Strong and weak (${row.setting.label}; int8 cosines, as search scores them)")
    out.appendLine(
        "A file found by words or a close name match is strong; a loose (substring or typo) name match alone is weak. " +
            if (rule == null) {
                "Absolute relevance is off; the largest score gap within each modality separates the collapsed tail."
            } else {
                "A file found by meaning is strong when a chunk clears its group's noise line by m = ${rule.margin} (groups under ${rule.minRows} rows: all strong)."
            },
    )
    out.appendLine()
    out.appendLine(
        "| Query | Group | Right | Right found by | " + kindGroups.joinToString(" | ") { "Best wrong $it" } + " | Strong | Right strong | Rank |" +
            (if (productionStrong != null) " Production strong |" else ""),
    )
    out.appendLine("|---|---|---|---|---|---|---|---|---|---|" + (if (productionStrong != null) "---|" else ""))
    queries.forEachIndexed { i, q ->
        val t = target[i]
        val rank = row.ranks[i]
        out.appendLine(
            "| ${q.q} | ${t?.let { groupOf(corpus[it].kind) } ?: "no match"} | ${f3(t?.let { row.cos[i][it] })} | " +
                "${if (t == null) {
                    "-"
                } else if (t in row.keyword[i]) {
                    "keyword"
                } else {
                    "meaning"
                }} | " +
                kindGroups.joinToString(" | ") { f3(wrong(i, it)) } + " | ${row.strong[i]} | " +
                "${if (t == null) {
                    "-"
                } else if (rank != null && rank <= row.strong[i]) {
                    "yes"
                } else {
                    "**no**"
                }} | ${rank ?: "-"} |" +
                (if (productionStrong != null) " ${productionStrong[i]} |" else ""),
        )
    }
    out.appendLine()
    out.appendLine("min(right): lowest right-file cosine among queries that found it by meaning only (a keyword match is strong anyway).")
    out.appendLine()
    out.appendLine("| Group | min(right) | max(best wrong), queries with a file | max(no-match) |")
    out.appendLine("|---|---|---|---|")
    for (group in kindGroups) {
        val right = queries.indices.mapNotNull { i ->
            target[i]?.takeIf { groupOf(corpus[it].kind) == group && it !in row.keyword[i] }?.let { row.cos[i][it] }
        }
        val wrongs = queries.indices.filter { target[it] != null }.mapNotNull { wrong(it, group) }
        val none = queries.indices.filter { target[it] == null }.mapNotNull { wrong(it, group) }
        out.appendLine("| $group | ${f3(right.minOrNull())} | ${f3(wrongs.maxOrNull())} | ${f3(none.maxOrNull())} |")
    }
    out.appendLine()
    val withFile = queries.indices.filter { target[it] != null }
    val noMatch = queries.indices.filter { target[it] == null }
    val strongRight = withFile.count { row.ranks[it]?.let { r -> r <= row.strong[it] } == true }
    val at5 = withFile.count { row.ranks[it]?.let { r -> r <= 5 } == true }
    val at5Strong = withFile.count { row.ranks[it]?.let { r -> r <= 5 && r <= row.strong[it] } == true }
    out.appendLine(
        "- Right file strong: $strongRight/${withFile.size}. Recall@5 over strong hits: $at5Strong/${withFile.size} (over all hits: $at5/${withFile.size}).",
    )
    out.appendLine(
        "- No-match queries with no strong hit: ${noMatch.count {
            row.strong[it] == 0
        }}/${noMatch.size}; strong hits on them: ${noMatch.sumOf { row.strong[it] }}.",
    )
    out.appendLine("- Strong hits per query with a file: ${withFile.sumOf { row.strong[it] }} over ${withFile.size} queries.")
    return out.toString()
}
