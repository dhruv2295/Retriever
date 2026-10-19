package app.retriever.core

import kotlinx.serialization.json.Json
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

    @Test fun stallingForegroundGpuMovesAllInferenceToCpu() {
        val p = DeviceProfile(Tier.BALANCED, ModelVariant.M440, Backend.GPU, 70, 5, false, false, gpuStallsRendering = true)
        assertEquals(Backend.CPU, inferenceBackendFor(p, visible = true))
        assertEquals(Backend.GPU, inferenceBackendFor(p, visible = false))
        assertEquals(Backend.GPU, inferenceBackendFor(p.copy(gpuStallsRendering = false), visible = true))
        assertEquals(Backend.CPU, inferenceBackendFor(p.copy(backend = Backend.CPU), visible = false))
        assertEquals(Backend.NPU, inferenceBackendFor(p.copy(backend = Backend.NPU), visible = true))
    }

    @Test fun earlierTuneResultsStillProtectRendering() {
        val saved = """
            {"tier":"BALANCED","model":"M440","backend":"GPU","visionTokens":70,"videoFrameEverySec":5,
            "audio":false,"bulkOnlyWhenCharging":false,"gpuVisionStalls":true}
        """.trimIndent()
        val profile = Json.decodeFromString<DeviceProfile>(saved)
        assertTrue(profile.gpuStallsRendering)
        assertEquals(Backend.CPU, inferenceBackendFor(profile, true))
        assertEquals(profile, Json.decodeFromString<DeviceProfile>(Json.encodeToString(profile)))
    }
}
