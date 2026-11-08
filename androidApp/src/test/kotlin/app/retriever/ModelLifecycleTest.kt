package app.retriever

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.core.Backend
import app.retriever.core.CoreDispatchers
import app.retriever.core.DeviceProfile
import app.retriever.core.Embedder
import app.retriever.core.ModelVariant
import app.retriever.core.Part
import app.retriever.core.Signature
import app.retriever.core.Tier
import app.retriever.di.AppScope
import app.retriever.indexing.IndexService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], application = android.app.Application::class)
class ModelLifecycleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun workManager() = WorkManagerTestInitHelper.initializeTestWorkManager(
        context,
        Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
    )

    @After fun stop() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        stopKoin()
    }

    @Test fun processStopReleasesTheIdleModelWithoutATrimCallback() = runTest {
        var closed = false
        val model = object : Embedder {
            override val signature = Signature("test")
            override val backend = Backend.CPU

            override suspend fun embedRaw(parts: List<Part>) = FloatArray(768).also { it[0] = 1f }

            override fun close() {
                closed = true
            }
        }
        val r = testRetriever(
            embedder = model,
            profile = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 5, false, false),
            dispatchers = CoreDispatchers(StandardTestDispatcher(testScheduler)),
        )
        r.uiVisible = true
        r.refreshSetUp()
        r.warmSearch()
        assertFalse(closed)
        val scope = this
        startKoin {
            modules(
                module {
                    single { r }
                    single { IndexService(context, r) }
                    single<CoroutineScope>(AppScope) { scope }
                },
            )
        }
        RetrieverApp().onStop(ProcessLifecycleOwner.get())
        advanceUntilIdle()
        assertFalse(r.uiVisible)
        assertTrue(closed)
    }
}
