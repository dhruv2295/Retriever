package app.retriever.home

import android.app.AppOpsManager
import android.app.Application
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.retriever.access.AccessService
import app.retriever.core.CoreDispatchers
import app.retriever.core.HomeFacts
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.indexing.IndexService
import app.retriever.status.StatusViewModel
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

/** Counts the live-query subscriptions the store is asked for, and the reads of the oldest photo that start On this day. */
private class CountingDriver(private val driver: SqlDriver) : SqlDriver by driver {
    var subscriptions = 0
    var memoryProbes = 0

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        if (sql.contains("MIN(COALESCE")) memoryProbes++
        return driver.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        subscriptions++
        driver.addListener(*queryKeys, listener = listener)
    }
}

/** A real Retriever over an in-memory store, the ViewModel and the store on one test dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HomeViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()
    private val core = CoreDispatchers(dispatcher)
    private val photos = (1..4).map { testFile("photo $it.jpg", Kind.PHOTO) }
    private val pdfs = (1..3).map { testFile("bill $it.pdf", Kind.PDF) }
    private val retriever = testRetriever(pdfs + photos, dispatchers = core)
    private val access by lazy { AccessService(app) }

    private fun vm(r: Retriever = retriever) = HomeViewModel(r, access, IndexService(app, r))

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // Environment.isExternalStorageManager() reads the primary external volume, which Robolectric starts without.
        ShadowEnvironment.addExternalDir("sdcard")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun homeFollowsTheIndexAndAccess() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        val ui = vm.ui.first { it.home?.newDocs?.size == 3 && it.counts != null }
        assertEquals(setOf(Kind.PDF), ui.home!!.newDocs.map { it.kind }.toSet())
        assertEquals(mapOf(Kind.PDF to 3L, Kind.PHOTO to 4L), ui.counts)
        assertEquals(0, ui.starred)
        assertFalse(ui.paused)
        // Robolectric allows all-files access until told otherwise.
        assertTrue(ui.hasAccess)
        // Access is asked again on return to the screen, not once at start: taken away in Settings meanwhile.
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode("android:manage_external_storage", Process.myUid(), app.packageName, AppOpsManager.MODE_ERRORED)
        vm.onResume()
        runCurrent()
        assertFalse(vm.ui.value.hasAccess)
        retriever.pause()
        assertTrue(vm.ui.first { it.paused }.paused)
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

    @Test fun unchangedListsKeepTheirInstances() {
        val docs = pdfs
        val old = HomeFacts(photos, emptyList(), docs, emptyList(), 0, 1, 0, 0, 0)
        // A refresh builds equal lists as new objects; only a list that really changed is replaced.
        val fresh = HomeFacts(photos.toList(), emptyList(), docs.toList(), listOf(photos[0]), 1, 1, 0, 0, 0).reusing(old)
        assertSame(old.newMedia, fresh.newMedia)
        assertSame(old.newDocs, fresh.newDocs)
        assertEquals(listOf(photos[0]), fresh.starred)
        assertEquals(1L, fresh.starredCount)
    }

    @Test fun readinessSaysWhereAKindStands() {
        val ui = HomeUi(
            counts = mapOf(Kind.PHOTO to 100L, Kind.PDF to 10L, Kind.DOC to 5L, Kind.OTHER to 3L),
            waiting = 40,
            pending = mapOf(Kind.PHOTO to 40L),
            inside = Kind.entries.toSet() - Kind.DOC,
        )
        assertEquals("40 to go", ui.readiness(Kind.PHOTO))
        assertEquals("Ready", ui.readiness(Kind.PDF))
        assertEquals("Ready", ui.readiness(Kind.PDF, Kind.DOC))
        assertEquals("Found by name", ui.readiness(Kind.DOC))
        assertEquals("Found by name", ui.readiness(Kind.OTHER))
        assertEquals("Waiting", ui.copy(pending = mapOf(Kind.PHOTO to 100L)).readiness(Kind.PHOTO))
        assertEquals(null, ui.copy(waiting = 0).readiness(Kind.PHOTO))
    }

    @Test fun starredFilesAreCounted() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        retriever.setStarred(photos[0].uri, true)
        assertEquals(1, vm.ui.first { it.starred == 1 }.starred)
    }

    @Test fun resumeLiftsAPause() = runTest {
        retriever.pause()
        val vm = vm()
        vm.ui.first { it.paused }
        vm.resume().join()
        assertFalse(vm.ui.first { !it.paused }.paused)
    }

    @Test fun countsAreSubscribedToOncePerViewModel() = runTest {
        val driver = object {
            lateinit var d: CountingDriver
        }
        val r = testRetriever(pdfs + photos, dispatchers = core, wrapDriver = { CountingDriver(it).also { c -> driver.d = c } })
        r.indexBatch(1.minutes)
        driver.d.subscriptions = 0
        r.counts().first()
        val one = driver.d.subscriptions
        assertTrue(one > 0)
        driver.d.subscriptions = 0
        val vm = vm(r)
        vm.ui.first { it.home != null }
        // Two collectors (the numbers, and the slower home reload) share one upstream.
        assertEquals(one, driver.d.subscriptions)
    }

    // --- Caught up, once ---

    @Test fun theCelebrationFiresOnceAndNeverForALaterCatchUpOrAfterARelaunch() = runTest {
        retriever.indexBatch(1.minutes)
        val vm = vm()
        assertTrue(vm.celebration.due())
        // The screen marks it after the haptic; a relaunch is a new ViewModel over the same store.
        vm.celebration.done()
        advanceUntilIdle()
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

    @Test fun readEverythingCommitsTheScopeAndSchedulesTheReading() = runTest {
        val vm = vm()
        vm.commit(true).join()
        assertTrue(retriever.tasteDecided())
        assertTrue(retriever.prefs.value.onlyWhileCharging)
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
        advanceUntilIdle()
        val probes = driver.d.memoryProbes
        assertEquals(1, probes)
        // Back to the app on the same date: no second look.
        vm.onResume()
        vm.onResume()
        advanceUntilIdle()
        assertEquals(probes, driver.d.memoryProbes)
        // Past midnight it asks again, for the new date.
        today = noon.plusDays(1)
        vm.onResume()
        advanceUntilIdle()
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
        assertFalse(vm.ui.value.showOnThisDay)
        advanceUntilIdle()
        assertFalse(r.prefs.value.showOnThisDay)

        StatusViewModel(r, IndexService(app, r), access).setOnThisDay(true)
        assertTrue(vm.ui.first { it.showOnThisDay }.showOnThisDay)
        assertTrue(r.prefs.value.showOnThisDay)
        // The cards that were found are still there: no second look.
        assertEquals(listOf(2024), vm.ui.value.memories.map { it.year })
    }
}
