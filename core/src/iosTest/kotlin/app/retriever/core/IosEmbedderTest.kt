@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertTrue

/** IosEmbedder's fallbacks, through IosEmbedderFactory with a scripted NativeEmbedder. */
class IosEmbedderTest {
    private val cacheDir = NSTemporaryDirectory() + "retriever-embedder-" + NSUUID().UUIDString
    private val good = FloatArray(DIMS) { it.toFloat() }
    private val ok = good.toLeBytes().toNSData() to 0
    private val tooLong = null to 1
    private val failed = null to 2

    @AfterTest
    fun cleanUp() {
        NSFileManager.defaultManager.removeItemAtPath(cacheDir, null)
    }

    /** Each embed call takes the next (output, lastError) pair. */
    private class ScriptedNative(private val script: List<Pair<NSData?, Int>>) : NativeEmbedder {
        val calls = mutableListOf<List<Any>>()
        var closed = false
        override var lastError = 0
        override var lastMessage: String? = null

        override fun embed(parts: List<Any>): NSData? {
            val (out, error) = script[calls.size]
            calls += parts
            lastError = error
            lastMessage = if (out == null) "error $error" else null
            return out
        }

        override fun close() {
            closed = true
        }
    }

    private fun open(native: NativeEmbedder, backend: Backend = Backend.CPU, active: Boolean = true): Embedder {
        val opener = object : NativeEmbedderOpener {
            override fun open(modelPath: String, gpu: Boolean, visionGpu: Boolean, visionTokens: Int, maxTokens: Int, cacheDir: String) = native
            override val lastMessage: String? = null
        }
        val variant = ModelVariant.M440
        val build = ModelBuild(variant, "model.litertlm", 1L, "0123456789abcdef")
        return IosEmbedderFactory(opener, "/nowhere", cacheDir, isSimulator = false) { active }
            .open(DeviceProfile(Tier.FULL, variant, backend, 70, 0, false, false), build)
    }

    private fun embed(e: Embedder) = runBlocking { e.embedRaw(listOf(Part.Text("hello"))) }

    @Test fun renderingStallsMoveTextAndVisionToCpuAndBackgroundStillUsesCpu() {
        var delegates: Pair<Boolean, Boolean>? = null
        val opener = object : NativeEmbedderOpener {
            override val lastMessage: String? = null
            override fun open(modelPath: String, gpu: Boolean, visionGpu: Boolean, visionTokens: Int, maxTokens: Int, cacheDir: String): NativeEmbedder {
                delegates = gpu to visionGpu
                return ScriptedNative(listOf(ok))
            }
        }
        var active = true
        val factory = IosEmbedderFactory(opener, "/nowhere", cacheDir, false) { active }
        val p = DeviceProfile(Tier.BALANCED, ModelVariant.M440, Backend.GPU, 70, 5, false, false, gpuStallsRendering = true)
        factory.open(factory.profileNow(p, true), ModelVariant.M440.generic).close()
        assertEquals(false to false, delegates)
        factory.open(factory.profileNow(p.copy(gpuStallsRendering = false), true), ModelVariant.M440.generic).close()
        assertEquals(true to true, delegates)
        active = false
        factory.open(factory.profileNow(p, false), ModelVariant.M440.generic).close()
        assertEquals(false to false, delegates)
    }

    @Test
    fun goodOutputDecodesAsFloats() {
        val native = ScriptedNative(listOf(ok))
        assertContentEquals(good, embed(open(native)))
        assertEquals(listOf<List<Any>>(listOf("hello")), native.calls)
    }

    @Test
    fun tooLongInputIsReportedAndKeepsTheModel() {
        val native = ScriptedNative(listOf(tooLong))
        assertFailsWith<InputTooLongException> { embed(open(native)) }
        assertFalse(native.closed)
        assertEquals(1, native.calls.size)
    }

    @Test
    fun gpuInTheBackgroundClosesWithoutBlamingTheBackend() {
        val native = ScriptedNative(listOf(failed))
        val e = assertFailsWith<EmbedderClosedException> { embed(open(native, Backend.GPU, active = false)) }
        assertFalse(e.broken)
        assertTrue(native.closed)
        assertEquals(1, native.calls.size) // no probe
    }

    /** The person's continued read, granted the GPU in the background, keeps it until the GPU refuses after all. */
    @Test
    fun aBackgroundGpuGrantKeepsTheGpuUntilItIsRefused() {
        var granted = true
        val natives = mutableListOf<NativeEmbedder?>(ScriptedNative(listOf(failed)), null)
        val opener = object : NativeEmbedderOpener {
            override val lastMessage: String? = null
            override fun open(modelPath: String, gpu: Boolean, visionGpu: Boolean, visionTokens: Int, maxTokens: Int, cacheDir: String) = natives.removeFirst()
        }
        val factory = IosEmbedderFactory(opener, "/nowhere", cacheDir, false, { granted }, { granted = false }) { false }
        val gpu = DeviceProfile(Tier.FULL, ModelVariant.M440, Backend.GPU, 70, 0, false, false)
        assertEquals(Backend.GPU, factory.backendNow(gpu))

        val refused = assertFailsWith<EmbedderClosedException> { embed(factory.open(gpu, ModelVariant.M440.generic)) }
        assertFalse(refused.broken)
        assertFalse(granted)
        assertEquals(Backend.CPU, factory.backendNow(gpu))

        granted = true
        assertFalse(assertFailsWith<EmbedderClosedException> { factory.open(gpu, ModelVariant.M440.generic) }.broken)
        assertFalse(granted, "an open refused in the background drops the grant too")
    }

    @Test
    fun aPassingProbeFailsOnlyTheInput() {
        val native = ScriptedNative(listOf(failed, ok))
        val e = assertFailsWith<IllegalArgumentException> { embed(open(native)) }
        assertIsNot<InputTooLongException>(e)
        assertFalse(native.closed)
        assertEquals(listOf<Any>(Prefix.query("warm up")), native.calls[1])
    }

    @Test
    fun aFailingProbeClosesTheBrokenBackend() {
        val native = ScriptedNative(listOf(failed, failed))
        val e = assertFailsWith<EmbedderClosedException> { embed(open(native)) }
        assertTrue(e.broken)
        assertTrue(native.closed)
    }

    @Test
    fun aRejectedImageUsesAnImageProbeAndKeepsTheBackend() = runBlocking {
        val native = ScriptedNative(listOf(failed, ok))
        val e = open(native)
        try {
            assertFailsWith<IllegalArgumentException> { e.embedRaw(listOf(Part.Image(byteArrayOf(1)))) }
            assertIs<NSData>(native.calls[1].single())
            assertFalse(native.closed)
        } finally {
            e.close()
        }
    }

    @Test
    fun nonFiniteImageOutputsRetireTheBackendWhenTheImageProbeFails() = runBlocking {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val invalid = floatArrayOf(value).toLeBytes().toNSData() to 0
            val native = ScriptedNative(listOf(invalid, invalid))
            val e = open(native)
            val failure = assertFailsWith<EmbedderClosedException> { e.embedRaw(listOf(Part.Image(byteArrayOf(1)))) }
            assertTrue(failure.broken)
            assertIs<NSData>(native.calls[1].single())
            assertTrue(native.closed)
        }
    }

    @Test
    fun aNonFiniteInputWithAPassingProbeKeepsTheBackend() {
        val native = ScriptedNative(listOf((floatArrayOf(Float.NaN).toLeBytes().toNSData() to 0), ok))
        val e = open(native)
        try {
            assertFailsWith<IllegalArgumentException> { embed(e) }
            assertEquals(listOf<Any>(Prefix.query("warm up")), native.calls[1])
            assertFalse(native.closed)
        } finally {
            e.close()
        }
    }

    @Test
    fun memoryAndBackgroundOpenRefusalsDoNotBlameTheGpu() {
        for (lowMemory in listOf(true, false)) {
            val opener = object : RefusesForNow {
                override val refusedForNow = lowMemory
                override val lastMessage = "open refused"
                override fun open(modelPath: String, gpu: Boolean, visionGpu: Boolean, visionTokens: Int, maxTokens: Int, cacheDir: String): NativeEmbedder? =
                    null
            }
            val factory = IosEmbedderFactory(opener, "/nowhere", cacheDir, isSimulator = false) { lowMemory }
            val profile = DeviceProfile(Tier.FULL, ModelVariant.M440, Backend.GPU, 70, 0, false, false)
            val error = assertFailsWith<EmbedderClosedException> { factory.open(profile, ModelVariant.M440.generic) }
            assertFalse(error.broken)
        }
    }
}
