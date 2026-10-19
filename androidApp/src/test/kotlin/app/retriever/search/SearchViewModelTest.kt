package app.retriever.search

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.core.Backend
import app.retriever.core.CoreDispatchers
import app.retriever.core.DeviceProfile
import app.retriever.core.Embedder
import app.retriever.core.Kind
import app.retriever.core.ModelVariant
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.Prefix
import app.retriever.core.ReadExtent
import app.retriever.core.Retriever
import app.retriever.core.SearchEngine
import app.retriever.core.SearchPhase
import app.retriever.core.Signature
import app.retriever.core.Tier
import app.retriever.core.answerFor
import app.retriever.core.cutShort
import app.retriever.core.showsEmpty
import app.retriever.nav.DocRoute
import app.retriever.nav.Route
import app.retriever.testFile
import app.retriever.testRetriever
import app.retriever.ui.Format
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.minutes

/** A real Retriever over an in-memory store, the ViewModel and the store on one test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SearchViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()
    private val core = CoreDispatchers(dispatcher)

    // Long PDF names rank below the short photo names, so a mixed top 60 holds few or no PDFs.
    private val pdfs = (1..3).map { testFile("bill from the electricity board for month $it.pdf", Kind.PDF) }
    private val photos = (1..120).map { testFile("bill $it.jpg", Kind.PHOTO) }

    /** No model: search answers from file names. */
    private val retriever = testRetriever(pdfs + photos, dispatchers = core)
    private val launchQuery = LaunchQuery()

    /** [photo] is what any picked photo reads as; null, unreadable. */
    private fun vm(
        r: Retriever = retriever,
        photo: ByteArray? = null,
        reader: PhotoReader = PhotoReader { uri, label -> photo?.let { PickedPhoto(Similar(uri, label), it) } },
    ) = SearchViewModel(r, launchQuery, RecentShortcuts(app), reader)

    /** The answer to the question asked now, as the screen would show it. */
    private val SearchUi.result get() = phase.answerFor(question)
    private val SearchUi.hits get() = result?.hits

    /** The whole search for the question asked now has answered: neither the names answer nor a words-only one asked again. */
    private val SearchUi.done get() = phase.let { it is SearchPhase.Results && it.question == question && it.complete }

    /** What the screen renders as "nothing found". */
    private val SearchUi.empty get() = phase.showsEmpty(question)

    /** Every state [vm] emits from now on, as it emits it. */
    private fun TestScope.record(vm: SearchViewModel): List<SearchUi> =
        mutableListOf<SearchUi>().also { seen -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.ui.collect { seen += it } } }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun scopeKeepsEverySearchInsideThePlannedFiles() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setQuery("bill")
        val everything = vm.ui.first { it.hits?.size == 60 }.hits!!
        val planned = everything.take(3).map { it.fileId }
        vm.setScope(planned)
        // Both the names answer and the full one carry the ids: whichever answers, nothing else is in it.
        val names = vm.ui.first { it.scope == planned && it.hits != null && it.question.scope == planned }
        assertEquals(planned.toSet(), names.hits!!.map { it.fileId }.toSet())
        val full = vm.ui.first { it.question.scope == planned && it.done }
        assertEquals(planned.toSet(), full.hits!!.map { it.fileId }.toSet())
        assertEquals("Closest in these 3 files", Format.closestIn(planned.size))
        vm.setScope(null)
        assertEquals(60, vm.ui.first { it.scope == null && it.done && (it.hits?.size ?: 0) == 60 }.hits!!.size)
    }

    /** Q1 3: a sample read through whose files held no text is not "still reading" them. */
    @Test fun aSampleOfNameOnlyFilesIsNotStillReading() = runTest {
        val r = testRetriever(pdfs, dispatchers = core)
        r.planTaste()
        r.indexBatch(1.minutes)
        val vm = vm(r)
        vm.setScope(r.scopeIds.value)
        advanceUntilIdle()
        assertEquals(pdfs.size, vm.ui.value.scope?.size)
        assertEquals(0, vm.ui.value.unread)
    }

    @Test fun chipSearchesInsideItsKind() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setQuery("bill")
        val mixed = vm.ui.first { it.hits?.size == 60 }.hits!!
        vm.setChip(Kind.PDF)
        val pdfHits = vm.ui.first { ui -> ui.chip == Kind.PDF && ui.done }.hits!!
        assertEquals(pdfs.map { it.name }.toSet(), pdfHits.map { it.file.name }.toSet())
        // A filter over the mixed results could not have found them all.
        assertTrue(mixed.count { it.file.kind == Kind.PDF } < pdfs.size)
    }

    /**
     * A Retriever with a fake model, read without the deepen pass: IMG_0 and IMG_1 sit at cosine 0.9 with any text
     * query, the other photos at 0.3; "notes.txt" leaves 4 chunks for the deepen pass, "big log.txt" was cut short.
     * [warmUp] runs inside each model open, with the model's lock held; [asked] sees each query's words as it embeds them.
     */
    private fun withModel(warmUp: suspend () -> Unit = {}, asked: suspend (String) -> Unit = {}): Retriever {
        val shots = (0 until 6).map { testFile("IMG_$it.jpg", Kind.PHOTO) }
        val docs = listOf(testFile("notes.txt", Kind.DOC), testFile("big log.txt", Kind.DOC))
        fun axis(i: Int, cos: Float) = FloatArray(768).also {
            it[0] = cos
            it[i] = sqrt(1 - cos * cos)
        }
        val warmUpText = Prefix.query("warm up")
        val embedder = object : Embedder {
            override val signature = Signature("M440/test/vt70/d256/t512") // the measured floor applies
            override val backend = Backend.CPU

            override suspend fun embedRaw(parts: List<Part>): FloatArray = when (val p = parts.single()) {
                is Part.Image -> p.jpeg[0].toInt().let { i -> axis(i + 1, if (i < 2) 0.9f else 0.3f) }

                is Part.Text -> {
                    if (p.text == warmUpText) {
                        warmUp()
                    } else if (p.text.startsWith(Prefix.query(""))) {
                        asked(p.text.removePrefix(Prefix.query("")))
                    }
                    if (p.text.startsWith(Prefix.query(""))) FloatArray(768).also { it[0] = 1f } else axis(200 + (p.text.hashCode() and 31), 0f)
                }

                else -> error("no audio here")
            }

            override fun close() {}
        }
        return testRetriever(
            files = shots + docs,
            extract = { file ->
                when (file.name) {
                    "notes.txt" -> List(12) { Piece("", listOf(Part.Text("bill line $it")), "bill line $it", "bill line $it") }
                    "big log.txt" -> listOf(Piece("", listOf(Part.Text("bill total")), "bill total"), cutShort())
                    else -> listOf(Piece("", listOf(Part.Image(byteArrayOf(file.name.filter(Char::isDigit).toByte()))), null))
                }
            },
            embedder = embedder,
            profile = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 5, false, false),
            dispatchers = core,
        ).also { it.uiVisible = true }
    }

    @Test fun resultKeepsTheEnginesStrongCount() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = vm(r)
        vm.setQuery("dog")
        val ui = vm.ui.first { it.query == "dog" && it.done }
        assertTrue(ui.vectorLane)
        // The relevance rule ships off (RELEVANCE_RULE_ENABLED), so every hit is strong; the two close photos lead.
        val hits = ui.hits!!
        assertTrue(hits.size > 2)
        assertEquals(hits.size, ui.result!!.strongCount)
        assertEquals(listOf("IMG_0.jpg", "IMG_1.jpg"), hits.take(2).map { it.file.name }.sorted())
        assertEquals(r.search("dog", limit = 60).strongCount, ui.result!!.strongCount)
    }

    @Test fun resultCarriesReadExtentsForTheHitsShown() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = vm(r)
        vm.setQuery("bill")
        // The read notes join the rows a moment after them.
        val ui = vm.ui.first { it.done && it.extents.isNotEmpty() }
        val byName = ui.hits!!.associate { it.file.name to it.fileId }
        assertEquals(
            mapOf(byName.getValue("notes.txt") to ReadExtent.DEEPENING, byName.getValue("big log.txt") to ReadExtent.FIRST_PART),
            ui.extents,
        )
    }

    @Test fun aSearchByPhotoHasNoLessLikelyTail() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = vm(r, photo = byteArrayOf(0))
        vm.findSimilar(testFile("IMG_0.jpg", Kind.PHOTO)).join()
        val ui = vm.ui.first { it.similar != null && it.done }
        assertTrue(ui.hits!!.size > 2)
        assertEquals(ui.hits!!.size, ui.result!!.strongCount)
    }

    @Test fun aSearchByPhotoAfterWordsIsANewAnswer() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm(photo = ByteArray(1))
        vm.setQuery("bill")
        val words = vm.ui.first { it.done }.answer
        vm.findSimilar(photos.first()).join()
        // Times out unless the photo's hits arrive as a new answer.
        vm.ui.first { it.similar != null && it.answer > words }
    }

    @Test fun aPhotoThatCannotBeReadSearchesNothing() = runTest {
        val vm = vm(photo = null)
        vm.searchPhoto("content://test/gone.jpg").join()
        assertEquals(null, vm.ui.value.similar)
        assertEquals(null, vm.ui.value.error)
    }

    @Test fun pickingTheSameUriAgainSearchesTheNewBytesAndClearingReturnsToWords() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        var image = 0
        val vm = vm(r, reader = PhotoReader { uri, label -> PickedPhoto(Similar(uri, label), byteArrayOf(image.toByte())) })
        vm.searchPhoto("content://picked/photo").join()
        advanceUntilIdle()
        assertEquals("IMG_0.jpg", vm.ui.value.hits!!.first().file.name)
        image = 3
        vm.searchPhoto("content://picked/photo").join()
        advanceUntilIdle()
        assertEquals("IMG_3.jpg", vm.ui.value.hits!!.first().file.name)
        vm.clearSimilar()
        vm.submit("notes")
        advanceUntilIdle()
        assertEquals(null, vm.ui.value.similar)
        assertEquals("notes.txt", vm.ui.value.hits!!.first().file.name)
    }

    @Test fun aSupersededSearchLeavesNoExtentsForHitsNotShown() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = vm(r)
        vm.setQuery("total")
        assertTrue(vm.ui.first { it.done && it.extents.isNotEmpty() }.extents.isNotEmpty())
        // Photos only now: the documents' read notes from the answer before must not linger.
        vm.setChip(Kind.PHOTO)
        val ui = vm.ui.first { it.chip == Kind.PHOTO && it.done }
        assertTrue(ui.hits!!.isNotEmpty())
        assertEquals(emptyMap<Long, ReadExtent>(), ui.extents)
    }

    @Test fun aWordsOnlyPhoneIsToldSo() = runTest {
        val small = testRetriever(ramGb = 3.0, dispatchers = core)
        assertTrue(small.textOnly)
        assertTrue(vm(small).ui.value.textOnly)
        assertFalse(vm().ui.value.textOnly)
    }

    @Test fun hitsSayWhichWordsTheyAnswerAndClearingTheWordsDropsTheFilter() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setQuery("bill")
        vm.setChip(Kind.PDF)
        assertEquals("bill", (vm.ui.first { it.done }.phase as SearchPhase.Results).question.text)
        vm.setQuery("")
        assertEquals(null, vm.ui.value.chip)
        // An empty field that stays empty (the screen coming back) keeps a filter picked since.
        vm.setChip(Kind.PHOTO)
        vm.setQuery("")
        assertEquals(Kind.PHOTO, vm.ui.value.chip)
    }

    @Test fun openingAHitAfterTypingRecordsTheQueryOnce() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setQuery("bill")
        vm.ui.first { it.hits?.size == 60 }
        vm.opened()
        assertEquals(listOf("bill"), vm.ui.first { it.recent.isNotEmpty() }.recent)
        vm.opened()
        advanceUntilIdle()
        assertEquals(listOf("bill"), retriever.recentQueries())
    }

    @Test fun refreshingRecentsDropsSearchesClearedElsewhere() = runTest {
        retriever.recordQuery("bill")
        val vm = vm()
        vm.ui.first { it.recent.isNotEmpty() }
        retriever.clearRecentQueries()
        vm.refreshRecent().join()
        assertEquals(emptyList<String>(), vm.ui.value.recent)
    }

    @Test fun openingAHitDuringAPhotoSearchRecordsNothing() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm(photo = ByteArray(1))
        vm.setQuery("bill")
        vm.ui.first { it.hits?.size == 60 }
        vm.findSimilar(photos.first()).join()
        assertTrue(vm.ui.value.similar != null)
        vm.opened()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), retriever.recentQueries())
    }

    @Test fun openingAHitWithABlankQueryRecordsNothing() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setQuery("bill")
        vm.ui.first { it.hits?.size == 60 }
        vm.setQuery("   ")
        vm.opened()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), retriever.recentQueries())
    }

    @Test fun recordsOnlyOnSubmitAndKeepsHistoryInTheStore() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setQuery("bill")
        advanceTimeBy(1_000)
        vm.ui.first { it.hits != null }
        assertEquals(emptyList<String>(), retriever.recentQueries())

        vm.submit()
        vm.ui.first { "bill" in it.recent }

        val again = vm()
        assertEquals(listOf("bill"), again.ui.first { it.recent.isNotEmpty() }.recent)

        again.removeRecent("bill").join()
        assertEquals(emptyList<String>(), again.ui.value.recent)
        assertEquals(emptyList<String>(), retriever.recentQueries())
    }

    @Test fun theNamesAnswerFirstAndTheFullOneRefreshesInPlace() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setQuery("bill")
        advanceTimeBy(40)
        runCurrent()
        val names = vm.ui.value
        assertTrue(names.hits!!.isNotEmpty())
        assertFalse(names.done)
        assertTrue(names.searching)
        advanceTimeBy(300)
        runCurrent()
        assertTrue(vm.ui.value.done)
        assertFalse(vm.ui.value.searching)
        // The same answer, so the screen stays where the person scrolled to.
        assertEquals(names.answer, vm.ui.value.answer)
        // A new word and a kind are each a new answer.
        vm.setChip(Kind.PDF)
        vm.ui.first { it.answer == names.answer + 1 }
        vm.setQuery("bill from")
        vm.ui.first { it.answer == names.answer + 2 && it.hits != null }
    }

    /** Reading waits while the person types or scrolls: [act] holds a batch off until [release]; focus alone does not. */
    private fun TestScope.holdsReadingOff(act: (SearchViewModel) -> Unit, release: (SearchViewModel) -> Unit) {
        val r = withModel()
        val vm = vm(r)
        vm.setFocused(true)
        act(vm)
        val batch = launch { r.indexBatch(1.minutes, deepen = false) }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(batch.isCompleted)
        release(vm)
        advanceUntilIdle()
        assertTrue(batch.isCompleted)
    }

    @Test fun typingHoldsReadingOffForAMoment() = runTest {
        holdsReadingOff(act = { it.setQuery("b") }, release = { advanceTimeBy(1_000) })
    }

    @Test fun scrollingHoldsReadingOff() = runTest {
        holdsReadingOff(act = { it.setScrolling(true) }, release = { it.setScrolling(false) })
    }

    @Test fun focusAloneLeavesReadingAlone() = runTest {
        val r = withModel()
        vm(r).setFocused(true)
        val batch = launch { r.indexBatch(1.minutes, deepen = false) }
        advanceUntilIdle()
        assertTrue(batch.isCompleted)
    }

    @Test fun aWordsOnlyAnswerWhileTheModelOpensIsAskedAgain() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        val r = withModel { gate?.await() }
        r.indexBatch(1.minutes, deepen = false)
        r.release()
        val hold = CompletableDeferred<Unit>().also { gate = it }
        // The model was closed: a search answers from words now and opens it off its path, which takes seconds.
        val vm = vm(r)
        vm.setQuery("dog")
        advanceTimeBy(300)
        runCurrent()
        val wordsOnly = vm.ui.value
        assertEquals("dog", (wordsOnly.phase as SearchPhase.Results).question.text)
        assertFalse(wordsOnly.searching)
        assertFalse(wordsOnly.vectorLane)
        // Asked again in a moment, so not finished: no "nothing found" for words that found nothing yet.
        assertFalse(wordsOnly.done)
        assertFalse(wordsOnly.empty)
        val answer = wordsOnly.answer
        hold.complete(Unit)
        advanceTimeBy(2_100)
        runCurrent()
        assertTrue(vm.ui.value.vectorLane)
        assertFalse(vm.ui.value.searching)
        assertTrue(vm.ui.value.done)
        // The same question answered better: the screen keeps its place.
        assertEquals(answer, vm.ui.value.answer)
    }

    @Test fun aSearchHandedInFromOutsideRuns() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        launchQuery.from(Intent().putExtra(LaunchQuery.EXTRA, " bill "))
        vm.ui.first { it.launched == 1 }
        assertEquals("bill", vm.ui.value.query)
        assertEquals(null, launchQuery.pending.value)
        vm.ui.first { it.done }
    }

    @Test fun aShortcutLaunchIsReportedUsedOnce() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.submit("bill")
        advanceUntilIdle()
        val manager = shadowOf(app.getSystemService(ShortcutManager::class.java))
        assertEquals(0, manager.reportedShortcutsUsed.count())
        launchQuery.from(Intent().putExtra(LaunchQuery.EXTRA, "bill"))
        vm.ui.first { it.launched == 1 }
        assertEquals(listOf("recent-0"), manager.reportedShortcutsUsed.toList())
        // A search that came from no shortcut is not one.
        launchQuery.from(Intent().putExtra(LaunchQuery.EXTRA, "something else"))
        vm.ui.first { it.launched == 2 }
        assertEquals(1, manager.reportedShortcutsUsed.count())
    }

    private fun shortcuts() = ShortcutManagerCompat.getDynamicShortcuts(app).map { it.shortLabel.toString() }

    @Test fun recentSearchesReachTheLauncherOnlyWhileRemembered() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.submit("bill")
        advanceUntilIdle()
        assertEquals(listOf("bill"), shortcuts())
        vm.clearRecent().join()
        assertEquals(emptyList<String>(), shortcuts())
        retriever.updatePrefs { it.copy(rememberSearches = false) }
        vm.submit("water")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), shortcuts())
    }

    @Test fun starringIsKeptAndTheStarredChipFilters() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setStarred(photos[3], true).join()
        vm.ui.first { photos[3].uri in it.starredUris }
        assertEquals(setOf(photos[3].uri), vm.ui.value.starredUris)
        vm.setQuery("bill")
        vm.setOnlyStarred(true)
        vm.ui.first { it.done && it.hits?.size == 1 }
        assertEquals(photos[3].name, vm.ui.value.hits!!.single().file.name)
        vm.setStarred(photos[3], false).join()
        vm.ui.first { it.starredUris.isEmpty() }
        // Clearing the words drops the filter, as it does the kind.
        vm.setQuery("")
        assertFalse(vm.ui.value.onlyStarred)
    }

    @Test fun aFailedActionIsSaidOnce() = runTest {
        val vm = vm(reader = { _, _ -> throw SecurityException("no grant") })
        vm.searchPhoto("content://test/revoked.jpg").join()
        assertEquals("That didn't work. Try again.", vm.ui.value.error)
        vm.errorShown()
        assertEquals(null, vm.ui.value.error)
    }

    @Test fun whatWasUnderstoodCanBeSearchedAsTyped() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setQuery("screenshots bill")
        // No screenshots here, so the kind was dropped and the words searched alone.
        vm.ui.first { it.done && it.result!!.relaxed && it.hits!!.isNotEmpty() }
        assertEquals(listOf("Screenshots"), vm.ui.value.result!!.parsed!!.understood)
        vm.searchWordsAsTyped()
        vm.ui.first { it.literal && it.done && !it.result!!.relaxed && it.result!!.parsed?.understood == emptyList<String>() }
        // New words are understood afresh.
        vm.setQuery("screenshots bill 1")
        assertFalse(vm.ui.value.literal)
    }

    // --- "Nothing found" only for a finished answer to the question on screen (F076) ---

    /** [setUp] leaves a finished empty answer; from then on, through [act] until [settled], nothing may render as empty. */
    private fun TestScope.neverEmptyAfter(
        vm: SearchViewModel,
        setUp: () -> Unit,
        act: () -> Unit,
        settled: (SearchUi) -> Boolean = {
            it.done &&
                it.hits!!.isNotEmpty()
        },
    ) {
        setUp()
        advanceUntilIdle()
        assertTrue("the setup must end on a finished empty answer", vm.ui.value.empty)
        val seen = record(vm)
        act()
        advanceUntilIdle()
        assertTrue(settled(vm.ui.value))
        // The first state recorded is the empty one set up.
        assertEquals(emptyList<SearchUi>(), seen.drop(1).filter { it.empty })
    }

    @Test fun allAfterAnEmptyChipNeverSaysNothingFound() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        neverEmptyAfter(vm, setUp = {
            vm.setQuery("bill")
            vm.setChip(Kind.AUDIO)
        }, act = { vm.setChip(null) })
    }

    @Test fun starredOffNeverSaysNothingFound() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        neverEmptyAfter(vm, setUp = {
            vm.setQuery("bill")
            vm.setOnlyStarred(true)
        }, act = { vm.setOnlyStarred(false) })
    }

    @Test fun wordsAsTypedSayNothingFoundOnlyForThemselves() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setChip(Kind.AUDIO)
        vm.setQuery("screenshots bill")
        advanceUntilIdle()
        assertTrue(vm.ui.value.empty)
        val seen = record(vm)
        vm.searchWordsAsTyped()
        advanceUntilIdle()
        // Nothing for the words as typed either, but said only once they were searched.
        val empties = seen.drop(1).filter { it.empty }
        assertTrue(empties.isNotEmpty())
        assertTrue(empties.all { (it.phase as SearchPhase.Results).question.literal })
        assertFalse(seen[1].empty)
    }

    @Test fun aPhotoPickedAfterAnEmptyAnswerNeverSaysNothingFound() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = vm(r, photo = byteArrayOf(0))
        neverEmptyAfter(vm, setUp = {
            vm.setQuery("dog")
            vm.setChip(Kind.PDF)
        }, act = {
            vm.setChip(null)
            vm.findSimilar(testFile("IMG_0.jpg", Kind.PHOTO))
        }, settled = { it.similar != null && it.done && it.hits!!.isNotEmpty() })
    }

    @Test fun aFreshQuestionNeverSaysNothingFoundOnTheWay() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        val seen = record(vm)
        vm.setQuery("b")
        vm.setQuery("bi")
        vm.setQuery("bill")
        advanceUntilIdle()
        assertTrue(vm.ui.value.hits!!.isNotEmpty())
        assertEquals(emptyList<SearchUi>(), seen.filter { it.empty })
    }

    @Test fun aWordsOnlyAnswerAskedAgainNeverSaysNothingFound() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        val r = withModel { gate?.await() }
        r.indexBatch(1.minutes, deepen = false)
        r.release()
        val hold = CompletableDeferred<Unit>().also { gate = it }
        val vm = vm(r)
        val seen = record(vm)
        // No file's name or words hold "dog": the words-only answer is empty, and only meaning finds the photos.
        vm.setQuery("dog")
        advanceTimeBy(300)
        runCurrent()
        assertTrue(vm.ui.value.hits!!.isEmpty())
        hold.complete(Unit)
        advanceTimeBy(2_100)
        runCurrent()
        assertTrue(vm.ui.value.done && vm.ui.value.hits!!.isNotEmpty())
        assertEquals(emptyList<SearchUi>(), seen.filter { it.empty })
    }

    // --- Background reading answers an empty search (F077) ---

    @Test fun anEmptyAnswerIsAskedAgainOnceFilesAreRead() = runTest {
        // Only "notes.txt" holds "bill", in its words: nothing finds it until it is read.
        val r = withModel()
        val vm = vm(r)
        vm.setQuery("bill")
        advanceTimeBy(2_000)
        runCurrent()
        val before = vm.ui.value
        assertTrue("nothing is read yet", before.empty)
        r.indexBatch(1.minutes, deepen = false)
        advanceUntilIdle()
        val after = vm.ui.value
        assertTrue(after.done && after.hits!!.isNotEmpty())
        // The same question, answered better: the grid keeps its place.
        assertEquals(before.question, after.question)
        assertEquals(before.answer, after.answer)
    }

    // --- Races (F082, F083, F001) ---

    @Test fun aReplacedSearchThatFailsIsNoFailure() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = withModel(asked = {
            if (it == "boom") {
                gate.await()
                error("the model broke")
            }
        })
        r.indexBatch(1.minutes, deepen = false)
        val vm = vm(r)
        val seen = record(vm)
        vm.setQuery("boom")
        advanceTimeBy(300)
        runCurrent()
        // The full search for "boom" waits in the model; new words replace it before its full search starts.
        vm.setQuery("bill")
        gate.complete(Unit)
        runCurrent()
        advanceUntilIdle()
        assertTrue(seen.none { it.phase is SearchPhase.Failed })
        assertTrue(vm.ui.value.done)
    }

    @Test fun clearingAndRetypingTheSameWordsAnswersFromNamesFirst() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setQuery("bill")
        advanceUntilIdle()
        assertTrue(vm.ui.value.done)
        vm.setQuery("")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(SearchPhase.Idle, vm.ui.value.phase)
        vm.setQuery("bill")
        advanceTimeBy(40)
        runCurrent()
        val names = vm.ui.value
        assertTrue(names.hits!!.isNotEmpty())
        assertFalse(names.done)
        assertTrue(names.searching)
    }

    @Test fun aViewModelClearedMidTypingLeavesReadingFree() = runTest {
        val r = withModel()
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, viewModelFactory { initializer { vm(r) } })[SearchViewModel::class.java]
        vm.setQuery("b")
        store.clear()
        val batch = launch { r.indexBatch(1.minutes, deepen = false) }
        advanceUntilIdle()
        assertTrue(batch.isCompleted)
    }

    @Test fun noMatchesGoesQuietAfterItWasSeenEnough() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        vm.setChip(Kind.AUDIO)
        val quiet = listOf("bill", "bill 1", "bill 2", "bill 3").map { q ->
            vm.setQuery(q)
            advanceUntilIdle()
            assertTrue(vm.ui.value.empty)
            vm.ui.value.quietNoMatch
        }
        assertEquals(listOf(false, false, false, true), quiet)
        assertNotEquals(0, retriever.prefs.value.noMatchSeen)
    }

    @Test fun openingADocumentHitCarriesItsIdAndTheQueryToTheReader() = runTest {
        val vm = vm()
        vm.setQuery("  security deposit ")
        val lease = testFile("lease.docx", Kind.DOC)
        val routes = mutableListOf<Route>()
        vm.actions(app) { routes += it }.onOpenHit(SearchEngine.Hit(lease, 9, 3, "p. 2", null, 1.0, false))
        assertEquals(DocRoute(9, lease.uri, lease.name, lease.mime, "p. 2", "security deposit"), routes.single())
    }
}
