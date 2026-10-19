package app.retriever.indexing

import android.os.Process
import app.retriever.core.Backend
import app.retriever.core.CoreDispatchers
import app.retriever.core.DeviceProfile
import app.retriever.core.Embedder
import app.retriever.core.Kind
import app.retriever.core.ModelVariant
import app.retriever.core.Part
import app.retriever.core.Prefix
import app.retriever.core.Signature
import app.retriever.core.Tier
import app.retriever.core.newIndexingDispatcher
import app.retriever.core.withBackgroundPriority
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.minutes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], application = android.app.Application::class)
class IndexingDispatcherTest {
    private val indexingDispatcher = newIndexingDispatcher()

    @After fun close() = indexingDispatcher.close()
    private fun priority() = Process.getThreadPriority(Process.myTid())

    @Test fun backgroundPrioritySurvivesSuspensionWithoutDemotingTheCaller() = runBlocking {
        val caller = Process.myTid()
        val before = priority()
        repeat(3) {
            withContext(indexingDispatcher) {
                assertNotEquals(caller, Process.myTid())
                assertEquals(Process.THREAD_PRIORITY_BACKGROUND, priority())
                delay(1)
                assertEquals(Process.THREAD_PRIORITY_BACKGROUND, priority())
            }
        }
        assertEquals(before, priority())
        withContext(Dispatchers.Default) { assertEquals(Process.THREAD_PRIORITY_DEFAULT, priority()) }
    }

    @Test fun nativeCreationUsesBackgroundPriorityAndRestoresTheCallerEvenOnFailure() {
        val before = priority()
        // Robolectric has no native LiteRT pools; verify the synchronous creation contract they inherit.
        withBackgroundPriority {
            assertEquals(Process.THREAD_PRIORITY_BACKGROUND, priority())
            withBackgroundPriority { assertEquals(Process.THREAD_PRIORITY_BACKGROUND, priority()) }
            assertEquals(Process.THREAD_PRIORITY_BACKGROUND, priority())
        }
        assertEquals(before, priority())
        assertThrows(IllegalStateException::class.java) {
            withBackgroundPriority {
                assertEquals(Process.THREAD_PRIORITY_BACKGROUND, priority())
                error("native open failed")
            }
        }
        assertEquals(before, priority())
    }

    @Test fun indexingYieldsPriorityAndSearchPausesItWithoutDemotingTheQueryCaller() = runBlocking {
        withTimeout(10_000) {
            val calls = mutableListOf<Int>()
            var extracted = false
            val queryEntered = CompletableDeferred<Unit>()
            val finishQuery = CompletableDeferred<Unit>()
            val model = object : Embedder {
                override val signature = Signature.of(ModelVariant.M740.generic, 70, 512)
                override val backend = Backend.CPU

                override suspend fun embedRaw(parts: List<Part>): FloatArray {
                    calls += priority()
                    if ((parts.single() as? Part.Text)?.text == Prefix.query("report")) {
                        queryEntered.complete(Unit)
                        finishQuery.await()
                    }
                    return FloatArray(768).also { it[0] = 1f }
                }

                override fun close() {}
            }
            val r = testRetriever(
                files = listOf(testFile("report.txt", Kind.DOC)),
                extract = {
                    assertEquals(Process.THREAD_PRIORITY_BACKGROUND, priority())
                    extracted = true
                    emptyList()
                },
                embedder = model,
                beforeOpen = {
                    withBackgroundPriority { assertEquals(Process.THREAD_PRIORITY_BACKGROUND, priority()) }
                    assertEquals(Process.THREAD_PRIORITY_DEFAULT, priority())
                },
                profile = DeviceProfile(Tier.BALANCED, ModelVariant.M740, Backend.CPU, 70, 5, false, false),
                dispatchers = CoreDispatchers(indexing = indexingDispatcher),
            )
            r.uiVisible = true
            r.setUiBusy(true)
            val batch = async { r.indexBatch(1.minutes) }
            r.state.first { it.running && it.pendingAtStart > 0 }
            assertEquals(listOf(Process.THREAD_PRIORITY_DEFAULT), calls)
            calls.clear()
            val query = async { r.search("report") }
            queryEntered.await()
            r.setUiBusy(false)
            withContext(indexingDispatcher) {} // Drain work already queued before inspecting the query's gate.
            assertFalse(extracted)
            assertFalse(batch.isCompleted)
            assertEquals(listOf(Process.THREAD_PRIORITY_DEFAULT), calls)
            finishQuery.complete(Unit)
            assertTrue(query.await().vectorLane)
            batch.await()
            assertTrue(r.state.value.toString(), extracted)
            assertEquals(listOf(Process.THREAD_PRIORITY_DEFAULT, Process.THREAD_PRIORITY_BACKGROUND), calls)
        }
    }
}
