package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class EvalTest {
    /** Each word and image adds its own seeded random vector, so scores almost never tie. Records every text it reads. */
    private class SeededEmbedder(override val signature: Signature) : Embedder {
        override val backend = Backend.CPU
        val texts = mutableListOf<String>()

        override suspend fun embedRaw(parts: List<Part>): FloatArray {
            val v = FloatArray(768)
            fun add(seed: Int) = Random(seed).let { r -> for (i in v.indices) v[i] += r.nextFloat() - 0.5f }
            for (p in parts) when (p) {
                is Part.Text -> {
                    texts += p.text
                    p.text.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }.forEach { add(it.hashCode()) }
                }
                is Part.Image -> add(p.jpeg.contentHashCode())
                is Part.Audio -> add(7)
            }
            return v
        }

        override fun close() {}
    }

    private class Factory : EmbedderFactory {
        val opened = mutableListOf<SeededEmbedder>()

        override fun backends(build: ModelBuild) = listOf(Backend.CPU)

        override fun open(profile: DeviceProfile, build: ModelBuild): Embedder =
            SeededEmbedder(Signature.of(build, profile.visionTokens, profile.maxTokens)).also { opened += it }
    }

    private val build = ModelVariant.M440.generic
    private val profile = DeviceProfile(Tier.BALANCED, ModelVariant.M440, Backend.CPU, 70, 0, false, false)

    private fun file(name: String, kind: Kind, folder: String = "Docs") =
        SourceFile("folder://documents/$folder/$name", name, folder, "application/octet-stream", kind, 100, 1)

    private val corpus = listOf(
        file("invoice.pdf", Kind.PDF),
        file("lease.pdf", Kind.PDF),
        file("trek.txt", Kind.DOC),
        file("recipe.docx", Kind.DOC),
        file("IMG_1001.jpg", Kind.PHOTO, "Photos"),
        file("IMG_1002.jpg", Kind.PHOTO, "Photos"),
        file("Screenshot flight.png", Kind.SCREENSHOT, "Photos"),
        file("empty.txt", Kind.DOC),
        // For "deposit": search-log shares the query prefix's words, so the vector lane ranks it first; slips repeats
        // the word, so the keyword lane does. Only the fusion's k and keyword weight decide which comes first.
        file("search-log.txt", Kind.DOC),
        file("slips.txt", Kind.DOC),
        file("search-notes.txt", Kind.DOC),
    )

    private val extractor = object : Extractor {
        val pieces = mapOf(
            "invoice.pdf" to listOf(
                Piece("p. 1", listOf(Part.Text("Invoice for a dining table and four chairs")), null),
                Piece("p. 2", listOf(Part.Text("Delivery charges and the total due")), null),
            ),
            "lease.pdf" to listOf(Piece("p. 1", listOf(Part.Text("Rent and security deposit for the flat in Pune")), null)),
            "trek.txt" to listOf(Piece("", listOf(Part.Text("Pack boots, a rain jacket and water for the trek")), null)),
            "recipe.docx" to listOf(Piece("", listOf(Part.Text("Aloo paratha recipe with potatoes and spices")), null)),
            "IMG_1001.jpg" to listOf(Piece("", listOf(Part.Image(byteArrayOf(1, 2, 3))), null)),
            "IMG_1002.jpg" to listOf(Piece("", listOf(Part.Image(byteArrayOf(4, 5, 6))), null)),
            "Screenshot flight.png" to listOf(
                Piece("", listOf(Part.Image(byteArrayOf(7, 8))), "Boarding pass seat 14C flight to Delhi"),
            ),
            "search-log.txt" to listOf(Piece("", listOf(Part.Text("Search result for the query task: deposit")), null)),
            "slips.txt" to listOf(Piece("", listOf(Part.Text("deposit deposit deposit")), null)),
            "search-notes.txt" to listOf(Piece("", listOf(Part.Text("Search result for the query task")), null)),
        )

        override suspend fun extract(file: SourceFile, profile: DeviceProfile) = pieces[file.name].orEmpty()
    }

    private val queries = listOf(
        EvalQuery("dining table invoice", "invoice.pdf"),
        EvalQuery("security deposit", "lease.pdf"),
        EvalQuery("what to pack for the trek", "trek.txt"),
        EvalQuery("potato paratha", "recipe.docx"),
        EvalQuery("my flight seat", "Screenshot flight.png"),
        EvalQuery("IMG_1002", "IMG_1002.jpg"),
        EvalQuery("empty", "empty.txt"),
        EvalQuery("a dog on the grass", "IMG_1001.jpg"),
        EvalQuery("deposit", "slips.txt"),
        EvalQuery("volcano eruption"),
    )

    @Test
    fun metricsCountQueriesAndAverageReciprocalRanks() {
        val m = metrics(listOf(1, 3, null, 6))
        assertEquals(Metrics(4, 1, 2, (1.0 + 1.0 / 3 + 1.0 / 6) / 4), m)
        assertEquals(Metrics(0, 0, 0, 0.0), metrics(emptyList()))
    }

    @Test
    fun weightedRrfScalesEachLane() {
        val merged = rrfMerge(listOf(listOf(1L), listOf(2L)), k = 10, weights = listOf(1.0, 2.0))
        assertEquals(listOf(2L to 2.0 / 11, 1L to 1.0 / 11), merged)
        assertEquals(rrfMerge(listOf(listOf(1L, 2L), listOf(2L))), rrfMerge(listOf(listOf(1L, 2L), listOf(2L)), weights = listOf(1.0, 1.0)))
    }

    /**
     * The eval reproduces production fusion and its strong/weak split: each row's file order and strong count are
     * SearchEngine.search's, query by query, under the default floor and under a low one that lets vector-only files through.
     */
    @Test
    fun defaultRowMatchesSearchEngine() = runTest {
        val factory = Factory()
        val default = EvalSetting(profile.visionTokens)
        val tweaked = EvalSetting(profile.visionTokens, tuning = SearchTuning(rrfK = 10, keywordWeight = 2.0))
        val low = RelevanceFloor(0.2f, 0.2f, 0.2f)
        val run = RetrievalEval(extractor, factory, build, profile, Store(testDriver())).run(corpus, queries, listOf(default, tweaked))
        val lowRun = RetrievalEval(extractor, factory, build, profile, Store(testDriver()), floor = low).run(corpus, queries, listOf(default))

        val embedder = factory.open(profile, build)
        val store = Store(testDriver())
        store.resetForModel(embedder.signature)
        store.upsertFiles(corpus)
        val monitor = object : DeviceMonitor {
            override fun state() = DeviceState(8.0, true, 80, Thermal.NONE)
        }
        val batch = IndexEngine(store, extractor, embedder, monitor, { IndexSettings(profile, Kind.entries.toSet()) }).runBatch(1.hours, false)
        assertEquals(corpus.size, batch.processed)

        for ((floor, row) in listOf(RelevanceFloor() to run.rows[0], RelevanceFloor() to run.rows[1], low to lowRun.rows[0])) {
            val engine = SearchEngine(store, embedder, row.setting.tuning) { floor }
            queries.forEachIndexed { i, q ->
                val production = engine.search(q.q)
                assertEquals(production.hits.map { it.file.name }, row.top[i].map { corpus[it].name }, "${row.setting.label} $floor: \"${q.q}\"")
                assertEquals(production.strongCount, row.strong[i], "${row.setting.label} $floor: \"${q.q}\" strong")
            }
        }
        // The low floor lets vector-only files through, so the strong counts above exercise the floor, not only the keyword rule.
        assertTrue(lowRun.rows[0].strong.sum() > run.rows[0].strong.sum(), "${lowRun.rows[0].strong} vs ${run.rows[0].strong}")
        // A no-match query has no rank and leaves the metrics alone.
        assertEquals(null, run.rows[0].ranks.last())
        assertEquals(null, run.kinds.last())
        // The two settings rank "deposit" differently, so the equality above shows SearchEngine uses its tuning.
        val deposit = queries.indexOfFirst { it.q == "deposit" }
        fun firstTwo(row: EvalRow) = row.top[deposit].take(2).map { corpus[it].name }
        assertEquals(listOf("search-log.txt", "slips.txt"), firstTwo(run.rows[0]))
        assertEquals(listOf("slips.txt", "search-log.txt"), firstTwo(run.rows[1]))
        // Queries whose words are in the target's text or name find it first.
        val ranks = queries.map { it.q }.zip(run.rows[0].ranks).toMap()
        for (q in listOf("dining table invoice", "security deposit", "IMG_1002", "empty")) assertEquals(1, ranks[q], q)
    }

    @Test
    fun prefixesOffFeedsTheModelRawText() = runTest {
        val factory = Factory()
        RetrievalEval(extractor, factory, build, profile, Store(testDriver())).run(corpus, queries, listOf(EvalSetting(70, prefixes = false)))
        val raw = factory.opened.single().texts
        assertTrue(raw.isNotEmpty())
        assertTrue(raw.none { it.startsWith("title: ") || it.startsWith("task: ") }, raw.toString())

        val prefixed = Factory()
        RetrievalEval(extractor, prefixed, build, profile, Store(testDriver())).run(corpus, queries, listOf(EvalSetting(70)))
        val texts = prefixed.opened.single().texts
        assertTrue(texts.all { it.startsWith("title: ") || it.startsWith("task: search result | query: ") }, texts.toString())
        assertEquals(queries.size, texts.count { it.startsWith("task: ") })
    }

    @Test
    fun eachVisionTokenCountOpensItsOwnModelAndEmbedsImagesOnce() = runTest {
        val factory = Factory()
        val run = RetrievalEval(extractor, factory, build, profile, Store(testDriver()))
            .run(corpus, queries, listOf(EvalSetting(70), EvalSetting(70, prefixes = false), EvalSetting(140), EvalSetting(70, dims = 1024, int8 = false)))
        assertEquals(2, factory.opened.size)
        assertEquals(setOf(70, 140), run.embedMs.keys)
        assertEquals(3, run.rows.size)
        assertTrue(run.notes.any { it.startsWith("d1024") }, run.notes.toString())
    }
}
