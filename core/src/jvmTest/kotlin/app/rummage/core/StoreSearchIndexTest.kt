package app.rummage.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.rummage.core.db.IndexDb
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SIG = Signature("FAKE/vt70/d256")
private val PROFILE = DeviceProfile(Tier.FULL, ModelVariant.M740, Backend.CPU, 70, 0, true, false)

/** Hashes words into the first [DIMS] dims, the only dims [Vector.of] keeps. */
class FakeEmbedder(override val signature: Signature = SIG) : Embedder {
    override val backend = Backend.CPU

    override suspend fun embedRaw(parts: List<Part>): FloatArray {
        val v = FloatArray(DIMS)
        for (p in parts) when (p) {
            is Part.Text -> for (word in stripPrefix(p.text).split(Regex("[^\\p{L}\\p{Nd}]+"))) {
                if (word.isNotEmpty()) v[abs(word.lowercase().hashCode()) % DIMS] += 1f
            }
            is Part.Image -> v[p.jpeg.size % DIMS] += 1f
            is Part.Audio -> v[7] += 1f
        }
        if (v.all { it == 0f }) v[0] = 1f
        return v
    }

    private fun stripPrefix(text: String): String {
        val afterQuery = text.lastIndexOf("query: ").let { if (it < 0) -1 else it + "query: ".length }
        val afterText = text.lastIndexOf("text: ").let { if (it < 0) -1 else it + "text: ".length }
        return text.substring(maxOf(afterQuery, afterText, 0))
    }

    override fun close() {}
}

class FakeMonitor(var state: DeviceState = DeviceState(8.0, true, 80, Thermal.NONE)) : DeviceMonitor {
    override fun state(): DeviceState = state
}

class FakeExtractor(
    private val pieces: Map<String, List<Piece>> = emptyMap(),
    private val fail: Boolean = false,
) : Extractor {
    override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
        if (fail) throw IllegalStateException("boom")
        return pieces[file.name] ?: emptyList()
    }
}

private fun file(name: String, kind: Kind = Kind.DOC, modifiedAt: Long = 1L) = SourceFile(
    uri = "content://test/$name",
    name = name,
    folder = "",
    mime = "text/plain",
    kind = kind,
    size = 100L,
    modifiedAt = modifiedAt,
)

private fun newStore(sig: Signature? = null): Store {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    IndexDb.Schema.create(driver)
    return Store(driver).also { if (sig != null) it.useSignature(sig) }
}

private fun vec(sig: Signature, hot: Int = 0) = Vector.of(FloatArray(DIMS) { if (it == hot) 1f else 0f }, sig)

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
        store.upsertFiles(listOf(file("dense.txt")))
        val engine = IndexEngine(
            store, FakeExtractor(mapOf("dense.txt" to listOf(Piece("p. 1", listOf(Part.Text(long)), long)))),
            TightEmbedder(), FakeMonitor(), PROFILE,
        )
        val result = engine.runBatch()
        assertEquals(0, result.failed)
        assertEquals("indexed", store.countsByState().keys.single())
        assertTrue(store.chunkCount() >= 3, "name chunk + at least two halves, got ${store.chunkCount()}")
    }
}

private val CORPUS = mapOf(
    "lease.txt" to "rental lease agreement security deposit two months",
    "chai.txt" to "masala chai recipe ginger cardamom",
    "bill.txt" to "electricity bill september invoice 48213",
)

private fun corpusExtractor() = FakeExtractor(
    CORPUS.mapValues { (_, text) -> listOf(Piece("", listOf(Part.Text(text)), null)) },
)

class StoreTest {
    @Test
    fun replaceChunksMarksFileIndexed() = runTest {
        val store = newStore(SIG)
        store.upsertFiles(listOf(file("a.txt")))
        val id = store.pending(10).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "a", vec(SIG))))
        assertEquals(1L, store.countsByState()["indexed"])
        assertTrue(store.pending(10).isEmpty())
    }

    @Test
    fun identicalUpsertKeepsIndexed() = runTest {
        val store = newStore(SIG)
        store.upsertFiles(listOf(file("a.txt")))
        val id = store.pending(10).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "a", vec(SIG))))
        store.upsertFiles(listOf(file("a.txt")))
        assertEquals(1L, store.countsByState()["indexed"])
    }

    @Test
    fun newerUpsertMarksPending() = runTest {
        val store = newStore(SIG)
        store.upsertFiles(listOf(file("a.txt")))
        val id = store.pending(10).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "a", vec(SIG))))
        store.upsertFiles(listOf(file("a.txt", modifiedAt = 2L)))
        assertEquals(1L, store.countsByState()["pending"])
    }

    @Test
    fun replaceChunksRejectsOtherSignature() = runTest {
        val store = newStore(Signature("A"))
        store.upsertFiles(listOf(file("a.txt")))
        val id = store.pending(10).single().id
        assertFailsWith<IllegalStateException> {
            store.replaceChunks(id, listOf(Store.NewChunk(0, "", null, vec(Signature("B")))))
        }
    }

    @Test
    fun keywordSearchMatchesWordAndPrefix() = runTest {
        val store = newStore(SIG)
        store.upsertFiles(listOf(file("bill.txt")))
        val fileId = store.pending(10).single().id
        store.replaceChunks(fileId, listOf(Store.NewChunk(0, "", "electricity bill invoice 48213", vec(SIG))))

        val whole = store.keywordSearch("invoice", 10)
        assertEquals(1, whole.size)
        assertEquals(fileId, store.chunk(whole.single())?.fileId)
        assertEquals(1, store.keywordSearch("invo", 10).size)
        assertEquals(1, store.keywordSearch("bill invo", 10).size)
        assertTrue(store.keywordSearch("pizza", 10).isEmpty())
    }

    @Test
    fun keywordSearchRanksByRelevanceNotInsertionOrder() = runTest {
        val store = newStore(SIG)
        store.upsertFiles(listOf(file("old.txt", modifiedAt = 3L), file("new.txt", modifiedAt = 2L)))
        val (old, new) = store.pending(10).map { it.id }
        store.replaceChunks(old, listOf(Store.NewChunk(0, "", "invoice and many other unrelated words about weather and travel plans", vec(SIG))))
        store.replaceChunks(new, listOf(Store.NewChunk(0, "", "invoice invoice invoice", vec(SIG))))
        assertEquals(new, store.chunk(store.keywordSearch("invoice", 10).first())?.fileId)
    }

    @Test
    fun keywordSearchMatchesThroughUnicodePunctuationAndCase() = runTest {
        val store = newStore(SIG)
        store.upsertFiles(listOf(file("a.txt")))
        val id = store.pending(10).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "\u201cLease Agreement\u201d tenant\u2014landlord \u2022Invoice CAF\u00c9", vec(SIG))))
        for (q in listOf("lease", "tenant", "landlord", "invoice", "caf\u00e9", "agreement lease")) {
            assertEquals(1, store.keywordSearch(q, 10).size, q)
        }
    }

    @Test
    fun keywordSearchIgnoresJunkInput() = runTest {
        val store = newStore(SIG)
        store.upsertFiles(listOf(file("bill.txt")))
        val fileId = store.pending(10).single().id
        store.replaceChunks(fileId, listOf(Store.NewChunk(0, "", "invoice", vec(SIG))))
        assertTrue(store.keywordSearch("\"*(:)", 10).isEmpty())
        assertTrue(store.keywordSearch("", 10).isEmpty())
    }

    @Test
    fun removeFilesDropsChunksAndKeywordHits() = runTest {
        val store = newStore(SIG)
        store.upsertFiles(listOf(file("bill.txt")))
        val fileId = store.pending(10).single().id
        store.replaceChunks(fileId, listOf(Store.NewChunk(0, "", "invoice", vec(SIG))))
        store.removeFiles(listOf("content://test/bill.txt"))
        assertEquals(0L, store.chunkCount())
        assertTrue(store.keywordSearch("invoice", 10).isEmpty())
        assertNull(store.file(fileId))
    }

    @Test
    fun useSignatureResetsIndex() = runTest {
        val store = newStore(Signature("A"))
        store.upsertFiles(listOf(file("a.txt"), file("b.txt")))
        val id = store.pending(10).first().id
        store.replaceChunks(id, listOf(Store.NewChunk(0, "", "a", vec(Signature("A")))))
        store.useSignature(Signature("B"))
        assertEquals(0L, store.chunkCount())
        assertEquals(2L, store.countsByState()["pending"])
    }

    @Test
    fun markFailedKeepsPendingUntilThirdAttempt() = runTest {
        val store = newStore(SIG)
        store.upsertFiles(listOf(file("a.txt")))
        val id = store.pending(10).single().id
        store.markFailed(id, 1)
        assertEquals(1L, store.countsByState()["pending"])
        store.markFailed(id, 3)
        assertEquals(1L, store.countsByState()["skipped"])
    }

    @Test
    fun retrySkippedGivesSkippedFilesFreshAttempts() = runTest {
        val store = newStore(SIG)
        store.upsertFiles(listOf(file("a.txt"), file("b.txt")))
        val (a, b) = store.pending(10)
        store.markFailed(a.id, 3)
        store.replaceChunks(b.id, listOf(Store.NewChunk(0, "", "b", vec(SIG))))

        store.retrySkipped()

        val retried = store.pending(10).single()
        assertEquals(a.id, retried.id)
        assertEquals(0, retried.attempts)
        assertEquals(mapOf("indexed" to 1L, "pending" to 1L), store.countsByState())
    }
}

class SearchEngineTest {
    private suspend fun indexedSearch(): SearchEngine {
        val store = newStore()
        val embedder = FakeEmbedder()
        store.upsertFiles(CORPUS.keys.map { file(it) })
        IndexEngine(store, corpusExtractor(), embedder, FakeMonitor(), PROFILE).runBatch()
        return SearchEngine(store, embedder).also { it.reload() }
    }

    @Test
    fun mergedSearchRanksSemanticMatchFirst() = runTest {
        val hits = indexedSearch().search("security deposit for the rental", SearchEngine.Mode.MERGED)
        assertEquals("lease.txt", hits.first().file.name)
    }

    @Test
    fun keywordSearchFindsExactNumber() = runTest {
        val hits = indexedSearch().search("48213", SearchEngine.Mode.KEYWORD)
        assertEquals("bill.txt", hits.first().file.name)
    }

    @Test
    fun kindFilterExcludesOtherKinds() = runTest {
        val hits = indexedSearch().search("security deposit", kinds = setOf(Kind.PHOTO))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun vectorSearchRefusesAnotherModelsIndex() = runTest {
        val store = newStore()
        store.upsertFiles(CORPUS.keys.map { file(it) })
        IndexEngine(store, corpusExtractor(), FakeEmbedder(), FakeMonitor(), PROFILE).runBatch()
        val other = SearchEngine(store, FakeEmbedder(Signature("OTHER/vt70/d256")))
        assertFailsWith<IllegalStateException> { other.search("rental", SearchEngine.Mode.VECTOR) }
        assertFailsWith<IllegalStateException> { other.searchImage(byteArrayOf(1)) }
        assertEquals("bill.txt", other.search("48213", SearchEngine.Mode.KEYWORD).first().file.name)
    }

    @Test
    fun eachFileAppearsAtMostOnce() = runTest {
        val hits = indexedSearch().search("security deposit for the rental")
        assertEquals(hits.size, hits.map { it.fileId }.toSet().size)
    }
}

class IndexEngineTest {
    private fun engine(store: Store, extractor: Extractor = corpusExtractor(), monitor: DeviceMonitor = FakeMonitor(), onFile: (FileMetric) -> Unit = {}) =
        IndexEngine(store, extractor, FakeEmbedder(), monitor, PROFILE, onFile)

    @Test
    fun runBatchIndexesEveryFile() = runTest {
        val store = newStore()
        store.upsertFiles(CORPUS.keys.map { file(it) })
        val result = engine(store).runBatch()
        assertEquals(3, result.processed)
        assertEquals(StopReason.DONE, result.stopReason)
        assertEquals(3L * 2, store.chunkCount())
    }

    @Test
    fun failingFileStaysPendingThenSkipped() = runTest {
        val store = newStore()
        store.upsertFiles(listOf(file("broken.txt")))
        val statesAfterFailure = mutableListOf<Map<String, Long>>()
        val result = engine(store, FakeExtractor(fail = true)) { metric ->
            if (!metric.ok) statesAfterFailure += store.countsByState()
        }.runBatch()
        assertEquals(
            listOf(mapOf("pending" to 1L), mapOf("pending" to 1L), mapOf("skipped" to 1L)),
            statesAfterFailure,
        )
        assertEquals(StopReason.DONE, result.stopReason)
    }

    @Test
    fun attemptIsCountedBeforeProcessingAndErrorsAreCaught() = runTest {
        val store = newStore()
        store.upsertFiles(listOf(file("huge.xlsx")))
        val attemptsSeen = mutableListOf<Int>()
        val crashing = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
                attemptsSeen += store.pending(1).single().attempts
                throw OutOfMemoryError("sharedStrings.xml")
            }
        }
        val result = engine(store, crashing).runBatch()
        assertEquals(listOf(1, 2), attemptsSeen)
        assertEquals(3, result.failed)
        assertEquals(mapOf("skipped" to 1L), store.countsByState())
    }

    @Test
    fun hotDevicePausesBeforeAnyFile() = runTest {
        val store = newStore()
        store.upsertFiles(CORPUS.keys.map { file(it) })
        val monitor = FakeMonitor(DeviceState(8.0, true, 80, Thermal.SEVERE))
        val result = engine(store, monitor = monitor).runBatch(pauseWhenHot = true)
        assertEquals(StopReason.HOT, result.stopReason)
        assertEquals(0, result.processed)
    }

    @Test
    fun unpluggedPausesWhenChargingRequired() = runTest {
        val store = newStore()
        store.upsertFiles(CORPUS.keys.map { file(it) })
        val monitor = FakeMonitor(DeviceState(8.0, false, 80, Thermal.NONE))
        val result = engine(store, monitor = monitor).runBatch(requireCharging = true)
        assertEquals(StopReason.UNPLUGGED, result.stopReason)
        assertEquals(0, result.processed)
    }
}
