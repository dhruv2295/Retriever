package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class CalibrationTest {
    /** Every image takes 100 ms; a query takes [queryMs] of its backend; CPU fails to open when [cpuFails]. */
    private class Factory(private val clock: TestTimeSource, private val queryMs: Map<Backend, Duration> = emptyMap(), private val cpuFails: Boolean = false) :
        EmbedderFactory {
        val texts = mutableListOf<String>()

        override fun backends(build: ModelBuild) = listOf(Backend.GPU, Backend.CPU)

        override fun open(profile: DeviceProfile, build: ModelBuild): Embedder {
            if (cpuFails && profile.backend == Backend.CPU) throw IllegalStateException("out of memory")
            return object : Embedder {
                override val signature = Signature("fake")
                override val backend = profile.backend

                override suspend fun embedRaw(parts: List<Part>): FloatArray {
                    val p = parts.single()
                    clock += when {
                        p is Part.Text && p.text.startsWith(Prefix.query("")) -> queryMs[backend] ?: 10.milliseconds
                        p is Part.Text -> 10.milliseconds.also { texts += p.text }
                        else -> 100.milliseconds
                    }
                    return FloatArray(DIMS) { 1f }
                }

                override fun close() {}
            }
        }
    }

    private val samples = Samples(List(3) { byteArrayOf(it.toByte()) }, emptyList(), emptyList())

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
