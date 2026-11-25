package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelBuildsTest {
    private fun android(soc: String, npu: Boolean = true, sdk: Int = 36, ram: Double = 12.0, vendor: String = "Google") =
        DeviceProbe(Platform.ANDROID, sdk, vendor, soc, ram, oldMaliGpu = false, npuDispatch = npu)

    private fun pick(probe: DeviceProbe, failed: Set<String> = emptySet()) = ModelBuilds.buildFor(ModelBuilds.variantFor(probe), probe, failed)

    @Test
    fun anInstalledM740KeepsItsVectorsUntilTheBuildChanges() {
        val device = android("Tensor G5")
        val old = ModelVariant.M740.generic
        val profile = DeviceProfile(Tier.FULL, ModelVariant.M740, Backend.CPU, 70, 5, true, false)
        val signature = Signature.of(old, 70, 512)
        assertEquals(old, ModelBuilds.installedOrSelected(device, profile, signature, emptySet()) { it == old })
        assertEquals(ModelVariant.M440, ModelBuilds.installedOrSelected(device, profile, signature, emptySet()) { false }.variant)
        assertEquals(ModelVariant.M440, ModelBuilds.installedOrSelected(device, profile, Signature("changed"), emptySet()) { true }.variant)
    }

    @Test
    fun tensorG3HasNoNpuBuild() = assertEquals(ModelVariant.M440.generic, pick(android("Tensor G3")))

    @Test
    fun tensorG5PicksItsNpuBuildPerVariant() {
        assertEquals("Google_Tensor_G5", pick(android("Tensor G5")).soc)
        assertEquals(ModelVariant.M440, pick(android("Tensor G5")).variant)
        val small = pick(android("Tensor G5", ram = 6.0))
        assertEquals(ModelVariant.M440, small.variant)
        assertEquals("embeddinggemma-2-text-vision-440m_Google_Tensor_G5.litertlm", small.fileName)
    }

    @Test
    fun npuNeedsDispatchAndSdk31() {
        assertEquals(ModelVariant.M440.generic, pick(android("Tensor G5", npu = false)))
        assertEquals(ModelVariant.M440.generic, pick(android("Tensor G5", sdk = 30)))
    }

    @Test
    fun mediaTekAndSnapdragonMatchTheirSoc() {
        assertEquals("Qualcomm_SM8650", pick(android("SM8650", vendor = "QTI")).soc)
        assertEquals(ModelVariant.M440.generic, pick(android("SM8635", vendor = "QTI")))
        assertEquals("MediaTek_MT6991", pick(android("MT6991", vendor = "Mediatek")).soc)
        assertEquals("MediaTek_MT6991", pick(android("MT6991-AB", vendor = "Mediatek")).soc)
    }

    @Test
    fun failedNpuFallsBackToGeneric() {
        val npu = pick(android("Tensor G5"))
        assertTrue(npu.isNpu)
        assertEquals(ModelVariant.M440.generic, pick(android("Tensor G5"), setOf(npu.fileName)))
        assertEquals(ModelVariant.M440.generic, ModelBuilds.fallback(npu))
    }

    @Test
    fun variantByPlatformAndRam() {
        val ios = DeviceProbe(Platform.IOS, 0, "Apple", "iPhone17,1", 8.0, oldMaliGpu = false, npuDispatch = true)
        assertEquals(ModelVariant.M440.generic, pick(ios))
        assertEquals(ModelVariant.M440, ModelBuilds.variantFor(android("x", ram = 12.0)))
        assertEquals(ModelVariant.M440, ModelBuilds.variantFor(android("x", ram = 6.0)))
    }

    @Test
    fun nativeCrashGuard() {
        assertFalse(ModelBuilds.shouldFallBack(1))
        assertTrue(ModelBuilds.shouldFallBack(2))
    }

    @Test
    fun manifestIsWellFormed() {
        val hex = Regex("[0-9a-f]{64}")
        for (b in ModelBuilds.npu) {
            assertTrue(hex.matches(b.sha256), b.fileName)
            assertTrue(b.url.contains("/resolve/${b.variant.commit}/"), b.url)
            assertTrue(b.fileName.endsWith("_${b.soc}.litertlm"), b.fileName)
            assertTrue(b.sizeBytes > 0)
        }
        assertEquals(8, ModelBuilds.npu.count { it.variant == ModelVariant.M440 })
    }

    // A TestFlight iPhone short of space aborted in XNNPack writing the weight cache, at every launch.
    @Test
    fun aPhoneShortOfSpaceKeepsTheWeightCacheInMemory() {
        val build = ModelVariant.M440.generic
        assertEquals("/caches/embed", cacheDirFor(build, "/caches/embed", build.sizeBytes))
        assertEquals(NO_DISK_CACHE, cacheDirFor(build, "/caches/embed", build.sizeBytes - 1))
    }
}
