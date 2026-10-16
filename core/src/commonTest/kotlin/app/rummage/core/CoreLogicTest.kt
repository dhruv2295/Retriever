package app.rummage.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreLogicTest {
    private val sig = Signature.of(ModelVariant.M740, 140)

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
        val full = Tiers.profileFor(100.0, 12.0, Backend.GPU)
        assertEquals(Tier.FULL, full.tier)
        assertEquals(ModelVariant.M740, full.model)
        assertEquals(140, full.visionTokens)
        assertEquals(2, full.videoFrameEverySec)
        assertEquals(true, full.audio)
        assertEquals(false, full.bulkOnlyWhenCharging)

        assertEquals(Tier.BALANCED, Tiers.profileFor(100.0, 7.0, Backend.GPU).tier)

        val balanced = Tiers.profileFor(400.0, 12.0, Backend.GPU)
        assertEquals(Tier.BALANCED, balanced.tier)
        assertEquals(ModelVariant.M740, balanced.model)
        assertEquals(70, balanced.visionTokens)
        assertEquals(5, balanced.videoFrameEverySec)
        assertEquals(true, balanced.audio)

        val light = Tiers.profileFor(401.0, 12.0, Backend.GPU)
        assertEquals(Tier.LIGHT, light.tier)
        assertEquals(ModelVariant.M740, light.model)
        assertEquals(false, light.audio)
        assertEquals(0, light.videoFrameEverySec)
    }

    @Test
    fun tiersPassBackendThrough() {
        assertEquals(Backend.CPU, Tiers.profileFor(100.0, 12.0, Backend.CPU).backend)
    }
}
