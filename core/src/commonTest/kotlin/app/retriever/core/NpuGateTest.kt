package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NpuGateTest {
    private fun ready(manufacturer: String, vararg libs: String) = ModelBuilds.npuRuntimeReady(manufacturer) { it in libs }

    @Test
    fun qualcommNeedsTheQnnLibrariesPlayInstalls() {
        assertTrue(ready("QTI", "libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnSystem.so"))
        assertTrue(ready("Qualcomm", "libLiteRtDispatch_Qualcomm.so", "libQnnHtp.so", "libQnnSystem.so"))
        // A sideloaded APK has the dispatch library from Maven but not the feature module's QNN libraries.
        assertFalse(ready("QTI", "libLiteRtDispatch_Qualcomm.so"))
    }

    @Test
    fun tensorAndMediaTekNeedTheirDispatchLib() {
        assertTrue(ready("Google", "libLiteRtDispatch_GoogleTensor.so"))
        assertFalse(ready("Google"))
        assertTrue(ready("Mediatek", "libLiteRtDispatch_MediaTek.so"))
        assertFalse(ready("Mediatek", "libLiteRtDispatch_GoogleTensor.so"))
        // The embedder links only the build's own vendor's library: with both in one directory, a Tensor G6 loaded MediaTek's.
        for (b in ModelBuilds.npu) {
            val vendor = b.soc.orEmpty().substringBefore('_').replace("Google", "GoogleTensor")
            assertEquals("libLiteRtDispatch_$vendor.so", ModelBuilds.dispatchLibrary(b.soc.orEmpty()))
        }
    }

    @Test
    fun unknownVendorIsNeverReady() = assertFalse(ready("Samsung", "libLiteRtDispatch_GoogleTensor.so"))
}
