package app.retriever.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.db.IndexDb
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class FacadeW5Test {
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    private class Fx(files: List<SourceFile>, pieces: Map<String, List<Piece>> = emptyMap()) {
        val store = newStore(Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens)).also { it.setProfile(PROFILE) }
        val source = FakeSource().apply { next = Changes(files, emptyList(), "tok", isFullScan = true) }
        val factory = FakeFactory()

        @Volatile
        var ready = true

        val r = Retriever(
            store, source, FakeExtractor(pieces), factory, FakeMonitor(), NoHasher, PIXEL, { ready }, { true },
            { _, _ -> }, System::currentTimeMillis, { 0L },
        )

        suspend fun index() {
            r.uiVisible = true
            r.refreshSetUp()
            r.indexBatch(1.minutes)
        }
    }

    private object NoHasher : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    }

    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    private fun names(r: SearchEngine.SearchResult) = r.hits.map { it.file.name }.toSet()

    @Test
    fun parsedFiltersApplyAndFallBackWhenTheyLeaveNothing() = test {
        val f = Fx(listOf(testFile("bill.pdf", Kind.PDF, modifiedAt = now), testFile("bill.txt", Kind.DOC, modifiedAt = now - 40 * day)))
        f.index()

        val pdf = f.r.search("bill pdf")
        assertEquals(setOf("bill.pdf"), names(pdf))
        assertEquals("bill", pdf.parsed!!.text)
        assertEquals(setOf(Kind.PDF), pdf.parsed.kinds)
        assertFalse(pdf.relaxed)

        assertEquals(setOf("bill.pdf"), names(f.r.search("bill today")), "a date filter on the capture time")

        val shots = f.r.search("screenshots bill")
        assertTrue(shots.relaxed, "no screenshot matches, so the kind filter is dropped")
        assertEquals(setOf("bill.pdf", "bill.txt"), names(shots))
        assertEquals(setOf(Kind.SCREENSHOT), shots.parsed!!.kinds)

        val clash = f.r.search("bill pdf", kinds = setOf(Kind.DOC))
        assertTrue(clash.relaxed, "the typed kind and the chip share nothing: the chip stays")
        assertEquals(setOf("bill.txt"), names(clash))

        val browse = f.r.search("pdfs")
        assertEquals(setOf("bill.pdf"), names(browse), "a query that is all filters lists what passes them")
        assertFalse(browse.relaxed)
        assertTrue(browse.vectorLane, "no words left to match by meaning, so the screen says nothing is missing")

        val literal = f.r.search("pdf bill", literal = true)
        assertTrue(literal.parsed!!.kinds.isEmpty())
        assertEquals("pdf bill", literal.parsed.text)

        assertEquals(setOf("bill.pdf"), names(f.r.searchNames("pdfs bil")), "phase one parses too")
    }

    @Test
    fun namesAloneNeverRelaxAndTheRelaxedRetryEmbedsOnce() = test {
        val f = Fx(listOf(testFile("bill.pdf", Kind.PDF, modifiedAt = now)))
        f.index()
        f.r.warmSearch()
        val names = f.r.searchNames("screenshots bill")
        assertTrue(names.hits.isEmpty(), "names under the filter found nothing, and say so instead of dropping it")
        assertFalse(names.relaxed)
        val embedder = f.factory.last!!
        val before = embedder.received.size
        val full = f.r.search("screenshots bill")
        assertTrue(full.relaxed)
        assertEquals(1, embedder.received.size - before, "the run without the filter reuses the query's vector")
    }

    @Test
    fun aQueryOfFiltersOnlyLooksPastTheNewestFilesAndRelaxesWhenEmpty() = test {
        val old = testFile("old.jpg", Kind.PHOTO, modifiedAt = now).copy(takenAt = now - 400 * day)
        val recent = (1..600).map { testFile("new$it.jpg", Kind.PHOTO, modifiedAt = now - it) }
        val f = Fx(recent + old)
        f.index()
        val year = java.time.Instant.ofEpochMilli(now - 400 * day).atZone(java.time.ZoneOffset.UTC).year
        assertEquals(listOf("old.jpg"), f.r.search("photos from $year").hits.map { it.file.name })
        val none = f.r.search("screenshots from $year")
        assertTrue(none.relaxed, "no screenshots then, so the filters go and the note says so")
        assertTrue(none.hits.isNotEmpty())
    }

    @Test
    fun starredFiltersApplyBeforeTheNameCapAndToPhotoSearch() = test {
        val bills = (1..150).map { testFile("invoice $it.txt", modifiedAt = now - it) }
        val f = Fx(bills)
        f.index()
        f.r.warmSearch()
        f.r.setStarred(bills.last().uri, true)
        assertEquals(listOf(bills.last().name), f.r.searchNames("invoice", onlyStarred = true).hits.map { it.file.name })
        assertEquals(listOf(bills.last().name), f.r.search("invoice", onlyStarred = true).hits.map { it.file.name })
        assertTrue(f.r.searchImage(ByteArray(3), onlyStarred = true).hits.all { it.file.uri == bills.last().uri })
    }

    @Test
    fun suggestionsNeverInventContentForAnUnreadFile() = test {
        val f = Fx(listOf(testFile("wifi password.png", Kind.SCREENSHOT)))
        assertTrue(f.r.suggestions().first().isEmpty())
    }

    @Test
    fun nameIndexIsBuiltAtWarmSearchAndFollowsSyncs() = test {
        val gone = testFile("holiday plan.txt")
        val f = Fx(listOf(testFile("passport scan.txt"), gone))
        f.index()
        f.r.warmSearch()
        assertEquals(setOf("passport scan.txt"), names(f.r.searchNames("pasport")), "a typo matches from memory")

        f.source.next = Changes(listOf(testFile("passport renewal.txt")), listOf(gone.uri), "tok2")
        f.r.indexBatch(1.minutes)
        assertEquals(setOf("passport scan.txt", "passport renewal.txt"), names(f.r.searchNames("passport")))
        assertTrue(f.r.searchNames("holiday").hits.isEmpty())
    }

    @Test
    fun warmSearchOpensTheModelSoTheFirstSearchDoesNot() = test {
        val f = Fx(listOf(testFile("a.txt")))
        f.r.uiVisible = true
        f.r.refreshSetUp()
        f.r.warmSearch()
        assertEquals(1, f.factory.opened.size)
        assertTrue(f.r.search("a").vectorLane)
        assertEquals(1, f.factory.opened.size)
    }

    @Test
    fun starsFilterSearchAndFollowTheirFiles() = test {
        val (a, b, c) = listOf("bill one.txt", "bill two.txt", "bill three.txt").map { testFile(it) }
        val f = Fx(listOf(a, b, c))
        f.index()
        assertTrue(f.r.search("bill", onlyStarred = true).hits.isEmpty(), "nothing starred, nothing found")

        f.r.setStarred(a.uri, true)
        f.r.setStarred(b.uri, true)
        f.r.setStarred("content://test/not-indexed", true)
        assertEquals(setOf(a.uri, b.uri), f.r.starred.value)
        assertEquals(setOf("bill one.txt", "bill two.txt"), names(f.r.search("bill", onlyStarred = true)))
        assertEquals(setOf(a.uri, b.uri), f.r.starredFiles().map { it.file.uri }.toSet())

        f.r.setStarred(b.uri, false)
        assertEquals(setOf(a.uri), f.r.starred.value)
        f.r.setStarred(b.uri, true)

        // A sync removes a (trashed): its star hides with it, and comes back when the file does (restored).
        f.source.next = Changes(emptyList(), listOf(a.uri), "tok2")
        f.r.indexBatch(1.minutes)
        assertEquals(setOf(b.uri), f.r.starred.value)
        f.source.next = Changes(listOf(a), emptyList(), "tok3")
        f.r.indexBatch(1.minutes)
        assertEquals(setOf(a.uri, b.uri), f.r.starred.value)
    }

    @Test
    fun homeFacts() = test {
        val shots = (1..12).map { testFile("shot$it.png", Kind.SCREENSHOT, modifiedAt = it.toLong(), folder = "Screenshots") }
        val docs = listOf(
            testFile("old.pdf", Kind.PDF, modifiedAt = 1),
            testFile("new.pdf", Kind.PDF, modifiedAt = 30),
            testFile("notes.txt", Kind.DOC, modifiedAt = 20),
            testFile("plan.txt", Kind.DOC, modifiedAt = 10),
        )
        val big = testFile("film.mp4", Kind.VIDEO, folder = "Movies", size = 300L * 1024 * 1024)
        val f = Fx(shots + docs + big, mapOf("notes.txt" to listOf(textPiece("agenda for monday"))))
        f.index()

        val h = f.r.home().first()
        assertEquals(listOf("new.pdf", "notes.txt", "plan.txt", "old.pdf"), h.newDocs.map { it.name })
        assertEquals((12 downTo 3).map { "shot$it.png" }, h.newShots.map { it.name })
        assertEquals(3L, h.folders)
        assertEquals(1L, h.largeCount)
        assertEquals(big.size, h.largeBytes)
        assertEquals(16L, f.r.counts().first().progress.nameOnly, "every file but notes.txt is read by name only")

        // While a file waits, the count is not taken again.
        f.store.removeFiles(listOf(big.uri))
        f.store.upsertFiles(listOf(testFile("later.txt")))
        assertEquals(15L, f.r.counts().first().progress.nameOnly)
    }

    @Test
    fun suggestionsUseReadContentAndRespectTrialScope() = test {
        val f = Fx(listOf(testFile("one.txt"), testFile("two.txt")))
        f.store.upsertFiles(listOf(testFile("one.txt"), testFile("two.txt")))
        val ids = f.store.pending(2).map { it.id }
        f.store.replaceChunks(ids[0], listOf(Store.NewChunk(1, "", "orchard cedar jasmine marigold", null)))
        f.store.replaceChunks(ids[1], listOf(Store.NewChunk(1, "", "orchard cedar jasmine marigold", null)))
        assertTrue(f.r.suggestions(setOf(ids[0])).first().isEmpty(), "terms must recur in two selected files")
        assertEquals(3, f.r.suggestions().first().size)
        assertTrue(f.r.suggestions(emptySet()).first().isEmpty())
    }

    @Test
    fun suggestionsComeFromTheNewestFilesOfALargeLibrary() = test {
        val f = Fx(emptyList())
        val files = List(5_000) { testFile("note$it.txt", modifiedAt = it.toLong()) }
        f.store.upsertFiles(files)
        val newest = files.size - ContentSuggestions.FILES
        f.store.inTransaction {
            files.forEachIndexed { i, file ->
                // Read over the whole library, the oldest files' longer words would win.
                val text = when {
                    i < ContentSuggestions.FILES -> "electricity reconciliation quarterly statement"
                    i < newest -> "4539,4000;1234"
                    else -> "harbour lantern meadow pebble"
                }
                f.store.replaceChunks(f.store.fileByUri(file.uri)!!.id, listOf(Store.NewChunk(1, "", text, null)))
            }
        }
        val terms = f.r.suggestions().first()
        assertEquals(3, terms.size, terms.toString())
        assertTrue(terms.all { t -> t.split(' ').all { it in setOf("harbour", "lantern", "meadow", "pebble") } }, terms.toString())
    }

    @Test
    fun collectorsOfTheSameSuggestionsShareOneReadAndReleaseIt() = test {
        var reads = 0
        val raw = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { IndexDb.Schema.create(it) }
        val spy = object : SqlDriver by raw {
            override fun <R> executeQuery(
                identifier: Int?,
                sql: String,
                mapper: (SqlCursor) -> QueryResult<R>,
                parameters: Int,
                binders: (SqlPreparedStatement.() -> Unit)?,
            ): QueryResult<R> = raw.executeQuery(identifier, sql, mapper, parameters, binders).also { if ("MAX(c.id)" in sql) reads++ }
        }
        val store = Store(spy).also { it.resetForModel(SIG) }
        val r =
            Retriever(store, FakeSource(), FakeExtractor(), FakeFactory(), FakeMonitor(), NoHasher, PIXEL, {
                true
            }, { true }, { _, _ -> }, System::currentTimeMillis, { 0L })
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.Default) { r.suggestions().collect { first.complete(Unit) } }
        first.await()
        val b = launch(Dispatchers.Default) { r.suggestions().collect { second.complete(Unit) } }
        second.await()
        assertEquals(1, reads, "the second collector shares the first one's read")
        a.cancelAndJoin()
        b.cancelAndJoin()
        // A collector back soon (a rotation, a quick trip away) gets the last terms without a recount.
        delay(500)
        r.suggestions().first()
        assertEquals(1, reads, "a collector back within the grace shares the read")
        // The sharing stops that long after its last collector leaves; from then on a new collector reads afresh.
        delay(Retriever.SUGGESTIONS_KEPT_MS + 1_000)
        r.suggestions().first()
        assertEquals(2, reads)
    }

    @Test
    fun turningRememberSearchesOffClearsAndStopsTheHistory() = test {
        val f = Fx(emptyList())
        f.r.recordQuery("wifi password")
        assertEquals(listOf("wifi password"), f.r.recentQueries())
        f.r.updatePrefs { it.copy(rememberSearches = false) }
        assertTrue(f.r.recentQueries().isEmpty())
        f.r.recordQuery("boarding pass")
        assertTrue(f.r.recentQueries().isEmpty())
        f.r.updatePrefs { it.copy(rememberSearches = true) }
        f.r.recordQuery("boarding pass")
        assertEquals(listOf("boarding pass"), f.r.recentQueries())
    }

    @Test
    fun aModelDeletedAfterSetupLeavesNamesAndWords() = test {
        val f = Fx(listOf(testFile("lease.txt")), mapOf("lease.txt" to listOf(textPiece("wombat clause"))))
        f.index()
        assertTrue(f.r.search("wombat").vectorLane)
        val opens = f.factory.opened.size

        f.ready = false
        f.r.refreshSetUp()
        assertEquals(true, f.r.setUp.value)
        assertTrue(f.r.modelMissing.value)
        val words = f.r.search("wombat")
        assertFalse(words.vectorLane, "no meaning lane without the file")
        assertEquals(listOf("lease.txt"), words.hits.map { it.file.name })

        f.source.next = Changes(listOf(testFile("deed.txt")), emptyList(), "tok2")
        f.r.indexBatch(1.minutes)
        assertEquals(opens, f.factory.opened.size, "batches don't open the model")
        assertEquals(2L, f.store.countsByState()[FileState.INDEXED], "new files are read without the missing model")
        assertEquals(listOf("deed.txt"), f.r.search("deed").hits.map { it.file.name }, "but its name is found")

        f.ready = true
        f.r.refreshSetUp()
        assertFalse(f.r.modelMissing.value)
        f.r.warmSearch() // the search bar's focus; a search itself never opens the model
        assertTrue(f.r.search("wombat").vectorLane)
    }
}
