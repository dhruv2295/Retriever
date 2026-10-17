package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NpuGateTest {
    private fun ready(manufacturer: String, vararg libs: String) =
        ModelBuilds.npuRuntimeReady(manufacturer) { it in libs }

    @Test
    fun qualcommNeedsQnnLibsToo() {
        assertFalse(ready("QTI", "libLiteRtDispatch_Qualcomm.so"))
        assertTrue(ready("QTI", "libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnSystem.so"))
    }

    @Test
    fun tensorAndMediaTekNeedTheirDispatchLib() {
        assertTrue(ready("Google", "libLiteRtDispatch_GoogleTensor.so"))
        assertFalse(ready("Google"))
        assertTrue(ready("Mediatek", "libLiteRtDispatch_MediaTek.so"))
        assertFalse(ready("Mediatek", "libLiteRtDispatch_GoogleTensor.so"))
    }

    @Test
    fun unknownVendorIsNeverReady() = assertFalse(ready("Samsung", "libLiteRtDispatch_GoogleTensor.so"))

    @Test
    fun qualcommWithoutRuntimeGetsGenericBuild() {
        val probe = DeviceProbe(Platform.ANDROID, 35, "QTI", "SM8650", 8.0, oldMaliGpu = false, npuDispatch = false)
        assertEquals(ModelVariant.M440.generic, ModelBuilds.buildFor(ModelVariant.M440, probe, emptySet()))
    }
}
