package app.retriever.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class VisionPolicyTest {
    @Test fun visibilityAndSearchWarmupNeverReplaceTheModelDuringAFile() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val opened = mutableListOf<DeviceProfile>()
        var closed = 0
        var calls = 0
        val factory = object : EmbedderFactory {
            override fun backends(build: ModelBuild) = listOf(Backend.GPU, Backend.CPU)
            override fun open(profile: DeviceProfile, build: ModelBuild): Embedder {
                opened += profile
                return object : Embedder {
                    override val backend = profile.backend
                    override val visionBackend = profile.visionBackend ?: backend
                    override val signature = Signature.of(build, profile.visionTokens, profile.maxTokens)
                    override suspend fun embedRaw(parts: List<Part>): FloatArray {
                        if (++calls == 2) {
                            entered.complete(Unit)
                            resume.await()
                            assertEquals(0, closed)
                            assertEquals(1, opened.size)
                        }
                        return FloatArray(DIMS) { 1f }
                    }
                    override fun close() {
                        closed++
                    }
                }
            }
        }
        val profile = PROFILE.copy(backend = Backend.GPU, gpuStallsRendering = true, cpuImageMs = 2000.0, gpuImageMs = 100.0)
        val store = newStore().also { it.setProfile(profile) }
        val source = FakeSource().apply { next = Changes(listOf(testFile("one.txt"), testFile("two.txt")), emptyList(), "1", true) }
        val monitor = FakeMonitor()
        val r = Retriever(
            store, source, FakeExtractor(), factory, monitor, NoHashes, PIXEL, { true }, { true }, { _, _ -> },
            System::currentTimeMillis, { 0L }, CoreDispatchers(dispatcher),
        ).apply { uiVisible = true }
        r.planTaste()
        val batch = async { r.taste() }
        entered.await()
        r.uiVisible = false // screen is off; all inference may use GPU after this file
        val warm = async { r.warmSearch() }
        runCurrent()
        assertFalse(warm.isCompleted)
        assertEquals(listOf(Backend.CPU), opened.map { it.visionBackend })
        resume.complete(Unit)
        assertEquals(NextRun.SOON, batch.await())
        warm.await()
        assertTrue(r.hasPendingTaste())
        assertEquals(NextRun.DONE, r.taste())
        assertEquals(listOf(Backend.CPU, Backend.GPU), opened.map { it.visionBackend })
        assertEquals(listOf(Backend.CPU, Backend.GPU), opened.map { it.backend })
        assertEquals(0L, r.counts().first().byState[FileState.PENDING])
        assertEquals(1L, SpeedBook.decode(store.meta("speed_by_kind_CPU_CPU")).paces[Kind.DOC]?.files)
        assertEquals(1L, SpeedBook.decode(store.meta("speed_by_kind_GPU_GPU")).paces[Kind.DOC]?.files)
    }

    @Test fun anUntouchedAppOnAChargerReadsOnTheGpuAndTheNextInputTurnsItBack() = runBlocking {
        val files = (1..3).map { testFile("f$it.txt") }
        var clock = 1_000_000L
        var retriever: Retriever? = null
        val extractor = FakeExtractor(files.associate { it.name to listOf(textPiece("words in ${it.name}")) }) { file ->
            if (file.name == "f1.txt") clock += IDLE_IN_FRONT_MS // left alone while the first file reads
            if (file.name == "f2.txt") retriever?.inputSeen() // touched while the second one reads
        }
        val factory = FakeFactory()
        val store = newStore().also { it.setProfile(PROFILE.copy(backend = Backend.GPU, gpuStallsRendering = true)) }
        val r = testRetriever(store, files, extractor, factory = factory, now = { clock }).apply {
            uiVisible = true
            inputSeen()
        }
        retriever = r
        r.refreshSetUp()
        repeat(4) { r.indexBatch(1.minutes) }
        // Each change ends the batch after its file, so the model reopens once per change, not per file.
        assertEquals(listOf(Backend.CPU, Backend.GPU, Backend.CPU), factory.opened.map { it.first.backend })
        assertEquals(3L, r.counts().first().byState[FileState.INDEXED])
    }

    @Test fun interruptedTrialDoesNotKeepTheNamesOnlyBatchesDoneReason() = runTest {
        val file = testFile("planned.txt")
        val store = newStore()
        val factory = FakeFactory()
        var ready = false
        val r = testRetriever(store, listOf(file), FakeExtractor(), factory = factory, modelReady = { ready }).apply { uiVisible = true }
        r.planTaste()
        assertEquals(NextRun.DONE, r.taste()) // keyword reading before the download
        assertEquals(StopReason.DONE, r.state.value.lastStop)
        store.setProfile(PROFILE)
        ready = true
        var calls = 0
        factory.beforeEmbed = { if (++calls == 2) throw EmbedderClosedException("model replaced", broken = false) }
        assertEquals(NextRun.SOON, r.taste())
        assertTrue(r.hasPendingTaste())
        assertNull(r.state.value.lastStop, "a model interruption is not a completed read")
        factory.beforeEmbed = {}
        assertEquals(NextRun.DONE, r.taste())
        assertFalse(r.hasPendingTaste())
    }

    @Test fun failedFilesAndTailsDrainBeforeTrialDone() = runTest {
        val good = testFile("good.txt")
        val bad = testFile("bad.txt")
        val outside = testFile("outside.txt")
        val store = newStore().also { it.setProfile(PROFILE) }
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
                if (file == bad) error("bad input")
                return List(12) { textPiece("part $it") }
            }
        }
        val r = testRetriever(store, listOf(good, bad, outside), extractor).apply { uiVisible = true }
        r.planTaste()
        store.setTaste(listOf(good, bad).map { store.fileByUri(it.uri)!!.id })
        assertEquals(NextRun.DONE, r.taste())
        assertFalse(r.hasPendingTaste())
        assertEquals(3, store.fileByUri(bad.uri)!!.attempts)
        assertEquals(FileState.SKIPPED, store.fileByUri(bad.uri)!!.state)
        assertEquals(0, store.tailChunksIn(store.tasteIds()).size)
        val counts = r.counts().first()
        assertEquals(0L, counts.byState[FileState.PENDING])
        assertEquals(1L, counts.waiting)
        assertTrue(counts.trial)
    }

    @Test fun trialPlannerUsesTheSelectedVisionPaceAndRetierKeepsTheStallMeasurement() = runTest {
        val files = (1..100).map { testFile("photo$it.jpg", Kind.PHOTO, modifiedAt = it * 7200000L) }
        val p = PROFILE.copy(tier = Tier.LIGHT, backend = Backend.GPU, gpuStallsRendering = true, cpuImageMs = 10000.0, gpuImageMs = 100.0)
        val store = newStore().also { it.setProfile(p) }
        val extractor = FakeExtractor(files.associate { it.name to listOf(Piece("", listOf(Part.Image(byteArrayOf(1))), null)) })
        val r = testRetriever(store, files, extractor).apply { uiVisible = true }
        val cpu = r.planTaste()
        assertEquals(9, cpu.ids.size)
        assertEquals(90, cpu.seconds)
        r.uiVisible = false
        val gpu = r.planTaste()
        // All 100 fit the GPU's pace; the demo takes 30 of them.
        assertEquals(DEMO_FILES, gpu.ids.size)
        assertEquals(3, gpu.seconds)
        // Actual reads can raise the tier without erasing Tune's measurements.
        r.uiVisible = true
        r.taste()
        assertEquals(Tier.FULL, store.profile()!!.tier)
        assertTrue(store.profile()!!.gpuStallsRendering)
        assertEquals(10000.0, store.profile()!!.cpuImageMs)
    }

    private object NoHashes : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    }
}
