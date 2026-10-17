package app.retriever

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.retriever.core.Kind
import app.retriever.core.Retriever
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The worker finds its Retriever through Koin, not through the Application, so a test module is enough. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class IndexWorkerTest {
    @After fun stop() = stopKoin()

    private fun run(retriever: Retriever, platformReady: Boolean): ListenableWorker.Result {
        startKoin { modules(module { single { retriever }; single(PlatformReady) { { platformReady } } }) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        return runBlocking { TestListenableWorkerBuilder<IndexWorker>(context).build().doWork() }
    }

    private fun listed(retriever: Retriever) = runBlocking { retriever.counts().first().byKind[Kind.DOC] ?: 0L }

    @Test fun syncsTheFileListFromTheInjectedRetriever() {
        val retriever = testRetriever(files = listOf(testFile("report.pdf", Kind.DOC)))
        assertEquals(ListenableWorker.Result.success(), run(retriever, platformReady = true))
        assertEquals(1L, listed(retriever))
    }

    @Test fun doesNothingWithoutAccessToFiles() {
        val retriever = testRetriever(files = listOf(testFile("report.pdf", Kind.DOC)))
        assertEquals(ListenableWorker.Result.success(), run(retriever, platformReady = false))
        assertEquals(0L, listed(retriever))
    }
}
