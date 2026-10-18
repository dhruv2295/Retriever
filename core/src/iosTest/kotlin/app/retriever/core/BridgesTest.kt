@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BridgesTest {
    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    @Test
    fun foldLatinStripsAccentsThroughNSString() {
        assertEquals("cafe", foldLatin("Café"))
        assertEquals("plain", foldLatin("Plain"))
    }

    @Test
    fun nsDataRoundTrips() {
        val bytes = byteArrayOf(1, -2, 3, 127, -128)
        assertContentEquals(bytes, bytes.toNSData().toByteArray())
        assertContentEquals(ByteArray(0), ByteArray(0).toNSData().toByteArray())
        val floats = floatArrayOf(1f, -2.5f, 0f)
        assertContentEquals(floats, floats.toLeBytes().toNSData().toFloatArray())
        assertFailsWith<IllegalArgumentException> { bytes.toNSData().toFloatArray() }
    }

    @Test
    fun cancellableReportsValueOrCancellationOnce() = test {
        val ok = CompletableDeferred<Pair<Int?, Throwable?>>()
        cancellable({ 42 }) { v, t -> ok.complete(v to t) }
        assertEquals(42 to null, ok.await())

        val calls = mutableListOf<Throwable?>()
        val cancelled = CompletableDeferred<Unit>()
        val handle = cancellable<Int>({ awaitCancellation() }) { _, t ->
            calls += t
            cancelled.complete(Unit)
        }
        handle.close()
        handle.close()
        cancelled.await()
        assertEquals(1, calls.size)
        assertIs<CancellationException>(calls.single())
    }

    @Test
    fun cancelledSearchFreesTheEmbedderForTheNextOne() = test {
        val dir = NSTemporaryDirectory() + "retriever-bridges-" + NSUUID().UUIDString
        try {
            val embedder = HangingEmbedder()
            val r = retriever(dir, embedder)
            r.refreshSetUp()
            // A cold search answers from keywords and opens the model for the next search.
            r.warmSearch()

            val first = CompletableDeferred<Throwable?>()
            val handle = r.searchCancellable("beach", null, 20, false, false) { _, t -> first.complete(t) }
            embedder.entered.await()
            handle.close()
            assertIs<CancellationException>(first.await())

            // Had the first search kept running, it would still hold the embedder mutex and this one would time out.
            embedder.hang = false
            val second = CompletableDeferred<Pair<SearchEngine.SearchResult?, Throwable?>>()
            r.searchCancellable("beach sunset", null, 20, false, false) { v, t -> second.complete(v to t) }
            val (result, error) = second.await()
            assertNull(error)
            assertTrue(result!!.vectorLane)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(dir, null)
        }
    }

    /** Like IosEmbedder, serializes embeds on a mutex; query embeds hang until cancelled while [hang] is set. */
    private class HangingEmbedder : Embedder {
        override val signature = Signature("FAKE/vt70/d256")
        override val backend = Backend.CPU
        val entered = CompletableDeferred<Unit>()

        @Volatile
        var hang = true
        private val mutex = Mutex()

        override suspend fun embedRaw(parts: List<Part>): FloatArray = mutex.withLock {
            if ((parts.single() as Part.Text).text != Prefix.query("warm up")) {
                entered.complete(Unit)
                if (hang) awaitCancellation()
            }
            FloatArray(DIMS) { if (it == 0) 1f else 0f }
        }

        override fun close() {}
    }

    private fun retriever(dir: String, embedder: HangingEmbedder): Retriever {
        val store = iosStore(dir) { _, _ -> }
        val probe = DeviceProbe(Platform.IOS, 0, "Apple", "iPhone", 8.0, false)
        store.setProfile(DeviceProfile(Tier.FULL, ModelBuilds.variantFor(probe), Backend.CPU, 70, 0, false, false))
        store.resetForModel(embedder.signature)
        return Retriever(
            store = store,
            source = object : FileSource {
                override suspend fun changes(since: String?) = Changes(emptyList(), emptyList(), "tok", isFullScan = true)
            },
            extractor = object : Extractor {
                override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = emptyList()
            },
            factory = object : EmbedderFactory {
                override fun backends(build: ModelBuild) = listOf(Backend.CPU)
                override fun open(profile: DeviceProfile, build: ModelBuild): Embedder = embedder
            },
            monitor = object : DeviceMonitor {
                override fun state() = DeviceState(8.0, true, 80, Thermal.NONE)
            },
            hasher = object : ContentHasher {
                override suspend fun quickHash(uri: String, size: Long): String? = null
                override suspend fun fullHash(uri: String): String? = null
            },
            probe = probe,
            modelReady = { true },
            platformReady = { true },
            log = { _, _ -> },
            now = { 1_700_000_000_000L },
            zoneOffsetMs = { 0L },
        )
    }
}

internal fun FloatArray.toLeBytes(): ByteArray = ByteArray(size * 4).also { out ->
    forEachIndexed { i, f ->
        val bits = f.toRawBits()
        for (b in 0..3) out[i * 4 + b] = (bits shr (8 * b)).toByte()
    }
}
