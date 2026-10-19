package app.retriever.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** "Stop searching" a folder or a kind acts at once, paused or not, through Retriever.updatePrefs alone. */
class ExclusionTest {
    private val bank = testFile("ledger.txt", folder = "Docs/Bank")
    private val banking = testFile("plan.txt", folder = "Docs/Banking")
    private val report = testFile("report.pdf", Kind.PDF)

    private class Fx(val files: List<SourceFile>, pieces: Map<String, List<Piece>>) {
        val store = newStore(Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens)).also { it.setProfile(PROFILE) }
        val source = FakeSource().apply { next = Changes(files, emptyList(), "tok", isFullScan = true) }
        val r = Retriever(
            store, source, FakeExtractor(pieces), FakeFactory(), FakeMonitor(), NoHasher, PIXEL, { true }, { true },
            { _, _ -> }, System::currentTimeMillis, { 0L },
        )

        suspend fun indexed() = apply {
            r.refreshSetUp()
            r.indexBatch(1.minutes)
            r.warmSearch()
        }
    }

    private object NoHasher : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    }

    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    private fun Fx.uris(r: SearchEngine.SearchResult) = r.hits.map { it.file.uri }.toSet()

    private suspend fun folders() = Fx(
        listOf(bank, banking),
        mapOf(bank.name to listOf(textPiece("monthly statement balance")), banking.name to listOf(textPiece("statement plan"))),
    ).indexed()

    @Test
    fun excludingAFolderRemovesItWhilePaused() = test {
        val f = folders()
        f.r.setStarred(bank.uri, true)
        assertTrue(bank.uri in f.uris(f.r.search("statement")), "found by meaning and keyword before")
        f.r.pause()
        val scans = f.source.calls.size

        f.r.updatePrefs { it.copy(excludedFolders = it.excludedFolders + "Docs/Bank") }

        assertEquals(scans, f.source.calls.size, "no scan ran")
        assertNull(f.store.fileByUri(bank.uri))
        assertEquals(
            listOf(banking.uri),
            f.store.keywordSearch("statement", 10).map { f.store.chunk(it.chunkId)!!.fileId }
                .map { f.store.file(it)!!.uri },
            "keyword lane: a sibling folder sharing the prefix stays",
        )
        assertTrue(f.r.searchNames("ledger").hits.isEmpty(), "names lane, from memory")
        assertFalse(bank.uri in f.uris(f.r.search("statement")), "meaning lane")
        assertTrue(f.r.starred.value.isEmpty(), "the star hides with its file")
    }

    /** The copy promises that removing access drops results while removing the download keeps them; this is the access half. */
    @Test
    fun accessThatGoesAwayDropsTheFilesFromWordsAndNames() = test {
        val f = folders()
        assertTrue(bank.uri in f.uris(f.r.search("statement")))

        f.source.next = Changes(listOf(banking), emptyList(), "tok2", isFullScan = true)
        f.r.indexBatch(1.minutes)

        assertNull(f.store.fileByUri(bank.uri))
        assertEquals(setOf(banking.uri), f.uris(f.r.search("statement")))
        assertTrue(f.r.searchNames("ledger").hits.isEmpty())
    }

    @Test
    fun reIncludingAFolderBringsTheStarredFileBackAfterTheNextSync() = test {
        val f = folders()
        f.r.setStarred(bank.uri, true)
        f.r.pause()
        f.r.updatePrefs { it.copy(excludedFolders = it.excludedFolders + "Docs/Bank") }

        // Put back before any sync ran: the stored excludes still match, yet the dropped files must be scanned for.
        f.r.updatePrefs { it.copy(excludedFolders = it.excludedFolders - "Docs/Bank", paused = false) }
        f.r.indexBatch(1.minutes)

        assertNull(f.source.calls.last(), "a full scan, since the store dropped what an incremental one can't see")
        assertNotNull(f.store.fileByUri(bank.uri))
        assertEquals(setOf(bank.uri), f.r.starred.value)
        assertEquals(setOf(bank.uri), f.uris(f.r.searchNames("ledger")))
    }

    @Test
    fun turningAKindOffDropsItsTextButKeepsTheName() = test {
        val f = Fx(listOf(report), mapOf(report.name to listOf(textPiece("oldtext quarterly")))).indexed()
        assertEquals(1, f.store.keywordSearch("oldtext", 10).size)
        f.r.pause()

        f.r.updatePrefs { it.copy(kinds = it.kinds - Kind.PDF) }

        assertEquals(0, f.store.keywordSearch("oldtext", 10).size)
        assertEquals(0, f.store.vectorCount(), "its vectors went with the text")
        assertEquals(FileState.INDEXED, f.store.fileByUri(report.uri)!!.state)
        assertEquals(setOf(report.uri), f.uris(f.r.searchNames("report")))
        assertFalse(report.uri in f.uris(f.r.search("oldtext quarterly")), "meaning lane")
    }

    @Test
    fun turningAKindBackOnRequeuesItWithoutASync() = test {
        val f = Fx(listOf(report), mapOf(report.name to listOf(textPiece("oldtext quarterly")))).indexed()
        f.r.pause()
        f.r.updatePrefs { it.copy(kinds = it.kinds - Kind.PDF) }
        val scans = f.source.calls.size

        f.r.updatePrefs { it.copy(kinds = it.kinds + Kind.PDF) }

        assertEquals(FileState.PENDING, f.store.fileByUri(report.uri)!!.state)
        assertEquals(scans, f.source.calls.size)
    }
}
