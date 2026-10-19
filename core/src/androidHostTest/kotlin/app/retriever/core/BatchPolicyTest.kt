package app.retriever.core

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        var onExtract: (SourceFile) -> Unit = {}
        val extractor = FakeExtractor(onExtract = { onExtract(it) })
        private val source = object : FileSource {
            override suspend fun changes(since: String?): Changes {
                onSync()
                return Changes(files, emptyList(), "tok", isFullScan = true)
            }
        }
        val r = Retriever(
            store, source, extractor, factory, monitor, NoHasher, PIXEL.copy(ramGb = ramGb), { modelReady }, { true },
            { _, _ -> }, System::currentTimeMillis, { 0L }, CoreDispatchers(dispatcher),
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
            plugged.copy(charging = false) to NextRun.WHEN_CHARGING,
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
            f.onExtract = { f.monitor.state = f.monitor.state.copy(charging = false) }
            val next = f.r.indexBatch(1.minutes)
            assertEquals(NextRun.WHEN_CHARGING, next)
            assertEquals(StopReason.UNPLUGGED, f.r.state.value.lastStop)
            assertEquals(0L, f.indexed(), "unplugging before embedding keeps the file pending")
        }
    }

    @Test
    fun aLowBatteryStopsAHiddenAppWithoutTheChargingSetting() = test {
        val low = Fx(state = plugged.copy(charging = false, batteryPct = 15))
        assertEquals(NextRun.WHEN_CHARGING, low.r.indexBatch(1.minutes))
        assertEquals(StopReason.LOW_BATTERY, low.r.state.value.lastStop)
        assertTrue(low.factory.opened.isEmpty())
        low.r.uiVisible = true
        assertEquals(NextRun.WHEN_CHARGING, low.r.indexBatch(1.minutes))

        val unknown = Fx(state = plugged.copy(charging = false, batteryPct = Int.MIN_VALUE))
        unknown.r.setReadingSchedule(ReadingSchedule.BATTERY)
        assertEquals(NextRun.DONE, unknown.r.indexBatch(1.minutes), "an unknown level is not low")
        assertEquals(1L, unknown.indexed())
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
            plugged.copy(charging = false, batteryPct = 10) to StopReason.LOW_BATTERY,
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
