package app.retriever.model

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.Backend
import app.retriever.core.CalibrationDeferred
import app.retriever.core.DIMS
import app.retriever.core.DeviceState
import app.retriever.core.Embedder
import app.retriever.core.Kind
import app.retriever.core.Part
import app.retriever.core.Signature
import app.retriever.core.SourceFile
import app.retriever.core.StopReason
import app.retriever.core.Thermal
import app.retriever.core.Tier
import app.retriever.core.UpgradeStage
import app.retriever.indexing.IndexService
import app.retriever.testFile
import app.retriever.testRetriever
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Collections
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real WebP decoding and JPEG encoding of the bundled illustrations
class CalibrationSamplesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private class Fast : Embedder {
        override val signature = Signature("fast")
        override val backend = Backend.CPU
        val images: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

        override suspend fun embedRaw(parts: List<Part>): FloatArray {
            parts.filterIsInstance<Part.Image>().forEach { images += it.jpeg }
            return FloatArray(DIMS) { 1f }
        }

        override fun close() {}
    }

    @Test fun aLowBatteryStopsBeforeAnySampleReachesTheModel() = runBlocking {
        val model = Fast()
        val r = testRetriever(embedder = model, deviceState = { DeviceState(8.0, false, 49, Thermal.NONE) })
        val failure = assertFailsWith<CalibrationDeferred> { Calibrator(app, r, IndexService(app, r)).calibrate(false) }
        assertEquals(StopReason.UNPLUGGED, failure.reason)
        assertTrue(model.images.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun uiDeferralWaitsWithoutRunningTheOperationThenResumesOnce() = runTest {
        var battery = 49
        val r = testRetriever(deviceState = { DeviceState(8.0, false, battery, Thermal.NONE) })
        var state: TuneState = TuneState.Idle
        var calls = 0
        val job = async {
            r.whenCalibrationAllowed(
                running = { state = TuneState.Running },
                deferred = { state = TuneState.Deferred(it) },
                operation = {
                    calls++
                    "ready"
                },
            )
        }
        runCurrent()
        assertEquals(TuneState.Deferred(StopReason.UNPLUGGED), state)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, calls)
        assertTrue(!job.isCompleted)
        battery = 50
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("ready", job.await())
        assertEquals(1, calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aTuneRunsDownToTheSessionFloorButWaitsForSafety() = runTest {
        var saver = true
        val r = testRetriever(deviceState = { DeviceState(8.0, false, 60, Thermal.NONE, powerSave = saver) })
        var calls = 0
        val job = async {
            r.whenCalibrationAllowed(running = {}, deferred = {}) { ++calls }
        }
        runCurrent()
        assertEquals(0, calls)
        saver = false
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, job.await())
        assertFalse(r.prefs.value.started)
    }

    @Test fun deferredTuningDoesNotOverrideTheActualResourceStatusWithSetupProgress() {
        val ui = UpgradeUi(stage = UpgradeStage.TUNE, tune = TuneState.Deferred(StopReason.HOT))
        assertEquals(null, ui.downloadState("388 MB"))
    }

    /** No photo is reachable: the three bundled illustrations stand in, so a fast phone is not tiered LIGHT (F029). */
    @Test
    fun withNoPhotosTheIllustrationsKeepAFastPhoneOffLight() = runBlocking {
        val embedder = Fast()
        val extracted = mutableListOf<SourceFile>()
        val files = listOf(testFile("a.pdf", Kind.PDF), testFile("b.docx", Kind.DOC))
        val retriever = testRetriever(files, extract = {
            extracted += it
            emptyList()
        }, embedder = embedder)
        val result = Calibrator(app, retriever, IndexService(app, retriever)).calibrate(tuneAgain = false)

        assertEquals(3, embedder.images.map { it.contentHashCode() }.toSet().size)
        assertTrue(embedder.images.all { it.isNotEmpty() })
        assertNotEquals(Tier.LIGHT, result.profile.tier)
        assertEquals("no PDF or document is read for a tune", emptyList<SourceFile>(), extracted)
    }
}
