package app.retriever.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fallback ladder, against the model host alone. */
class ModelHostTest {
    private val g5 = DeviceProbe(Platform.ANDROID, 35, "Google", "Tensor G5", 8.0, false, npuDispatch = true)

    private class Fx(val probe: DeviceProbe, val factory: FakeFactory, profile: DeviceProfile) {
        val store = newStore().also { it.setProfile(profile) }
        private val variant = ModelBuilds.variantFor(probe)
        val required = MutableStateFlow(ModelBuilds.buildFor(variant, probe, emptySet()))
        var crashed = true
        val host: ModelHost by lazy {
            ModelHost(
                store, factory, textOnly = false, required, modelReady = { true }, log = { _, _ -> },
                dispatchers = CoreDispatchers(),
                reload = { required.value = ModelBuilds.buildFor(variant, probe, host.npuFailed()) },
                onOpened = { _, _ -> },
                onClosed = {},
                lastExitCrashed = { crashed },
                visible = { true },
            )
        }
    }

    @Test
    fun idleReleaseDoesNotWaitForAnOpenAndChecksEligibilityUnderTheLock() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE)
        f.host.open()
        f.host.lock.lock()
        f.host.releaseIf { error("must not inspect an embedder while its lock is held") }
        f.host.lock.unlock()
        f.host.releaseIf { false }
        assertFalse(f.factory.last!!.closed)
        f.host.releaseIf {
            assertTrue(f.host.lock.isLocked)
            true
        }
        assertTrue(f.factory.last!!.closed)
    }

    @Test
    fun aGpuWarmUpFailureDemotesToCpuOnceAndOpensOnCpu() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE.copy(backend = Backend.GPU))
        // The open's only embed is its warm-up.
        f.factory.beforeEmbed = { if (f.factory.opened.last().first.backend == Backend.GPU) throw EmbedderClosedException("GPU inference failed") }

        assertNotNull(f.host.open())
        assertEquals(Backend.CPU, f.store.profile()!!.backend)
        assertEquals(listOf(Backend.GPU, Backend.CPU), f.factory.opened.map { it.first.backend })
        assertNull(f.store.meta("npu_failures"))
        f.host.release()
        assertNotNull(f.host.open())
        assertEquals(Backend.CPU, f.factory.opened.last().first.backend, "the next open uses CPU")
        assertEquals(3, f.factory.opened.size)
    }

    @Test
    fun aGpuThatCantOpenRightNowIsNotDemoted() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE.copy(backend = Backend.GPU))
        f.factory.fail = { _, _ -> EmbedderClosedException("not enough memory to open the model", broken = false) }

        // Rethrown, so the batch rests instead of ending or looping.
        assertFailsWith<EmbedderClosedException> { f.host.open() }
        assertEquals(Backend.GPU, f.store.profile()!!.backend)
        f.factory.fail = { _, _ -> null }
        assertNotNull(f.host.open())
        assertEquals(Backend.GPU, f.factory.opened.last().first.backend)
    }

    @Test
    fun aGpuOpenFailureDemotesAndRetriesOnCpu() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE.copy(backend = Backend.GPU))
        f.factory.fail = { p, _ -> if (p.backend == Backend.GPU) IllegalStateException("GPU delegate failed to initialize") else null }

        assertNotNull(f.host.open())
        assertEquals(Backend.CPU, f.store.profile()!!.backend)
        assertEquals(listOf(Backend.GPU, Backend.CPU), f.factory.opened.map { it.first.backend })
        assertEquals("0", f.store.meta("unfinished_opens"))
        f.host.release()
        assertNotNull(f.host.open())
        assertEquals(Backend.CPU, f.factory.opened.last().first.backend)
    }

    @Test
    fun anUntypedGpuWarmUpFailureAlsoDemotes() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE.copy(backend = Backend.GPU))
        f.factory.beforeEmbed = {
            if (f.factory.opened.last().first.backend == Backend.GPU) throw IllegalStateException("GPU warm-up failed")
        }

        assertNotNull(f.host.open())
        assertEquals(Backend.CPU, f.store.profile()!!.backend)
        assertEquals(listOf(Backend.GPU, Backend.CPU), f.factory.opened.map { it.first.backend })
    }

    @Test
    fun aGpuWarmUpRefusedForNowKeepsTheGpu() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE.copy(backend = Backend.GPU))
        f.factory.beforeEmbed = { throw EmbedderClosedException("GPU unavailable in the background", broken = false) }

        assertFalse(assertFailsWith<EmbedderClosedException> { f.host.open() }.broken)
        assertEquals(Backend.GPU, f.store.profile()!!.backend)
        assertEquals("0", f.store.meta("unfinished_opens"))
    }

    @Test
    fun aCpuOpenFailureIsRethrownWithoutRetrying() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE.copy(backend = Backend.CPU))
        f.factory.fail = { _, _ -> IllegalStateException("CPU open failed") }

        assertFailsWith<IllegalStateException> { f.host.open() }
        assertEquals(Backend.CPU, f.store.profile()!!.backend)
        assertEquals(1, f.factory.opened.size)
        assertEquals("0", f.store.meta("unfinished_opens"))
    }

    @Test
    fun aCancelledGpuWarmUpDoesNotDemoteAndTheNextOpenRuns() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE.copy(backend = Backend.GPU))
        f.factory.beforeEmbed = { throw CancellationException("search cancelled") }

        assertFailsWith<CancellationException> { f.host.open() }
        assertEquals(Backend.GPU, f.store.profile()!!.backend)
        assertEquals("0", f.store.meta("unfinished_opens"))
        f.factory.beforeEmbed = {}
        assertNotNull(f.host.open())
        assertEquals(listOf(Backend.GPU, Backend.GPU), f.factory.opened.map { it.first.backend })
    }

    @Test
    fun unfinishedOpensWithoutACrashResetAndKeepTheGpu() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE.copy(backend = Backend.GPU))
        f.crashed = false
        f.store.setMeta("unfinished_opens", "2")

        assertNotNull(f.host.open())
        assertEquals(Backend.GPU, f.store.profile()!!.backend)
        assertNull(f.store.meta("gpu_unsafe"))
        assertEquals("0", f.store.meta("unfinished_opens"))
    }

    @Test
    fun unfinishedOpensWithoutACrashKeepTheNpu() = runTest {
        val factory = FakeFactory { if (it.isNpu) listOf(Backend.NPU) else listOf(Backend.GPU, Backend.CPU) }
        val f = Fx(g5, factory, PROFILE.copy(backend = Backend.NPU))
        f.crashed = false
        f.store.setMeta("unfinished_opens", "2")

        assertNotNull(f.host.open())
        assertNull(f.store.meta("npu_failures"))
        assertTrue(f.required.value.isNpu)
    }

    @Test
    fun anNpuBuildThatFailsToOpenThreeTimesMarksTheGenericBuild() = runTest {
        val factory = FakeFactory { if (it.isNpu) listOf(Backend.NPU) else listOf(Backend.GPU, Backend.CPU) }
        factory.fail = { _, b -> if (b.isNpu) IllegalStateException("no dispatch library") else null }
        val f = Fx(g5, factory, PROFILE.copy(backend = Backend.NPU))
        val npu = f.required.value
        assertTrue(npu.isNpu)

        // Until then each failure rests like a model that can't run right now, and the chip build stays.
        repeat(ModelHost.NPU_TRIES - 1) {
            assertFalse(assertFailsWith<EmbedderClosedException> { f.host.open() }.broken)
            assertTrue(f.required.value.isNpu)
        }
        assertNull(f.host.open())
        assertEquals(setOf(npu.fileName), f.host.npuFailed())
        assertEquals(ModelVariant.M440.generic, f.required.value)
        assertEquals(Backend.GPU, f.store.profile()!!.backend)
        assertEquals(ModelVariant.M440.generic, f.host.open()!!.second)
    }

    /** The 6 Nov Pixel: one failed tune under the wrong vendor's library left it on GPU, flagged under the old key. */
    @Test
    fun aChipBuildFlaggedByTheOldOneFailureRuleOpensOnTheNpuAgain() = runTest {
        val factory = FakeFactory { if (it.isNpu) listOf(Backend.NPU) else listOf(Backend.GPU, Backend.CPU) }
        val f = Fx(g5, factory, PROFILE.copy(backend = Backend.GPU))
        val npu = f.required.value
        f.store.setMeta("npu_failed", """["${npu.fileName}"]""")

        assertEquals(npu, f.host.open()!!.second)
        assertEquals(Backend.NPU, f.factory.opened.single().first.backend)
        assertEquals(Backend.NPU, f.store.profile()!!.backend)
    }

    @Test
    fun twoUnfinishedOpensMoveTheGpuToCpu() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE.copy(backend = Backend.GPU))
        f.store.setMeta("unfinished_opens", "2")

        assertNotNull(f.host.open())
        assertEquals(Backend.CPU, f.store.profile()!!.backend)
        assertEquals("1", f.store.meta("gpu_unsafe"))
        assertEquals(Backend.CPU, f.factory.opened.single().first.backend)
        assertEquals("0", f.store.meta("unfinished_opens"))
    }

    @Test
    fun aModelThatCantRunRightNowReopensWithoutDemotion() = runTest {
        val f = Fx(PIXEL, FakeFactory(), PROFILE.copy(backend = Backend.GPU))
        val (e, build) = f.host.open()!!

        assertFalse(f.host.broke(e, build, EmbedderClosedException("GPU refused in the background", broken = false)))
        assertEquals(Backend.GPU, f.store.profile()!!.backend)
        assertNull(f.store.meta("npu_failures"))
        assertNotNull(f.host.open())
        assertEquals(2, f.factory.opened.size)
        assertEquals(Backend.GPU, f.factory.opened.last().first.backend)
    }
}
