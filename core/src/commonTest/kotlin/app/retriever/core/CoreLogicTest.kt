package app.retriever.core

import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class CoreLogicTest {
    private val sig = Signature.of(ModelVariant.M740.generic, 140, 512)

    private fun randomRaw(rnd: Random, n: Int) = FloatArray(n) { rnd.nextFloat() * 2f - 1f }

    @Test
    fun vectorOfHasDimsBytesAndUnitNorm() {
        val v = Vector.of(randomRaw(Random(42), 768), sig)
        assertEquals(DIMS, v.bytes.size)
        var sum = 0.0
        for (b in v.bytes) {
            val x = b * v.scale
            sum += x * x
        }
        assertEquals(1.0, kotlin.math.sqrt(sum), 0.02)
    }

    @Test
    fun vectorOfRejectsShortRaw() {
        assertFailsWith<IllegalArgumentException> { Vector.of(FloatArray(DIMS - 1), sig) }
    }

    @Test
    fun vectorOfRejectsNaN() {
        val raw = randomRaw(Random(1), 768)
        raw[5] = Float.NaN
        assertFailsWith<IllegalArgumentException> { Vector.of(raw, sig) }
    }

    @Test
    fun vectorOfIgnoresDimsAfterFirstDims() {
        val a = randomRaw(Random(7), 300)
        val b = a.copyOf().also { it[299] += 1f }
        assertContentEquals(Vector.of(a, sig).bytes, Vector.of(b, sig).bytes)
    }

    @Test
    fun cosineOfSelfIsOneAndOfNegationIsMinusOne() {
        val a = randomRaw(Random(3), 768)
        val neg = FloatArray(a.size) { -a[it] }
        assertEquals(1f, cosine(a, a), 1e-5f)
        assertEquals(-1f, cosine(a, neg), 1e-5f)
    }

    @Test
    fun dotInt8MatchesCosineOfRawFloats() {
        val rnd = Random(42)
        repeat(20) {
            val a = randomRaw(rnd, 768)
            val b = randomRaw(rnd, 768)
            val va = Vector.of(a, sig)
            val vb = Vector.of(b, sig)
            val dot = dotInt8(va.bytes, 0, vb.bytes, vb.scale, va.scale)
            assertEquals(cosine(a, b), dot, 0.02f)
        }
    }

    @Test
    fun chunkerBoundsChunksAndKeepsWordOrder() {
        val words = (0 until 500).map { "w$it" }
        val chunks = Chunker.chunks(words.joinToString(" "))
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.split(' ').size <= 220 })
        assertEquals(words, chunks.flatMap { it.split(' ') })
    }

    @Test
    fun chunkerReturnsNothingForBlankText() {
        assertEquals(emptyList(), Chunker.chunks(""))
        assertEquals(emptyList(), Chunker.chunks("   \n  "))
    }

    @Test
    fun chunkerKeepsShortParagraphAsOneChunk() {
        val text = "A short note about the trip."
        assertEquals(listOf(text), Chunker.chunks(text))
    }

    @Test
    fun prefixesMatchModelCard() {
        assertEquals("title: none | text: x", Prefix.document("", "x"))
        assertEquals("task: search result | query: q", Prefix.query("q"))
    }

    @Test
    fun rrfMergeOrdersByReciprocalRankSum() {
        val merged = rrfMerge(listOf(listOf(1L, 2L, 3L), listOf(3L, 1L)))
        assertEquals(listOf(1L, 3L, 2L), merged.map { it.first })
        assertEquals(1.0 / 61 + 1.0 / 62, merged[0].second, 1e-9)
        assertEquals(1.0 / 63 + 1.0 / 61, merged[1].second, 1e-9)
        assertEquals(1.0 / 62, merged[2].second, 1e-9)
    }

    @Test
    fun tiersPickProfileFromImageSpeedAndRam() {
        // Measured Pixel 8 Pro: GPU image 447 ms, 12 GB.
        val balanced = Tiers.profileFor(447.0, 12.0, Backend.GPU, ModelVariant.M740)
        assertEquals(Tier.BALANCED, balanced.tier)
        assertEquals(ModelVariant.M740, balanced.model)
        assertEquals(70, balanced.visionTokens)
        assertEquals(5, balanced.videoFrameEverySec)
        assertEquals(true, balanced.audio)
        assertEquals(false, balanced.bulkOnlyWhenCharging)

        val full = Tiers.profileFor(100.0, 12.0, Backend.GPU, ModelVariant.M740)
        assertEquals(Tier.FULL, full.tier)
        assertEquals(140, full.visionTokens)
        assertEquals(2, full.videoFrameEverySec)

        assertEquals(Tier.BALANCED, Tiers.profileFor(100.0, 7.0, Backend.GPU, ModelVariant.M740).tier)

        val light = Tiers.profileFor(1500.0, 12.0, Backend.GPU, ModelVariant.M740)
        assertEquals(Tier.LIGHT, light.tier)
        assertEquals(false, light.audio)
        assertEquals(30, light.videoFrameEverySec)
        assertEquals(true, light.bulkOnlyWhenCharging)

        assertFalse(Tiers.profileFor(447.0, 12.0, Backend.GPU, ModelVariant.M440).audio)
    }

    @Test
    fun tiersPassBackendVariantAndTokensThrough() {
        val p = Tiers.profileFor(100.0, 12.0, Backend.CPU, ModelVariant.M440, maxTokens = 256)
        assertEquals(Backend.CPU, p.backend)
        assertEquals(ModelVariant.M440, p.model)
        assertEquals(256, p.maxTokens)
    }

    @Test
    fun signatureKeysOnFileShaAndTokenWindow() {
        assertEquals("M740/e7a8a220/vt70/d256/t512", Signature.of(ModelVariant.M740.generic, 70, 512).value)
    }

    @Test
    fun variantsArePinnedToCommits() {
        assertEquals(listOf(ModelVariant.M740, ModelVariant.M440), ModelVariant.entries)
        for (v in ModelVariant.entries) {
            assertTrue(Regex("^[0-9a-f]{40}$").matches(v.commit), v.name)
            assertTrue(Regex("^[0-9a-f]{64}$").matches(v.sha256), v.name)
            val url = v.generic.url
            assertTrue(v.commit in url, url)
            assertFalse("resolve/main" in url, url)
            assertFalse(v.generic.isNpu)
        }
    }

    @Test
    fun profileWithoutMaxTokensDecodesTo512() {
        val old = """{"tier":"BALANCED","model":"M740","backend":"GPU","visionTokens":70,""" +
            """"videoFrameEverySec":5,"audio":true,"bulkOnlyWhenCharging":true}"""
        assertEquals(512, Json.decodeFromString(DeviceProfile.serializer(), old).maxTokens)
    }

    private class FakeEmbedder(override val backend: Backend, private val flip: Boolean) : Embedder {
        override val signature = Signature("FAKE")

        override suspend fun embedRaw(parts: List<Part>): FloatArray {
            val rnd = Random(parts.toString().hashCode())
            return FloatArray(DIMS) { (rnd.nextFloat() * 2f - 1f) * if (flip) -1f else 1f }
        }

        override fun close() {}
    }

    private class FakeFactory(private val disagree: Backend? = null) : EmbedderFactory {
        val opened = mutableListOf<Pair<DeviceProfile, ModelBuild>>()

        override fun backends(build: ModelBuild) =
            if (build.isNpu) listOf(Backend.NPU) else listOf(Backend.GPU, Backend.CPU)

        override fun open(profile: DeviceProfile, build: ModelBuild): Embedder {
            opened += profile to build
            return FakeEmbedder(profile.backend, flip = profile.backend == disagree)
        }
    }

    private val samples = Samples(emptyList(), listOf("one", "two", "three"), emptyList())

    @Test
    fun calibrationOnNpuOnlyBuildSkipsCpuReference() = runTest {
        val npu = ModelVariant.M740.generic.copy(fileName = "npu.litertlm", soc = "SM8650")
        val factory = FakeFactory()
        val r = Calibration(factory).run(samples, npu, ramGb = 12.0, maxTokens = 256)
        assertEquals(Backend.NPU, r.chosen)
        assertEquals(listOf(Backend.NPU), r.backends.map { it.backend })
        assertTrue(r.backends.single().ok)
        assertNull(r.backends.single().meanCosineVsCpu)
        assertNull(r.backends.single().minCosineVsCpu)
        assertEquals(Backend.NPU, r.profile.backend)
        assertEquals(256, r.profile.maxTokens)
        assertTrue(factory.opened.all { (p, b) -> b == npu && p.model == b.variant && p.maxTokens == 256 })
    }

    /**
     * CPU: the first two calls of each input kind are cold (2 s, paging in weights), the rest take 100 ms. GPU: every
     * call takes 2 s, as an emulator's WebGPU fallback does.
     */
    private class TimedFactory(private val clock: TestTimeSource) : EmbedderFactory {
        val calls = mutableMapOf<Backend, Int>()

        override fun backends(build: ModelBuild) = listOf(Backend.GPU, Backend.CPU)

        override fun open(profile: DeviceProfile, build: ModelBuild): Embedder = object : Embedder {
            override val signature = Signature("timed")
            override val backend = profile.backend
            private val seen = mutableMapOf<String, Int>()

            override suspend fun embedRaw(parts: List<Part>): FloatArray {
                calls[backend] = (calls[backend] ?: 0) + 1
                val kind = when (val p = parts.single()) {
                    is Part.Image -> "image"
                    is Part.Text -> if (p.text.startsWith(Prefix.query(""))) "query" else "text"
                    else -> "audio"
                }
                val n = seen.getOrElse(kind) { 0 }.also { seen[kind] = it + 1 }
                clock += if (backend == Backend.GPU || n < 2) 2.seconds else 100.milliseconds
                return FloatArray(DIMS) { 1f }
            }

            override fun close() {}
        }
    }

    @Test
    fun calibrationTimesAWarmModelAndStopsMeasuringABackendFarBehindCpu() = runTest {
        val clock = TestTimeSource()
        val factory = TimedFactory(clock)
        val samples = Samples(List(5) { byteArrayOf(it.toByte()) }, listOf("one", "two", "three"), emptyList())
        val r = Calibration(factory, clock).run(samples, ModelVariant.M740.generic, ramGb = 8.0)
        val cpu = r.backends.single { it.backend == Backend.CPU }
        // Cold calls of every kind are left out, so the photo time is the warm one and the tier fits the phone.
        assertEquals(100.0, cpu.imageMs)
        assertEquals(100.0, cpu.textMs)
        assertEquals(100.0, cpu.queryMs)
        assertEquals(Tier.FULL, r.profile.tier)
        // The GPU is dropped once it has spent three times CPU's whole measurement, before its last calls.
        val gpu = r.backends.single { it.backend == Backend.GPU }
        assertFalse(gpu.ok)
        assertEquals("much slower than CPU", gpu.error)
        assertTrue(factory.calls.getValue(Backend.GPU) < factory.calls.getValue(Backend.CPU))
        assertEquals(Backend.CPU, r.chosen)
    }

    @Test
    fun aRetuneMovesTheDefaultsThePersonNeverTouched() {
        val light = Tiers.profileFor(5000.0, 8.0, Backend.CPU, ModelVariant.M740)
        val balanced = Tiers.profileFor(300.0, 8.0, Backend.CPU, ModelVariant.M740)
        assertEquals(Tier.LIGHT, light.tier)
        assertEquals(Tier.BALANCED, balanced.tier)
        // Untouched: LIGHT's audio off and charging-only become BALANCED's.
        assertEquals(Prefs.defaultFor(balanced), Prefs.defaultFor(light).retuned(light, balanced))
        // Video turned off and charging-only turned off by hand stay that way; audio still follows the tier.
        val mine = Prefs.defaultFor(light).let { it.copy(kinds = it.kinds - Kind.VIDEO, onlyWhileCharging = false, paused = true) }
        val after = mine.retuned(light, balanced)
        assertEquals(Kind.entries.toSet() - Kind.VIDEO, after.kinds)
        assertFalse(after.onlyWhileCharging)
        assertTrue(after.paused)
    }

    @Test
    fun calibrationPicksCpuWhenGpuDisagrees() = runTest {
        val r = Calibration(FakeFactory(disagree = Backend.GPU)).run(samples, ModelVariant.M740.generic, ramGb = 12.0)
        assertEquals(Backend.CPU, r.chosen)
        val gpu = r.backends.single { it.backend == Backend.GPU }
        assertFalse(gpu.ok)
        assertTrue(gpu.meanCosineVsCpu!! < 0.98f)
    }
}
