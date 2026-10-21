package app.retriever.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class TrialReadTest {
    private val sig = Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens)
    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    @Test
    fun currentFilePrecedesExtractionAndRecentSuccessesAreBoundedAndRestored() = test {
        val files = (1..30).map { testFile("d$it.txt", modifiedAt = it * 7_200_000L) }
        val store = newStore(sig).also { it.setProfile(PROFILE) }
        lateinit var r: Retriever
        val seen = mutableListOf<SourceFile>()
        val extractor = FakeExtractor(files.associate { it.name to listOf(textPiece("actual content")) }, onExtract = {
            assertEquals(it, r.state.value.currentFile)
            assertEquals(it.name, r.state.value.current)
            assertFalse(it in r.state.value.recentFiles)
            seen += it
        })
        r = testRetriever(store, files, extractor).apply { uiVisible = true }
        val plan = r.planTaste()
        r.taste()
        assertEquals(plan.ids, seen.map { it.uri })
        assertEquals(seen.asReversed().take(24), r.state.value.recentFiles)
        assertNull(r.state.value.currentFile)
        r.taste()
        assertEquals(seen.asReversed().take(24), r.state.value.recentFiles, "a batch boundary keeps the history")
        val restarted = testRetriever(store, files, FakeExtractor())
        restarted.refreshSetUp()
        assertEquals(r.state.value.recentFiles, restarted.state.value.recentFiles)
    }

    @Test
    fun failuresAndCancellationNeverBecomeRecentSuccesses() = test {
        val file = testFile("bad.txt")
        val store = newStore(sig).also { it.setProfile(PROFILE) }
        val failed = testRetriever(store, listOf(file), FakeExtractor(fail = true)).apply { uiVisible = true }
        failed.planTaste()
        failed.taste()
        assertTrue(failed.state.value.recentFiles.isEmpty())
        assertNull(failed.state.value.currentFile)
        val restarted = testRetriever(store, listOf(file), FakeExtractor())
        restarted.refreshSetUp()
        assertTrue(restarted.state.value.recentFiles.isEmpty(), "SKIPPED is not INDEXED")

        val fresh = newStore(sig).also { it.setProfile(PROFILE) }
        lateinit var cancelled: Retriever
        cancelled = testRetriever(fresh, listOf(file), FakeExtractor(onExtract = { cancelled.cancelBatch() })).apply { uiVisible = true }
        cancelled.planTaste()
        assertEquals(NextRun.SOON, cancelled.taste())
        assertTrue(cancelled.state.value.recentFiles.isEmpty())
        assertNull(cancelled.state.value.currentFile)
    }

    @Test
    fun foregroundHandoffWaitsForCancelledBatchThenReadsWithoutAnotherSchedulerWake() = runBlocking {
        withTimeout(20.seconds) {
            val file = testFile("a.txt")
            val store = newStore(sig).also { it.setProfile(PROFILE) }
            val entered = CompletableDeferred<Unit>()
            val hold = CompletableDeferred<Unit>()
            var first = true
            val extractor = object : Extractor {
                override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
                    if (first) {
                        first = false
                        entered.complete(Unit)
                        hold.await()
                    }
                    return emptyList()
                }
            }
            val r = testRetriever(store, listOf(file), extractor).apply { uiVisible = true }
            r.planTaste()
            val old = async { r.taste() }
            entered.await()
            r.cancelBatch()
            val foreground = async { r.taste() }
            assertEquals(NextRun.SOON, old.await())
            assertEquals(NextRun.DONE, foreground.await())
            assertEquals(0L, r.counts().first().byState[FileState.PENDING])
            assertNull(r.state.value.currentFile)
        }
    }
}
