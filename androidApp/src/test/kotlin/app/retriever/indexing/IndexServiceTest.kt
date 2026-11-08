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
import app.retriever.core.Kind
import app.retriever.core.NextRun
import app.retriever.core.StopReason
import app.retriever.core.ThermalRest
import app.retriever.indexing.IndexService.Companion.CHARGING
import app.retriever.indexing.IndexService.Companion.IDLE
import app.retriever.indexing.IndexService.Companion.INDEX
import app.retriever.indexing.IndexService.Companion.MEDIA
import app.retriever.indexing.IndexService.Companion.PERIODIC
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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

    // Parks on a deferred the class holds: a coroutine suspended in awaitCancellation() is unreachable, so a GC collected
    // it with WorkManager's future and the run turned FAILED (FutureGarbageCollectedException).
    private class Parked(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = PARKED.await()
    }

    private companion object {
        val PARKED = CompletableDeferred<ListenableWorker.Result>()
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

    private val driver get() = WorkManagerTestInitHelper.getTestDriver(context)!!

    /** Every run waits for storage that is not low; the test driver says when it is. */
    private fun storageFree(name: String) = infos(name).filter { !it.state.isFinished }.forEach { driver.setAllConstraintsMet(it.id) }

    private fun live(name: String) = infos(name).filter { !it.state.isFinished }

    /** On a nearly full disk every file's write fails and burns its attempts, so no run starts until storage is not low. */
    @Test fun everyRunWaitsWhileStorageIsLow() {
        val service = IndexService(context, testRetriever())
        service.kick()
        service.schedule(NextRun.WHEN_CHARGING)
        service.schedule(NextRun.WHEN_IDLE)
        service.watchMedia()
        service.schedulePeriodic(onlyWhileCharging = false)
        for (name in listOf(INDEX, CHARGING, IDLE, MEDIA, PERIODIC)) assertTrue(name, infos(name).single().constraints.requiresStorageNotLow())
    }

    /**
     * The overnight bug: a follow-up REPLACEd the run that was reading, mid-batch. Queued from inside the running reader, the
     * next run waits behind it.
     */
    @Test fun aFollowUpQueuesBehindTheRunningReaderInsteadOfCancellingIt() {
        val service = IndexService(context, testRetriever())
        service.kick()
        storageFree(INDEX)
        val running = live(INDEX).single().id

        service.schedule(NextRun.SOON)
        service.kick() // a wake-up meanwhile

        val (reader, next) = live(INDEX).partition { it.id == running }
        assertEquals(WorkInfo.State.RUNNING, reader.single().state)
        assertEquals(WorkInfo.State.BLOCKED, next.single().state)
    }

    /** Read now doesn't wait out a reader resting before its next run. */
    @Test fun readNowReplacesAReaderRestingBeforeItsNextRun() = runBlocking {
        val service = IndexService(context, testRetriever())
        service.schedule(NextRun.AFTER_COOLDOWN)
        assertEquals(ThermalRest.RECHECK.inWholeMilliseconds, live(INDEX).single().initialDelayMillis)

        service.readNow()
        assertEquals(0L, live(INDEX).single().initialDelayMillis)
    }

    /**
     * In use on battery, idle or the charger wakes it: on a charger reading doesn't wait for idle. Both are wake-ups, not
     * readers, so neither can take the index from one.
     */
    @Test fun waitsForTheChargerOrIdleAreWakeUps() {
        val service = IndexService(context, testRetriever())
        service.schedule(NextRun.WHEN_IDLE)
        val charging = infos(CHARGING).single()
        val idle = infos(IDLE).single()
        assertTrue(charging.constraints.requiresCharging())
        assertTrue(idle.constraints.requiresDeviceIdle())
        assertTrue(listOf(charging, idle).none { IndexService.READER in it.tags })
        assertTrue(infos(INDEX).isEmpty())
    }

    /** Android 15+ refuses the service once its 6 hours a day are used: hidden, the reader reads as a job, not a loop of starts. */
    @Test fun aRefusedServiceIsNotAskedForAgainWhileHidden() {
        val retriever = testRetriever()
        val service = IndexService(context, retriever)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(service.mayStartForeground())
        service.foregroundRefused()
        assertFalse("hidden", service.mayStartForeground())
        retriever.uiVisible = true
        assertTrue("in front, which resets Android's limit", service.mayStartForeground())
    }

    @Test fun pauseStopsTheReaderButKeepsTheWakeUps() {
        val retriever = testRetriever()
        val service = IndexService(context, retriever)
        service.schedulePeriodic(onlyWhileCharging = false)
        service.kick()
        storageFree(INDEX)
        assertEquals(WorkInfo.State.RUNNING, infos(INDEX).single().state)

        runBlocking { service.pause() }

        assertTrue(retriever.prefs.value.paused)
        assertEquals(WorkInfo.State.CANCELLED, infos(INDEX).single().state)
        assertEquals(false, infos(PERIODIC).single().state.isFinished)
    }

    @Test fun aHeldRunRestsByWhyItStopped() {
        assertEquals("heat polls", ThermalRest.POLL, IndexService.restBefore(NextRun.AFTER_COOLDOWN, StopReason.HOT))
        assertEquals("a model open refused for memory", ThermalRest.RECHECK, IndexService.restBefore(NextRun.AFTER_COOLDOWN, null))
        assertEquals("a model closed mid-batch", 10.seconds, IndexService.restBefore(NextRun.SOON, null))
        assertEquals("out of time", Duration.ZERO, IndexService.restBefore(NextRun.SOON, StopReason.BUDGET))
        assertEquals("a phone in use", IndexService.BUSY_POLL, IndexService.restBefore(NextRun.WHEN_IDLE, StopReason.BUSY))
    }

    @Test fun aForegroundRunHoldsOnAChargerOrInFrontUntilPausedOrDone() {
        fun holds(next: NextRun, paused: Boolean = false, visible: Boolean = false, pluggedIn: Boolean = true) =
            IndexService.holdsForeground(next, paused, visible, pluggedIn)
        assertTrue("the next batch at once", holds(NextRun.SOON))
        assertTrue("heat rests in place", holds(NextRun.AFTER_COOLDOWN))
        assertTrue("in front on battery", holds(NextRun.SOON, visible = true, pluggedIn = false))
        assertFalse("hidden on battery: the reader follows", holds(NextRun.SOON, pluggedIn = false))
        assertFalse("Pause", holds(NextRun.SOON, paused = true))
        assertTrue("a phone in use on its charger: waits with its notification", holds(NextRun.WHEN_IDLE))
        assertFalse("in use on battery", holds(NextRun.WHEN_IDLE, visible = true, pluggedIn = false))
        assertFalse("in use, paused", holds(NextRun.WHEN_IDLE, paused = true))
        for (next in listOf(NextRun.DONE, NextRun.WHEN_CHARGING, NextRun.RETRY)) assertFalse(next.name, holds(next))
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

        driver.setAllConstraintsMet(armed.id)
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
        storageFree(INDEX)

        // KEEP drops the second request, and the running batch comes back soon for the change instead.
        assertEquals(NextRun.SOON, runBlocking { retriever.indexBatch(1.minutes) })
        // A GC here once collected a parked run (see Parked); it must stay RUNNING.
        repeat(3) {
            System.gc()
            Thread.sleep(50)
        }
        val errors = ShadowLog.getLogs().filter { it.throwable != null }.joinToString("\n") { it.msg + "\n" + it.throwable.stackTraceToString() }
        assertEquals(errors, WorkInfo.State.RUNNING, infos(INDEX).single().state)
    }
}
