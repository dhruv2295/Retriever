package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** Sample mode limits what is read, never what is kept (Pixel finding #1: turning it on deleted indexed files). */
class SampleModeTest {
    private val files = listOf(Kind.DOC, Kind.PDF, Kind.SCREENSHOT).flatMap { kind ->
        (1..4).map { testFile("${kind.name.lowercase()}$it", kind, modifiedAt = it.toLong()) }
    }
    private val extractor = FakeExtractor(files.associate { it.name to listOf(textPiece("inside ${it.name}")) })

    private suspend fun Store.batch() = IndexEngine(this, extractor, FakeEmbedder(), FakeMonitor(), settingsOf()).runBatch(9.minutes, requireCharging = false)

    private fun Store.state(name: String) = fileByUri("content://test/$name")?.state

    @Test
    fun turningSampleOnKeepsEveryIndexedFileAndItsChunks() = runTest {
        val store = newStore()
        val source = FakeSource().apply { next = Changes(files, emptyList(), "tok1", isFullScan = true) }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        sync.sync()
        store.batch()
        val chunks = store.chunkCount()
        assertEquals(12L, store.countsByState()[FileState.INDEXED])

        store.updatePrefs { it.copy(sampleSize = 2) }
        clock = 2 * 24 * 3_600_000L // a full scan, the case that used to trim
        val result = sync.sync()

        assertEquals(0, result.removed)
        assertEquals(12L, store.countsByState()[FileState.INDEXED])
        assertEquals(chunks, store.chunkCount())
        assertEquals(0, store.batch().processed)
    }

    @Test
    fun aFreshLibraryReadsOnlyTheNewestPerKindAndTurningSampleOffReadsTheRest() = runTest {
        val store = newStore()
        store.updatePrefs { it.copy(sampleSize = 2) }
        val source = FakeSource().apply { next = Changes(files, emptyList(), "tok1", isFullScan = true) }
        var clock = 0L
        val sync = LibrarySync(store, source, now = { clock })
        sync.sync()
        store.batch()

        assertEquals(setOf("doc4", "doc3", "pdf4", "pdf3", "screenshot4", "screenshot3"), extractor.names.toSet())
        assertEquals(6, extractor.names.size)
        assertEquals(FileState.PENDING, store.state("doc1"))
        assertEquals(listOf("doc1"), SearchEngine(store, FakeEmbedder()).search("doc1", SearchEngine.Mode.KEYWORD).hits.map { it.file.name })

        store.updatePrefs { it.copy(sampleSize = null) }
        clock = 3_600_000L
        source.next = Changes(emptyList(), emptyList(), "tok2", isFullScan = false)
        sync.sync()
        assertEquals("tok1", source.calls.last(), "turning sample off needs no full rescan")
        store.batch()

        assertEquals(12, extractor.names.size)
        assertEquals(12L, store.countsByState()[FileState.INDEXED])
    }
}
