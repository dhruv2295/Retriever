package app.retriever.indexing

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.work.ForegroundUpdater
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
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Koin's WorkerFactory builds the worker with its constructor dependencies, so a test module is enough. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IndexWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val foregrounds = AtomicInteger()

    @Before fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    @After fun stop() = stopKoin()

    private val notificationIds = java.util.Collections.synchronizedList(mutableListOf<Int>())
    private val serviceTypes = java.util.Collections.synchronizedList(mutableListOf<Int>())

    /** Counts the run's foreground-service starts and keeps each one's notification id and service type. */
    private val countingUpdater = ForegroundUpdater { _, _, info ->
        foregrounds.incrementAndGet()
        notificationIds += info.notificationId
        serviceTypes += info.foregroundServiceType
        CallbackToFutureAdapter.getFuture { it.set(null) }
    }

    private fun worker(retriever: Retriever, platformReady: Boolean = true, trial: Boolean = false, charger: Boolean = false): IndexWorker {
        startKoin {
            androidContext(context)
            modules(
                module {
                    single { retriever }
                    single(PlatformReady) { { platformReady } }
                    single { IndexService(context, retriever) }
                    single { Calibrator(context, retriever, get()) }
                    worker { IndexWorker(androidContext(), get(), get(), get(), get(PlatformReady), get()) }
                },
            )
        }
        return TestListenableWorkerBuilder<IndexWorker>(context)
            .setWorkerFactory(KoinWorkerFactory())
            .setForegroundUpdater(countingUpdater)
            .setInputData(workDataOf(IndexService.TRIAL to trial, IndexService.CHARGER to charger))
            .build()
    }

    private fun run(retriever: Retriever, platformReady: Boolean = true): ListenableWorker.Result = runBlocking { worker(retriever, platformReady).doWork() }

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

    /** Two runs that overlap share one notification instead of showing two identical ones. */
    @Test fun everyRunUsesTheOneProcessingNotification() {
        repeat(2) {
            val retriever = testRetriever(files = listOf(testFile("a$it.txt", Kind.DOC)), ramGb = 3.0)
            runBlocking { worker(retriever, charger = true).doWork() }
            stopKoin()
        }
        assertEquals(listOf(Notifications.PROGRESS_ID, Notifications.PROGRESS_ID), notificationIds.toList())
    }

    /** Hidden on a charger, a run holding its foreground service starts the next batch itself instead of a follow-up. */
    @Test fun aChargerRunHoldsItsForegroundServiceAcrossBatches() {
        val model = Model()
        lateinit var retriever: Retriever
        retriever = withModel(model) {
            retriever.notifyChanged() // the first batch ends SOON, as one past its budget does
            emptyList()
        }
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(retriever, charger = true).doWork() })
        assertEquals(1, foregrounds.get())
        assertTrue("the second batch ran here and ended DONE: no follow-up to schedule", queued(CONTINUE).isEmpty())
        assertTrue("done, the model goes", model.closed)
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

    @Test fun aVisibleRunStartsAForegroundService() {
        val retriever = withModel(Model())
        retriever.uiVisible = true
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(1, foregrounds.get())
        // Play's declaration lists local file processing under dataSync; mediaProcessing is for media transcoding only.
        assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC), serviceTypes.toList())
    }

    /** Q1 2: a run that can't read content (here paused) only syncs names, so it posts no reading notification. */
    @Test fun aRunThatCannotReadContentStartsNoForegroundService() {
        val retriever = withModel(Model())
        retriever.uiVisible = true
        runBlocking { retriever.pause() }
        assertEquals(ListenableWorker.Result.success(), run(retriever))
        assertEquals(0, foregrounds.get())
    }

    @Test fun trialWorkerWaitsForTheDirectBatchAndStartsForegroundWhileHidden() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val retriever =
            testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0, wrapDriver = {
                it.close()
                AndroidSqliteDriver(IndexDb.Schema, context, null)
            }, extract = {
                if (calls++ == 0) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                emptyList()
            })
        retriever.planTaste()
        val direct = async { retriever.taste() }
        withTimeout(10.seconds) { entered.await() }
        val worker = worker(retriever, trial = true)
        val continuation = async(Dispatchers.Default) { worker.doWork() }
        try {
            withTimeout(10.seconds) { while (!continuation.isCompleted && foregrounds.get() == 0) delay(10) }
            assertFalse("the continuation must wait for the core lock", continuation.isCompleted)
            assertEquals(1, foregrounds.get())
            retriever.cancelBatch()
            direct.await()
            assertEquals(ListenableWorker.Result.success(), withTimeout(10.seconds) { continuation.await() })
            assertEquals(2, calls)
            assertFalse(retriever.hasPendingTaste())
        } finally {
            retriever.cancelBatch()
            direct.cancel()
            continuation.cancel()
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
        assertEquals(0, foregrounds.get())
    }
}
