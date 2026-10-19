package app.retriever.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.retriever.DebugVision
import app.retriever.core.Backend
import app.retriever.core.DeviceProfile
import app.retriever.core.ModelVariant
import app.retriever.core.Tier
import app.retriever.core.android.MediaPipeEmbedderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VisionOverrideTest {
    @Test fun overrideSurvivesFactoryRecreationAndProfileWritesUntilPolicyIsRestored() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun factory() = MediaPipeEmbedderFactory(context, context.filesDir) { DebugVision.backend(context) }
        val p = DeviceProfile(Tier.LIGHT, ModelVariant.M440, Backend.GPU, 70, 30, false, true, gpuStallsRendering = true)
        DebugVision.set(context, "gpu")
        val gpu = factory().profileNow(p, true)
        assertEquals(Backend.GPU, gpu.backend)
        assertEquals(Backend.GPU, gpu.visionBackend)
        DebugVision.set(context, "cpu")
        val cpu = factory().profileNow(p.copy(backend = Backend.CPU), false)
        assertEquals(Backend.CPU, cpu.backend)
        assertEquals(Backend.CPU, cpu.visionBackend)
        DebugVision.set(context, "policy")
        assertEquals(Backend.CPU, factory().profileNow(p, true).backend)
        assertEquals(Backend.CPU, factory().profileNow(p, true).visionBackend)
        assertEquals(Backend.GPU, factory().profileNow(p, false).backend)
        assertEquals(Backend.GPU, factory().profileNow(p, false).visionBackend)
    }
}
