package app.retriever

import android.os.Build
import android.os.Environment
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.retriever.core.Backend
import app.retriever.core.EvalSetting
import app.retriever.core.Kind
import app.retriever.core.RelevanceFloor
import app.retriever.core.RetrievalEval
import app.retriever.core.Signature
import app.retriever.core.Store
import app.retriever.core.android.MediaPipeEmbedderFactory
import app.retriever.core.db.IndexDb
import app.retriever.core.evalGrid
import app.retriever.core.parseEvalQueries
import app.retriever.core.report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The 13-query eval (a target file in the top 5) plus per-kind median extract times, over the app's own
 * Retriever and database. Embed time per kind needs a per-file hook the facade doesn't expose.
 */
class EvalRunner(private val graph: AppGraph) {
    suspend fun run(): String = withContext(Dispatchers.Default) {
        val retriever = graph.retriever
        val out = StringBuilder()
        var found = 0
        val queryMs = mutableListOf<Double>()
        for ((q, target) in QUERIES) {
            val mark = TimeSource.Monotonic.markNow()
            val result = retriever.search(q)
            queryMs += mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            val top = result.hits.take(5)
            val hit = top.any { it.file.name.contains(target, ignoreCase = true) }
            if (hit) found++
            out.append(if (hit) "hit  " else "miss ").append(q).append(" -> ").append(top.joinToString(" | ") { it.file.name })
            out.appendLine(if (result.vectorLane) "" else " (words only)")
        }
        out.appendLine("found $found/${QUERIES.size} in the top 5 · query ${latency(queryMs)}")

        val profile = CalibrationSamples.readingProfile(retriever.requiredBuild.value.variant)
        out.appendLine("median extract ms, newest $PER_KIND files per kind:")
        for (kind in Kind.entries) {
            val ms = retriever.files(kind, null, Store.Sort.NEWEST, PER_KIND, 0).map { stored ->
                val mark = TimeSource.Monotonic.markNow()
                runCatching { graph.extractor.extract(stored.file, profile) }
                mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            }
            if (ms.isNotEmpty()) out.appendLine("$kind n=${ms.size} extract=${median(ms).f1()}")
        }
        out.toString().also { File(graph.app.getExternalFilesDir("eval"), "eval.txt").writeText(it) }
    }

    /**
     * Corpus mode: core's RetrievalEval, the iOS simulator eval's grid, over the eval corpus and queries.json pushed
     * to Download/retriever-eval. The app must have synced since the push, so the files are found in the store by folder.
     * Writes eval-corpus.txt next to eval.txt.
     */
    suspend fun runCorpus(): String = withContext(Dispatchers.Default) {
        val retriever = graph.retriever
        val dir = File(Environment.getExternalStorageDirectory(), "Download/$CORPUS_DIR")
        val queries = parseEvalQueries(File(dir, "queries.json").readText())
        val corpus = retriever.folders().map { it.first }.filter { CORPUS_DIR in it.split('/') }
            .flatMap { retriever.files(null, it, Store.Sort.NAME, 10_000, 0) }
            .map { it.file }
            .filter { it.name != "queries.json" && it.name != "report.md" }
            .sortedBy { it.uri }
        // The production path first, as a person meets it: the app's index, ranks, strong counts and latency.
        val queryMs = mutableListOf<Double>()
        val results = queries.map { q ->
            val mark = TimeSource.Monotonic.markNow()
            retriever.search(q.q).also { queryMs += mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS) }
        }
        val production = queries.zip(results) { q, r ->
            q.file?.let { f -> r.hits.indexOfFirst { it.file.folder.split('/').contains(CORPUS_DIR) && it.file.name == f }.takeIf { it >= 0 }?.plus(1) }
        }
        // One copy of the model in memory: the eval opens its own per vision-token count.
        retriever.release()
        val build = retriever.requiredBuild.value
        // The facade doesn't expose the stored profile; this phone's tier may use 140 vision tokens, and the grid has both.
        val profile = CalibrationSamples.readingProfile(build.variant).copy(backend = retriever.state.value.backend ?: Backend.CPU, videoFrameEverySec = 0)
        val run = RetrievalEval(
            graph.extractor,
            MediaPipeEmbedderFactory(graph.app, graph.modelDir, retriever.probe),
            build,
            profile,
            Store(AndroidSqliteDriver(IndexDb.Schema, graph.app, null)),
            floor = RelevanceFloor.measuredFor(Signature.of(build, profile.visionTokens, profile.maxTokens)),
        ).run(corpus, queries, evalGrid(listOf(70, 140)))
        val report = "## ${Build.MODEL}: ${build.fileName}, ${profile.backend}\n\n" +
            "Production search (Retriever.search, ${queries.size} queries): ${latency(queryMs)}" +
            (if (results.all { it.vectorLane }) "; every search used the vector lane.\n\n" else "; some searches were keyword-only.\n\n") +
            run.report(EvalSetting(profile.visionTokens), production, results.map { it.strongCount })
        report.also { File(graph.app.getExternalFilesDir("eval"), "eval-corpus.txt").writeText(it) }
    }

    private fun median(xs: List<Double>): Double =
        if (xs.isEmpty()) 0.0 else xs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    /** p50, p95 (nearest rank) and max, in ms. */
    private fun latency(ms: List<Double>): String {
        if (ms.isEmpty()) return "no queries"
        val s = ms.sorted()
        return "p50 ${median(s).f1()} ms, p95 ${s[(s.size * 95 + 99) / 100 - 1].f1()} ms, max ${s.last().f1()} ms"
    }

    private fun Double.f1() = "%.1f".format(Locale.US, this)

    private companion object {
        const val PER_KIND = 10
        const val CORPUS_DIR = "retriever-eval"

        /** Query to a substring of the file it should find, in the test phone's library; edit to match yours. */
        val QUERIES = listOf(
            "my resume" to "Resume",
            "bank statement" to "Statement",
            "credit score report" to "Credit Report",
            "driving licence" to "Licence",
            "train ticket" to "Ticket",
            "car insurance policy" to "Insurance",
            "tax payment receipt" to "Receipt",
            "home loan papers" to "Loan",
            "account balance certificate" to "Certificate",
            "mutual fund portfolio report" to "Portfolio",
            "pitch deck" to "pitch deck",
            "invoice" to "invoice",
        )
    }
}
