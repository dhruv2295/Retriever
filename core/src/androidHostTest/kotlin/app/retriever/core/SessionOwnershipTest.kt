package app.retriever.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SessionOwnershipTest {
    @Test fun suspendedOldCleanupCannotClearANewerSession() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = newStore()
        val r = Retriever(
            store, FakeSource(), FakeExtractor(), FakeFactory(), FakeMonitor(),
            object : ContentHasher {
                override suspend fun quickHash(uri: String, size: Long): String? = null
                override suspend fun fullHash(uri: String): String? = null
            },
            PIXEL.copy(platform = Platform.IOS), { true }, { true }, { _, _ -> },
            System::currentTimeMillis, { 0L }, CoreDispatchers(dispatcher, watchdog = dispatcher),
        )
        val first = r.startProcessing(false)
        // Start owns the command lock but has suspended before its IO transaction. Old cleanup sees the old prefs.
        val newer = async(start = CoroutineStart.UNDISPATCHED) { r.startProcessing(false) }
        val stale = async(start = CoroutineStart.UNDISPATCHED) { r.endReadNowSession(first) }
        runCurrent()
        val second = newer.await()
        assertNotEquals(first, second)
        assertFalse(stale.await())
        assertEquals(second, r.readNowSession)
        assertTrue(r.prefs.value.started)
        assertEquals(50, ReadingPolicy.batteryFloor(r.prefs.value))
        // A callback from the old task must neither process under nor cancel its successor's identity.
        r.indexBatch(1.seconds, expectedSession = first)
        assertEquals(0, r.state.value.processed)
        assertEquals(second, r.readNowSession)
        var nativeCancels = 0
        assertFalse(r.endReadNowSession(first) { nativeCancels++ })
        assertEquals(0, nativeCancels, "old expiration must not cancel newer native reads")
        assertTrue(r.endReadNowSession(second) { nativeCancels++ })
        assertEquals(1, nativeCancels)
        assertFalse(r.endReadNowSession(second) { nativeCancels++ }, "all end paths are idempotent")
        assertEquals(1, nativeCancels)
        assertEquals(75, ReadingPolicy.batteryFloor(r.prefs.value))
        assertEquals(0L, r.readNowSession)
        r.close()
    }

    @Test fun automaticBatchNeverAdoptsAnExplicitRun() = runTest {
        val store = newStore()
        val file = testFile("waiting.txt")
        val r = testRetriever(store, listOf(file), FakeExtractor(), probe = PIXEL.copy(platform = Platform.IOS))
        val session = r.startProcessing(false)
        r.indexBatch(1.seconds, expectedSession = 0)
        assertEquals(0, r.state.value.processed)
        assertEquals(session, r.readNowSession)
        assertEquals(50, ReadingPolicy.batteryFloor(r.prefs.value))
        r.pause()
        assertEquals(0L, r.readNowSession)
        r.close()
    }
}
