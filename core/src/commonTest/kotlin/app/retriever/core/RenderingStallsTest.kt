package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderingStallsTest {
    @Test fun measuredNormalFramesDoNotTripTheStallThreshold() {
        val gaps = FrameGaps()
        listOf(0.0, 11.0, 38.0, 91.0, 164.0, 261.0, 411.0).forEach { gaps.frame(it, true) }
        assertEquals(150.0, gaps.maxGapMs)
        assertFalse(stallsRendering(gaps.maxGapMs))
        assertFalse(stallsRendering(250.0))
    }

    @Test fun oneLongFrameIsEnough() {
        val gaps = FrameGaps()
        listOf(0.0, 16.0, 4966.0, 4982.0).forEach { gaps.frame(it, true) }
        assertEquals(4950.0, gaps.maxGapMs)
        assertTrue(stallsRendering(gaps.maxGapMs))
        assertTrue(stallsRendering(250.1))
    }

    @Test fun aHiddenDisplayDoesNotBecomeAStall() {
        val gaps = FrameGaps()
        gaps.frame(0.0, true)
        gaps.frame(5000.0, false)
        gaps.frame(10000.0, true)
        gaps.frame(10016.0, true)
        assertEquals(16.0, gaps.maxGapMs)
    }

    @Test fun onlyStallingForegroundGpuVisionMovesToCpu() {
        val p = DeviceProfile(Tier.BALANCED, ModelVariant.M440, Backend.GPU, 70, 5, false, false, gpuVisionStalls = true)
        assertEquals(Backend.CPU, visionBackendFor(p, visible = true))
        assertEquals(Backend.GPU, visionBackendFor(p, visible = false))
        assertEquals(Backend.GPU, visionBackendFor(p.copy(gpuVisionStalls = false), visible = true))
        assertEquals(Backend.CPU, visionBackendFor(p.copy(backend = Backend.CPU), visible = false))
        assertEquals(Backend.NPU, visionBackendFor(p.copy(backend = Backend.NPU), visible = true))
    }
}
