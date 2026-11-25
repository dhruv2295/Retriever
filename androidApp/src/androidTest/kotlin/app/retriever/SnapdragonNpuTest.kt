package app.retriever

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.retriever.core.Calibration
import app.retriever.core.EMBED_PX
import app.retriever.core.ModelBuild
import app.retriever.core.ModelBuilds
import app.retriever.core.ModelVariant
import app.retriever.core.Samples
import app.retriever.core.android.AndroidDeviceProbe
import app.retriever.core.android.ImageBytes
import app.retriever.core.android.MediaPipeEmbedderFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * Times the Snapdragon NPU build against the generic build with the app's own Tune (Calibration), on a phone without the
 * Play-delivered QNN modules: build with -PqnnInApk (androidApp/build.gradle.kts). Results go to logcat, tag NpuBench.
 */
@RunWith(AndroidJUnit4::class)
class SnapdragonNpuTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun npuBuildLoadsAndEmbeds() = runBlocking {
        val probe = AndroidDeviceProbe(context).probe()
        log("soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL} sdk=${Build.VERSION.SDK_INT} ram=${probe.ramGb} npuReady=${probe.npuDispatch}")
        val npu = ModelBuilds.buildFor(ModelVariant.M440, probe, emptySet())
        assertTrue("no NPU build for ${probe.socManufacturer} ${probe.socModel}", npu.isNpu)

        val dir = File(context.filesDir, "npu-bench").apply { mkdirs() }
        // The app's Tune samples when the phone has no photos, repeated to its ten.
        val images = listOf(R.drawable.illus_welcome, R.drawable.illus_reading, R.drawable.illus_empty)
            .mapNotNull { ImageBytes.jpeg(context.resources, it, EMBED_PX) }
        val samples = Samples(List(10) { images[it % images.size] }, emptyList(), emptyList())
        val calibration = Calibration(MediaPipeEmbedderFactory(context, dir))

        var npuOk = false
        for (build in listOf(npu, ModelVariant.M440.generic)) {
            download(build, dir)
            val result = calibration.run(samples, build, probe.ramGb)
            for (b in result.backends) {
                val perMin = b.imageMs?.let { 60_000 / it }
                log(
                    "${build.fileName} ${b.backend} ok=${b.ok} imageMs=${b.imageMs} photosPerMin=$perMin textMs=${b.textMs} " +
                        "queryMs=${b.queryMs} loadMs=${b.loadMs} detail=${b.detail} error=${b.error}",
                )
                if (build.isNpu && b.ok) npuOk = true
            }
            File(dir, build.fileName).delete()
        }
        assertTrue("NPU build failed; see logcat tag NpuBench", npuOk)
    }

    private fun download(build: ModelBuild, dir: File) {
        val file = File(dir, build.fileName)
        val sha = MessageDigest.getInstance("SHA-256")
        URL(build.url).openStream().use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sha.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val hex = sha.digest().joinToString("") { "%02x".format(it) }
        assertTrue("${build.fileName} sha256 $hex", hex == build.sha256)
        log("downloaded ${build.fileName} ${file.length()} bytes")
    }

    private fun log(message: String) {
        Log.i("NpuBench", message)
        println("NpuBench $message")
    }
}
