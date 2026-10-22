package app.retriever.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class CalibrationTest {
    /** Every image takes 100 ms; a query takes [queryMs] of its backend; CPU fails to open when [cpuFails]. */
    private class Factory(
        private val clock: TestTimeSource,
        private val queryMs: Map<Backend, Duration> = emptyMap(),
        private val cpuFails: Boolean = false,
        private val imageMs: Map<Backend, Duration> = emptyMap(),
        private val onEmbed: (Backend, Part) -> Unit = { _, _ -> },
    ) : EmbedderFactory {
        val texts = mutableListOf<String>()

        override fun backends(build: ModelBuild) = listOf(Backend.GPU, Backend.CPU)

        override fun open(profile: DeviceProfile, build: ModelBuild): Embedder {
            if (cpuFails && profile.backend == Backend.CPU) throw IllegalStateException("out of memory")
            return object : Embedder {
                override val signature = Signature("fake")
                override val backend = profile.backend

                override suspend fun embedRaw(parts: List<Part>): FloatArray {
                    val p = parts.single()
                    onEmbed(backend, p)
                    clock += when {
                        p is Part.Text && p.text.startsWith(Prefix.query("")) -> queryMs[backend] ?: 10.milliseconds
                        p is Part.Text -> 10.milliseconds.also { texts += p.text }
                        else -> imageMs[backend] ?: 100.milliseconds
                    }
                    return FloatArray(DIMS) { 1f }
                }

                override fun close() {}
            }
        }
    }

    private val samples = Samples(List(3) { byteArrayOf(it.toByte()) }, emptyList(), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun existingGpuImagesTextsAndQueriesMeasureStallsWithoutNewInference() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var callback: ((Double) -> Unit)? = null
        var starts = 0
        var stops = 0
        val frames = object : FrameSource {
            override val refreshRateHz = 120.0
            override fun start(onFrame: (Double) -> Unit) {
                starts++
                callback = onFrame
                onFrame(0.0)
            }
            override fun stop() {
                stops++
                callback = null
            }
        }
        val clock = TestTimeSource()
        try {
            for (stalledKind in listOf("image", "text", "query", "audio", "none")) {
                starts = 0
                stops = 0
                val calls = mutableMapOf<Pair<Backend, String>, Int>()
                val factory =
                    Factory(
                        clock,
                        queryMs = mapOf(
                            Backend.CPU to 78.milliseconds,
                            Backend.GPU to 103.milliseconds,
                        ),
                        imageMs = mapOf(Backend.CPU to 788.milliseconds, Backend.GPU to 474.milliseconds),
                        onEmbed = { backend, part ->
                            val kind = when (part) {
                                is Part.Image -> "image"
                                is Part.Text -> if (part.text.startsWith(Prefix.query(""))) "query" else "text"
                                else -> "audio"
                            }
                            val key = backend to kind
                            calls[key] = (calls[key] ?: 0) + 1
                            if (backend == Backend.GPU) callback?.invoke(if (kind == stalledKind) 183.0 else 8.0)
                        },
                    )
                val r = Calibration(factory, clock, frames).run(samples.copy(audio = listOf(shortArrayOf(1))), ModelVariant.M740.generic, 8.0)
                val stalls = stalledKind in listOf("image", "text", "query")
                assertEquals(stalls, r.profile.gpuStallsRendering, stalledKind)
                assertEquals(788.0, r.profile.cpuImageMs)
                assertEquals(474.0, r.profile.gpuImageMs)
                assertEquals(Backend.GPU, r.chosen)
                assertEquals(if (stalls) Backend.CPU else Backend.GPU, inferenceBackendFor(r.profile, visible = true))
                assertEquals(Backend.GPU, inferenceBackendFor(r.profile, visible = false))
                assertEquals(if (stalls) 183.0 else 8.0, r.backends.single { it.backend == Backend.GPU }.maxFrameGapMs)
                assertFalse(r.backends.single { it.backend == Backend.CPU }.renderingStalls)
                for (backend in listOf(Backend.CPU, Backend.GPU)) {
                    assertEquals(5, calls[backend to "image"], "three samples and two warm-ups, no new benchmark")
                    assertEquals(12, calls[backend to "text"])
                    assertEquals(12, calls[backend to "query"])
                    assertEquals(3, calls[backend to "audio"])
                }
                assertEquals(29, starts)
                assertEquals(starts, stops)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aWorkingGpuPassesWhenTheCpuReferenceFails() = runTest {
        val clock = TestTimeSource()
        val r = Calibration(Factory(clock, cpuFails = true), clock).run(samples, ModelVariant.M740.generic, ramGb = 8.0)
        val gpu = r.backends.single { it.backend == Backend.GPU }
        assertTrue(gpu.ok, gpu.error)
        assertNull(gpu.error)
        assertEquals(Backend.GPU, r.chosen)
    }

    @Test
    fun aGpuTurnedDownKeepsItsReasonInTheProfile() = runTest {
        val clock = TestTimeSource()
        val factory = Factory(clock, imageMs = mapOf(Backend.GPU to 10_000.milliseconds))
        val r = Calibration(factory, clock).run(samples, ModelVariant.M740.generic, ramGb = 8.0)
        assertEquals(Backend.CPU, r.chosen)
        assertNull(r.profile.gpuImageMs)
        assertEquals("much slower than CPU", r.profile.gpuError)
    }

    @Test
    fun withEqualPhotoTimesTheFasterQueryBackendWins() = runTest {
        val clock = TestTimeSource()
        val factory = Factory(clock, mapOf(Backend.CPU to 60.milliseconds, Backend.GPU to 10.milliseconds))
        val r = Calibration(factory, clock).run(samples, ModelVariant.M740.generic, ramGb = 8.0)
        assertTrue(r.backends.all { it.ok && it.imageMs == 100.0 })
        // CPU is measured first, so an image-only pick would keep it on the tie.
        assertEquals(Backend.GPU, r.chosen)
    }

    @Test
    fun coreFillsTheStandInTextsWhenAPlatformPassesNone() = runTest {
        val clock = TestTimeSource()
        val factory = Factory(clock)
        Calibration(factory, clock).run(samples, ModelVariant.M740.generic, ramGb = 8.0)
        assertTrue(Samples.FALLBACK_TEXTS.all { t -> factory.texts.any { it.endsWith(t) } })
    }
}
