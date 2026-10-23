package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private fun vec(sig: Signature, hot: Int = 0) = Vector.of(FloatArray(DIMS) { if (it == hot) 1f else 0f }, sig)

private fun engine(
    store: Store,
    extractor: Extractor = corpusExtractor(),
    embedder: Embedder = FakeEmbedder(),
    monitor: DeviceMonitor = FakeMonitor(),
    onFile: (FileMetric) -> Unit = {},
) = IndexEngine(store, extractor, embedder, monitor, settingsOf(), onFile)

private suspend fun IndexEngine.batch(requireCharging: Boolean = false, pauseWhenHot: Boolean = true) = runBatch(9.minutes, requireCharging, pauseWhenHot)

class OverflowSplitTest {
    /** Rejects any text input longer than 300 chars, the way the runtime rejects inputs over its signature length. */
    private class TightEmbedder : Embedder by FakeEmbedder() {
        private val inner = FakeEmbedder()
        override suspend fun embedRaw(parts: List<Part>): FloatArray {
            val t = parts.filterIsInstance<Part.Text>().sumOf { it.text.length }
            if (t > 300) throw InputTooLongException("Input sequence length ($t) exceeds maximum supported signature length (512).")
            return inner.embedRaw(parts)
        }
    }

    @Test
    fun anOverlongTextPieceIsSplitInsteadOfFailingTheFile() = runTest {
        val store = newStore()
        val long = List(80) { "word$it" }.joinToString(" ") // ~550 chars
        store.upsertFiles(listOf(testFile("dense.txt")))
        val result = engine(store, FakeExtractor(mapOf("dense.txt" to listOf(Piece("p. 1", listOf(Part.Text(long)), long)))), TightEmbedder()).batch()
        assertEquals(0, result.failed)
        assertEquals(FileState.INDEXED, store.countsByState().keys.single())
        assertTrue(store.chunkCount() >= 2, "at least two halves, got ${store.chunkCount()}")
    }
}

private val CORPUS = mapOf(
    "lease.txt" to "rental lease agreement security deposit two months",
    "chai.txt" to "masala chai recipe ginger cardamom",
    "bill.txt" to "electricity bill september invoice 48213",
)

private fun corpusExtractor() = FakeExtractor(CORPUS.mapValues { (_, text) -> listOf(textPiece(text)) })

class StoreTest {
    @Test
    fun replaceChunksMarksFileIndexed() {
        val store = newStore()
        store.upsertFiles(listOf(testFile("a.txt")))
        val id = store.pending(10).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "a", vec(SIG))))
        assertEquals(1L, store.countsByState()[FileState.INDEXED])
        assertTrue(store.pending(10).isEmpty())
    }

    @Test
    fun identicalUpsertKeepsIndexed() {
        val store = newStore()
        store.upsertFiles(listOf(testFile("a.txt")))
        val id = store.pending(10).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "a", vec(SIG))))
        store.upsertFiles(listOf(testFile("a.txt")))
        assertEquals(1L, store.countsByState()[FileState.INDEXED])
    }

    @Test
    fun newerUpsertMarksPending() {
        val store = newStore()
        store.upsertFiles(listOf(testFile("a.txt")))
        val id = store.pending(10).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "a", vec(SIG))))
        store.upsertFiles(listOf(testFile("a.txt", modifiedAt = 2L)))
        assertEquals(1L, store.countsByState()[FileState.PENDING])
    }

    @Test
    fun replaceChunksRejectsOtherSignature() {
        val store = newStore(Signature("A"))
        store.upsertFiles(listOf(testFile("a.txt")))
        val id = store.pending(10).single().id
        assertFailsWith<IllegalStateException> {
            store.replaceChunks(id, listOf(Store.NewChunk(0, "", null, vec(Signature("B")))))
        }
    }

    @Test
    fun keywordSearchMatchesWordAndPrefix() {
        val store = newStore()
        store.upsertFiles(listOf(testFile("bill.txt")))
        val fileId = store.pending(10).single().id
        store.replaceChunks(fileId, listOf(Store.NewChunk(0, "", "electricity bill invoice 48213", vec(SIG))))

        val whole = store.keywordSearch("invoice", 10)
        assertEquals(1, whole.size)
        assertEquals(fileId, store.chunk(whole.single().chunkId)?.fileId)
        assertEquals(1, store.keywordSearch("invo", 10).size)
        assertEquals(1, store.keywordSearch("bill invo", 10).size)
        assertTrue(store.keywordSearch("pizza", 10).isEmpty())
    }

    @Test
    fun keywordSearchRanksByRelevanceNotInsertionOrder() {
        val store = newStore()
        store.upsertFiles(listOf(testFile("old.txt", modifiedAt = 3L), testFile("new.txt", modifiedAt = 2L)))
        val (old, new) = store.pending(10).map { it.id }
        store.replaceChunks(old, listOf(Store.NewChunk(0, "", "invoice and many other unrelated words about weather and travel plans", vec(SIG))))
        store.replaceChunks(new, listOf(Store.NewChunk(0, "", "invoice invoice invoice", vec(SIG))))
        assertEquals(new, store.chunk(store.keywordSearch("invoice", 10).first().chunkId)?.fileId)
    }

    @Test
    fun keywordSearchRanksTheNewestMatchesAndFiltersKindsBeforeTheCap() {
        val store = newStore()
        val cap = Store.FTS_CAP.toInt()
        val files = listOf(testFile("old.pdf", Kind.PDF)) + List(cap) { testFile("new$it.txt") }
        store.inTransaction {
            store.upsertFiles(files)
            for (f in files) store.replaceChunks(store.fileByUri(f.uri)!!.id, listOf(Store.NewChunk(0, "", "invoice", null)))
        }
        val pdf = store.fileByUri(files[0].uri)!!.id

        val all = store.keywordSearch("invoice", cap + 1)
        assertEquals(cap, all.size)
        assertTrue(all.none { store.chunk(it.chunkId)?.fileId == pdf }, "the oldest match is past the cap")
        assertEquals(pdf, store.chunk(store.keywordSearch("invoice", 10, setOf(Kind.PDF)).single().chunkId)?.fileId)
    }

    @Test
    fun keywordSearchMatchesThroughUnicodePunctuationAccentsAndCase() {
        val store = newStore()
        store.upsertFiles(listOf(testFile("a.txt")))
        val id = store.pending(10).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "“Lease Agreement” tenant—landlord •Invoice CAFÉ", vec(SIG))))
        for (q in listOf("lease", "tenant", "landlord", "invoice", "café", "cafe", "agreement lease")) {
            assertEquals(1, store.keywordSearch(q, 10).size, q)
        }
    }

    @Test
    fun keywordSearchIgnoresJunkInputAndLogsNothing() {
        val logged = mutableListOf<String>()
        val store = newStore(log = { m, _ -> logged += m })
        store.upsertFiles(listOf(testFile("bill.txt")))
        val fileId = store.pending(10).single().id
        store.replaceChunks(fileId, listOf(Store.NewChunk(0, "", "invoice", vec(SIG))))
        assertTrue(store.keywordSearch("\"*(:)", 10).isEmpty())
        assertTrue(store.keywordSearch("", 10).isEmpty())
        assertTrue(store.keywordSearch("OR AND NOT NEAR", 10).isEmpty())
        assertTrue(logged.isEmpty(), logged.toString())
    }

    @Test
    fun removeFilesDropsChunksAndKeywordHits() {
        val store = newStore()
        store.upsertFiles(listOf(testFile("bill.txt")))
        val fileId = store.pending(10).single().id
        store.replaceChunks(fileId, listOf(Store.NewChunk(0, "", "invoice", vec(SIG))))
        store.removeFiles(listOf("content://test/bill.txt"))
        assertEquals(0L, store.chunkCount())
        assertTrue(store.keywordSearch("invoice", 10).isEmpty())
        assertTrue(store.fileKeywordSearch("bill", 10).isEmpty())
        assertNull(store.file(fileId))
    }

    @Test
    fun resetForModelKeepsContentAndDropsOnlyVectors() {
        val store = newStore(Signature("A"))
        store.upsertFiles(listOf(testFile("a.txt"), testFile("b.txt")))
        val id = store.pending(10).first().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "a", vec(Signature("A")))))
        store.resetForModel(Signature("B"))
        assertEquals(1L, store.chunkCount())
        assertEquals(0, store.vectorCount())
        assertEquals(1L, store.countsByState()[FileState.PENDING])
        assertEquals(1L, store.countsByState()[FileState.INDEXED])
        assertEquals(Signature("B"), store.signature())
        assertEquals(1, store.fileKeywordSearch("a", 10).size, "names stay searchable across a model change")
    }

    @Test
    fun markFailedKeepsPendingUntilThirdAttempt() {
        val store = newStore()
        store.upsertFiles(listOf(testFile("a.txt")))
        val id = store.pending(10).single().id
        store.markFailed(id, 1)
        assertEquals(1L, store.countsByState()[FileState.PENDING])
        store.markFailed(id, 3)
        assertEquals(1L, store.countsByState()[FileState.SKIPPED])
        assertEquals(listOf("a.txt"), store.skipped(10).map { it.file.name })
    }

    @Test
    fun retrySkippedGivesSkippedFilesFreshAttempts() {
        val store = newStore()
        store.upsertFiles(listOf(testFile("a.txt"), testFile("b.txt")))
        val (a, b) = store.pending(10)
        store.markFailed(a.id, 3)
        store.replaceChunks(b.id, listOf(Store.NewChunk(0, "", "b", vec(SIG))))

        store.retrySkipped()

        val retried = store.pending(10).single()
        assertEquals(a.id, retried.id)
        assertEquals(0, retried.attempts)
        assertEquals(mapOf(FileState.INDEXED to 1L, FileState.PENDING to 1L), store.countsByState())
    }

    @Test
    fun recentQueriesKeepTheNewestTenWithoutDuplicates() {
        val store = newStore()
        for (i in 1..12) store.addRecentQuery("q$i", i.toLong())
        store.addRecentQuery("q5", 100)
        assertEquals(listOf("q5", "q12", "q11", "q10", "q9", "q8", "q7", "q6", "q4", "q3"), store.recentQueries())
        store.clearRecentQueries()
        assertTrue(store.recentQueries().isEmpty())
    }

    @Test
    fun unreadableProfileIsLogged() {
        val logged = mutableListOf<String>()
        val store = newStore(log = { m, _ -> logged += m })
        store.setMeta("profile", "junk")
        assertNull(store.profile())
        assertEquals(1, logged.size)
    }
}

class SearchEngineTest {
    private suspend fun indexedSearch(): SearchEngine {
        val store = newStore()
        val embedder = FakeEmbedder()
        store.upsertFiles(CORPUS.keys.map { testFile(it) })
        engine(store, embedder = embedder).batch()
        return SearchEngine(store, embedder)
    }

    @Test
    fun mergedSearchRanksSemanticMatchFirst() = runTest {
        val result = indexedSearch().search("security deposit for the rental", SearchEngine.Mode.MERGED)
        assertTrue(result.vectorLane)
        assertEquals("lease.txt", result.hits.first().file.name)
    }

    @Test
    fun keywordSearchFindsExactNumber() = runTest {
        val hits = indexedSearch().search("48213", SearchEngine.Mode.KEYWORD).hits
        assertEquals("bill.txt", hits.first().file.name)
        assertEquals("electricity bill september invoice \u000248213\u0003", hits.first().snippet)
    }

    @Test
    fun kindFilterExcludesOtherKinds() = runTest {
        val hits = indexedSearch().search("security deposit", kinds = setOf(Kind.PHOTO)).hits
        assertTrue(hits.isEmpty())
    }

    @Test
    fun anotherModelsIndexDegradesToKeywordOnly() = runTest {
        val store = newStore()
        store.upsertFiles(CORPUS.keys.map { testFile(it) })
        engine(store).batch()
        val other = SearchEngine(store, FakeEmbedder(Signature("OTHER/vt70/d256")))
        val vector = other.search("rental", SearchEngine.Mode.VECTOR)
        assertFalse(vector.vectorLane)
        assertTrue(vector.hits.isEmpty())
        val image = other.searchImage(byteArrayOf(1))
        assertFalse(image.vectorLane)
        assertTrue(image.hits.isEmpty())
        val merged = other.search("48213")
        assertFalse(merged.vectorLane)
        assertEquals("bill.txt", merged.hits.first().file.name)
    }

    @Test
    fun aClosedEmbedderPropagatesSoTheCallerCanRetireIt() = runTest {
        val store = newStore()
        val embedder = FakeEmbedder()
        store.upsertFiles(CORPUS.keys.map { testFile(it) })
        engine(store, embedder = embedder).batch()
        embedder.close()
        assertFailsWith<EmbedderClosedException> { SearchEngine(store, embedder).search("48213") }
    }

    @Test
    fun eachFileAppearsAtMostOnce() = runTest {
        val hits = indexedSearch().search("security deposit for the rental").hits
        assertEquals(hits.size, hits.map { it.fileId }.toSet().size)
    }

    @Test
    fun refreshPicksUpNewFilesAndDropsDeletedOnes() = runTest {
        val store = newStore()
        val embedder = FakeEmbedder()
        val search = SearchEngine(store, embedder)
        store.upsertFiles(listOf(testFile("lease.txt")))
        engine(store, embedder = embedder).batch()
        assertEquals("lease.txt", search.search("security deposit", SearchEngine.Mode.VECTOR).hits.single().file.name)

        store.upsertFiles(listOf(testFile("chai.txt")))
        engine(store, embedder = embedder).batch()
        store.removeFiles(listOf("content://test/lease.txt"))
        val hits = search.search("masala chai ginger", SearchEngine.Mode.VECTOR).hits
        assertEquals(listOf("chai.txt"), hits.map { it.file.name })
        assertEquals(2, search.fullLoads, "deleting half the loaded rows forces a rebuild")
    }
}

class IndexEngineTest {
    @Test
    fun runBatchIndexesEveryFileWithoutANameChunk() = runTest {
        val store = newStore()
        store.upsertFiles(CORPUS.keys.map { testFile(it) })
        val result = engine(store).batch()
        assertEquals(3, result.processed)
        assertEquals(StopReason.DONE, result.stopReason)
        assertEquals(3L, store.chunkCount())
    }

    @Test
    fun failingFileStaysPendingThenSkipped() = runTest {
        val store = newStore()
        store.upsertFiles(listOf(testFile("broken.txt")))
        val statesAfterFailure = mutableListOf<Map<FileState, Long>>()
        val result = engine(store, FakeExtractor(fail = true)) { metric ->
            if (!metric.ok) statesAfterFailure += store.countsByState()
        }.batch()
        assertEquals(
            listOf(mapOf(FileState.PENDING to 1L), mapOf(FileState.PENDING to 1L), mapOf(FileState.SKIPPED to 1L)),
            statesAfterFailure,
        )
        assertEquals(StopReason.DONE, result.stopReason)
    }

    @Test
    fun attemptIsCountedBeforeProcessingAndErrorsAreCaught() = runTest {
        val store = newStore()
        store.upsertFiles(listOf(testFile("huge.xlsx")))
        val attemptsSeen = mutableListOf<Int>()
        val crashing = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
                attemptsSeen += store.pending(1).single().attempts
                throw OutOfMemoryError("sharedStrings.xml")
            }
        }
        val result = engine(store, crashing).batch()
        // The third attempt keeps the file pending while it is read; only its failure skips it.
        assertEquals(listOf(1, 2, 3), attemptsSeen)
        assertEquals(3, result.failed)
        assertEquals(mapOf(FileState.SKIPPED to 1L), store.countsByState())
    }

    @Test
    fun hotDevicePausesBeforeAnyFile() = runTest {
        val store = newStore()
        store.upsertFiles(CORPUS.keys.map { testFile(it) })
        val monitor = FakeMonitor(DeviceState(8.0, true, 80, Thermal.SEVERE))
        val result = engine(store, monitor = monitor).batch(pauseWhenHot = true)
        assertEquals(StopReason.HOT, result.stopReason)
        assertEquals(0, result.processed)
    }

    @Test
    fun unpluggedPausesWhenChargingRequired() = runTest {
        val store = newStore()
        store.upsertFiles(CORPUS.keys.map { testFile(it) })
        val monitor = FakeMonitor(DeviceState(8.0, false, 80, Thermal.NONE))
        val result = engine(store, monitor = monitor).batch(requireCharging = true)
        assertEquals(StopReason.UNPLUGGED, result.stopReason)
        assertEquals(0, result.processed)
    }

    @Test
    fun aPluggedInPhoneReadsBeforeItsLevelRises() = runTest {
        val store = newStore()
        store.upsertFiles(CORPUS.keys.map { testFile(it) })
        val monitor = FakeMonitor(DeviceState(8.0, charging = false, 84, Thermal.NONE, pluggedIn = true))
        val result = engine(store, monitor = monitor).batch(requireCharging = true)
        assertEquals(StopReason.DONE, result.stopReason)
        assertEquals(CORPUS.size, result.processed)
    }
}
