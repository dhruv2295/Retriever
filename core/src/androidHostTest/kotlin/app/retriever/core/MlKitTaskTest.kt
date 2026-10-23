package app.retriever.core

import app.retriever.core.android.awaitResult
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** ML Kit's OCR and labeling tasks, awaited by the extractor. */
class MlKitTaskTest {
    @Test
    fun aTaskCancelledUnderALiveCallerFailsInsteadOfLookingLikeAStop() = runBlocking {
        assertEquals("text", Tasks.forResult("text").awaitResult())
        assertFailsWith<IllegalArgumentException> { Tasks.forException<String>(IllegalArgumentException("bad image")).awaitResult() }
        // A recognizer closed under a read cancels its task; the read must neither hang nor stop the batch.
        val closed = assertFailsWith<IllegalStateException> { Tasks.forCanceled<String>().awaitResult() }
        assertFalse(closed is CancellationException)
    }

    @Test
    fun aStopStillStopsAReadWaitingOnATask() = runBlocking {
        val never = TaskCompletionSource<String>()
        val read = async(start = CoroutineStart.UNDISPATCHED) { never.task.awaitResult() }
        read.cancel()
        assertFailsWith<CancellationException> { read.await() }
        Unit
    }
}
