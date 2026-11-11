package app.retriever.indexing

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import app.retriever.CountingDriver
import app.retriever.core.CoreDispatchers
import app.retriever.core.DeviceState
import app.retriever.core.Kind
import app.retriever.core.NextRun
import app.retriever.core.ReadingSchedule
import app.retriever.core.StopReason
import app.retriever.core.Thermal
import app.retriever.indexing.IndexService.Companion.CHARGING
import app.retriever.indexing.IndexService.Companion.INDEX
import app.retriever.indexing.IndexService.Companion.MEDIA
import app.retriever.indexing.IndexService.Companion.PERIODIC
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
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

    /** The start token the last tap's ReadingService intent carries. */
    private fun acceptedToken(): Long = shadowOf(context as Application).nextStartedService.getLongExtra(ReadingService.TOKEN, 0)

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
        service.watchMedia()
        service.schedulePeriodic(onlyWhileCharging = false)
        for (name in listOf(INDEX, CHARGING, MEDIA, PERIODIC)) assertTrue(name, infos(name).single().constraints.requiresStorageNotLow())
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

    @Test fun aBelowFiftyAccessRequestPlansTheDemoWithoutStartingAService() = runBlocking {
        var extracted = 0
        val files = (1..120).map { testFile("demo$it.pdf", Kind.PDF) }
        val retriever = testRetriever(files = files, deviceState = { DeviceState(8.0, false, 49, Thermal.NONE) }, extract = {
            extracted++
            emptyList()
        })
        retriever.refreshSetUp()
        val service = IndexService(context, retriever)
        service.startSetup()
        assertNull(shadowOf(context as Application).nextStartedService)
        assertTrue(retriever.prefs.value.processingRequested)
        assertFalse(retriever.prefs.value.started)
        val sample = retriever.scopeIds.value
        assertTrue(sample.orEmpty().isNotEmpty())
        assertFalse(requireNotNull(retriever.setup.value).decided)
        assertEquals(StopReason.UNPLUGGED, retriever.explicitProcessingBlocker())
        service.start()
        assertEquals(sample, retriever.scopeIds.value)
        assertTrue(retriever.prefs.value.processingRequested)
        assertFalse(service.foregroundHeld())
        assertNull(shadowOf(context).nextStartedService)
        assertEquals(0, extracted)
        service.pause()
        assertFalse(retriever.prefs.value.processingRequested)
    }

    /** Android allows the start for a few seconds after the tap: the tap launches the service, which starts the session. */
    @Test fun serviceLaunchPrecedesDatabaseInitialization() = runBlocking {
        val retriever = testRetriever()
        val service = IndexService(context, retriever)
        service.start()
        val token = acceptedToken()
        assertFalse("the tap itself starts no session", retriever.prefs.value.started)
        service.host(token, setup = false) {
            assertTrue("the service does, once in the foreground", retriever.prefs.value.started)
            assertTrue(service.foregroundHeld())
            NextRun.DONE
        }
        assertFalse(service.foregroundHeld())
    }

    @Test fun aBatteryDropAfterSdkAcceptanceDefersTheRequestWithoutAnOwner() = runBlocking {
        var battery = 74
        val retriever = testRetriever(deviceState = { DeviceState(8.0, false, battery, Thermal.NONE) })
        val service = IndexService(context, retriever)
        service.start()
        val token = acceptedToken()
        battery = 49
        service.host(token, setup = false) { error("no session starts below the floor") }
        assertTrue(retriever.prefs.value.processingRequested)
        assertFalse(retriever.prefs.value.started)
        assertFalse(service.foregroundHeld())
        assertEquals(StopReason.UNPLUGGED, retriever.explicitProcessingBlocker())
        service.endStaleSession()
        assertTrue(retriever.prefs.value.processingRequested)
        assertEquals(ReadingSchedule.CHARGING, retriever.prefs.value.automaticReading)
    }

    @Test fun pauseBeforeTheAcceptedServiceCallbackClearsItsReservation() = runBlocking {
        val retriever = testRetriever()
        val service = IndexService(context, retriever)
        service.start()
        val token = acceptedToken()
        service.pause()
        service.host(token, setup = false) { error("a paused tap never reads") }
        assertFalse(service.foregroundHeld())
        assertFalse(retriever.prefs.value.started)
        assertTrue(retriever.prefs.value.paused)
    }

    @Test fun aNewResumeThatInitializesBeforeAnOlderPauseGetsTheMutexWins() = runBlocking {
        val retriever = testRetriever(dispatchers = CoreDispatchers(io = Dispatchers.Unconfined))
        val service = IndexService(context, retriever)
        service.start()
        val first = acceptedToken()
        val held = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            service.host(first, setup = false) {
                try {
                    awaitCancellation()
                } finally {
                    // The Pause's token cancels this run, which can resume another caller before Pause takes the lock.
                    withContext(NonCancellable) { service.start() }
                }
            }
        }
        service.pause()
        held.join()
        service.host(acceptedToken(), setup = false) {
            assertTrue(retriever.prefs.value.started)
            assertFalse("the newer Resume is the latest command", retriever.prefs.value.paused)
            NextRun.DONE
        }
    }

    @Test fun aRejectedDirectStartPreservesPrefsScopeAndQueuedAutomaticWork() = runBlocking {
        val refused = object : ContextWrapper(context) {
            override fun startForegroundService(intent: Intent) = throw SecurityException("not allowed")
        }
        val retriever = testRetriever(files = listOf(testFile("demo.txt", Kind.DOC)))
        retriever.refreshSetUp()
        retriever.planTaste()
        val service = IndexService(refused, retriever)
        service.schedule(NextRun.AFTER_COOLDOWN)
        val queuedBefore = live(INDEX).single()
        val prefsBefore = retriever.prefs.value
        val scopeBefore = retriever.scopeIds.value
        service.start()
        assertEquals(prefsBefore, retriever.prefs.value)
        assertEquals(scopeBefore, retriever.scopeIds.value)
        assertFalse(retriever.prefs.value.started)
        assertFalse(service.foregroundHeld())
        assertEquals(queuedBefore.id, live(INDEX).single().id)
        assertEquals(queuedBefore.initialDelayMillis, live(INDEX).single().initialDelayMillis)
        assertEquals(null, shadowOf(context as Application).nextStartedService)
    }

    @Test fun anUnreadableProgressSnapshotCannotHoldRecoveryCleanup() = runBlocking {
        lateinit var database: CountingDriver
        val retriever = testRetriever(wrapDriver = { CountingDriver(it).also { wrapped -> database = wrapped } })
        val service = IndexService(context, retriever)
        retriever.requestProcessing(setup = false)
        database.failing = setOf("index_meta")
        withTimeout(5.seconds) { service.updateWaitingNotification() }
        assertTrue(retriever.prefs.value.processingRequested)
        assertFalse(service.foregroundHeld())
    }

    @Test fun visibleRecoveryRequiresAHumanRequestAndPauseClearsIt() = runBlocking {
        var battery = 49
        val retriever = testRetriever(files = listOf(testFile("work.txt", Kind.DOC)), deviceState = { DeviceState(8.0, false, battery, Thermal.NONE) })
        retriever.uiVisible = true
        retriever.refreshSetUp()
        val service = IndexService(context, retriever)
        service.recoverRequested()
        assertNull(shadowOf(context as Application).nextStartedService)
        service.start()
        assertTrue(retriever.prefs.value.processingRequested)
        assertFalse(retriever.prefs.value.started)
        assertNull(shadowOf(context).nextStartedService)
        battery = 60
        service.recoverRequested()
        assertEquals(ReadingService::class.java.name, requireNotNull(shadowOf(context).nextStartedService).component?.className)
        service.pause()
        assertFalse(retriever.prefs.value.processingRequested)
        service.recoverRequested()
        assertNull(shadowOf(context).nextStartedService)
    }

    /** In use on battery, the charger wakes it: a wake-up, not a reader, so it can't take the index from one. */
    @Test fun waitsForTheChargerAreWakeUps() {
        val service = IndexService(context, testRetriever())
        service.schedule(NextRun.WHEN_CHARGING)
        val charging = infos(CHARGING).single()
        assertTrue(charging.constraints.requiresCharging())
        assertFalse(IndexService.READER in charging.tags)
        assertTrue(infos(INDEX).isEmpty())
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
