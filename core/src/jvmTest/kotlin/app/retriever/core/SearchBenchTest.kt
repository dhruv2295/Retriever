package app.retriever.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Search speed on a phone-sized index: 50,000 files, 150,000 chunks of 60-120 words drawn from a Zipf vocabulary
 * (stop words on top, 20,000 made-up words below, some Hindi), random unit int8 vectors. Times the keyword lane, the
 * vector scan and the whole fused search over 40 queries and prints p50/p95 per lane.
 */
class SearchBenchTest {
    private class Query(val text: String, val kinds: Set<Kind>? = null)

    private class FixedEmbedder(private val v: FloatArray) : Embedder {
        override val signature = SIG
        override val backend = Backend.CPU

        override suspend fun embedRaw(parts: List<Part>) = v

        override fun close() {}
    }

    @Test
    fun searchStaysFastAtFiftyThousandFiles(): Unit = runBlocking {
        val rnd = Random(42)
        // A file, not memory, as on a phone: SQLite's 2 MB page cache in front of the OS's.
        val db = File.createTempFile("search-bench", ".db").apply { deleteOnExit() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${db.path}").also { IndexDb.Schema.create(it) }
        val store = Store(driver).also { it.resetForModel(SIG) }
        val vocab = vocabulary(rnd)
        val buildMs = timed { build(store, rnd, vocab, Zipf(vocab.size)) }
        check(store.fileCount() == FILES.toLong() && store.chunkCount() == CHUNKS.toLong())

        val queries = queries(vocab)
        val qVec = FloatArray(DIMS) { rnd.nextGaussian().toFloat() }
        val engine = SearchEngine(store, FixedEmbedder(qVec))
        val q = Vector.of(qVec, SIG)
        val table = store.loadVectors(into = VectorTable.withCapacity(store.vectorCount()))
        fun allowed(kinds: Set<Kind>?) = kinds?.let { k -> BooleanArray(Kind.entries.size) { Kind.entries[it] in k } }

        val lanes = linkedMapOf<String, suspend (Query) -> Unit>(
            "keyword" to { query ->
                store.keywordSearch(query.text, SearchEngine.CANDIDATES, query.kinds)
                store.fileKeywordSearch(query.text, SearchEngine.CANDIDATES, query.kinds)
            },
            "vector" to { query -> engine.scanTop(q, SearchEngine.CANDIDATES, table, allowed(query.kinds)) },
            "fused" to { query -> engine.search(query.text, kinds = query.kinds) },
        )
        println("SearchBench: $FILES files, $CHUNKS chunks, built in ${buildMs.toInt()} ms")
        for ((lane, run) in lanes) {
            queries.forEach { run(it) }
            val perQuery = queries.associateWith { query -> List(ROUNDS) { timed { run(query) } }.sorted()[ROUNDS / 2] }
            val samples = perQuery.values.sorted()
            val p50 = samples[samples.size / 2]
            val p95 = samples[(samples.size * 95 + 99) / 100 - 1]
            val worst = perQuery.entries.maxBy { it.value }.key
            println("SearchBench $lane: p50 %.1f ms, p95 %.1f ms, slowest \"%s\" %s".format(p50, p95, worst.text, worst.kinds ?: ""))
            assertTrue(p95 <= LIMIT_MS.getValue(lane), "$lane p95 $p95 ms is over its ${LIMIT_MS.getValue(lane)} ms limit")
        }
        driver.close()
        db.delete()
    }

    private inline fun timed(body: () -> Unit): Double {
        val t = System.nanoTime()
        body()
        return (System.nanoTime() - t) / 1e6
    }

    /** Ranks 0-7 are stop words; the rest are unique made-up words of 3-10 letters. */
    private fun vocabulary(rnd: Random): List<String> {
        val words = LinkedHashSet(listOf("the", "and", "of", "to", "in", "is", "for", "on"))
        while (words.size < VOCAB) words += String(CharArray(3 + rnd.nextInt(8)) { 'a' + rnd.nextInt(26) })
        return words.toList()
    }

    private class Zipf(n: Int) {
        private val cum = DoubleArray(n).also { c ->
            var s = 0.0
            for (i in 0 until n) {
                s += 1.0 / Math.pow(i + 1.0, 1.05)
                c[i] = s
            }
        }

        fun next(rnd: Random): Int {
            val i = cum.binarySearch(rnd.nextDouble() * cum.last())
            return if (i >= 0) i else -i - 1
        }
    }

    private fun build(store: Store, rnd: Random, vocab: List<String>, zipf: Zipf) = store.inTransaction {
        // Kind shares of a typical phone; documents carry most of the chunks.
        val mix = listOf(
            Triple(Kind.PHOTO, 27_500, 1), Triple(Kind.SCREENSHOT, 7_500, 1), Triple(Kind.VIDEO, 2_500, 4),
            Triple(Kind.AUDIO, 1_000, 3), Triple(Kind.OTHER, 1_500, 3), Triple(Kind.DOC, 6_000, 8), Triple(Kind.PDF, 4_000, 12),
        )
        var spare = CHUNKS - mix.sumOf { it.second * it.third }
        val plan = mix.flatMap { (kind, n, chunks) ->
            List(n) { kind to chunks + if (kind == Kind.PDF && spare > 0) 1.also { spare-- } else 0 }
        }.shuffled(rnd)
        check(plan.size == FILES && plan.sumOf { it.second } == CHUNKS)
        val files = plan.mapIndexed { i, (kind, _) ->
            val name = when (kind) {
                Kind.PHOTO -> "IMG_${20_200_000 + i}.jpg"
                Kind.SCREENSHOT -> "Screenshot_${20_200_000 + i}.png"
                Kind.VIDEO -> "VID_${20_200_000 + i}.mp4"
                else -> "${vocab[8 + zipf.next(rnd) % (VOCAB - 8)]} ${vocab[8 + zipf.next(rnd) % (VOCAB - 8)]} $i.${kind.name.lowercase()}"
            }
            SourceFile("content://bench/$i", name, FOLDERS[rnd.nextInt(FOLDERS.size)], "application/octet-stream", kind, 1000L + i, i.toLong())
        }
        store.upsertFiles(files)
        val text = StringBuilder()
        files.forEachIndexed { i, f ->
            val id = checkNotNull(store.fileByUri(f.uri)).id
            val chunks = List(plan[i].second) { ordinal ->
                text.setLength(0)
                val hindi = rnd.nextInt(30) == 0
                repeat(60 + rnd.nextInt(61)) {
                    if (it > 0) text.append(' ')
                    text.append(if (hindi && it % 2 == 0) HINDI[rnd.nextInt(HINDI.size)] else vocab[zipf.next(rnd)])
                }
                val raw = FloatArray(DIMS) { rnd.nextGaussian().toFloat() }
                Store.NewChunk(ordinal, if (f.kind == Kind.PDF) "p. ${ordinal + 1}" else "", text.toString(), Vector.of(raw, SIG))
            }
            store.replaceChunks(id, chunks)
        }
    }

    /** 40 queries: rare words, common words, phrases, kind filters and one Hindi. */
    private fun queries(vocab: List<String>): List<Query> =
        listOf(15_000, 12_000, 9_000, 7_000, 5_000, 3_000, 2_000, 1_000, 19_000, 17_500).map { Query(vocab[it]) } +
            // Words in thousands of file names and folders.
            listOf(Query("screenshot"), Query("img")) +
            listOf("the", "and", "of", vocab[8], vocab[9], vocab[12], vocab[20], vocab[50], vocab[100], vocab[300]).map { Query(it) } +
            listOf(
                "the ${vocab[8]}", "${vocab[9]} ${vocab[10]}", "${vocab[30]} ${vocab[5000]}", "${vocab[200]} ${vocab[400]}",
                "${vocab[1000]} ${vocab[2000]}", "${vocab[8]} ${vocab[9]} ${vocab[10]}", "${vocab[40]} ${vocab[60]} ${vocab[80]}",
                "of the ${vocab[700]}", "${vocab[15]} ${vocab[15_000]}", "${vocab[3]} ${vocab[4]} ${vocab[5]}",
                vocab[8].take(3), vocab[600].take(4),
            ).map { Query(it) } +
            listOf(
                Query(vocab[8], setOf(Kind.PDF)), Query(vocab[150], setOf(Kind.DOC, Kind.PDF)),
                Query(vocab[6000], setOf(Kind.PHOTO)), Query("the", setOf(Kind.SCREENSHOT)),
                Query("${vocab[20]} ${vocab[90]}", setOf(Kind.DOC)),
            ) +
            Query("बिजली बिल")

    private companion object {
        const val FILES = 50_000
        const val CHUNKS = 150_000
        const val VOCAB = 20_000
        const val ROUNDS = 3

        /**
         * p95 limits: about 3x the worst p95 of three runs, never under a floor, so a loaded CI machine does not flap.
         * Measured 2026-10-17 on an M4 Max shared with other builds (load average 130-350), p95 of three runs:
         * keyword 45.9 / 34.7 / 36.0 ms (3x = 138, limit 150); vector 9.8 / 9.3 / 8.6 ms (3x = 29, floor 50);
         * fused 50.7 / 48.2 / 136.6 ms, the last a load spike, 61.2 on a fourth run (3x = 184, limit 200).
         * Before the wave 4 keyword cap the same bench measured keyword p95 296-338 ms and fused 287-393 ms.
         */
        val LIMIT_MS = mapOf("keyword" to 150.0, "vector" to 50.0, "fused" to 200.0)

        val FOLDERS = listOf("DCIM/Camera", "Pictures/Screenshots", "Download", "Documents", "Documents/Work", "WhatsApp/Media", "Movies", "Music")
        val HINDI = listOf(
            "बिजली", "बिल", "किराया", "पानी", "घर", "महीना", "भुगतान", "रसीद", "दवा", "स्कूल", "फीस", "बैंक", "खाता", "नाम",
            "पता", "तारीख", "राशि", "कुल", "जमा", "समझौता", "मकान", "मालिक", "किरायेदार", "सुरक्षा", "राशन", "कार्ड",
        )
    }
}
