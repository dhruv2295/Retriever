package app.retriever.indexing

import android.content.Context
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
import app.retriever.indexing.IndexService.Companion.CONTINUE
import app.retriever.indexing.IndexService.Companion.MEDIA
import app.retriever.indexing.IndexService.Companion.NOW
import app.retriever.indexing.IndexService.Companion.PERIODIC
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
        work.cancelAllWork()
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
        assertEquals(WorkInfo.State.RUNNING, infos(NOW).single().state)
    }
}
