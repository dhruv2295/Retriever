package app.retriever.home

import android.app.AppOpsManager
import android.app.Application
import android.os.Process
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.CountingDriver
import app.retriever.access.AccessService
import app.retriever.core.CoreDispatchers
import app.retriever.core.HomeFacts
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.indexing.IndexService
import app.retriever.indexing.ReadingService
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes

/** A real Retriever over an in-memory store, the ViewModel and the store on one test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HomeViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()
    private val core = CoreDispatchers(dispatcher)
    private val appScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val photos = (1..4).map { testFile("photo $it.jpg", Kind.PHOTO) }
    private val pdfs = (1..3).map { testFile("bill $it.pdf", Kind.PDF) }
    private val retriever = testRetriever(pdfs + photos, dispatchers = core)
    private val access by lazy { AccessService(app) }

    private val models = mutableListOf<ViewModel>()
    private fun vm(r: Retriever = retriever) = HomeViewModel(r, access, IndexService(app, r), appScope).also { models += it }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // Environment.isExternalStorageManager() reads the primary external volume, which Robolectric starts without.
        ShadowEnvironment.addExternalDir("sdcard")
    }

    @After fun tearDown() {
        // Wait for IO continuations too, before replacing Main underneath their ViewModel scopes.
        runTest(dispatcher) {
            models.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            appScope.coroutineContext.job.cancelAndJoin()
        }
        WorkManager.getInstance(app).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        Dispatchers.resetMain()
    }

    @Test fun homeFollowsTheIndexAndAccess() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        val ui = vm.ui.first { it.home?.newDocs?.size == 3 && it.counts != null }
        assertEquals(setOf(Kind.PDF), ui.home!!.newDocs.map { it.kind }.toSet())
        assertEquals(mapOf(Kind.PDF to 3L, Kind.PHOTO to 4L), ui.counts)
        assertEquals(0, ui.starred)
        assertFalse(retriever.prefs.value.paused)
        // Robolectric allows all-files access until told otherwise.
        assertTrue(ui.hasAccess)
        // Access is asked again on return to the screen, not once at start: taken away in Settings meanwhile.
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode("android:manage_external_storage", Process.myUid(), app.packageName, AppOpsManager.MODE_ERRORED)
        vm.onResume()
        runCurrent()
        assertFalse(vm.ui.value.hasAccess)
        retriever.pause()
        assertTrue(retriever.prefs.value.paused)
    }

    @Test fun noHomeListHoldsAnOtherFile() = runTest {
        // An app database newer than everything and starred: Home never shows it, the Starred list still holds it.
        val dump = testFile("msgstore.crypt14", Kind.OTHER).copy(modifiedAt = Long.MAX_VALUE / 2)
        val r = testRetriever(pdfs + photos + dump, dispatchers = core)
        r.indexBatch(1.minutes)
        r.setStarred(dump.uri, true)
        r.setStarred(photos[0].uri, true)
        val home = vm(r).ui.first { it.home != null }.home!!
        val lists = home.newMedia + home.newShots + home.newDocs + home.starred
        assertTrue(lists.isNotEmpty())
        assertTrue(lists.none { it.kind == Kind.OTHER })
        assertEquals(1L, home.starredCount)
        assertEquals(2, r.starredFiles().size)
    }

    @Test fun unchangedFactsKeepTheirInstance() {
        val old = HomeFacts(photos, emptyList(), pdfs, emptyList(), 0, 1, 0, 0)
        // A refresh builds equal facts as new objects; only facts that really changed are replaced.
        assertSame(old, HomeFacts(photos.toList(), emptyList(), pdfs.toList(), emptyList(), 0, 1, 0, 0).reusing(old))
        val fresh = HomeFacts(photos.toList(), emptyList(), pdfs.toList(), listOf(photos[0]), 1, 1, 0, 0).reusing(old)
        assertEquals(listOf(photos[0]), fresh.starred)
        assertEquals(1L, fresh.starredCount)
    }

    @Test fun starredFilesAreCounted() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        retriever.setStarred(photos[0].uri, true)
        assertEquals(1, vm.ui.first { it.starred == 1 }.starred)
    }

    @Test fun leavingHomeDoesNotCancelAnAcceptedStartOrPause() = runTest {
        val startVm = vm()
        val start = startVm.readNow()
        startVm.viewModelScope.coroutineContext.job.cancel()
        start.join()
        assertEquals(ReadingService::class.java.name, shadowOf(app).nextStartedService.component?.className)
        val pauseVm = vm()
        val pause = pauseVm.stopReadNow()
        pauseVm.viewModelScope.coroutineContext.job.cancel()
        pause.join()
        assertTrue(retriever.prefs.value.paused)
        assertFalse(retriever.prefs.value.started)
    }

    @Test fun countsAreSubscribedToOncePerViewModel() = runTest {
        val driver = object {
            lateinit var d: CountingDriver
        }
        val r = testRetriever(pdfs + photos, dispatchers = core, wrapDriver = { CountingDriver(it).also { c -> driver.d = c } })
        r.indexBatch(1.minutes)
        driver.d.subscriptions = 0
        val vm = vm(r)
        vm.ui.first { it.home != null && it.counts != null && it.readingStatus != null }
        // The numbers and the reading status share core's one counts subscription; the suggestions, which follow what is
        // read, keep one of their own. Home's facts follow the library, not the store.
        assertTrue("${driver.d.subscriptions} store subscriptions", driver.d.subscriptions <= 2)
    }

    /** Home waiting in the back stack (nothing collecting) holds no store subscription; shown, it does. */
    @Test fun theLibraryIsWatchedOnlyWhileHomeIsShown() = runTest {
        val driver = object {
            lateinit var d: CountingDriver
        }
        val r = testRetriever(pdfs + photos, dispatchers = core, wrapDriver = { CountingDriver(it).also { c -> driver.d = c } })
        r.indexBatch(1.minutes)
        driver.d.subscriptions = 0
        val vm = vm(r)
        advanceUntilIdle()
        assertEquals("subscriptions with no screen", 0, driver.d.subscriptions)
        assertEquals(null, vm.ui.value.counts)
        vm.ui.first { it.counts != null && it.home != null }
        assertTrue(driver.d.subscriptions > 0)
    }

    /** Every section's source answers before any section draws, so none is later inserted above another. */
    @Test fun sectionsWaitForTheFactsAndOnThisDay() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        assertFalse(vm.ui.value.sectionsReady)
        vm.now = { ZonedDateTime.now() }
        vm.onResume()
        val ui = vm.ui.first { it.sectionsReady }
        // The suggestions live in the search bar now, so Home's sections don't wait for them.
        assertTrue(ui.home != null && ui.memoriesDay != null)
    }

    /** A read that fails is logged and skipped, never thrown into Home's scope; the suggestions answer none, so Home draws. */
    @Test fun aFailedReadNeverReachesHome() = runTest {
        val driver = object {
            lateinit var d: CountingDriver
        }
        val r = testRetriever(pdfs + photos, dispatchers = core, wrapDriver = { CountingDriver(it).also { c -> driver.d = c } })
        r.indexBatch(1.minutes)
        // The counts (each collector's first read and the shared one) and the suggestions' content versions.
        driver.d.failing = setOf("GROUP BY kind, state, partial, reach", "MAX(c.id)")
        val ui = vm(r).ui.first { it.home != null && it.suggestions != null }
        assertEquals(emptyList<String>(), ui.suggestions)
        assertEquals(null, ui.counts)
    }

    // --- Caught up, once ---

    @Test fun theCelebrationFiresOnceAndNeverForALaterCatchUpOrAfterARelaunch() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        assertTrue(vm.celebration.due())
        // The screen marks it after the haptic; a relaunch is a new ViewModel over the same store.
        vm.celebration.done()
        runCurrent()
        assertFalse(vm.celebration.due())
        assertFalse(vm(retriever).celebration.due())
        // Caught up again after more files arrived: still spent.
        retriever.indexBatch(1.minutes)
        assertFalse(vm(retriever).celebration.due())
    }

    @Test fun theCelebrationIsNotSpentUntilItIsMarked() = runTest {
        // A screen left before the haptic never marks it, so the next time it is on screen it still plays.
        assertTrue(vm().celebration.due())
        assertTrue(vm().celebration.due())
    }

    // --- On this day ---

    private val newYork = ZoneId.of("America/New_York")

    @Test fun resumeDoesNotBlockOnTheClock() = runTest {
        val clock = CompletableDeferred<ZonedDateTime>()
        val vm = vm()
        vm.now = { clock.await() }
        vm.onResume()
        runCurrent()
        assertEquals(null, vm.ui.value.memoriesDay)
        val noon = ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, newYork)
        clock.complete(noon)
        assertEquals(noon.toLocalDate(), vm.ui.first { it.memoriesDay != null }.memoriesDay)
    }

    @Test fun aDayIsRangedByTheZonesRulesAcrossDstChanges() {
        fun hours(r: Triple<Int, Long, Long>) = Duration.ofMillis(r.third + 1 - r.second).toHours()
        // 9 March 2025 and 2 November 2025 are the 23 and 25 hour days in New York.
        assertEquals(23L, hours(onThisDayRanges(LocalDate.of(2026, 3, 9), newYork).first { it.first == 2025 }))
        assertEquals(24L, hours(onThisDayRanges(LocalDate.of(2026, 3, 9), newYork).first { it.first == 2024 }))
        assertEquals(25L, hours(onThisDayRanges(LocalDate.of(2026, 11, 2), newYork).first { it.first == 2025 }))
        // The day's end is the next midnight less a millisecond, so the days tile with no gap and no overlap.
        val (_, from, to) = onThisDayRanges(LocalDate.of(2026, 3, 9), newYork).first { it.first == 2025 }
        assertEquals(ZonedDateTime.of(2025, 3, 9, 0, 0, 0, 0, newYork).toInstant().toEpochMilli(), from)
        assertEquals(ZonedDateTime.of(2025, 3, 10, 0, 0, 0, 0, newYork).toInstant().toEpochMilli() - 1, to)
    }

    @Test fun theSameDayInPastYearsNewestFirst() {
        val years = onThisDayRanges(LocalDate.of(2026, 10, 17), newYork).map { it.first }
        assertEquals(2025, years.first())
        assertEquals(years.sortedDescending(), years)
        assertFalse(2026 in years)
    }

    @Test fun twentyNinthOfFebruaryIsOnlyInLeapYears() {
        val years = onThisDayRanges(LocalDate.of(2028, 2, 29), newYork).map { it.first }
        assertEquals(listOf(2024, 2020, 2016), years.take(3))
        assertTrue(years.all { java.time.Year.isLeap(it.toLong()) })
    }

    private fun taken(name: String, kind: Kind, at: ZonedDateTime) =
        testFile(name, kind).copy(modifiedAt = at.toInstant().toEpochMilli(), takenAt = at.toInstant().toEpochMilli())

    @Test fun onThisDayAsksOncePerLocalDateAndAgainWhenTheDateChanges() = runTest {
        val driver = object {
            lateinit var d: CountingDriver
        }
        val noon = ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, newYork)
        val r = testRetriever(
            listOf(
                taken("a.jpg", Kind.PHOTO, noon.minusYears(3)),
                taken("b.jpg", Kind.PHOTO, noon.minusYears(3).plusHours(2)),
                taken("c.mp4", Kind.VIDEO, noon.minusYears(3).minusHours(1)),
                taken("d.jpg", Kind.PHOTO, noon.minusYears(1).plusDays(1)),
                taken("e.jpg", Kind.PHOTO, noon.minusYears(5)),
            ),
            dispatchers = core,
            wrapDriver = { CountingDriver(it).also { c -> driver.d = c } },
        )
        r.indexBatch(1.minutes)
        val vm = vm(r)
        var today = noon
        vm.now = { today }
        vm.onResume()
        val ui = vm.ui.first { it.memories.isNotEmpty() }
        assertEquals(LocalDate.of(2026, 10, 17), ui.memoriesDay)
        // 2025's photo is a day late; 2023 holds two photos and a video; 2021 one photo.
        assertEquals(listOf(2023, 2021), ui.memories.map { it.year })
        assertEquals(2L, ui.memories[0].photos)
        assertEquals(1L, ui.memories[0].videos)
        runCurrent()
        val probes = driver.d.memoryProbes
        assertEquals(1, probes)
        // Back to the app on the same date: no second look.
        vm.onResume()
        vm.onResume()
        runCurrent()
        assertEquals(probes, driver.d.memoryProbes)
        // Past midnight it asks again, for the new date.
        today = noon.plusDays(1)
        vm.onResume()
        runCurrent()
        assertEquals(probes + 1, driver.d.memoryProbes)
        assertEquals(LocalDate.of(2026, 10, 18), vm.ui.value.memoriesDay)
    }

    @Test fun hidingOnThisDayIsAtOnceAndTheStatusSwitchBringsItBack() = runTest {
        val noon = ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, newYork)
        val r = testRetriever(listOf(taken("a.jpg", Kind.PHOTO, noon.minusYears(2))), dispatchers = core)
        r.indexBatch(1.minutes)
        val vm = vm(r)
        vm.now = { noon }
        vm.onResume()
        assertTrue(vm.ui.first { it.memories.isNotEmpty() }.showOnThisDay)

        vm.hideOnThisDay()
        // The screen's state changes first, not waiting for the store's write.
        assertFalse(vm.ui.first { !it.showOnThisDay }.showOnThisDay)
        runCurrent()
        assertFalse(r.prefs.value.showOnThisDay)

        r.updatePrefs { it.copy(showOnThisDay = true) }
        assertTrue(vm.ui.first { it.showOnThisDay }.showOnThisDay)
        assertTrue(r.prefs.value.showOnThisDay)
        // The cards that were found are still there: no second look.
        assertEquals(listOf(2024), vm.ui.value.memories.map { it.year })
    }

    @Test fun undoingTheHideBringsOnThisDayBackWithItsCards() = runTest {
        val noon = ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, newYork)
        val r = testRetriever(listOf(taken("a.jpg", Kind.PHOTO, noon.minusYears(2))), dispatchers = core)
        r.indexBatch(1.minutes)
        val vm = vm(r)
        vm.now = { noon }
        vm.onResume()
        vm.ui.first { it.memories.isNotEmpty() }

        vm.hideOnThisDay()
        runCurrent()
        assertFalse(r.prefs.value.showOnThisDay)
        vm.showOnThisDay()
        runCurrent()
        assertTrue(r.prefs.value.showOnThisDay)
        assertEquals(listOf(2024), vm.ui.first { it.showOnThisDay }.memories.map { it.year })
    }
}
