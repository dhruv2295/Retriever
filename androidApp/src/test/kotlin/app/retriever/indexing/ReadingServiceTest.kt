package app.retriever.indexing

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.retriever.core.Backend
import app.retriever.core.DeviceProfile
import app.retriever.core.Embedder
import app.retriever.core.Kind
import app.retriever.core.ModelVariant
import app.retriever.core.Part
import app.retriever.core.Piece
import app.retriever.core.ReadingProgress
import app.retriever.core.Retriever
import app.retriever.core.Signature
import app.retriever.core.SourceFile
import app.retriever.core.Tier
import app.retriever.core.db.IndexDb
import app.retriever.indexing.IndexService.Companion.INDEX
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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReadingServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(context)

    @After fun stop() = stopKoin()

    private class Model : Embedder {
        @Volatile var closed = false
        override val signature = Signature("M740/test/vt70/d256/t512")
        override val backend = Backend.CPU

        override suspend fun embedRaw(parts: List<Part>) = FloatArray(768).also { it[0] = 1f }

        override fun close() {
            closed = true
        }
    }

    private fun withModel(model: Embedder, tuned: Boolean = true, extract: (SourceFile) -> List<Piece> = { emptyList() }) = testRetriever(
        files = listOf(testFile("report.pdf", Kind.DOC)),
        extract = extract,
        embedder = model,
        profile = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 5, false, false).takeIf { tuned },
        wrapDriver = {
            it.close()
            AndroidSqliteDriver(IndexDb.Schema, context, null)
        },
    )

    /** Starts the service as IndexWorker does, with Koin's graph around [retriever]. */
    private fun start(retriever: Retriever, trial: Boolean = false): ReadingService {
        startKoin {
            androidContext(context)
            modules(
                module {
                    single { retriever }
                    single { IndexService(context, retriever) }
                    single { Calibrator(context, retriever, get()) }
                    single {
                        val download = ModelDownload(context, context.cacheDir.resolve("service-models"))
                        ModelRepository(context, download, retriever, CoroutineScope(Dispatchers.IO), get())
                    }
                },
            )
        }
        val notification = Notifications.progress(context, ReadingProgress(), null, Notifications.pauseIntent(context))
        assertTrue(ReadingService.start(context, notification, trial))
        val intent = shadowOf(context as Application).nextStartedService
        return Robolectric.buildService(ReadingService::class.java, intent).create().startCommand(0, 1).get()
    }

    private fun stopped(service: ReadingService): Boolean {
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(service).isStoppedBySelf
    }

    private fun awaitStopped(service: ReadingService) = runBlocking { withTimeout(10.seconds) { while (!stopped(service)) delay(10) } }

    private fun queued() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(INDEX).get()

    /** On a charger the service starts the next batch itself instead of a follow-up, then stops once there's nothing left. */
    @Test fun aHeldRunGoesOnBatchAfterBatchThenStops() {
        val model = Model()
        lateinit var retriever: Retriever
        retriever = withModel(model) {
            retriever.notifyChanged() // the first batch ends SOON, as one past its budget does
            emptyList()
        }
        val service = start(retriever)
        assertEquals(Notifications.PROGRESS_ID, shadowOf(service).lastForegroundNotificationId)
        awaitStopped(service)
        assertTrue("the second batch ran here and ended DONE: no follow-up to schedule", queued().isEmpty())
        assertTrue("done, the model goes", model.closed)
    }

    /** The download finished with the app closed: the service tunes it, after it holds its foreground service. */
    @Test fun aHeldRunTunesADownloadedModel() {
        val retriever = withModel(Model(), tuned = false)
        runBlocking { retriever.refreshSetUp() }
        assertEquals(false, retriever.setUp.value)
        val service = start(retriever)
        awaitStopped(service)
        assertEquals(true, retriever.setUp.value)
    }

    @Test fun aHeldRunWaitsForABatchStillRunning() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0, wrapDriver = {
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
        val direct = async(Dispatchers.Default) { retriever.taste() }
        try {
            withTimeout(10.seconds) { entered.await() }
            val service = start(retriever, trial = true)
            delay(500)
            assertFalse("the service waits for the core lock", stopped(service))
            retriever.cancelBatch()
            direct.await()
            awaitStopped(service)
            assertEquals(2, calls)
            assertFalse(retriever.hasPendingTaste())
        } finally {
            retriever.cancelBatch()
            direct.cancel()
        }
    }
}
