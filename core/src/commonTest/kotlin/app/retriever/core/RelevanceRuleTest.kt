package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SearchEngine's strong/weak split ([RelevanceRule], name lanes) and its filters. */
class RelevanceRuleTest {
    private val sig = Signature("M740/0123abcd/vt70/d256/t512")
    private val rnd = Random(11)

    /** Every query is the first axis, so a row's cosine is its first component. */
    private class AxisEmbedder(override val signature: Signature) : Embedder {
        override val backend = Backend.CPU

        override suspend fun embedRaw(parts: List<Part>) = FloatArray(768).also { it[0] = 1f }

        override fun close() {}
    }

    /** One file with one chunk at cosine [cos]. Null [text] and ordinal 1 make it an image row (a PDF page image). */
    private class F(val name: String, val cos: Float, val kind: Kind = Kind.PDF, val text: String? = "words", val ordinal: Int = 1, val at: Long = 1)

    private fun gauss() = sqrt(-2 * ln(1 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())

    private fun background(n: Int, mean: Double, sd: Double, text: String? = "words") = List(n) { F("bg$it.pdf", (mean + sd * gauss()).toFloat(), text = text) }

    /**
     * The row is (cos, noise of norm sqrt(1 - cos^2)), so its first component is the largest and int8 keeps cos to
     * within float rounding.
     */
    private fun store(files: List<F>): Pair<Store, Map<String, Long>> {
        val store = Store(testDriver())
        store.resetForModel(sig)
        val sources = files.map { SourceFile("folder://documents/Docs/${it.name}", it.name, "Docs", "application/pdf", it.kind, 100, it.at) }
        store.upsertFiles(sources)
        val ids = HashMap<String, Long>()
        store.inTransaction {
            files.forEachIndexed { i, f ->
                val noise = FloatArray(DIMS - 1) { gauss().toFloat() }
                val norm = sqrt(noise.sumOf { (it * it).toDouble() }).toFloat()
                val rest = sqrt(1 - f.cos * f.cos)
                val raw = FloatArray(768).also { v ->
                    v[0] = f.cos
                    for (d in 1 until DIMS) v[d] = noise[d - 1] / norm * rest
                }
                val id = checkNotNull(store.fileByUri(sources[i].uri)).id
                ids[f.name] = id
                store.replaceChunks(id, listOf(Store.NewChunk(f.ordinal, "p. 1", f.text, Vector.of(raw, sig))))
            }
        }
        return store to ids
    }

    private fun engine(files: List<F>, rule: RelevanceRule? = RelevanceRule(), names: NameIndex? = null) =
        SearchEngine(store(files).first, AxisEmbedder(sig), rule = rule) { names }

    private fun strongNames(r: SearchEngine.SearchResult) = r.hits.take(r.strongCount).map { it.file.name }

    private val vector = SearchEngine.Mode.VECTOR

    /** At m = 1 the noise line sits at about 5.1 sigma for 5,000 rows, which pure noise passes about 0.1% of the time. */
    @Test
    fun anOutlierSixSigmaAboveAGaussianBackgroundIsStrong() = runTest {
        val r = engine(background(5_000, 0.5, 0.02) + F("outlier.pdf", 0.62f), RelevanceRule(margin = 1.0)).search("anything", vector)
        assertEquals("outlier.pdf", r.hits.first().file.name)
        assertEquals(listOf("outlier.pdf"), strongNames(r))
    }

    /**
     * The rule's own statistic on pure noise: per query, 5,000 Gaussian cosines through the same histogram and line the
     * scan uses. At m = 0 the largest clears it with P = 1 - Phi(sqrt(2 ln n))^n, 8.8% at n = 5,000.
     */
    @Test
    fun atZeroMarginNoiseAloneIsStrongAsOftenAsTheGaussianMaxPredicts() {
        val rule = RelevanceRule(margin = 0.0)
        val text = Modality.TEXT.ordinal
        var strong = 0
        repeat(1_000) {
            val h = rule.histogram()
            var max = -1f
            repeat(5_000) {
                val c = (0.5 + 0.03 * gauss()).toFloat()
                h[text * RelevanceRule.BINS + RelevanceRule.bin(c)]++
                if (c > max) max = c
            }
            if (max >= rule.minStrong(h)[text]) strong++
        }
        println("RelevanceRuleTest: false-strong $strong/1000 at m = 0, predicted 88")
        assertTrue(strong in 58..118, "false-strong $strong/1000, predicted 88")
    }

    @Test
    fun aGroupUnderTwoHundredRowsCallsEveryHitStrong() = runTest {
        val r = engine(background(150, 0.5, 0.02)).search("anything", vector)
        assertEquals(20, r.hits.size)
        assertEquals(r.hits.size, r.strongCount)
    }

    /**
     * Page images sit around 0.20, text chunks around 0.30. A page image at 0.32 is far above its own group, but inside
     * the spread of a pooled one: strong only while the groups keep separate histograms.
     */
    @Test
    fun pdfTextAndPageImagesUseSeparateHistograms() = runTest {
        fun files(imagesHaveText: Boolean) = background(300, 0.30, 0.02) +
            List(300) { F("page$it.pdf", (0.20 + 0.02 * gauss()).toFloat(), text = if (imagesHaveText) "words" else null) } +
            F("outlier page.pdf", 0.32f, text = if (imagesHaveText) "words" else null)
        val separate = engine(files(imagesHaveText = false)).search(SearchRequest("anything", limit = 100, mode = vector))
        assertTrue("outlier page.pdf" in strongNames(separate), "${separate.strongCount} strong")
        val pooled = engine(files(imagesHaveText = true)).search(SearchRequest("anything", limit = 100, mode = vector))
        assertTrue("outlier page.pdf" !in strongNames(pooled))
        assertTrue(pooled.hits.any { it.file.name == "outlier page.pdf" })
    }

    /**
     * Provenance (eval/relevance-fit.py over the replays of the public XM3600 +
     * SciFact + MIRACL-hi library): conformal risk control at alpha 0.10 on M440/92dcbea1/vt70's calibration split (2,478
     * listed right files) gave m = -1.74. On the test split it calls 78% of text and 54% of photo no-match queries
     * strong (gate: at most 12%), and no m, even one picked on the test split, reaches strong recall 0.80 at 12%
     * false-strong in any modality group pooled over languages; method 4.4's own gate fails too. So the rule ships off
     * and the margin stays the unfitted placeholder. Re-fit with a new method before changing either; never hand-pick m.
     */
    @Test
    fun theFitFailedItsGateSoTheRuleShipsOff() {
        assertEquals(false, RELEVANCE_RULE_ENABLED)
        assertEquals(0.0, RELEVANCE_MARGIN)
    }

    @Test
    fun theShippedSettingKeepsAllHitsAndTheBestTwentyVisibleWhateverTheirModality() = runTest {
        // RELEVANCE_RULE_ENABLED is false (theFitFailedItsGateSoTheRuleShipsOff); this test changes with it.
        assertEquals(null, RelevanceRule.shipped)
        // Page images (no text) score above every text chunk, so they fill the visible part on their own.
        val (store, _) = store(background(5_000, 0.5, 0.02) + List(30) { F("page$it.pdf", 0.7f + it / 1000f, text = null) })
        val r = SearchEngine(store, AxisEmbedder(sig)).search(SearchRequest("anything", limit = 100, mode = vector))
        assertEquals(100, r.hits.size)
        assertEquals(LIKELY_MEANING_FILES, r.strongCount)
        assertTrue(strongNames(r).all { it.startsWith("page") }, strongNames(r).toString())
        val photo = SearchEngine(store, AxisEmbedder(sig)).searchImage(byteArrayOf(1))
        assertEquals(20, photo.hits.size)
        assertEquals(LIKELY_MEANING_FILES, photo.strongCount)
    }

    @Test
    fun exactAndPrefixNamesAreStrongButASubstringOrTypoAloneIsNot() = runTest {
        val files = listOf(F("Passport scan.pdf", 0.1f), F("Lease.pdf", 0.2f))
        val (store, ids) = store(files)
        val names = NameIndex.build(files.map { NameEntry(ids.getValue(it.name), it.name, "Docs", it.kind) })
        val engine = SearchEngine(store, AxisEmbedder(sig), rule = RelevanceRule()) { names }
        for (q in listOf("passport", "pass")) {
            val r = engine.names(SearchRequest(q))
            assertEquals(listOf("Passport scan.pdf"), strongNames(r), q)
            assertTrue(r.hits.single().byName)
        }
        for (q in listOf("assport", "pasport")) {
            val r = engine.names(SearchRequest(q))
            assertEquals("Passport scan.pdf", r.hits.single().file.name, q)
            assertEquals(0, r.strongCount, q)
        }
        // Keyword-only (no model): a typo match is still weak; the full search marks name hits and only those.
        val full = SearchEngine(store, AxisEmbedder(Signature("other"))) { names }.search("pasport")
        assertEquals(0, full.strongCount)
        assertTrue(full.hits.single().byName)
        assertTrue(SearchEngine(store, AxisEmbedder(sig)) { names }.search("anything", vector).hits.none { it.byName })
    }

    /** A CJK word inside a name's run is a word, as the cold `file_fts` lane finds it: strong once the index is warm too. */
    @Test
    fun aCjkWordInsideANameIsStrong() = runTest {
        val files = listOf(F("会議資料.pdf", 0.1f))
        val (store, ids) = store(files)
        val names = NameIndex.build(files.map { NameEntry(ids.getValue(it.name), it.name, "書類", it.kind) })
        val r = SearchEngine(store, AxisEmbedder(sig), rule = RelevanceRule()) { names }.names(SearchRequest("資料"))
        assertEquals(listOf("会議資料.pdf"), strongNames(r))
    }

    /** NameIndex lists close matches before loose ones; fusion must keep that order, so a loose name never cuts a prefix one. */
    @Test
    fun substringNamesNeverPushPrefixNamesPastTheCut() = runTest {
        for ((close, loose, limit) in listOf(Triple(40, 40, 60), Triple(70, 30, 70))) {
            val files = List(close) { F("leases $it.pdf", 0.1f) } + List(loose) { F("release $it.pdf", 0.1f) }
            val (store, ids) = store(files)
            val names = NameIndex.build(files.map { NameEntry(ids.getValue(it.name), it.name, "Docs", it.kind) })
            val r = SearchEngine(store, AxisEmbedder(sig)) { names }.names(SearchRequest("lease", limit = limit))
            val case = "$close prefix and $loose substring names, limit $limit"
            assertEquals(files.take(close).map { it.name }.toSet(), r.hits.take(close).map { it.file.name }.toSet(), case)
            assertEquals(close, r.strongCount, case)
        }
    }

    @Test
    fun datesKindsAndFileIdsFilterEveryLane() = runTest {
        val day = 86_400_000L
        val files =
            listOf(
                F("old.pdf", 0.9f, at = 10 * day),
                F("new.pdf", 0.5f, at = 20 * day + 5),
                F("newer.pdf", 0.4f, at = 21 * day),
                F(
                    "photo.jpg",
                    0.8f,
                    Kind.PHOTO,
                    at =
                    20 * day,
                ),
            )
        val (store, ids) = store(files)
        val names = NameIndex.build(files.map { NameEntry(ids.getValue(it.name), it.name, "Docs", it.kind, it.at) })
        val engine = SearchEngine(store, AxisEmbedder(sig)) { names }
        suspend fun found(req: SearchRequest) = engine.search(req).hits.map { it.file.name }.toSet()
        assertEquals(setOf("new.pdf", "photo.jpg"), found(SearchRequest("anything", from = 20 * day, to = 21 * day)))
        assertEquals(setOf("new.pdf"), found(SearchRequest("anything", kinds = setOf(Kind.PDF), from = 20 * day, to = 21 * day)))
        assertEquals(setOf("old.pdf", "newer.pdf"), found(SearchRequest("anything", fileIds = setOf(ids.getValue("old.pdf"), ids.getValue("newer.pdf")))))
        // The name lane is filtered too: "pdf" names every document, but only the dated one passes.
        assertEquals(setOf("new.pdf"), engine.names(SearchRequest("pdf", from = 20 * day, to = 21 * day)).hits.map { it.file.name }.toSet())
    }

    @Test
    fun theSnippetIsTheSentenceSharingTheMostQueryWords() {
        val text = "Rent is due on the 5th. The security deposit is two months of rent. Call the owner."
        assertEquals("The security deposit is two months of rent.", SearchEngine.bestSentence(text, SearchEngine.folded("Security Déposit")))
        assertEquals(text.take(SearchEngine.SNIPPET), SearchEngine.bestSentence(text, SearchEngine.folded("guitar")))
    }
}
