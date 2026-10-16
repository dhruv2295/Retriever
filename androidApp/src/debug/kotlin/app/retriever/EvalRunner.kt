package app.retriever

import app.retriever.core.Kind
import app.retriever.core.Store
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
        out.appendLine("found $found/${QUERIES.size} in the top 5 · median query ${median(queryMs).f1()} ms")

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

    private fun median(xs: List<Double>): Double =
        if (xs.isEmpty()) 0.0 else xs.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

    private fun Double.f1() = "%.1f".format(Locale.US, this)

    private companion object {
        const val PER_KIND = 10

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
