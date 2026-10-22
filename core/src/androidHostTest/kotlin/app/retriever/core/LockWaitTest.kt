package app.retriever.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A run that replaces a cancelled one (WorkManager's REPLACE) must not end DONE on the lock the old batch still holds. */
class LockWaitTest {
    @Test
    fun aRunStartedWhileACancelledBatchUnwindsWaitsAndReads() = runBlocking {
        withTimeout(20.seconds) {
            val entered = CompletableDeferred<Unit>()
            var calls = 0
            val extractor = object : Extractor {
                override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
                    if (calls++ == 0) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    return listOf(textPiece("words"))
                }
            }
            val r = testRetriever(newStore(), listOf(testFile("a.txt")), extractor)
            r.refreshSetUp()
            val cancelled = async { r.indexBatch(1.minutes) }
            entered.await()
            val replacing = async { r.indexBatch(1.minutes) }
            r.cancelBatch()
            cancelled.await()
            replacing.await()
            assertEquals(2, calls, "the replacing run read the file the cancelled one gave back")
        }
    }
}
