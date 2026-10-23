package app.retriever.core.android

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OptionalLabelsTest {
    @Test fun aLabelerFailureKeepsThePictureWithoutLabels() = runTest {
        val logged = mutableListOf<Throwable?>()
        val labels = optionalLabels({ _, t -> logged += t }) { throw IllegalStateException("labeler down") }
        assertEquals("", labels)
        assertEquals(1, logged.size)
    }

    @Test fun cancellationStillPropagates() = runTest {
        assertFailsWith<CancellationException> { optionalLabels({ _, _ -> }) { throw CancellationException("stopped") } }
    }
}
