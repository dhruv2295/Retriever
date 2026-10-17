package app.retriever

import android.app.AppOpsManager
import android.os.Process
import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutManagerCompat
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
import app.retriever.core.Signature
import app.retriever.core.Tier
import app.retriever.core.cutShort
import app.retriever.data.AccessService
import app.retriever.data.IndexService
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
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
    private val access by lazy { AccessService(app) }

    /** [photo] is what any picked photo reads as; null, unreadable. */
    private fun vm(
        r: Retriever = retriever,
        photo: ByteArray? = null,
        reader: PhotoReader = PhotoReader { _, label -> photo?.let { Similar(label, it) } },
    ) = SearchViewModel(r, access, IndexService(app, r), launchQuery, RecentShortcuts(app), reader)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // Environment.isExternalStorageManager() reads the primary external volume, which Robolectric starts without.
        ShadowEnvironment.addExternalDir("sdcard")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun chipSearchesInsideItsKind() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
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
     * [warmUp] runs inside each model open, with the model's lock held.
     */
    private fun withModel(warmUp: suspend () -> Unit = {}): Retriever {
        val shots = (0 until 6).map { testFile("IMG_$it.jpg", Kind.PHOTO) }
        val docs = listOf(testFile("notes.txt", Kind.DOC), testFile("big log.txt", Kind.DOC))
        fun axis(i: Int, cos: Float) = FloatArray(768).also { it[0] = cos; it[i] = sqrt(1 - cos * cos) }
        val warmUpText = Prefix.query("warm up")
        val embedder = object : Embedder {
            override val signature = Signature("M440/test/vt70/d256/t512") // the measured floor applies
            override val backend = Backend.CPU

            override suspend fun embedRaw(parts: List<Part>): FloatArray = when (val p = parts.single()) {
                is Part.Image -> p.jpeg[0].toInt().let { i -> axis(i + 1, if (i < 2) 0.9f else 0.3f) }
                is Part.Text -> {
                    if (p.text == warmUpText) warmUp()
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
                    "notes.txt" -> List(12) { Piece("", listOf(Part.Text("bill line $it")), "bill line $it") }
                    "big log.txt" -> listOf(Piece("", listOf(Part.Text("bill total")), "bill total"), cutShort())
                    else -> listOf(Piece("", listOf(Part.Image(byteArrayOf(file.name.filter(Char::isDigit).toByte()))), null))
                }
            },
            embedder = embedder,
            profile = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 5, false, false),
            dispatchers = core,
        )
    }

    @Test fun resultKeepsTheEnginesStrongCount() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = vm(r)
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
        val vm = vm(r)
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
        val vm = vm(r, photo = byteArrayOf(0))
        vm.findSimilar(testFile("IMG_0.jpg", Kind.PHOTO)).join()
        val ui = vm.ui.first { it.similar != null && !it.searching && it.hits != null }
        assertTrue(ui.hits!!.size > 2)
        assertEquals(ui.hits.size, ui.strongCount)
    }

    @Test fun aSearchByPhotoAfterWordsIsANewAnswer() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm(photo = ByteArray(1))
        vm.setQuery("bill")
        val words = vm.ui.first { it.hitsFor == "bill" && !it.searching }.answer
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

    @Test fun aSupersededSearchLeavesNoExtentsForHitsNotShown() = runTest {
        val r = withModel()
        r.indexBatch(1.minutes, deepen = false)
        val vm = vm(r)
        vm.setQuery("total")
        assertTrue(vm.ui.first { it.hitsFor == "total" && !it.searching && it.extents.isNotEmpty() }.extents.isNotEmpty())
        // Photos only now: the documents' read notes from the answer before must not linger.
        vm.setChip(Kind.PHOTO)
        val ui = vm.ui.first { it.chip == Kind.PHOTO && !it.searching && it.hits.orEmpty().all { h -> h.file.kind == Kind.PHOTO } }
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
        val vm = vm()
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
        val vm = vm(photo = ByteArray(1))
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
        val vm = vm()
        vm.setQuery("bill")
        val hit = vm.ui.first { it.hits?.size == 60 }.hits!!.first()
        vm.setQuery("   ")
        vm.opened(hit)
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
        assertEquals("bill", names.hitsFor)
        assertTrue(names.nameOnly)
        assertTrue(names.searching)
        advanceTimeBy(300)
        runCurrent()
        assertFalse(vm.ui.value.nameOnly)
        assertFalse(vm.ui.value.searching)
        // The same answer, so the screen stays where the person scrolled to.
        assertEquals(names.answer, vm.ui.value.answer)
        // A new word and a kind are each a new answer.
        vm.setChip(Kind.PDF)
        vm.ui.first { it.answer == names.answer + 1 }
        vm.setQuery("bill from")
        vm.ui.first { it.answer == names.answer + 2 && it.hitsFor == "bill from" }
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
        // The model opening on focus holds its lock for seconds; a search meanwhile answers from words.
        val warming = launch { r.warmSearch() }
        runCurrent()
        val vm = vm(r)
        vm.setQuery("dog")
        advanceTimeBy(300)
        runCurrent()
        assertEquals("dog", vm.ui.value.hitsFor)
        assertFalse(vm.ui.value.searching)
        assertFalse(vm.ui.value.vectorLane)
        val answer = vm.ui.value.answer
        hold.complete(Unit)
        warming.join()
        advanceTimeBy(2_100)
        runCurrent()
        assertTrue(vm.ui.value.vectorLane)
        assertFalse(vm.ui.value.searching)
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
        vm.ui.first { it.hitsFor == "bill" && !it.nameOnly && !it.searching }
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
        assertEquals(1, vm.ui.value.starred)
        vm.setQuery("bill")
        vm.setOnlyStarred(true)
        vm.ui.first { it.hitsFor == "bill" && it.hits?.size == 1 && !it.nameOnly && !it.searching }
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
        vm.ui.first { it.relaxed && !it.nameOnly && !it.searching && it.hits.orEmpty().isNotEmpty() }
        assertEquals(listOf("Screenshots"), vm.ui.value.parsed!!.understood)
        vm.searchWordsAsTyped()
        vm.ui.first { it.literal && !it.relaxed && it.parsed?.understood == emptyList<String>() && !it.nameOnly && !it.searching }
        // New words are understood afresh.
        vm.setQuery("screenshots bill 1")
        assertFalse(vm.ui.value.literal)
    }

    @Test fun homeFollowsTheIndexAndAccess() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        val ui = vm.ui.first { it.home?.newDocs?.size == 3 && it.counts != null }
        assertEquals(setOf(Kind.PDF), ui.home!!.newDocs.map { it.kind }.toSet())
        assertEquals(0, ui.starred)
        assertFalse(ui.paused)
        // Robolectric allows all-files access until told otherwise.
        assertTrue(ui.hasAccess)
        // Access is asked again on return to the screen, not once at start: taken away in Settings meanwhile.
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode("android:manage_external_storage", Process.myUid(), app.packageName, AppOpsManager.MODE_ERRORED)
        vm.checkAccess()
        runCurrent()
        assertFalse(vm.ui.value.hasAccess)
        retriever.pause()
        assertTrue(vm.ui.first { it.paused }.paused)
    }

    @Test fun resumeLiftsAPause() = runTest {
        retriever.pause()
        val vm = vm()
        vm.ui.first { it.paused }
        vm.resume().join()
        assertFalse(vm.ui.first { !it.paused }.paused)
    }
}
