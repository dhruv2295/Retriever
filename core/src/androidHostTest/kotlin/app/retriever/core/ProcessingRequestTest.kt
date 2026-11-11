package app.retriever.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ProcessingRequestTest {
    @Test fun batteryDropBeforeTheStartTransactionRetainsOnlyTheRequest() = runTest {
        for (setup in listOf(false, true)) {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val monitor = FakeMonitor(DeviceState(8.0, false, 74, Thermal.NONE))
            val r = testRetriever(
                newStore(),
                listOf(testFile("pending.txt")),
                FakeExtractor(),
                monitor = monitor,
                dispatchers = CoreDispatchers(dispatcher, watchdog = dispatcher),
            )
            try {
                val start = async(start = CoroutineStart.UNDISPATCHED) {
                    r.startProcessing(setup)
                }
                monitor.state = monitor.state.copy(batteryPct = 49)
                assertEquals(0L, start.await())
                assertTrue(r.prefs.value.processingRequested)
                assertFalse(r.prefs.value.started)
                assertEquals(0L, r.readNowSession)
                assertTrue(r.processingRequestGeneration > 0L)
            } finally {
                r.close()
            }
        }
    }

    @Test fun launchRefreshCannotHideAConcurrentRequestOrPause() = runBlocking {
        val r = testRetriever(newStore(), emptyList(), FakeExtractor())
        try {
            repeat(100) {
                coroutineScope {
                    launch(Dispatchers.Default) { r.refreshSetUp() }
                    launch(Dispatchers.Default) { r.requestProcessing(setup = false) }
                }
                assertTrue(r.processingRequestGeneration > 0L)
                assertTrue(r.prefs.value.processingRequested)
                coroutineScope {
                    launch(Dispatchers.Default) { r.refreshSetUp() }
                    launch(Dispatchers.Default) { r.pause() }
                }
                assertEquals(0L, r.processingRequestGeneration)
                assertFalse(r.prefs.value.processingRequested)
                assertTrue(r.prefs.value.paused)
            }
        } finally {
            r.close()
        }
    }

    @Test fun blockedRequestWaitsForChargingAndCanFinishAsOrdinaryWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = newStore()
        val monitor = FakeMonitor(DeviceState(8.0, false, 49, Thermal.NONE))
        val r =
            testRetriever(
                store,
                listOf(testFile("pending.txt")),
                FakeExtractor(),
                monitor = monitor,
                dispatchers = CoreDispatchers(dispatcher, watchdog = dispatcher),
            )
        try {
            r.updatePrefs { it.withSchedule(ReadingSchedule.MANUAL) }
            assertEquals(0L, r.startProcessing(false))
            val generation = r.processingRequestGeneration
            assertTrue(generation > 0L)
            assertTrue(r.prefs.value.processingRequested)
            assertFalse(r.prefs.value.started)
            assertEquals(0L, r.startProcessing(false), "another tap cannot bypass fifty")
            assertEquals(generation, r.processingRequestGeneration)
            assertFalse(r.finishProcessingRequest(generation), "an uncounted library is not an empty library")
            monitor.state = monitor.state.copy(charging = true, pluggedIn = true)
            r.indexBatch(20.seconds, expectedSession = 0)
            assertEquals(0L, r.readNowSession)
            assertEquals(1L, store.countsByState()[FileState.INDEXED])
            assertTrue(r.finishProcessingRequest(generation))
            assertFalse(r.prefs.value.processingRequested)
            assertEquals(ReadingSchedule.MANUAL, r.prefs.value.automaticReading)
        } finally {
            r.close()
        }
    }

    @Test fun safetyWaitsKeepTheRequestAndOwnerButUserPauseClearsBoth() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = FakeMonitor(DeviceState(8.0, false, 80, Thermal.NONE))
        val r =
            testRetriever(
                newStore(),
                listOf(testFile("pending.txt")),
                FakeExtractor(),
                monitor = monitor,
                dispatchers = CoreDispatchers(dispatcher, watchdog = dispatcher),
            )
        try {
            val owner = r.startProcessing(false)
            for ((state, stop) in listOf(
                monitor.state.copy(batteryPct = 49) to StopReason.UNPLUGGED,
                monitor.state.copy(powerSave = true) to StopReason.POWER_SAVE,
                monitor.state.copy(thermal = Thermal.SEVERE) to StopReason.HOT,
            )) {
                monitor.state = state
                assertEquals(stop, r.readingBlocker())
                assertEquals(owner, r.readNowSession)
                assertTrue(r.prefs.value.started)
                assertTrue(r.prefs.value.processingRequested)
            }
            monitor.state = DeviceState(8.0, false, 80, Thermal.NONE)
            assertNull(r.readingBlocker())
            r.endReadNowSession(owner)
            assertFalse(r.prefs.value.started)
            assertTrue(r.prefs.value.processingRequested)
            r.pause()
            assertFalse(r.prefs.value.processingRequested)
            assertEquals(0L, r.processingRequestGeneration)
        } finally {
            r.close()
        }
    }

    @Test fun oldCompletionCannotClearANewerRequest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = newStore()
        val r = testRetriever(store, emptyList(), FakeExtractor(), dispatchers = CoreDispatchers(dispatcher, watchdog = dispatcher))
        try {
            val first = r.startProcessing(false)
            r.indexBatch(10.seconds, expectedSession = first)
            val second = r.startProcessing(false)
            assertFalse(r.finishProcessingRequest(first))
            assertTrue(r.prefs.value.processingRequested)
            assertEquals(second, r.readNowSession)
            assertTrue(r.finishProcessingRequest(second))
            assertFalse(r.prefs.value.processingRequested)
            assertFalse(r.prefs.value.started)
        } finally {
            r.close()
        }
    }
}
