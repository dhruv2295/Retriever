package app.retriever.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RetrieverTest {
    private object NoHasher : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    }

    private val pixel = DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 8.0, false)
    private val genericSig = Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens)

    /** Runs each test and the Retriever under it on one virtual-time scheduler. */
    private val dispatcher = StandardTestDispatcher()

    private inner class Fx(
        val store: Store,
        val probe: DeviceProbe,
        files: Int = 1,
        val factory: FakeFactory = FakeFactory(),
        val monitor: FakeMonitor = FakeMonitor(),
        val extractor: FakeExtractor = FakeExtractor(),
        profile: DeviceProfile? = PROFILE,
    ) {
        val source = FakeSource()
        val logs = mutableListOf<String>()
        var ready = true

        init {
            profile?.let(store::setProfile)
            source.next = Changes((1..files).map { testFile("f$it.txt", modifiedAt = it.toLong()) }, emptyList(), "tok", isFullScan = true)
        }

        fun retriever() = Retriever(
            store, source, extractor, factory, monitor, NoHasher, probe, { ready }, { true },
            { m, _ -> logs += m }, System::currentTimeMillis, { 0L }, CoreDispatchers(dispatcher),
        )

        val r = retriever()
    }

    private fun fx(
        store: Store = newStore(),
        probe: DeviceProbe = pixel,
        files: Int = 1,
        factory: FakeFactory = FakeFactory(),
        monitor: FakeMonitor = FakeMonitor(),
        extractor: FakeExtractor = FakeExtractor(),
    ) = Fx(store, probe, files, factory, monitor, extractor)

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher, timeout = 20.seconds) { body() }

    @Test
    fun stopReasonsMapToNextRun() {
        val expected = mapOf(
            StopReason.BUDGET to NextRun.SOON,
            StopReason.HOT to NextRun.AFTER_COOLDOWN,
            StopReason.UNPLUGGED to NextRun.WHEN_CHARGING,
            StopReason.BUSY to NextRun.WHEN_IDLE,
            StopReason.DONE to NextRun.DONE,
            StopReason.PAUSED to NextRun.DONE,
        )
        for ((reason, next) in expected) assertEquals(next, Retriever.nextRunFor(reason, false), "$reason")
        assertEquals(NextRun.SOON, Retriever.nextRunFor(StopReason.DONE, true))
    }

    @Test
    fun hotAndBusyAndPausedStopTheBatch() = test {
        val f = fx(monitor = FakeMonitor(DeviceState(8.0, true, 80, Thermal.SEVERE)))
        assertEquals(NextRun.AFTER_COOLDOWN, f.r.indexBatch(1.minutes))
        f.monitor.state = DeviceState(8.0, true, 80, Thermal.NONE, screenOn = true)
        assertEquals(NextRun.WHEN_IDLE, f.r.indexBatch(1.minutes))
        f.r.pause()
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(StopReason.PAUSED, f.r.state.value.lastStop)
    }

    @Test
    fun tuneAgainKeepsVisionTokens() = test {
        val f = fx()
        f.store.setProfile(PROFILE.copy(visionTokens = 99))
        val samples = Samples(listOf(ByteArray(10)), listOf("hello world"), emptyList())
        assertEquals(99, f.r.calibrate(samples, tuneAgain = true).profile.visionTokens)
        assertEquals(99, f.store.profile()!!.visionTokens)
        assertTrue(f.store.hasPrefs())
        assertTrue(f.r.calibrate(samples, tuneAgain = false).profile.visionTokens != 99)
    }

    private val light = PROFILE.copy(tier = Tier.LIGHT, videoFrameEverySec = 30, audio = false, bulkOnlyWhenCharging = true)
    private val image = listOf(Piece("", listOf(Part.Image(ByteArray(4))), null))

    // Emulator, wave 5: tuned LIGHT under host load (Audio off), Tune again gave BALANCED, yet the voice notes stayed
    // name-only and the video kept its one 30 s moment.
    @Test
    fun aTuneThatRaisesTheTierReadsAgainWhatTheOldTierLeftOut() = test {
        val f = Fx(newStore(), pixel, profile = light)
        f.store.updatePrefs { Prefs.defaultFor(light) }
        f.source.next = Changes(
            listOf(testFile("memo.m4a", Kind.AUDIO), testFile("clip.mp4", Kind.VIDEO), testFile("a.txt")), emptyList(), "tok", isFullScan = true,
        )
        f.r.indexBatch(1.minutes)
        assertEquals(3L, f.store.countsByState()[FileState.INDEXED])
        f.r.calibrate(Samples(listOf(ByteArray(10)), listOf("hello world"), emptyList()), tuneAgain = true)
        assertEquals(Tier.FULL, f.store.profile()!!.tier)
        assertEquals(setOf("memo.m4a", "clip.mp4"), f.store.pending(10).map { it.file.name }.toSet())
    }

    @Test
    fun photosReadFasterThanTuningMeasuredMoveTheTierUp() = test {
        val photos = (1..Retriever.RETIER_WINDOW).map { testFile("p$it.jpg", Kind.PHOTO, modifiedAt = 10L + it) }
        val f = Fx(newStore(), pixel, profile = light.copy(visionTokens = 99), extractor = FakeExtractor(photos.associate { it.name to image }))
        f.store.updatePrefs { Prefs.defaultFor(light) }
        f.source.next = Changes(photos + testFile("memo.m4a", Kind.AUDIO), emptyList(), "tok", isFullScan = true)
        f.r.indexBatch(1.minutes)
        val now = f.store.profile()!!
        assertEquals(Tier.FULL, now.tier)
        assertEquals(99, now.visionTokens, "vision tokens stay, so no vector goes stale")
        assertTrue(Kind.AUDIO in f.store.prefs().kinds)
        assertFalse(f.store.prefs().onlyWhileCharging)
    }

    @Test
    fun fastPhotosNeverMoveTheTierDown() = test {
        val photos = (1..Retriever.RETIER_WINDOW).map { testFile("p$it.jpg", Kind.PHOTO, modifiedAt = it.toLong()) }
        // 6.5 GB: fast photos still mean BALANCED, below the stored FULL.
        val f = Fx(newStore(), pixel.copy(ramGb = 6.5), extractor = FakeExtractor(photos.associate { it.name to image }))
        f.source.next = Changes(photos, emptyList(), "tok", isFullScan = true)
        f.r.indexBatch(1.minutes)
        assertEquals(PROFILE, f.store.profile())
    }

    @Test
    fun signatureChangeResetsOnceAndLogs() = test {
        val f = fx()
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(1, f.logs.count { "signature changed" in it })
        assertEquals(genericSig, f.store.signature())
        assertEquals(1L, f.store.countsByState()[FileState.INDEXED])
    }

    @Test
    fun uiBusyHoldsEmbeddingAndASecondBatchOnlyFlagsChanges() = test {
        val f = fx()
        f.r.setUiBusy(true)
        val first = async { f.r.indexBatch(1.minutes) }
        advanceUntilIdle()
        assertTrue(f.extractor.names.isNotEmpty())
        assertEquals(1, f.factory.last!!.received.size, "only the warm-up embed ran")
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        f.r.setUiBusy(false)
        assertEquals(NextRun.SOON, first.await(), "the second call asked for a follow-up sync")
        assertEquals(1, f.source.calls.size)
        assertTrue(f.factory.last!!.received.size > 1)
    }

    @Test
    fun setUpNeedsATunedModelAndOutlivesItsFile() = test {
        val f = Fx(newStore(), pixel, profile = null)
        assertEquals(null, f.r.setUp.value)
        f.r.refreshSetUp()
        assertEquals(false, f.r.setUp.value, "downloaded but never tuned")
        f.store.setProfile(PROFILE)
        f.ready = false
        f.r.refreshSetUp()
        assertEquals(true, f.r.setUp.value, "the file went away after setup")
        assertTrue(f.r.modelMissing.value)
        f.ready = true
        f.r.refreshSetUp()
        assertEquals(true, f.r.setUp.value)
        assertFalse(f.r.modelMissing.value)
    }

    @Test
    fun visibleIndexesOnBatteryBackgroundWaitsForCharging() = test {
        val f = fx(monitor = FakeMonitor(DeviceState(8.0, false, 50, Thermal.NONE, screenOn = false)))
        f.r.updatePrefs { it.copy(onlyWhileCharging = true) }
        assertEquals(NextRun.WHEN_CHARGING, f.r.indexBatch(1.minutes))
        assertEquals(1L, f.store.countsByState()[FileState.PENDING])
        f.monitor.state = f.monitor.state.copy(screenOn = true)
        f.r.uiVisible = true
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(1L, f.store.countsByState()[FileState.INDEXED])
    }

    @Test
    fun cancelBatchStopsWithinOneFile() = test {
        lateinit var f: Fx
        val at = 2
        f = fx(files = 50, extractor = FakeExtractor(onExtract = { if (f.extractor.names.size == at) f.r.cancelBatch() }))
        assertEquals(NextRun.SOON, f.r.indexBatch(1.minutes))
        assertTrue(f.extractor.names.size <= at + 1, "extracted ${f.extractor.names.size} after cancel at $at")
        assertEquals(StopReason.BUDGET, f.r.state.value.lastStop)
        assertFalse(f.r.state.value.running)
    }

    @Test
    fun npuFailureFallsBackToGenericForGood() = test {
        val g5 = DeviceProbe(Platform.ANDROID, 35, "Google", "Tensor G5", 8.0, false, npuDispatch = true)
        val npuBuild = ModelBuilds.npu.single { it.variant == ModelVariant.M740 && it.soc == "Google_Tensor_G5" }
        val factory = FakeFactory { if (it.isNpu) listOf(Backend.NPU) else listOf(Backend.GPU, Backend.CPU) }
        factory.fail = { _, b -> if (b.isNpu) IllegalStateException("no dispatch library") else null }
        val f = fx(probe = g5, factory = factory)
        f.store.setProfile(PROFILE.copy(backend = Backend.NPU))
        f.r.refreshSetUp()
        assertEquals(npuBuild, f.r.requiredBuild.value)

        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(ModelVariant.M740.generic, f.r.requiredBuild.value)
        assertTrue(npuBuild.fileName in f.store.meta("npu_failed")!!)
        assertEquals(Backend.GPU, f.store.profile()!!.backend)

        val again = f.retriever()
        again.refreshSetUp()
        assertEquals(ModelVariant.M740.generic, again.requiredBuild.value)
    }

    @Test
    fun twoOpensThatNeverReturnDemoteGpuToCpu() = test {
        val store = newStore()
        store.setProfile(PROFILE.copy(backend = Backend.GPU))
        // A hung warm-up stands for a process that died natively inside the open; each Fx is a new process.
        val hanging = FakeFactory().apply { beforeEmbed = { awaitCancellation() } }
        val crashed = (1..2).map { n ->
            val f = Fx(store, pixel, factory = hanging, profile = null)
            async { f.r.indexBatch(1.minutes) }.also {
                advanceUntilIdle()
                assertEquals("$n", store.meta("unfinished_opens"))
            }
        }
        val f = Fx(store, pixel, profile = null)
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(Backend.CPU, store.profile()!!.backend)
        assertEquals(Backend.CPU, f.factory.opened.single().first.backend)
        assertEquals("0", store.meta("unfinished_opens"))
        crashed.forEach { it.cancel() }
    }

    @Test
    fun aThrownOrCancelledOpenIsNoCrash() = test {
        val f = fx()
        f.factory.fail = { _, _ -> IllegalStateException("no GPU today") }
        assertEquals(NextRun.AFTER_COOLDOWN, f.r.indexBatch(1.minutes))
        assertEquals("0", f.store.meta("unfinished_opens"))

        f.factory.fail = { _, _ -> null }
        f.factory.beforeEmbed = { awaitCancellation() }
        val batch = async { f.r.indexBatch(1.minutes) }
        advanceUntilIdle()
        assertEquals("1", f.store.meta("unfinished_opens"))
        f.r.cancelBatch()
        assertEquals(NextRun.SOON, batch.await())
        assertEquals("0", f.store.meta("unfinished_opens"))
    }

    @Test
    fun aPrefsSwitchDoesNotReopenTheModel() = test {
        val f = fx(store = newStore(genericSig))
        f.r.indexBatch(1.minutes)
        f.r.updatePrefs { it.copy(onlyWhileCharging = true, kinds = it.kinds - Kind.VIDEO) }
        assertTrue(f.r.search("hello").vectorLane)
        assertEquals(1, f.factory.opened.size)
    }

    @Test
    fun aGpuThatBreaksMidBatchMovesToCpuWithoutTouchingTheNpuList() = test {
        val f = fx(files = 3)
        f.store.setProfile(PROFILE.copy(backend = Backend.GPU))
        var embeds = 0
        f.factory.beforeEmbed = { if (++embeds == 2) throw EmbedderClosedException("GPU inference failed") }
        assertEquals(NextRun.SOON, f.r.indexBatch(1.minutes))
        assertEquals(Backend.CPU, f.store.profile()!!.backend)
        assertEquals(null, f.store.meta("npu_failed"))
        assertEquals(null, f.r.state.value.lastError)
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(Backend.CPU, f.factory.opened.last().first.backend)
        assertEquals(3L, f.store.countsByState()[FileState.INDEXED], "the interrupted file kept its attempts")
    }

    @Test
    fun aGpuThatCantRunRightNowIsReopenedNotDemoted() = test {
        val f = fx(files = 3)
        f.store.setProfile(PROFILE.copy(backend = Backend.GPU))
        var embeds = 0
        f.factory.beforeEmbed = { if (++embeds == 2) throw EmbedderClosedException("GPU refused in the background", broken = false) }
        assertEquals(NextRun.SOON, f.r.indexBatch(1.minutes))
        assertEquals(Backend.GPU, f.store.profile()!!.backend)
        assertEquals(NextRun.DONE, f.r.indexBatch(1.minutes))
        assertEquals(Backend.GPU, f.factory.opened.last().first.backend)
        assertEquals(3L, f.store.countsByState()[FileState.INDEXED])
    }

    @Test
    fun anNpuReleasedUnderABatchIsNotMarkedFailed() = test {
        val g5 = DeviceProbe(Platform.ANDROID, 35, "Google", "Tensor G5", 8.0, false, npuDispatch = true)
        val factory = FakeFactory { if (it.isNpu) listOf(Backend.NPU) else listOf(Backend.GPU, Backend.CPU) }
        val f = fx(probe = g5, factory = factory, files = 3)
        f.store.setProfile(PROFILE.copy(backend = Backend.NPU))
        var embeds = 0
        factory.beforeEmbed = { if (++embeds == 2) f.r.release() }
        assertEquals(NextRun.SOON, f.r.indexBatch(1.minutes))
        assertEquals(null, f.store.meta("npu_failed"))
        assertTrue(f.r.requiredBuild.value.isNpu)
    }

    @Test
    fun aModelThatBreaksDuringASearchIsRetiredAndReopened() = test {
        val f = fx(store = newStore(genericSig))
        f.r.indexBatch(1.minutes)
        var broken = true
        f.factory.beforeEmbed = {
            if (broken) {
                broken = false
                throw EmbedderClosedException("inference failed")
            }
        }
        val first = f.r.search("f1")
        assertFalse(first.vectorLane)
        assertEquals("f1.txt", first.hits.single().file.name, "keyword results still answer")
        assertTrue(f.r.search("f1").vectorLane)
        assertEquals(2, f.factory.opened.size)
    }

    @Test
    fun tuneCountsAsAnOpenAndSkipsAGpuThatCrashedTwice() = test {
        val f = fx()
        val samples = Samples(listOf(ByteArray(10)), listOf("hello world"), emptyList())
        f.factory.beforeEmbed = { awaitCancellation() }
        val hung = async { f.r.calibrate(samples, tuneAgain = false) }
        advanceUntilIdle()
        assertEquals("1", f.store.meta("unfinished_opens"))
        hung.cancelAndJoin()

        f.factory.beforeEmbed = {}
        f.store.setMeta("unfinished_opens", "2")
        f.factory.opened.clear()
        assertEquals(Backend.CPU, f.r.calibrate(samples, tuneAgain = false).chosen)
        assertTrue(f.factory.opened.none { it.first.backend == Backend.GPU })
        assertEquals("0", f.store.meta("unfinished_opens"))
    }

    @Test
    fun prefsStoredBeforeTheSetupFlagsStillDecode() {
        val store = newStore()
        store.setMeta("prefs", """{"kinds":["PHOTO"],"paused":true}""")
        assertEquals(Prefs(kinds = setOf(Kind.PHOTO), paused = true), store.prefs())
        assertFalse(store.prefs().setupStarted || store.prefs().notificationsDismissed)
    }

    @Test
    fun searchImageSendsOneRawImage() = test {
        val f = fx(store = newStore(genericSig))
        val result = f.r.searchImage(ByteArray(33))
        assertTrue(result.vectorLane)
        val parts = f.factory.last!!.received.last()
        assertEquals(1, parts.size)
        assertTrue(parts.single() is Part.Image)
    }
}
