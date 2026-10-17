package app.retriever

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.retriever.core.Backend
import app.retriever.core.Changes
import app.retriever.core.ContentHasher
import app.retriever.core.DeviceMonitor
import app.retriever.core.DeviceProbe
import app.retriever.core.DeviceProfile
import app.retriever.core.DeviceState
import app.retriever.core.Embedder
import app.retriever.core.EmbedderFactory
import app.retriever.core.Extractor
import app.retriever.core.FileSource
import app.retriever.core.Kind
import app.retriever.core.ModelBuild
import app.retriever.core.ModelVariant
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.Platform
import app.retriever.core.Prefix
import app.retriever.core.ReadExtent
import app.retriever.core.Retriever
import app.retriever.core.Signature
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.core.Thermal
import app.retriever.core.Tier
import app.retriever.core.cutShort
import app.retriever.core.db.IndexDb
import android.content.Intent
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.fail
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes

/** A real Retriever over an in-memory store with no model, so search answers from file names. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchViewModelTest {
    // Long PDF names rank below the short photo names, so a mixed top 60 holds few or no PDFs.
    private val pdfs = (1..3).map { file("bill from the electricity board for month $it.pdf", Kind.PDF) }
    private val photos = (1..120).map { file("bill $it.jpg", Kind.PHOTO) }

    private fun file(name: String, kind: Kind) = SourceFile("content://test/$name", name, "", "application/octet-stream", kind, 100, 1)

    private val retriever = Retriever(
        store = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { IndexDb.Schema.create(it); Store(it) },
        source = object : FileSource {
            override suspend fun changes(since: String?) = Changes(pdfs + photos, emptyList(), "tok", isFullScan = true)
        },
        extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = emptyList()
        },
        factory = object : EmbedderFactory {
            override fun backends(build: ModelBuild) = listOf(Backend.CPU)
            override fun open(profile: DeviceProfile, build: ModelBuild): Embedder = error("no model in this test")
        },
        monitor = object : DeviceMonitor {
            override fun state() = DeviceState(8.0, true, 80, Thermal.NONE)
        },
        hasher = object : ContentHasher {
            override suspend fun quickHash(uri: String, size: Long): String? = null
            override suspend fun fullHash(uri: String): String? = null
        },
        probe = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 8.0, false),
        modelReady = { false },
        platformReady = { true },
        log = { _, _ -> },
        now = System::currentTimeMillis,
        zoneOffsetMs = { 0L },
    )

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun chipSearchesInsideItsKind() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        val mixed = vm.ui.first { it.hits?.size == 60 }.hits!!
        vm.setChip(Kind.PDF)
        val pdfHits = vm.ui.first { ui -> ui.chip == Kind.PDF && !ui.searching && ui.hits.orEmpty().all { it.file.kind == Kind.PDF } }.hits!!
        assertEquals(pdfs.map { it.name }.toSet(), pdfHits.map { it.file.name }.toSet())
        // A filter over the mixed results could not have found them all.
        assertTrue(mixed.count { it.file.kind == Kind.PDF } < pdfs.size)
    }

    /**
     * A Retriever with a fake model, read without the deepen pass: IMG_0 and IMG_1 sit at cosine 0.9 with any text
     * query, the other photos at 0.3; "notes.txt" leaves 4 chunks for the deepen pass, "big log.txt" was cut short.
     */
    private fun withModel(beforeOpen: () -> Unit = {}): Retriever {
        val shots = (0 until 6).map { file("IMG_$it.jpg", Kind.PHOTO) }
        val docs = listOf(file("notes.txt", Kind.DOC), file("big log.txt", Kind.DOC))
        val store = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { IndexDb.Schema.create(it); Store(it) }
        store.setProfile(DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 5, false, false))
        fun axis(i: Int, cos: Float) = FloatArray(768).also { it[0] = cos; it[i] = sqrt(1 - cos * cos) }
        val embedder = object : Embedder {
            override val signature = Signature("M440/test/vt70/d256/t512") // the measured floor applies
            override val backend = Backend.CPU

            override suspend fun embedRaw(parts: List<Part>): FloatArray = when (val p = parts.single()) {
                is Part.Image -> p.jpeg[0].toInt().let { i -> axis(i + 1, if (i < 2) 0.9f else 0.3f) }
                is Part.Text -> if (p.text.startsWith(Prefix.query(""))) FloatArray(768).also { it[0] = 1f } else axis(200 + (p.text.hashCode() and 31), 0f)
                else -> error("no audio here")
            }

            override fun close() {}
        }
        return Retriever(
            store = store,
            source = object : FileSource {
                override suspend fun changes(since: String?) = Changes(shots + docs, emptyList(), "tok", isFullScan = true)
            },
            extractor = object : Extractor {
                override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = when (file.name) {
                    "notes.txt" -> List(12) { Piece("", listOf(Part.Text("bill line $it")), "bill line $it") }
                    "big log.txt" -> listOf(Piece("", listOf(Part.Text("bill total")), "bill total"), cutShort())
                    else -> listOf(Piece("", listOf(Part.Image(byteArrayOf(file.name.filter(Char::isDigit).toByte()))), null))
                }
            },
            factory = object : EmbedderFactory {
                override fun backends(build: ModelBuild) = listOf(Backend.CPU)
                override fun open(profile: DeviceProfile, build: ModelBuild): Embedder = embedder.also { beforeOpen() }
            },
            monitor = object : DeviceMonitor {
                override fun state() = DeviceState(8.0, true, 80, Thermal.NONE)
            },
            hasher = object : ContentHasher {
                override suspend fun quickHash(uri: String, size: Long): String? = null
                override suspend fun fullHash(uri: String): String? = null
            },
            probe = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 8.0, false),
            modelReady = { true },
            platformReady = { true },
            log = { _, _ -> },
            now = System::currentTimeMillis,
            zoneOffsetMs = { 0L },
        )
    }

    @Test fun resultKeepsTheEnginesStrongCount() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = SearchViewModel(r) { null }
        vm.setQuery("dog")
        val ui = vm.ui.first { it.hitsFor == "dog" && !it.searching }
        assertTrue(ui.vectorLane)
        // The relevance rule ships off (RELEVANCE_RULE_ENABLED), so every hit is strong; the two close photos lead.
        assertTrue(ui.hits!!.size > 2)
        assertEquals(ui.hits.size, ui.strongCount)
        assertEquals(listOf("IMG_0.jpg", "IMG_1.jpg"), ui.hits.take(2).map { it.file.name }.sorted())
        assertEquals(r.search("dog", limit = 60).strongCount, ui.strongCount)
    }

    @Test fun resultCarriesReadExtentsForTheHitsShown() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = SearchViewModel(r) { null }
        vm.setQuery("bill")
        // The read notes join the rows a moment after them.
        val ui = vm.ui.first { it.hitsFor == "bill" && !it.searching && it.extents.isNotEmpty() }
        val byName = ui.hits!!.associate { it.file.name to it.fileId }
        assertEquals(
            mapOf(byName.getValue("notes.txt") to ReadExtent.DEEPENING, byName.getValue("big log.txt") to ReadExtent.FIRST_PART),
            ui.extents,
        )
    }

    @Test fun aSearchByPhotoHasNoLessLikelyTail() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = SearchViewModel(r) { byteArrayOf(0) }
        vm.findSimilar(file("IMG_0.jpg", Kind.PHOTO)).join()
        val ui = vm.ui.first { it.similar != null && !it.searching && it.hits != null }
        assertTrue(ui.hits!!.size > 2)
        assertEquals(ui.hits.size, ui.strongCount)
    }

    @Test fun aSearchByPhotoAfterWordsIsANewAnswer() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { ByteArray(1) }
        vm.setQuery("bill")
        val words = vm.ui.first { it.hitsFor == "bill" && !it.searching }.answer
        vm.findSimilar(photos.first()).join()
        // Times out unless the photo's hits arrive as a new answer.
        vm.ui.first { it.similar != null && it.answer > words }
    }

    @Test fun aSupersededSearchLeavesNoExtentsForHitsNotShown() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = SearchViewModel(r) { null }
        vm.setQuery("total")
        assertTrue(vm.ui.first { it.hitsFor == "total" && !it.searching && it.extents.isNotEmpty() }.extents.isNotEmpty())
        // Photos only now: the documents' read notes from the answer before must not linger.
        vm.setChip(Kind.PHOTO)
        val ui = vm.ui.first { it.chip == Kind.PHOTO && !it.searching && it.hits.orEmpty().all { h -> h.file.kind == Kind.PHOTO } }
        assertTrue(ui.hits!!.isNotEmpty())
        assertEquals(emptyMap<Long, ReadExtent>(), ui.extents)
    }

    @Test fun aWordsOnlyPhoneIsToldSo() = runTest {
        val small = Retriever(
            store = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).let { IndexDb.Schema.create(it); Store(it) },
            source = object : FileSource {
                override suspend fun changes(since: String?) = Changes(emptyList(), emptyList(), "tok", isFullScan = true)
            },
            extractor = object : Extractor {
                override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = emptyList()
            },
            factory = object : EmbedderFactory {
                override fun backends(build: ModelBuild) = listOf(Backend.CPU)
                override fun open(profile: DeviceProfile, build: ModelBuild): Embedder = error("no model on this phone")
            },
            monitor = object : DeviceMonitor {
                override fun state() = DeviceState(3.0, true, 80, Thermal.NONE)
            },
            hasher = object : ContentHasher {
                override suspend fun quickHash(uri: String, size: Long): String? = null
                override suspend fun fullHash(uri: String): String? = null
            },
            probe = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 3.0, false),
            modelReady = { false },
            platformReady = { true },
            log = { _, _ -> },
            now = System::currentTimeMillis,
            zoneOffsetMs = { 0L },
        )
        assertTrue(small.textOnly)
        assertTrue(SearchViewModel(small) { null }.ui.value.textOnly)
        assertFalse(SearchViewModel(retriever) { null }.ui.value.textOnly)
    }

    @Test fun hitsSayWhichWordsTheyAnswerAndClearingTheWordsDropsTheFilter() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        vm.setChip(Kind.PDF)
        assertEquals("bill", vm.ui.first { it.hits != null && !it.searching }.hitsFor)
        vm.setQuery("")
        assertEquals(null, vm.ui.value.chip)
        // An empty field that stays empty (the screen coming back) keeps a filter picked since.
        vm.setChip(Kind.PHOTO)
        vm.setQuery("")
        assertEquals(Kind.PHOTO, vm.ui.value.chip)
    }

    @Test fun openingAHitAfterTypingRecordsTheQueryOnce() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        val hit = vm.ui.first { it.hits?.size == 60 }.hits!!.first()
        vm.opened(hit)
        assertEquals(listOf("bill"), vm.ui.first { it.recent.isNotEmpty() }.recent)
        vm.opened(hit)
        advanceUntilIdle()
        assertEquals(listOf("bill"), retriever.recentQueries())
    }

    @Test fun openingAHitDuringAPhotoSearchRecordsNothing() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { ByteArray(1) }
        vm.setQuery("bill")
        val hit = vm.ui.first { it.hits?.size == 60 }.hits!!.first()
        vm.findSimilar(photos.first()).join()
        assertTrue(vm.ui.value.similar != null)
        vm.opened(hit)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), retriever.recentQueries())
    }

    @Test fun openingAHitWithABlankQueryRecordsNothing() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        val hit = vm.ui.first { it.hits?.size == 60 }.hits!!.first()
        vm.setQuery("   ")
        vm.opened(hit)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), retriever.recentQueries())
    }

    @Test fun recordsOnlyOnSubmitAndKeepsHistoryInTheStore() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        advanceTimeBy(1_000)
        vm.ui.first { it.hits != null }
        assertEquals(emptyList<String>(), retriever.recentQueries())

        vm.submit()
        vm.ui.first { "bill" in it.recent }

        val again = SearchViewModel(retriever) { null }
        assertEquals(listOf("bill"), again.ui.first { it.recent.isNotEmpty() }.recent)

        again.removeRecent("bill").join()
        assertEquals(emptyList<String>(), again.ui.value.recent)
        assertEquals(emptyList<String>(), retriever.recentQueries())
    }

    // The IO work of a search runs on real threads. Sleeping, not delaying, keeps the test clock from skipping ahead.
    private fun TestScope.until(what: String, cond: () -> Boolean) {
        repeat(300) {
            testScheduler.runCurrent()
            if (cond()) return
            Thread.sleep(10)
        }
        fail("never: $what")
    }

    @Test fun theNamesAnswerFirstAndTheFullOneRefreshesInPlace() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("bill")
        advanceTimeBy(40)
        until("names") { vm.ui.value.hits != null }
        val names = vm.ui.value
        assertTrue(names.nameOnly)
        assertTrue(names.searching)
        assertEquals("bill", names.hitsFor)
        advanceTimeBy(300)
        until("full") { !vm.ui.value.nameOnly && !vm.ui.value.searching }
        // The same answer, so the screen stays where the person scrolled to.
        assertEquals(names.answer, vm.ui.value.answer)
        // A new word, a kind, and a retry are each a new answer.
        vm.setChip(Kind.PDF)
        until("chip") { vm.ui.value.answer == names.answer + 1 }
        vm.setQuery("bill from")
        advanceTimeBy(300)
        until("words") { vm.ui.value.answer == names.answer + 2 && vm.ui.value.hitsFor == "bill from" }
    }

    @Test fun focusReadiesSearchAndTypingKeepsTheDeviceBusyForAMoment() = runTest {
        val busy = mutableListOf<Boolean>()
        var warmed = 0
        val vm = SearchViewModel(retriever, setBusy = { busy += it }, warm = { warmed++ }) { null }
        vm.setFocused(true)
        until("warm") { warmed == 1 }
        // Focus alone, e.g. looking through results with the bar open, leaves reading alone.
        assertEquals(emptyList<Boolean>(), busy)
        vm.setQuery("b")
        assertEquals(true, busy.last())
        vm.setQuery("bi")
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(true, busy.last())
        advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(false, busy.last())
        vm.setScrolling(true)
        assertEquals(true, busy.last())
        vm.setScrolling(false)
        assertEquals(false, busy.last())
    }

    @Test fun noNameMatchesYetIsNotShownAsNoMatches() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("zebra")
        advanceTimeBy(60)
        Thread.sleep(200)
        testScheduler.runCurrent()
        // The names found nothing; that is not the answer, so nothing is shown and the search goes on.
        assertEquals(null, vm.ui.value.hits)
        assertTrue(vm.ui.value.searching)
        advanceTimeBy(300)
        until("full") { vm.ui.value.hits != null && !vm.ui.value.searching }
        assertFalse(vm.ui.value.nameOnly)
    }

    @Test fun aWordsOnlyAnswerWhileTheModelOpensIsAskedAgain() = runTest {
        var hold: CountDownLatch? = null
        val r = withModel { hold?.await() }
        r.indexBatch(1.minutes, deepen = false)
        r.release()
        hold = CountDownLatch(1)
        // The model opening on focus holds its lock for seconds; a search meanwhile answers from words.
        val warming = Thread { kotlinx.coroutines.runBlocking { r.warmSearch() } }.apply { start() }
        Thread.sleep(200)
        val vm = SearchViewModel(r) { null }
        vm.setQuery("dog")
        advanceTimeBy(300)
        until("words only") { vm.ui.value.hitsFor == "dog" && !vm.ui.value.searching && !vm.ui.value.vectorLane }
        val answer = vm.ui.value.answer
        hold!!.countDown()
        warming.join()
        advanceTimeBy(2_100)
        until("meaning") { vm.ui.value.vectorLane && !vm.ui.value.searching }
        // The same question answered better: the screen keeps its place.
        assertEquals(answer, vm.ui.value.answer)
    }

    @Test fun aSearchHandedInFromOutsideRuns() = runTest {
        retriever.indexBatch(1.minutes)
        LaunchQuery.consume()
        val vm = SearchViewModel(retriever) { null }
        LaunchQuery.from(Intent().putExtra(LaunchQuery.EXTRA, " bill "))
        until("launch") { vm.ui.value.launched == 1 }
        assertEquals("bill", vm.ui.value.query)
        assertEquals(null, LaunchQuery.pending.value)
        advanceTimeBy(1_000)
        until("answer") { vm.ui.value.hitsFor == "bill" && !vm.ui.value.nameOnly }
    }

    @Test fun recentSearchesReachTheLauncherOnlyWhileRemembered() = runTest {
        retriever.indexBatch(1.minutes)
        val pushed = mutableListOf<List<String>>()
        val vm = SearchViewModel(retriever, onRecent = { pushed += it }) { null }
        vm.submit("bill")
        until("pushed") { pushed.isNotEmpty() }
        assertEquals(listOf(listOf("bill")), pushed)
        vm.clearRecent().join()
        assertEquals(emptyList<String>(), pushed.last())
        retriever.updatePrefs { it.copy(rememberSearches = false) }
        val before = pushed.size
        vm.submit("water")
        advanceTimeBy(1_000)
        Thread.sleep(200)
        testScheduler.runCurrent()
        assertEquals(before, pushed.size)
    }

    @Test fun starringIsKeptAndTheStarredChipFilters() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setStarred(photos[3], true).join()
        until("starred") { photos[3].uri in vm.ui.value.starredUris }
        assertEquals(1, vm.ui.value.starred)
        vm.setQuery("bill")
        vm.setOnlyStarred(true)
        advanceTimeBy(1_000)
        until("only") { vm.ui.value.hitsFor == "bill" && vm.ui.value.hits?.size == 1 && !vm.ui.value.nameOnly }
        assertEquals(photos[3].name, vm.ui.value.hits!!.single().file.name)
        vm.setStarred(photos[3], false).join()
        until("unstarred") { vm.ui.value.starredUris.isEmpty() }
        // Clearing the words drops the filter, as it does the kind.
        vm.setQuery("")
        assertFalse(vm.ui.value.onlyStarred)
    }

    @Test fun whatWasUnderstoodCanBeSearchedAsTyped() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = SearchViewModel(retriever) { null }
        vm.setQuery("screenshots bill")
        advanceTimeBy(1_000)
        // No screenshots here, so the kind was dropped and the words searched alone.
        until("relaxed") { vm.ui.value.relaxed && !vm.ui.value.nameOnly && vm.ui.value.hits.orEmpty().isNotEmpty() }
        assertEquals(listOf("Screenshots"), vm.ui.value.parsed!!.understood)
        vm.searchWordsAsTyped()
        advanceTimeBy(1_000)
        until("literal") { vm.ui.value.literal && !vm.ui.value.relaxed && vm.ui.value.parsed?.understood == emptyList<String>() && !vm.ui.value.nameOnly }
        // New words are understood afresh.
        vm.setQuery("screenshots bill 1")
        assertFalse(vm.ui.value.literal)
    }

    @Test fun homeFollowsTheIndexAndAccess() = runTest {
        retriever.indexBatch(1.minutes)
        var access = true
        val vm = SearchViewModel(retriever, { access }) { null }
        val ui = vm.ui.first { it.home?.newDocs?.size == 3 && it.counts != null }
        assertEquals(setOf(Kind.PDF), ui.home!!.newDocs.map { it.kind }.toSet())
        assertEquals(0, ui.starred)
        assertFalse(ui.paused)
        assertTrue(ui.hasAccess)
        // Access is asked again on return to the screen, not once at start.
        access = false
        vm.checkAccess()
        assertFalse(vm.ui.value.hasAccess)
        retriever.pause()
        assertTrue(vm.ui.first { it.paused }.paused)
    }
}
