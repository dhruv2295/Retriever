package app.retriever.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** iOS opens a GPU profile on CPU while in the background (IosEmbedderFactory.backendNow); this is the simulator-free proof. */
class BackendNowTest {
    private object NoHasher : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    }

    @Volatile
    private var active = false
    private val fake = FakeFactory()
    private val factory = object : EmbedderFactory by fake {
        override fun backendNow(profile: DeviceProfile) = if (profile.backend == Backend.GPU && !active) Backend.CPU else profile.backend
    }
    private val store = newStore().also { it.setProfile(PROFILE.copy(backend = Backend.GPU)) }
    private val source = FakeSource().apply {
        next = Changes((1..3).map { testFile("f$it.txt", modifiedAt = it.toLong()) }, emptyList(), "tok", isFullScan = true)
    }
    private val r = Retriever(
        store, source, FakeExtractor(), factory, FakeMonitor(), NoHasher,
        DeviceProbe(Platform.ANDROID, 35, "Google", "Pixel", 8.0, false), { true }, { true }, { _, _ -> }, System::currentTimeMillis, { 0L },
    )

    private fun test(body: suspend () -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    private fun openedOn() = fake.opened.map { it.first.backend }

    /** The first embed is the warm-up in openLocked; the second is the first file. */
    private fun breakOnFirstFile() {
        var embeds = 0
        fake.beforeEmbed = { if (++embeds == 2) throw EmbedderClosedException("inference failed") }
    }

    @Test
    fun aBackgroundBatchOpensOnCpu() = test {
        assertEquals(NextRun.DONE, r.indexBatch(1.minutes))
        assertEquals(listOf(Backend.CPU), openedOn())
        assertEquals(3L, store.countsByState()[FileState.INDEXED])
    }

    @Test
    fun theForegroundReopensOnGpu() = test {
        r.indexBatch(1.minutes)
        active = true
        r.warmSearch() // what the app runs on coming to the front; a search itself never opens the model
        assertTrue(r.search("f1").vectorLane)
        assertEquals(listOf(Backend.CPU, Backend.GPU), openedOn())
        assertEquals(Backend.GPU, store.profile()!!.backend)
    }

    @Test
    fun aBrokenCpuEmbedderLeavesTheGpuProfileAlone() = test {
        breakOnFirstFile()
        assertEquals(NextRun.SOON, r.indexBatch(1.minutes))
        assertEquals(listOf(Backend.CPU), openedOn())
        assertEquals(Backend.GPU, store.profile()!!.backend)
    }

    @Test
    fun aBrokenGpuEmbedderStillDemotesIt() = test {
        active = true
        breakOnFirstFile()
        assertEquals(NextRun.SOON, r.indexBatch(1.minutes))
        assertEquals(listOf(Backend.GPU), openedOn())
        assertEquals(Backend.CPU, store.profile()!!.backend)
    }
}
