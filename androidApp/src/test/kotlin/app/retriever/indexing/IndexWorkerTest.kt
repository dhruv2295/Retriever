package app.retriever.indexing

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.retriever.IndexWorker
import app.retriever.core.Backend
import app.retriever.core.DeviceProfile
import app.retriever.core.Embedder
import app.retriever.core.Kind
import app.retriever.core.ModelVariant
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.Retriever
import app.retriever.core.Signature
import app.retriever.core.SourceFile
import app.retriever.core.Tier
import app.retriever.core.db.IndexDb
import app.retriever.di.PlatformReady
import app.retriever.indexing.IndexService.Companion.CONTINUE
import app.retriever.indexing.IndexService.Companion.NOW
import app.retriever.model.Calibrator
import app.retriever.model.ModelDownload
import app.retriever.model.ModelRepository
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Koin's WorkerFactory builds the worker with its constructor dependencies, so a test module is enough. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IndexWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    @After fun stop() = stopKoin()

    /** [askForeground]: what IndexService sets when Android can still allow the service (leaving, visible). */
    private fun worker(
        retriever: Retriever,
        platformReady: Boolean = true,
        trial: Boolean = false,
        charger: Boolean = false,
        askForeground: Boolean = trial || charger,
    ): IndexWorker {
        startKoin {
            androidContext(context)
            modules(
                module {
                    single { retriever }
                    single(PlatformReady) { { platformReady } }
                    single { IndexService(context, retriever) }
                    single { Calibrator(context, retriever, get()) }
                    single {
                        val download = ModelDownload(context, context.cacheDir.resolve("worker-models"))
                        ModelRepository(context, download, retriever, CoroutineScope(Dispatchers.IO), get())
                    }
                    worker { IndexWorker(androidContext(), get(), get(), get(), get(PlatformReady), get(), get()) }
                },
            )
        }
        return TestListenableWorkerBuilder<IndexWorker>(context)
            .setWorkerFactory(KoinWorkerFactory())
            .setTags(listOfNotNull(IndexService.TRIAL.takeIf { trial }, IndexService.CHARGER.takeIf { charger }))
            .setInputData(workDataOf(IndexService.FOREGROUND to askForeground))
            .build()
    }

    private fun run(retriever: Retriever, platformReady: Boolean = true): ListenableWorker.Result = runBlocking { worker(retriever, platformReady).doWork() }

    /** The services the runs started, oldest first. */
    private fun started(): List<String?> = generateSequence { shadowOf(context as Application).nextStartedService }.map { it.component?.className }.toList()

    private fun listed(retriever: Retriever) = runBlocking { retriever.counts().first().byKind[Kind.DOC] ?: 0L }

    private fun queued(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    private class Model : Embedder {
        @Volatile var closed = false
        override val signature = Signature("M740/test/vt70/d256/t512")
        override val backend = Backend.CPU

        override suspend fun embedRaw(parts: List<Part>) = FloatArray(768).also { it[0] = 1f }

        override fun close() {
            closed = true
        }
    }

    /** A tuned phone with the model and one document, read by [extract]. */
    private fun withModel(model: Model, beforeOpen: () -> Unit = {}, extract: (SourceFile) -> List<Piece> = { emptyList() }) = testRetriever(
        files = listOf(testFile("report.pdf", Kind.DOC)),
        extract = extract,
        embedder = model,
        beforeOpen = beforeOpen,
        profile = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 5, false, false),
        wrapDriver = {
            it.close()
            AndroidSqliteDriver(IndexDb.Schema, context, null)
        },
    )

    /** The model with no tune yet: as when its download finished with the app closed. */
    private fun untuned(model: Embedder) = testRetriever(
        files = listOf(testFile("report.pdf", Kind.DOC)),
        embedder = model,
        wrapDriver = {
            it.close()
            AndroidSqliteDriver(IndexDb.Schema, context, null)
        },
    )

    /** The download finished with the app closed: a run tunes it, so search by description is ready without opening the app. */
    @Test fun aRunTunesADownloadedModel() {
        val retriever = untuned(Model())
        runBlocking { retriever.refreshSetUp() }
        assertEquals(false, retriever.setUp.value)
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(true, retriever.setUp.value)
    }

    /**
     * Android allows the start for only seconds after the app leaves, and a tune takes a minute: the service comes first, and
     * the tune is the service's. The job ends at once, so JobScheduler never counts it as timed out.
     */
    @Test fun aChargerRunStartsReadingServiceAndEndsItsJob() {
        val retriever = untuned(Model())
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(retriever, charger = true).doWork() })
        assertEquals(listOf(ReadingService::class.java.name), started())
        assertEquals("not tuned here", false, retriever.setUp.value)
        assertTrue("no follow-up: the service schedules what comes after", queued(CONTINUE).isEmpty())
    }

    /** A tune that fails is logged and left for a later run: the run itself still succeeds and syncs the files. */
    @Test fun aFailedTuneNeverFailsTheRun() {
        val broken = object : Embedder {
            override val signature = Signature("M740/test/vt70/d256/t512")
            override val backend = Backend.CPU

            override suspend fun embedRaw(parts: List<Part>): FloatArray = error("the model can't run here")

            override fun close() = Unit
        }
        val retriever = untuned(broken)
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(false, retriever.setUp.value)
        assertEquals(1L, listed(retriever))
    }

    @Test fun syncsTheFileListFromTheInjectedRetriever() {
        val retriever = testRetriever(files = listOf(testFile("report.pdf", Kind.DOC)))
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(1L, listed(retriever))
    }

    @Test fun doesNothingWithoutAccessToFiles() {
        val retriever = testRetriever(files = listOf(testFile("report.pdf", Kind.DOC)))
        assertEquals(ListenableWorker.Result.success(), run(retriever, platformReady = false))
        assertEquals(0L, listed(retriever))
    }

    @Test fun aStoppedRunGivesTheModelBack() {
        val model = Model()
        val reading = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val retriever = withModel(model) {
            reading.countDown()
            gate.await()
            emptyList()
        }
        val worker = worker(retriever)
        runBlocking {
            val run = launch(Dispatchers.Default) { worker.doWork() }
            reading.await()
            run.cancel()
            gate.countDown()
            run.join()
        }
        assertTrue(model.closed)
    }

    @Test fun aRunThatComesBackSoonKeepsTheModel() {
        val model = Model()
        lateinit var retriever: Retriever
        retriever = withModel(model) {
            retriever.notifyChanged()
            emptyList()
        }
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertFalse(model.closed)
        assertEquals(1, queued(CONTINUE).size)
    }

    @Test fun aFailedBatchRestsThroughContinueSoIndexNowStillRuns() {
        val retriever = withModel(Model(), beforeOpen = { error("the model file can't be read") })
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(1, queued(CONTINUE).size)
        // Nothing of NOW waits in a backoff, so the person's next Resume or commit is not dropped by KEEP.
        assertTrue(queued(NOW).isEmpty())
        IndexService(context, retriever).indexNow()
        assertEquals(1, queued(NOW).size)
    }

    @Test fun aVisibleRunStartsReadingService() {
        val retriever = withModel(Model())
        retriever.uiVisible = true
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(listOf(ReadingService::class.java.name), started())
    }

    /** Hidden, with no exemption left (a deferred screen-off broadcast, a follow-up), Android refuses the service: plain jobs. */
    @Test fun aHiddenTrialOrChargerRunAsksForNoServiceWithoutAnExemption() {
        for ((trial, charger) in listOf(true to false, false to true)) {
            val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0)
            runBlocking {
                retriever.planTaste()
                assertEquals(ListenableWorker.Result.success(), worker(retriever, trial = trial, charger = charger, askForeground = false).doWork())
                assertFalse("still read", retriever.hasPendingTaste())
            }
            stopKoin()
        }
        assertEquals(emptyList<String?>(), started())
    }

    /** Q1 2: a run that can't read content (here paused) only syncs names, so it posts no reading notification. */
    @Test fun aRunThatCannotReadContentStartsNoForegroundService() {
        val retriever = withModel(Model())
        retriever.uiVisible = true
        runBlocking { retriever.pause() }
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(emptyList<String?>(), started())
    }

    /** A trial handoff starts the service while the direct batch still runs; the service, not the job, waits for its lock. */
    @Test fun aTrialHandoffStartsReadingServiceWithoutWaitingForTheDirectBatch() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0, extract = {
            entered.complete(Unit)
            awaitCancellation()
        })
        retriever.planTaste()
        val direct = async(Dispatchers.Default) { retriever.taste() }
        try {
            withTimeout(10.seconds) { entered.await() }
            assertEquals(ListenableWorker.Result.success(), withTimeout(10.seconds) { worker(retriever, trial = true).doWork() })
            assertEquals(listOf(ReadingService::class.java.name), started())
        } finally {
            retriever.cancelBatch()
            direct.cancel()
        }
    }

    @Test fun noForegroundServiceWhileAnotherBatchRuns() {
        val reading = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val retriever = withModel(Model()) {
            reading.countDown()
            gate.await()
            emptyList()
        }
        retriever.uiVisible = true
        runBlocking {
            val batch = async(Dispatchers.Default) { retriever.indexBatch(1.minutes) }
            reading.await()
            assertEquals(ListenableWorker.Result.success(), worker(retriever).doWork())
            gate.countDown()
            batch.await()
        }
        assertEquals(emptyList<String?>(), started())
    }
}
