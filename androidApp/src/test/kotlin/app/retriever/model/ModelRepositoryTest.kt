package app.retriever.model

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.retriever.core.ModelBuild
import app.retriever.core.ModelVariant
import app.retriever.testRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDownloadManager
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ModelRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val manager = shadowOf(context.getSystemService(DownloadManager::class.java))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** DownloadManager's polled progress, fed by hand: Robolectric never reports a Wi-Fi wait. */
    private val polls = Channel<ModelDownload.Progress>(Channel.UNLIMITED)
    private val download = ModelDownload(context, dir)
    private val repo = ModelRepository(context, download, testRetriever(), scope) {
        polls.receiveAsFlow().transformWhile {
            emit(it)
            !it.done && !it.failed
        }
    }

    private val bytes = "the model".toByteArray()
    private val build = ModelBuild(
        ModelVariant.entries.first(),
        "emb_repo.litertlm",
        bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        ShadowDownloadManager.reset()
        ShadowStatFs.registerStats(dir, 1_000_000, 900_000, 900_000)
    }

    @After fun tearDown() = scope.cancel()

    private fun progress(bytes: Long = 0, done: Boolean = false, failed: Boolean = false, wifi: Boolean = false) =
        polls.trySend(ModelDownload.Progress(bytes, 100, done, failed, wifi))

    private fun awaitState(want: (ModelState) -> Boolean) = runBlocking { withTimeout(5_000) { repo.state.first(want) } }

    @Test fun startDownloadsVerifiesAndPrunes() {
        val old = File(dir, "emb_old.litertlm").apply { writeText("old") }
        repo.start(build)
        progress(bytes = 40)
        assertEquals(ModelState.Downloading(0.4f, false), awaitState { it == ModelState.Downloading(0.4f, false) })
        assertEquals(1, manager.requestCount)
        download.file(build).writeBytes(bytes)
        progress(done = true)
        awaitState { it == ModelState.Done }
        assertTrue(download.ready(build))
        assertFalse(old.exists())
    }

    @Test fun waitsForWifi() {
        repo.start(build)
        progress(wifi = true)
        awaitState { it == ModelState.WaitingForWifi }
        progress(bytes = 10)
        awaitState { it is ModelState.Downloading && !it.indeterminate }
    }

    @Test fun damagedFileFailsAndIsDeleted() {
        repo.start(build)
        progress(bytes = 50)
        awaitState { it is ModelState.Downloading && !it.indeterminate }
        download.file(build).writeBytes(ByteArray(bytes.size))
        progress(done = true)
        assertEquals(ModelState.Failed(ModelRepository.DAMAGED), awaitState { it is ModelState.Failed })
        assertFalse(download.file(build).exists())
        repo.clearFailure()
        assertEquals(ModelState.Missing, repo.state.value)
    }

    @Test fun cancelRemovesTheDownload() {
        repo.start(build)
        progress(bytes = 20)
        awaitState { it is ModelState.Downloading && !it.indeterminate }
        val id = download.active(build)!!
        runBlocking { repo.cancel(build) }
        assertEquals(ModelState.Missing, repo.state.value)
        assertNull(manager.getRequest(id))
        assertFalse(download.file(build).exists())
    }

    @Test fun aFailingCheckFailsTheDownloadInsteadOfCrashing() {
        repo.start(build)
        progress(bytes = 50)
        awaitState { it is ModelState.Downloading && !it.indeterminate }
        // The hash read throws: a directory can't be opened as a stream.
        download.file(build).mkdirs()
        progress(done = true)
        assertEquals(ModelState.Failed(ModelRepository.STOPPED), awaitState { it is ModelState.Failed })
    }

    /** DownloadManager.query is nullable, e.g. when its provider is gone. */
    @Implements(DownloadManager::class)
    class NullQuery : ShadowDownloadManager() {
        @Implementation override fun query(query: DownloadManager.Query): Cursor? = null
    }

    @Config(shadows = [NullQuery::class])
    @Test
    fun aNullCursorReadsAsNoDownload() {
        assertNull(download.active(build))
        runBlocking { repo.sync(build) }
        assertEquals(ModelState.Missing, repo.state.value)
        val last = runBlocking { download.progress(1).first() }
        assertTrue(last.failed)
    }

    @Test fun noSpaceSaysSo() {
        ShadowStatFs.registerStats(dir, 1, 0, 0)
        repo.start(build)
        assertEquals(ModelState.Failed(spaceMessage(build)), awaitState { it is ModelState.Failed })
        assertEquals(0, manager.requestCount)
    }
}
