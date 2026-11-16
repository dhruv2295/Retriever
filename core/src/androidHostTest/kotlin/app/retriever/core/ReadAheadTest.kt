package app.retriever.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The model's accelerator lets the next file's extraction overlap this file's embedding; videos wait for the model. */
class ReadAheadTest {
    private class OnGpu(private val inner: Embedder, override val backend: Backend = Backend.GPU, private val before: suspend (List<Part>) -> Unit = {}) :
        Embedder by inner {
        override val visionBackend get() = backend

        override suspend fun embedRaw(parts: List<Part>): FloatArray {
            before(parts)
            return inner.embedRaw(parts)
        }
    }

    private val files = listOf("a.txt", "b.txt", "c.txt").mapIndexed { i, n -> testFile(n, modifiedAt = 10L - i) }
    private val pieces = files.associate { it.name to listOf(textPiece("words of ${it.name}")) }

    @Test
    fun theNextFileIsReadWhileThisOneEmbedsOnTheGpu(): Unit = runBlocking {
        val store = newStore().apply { upsertFiles(files) }
        val bStarted = CompletableDeferred<Unit>()
        val extractor = FakeExtractor(pieces) { if (it.name == "b.txt") bStarted.complete(Unit) }
        // a's embed waits for b's extraction: without the read-ahead this would time out.
        val embedder = OnGpu(FakeEmbedder()) { parts: List<Part> ->
            if ("a.txt" in
                (parts.single() as Part.Text).text
            ) {
                withTimeout(5.seconds) { bStarted.await() }
            }
        }
        val result = IndexEngine(store, extractor, embedder, FakeMonitor(), settingsOf(), readAheadDepth = { 1 })
            .runBatch(1.minutes, requireCharging = false)

        assertEquals(BatchResult(3, 0, StopReason.DONE), result)
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), extractor.names)
        files.forEach { assertEquals(1, store.fileByUri(it.uri)!!.attempts, it.name) }
        assertEquals(3L, store.vectorCount().toLong())
    }

    @Test
    fun aStopGivesTheReadAheadFileItsAttemptBack(): Unit = runBlocking {
        val store = newStore().apply { upsertFiles(files) }
        var done = 0
        val engine = IndexEngine(
            store,
            FakeExtractor(pieces),
            OnGpu(FakeEmbedder()),
            FakeMonitor(),
            settingsOf(),
            onFile = { done++ },
            policyStop = { StopReason.MANUAL.takeIf { done > 0 } },
            readAheadDepth = { 1 },
        )

        assertEquals(StopReason.MANUAL, engine.runBatch(1.minutes, requireCharging = false).stopReason)

        assertEquals(FileState.INDEXED, store.fileByUri(files[0].uri)!!.state)
        val b = store.fileByUri(files[1].uri)!!
        assertEquals(FileState.PENDING to 0, b.state to b.attempts, "b was read ahead, then the stop gave its attempt back")
    }

    @Test
    fun onAChargerTwoFilesAreReadWhileOneEmbedsAndAStopGivesBothAttemptsBack(): Unit = runBlocking {
        val store = newStore().apply { upsertFiles(files) }
        val bothStarted = CompletableDeferred<Unit>()
        val started = mutableSetOf<String>()
        val extractor = FakeExtractor(pieces) {
            synchronized(started) {
                started += it.name
                if (started.containsAll(listOf("b.txt", "c.txt"))) bothStarted.complete(Unit)
            }
        }
        var done = 0
        // a's embed waits until b and c are both being read: with one file ahead this would time out.
        val embedder = OnGpu(FakeEmbedder()) { parts ->
            if ("a.txt" in (parts.single() as Part.Text).text) withTimeout(5.seconds) { bothStarted.await() }
        }
        val engine = IndexEngine(
            store,
            extractor,
            embedder,
            FakeMonitor(),
            settingsOf(),
            onFile = { done++ },
            policyStop = { StopReason.MANUAL.takeIf { done > 0 } },
            readAheadDepth = { IndexEngine.readAheadDepth(it, visible = false) },
        )

        assertEquals(StopReason.MANUAL, engine.runBatch(1.minutes, requireCharging = false).stopReason)
        assertEquals(FileState.INDEXED, store.fileByUri(files[0].uri)!!.state)
        for (f in files.drop(1)) {
            val stored = store.fileByUri(f.uri)!!
            assertEquals(FileState.PENDING to 0, stored.state to stored.attempts, "${f.name} was read ahead, then the stop gave its attempt back")
        }
    }

    private val fruit = mapOf("p1.jpg" to "apple", "p2.jpg" to "banana", "p3.jpg" to "cherry")
    private val photos = fruit.keys.mapIndexed { i, n -> testFile(n, Kind.PHOTO, modifiedAt = 10L - i) }

    /** Photos whose words come later; each photo's OCR text starts with its own fruit. */
    private inner class Photos(private val onWords: (SourceFile) -> Unit = {}) : Extractor {
        val words: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun extract(file: SourceFile, profile: DeviceProfile) = imageFirst(ByteArray(2))

        override suspend fun extractHead(file: SourceFile, profile: DeviceProfile) = imageFirst(ByteArray(2))

        override suspend fun later(file: SourceFile, locator: String, profile: DeviceProfile): List<Piece> {
            words += file.name
            onWords(file)
            return imageWithText("", ByteArray(2), "${fruit[file.name]} platform departs for the harbour at noon", 800)
        }
    }

    // p1's words embed only once p2's are being read: without the read-ahead this would time out.
    private fun gatedOnP2(p2Read: CompletableDeferred<Unit>, onApple: () -> Unit = {}) = OnGpu(FakeEmbedder()) { parts ->
        if (parts.any { it is Part.Text && "apple" in it.text }) {
            withTimeout(5.seconds) { p2Read.await() }
            onApple()
        }
    }

    @Test
    fun photosWordsAreReadWhileTheOnesBeforeEmbedAndLandOnTheirOwnPhoto(): Unit = runBlocking {
        val store = newStore().apply { upsertFiles(photos) }
        val p2Read = CompletableDeferred<Unit>()
        val extractor = Photos { if (it.name == "p2.jpg") p2Read.complete(Unit) }

        IndexEngine(store, extractor, gatedOnP2(p2Read), FakeMonitor(), settingsOf(), readAheadDepth = { 1 }).runBatch(1.minutes)

        assertEquals(fruit.keys.toList(), extractor.words, "newest first, each once")
        assertEquals(0L, store.wordsChunkCount())
        for ((name, word) in fruit) {
            assertEquals(listOf(name), SearchEngine(store, FakeEmbedder()).search(word, SearchEngine.Mode.KEYWORD).hits.map { it.file.name })
        }
    }

    @Test
    fun aStopGivesTheReadAheadWordsTheirAttemptBack(): Unit = runBlocking {
        val store = newStore().apply { upsertFiles(photos) }
        IndexEngine(store, Photos(), OnGpu(FakeEmbedder()), FakeMonitor(), settingsOf(), wordsPage = { emptyList() }).runBatch(1.minutes)
        val p2Read = CompletableDeferred<Unit>()
        var appleEmbedded = false
        val engine = IndexEngine(
            store,
            Photos { if (it.name == "p2.jpg") p2Read.complete(Unit) },
            gatedOnP2(p2Read) { appleEmbedded = true },
            FakeMonitor(),
            settingsOf(),
            policyStop = { StopReason.MANUAL.takeIf { appleEmbedded } },
            readAheadDepth = { 1 },
        )

        assertEquals(StopReason.MANUAL, engine.runBatch(1.minutes).stopReason)
        val left = store.wordsChunks(64)
        assertEquals(listOf("p2.jpg", "p3.jpg"), left.map { it.fileName })
        assertEquals(listOf(0, 0), left.map { it.attempts }, "p2 was read ahead, then the stop gave its attempt back")
    }

    @Test
    fun onCpuOrWhileShownNothingIsReadAhead() = runTest {
        for ((backend, allowed) in listOf(Backend.CPU to true, Backend.GPU to false)) {
            val store = newStore().apply { upsertFiles(files) }
            var bAttemptsWhileAEmbeds: Int? = null
            val embedder = OnGpu(FakeEmbedder(), backend) { parts ->
                if ("a.txt" in (parts.single() as Part.Text).text) bAttemptsWhileAEmbeds = store.fileByUri(files[1].uri)!!.attempts
            }
            IndexEngine(store, FakeExtractor(pieces), embedder, FakeMonitor(), settingsOf(), readAheadDepth = { if (allowed) 1 else 0 })
                .runBatch(1.minutes, requireCharging = false)
            assertEquals(3L, store.vectorCount().toLong())
            assertEquals(0, bAttemptsWhileAEmbeds, "$backend, read ahead allowed $allowed: b untouched while a embeds")
        }
    }

    @Test fun readAheadRechecksInteractionAfterExtractionInsteadOfUsingTheFilesOldState() = runTest {
        val store = newStore().apply { upsertFiles(files) }
        val monitor = FakeMonitor()
        val extractor = FakeExtractor(pieces) { monitor.state = monitor.state.copy(screenOn = true) }
        var bAttemptsWhileAEmbeds: Int? = null
        val embedder = OnGpu(FakeEmbedder()) { parts ->
            if ("a.txt" in (parts.single() as Part.Text).text) bAttemptsWhileAEmbeds = store.fileByUri(files[1].uri)!!.attempts
        }
        val result = IndexEngine(
            store,
            extractor,
            embedder,
            monitor,
            settingsOf(),
            readAheadDepth = { IndexEngine.readAheadDepth(it, visible = false) },
        ).runBatch(1.minutes)
        assertEquals(BatchResult(3, 0, StopReason.DONE), result, "interaction lowers intensity but work continues")
        assertEquals(0, bAttemptsWhileAEmbeds, "no extra extraction was launched using stale idle state")
    }

    @Test
    fun withoutTheModelVideosAreFoundByNameAndReadWhenItComes() = runTest {
        val store = newStore(sig = null)
        val clip = testFile("clip.mp4", Kind.VIDEO)
        store.upsertFiles(listOf(clip, testFile("a.txt")))
        val frame = Piece("0:02", listOf(Part.Image(ByteArray(8))), null)
        val extractor = FakeExtractor(mapOf("clip.mp4" to listOf(frame), "a.txt" to listOf(textPiece("hello"))))

        IndexEngine(store, extractor, null, FakeMonitor(), settingsOf()).runBatch(1.minutes, requireCharging = false)
        assertEquals(listOf("a.txt"), extractor.names, "no frames are decoded without the model")
        assertEquals(FileState.INDEXED, store.fileByUri(clip.uri)!!.state)
        assertEquals(1L, store.countsNow().progress.nameOnly, "found by name, as before")

        store.resetForModel(SIG)
        assertEquals(FileState.PENDING, store.fileByUri(clip.uri)!!.state, "the model's arrival queues the video for its frames")
        assertEquals(1L, store.countsNow().progress.scheduled, "it shows as not processed yet, as a requeued kind does")
        assertEquals(FileState.INDEXED, store.fileByUri(testFile("a.txt").uri)!!.state, "text already read stays read")

        val embedder = FakeEmbedder()
        IndexEngine(store, extractor, embedder, FakeMonitor(), settingsOf()).runBatch(1.minutes, requireCharging = false, deepen = true)
        assertEquals(listOf("a.txt", "clip.mp4"), extractor.names)
        assertEquals(FileState.INDEXED, store.fileByUri(clip.uri)!!.state)
        assertNull(store.pending(10).firstOrNull())

        store.resetForModel(Signature("OTHER"))
        assertEquals(FileState.INDEXED, store.fileByUri(clip.uri)!!.state, "a video with frames keeps them; only its vectors are redone")
    }
}
