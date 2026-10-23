package app.retriever.ui.components

import android.content.ComponentCallbacks2
import androidx.core.graphics.createBitmap
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

/** The loader's own rules, with a stand-in decode: what a scroll cancels, how many decodes run, what stays in memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ThumbnailsTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun photo(n: Int = 1) = SourceFile("content://thumbs.test/$n.jpg", "$n.jpg", "DCIM", "image/jpeg", Kind.PHOTO, 10, 1)

    /** A cell that scrolls away cancels its decode through the signal the provider holds, and leaves no miss behind. */
    @Test fun cancellingALoadCancelsItsDecode() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val heard = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            Thumbnails.load(app, photo(), 256, "", Dispatchers.IO) { signal ->
                signal.setOnCancelListener { heard.countDown() }
                started.complete(Unit)
                heard.await(5, SECONDS)
                null
            }
        }
        started.await()
        withTimeout(5_000) { job.cancelAndJoin() }
        assertEquals("the decode never heard of the cancel", 0L, heard.count)
        // Not remembered as a file without a picture: the next look decodes it.
        assertNotNull(Thumbnails.load(app, photo(), 256, "", Dispatchers.IO) { createBitmap(4, 4) })
    }

    /** A fling: a few decodes run at once, and the ones still queued when their cells scroll away never start. */
    @Test fun aFlingDecodesAFewAtATimeAndDropsTheQueuedOnes() = runBlocking {
        val gate = CountDownLatch(1)
        val started = AtomicInteger()
        val busy = CountDownLatch(Thumbnails.THUMBNAIL_WORKERS)
        val jobs = (1..20).map { n ->
            launch(Dispatchers.Default) {
                Thumbnails.load(app, photo(n), 256, "", Thumbnails.dispatcherFor(Kind.PHOTO)) {
                    started.incrementAndGet()
                    busy.countDown()
                    gate.await(5, SECONDS)
                    null
                }
            }
        }
        // Every worker holds a decode, so nothing else can start before the cells scroll away.
        assertTrue("every worker decodes", busy.await(5, SECONDS))
        jobs.forEach { it.cancel() }
        gate.countDown()
        jobs.joinAll()
        assertEquals("only the workers' decodes ran", Thumbnails.THUMBNAIL_WORKERS, started.get())
    }

    /** A decode that finishes after its cell left is kept, so scrolling back finds it in memory. */
    @Test fun aDecodeThatFinishesAfterItsCellLeftIsKept() = runBlocking {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val job = launch(Dispatchers.Default) {
            Thumbnails.load(app, photo(), 256, "", Dispatchers.IO) {
                entered.countDown()
                finish.await(5, SECONDS)
                createBitmap(4, 4)
            }
        }
        assertTrue(entered.await(5, SECONDS))
        job.cancel()
        finish.countDown()
        job.join()
        assertNotNull(Thumbnails.cached(app, photo(), 256))
    }

    /** A cell seeded from memory gets the very picture its load returns, so it neither recomposes nor crossfades again. */
    @Test fun aCachedPictureIsTheOneItsLoadReturns() = runBlocking {
        val loaded = Thumbnails.load(app, photo(), 256, "", Dispatchers.IO) { createBitmap(4, 4) }
        assertSame(loaded, Thumbnails.cached(app, photo(), 256))
        assertSame(loaded, Thumbnails.load(app, photo(), 256, "", Dispatchers.IO) { error("decoded again") })
    }

    /** A brief hide keeps the pictures for the way back; once the hidden app is cached they make room for the indexer. */
    @Test fun onlyACachedAppEmptiesTheCache() = runBlocking {
        assertNotNull(Thumbnails.load(app, photo(), 256, "", Dispatchers.IO) { createBitmap(4, 4) })
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertNotNull(Thumbnails.cached(app, photo(), 256))
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertNull(Thumbnails.cached(app, photo(), 256))
    }
}
