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
import app.retriever.core.DeviceState
import app.retriever.core.Embedder
import app.retriever.core.FaceReader
import app.retriever.core.FoundFace
import app.retriever.core.Kind
import app.retriever.core.ModelVariant
import app.retriever.core.Part
import app.retriever.core.PeopleModel
import app.retriever.core.Piece
import app.retriever.core.Retriever
import app.retriever.core.Signature
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
import app.retriever.core.Thermal
import app.retriever.core.ThermalRest
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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

    private fun withModel(
        model: Embedder,
        tuned: Boolean = true,
        deviceState: (() -> DeviceState)? = null,
        extract: suspend (SourceFile) -> List<Piece> = {
            emptyList()
        },
    ) = testRetriever(
        files = listOf(testFile("report.pdf", Kind.DOC)),
        extract = extract,
        embedder = model,
        deviceState = deviceState,
        profile = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 5, false, false).takeIf { tuned },
        wrapDriver = {
            it.close()
            AndroidSqliteDriver(IndexDb.Schema, context, null)
        },
    )

    /** Starts a user-requested service, with Koin's graph around [retriever]. */
    private fun start(retriever: Retriever, refused: Boolean = false): ReadingService {
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
        runBlocking { GlobalContext.get().get<IndexService>().start() }
        val intent = shadowOf(context as Application).nextStartedService
        val controller = Robolectric.buildService(ReadingService::class.java, intent).create()
        if (refused) shadowOf(controller.get()).setThrowInStartForeground(SecurityException("foreground promotion refused"))
        return controller.startCommand(0, 1).get()
    }

    private fun stopped(service: ReadingService): Boolean {
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(service).isStoppedBySelf
    }

    private fun awaitStopped(service: ReadingService) = runBlocking { withTimeout(10.seconds) { while (!stopped(service)) delay(10) } }

    private fun queued() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(INDEX).get().filter { !it.state.isFinished }

    /** The service starts the next batch itself instead of a follow-up, then stops, and ends the session, once there's nothing left. */
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
        assertFalse("batch completion releases CPU wakefulness", ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue("the second batch ran here and ended DONE: no follow-up to schedule", queued().isEmpty())
        assertTrue("done, the model goes", model.closed)
        assertFalse("the session ends with the service", retriever.prefs.value.started)
    }

    @Test fun aBatteryPauseKeepsTheHostWithoutCpuWakefulnessAndResumesAfterRecovery() = runBlocking {
        val battery = java.util.concurrent.atomic.AtomicInteger(80)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val retriever = withModel(Model(), deviceState = { DeviceState(8.0, false, battery.get(), Thermal.NONE) }) {
            if (attempts.getAndIncrement() == 0) {
                entered.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    canceled.complete(Unit)
                }
            }
            emptyList()
        }
        val service = start(retriever)
        val index = GlobalContext.get().get<IndexService>()
        try {
            withTimeout(10.seconds) { entered.await() }
            val activeWake = ShadowPowerManager.getLatestWakeLock()
            battery.set(49)
            withTimeout(10.seconds) { canceled.await() }
            withTimeout(10.seconds) { while (activeWake.isHeld) delay(10) }
            assertTrue(index.foregroundHeld())
            assertTrue(retriever.prefs.value.started)
            assertTrue(retriever.prefs.value.processingRequested)
            assertFalse(stopped(service))
            battery.set(80)
            withTimeout(25.seconds) { while (!stopped(service)) delay(10) }
            assertEquals(2, attempts.get())
            assertFalse(retriever.prefs.value.processingRequested)
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        } finally {
            service.onDestroy()
        }
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
        val cleaning = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val retriever = testRetriever(files = listOf(testFile("a.txt", Kind.DOC)), ramGb = 3.0, wrapDriver = {
            it.close()
            AndroidSqliteDriver(IndexDb.Schema, context, null)
        }, extract = {
            if (calls++ == 0) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        release.await()
                    }
                }
            }
            emptyList()
        })
        retriever.planTaste()
        val direct = async(Dispatchers.Default) { retriever.taste() }
        try {
            withTimeout(10.seconds) { entered.await() }
            val service = start(retriever)
            withTimeout(10.seconds) { cleaning.await() }
            assertFalse("the service waits while the cancelled batch still owns the core lock", stopped(service))
            release.complete(Unit)
            direct.await()
            awaitStopped(service)
            assertEquals(2, calls)
            assertFalse(retriever.hasPendingTaste())
        } finally {
            release.complete(Unit)
            retriever.cancelBatch()
            direct.cancel()
        }
    }

    @Test fun refusedForegroundPromotionEndsTheSessionAndSchedulesAPlainReader() = runBlocking {
        val retriever = withModel(Model())
        val service = start(retriever, refused = true)
        val index = GlobalContext.get().get<IndexService>()
        try {
            withTimeout(10.seconds) { while (index.foregroundHeld() || queued().isEmpty()) delay(10) }
            assertTrue(stopped(service))
            assertFalse(retriever.prefs.value.started)
            assertEquals(0L, queued().single().initialDelayMillis)
        } finally {
            service.onDestroy()
        }
    }

    /** Resume after Pause, then Android's timeout: each waits for the cancelled batch's cleanup, and the fallback comes after. */
    @Test fun aReplacementAndATimeoutWaitForTheCancelledBatchCleanup() = runBlocking {
        val entered = Semaphore(0)
        val cleaning = Semaphore(0)
        val release = Semaphore(0)
        val retriever = withModel(Model()) {
            entered.release()
            try {
                awaitCancellation()
            } finally {
                cleaning.release()
                release.acquire()
            }
        }
        val service = start(retriever)
        val index = GlobalContext.get().get<IndexService>()
        try {
            assertTrue(entered.tryAcquire(10, TimeUnit.SECONDS))
            val first = ShadowPowerManager.getLatestWakeLock()
            assertTrue("CPU stays awake while the batch is executing", first.isHeld)
            assertEquals("Retriever:ReadingBatch", shadowOf(first).tag)
            index.pause()
            index.start()
            service.onStartCommand(shadowOf(context as Application).nextStartedService, 0, 2)
            assertTrue(cleaning.tryAcquire(10, TimeUnit.SECONDS))
            assertTrue(index.foregroundHeld())
            assertEquals("the replacement waits for the old batch's cleanup", 0, entered.availablePermits())
            release.release()
            assertTrue(entered.tryAcquire(10, TimeUnit.SECONDS))
            assertFalse("Pause released the old batch's wake lock", first.isHeld)
            val second = ShadowPowerManager.getLatestWakeLock()
            assertTrue("the replacement owns its own batch wake lock", second.isHeld)
            assertFalse("the old run's end cannot stop the replacement", stopped(service))

            service.onTimeout(2, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertTrue(cleaning.tryAcquire(10, TimeUnit.SECONDS))
            assertTrue(index.foregroundHeld())
            assertTrue("no fallback while the cancelled batch still holds the service", queued().none { it.initialDelayMillis == 0L })
            release.release()
            withTimeout(10.seconds) { while (index.foregroundHeld() || queued().none { it.initialDelayMillis == 0L }) delay(10) }
            assertFalse(retriever.prefs.value.started)
            assertEquals(0L, queued().single().initialDelayMillis)
            assertFalse("cancelled batch cleanup releases CPU wakefulness", second.isHeld)
        } finally {
            release.release(2)
            service.onDestroy()
        }
    }

    /**
     * A batch that ends DONE with nothing read while the request is unfinished (People's model waits for Wi-Fi, a failed NPU
     * build for the generic download) ended no session: the service read again every 10 s under a wake lock, for hours.
     */
    @Test fun aDoneWithNothingReadEndsTheSession() {
        val waitsForWifi = object : FaceReader {
            override suspend fun faces(uri: String) = emptyList<FoundFace>()

            override suspend fun ready(mobileData: Boolean) = PeopleModel.WAITING_FOR_WIFI
        }
        val retriever = testRetriever(files = listOf(testFile("photo.jpg", Kind.PHOTO)), faceReader = waitsForWifi, wrapDriver = {
            it.close()
            AndroidSqliteDriver(IndexDb.Schema, context, null)
        })
        runBlocking { retriever.updatePrefs { it.copy(recognizePeople = true) } }
        val service = start(retriever)
        awaitStopped(service)
        assertTrue("the request waits for the person", retriever.prefs.value.processingRequested)
        assertEquals("plain jobs carry on", 0L, queued().single().initialDelayMillis)
        assertFalse(retriever.prefs.value.started)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test fun anotherAppInUseKeepsExplicitPreparationAndProcessingRunning() = runBlocking {
        var calls = 0
        val retriever = withModel(Model(), tuned = false, deviceState = { DeviceState(8.0, true, 80, Thermal.NONE, screenOn = true) }) {
            calls++
            emptyList()
        }
        retriever.refreshSetUp()
        val service = start(retriever)
        val index = GlobalContext.get().get<IndexService>()
        try {
            awaitStopped(service)
            assertEquals(1, calls)
            assertEquals("preparation uses the same eligibility as processing", true, retriever.setUp.value)
            assertEquals(StopReason.DONE, retriever.state.value.lastStop)
            assertFalse(index.foregroundHeld())
            assertFalse(retriever.prefs.value.started)
            assertEquals(null, shadowOf(context as Application).nextStartedService)
        } finally {
            service.onDestroy()
        }
    }

    @Test fun pauseCancelsConservativeProcessingWhileAnotherAppIsInUse() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val retriever = withModel(Model(), deviceState = { DeviceState(8.0, true, 80, Thermal.NONE, screenOn = true) }) {
            calls++
            entered.complete(Unit)
            awaitCancellation()
        }
        val service = start(retriever)
        val index = GlobalContext.get().get<IndexService>()
        try {
            withTimeout(10.seconds) { entered.await() }
            index.pause()
            awaitStopped(service)
            assertEquals(1, calls)
            assertTrue(retriever.prefs.value.paused)
            assertFalse(index.foregroundHeld())
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        } finally {
            service.onDestroy()
        }
    }

    @Test fun aServiceErrorReleasesItsHoldBeforeSchedulingARetry() {
        val retriever = testRetriever(
            files = listOf(testFile("report.pdf", Kind.DOC)),
            embedder = Model(),
            beforeOpen = { error("model file is unavailable") },
            profile = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 5, false, false),
            wrapDriver = {
                it.close()
                AndroidSqliteDriver(IndexDb.Schema, context, null)
            },
        )
        val service = start(retriever)
        awaitStopped(service)
        assertFalse(GlobalContext.get().get<IndexService>().foregroundHeld())
        assertFalse("failed batch releases CPU wakefulness", ShadowPowerManager.getLatestWakeLock().isHeld)
        assertFalse(retriever.prefs.value.started)
        assertEquals(ThermalRest.RECHECK.inWholeMilliseconds, queued().single().initialDelayMillis)
    }
}
