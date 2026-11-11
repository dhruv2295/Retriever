package app.retriever.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SetupSessionTest {
    @Test fun earlyContinueLeavesTheDemoFirstAndOnlyItsOwnerCanAdvanceToTheRest() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = newStore().also { it.setProfile(PROFILE) }
        val files = (1..120).map { testFile("doc$it.pdf", Kind.PDF) }
        val extractor = FakeExtractor(files.associate { it.name to listOf(textPiece("demo content")) })
        val r =
            testRetriever(
                store,
                files,
                extractor,
                monitor = FakeMonitor(DeviceState(8.0, false, 60, Thermal.NONE)),
                dispatchers = CoreDispatchers(dispatcher, watchdog = dispatcher),
            )
        r.refreshSetUp()
        val session = r.startProcessing(true)
        val planned = assertNotNull(r.scopeIds.value).toList()
        assertTrue(planned.isNotEmpty())
        assertTrue(planned.size < files.size, "the test must leave non-demo files waiting")
        assertFalse(assertNotNull(r.setup.value).decided)
        assertFalse(r.completeSetupSample(session), "a tap cannot skip pending demo files")
        r.finishSetup()
        assertTrue(assertNotNull(r.setup.value).decided)
        r.setReadingSchedule(ReadingSchedule.BATTERY)
        assertEquals(ReadingSchedule.BATTERY, r.prefs.value.automaticReading)
        assertEquals(planned, r.scopeIds.value, "navigation Continue and schedule changes preserve demo order")
        r.indexBatch(1.minutes, expectedSession = session)
        val demoNames = store.storedIn(planned).map { it.file.name }.toSet()
        assertEquals(demoNames, extractor.names.toSet())
        assertFalse(store.countsNow(planned).progress.notNow, "early Continue is not Not now")
        assertFalse(r.completeSetupSample(session + 1), "an unrelated host cannot change scope")
        assertTrue(r.completeSetupSample(session))
        assertNull(r.scopeIds.value)
        r.indexBatch(1.minutes, expectedSession = session)
        assertEquals(files.map { it.name }.toSet(), extractor.names.toSet())
        assertEquals(50, ReadingPolicy.batteryFloor(r.prefs.value))
        r.close()
    }

    @Test fun theDemoScreenStillWaitsForContinueAfterTheHeldReaderAdvancesScope() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = newStore().also { it.setProfile(PROFILE) }
        val files = (1..120).map { testFile("doc$it.pdf", Kind.PDF) }
        val r =
            testRetriever(
                store,
                files,
                FakeExtractor(),
                monitor = FakeMonitor(DeviceState(8.0, true, 100, Thermal.NONE)),
                dispatchers = CoreDispatchers(dispatcher, watchdog = dispatcher),
            )
        r.refreshSetUp()
        val session = r.startProcessing(true)
        r.indexBatch(1.minutes, expectedSession = session)
        assertTrue(r.completeSetupSample(session))
        assertFalse(assertNotNull(r.setup.value).decided)
        assertEquals(SetupStage.SAMPLE, assertNotNull(r.setup.value).withAccess(true).stage)
        r.finishSetup()
        assertEquals(SetupStage.DONE, assertNotNull(r.setup.value).withAccess(true).stage)
        r.close()
    }

    @Test fun automaticRecoveryAfterEarlyContinueFinishesTheDemoAtTheUsualFloor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = newStore().also { it.setProfile(PROFILE) }
        val files = (1..120).map { testFile("doc$it.pdf", Kind.PDF) }
        val monitor = FakeMonitor(DeviceState(8.0, false, 60, Thermal.NONE))
        val extractor = FakeExtractor()
        val r = testRetriever(store, files, extractor, monitor = monitor, dispatchers = CoreDispatchers(dispatcher, watchdog = dispatcher))
        r.refreshSetUp()
        r.updatePrefs { it.withSchedule(ReadingSchedule.BATTERY) }
        val session = r.startProcessing(true)
        val planned = assertNotNull(r.scopeIds.value).toList()
        r.finishSetup()
        monitor.state = monitor.state.copy(batteryPct = 49)
        r.endReadNowSession(session)
        assertEquals(75, ReadingPolicy.batteryFloor(r.prefs.value))
        assertEquals(StopReason.UNPLUGGED, r.readingBlocker())
        assertFalse(store.countsNow(planned).progress.notNow)
        monitor.state = monitor.state.copy(batteryPct = 75)
        r.indexBatch(1.minutes, expectedSession = 0)
        assertEquals(store.storedIn(planned).map { it.file.name }.toSet(), extractor.names.toSet())
        assertTrue(r.completeSetupSample(0))
        r.indexBatch(1.minutes, expectedSession = 0)
        assertEquals(files.map { it.name }.toSet(), extractor.names.toSet())
        assertEquals(0L, r.readNowSession)
        assertEquals(75, ReadingPolicy.batteryFloor(r.prefs.value))
        r.close()
    }

    @Test fun aRetiredGreetedAllScopeIsRecoveredBeforeAccessPlanning() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = newStore().also { it.setProfile(PROFILE) }
        val files = (1..120).map { testFile("old$it.pdf", Kind.PDF) }
        val r = testRetriever(store, files, FakeExtractor(), dispatchers = CoreDispatchers(dispatcher, watchdog = dispatcher))
        r.setSetupMark(SetupMark.GREETED)
        r.setReadingSchedule(ReadingSchedule.BATTERY)
        assertTrue(assertNotNull(r.setup.value).decided)
        r.startProcessing(true)
        assertFalse(assertNotNull(r.setup.value).decided)
        assertTrue(r.scopeIds.value.orEmpty().isNotEmpty())
        r.close()
    }
}
