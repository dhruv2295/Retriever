package app.retriever.indexing

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.retriever.IndexWorker
import app.retriever.core.Backend
import app.retriever.core.DeviceProfile
import app.retriever.core.DeviceState
import app.retriever.core.Embedder
import app.retriever.core.Kind
import app.retriever.core.ModelVariant
import app.retriever.core.NextRun
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.Retriever
import app.retriever.core.Signature
import app.retriever.core.SourceFile
import app.retriever.core.Thermal
import app.retriever.core.ThermalRest
import app.retriever.core.Tier
import app.retriever.core.db.IndexDb
import app.retriever.di.PlatformReady
import app.retriever.indexing.IndexService.Companion.INDEX
import app.retriever.model.Calibrator
import app.retriever.model.ModelDownload
import app.retriever.model.ModelRepository
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch

/** Koin's WorkerFactory builds the worker with its constructor dependencies, so a test module is enough. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IndexWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    @After fun stop() {
        try {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
        } finally {
            stopKoin()
        }
    }

    /** [reader]: the run that reads; without it, a wake-up. */
    private fun worker(retriever: Retriever, platformReady: Boolean = true, reader: Boolean = true): IndexWorker {
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
            .setTags(listOfNotNull(IndexService.READER.takeIf { reader }))
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
    private fun untuned(model: Embedder, deviceState: (() -> DeviceState)? = null) = testRetriever(
        files = listOf(testFile("report.pdf", Kind.DOC)),
        embedder = model,
        deviceState = deviceState,
        wrapDriver = {
            it.close()
            AndroidSqliteDriver(IndexDb.Schema, context, null)
        },
    )

    @Test fun anotherAppInUseAllowsConservativeAutomaticPreparationWithoutAForegroundService() {
        val retriever = untuned(Model(), deviceState = { DeviceState(8.0, true, 80, Thermal.NONE, screenOn = true) })
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(true, retriever.setUp.value)
        assertEquals(1L, listed(retriever))
    }

    @Test fun aWorkerBelowTheBatteryFloorSkipsHeavyPreparationButStillSyncsNames() {
        val retriever = untuned(Model(), deviceState = { DeviceState(8.0, false, 74, Thermal.NONE) })
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(false, retriever.setUp.value)
        assertEquals(1L, listed(retriever))
    }

    /** The download finished with the app closed: a run tunes it, so search by description is ready without opening the app. */
    @Test fun aRunTunesADownloadedModel() {
        val retriever = untuned(Model())
        runBlocking { retriever.refreshSetUp() }
        assertEquals(false, retriever.setUp.value)
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(true, retriever.setUp.value)
    }

    /** A session with no tap to take (Android ended its service, or the process) is over: the run reads as a plain job. */
    @Test fun aSessionWithoutATapEndsAndReadsAsAJob() {
        val retriever = withModel(Model())
        runBlocking { retriever.startProcessing(false) }
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertFalse(retriever.prefs.value.started)
        assertEquals(1L, listed(retriever))
    }

    /** Media, periodic, charger and idle runs only start the reader: four runs that each read fought over the index. */
    @Test fun aWakeUpOnlyStartsTheReader() {
        val retriever = testRetriever(files = listOf(testFile("report.pdf", Kind.DOC)))
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(retriever, reader = false).doWork() })
        assertEquals("not synced", 0L, listed(retriever))
        assertEquals(1, queued(INDEX).size)
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
        assertEquals(1, queued(INDEX).size)
    }

    /** Not Result.retry(): a reader in WorkManager's backoff (up to 5 h) would hold every wake-up off. */
    @Test fun aFailedBatchRestsAsTheReadersNextRun() {
        val retriever = withModel(Model(), beforeOpen = { error("the model file can't be read") })
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(ThermalRest.RECHECK.inWholeMilliseconds, queued(INDEX).single().initialDelayMillis)
    }

    /** In front with no Start processing, the run reads here: nothing automatic starts the service, whatever Android allows. */
    @Test fun aVisibleRunWithoutATapStartsNoService() {
        val retriever = withModel(Model())
        retriever.uiVisible = true
        shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(emptyList<String?>(), started())
        assertEquals(1L, listed(retriever))
    }

    /** The demo is not the person's Start processing: the reader reads the sample as a plain job. */
    @Test fun theDemoReadsAsAPlainJob() = runBlocking {
        val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0)
        retriever.planTaste()
        assertEquals(ListenableWorker.Result.success(), worker(retriever).doWork())
        assertFalse("read", retriever.hasPendingTaste())
    }

    /**
     * A media or periodic job while ReadingService holds a run: taking the lock ended the held run's service overnight. It
     * only comes back later, in case the process dies with the service.
     */
    @Test fun aRunLeavesTheReadingToAHeldRun() = runBlocking {
        val retriever = testRetriever(files = listOf(testFile("report.pdf", Kind.DOC)))
        val worker = worker(retriever)
        val index = GlobalContext.get().get<IndexService>()
        index.start()
        val token = shadowOf(context as Application).nextStartedService.getLongExtra(ReadingService.TOKEN, 0)
        index.host(token, setup = false) {
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals("not synced: the held run's next batch does", 0L, listed(retriever))
            assertTrue(queued(INDEX).all { it.initialDelayMillis == ThermalRest.RECHECK.inWholeMilliseconds })
            NextRun.DONE
        }
    }
}
