package app.retriever.indexing

import android.content.Context
import android.os.PowerManager
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.core.CoreDispatchers
import app.retriever.core.Kind
import app.retriever.core.NextRun
import app.retriever.core.StopReason
import app.retriever.core.ThermalRest
import app.retriever.indexing.IndexService.Companion.CONTINUE
import app.retriever.indexing.IndexService.Companion.MEDIA
import app.retriever.indexing.IndexService.Companion.NOW
import app.retriever.indexing.IndexService.Companion.PERIODIC
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import org.robolectric.shadows.ShadowLog
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** WorkManager's own test driver runs the unique-work policies; every enqueued run parks, so its state shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IndexServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work get() = WorkManager.getInstance(context)

    private class Parked(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = awaitCancellation()
    }

    @Before fun setUp() {
        val parked = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                Parked(appContext, workerParameters)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(parked).build(),
        )
    }

    @After fun tearDown() {
        work.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun infos(name: String): List<WorkInfo> = work.getWorkInfosForUniqueWork(name).get()

    @Test fun aTimedContinueReplacesOneWaitingForTheCharger() {
        val service = IndexService(context, testRetriever())
        service.continueWhenCharging()
        service.continueLater(10.seconds)

        // Appended, it would sit BLOCKED behind the charger's request; replaced, the delay alone holds it.
        val live = infos(CONTINUE).filter { !it.state.isFinished }
        assertEquals(1, live.size)
        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(live.single().id)
        assertEquals(WorkInfo.State.RUNNING, work.getWorkInfoById(live.single().id).get()!!.state)
    }

    @Test fun leavingOnAChargerWithFilesToReadStartsAForegroundRunUnlessPaused() = runBlocking {
        val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0)
        retriever.planTaste()
        retriever.commit(whileCharging = true)
        val service = IndexService(context, retriever)

        service.screenOff()
        val live = infos(CONTINUE).single { !it.state.isFinished }
        assertTrue("asks for its foreground service", IndexService.CHARGER in live.tags)
        assertEquals(false, live.constraints.requiresDeviceIdle())

        work.cancelAllWork().result.get()
        retriever.pause()
        service.screenOff()
        assertTrue("a Pause holds", infos(CONTINUE).none { !it.state.isFinished })
    }

    @Test fun aSecondScreenOffKeepsTheChargerRunThatHoldsTheService() = runBlocking {
        val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0)
        retriever.planTaste()
        retriever.commit(whileCharging = true)
        val service = IndexService(context, retriever)
        service.screenOff()
        val holding = infos(CONTINUE).single { !it.state.isFinished }.id

        service.screenOff() // the screen goes off again while that run rests between batches
        assertEquals(listOf(holding), infos(CONTINUE).filter { !it.state.isFinished }.map { it.id })
    }

    @Test fun aHeldRunRestsByWhyItStopped() {
        assertEquals("heat polls", ThermalRest.POLL, IndexService.restBefore(NextRun.AFTER_COOLDOWN, StopReason.HOT))
        assertEquals("a model open refused for memory", ThermalRest.RECHECK, IndexService.restBefore(NextRun.AFTER_COOLDOWN, null))
        assertEquals("a model closed mid-batch", 10.seconds, IndexService.restBefore(NextRun.SOON, null))
        assertEquals("out of time", Duration.ZERO, IndexService.restBefore(NextRun.SOON, StopReason.BUDGET))
    }

    @Test fun foregroundRunsStartOnlyOnAChargerWithReadingAllowedAndWorkLeft() {
        assertTrue(IndexService.startsOnCharger(pluggedIn = true, blocker = null, hasWork = true))
        assertFalse(IndexService.startsOnCharger(pluggedIn = false, blocker = null, hasWork = true))
        assertFalse(IndexService.startsOnCharger(pluggedIn = true, blocker = null, hasWork = false))
        for (blocker in listOf(StopReason.PAUSED, StopReason.HOT, StopReason.POWER_SAVE, StopReason.MANUAL, StopReason.UNPLUGGED)) {
            assertFalse(blocker.name, IndexService.startsOnCharger(pluggedIn = true, blocker = blocker, hasWork = true))
        }
    }

    @Test fun aForegroundRunHoldsOnAChargerOrInFrontUntilPausedOrDone() {
        fun holds(next: NextRun, paused: Boolean = false, visible: Boolean = false, pluggedIn: Boolean = true) =
            IndexService.holdsForeground(next, paused, visible, pluggedIn)
        assertTrue("the next batch at once", holds(NextRun.SOON))
        assertTrue("heat rests in place", holds(NextRun.AFTER_COOLDOWN))
        assertTrue("in front on battery", holds(NextRun.SOON, visible = true, pluggedIn = false))
        assertFalse("hidden on battery: WorkManager's follow-ups", holds(NextRun.SOON, pluggedIn = false))
        assertFalse("Pause", holds(NextRun.SOON, paused = true))
        for (next in listOf(NextRun.DONE, NextRun.WHEN_CHARGING, NextRun.WHEN_IDLE, NextRun.RETRY)) assertFalse(next.name, holds(next))
    }

    /** Android defers a cached app's screen-off broadcast, so a busy stop on a charger can't wait for it or for IDLE. */
    @Test fun aPhoneInUseOnItsChargerIsCheckedAgainSoonOnBatteryAtIdle() = runBlocking {
        var plugged = true
        val service = IndexService(context, testRetriever(pluggedIn = { plugged }))
        service.schedule(NextRun.WHEN_IDLE)
        val onCharger = infos(CONTINUE).single { !it.state.isFinished }
        assertEquals(false, onCharger.constraints.requiresDeviceIdle())
        assertEquals(IndexService.BUSY_RECHECK.inWholeMilliseconds, onCharger.initialDelayMillis)

        plugged = false
        service.schedule(NextRun.WHEN_IDLE)
        assertEquals(true, infos(CONTINUE).single { !it.state.isFinished }.constraints.requiresDeviceIdle())
    }

    @Test fun screenOffTrialReplacesIdleWithoutAnIdleConstraint() = runBlocking {
        val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0)
        retriever.planTaste()
        val service = IndexService(context, retriever)
        service.continueWhenIdle()
        service.screenOff()
        val live = infos(CONTINUE).single { !it.state.isFinished }
        assertEquals(false, live.constraints.requiresDeviceIdle())
        assertEquals(false, live.constraints.requiresBatteryNotLow())
        assertEquals(0L, live.initialDelayMillis)
    }

    @Test fun screenOffDuringDirectTrialPersistsHandoffBeforeCancellation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var calls = 0
        val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0, extract = {
            calls++
            entered.complete(Unit)
            try {
                proceed.await()
            } finally {
                assertTrue("persist before cancelling extraction", infos(CONTINUE).any { !it.state.isFinished })
            }
            emptyList()
        }).apply { uiVisible = true }
        retriever.planTaste()
        val service = IndexService(context, retriever)
        val reading = async { service.readTrial() }
        try {
            withTimeout(10.seconds) { entered.await() }
            retriever.uiVisible = false
            service.screenOff()
            val continuation = infos(CONTINUE).single { !it.state.isFinished }
            assertEquals(0L, continuation.initialDelayMillis)
            assertEquals(false, continuation.constraints.requiresDeviceIdle())
            // The direct call must finish even while the extractor is suspended.
            withTimeout(10.seconds) { reading.await() }
            retriever.uiVisible = true
            assertTrue(service.readTrial())
            assertEquals("the persisted worker owns a quick comeback", 1, calls)
            assertEquals(continuation.id, infos(CONTINUE).single { !it.state.isFinished }.id)
        } finally {
            proceed.complete(Unit)
            reading.await()
        }
    }

    @Test fun leavingBatterySaverResumesTheTrialWithoutACooldown() = runBlocking {
        val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0)
        retriever.planTaste()
        val service = IndexService(context, retriever)
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        power.setIsPowerSaveMode(true)
        service.powerSaveChanged()
        assertTrue(infos(CONTINUE).isEmpty())
        power.setIsPowerSaveMode(false)
        service.powerSaveChanged()
        val live = infos(CONTINUE).single { !it.state.isFinished }
        assertTrue(IndexService.TRIAL in live.tags)
        assertEquals(0L, live.initialDelayMillis)
    }

    @Test fun foregroundTrialReadsEvenWithAWorkerWaitingForIdle() = runBlocking {
        val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0)
        retriever.planTaste()
        retriever.uiVisible = true
        val service = IndexService(context, retriever)
        service.continueWhenIdle()
        assertTrue(service.readTrial())
        assertEquals(false, retriever.hasPendingTaste())
        assertEquals(1, retriever.state.value.processed)
        assertTrue("the direct read did not need a WorkManager slot", infos(NOW).isEmpty())
    }

    @Test fun overlappingForegroundRequestsWaitForTheHandoff() = runTest {
        val retriever = testRetriever(
            files = listOf(testFile("a.txt", Kind.DOC)),
            ramGb = 3.0,
            dispatchers = CoreDispatchers(StandardTestDispatcher(testScheduler)),
        ).apply { uiVisible = true }
        retriever.planTaste()
        val service = IndexService(context, retriever)
        val finished = mutableListOf<String>()
        val first = launch {
            service.readTrial()
            finished += "first"
        }
        val second = launch {
            service.readTrial()
            finished += "second"
        }
        first.join()
        second.join()
        assertEquals(listOf("first", "second"), finished)
        assertEquals(1, retriever.state.value.processed)
    }

    @Test fun pauseStopsNowAndContinueButKeepsThePeriodicRun() {
        val retriever = testRetriever()
        val service = IndexService(context, retriever)
        service.schedulePeriodic(onlyWhileCharging = false)
        service.indexNow()
        service.continueLater(10.seconds)
        assertEquals(WorkInfo.State.RUNNING, infos(NOW).single().state)

        runBlocking { service.pause() }

        assertTrue(retriever.prefs.value.paused)
        assertEquals(WorkInfo.State.CANCELLED, infos(NOW).single().state)
        assertTrue(infos(CONTINUE).all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(false, infos(PERIODIC).single().state.isFinished)
    }

    @Test fun mediaChangesTriggerARunAndTheRunReArmsWithoutCancellingItself() {
        val service = IndexService(context, testRetriever())
        service.watchMedia()
        service.watchMedia()
        val armed = infos(MEDIA).single()
        val trigger = armed.constraints.contentUriTriggers.single()
        assertEquals(MediaStore.Files.getContentUri("external"), trigger.uri)
        assertTrue(trigger.isTriggeredForDescendants)
        assertEquals(60_000L, armed.constraints.contentTriggerUpdateDelayMillis)
        assertEquals(600_000L, armed.constraints.contentTriggerMaxDelayMillis)

        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(armed.id)
        assertEquals(WorkInfo.State.RUNNING, work.getWorkInfoById(armed.id).get()!!.state)
        service.watchMedia(rearm = true)

        // The run goes on, and the next trigger waits behind it.
        val (running, next) = infos(MEDIA).partition { it.id == armed.id }
        assertEquals(WorkInfo.State.RUNNING, running.single().state)
        assertEquals(WorkInfo.State.BLOCKED, next.single().state)
    }

    @Test fun indexNowDuringARunningBatchMakesItSyncAgain() {
        lateinit var service: IndexService
        // Text-only reads the document with no model; mid-read, a change asks for another run.
        val retriever = testRetriever(files = listOf(testFile("notes.txt", Kind.DOC)), ramGb = 3.0, extract = {
            service.indexNow()
            emptyList()
        })
        service = IndexService(context, retriever)
        service.indexNow()

        // KEEP drops the second request, and the running batch comes back soon for the change instead.
        assertEquals(NextRun.SOON, runBlocking { retriever.indexBatch(1.minutes) })
        val errors = ShadowLog.getLogs().filter { it.throwable != null }.joinToString("\n") { it.msg + "\n" + it.throwable.stackTraceToString() }
        assertEquals(errors, WorkInfo.State.RUNNING, infos(NOW).single().state)
    }
}
