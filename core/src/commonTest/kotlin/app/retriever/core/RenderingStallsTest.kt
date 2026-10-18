package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderingStallsTest {
    @Test fun thresholdFollowsTheDisplay() {
        for (hz in listOf(60.0, 90.0, 120.0, 144.0)) {
            assertFalse(stallsRendering(3000.0 / hz, hz))
            assertTrue(stallsRendering(3000.0 / hz + 0.1, hz))
            assertTrue(stallsRendering(183.0, hz))
        }
        assertFalse(stallsRendering(40.0, 60.0))
        assertTrue(stallsRendering(40.0, 120.0))
    }

    @Test fun refreshChangesAreMeasuredAtTheGap() {
        val gaps = FrameGaps()
        gaps.frame(0.0, true, 60.0)
        gaps.frame(40.0, true, 60.0)
        assertFalse(gaps.stalled)
        gaps.frame(80.0, true, 120.0)
        assertTrue(gaps.stalled)
        assertEquals(40.0, gaps.maxGapMs)
    }

    @Test fun aHiddenDisplayDoesNotBecomeAStall() {
        val gaps = FrameGaps()
        gaps.frame(0.0, true, 120.0)
        gaps.frame(5000.0, false, 120.0)
        gaps.frame(10000.0, true, 120.0)
        gaps.frame(10016.0, true, 120.0)
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
