package app.retriever

import android.content.Context
import android.os.Build
import android.util.Log
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.retriever.core.Backend
import app.retriever.core.DIMS
import app.retriever.core.Embedder
import app.retriever.core.EvalSetting
import app.retriever.core.Extractor
import app.retriever.core.Kind
import app.retriever.core.Part
import app.retriever.core.RetrievalEval
import app.retriever.core.Retriever
import app.retriever.core.SearchEngine
import app.retriever.core.Signature
import app.retriever.core.Store
import app.retriever.core.Vector
import app.retriever.core.VectorTable
import app.retriever.core.android.MediaPipeEmbedderFactory
import app.retriever.core.db.IndexDb
import app.retriever.core.evalGrid
import app.retriever.core.parseEvalQueries
import app.retriever.core.report
import app.retriever.core.traced
import app.retriever.di.AppScope
import app.retriever.di.ModelDir
import app.retriever.model.CalibrationSamples
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * The 13-query eval (a target file in the top 5) plus per-kind median extract times, over the app's own
 * Retriever and database. Embed time per kind needs a per-file hook the facade doesn't expose.
 */
class EvalRunner : KoinComponent {
    private val app: Context by inject()
    private val retriever: Retriever by inject()
    private val extractor: Extractor by inject()
    private val modelDir: File by inject(ModelDir)
    private val scope: CoroutineScope by inject(AppScope)

    suspend fun run(): String = withContext(Dispatchers.Default) {
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
                runCatching { extractor.extract(stored.file, profile) }
                mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            }
            if (ms.isNotEmpty()) out.appendLine("$kind n=${ms.size} extract=${median(ms).f1()}")
        }
        out.toString().also { File(app.getExternalFilesDir("eval"), "eval.txt").writeText(it) }
    }

    /**
     * Corpus mode: core's RetrievalEval, the iOS simulator eval's grid, over the eval corpus and queries.json pushed
     * to the app's files/eval folder (see runNamed). The app must have synced since the push, so the files are found in the store by folder.
     * Writes eval-corpus.txt next to eval.txt.
     */
    suspend fun runCorpus(): String = withContext(Dispatchers.Default) {
        val queries = parseEvalQueries(File(evalDir(), "queries.json").readText())
        val corpus = retriever.folders().map { it.first }.filter { CORPUS_DIR in it.split('/') }
            .flatMap { retriever.files(null, it, Store.Sort.NAME, 10_000, 0) }
            .map { it.file }
            .sortedBy { it.uri }
        // The production path first, as a person meets it: the app's index, ranks, strong counts and latency.
        val queryMs = mutableListOf<Double>()
        val results = queries.map { q ->
            val mark = TimeSource.Monotonic.markNow()
            retriever.search(q.q).also { queryMs += mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS) }
        }
        // Only corpus files take a rank or count as strong; anything else the library holds is dropped before ranking.
        val uris = corpus.mapTo(HashSet()) { it.uri }
        val ranked = results.map { r -> r.hits.filter { it.file.uri in uris } }
        val production = queries.zip(ranked) { q, hits ->
            q.file?.let { f -> hits.indexOfFirst { it.file.name == f }.takeIf { it >= 0 }?.plus(1) }
        }
        val strong = results.map { r -> r.hits.take(r.strongCount).count { it.file.uri in uris } }
        // One copy of the model in memory: the eval opens its own per vision-token count.
        retriever.release()
        val build = retriever.requiredBuild.value
        // The facade doesn't expose the stored profile; this phone's tier may use 140 vision tokens, and the grid has both.
        val profile = CalibrationSamples.readingProfile(build.variant).copy(backend = retriever.state.value.backend ?: Backend.CPU, videoFrameEverySec = 0)
        val run = RetrievalEval(
            extractor,
            MediaPipeEmbedderFactory(app, modelDir),
            build,
            profile,
            Store(AndroidSqliteDriver(IndexDb.Schema, app, null)),
        ).run(corpus, queries, evalGrid(listOf(70, 140)))
        val report = "## ${Build.MODEL}: ${build.fileName}, ${profile.backend}\n\n" +
            "Production search (Retriever.search, ${queries.size} queries): ${latency(queryMs)}" +
            (if (results.all { it.vectorLane }) "; every search used the vector lane.\n\n" else "; some searches were keyword-only.\n\n") +
            run.report(EvalSetting(profile.visionTokens), production, strong)
        report.also { File(app.getExternalFilesDir("eval"), "eval-corpus.txt").writeText(it) }
    }

    /**
     * Entry for `adb shell am start -n app.retriever/.DebugActivity --es run corpus|scan|latency|query|stats [--es q text]`.
     * The report goes to logcat (tag RetrieverBench, ending in "done <run>") and files/eval/report-<run>.txt, next to the pushed queries.json (adb push it to /sdcard/Android/data/app.retriever/files/eval/).
     */
    suspend fun runNamed(name: String, q: String?): String {
        val text = try {
            when (name) {
                "corpus" -> runCorpus()

                "scan" -> scan()

                "latency" -> searchLatency()

                "query" -> query(q ?: error("query needs --es q <text>"))

                "stats" -> stats()

                "vision" -> {
                    DebugVision.set(app, q ?: error("vision needs --es q cpu|gpu|policy"))
                    "Inference override: $q (text and vision; applies at the next batch boundary)"
                }

                else -> "Unknown run '$name'. Use corpus, scan, latency, query or stats."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Run $name failed: ${e.stackTraceToString()}"
        }
        text.lines().forEach { Log.i(TAG, it) }
        Log.i(TAG, "done $name")
        runCatching {
            File(evalDir(), "report-$name.txt").writeText(text)
        }.onFailure { Log.w(TAG, "report-$name.txt not written", it) }
        return text
    }

    /** The vector scan alone on this CPU: [ROWS] random int8 rows through the engine's own scanTop, one thread. */
    private suspend fun scan(): String = withContext(Dispatchers.Default) {
        val rnd = Random(42)
        val max = ROWS.max()
        val sig = Signature("bench")
        val full = VectorTable(
            LongArray(max) { it + 1L },
            LongArray(max) { it.toLong() },
            ByteArray(max * DIMS).also(rnd::nextBytes),
            FloatArray(max) {
                1f /
                    127
            },
        )
        val q = Vector(ByteArray(DIMS).also(rnd::nextBytes), 1f / 127, sig)
        val engine = SearchEngine(
            Store(AndroidSqliteDriver(IndexDb.Schema, app, null)),
            object : Embedder {
                override val signature = sig
                override val backend = Backend.CPU

                override suspend fun embedRaw(parts: List<Part>): FloatArray = error("scan only")

                override fun close() {}
            },
        )
        val out = StringBuilder("Vector scan, ${Build.MODEL}, 1 thread, top 100, $RUNS runs after $WARMUP warm-ups\n")
        for (n in ROWS) {
            val table = full.view(n)
            repeat(WARMUP) { engine.scanTop(q, 100, table) }
            val ms = List(RUNS) {
                val mark = TimeSource.Monotonic.markNow()
                traced("vec.scan") { engine.scanTop(q, 100, table) }
                mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            }
            out.appendLine("$n rows: ${latency(ms)}")
        }
        out.append(
            """
            |
            |Is dotInt8 vectorised (sdot or smull on arm64)? On a userdebug image or rooted device:
            |  adb root; adb shell cmd package compile -m speed -f app.retriever
            |  adb shell 'oatdump --oat-file=${'$'}(find /data/app -name base.odex -path "*app.retriever*") --method-filter=dotInt8' | grep -c -E 'sdot|smull'
            |dotInt8 may be inlined into scanTop, so repeat with --method-filter=scanTop. Zero hits: the loop is scalar.
            """.trimMargin(),
        )
        out.toString()
    }

    /**
     * Retriever.search over the eval queries: model closed and cold (released before each query, so the time includes
     * opening it), closed then warmSearch (as a focused search bar does), the names lane, open and idle, open with a
     * foreground batch running, and the focus-busy window with the batch standing by (push extra photos first).
     */
    private suspend fun searchLatency(): String = withContext(Dispatchers.Default) {
        val queries = parseEvalQueries(File(evalDir(), "queries.json").readText()).map { it.q }
        var keywordOnly = 0
        suspend fun timed(q: String): Double {
            val mark = TimeSource.Monotonic.markNow()
            if (!retriever.search(q).vectorLane) keywordOnly++
            return mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        }
        suspend fun timedNames(q: String): Double {
            val mark = TimeSource.Monotonic.markNow()
            retriever.searchNames(q)
            return mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        }
        val out = StringBuilder("Search latency, ${Build.MODEL}, ${queries.size} queries, ${retriever.requiredBuild.value.fileName}\n")
        fun line(label: String, ms: List<Double>) = out.appendLine("$label (n=${ms.size}): ${latency(ms)}; keyword-only searches so far: $keywordOnly")

        // Paused, newly pushed files stay pending and no background batch disturbs the first two states.
        retriever.pause()
        try {
            val sample = queries.filterIndexed { i, _ -> i % 4 == 0 }

            Log.i(TAG, "latency: model closed, cold")
            retriever.warmSearch()
            line(
                "model closed, opened by each search (no warm-up)",
                sample.map {
                    retriever.release()
                    timed(it)
                },
            )

            // What a person meets: the search bar takes focus, warmSearch runs, then they type.
            Log.i(TAG, "latency: model closed, then warmSearch")
            val warmMs = mutableListOf<Double>()
            val afterWarm = sample.map {
                retriever.release()
                val mark = TimeSource.Monotonic.markNow()
                retriever.warmSearch()
                warmMs += mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
                timed(it)
            }
            line("model closed, after warmSearch", afterWarm)
            line("  warmSearch itself", warmMs)

            Log.i(TAG, "latency: names lane")
            retriever.warmSearch()
            line("names lane (searchNames, no model)", List(3) { queries }.flatten().map { timedNames(it) })

            Log.i(TAG, "latency: model open, idle")
            timed(queries.first())
            line("model open, idle", List(3) { queries }.flatten().map { timed(it) })
        } finally {
            retriever.resume()
        }

        Log.i(TAG, "latency: foreground batch")
        val batch = scope.launch { retriever.indexBatch(10.minutes, deepen = false) }
        val started = withTimeoutOrNull(60_000) { retriever.state.first { it.running && it.pendingAtStart > 0 } }
        if (started == null) {
            out.appendLine("foreground batch: none ran (nothing pending); push extra files to /sdcard/DCIM and rescan")
        } else {
            // Searches pause indexing, so back-to-back queries would starve it; space them like a person typing.
            withTimeoutOrNull(180_000) { retriever.state.first { it.processed > 0 || !it.running } }
            val processedBefore = retriever.state.value.processed
            val ms = mutableListOf<Double>()
            for (q in List(3) { queries }.flatten()) {
                if (!retriever.state.value.running) break
                delay(PAUSE_BETWEEN_QUERIES_MS)
                ms += timed(q)
            }
            line("foreground batch running (${started.pendingAtStart} pending)", ms)
            out.appendLine("  the batch finished ${retriever.state.value.processed - processedBefore} files meanwhile")

            // Focus-busy window: the search UI holds uiBusy while the person types, so the batch stands by. Names and full search, back to back.
            if (retriever.state.value.running) {
                Log.i(TAG, "latency: focus-busy window")
                retriever.setUiBusy(true)
                try {
                    line("busy window, names lane", List(3) { queries }.flatten().map { timedNames(it) })
                    line("busy window, full search", List(3) { queries }.flatten().map { timed(it) })
                } finally {
                    retriever.setUiBusy(false)
                }
            } else {
                out.appendLine("busy window: the batch ended before it; push more files to /sdcard/DCIM and rescan")
            }
        }
        retriever.cancelBatch()
        batch.join()
        out.toString()
    }

    /** One search, three times: hits (strong ones first), strong flags and timings. A closed model makes the first one slow. */
    private suspend fun query(q: String): String = withContext(Dispatchers.Default) {
        val out = StringBuilder("Query \"$q\"\n")
        val times = mutableListOf<Double>()
        lateinit var result: SearchEngine.SearchResult
        repeat(3) {
            val mark = TimeSource.Monotonic.markNow()
            result = retriever.search(q)
            times += mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        }
        out.appendLine(
            "${result.hits.size} hits, ${result.strongCount} strong, vector lane ${result.vectorLane}; search ms, 1st to 3rd: ${times.joinToString {
                it.f1()
            }}",
        )
        result.hits.forEachIndexed { i, h ->
            out.appendLine(
                "${i + 1} ${if (i < result.strongCount) "strong" else "weak  "} ${"%.4f".format(
                    Locale.US,
                    h.score,
                )} ${h.file.kind} ${h.file.name} ${h.locator}${if (h.byName) " [name]" else ""}",
            )
        }
        out.toString()
    }

    /** Counts by kind and state, and how many have a taken_at, straight from the index file. */
    private suspend fun stats(): String = withContext(Dispatchers.Default) {
        val driver = AndroidSqliteDriver(IndexDb.Schema, app, Store.DB_NAME)
        try {
            fun rows(sql: String, cols: Int) = driver.executeQuery(
                null,
                sql,
                { c ->
                    val r = mutableListOf<List<String?>>()
                    while (c.next().value) r += List(cols) { c.getString(it) }
                    QueryResult.Value(r)
                },
                0,
            ).value
            val out = StringBuilder("Index stats, ${Build.MODEL}\n")
            val state = retriever.state.value
            out.appendLine(
                "runtime: ${state.backend}, ${state.detail ?: "all encoders on selected backend"}; inference override: ${DebugVision.backend(app) ?: "policy"}",
            )
            out.appendLine("kind state files with_taken_at")
            rows("SELECT kind, state, COUNT(*), COUNT(taken_at) FROM files GROUP BY kind, state ORDER BY kind, state", 4).forEach {
                out.appendLine(it.joinToString(" "))
            }
            val (files, taken, chunks) = rows(
                "SELECT (SELECT COUNT(*) FROM files), (SELECT COUNT(taken_at) FROM files), (SELECT COUNT(*) FROM chunks)",
                3,
            ).single()
            out.appendLine("total: $files files, $taken with taken_at, $chunks chunks")
            rows(
                "SELECT key, CASE WHEN key = 'profile' THEN value ELSE substr(value, 1, 200) END FROM index_meta WHERE key IN ('prefs', 'model_signature', 'profile')",
                2,
            ).forEach {
                out.appendLine("${it[0]}: ${it[1]}")
            }
            out.toString()
        } finally {
            driver.close()
        }
    }

    private fun median(xs: List<Double>): Double = if (xs.isEmpty()) 0.0 else xs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    /** p50, p95 (nearest rank) and max, in ms. */
    private fun latency(ms: List<Double>): String {
        if (ms.isEmpty()) return "no queries"
        val s = ms.sorted()
        return "p50 ${median(s).f1()} ms, p95 ${s[(s.size * 95 + 99) / 100 - 1].f1()} ms, max ${s.last().f1()} ms"
    }

    private fun Double.f1() = "%.1f".format(Locale.US, this)

    /** App-private, so MediaStore never indexes the manifest or the reports. */
    private fun evalDir() = checkNotNull(app.getExternalFilesDir("eval")) { "external files dir unavailable" }

    private companion object {
        const val PER_KIND = 10
        const val CORPUS_DIR = "retriever-eval"
        const val TAG = "RetrieverBench"
        val ROWS = listOf(150_000, 300_000)
        const val WARMUP = 8
        const val RUNS = 40
        const val PAUSE_BETWEEN_QUERIES_MS = 400L

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
