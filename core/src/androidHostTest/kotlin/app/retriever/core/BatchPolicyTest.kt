package app.retriever.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What [Retriever.indexBatch] decides before and around the model: budget, eligibility, the charging rule, failures. */
class BatchPolicyTest {
    private val dispatcher = StandardTestDispatcher()
    private val genericSig = Signature.of(ModelVariant.M440.generic, PROFILE.visionTokens, PROFILE.maxTokens)

    /** On the charger, screen off, cool: every check passes. */
    private val plugged = DeviceState(8.0, charging = true, batteryPct = 80, thermal = Thermal.NONE)
    private val unplugged = plugged.copy(charging = false, pluggedIn = false)

    private object NoHasher : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    }

    private inner class Fx(
        files: List<SourceFile> = listOf(testFile("a.txt")),
        state: DeviceState = plugged,
        ramGb: Double = 8.0,
        modelReady: Boolean = true,
        sig: Signature = genericSig,
    ) {
        val store = newStore(sig).also { it.setProfile(PROFILE) }
        val monitor = FakeMonitor(state)
        val factory = FakeFactory()
        var onSync: () -> Unit = {}
        var onExtract: suspend (SourceFile) -> Unit = {}
        var onLog: (String) -> Unit = {}
        val extractor = FakeExtractor(onExtract = { onExtract(it) })
        private val source = object : FileSource {
            override suspend fun changes(since: String?): Changes {
                onSync()
                return Changes(files, emptyList(), "tok", isFullScan = true)
            }
        }
        val r = Retriever(
            store, source, extractor, factory, monitor, NoHasher, PIXEL.copy(ramGb = ramGb), { modelReady }, { true },
            // The watchdog on virtual time too: no batch here waits on a real thread.
            { m, _ -> onLog(m) }, System::currentTimeMillis, { 0L }, CoreDispatchers(dispatcher, watchdog = dispatcher), fewWaitingLimit = 0,
        )

        fun indexed() = store.countsByState()[FileState.INDEXED] ?: 0L
    }

    private fun test(body: suspend () -> Unit) = runTest(dispatcher, timeout = 20.seconds) { body() }

    @Test
    fun theBudgetCountsTheSyncAndTheOpen() = test {
        val f = Fx()
        f.onSync = { Thread.sleep(100) }
        assertEquals(NextRun.SOON, f.r.indexBatch(50.milliseconds))
        assertEquals(StopReason.BUDGET, f.r.state.value.lastStop)
        assertEquals(1, f.factory.opened.size)
        assertTrue(f.extractor.names.isEmpty(), "the sync used the whole budget")
    }

    @Test
    fun aStateThatWouldStopTheBatchStopsItBeforeTheModelOpens() = test {
        val cases = listOf(
            plugged.copy(thermal = Thermal.SEVERE) to NextRun.AFTER_COOLDOWN,
            plugged.copy(screenOn = true) to NextRun.WHEN_IDLE,
            unplugged to NextRun.WHEN_CHARGING,
        )
        for ((state, next) in cases) {
            val f = Fx(state = state)
            f.r.updatePrefs { it.copy(onlyWhileCharging = true) }
            assertEquals(next, f.r.indexBatch(1.minutes), "$state")
            assertTrue(f.factory.opened.isEmpty(), "$state opened the model")
            assertTrue(f.extractor.names.isEmpty())
        }
    }

    @Test
    fun nothingToReadNeverOpensTheModel() = test {
        val empty = Fx(files = emptyList())
        assertEquals(NextRun.DONE, empty.r.indexBatch(1.minutes))
        assertTrue(empty.factory.opened.isEmpty())
        // Caught up and on the charger with the screen off (so deepening is allowed), with no tail left.
        val f = Fx()
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(1, f.factory.opened.size)
    }

    @Test
    fun vectorsOfAnotherModelStillOpenItWithNothingPending() = test {
        val f = Fx(files = emptyList(), sig = SIG)
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(1, f.factory.opened.size)
        assertEquals(genericSig, f.store.signature())
    }

    @Test
    fun unpluggingMidBatchStopsVisibleAndHiddenReading() = test {
        for (visible in listOf(true, false)) {
            val f = Fx(files = (1..3).map { testFile("f$it.txt", modifiedAt = it.toLong()) })
            f.r.updatePrefs { it.copy(onlyWhileCharging = true) }
            f.r.uiVisible = visible
            f.onExtract = { f.monitor.state = f.monitor.state.copy(charging = false, pluggedIn = false) }
            val next = f.r.indexBatch(1.minutes)
            assertEquals(NextRun.WHEN_CHARGING, next)
            assertEquals(StopReason.UNPLUGGED, f.r.state.value.lastStop)
            assertEquals(0L, f.indexed(), "unplugging before embedding keeps the file pending")
        }
    }

    @Test
    fun aLowBatteryStopsAHiddenAppWithoutTheChargingSetting() = test {
        val low = Fx(state = unplugged.copy(batteryPct = 15))
        assertEquals(NextRun.WHEN_CHARGING, low.r.indexBatch(1.minutes))
        assertEquals(StopReason.LOW_BATTERY, low.r.state.value.lastStop)
        assertTrue(low.factory.opened.isEmpty())
        low.r.uiVisible = true
        assertEquals(NextRun.WHEN_CHARGING, low.r.indexBatch(1.minutes))

        val unknown = Fx(state = unplugged.copy(batteryPct = Int.MIN_VALUE))
        unknown.r.setReadingSchedule(ReadingSchedule.BATTERY)
        assertEquals(NextRun.DONE, unknown.r.indexBatch(1.minutes), "an unknown level is not low")
        assertEquals(1L, unknown.indexed())
    }

    /** Android's "charging" means the level is rising, false for up to 15 minutes after plugging in below 90%. */
    @Test
    fun theChargerRuleAsksWhetherThePhoneIsPluggedInNotWhetherItsLevelRises() = test {
        val f = Fx()
        f.r.uiVisible = true
        val notRising = plugged.copy(charging = false, batteryPct = 84, screenOn = true)
        val cases = listOf(
            notRising to null,
            unplugged.copy(batteryPct = 84, screenOn = true) to StopReason.UNPLUGGED,
            notRising.copy(batteryPct = 15) to StopReason.CHARGING_UP,
            notRising.copy(batteryPct = 15, charging = true) to null,
        )
        for ((state, stop) in cases) {
            f.monitor.state = state
            assertEquals(stop, f.r.readingBlocker(), "$state")
        }
    }

    @Test
    fun trialPausesForOtherAppsAndResumesWhenVisibleOrScreenOff() = test {
        for (visible in listOf(true, false)) {
            val f = Fx(state = plugged.copy(screenOn = true))
            f.r.planTaste()
            assertEquals(NextRun.WHEN_IDLE, f.r.taste())
            assertEquals(StopReason.BUSY, f.r.state.value.lastStop)
            f.r.uiVisible = visible
            f.monitor.state = plugged.copy(screenOn = visible)
            assertEquals(NextRun.DONE, f.r.taste())
            assertEquals(1L, f.indexed())
        }
    }

    @Test
    fun hiddenTrialRespectsBatterySaverHeatAndLowBattery() = test {
        val cases = listOf(
            plugged.copy(powerSave = true) to StopReason.POWER_SAVE,
            plugged.copy(thermal = Thermal.SEVERE) to StopReason.HOT,
            unplugged.copy(batteryPct = 10) to StopReason.LOW_BATTERY,
        )
        for ((state, stop) in cases) {
            val f = Fx(state = state)
            f.r.planTaste()
            f.r.taste()
            assertEquals(stop, f.r.state.value.lastStop)
            assertEquals(0L, f.indexed())
        }
        val f = Fx(state = plugged.copy(powerSave = true))
        f.r.uiVisible = true
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes), "Battery Saver also stops visible reading")
    }

    @Test
    fun aFailedSyncAsksForARetry() = test {
        val f = Fx()
        f.onSync = { throw IllegalStateException("provider went away") }
        assertEquals(NextRun.RETRY, f.r.indexBatch(1.minutes))
        assertTrue(f.r.state.value.lastError != null)
        assertTrue(f.factory.opened.isEmpty())
    }

    private class ScanError : Error("the scan ran out of memory")

    /** An Error in the plan's sync (memory, at 100k files) reaches the caller instead of leaving it waiting with the lock held. */
    @Test
    fun anErrorInThePlansSyncReachesTheCaller() = test {
        val f = Fx()
        f.onSync = { throw ScanError() }
        assertFailsWith<ScanError> { f.r.planTaste() }
        f.onSync = {}
        assertEquals(1, f.r.planTaste().ids.size, "the lock was given back")
    }

    @Test
    fun aFullDiskStopsTheBatchAsAReasonNotAnError() = test {
        val f = Fx()
        f.onSync = { throw SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)") }
        assertEquals(NextRun.RETRY, f.r.indexBatch(1.minutes))
        assertEquals(StopReason.DISK_FULL, f.r.state.value.lastStop)
        assertEquals(null, f.r.state.value.lastError)
    }

    /** The device's own checks never see a full disk, so the live line takes it from the batch that stopped for it. */
    @Test
    fun theStatusLineSaysThePhoneIsFull() = test {
        val f = Fx()
        f.onExtract = { throw SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)") }
        assertEquals(NextRun.RETRY, f.r.indexBatch(1.minutes))
        val status = f.r.readingStatus().first()
        assertEquals(ReadingPhase.WAITING, status.phase)
        assertEquals("Paused, your phone is full", status.title)
        assertEquals("Free up some space and Retriever carries on.", status.detail)
    }

    @Test
    fun theReadingPolicyIsAskedOnceBeforeEachFileNotOncePerRule() = test {
        val files = (1..30).map { testFile("f$it.txt", modifiedAt = it.toLong()) }
        val f = Fx(files = files)
        var reads = 0
        // On virtual time the watchdog never ticks here (the batch never waits a second), so every read is the batch's own.
        f.monitor.failing = {
            reads++
            false
        }
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(30L, f.indexed())
        // Per file: the loop's state and policy, the configuration and deepen checks, the policy before the read and before the embed.
        assertTrue(reads <= 6 * files.size + 15, "$reads device reads for ${files.size} files")
    }

    /** A failed watchdog check (a dead binder) is logged, not a crash, and the next one still stops a long file mid-read. */
    @Test
    fun aFailedWatchdogCheckIsLoggedAndTheNextStillStopsTheBatch() = test {
        val f = Fx()
        val logs = mutableListOf<String>()
        f.onLog = { logs += it }
        var checks = 0
        var readWhole = false
        // Mid-file only the watchdog asks: its first check fails, its second finds the phone hot.
        f.onExtract = {
            f.monitor.failing = { ++checks == 1 }
            f.monitor.state = plugged.copy(thermal = Thermal.SEVERE)
            delay(10.seconds)
            readWhole = true
        }
        assertEquals(NextRun.AFTER_COOLDOWN, f.r.indexBatch(1.minutes))
        assertEquals(StopReason.HOT, f.r.state.value.lastStop)
        assertFalse(readWhole, "stopped mid-read, not after it")
        assertEquals(0L, f.indexed())
        assertTrue("The batch's policy check failed" in logs, "$logs")
    }

    @Test
    fun noModelWithChangesDuringTheSyncComesBackSoon() = test {
        val f = Fx(modelReady = false)
        f.onSync = { f.r.notifyChanged() }
        assertEquals(NextRun.SOON, f.r.indexBatch(1.minutes))
        f.onSync = {}
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
    }

    @Test
    fun textOnlyReadsAPhotoInOnePass() = test {
        val f = Fx(files = listOf(testFile("cat.jpg", Kind.PHOTO)), ramGb = 3.0)
        assertTrue(f.r.textOnly)
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(1L, f.indexed())
        assertEquals(listOf("cat.jpg"), f.extractor.names, "photo words and labels do not need the model")
    }
}
