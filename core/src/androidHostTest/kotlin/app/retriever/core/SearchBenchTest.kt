package app.retriever.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.Dispatchers
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
        lateinit var entries: List<NameEntry>
        val buildMs = timed { entries = build(store, rnd, vocab, Zipf(vocab.size)) }
        check(store.fileCount() == FILES.toLong() && store.chunkCount() == CHUNKS.toLong())
        // Schema 3's upgrade only recreates the keyword tables, so the database open stays quick at phone scale; the
        // refill runs once, later, from warmSearch.
        val upgrade = File(SchemaV3Test.SQL_DIR, "2.sqm").readText().substringAfter("DROP TABLE chunk_fts").let { "DROP TABLE chunk_fts$it" }
        val upgradeMs = timed { upgrade.split(';').filter { it.isNotBlank() }.forEach { driver.execute(null, it, 0) } }
        val rebuildMs = timed { check(store.rebuildFtsIfPending()) }
        println("SearchBench: keyword tables recreated in ${upgradeMs.toInt()} ms, refilled in ${rebuildMs.toInt()} ms")
        assertTrue(upgradeMs < 500, "the upgrade's keyword-table step took $upgradeMs ms")
        assertTrue(rebuildMs < 6_000, "the keyword refill took $rebuildMs ms")

        val queries = queries(vocab)
        val qVec = FloatArray(DIMS) { rnd.nextGaussian().toFloat() }
        val names = NameIndex.build(entries)
        val engine = SearchEngine(store, FixedEmbedder(qVec)) { names }
        val q = Vector.of(qVec, SIG)
        val table = store.loadVectors(into = VectorTable.withCapacity(store.vectorCount()))
        fun allowed(kinds: Set<Kind>?) = kinds?.let { k -> BooleanArray(Kind.entries.size) { Kind.entries[it] in k } }
        val rule = RelevanceRule()

        // The scan variants run interleaved, query by query, so machine load hits each alike.
        val scans = linkedMapOf<String, (Query) -> Unit>(
            "vector-wave4" to { query -> wave4ScanTop(q, SearchEngine.CANDIDATES, table, allowed(query.kinds)) },
            "vector" to { query -> engine.scanTop(q, SearchEngine.CANDIDATES, table, allowed(query.kinds)) },
            "vector+rule" to { query ->
                val h = rule.histogram()
                engine.scan(q, SearchEngine.CANDIDATES, table, allowed(query.kinds), histogram = h)
                rule.minStrong(h)
            },
        )
        repeat(3) { queries.forEach { query -> scans.values.forEach { it(query) } } }
        val scanMs = scans.keys.associateWith { mutableMapOf<Query, MutableList<Double>>() }
        repeat(SCAN_ROUNDS) { queries.forEach { query -> scans.forEach { (name, run) -> scanMs.getValue(name).getOrPut(query) { mutableListOf() } += timed { run(query) } } } }
        val scanP95 = scanMs.mapValues { (name, ms) -> report(name, ms.mapValues { it.value.sorted()[SCAN_ROUNDS / 2] }) }
        val base = scanP95.getValue("vector-wave4")
        println("SearchBench: vector %+.1f%%, vector+rule %+.1f%% against the wave-4 scan".format(
            (scanP95.getValue("vector") / base - 1) * 100, (scanP95.getValue("vector+rule") / base - 1) * 100))
        assertTrue(scanP95.getValue("vector") <= base * 1.05, "vector p95 ${scanP95.getValue("vector")} ms vs wave 4's $base ms")
        assertTrue(scanP95.getValue("vector") <= LIMIT_MS.getValue("vector"))

        val lanes = linkedMapOf<String, suspend (Query) -> Unit>(
            "keyword" to { query ->
                store.keywordSearch(query.text, SearchEngine.CANDIDATES, query.kinds)
                store.fileKeywordSearch(query.text, SearchEngine.CANDIDATES, query.kinds)
            },
            "names" to { query -> engine.names(SearchRequest(query.text, query.kinds)) },
            "fused" to { query -> engine.search(query.text, kinds = query.kinds) },
        )
        println("SearchBench: $FILES files, $CHUNKS chunks, built in ${buildMs.toInt()} ms")
        for ((lane, run) in lanes) {
            queries.forEach { run(it) }
            val p95 = report(lane, queries.associateWith { query -> List(ROUNDS) { timed { run(query) } }.sorted()[ROUNDS / 2] })
            assertTrue(p95 <= LIMIT_MS.getValue(lane), "$lane p95 $p95 ms is over its ${LIMIT_MS.getValue(lane)} ms limit")
        }
        driver.close()
        db.delete()
    }

    /** Prints p50 and p95 of the per-query times and returns the p95. */
    private fun report(lane: String, perQuery: Map<Query, Double>): Double {
        val samples = perQuery.values.sorted()
        val p50 = samples[samples.size / 2]
        val p95 = samples[(samples.size * 95 + 99) / 100 - 1]
        val worst = perQuery.entries.maxBy { it.value }.key
        println("SearchBench $lane: p50 %.2f ms, p95 %.2f ms, slowest \"%s\" %s".format(p50, p95, worst.text, worst.kinds ?: ""))
        return p95
    }

    /**
     * The keyword and name lanes run beside the query embed: a 50 ms embed (blocking, as a native call does) and 30 ms
     * of name work take about 50 ms together, not 80.
     */
    @Test
    fun keywordLanesRunBesideTheEmbed(): Unit = runBlocking(Dispatchers.Default) {
        val store = newStore()
        val engine = SearchEngine(store, FakeEmbedder { Thread.sleep(50) }) {
            Thread.sleep(30)
            NameIndex.EMPTY
        }
        engine.search("warm up")
        // A new query each time: the engine reuses the last query's vector.
        val ms = List(5) { timed { engine.search("pool party $it") } }.sorted()[2]
        println("SearchBench: 50 ms embed beside 30 ms of keyword work took %.1f ms".format(ms))
        assertTrue(ms in 50.0..70.0, "$ms ms")
    }

    /** SearchEngine.scanTop as wave 4 shipped it (kind filter only, no histograms): the same-run baseline. */
    private fun wave4ScanTop(q: Vector, n: Int, table: VectorTable, allowed: BooleanArray?): List<Pair<Long, Float>> {
        val size = table.size
        val bytes = table.bytes
        val scales = table.scales
        val ids = table.chunkIds
        val kinds = table.kinds
        val k = minOf(n, size)
        if (k <= 0) return emptyList()
        val heapScore = FloatArray(k)
        val heapRow = IntArray(k)
        var filled = 0
        for (row in 0 until size) {
            if (allowed != null && !allowed[kinds[row].toInt()]) continue
            val s = dotInt8(bytes, row * DIMS, q.bytes, q.scale, scales[row])
            if (filled < k) {
                var i = filled++
                while (i > 0) {
                    val parent = (i - 1) / 2
                    if (heapScore[parent] <= s) break
                    heapScore[i] = heapScore[parent]
                    heapRow[i] = heapRow[parent]
                    i = parent
                }
                heapScore[i] = s
                heapRow[i] = row
            } else if (s > heapScore[0]) {
                var i = 0
                while (true) {
                    val l = 2 * i + 1
                    if (l >= k) break
                    val c = if (l + 1 < k && heapScore[l + 1] < heapScore[l]) l + 1 else l
                    if (heapScore[c] >= s) break
                    heapScore[i] = heapScore[c]
                    heapRow[i] = heapRow[c]
                    i = c
                }
                heapScore[i] = s
                heapRow[i] = row
            }
        }
        return (0 until filled).sortedByDescending { heapScore[it] }.map { ids[heapRow[it]] to heapScore[it] }
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

    /** Returns the files' name entries, for the name index. */
    private fun build(store: Store, rnd: Random, vocab: List<String>, zipf: Zipf): List<NameEntry> = store.inTransaction {
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
        val entries = ArrayList<NameEntry>(files.size)
        files.forEachIndexed { i, f ->
            val id = checkNotNull(store.fileByUri(f.uri)).id
            entries += NameEntry(id, f.name, f.folder, f.kind)
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
        entries
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
        const val SCAN_ROUNDS = 7

        /**
         * p95 limits: about 3x the worst p95 of three runs, never under a floor, so a loaded CI machine does not flap.
         * Measured 2026-10-17 on an M4 Max shared with other builds (load average 130-350), p95 of three runs:
         * keyword 45.9 / 34.7 / 36.0 ms (3x = 138, limit 150); vector 9.8 / 9.3 / 8.6 ms (3x = 29, floor 50);
         * fused 50.7 / 48.2 / 136.6 ms, the last a load spike, 61.2 on a fourth run (3x = 184, limit 200).
         * Before the wave 4 keyword cap the same bench measured keyword p95 296-338 ms and fused 287-393 ms.
         * names: the phase-one budget from the wave 5 plan (one frame-ish), including the batched file lookup.
         */
        val LIMIT_MS = mapOf("keyword" to 150.0, "vector" to 50.0, "names" to 5.0, "fused" to 200.0)

        val FOLDERS = listOf("DCIM/Camera", "Pictures/Screenshots", "Download", "Documents", "Documents/Work", "WhatsApp/Media", "Movies", "Music")
        val HINDI = listOf(
            "बिजली", "बिल", "किराया", "पानी", "घर", "महीना", "भुगतान", "रसीद", "दवा", "स्कूल", "फीस", "बैंक", "खाता", "नाम",
            "पता", "तारीख", "राशि", "कुल", "जमा", "समझौता", "मकान", "मालिक", "किरायेदार", "सुरक्षा", "राशन", "कार्ड",
        )
    }
}
