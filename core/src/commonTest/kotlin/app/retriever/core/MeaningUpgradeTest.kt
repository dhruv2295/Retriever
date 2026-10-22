package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class MeaningUpgradeTest {
    private val profile = DeviceProfile(Tier.LIGHT, ModelVariant.M440, Backend.CPU, 70, 5, false, false)
    private val monitor = object : DeviceMonitor {
        override fun state() = DeviceState(8.0, true, 90, Thermal.NONE)
    }

    private fun file(kind: Kind) = SourceFile("file:///$kind", "item.$kind", "Library", "", kind, 100, 1)

    @Test fun modelArrivalAndReplacementKeepWordsLabelsAndReadStateWithoutRepeatingOcr() = runTest {
        val store = Store(testDriver())
        val files = listOf(file(Kind.PHOTO), file(Kind.SCREENSHOT), file(Kind.PDF), file(Kind.DOC), file(Kind.AUDIO))
        var extractions = 0
        var visualReads = 0
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
                extractions++
                return if (file.kind == Kind.DOC) {
                    listOf(Piece("", listOf(Part.Text("lease deposit")), "lease deposit", "lease deposit"))
                } else {
                    imageWithText(if (file.kind == Kind.PDF) "p. 1" else "", byteArrayOf(1), OCR, 800, "beach")
                }
            }

            override suspend fun visual(file: SourceFile, locator: String): Part.Image {
                visualReads++
                if (file.kind == Kind.PDF) assertEquals("p. 1", locator)
                return Part.Image(byteArrayOf(1))
            }
        }
        fun engine(embedder: Embedder?) = IndexEngine(store, extractor, embedder, monitor, { IndexSettings(profile, Kind.entries.toSet()) })
        store.upsertFiles(files)
        engine(null).runBatch(1.minutes, false)
        val ids = files.map { store.fileByUri(it.uri)!!.id }
        val before = ids.associateWith { store.chunkTexts(it).map { chunk -> chunk.text } }
        assertEquals(4, extractions, "audio is name-only even if requested")
        assertEquals(0, store.vectorCount())
        assertTrue(store.meaningStates(ids).values.all { it == MeaningState.NOT_ADDED })
        val search = SearchEngine(store, NoModel)
        assertEquals(3, search.search("48213", SearchEngine.Mode.KEYWORD).hits.size)
        val labelHits = search.search("beach", SearchEngine.Mode.KEYWORD).hits
        assertEquals(3, labelHits.size)
        assertTrue(labelHits.all { it.matchReason == "Matches: beach" })
        assertTrue(labelHits.none { it.snippet.orEmpty().contains("beach") })
        assertNull(search.search("lease", SearchEngine.Mode.KEYWORD).hits.single().matchReason)
        val chunkCount = store.chunkCount()
        for (generation in listOf("first", "replacement")) {
            val signature = Signature(generation)
            store.resetForModel(signature)
            assertEquals(chunkCount, store.chunkCount())
            assertTrue(store.pending(20).isEmpty())
            assertTrue(store.readExtents(ids).isEmpty(), "waiting for meaning is a count, never a Partly read file")
            assertEquals(ids.size.toLong(), store.countsNow().tailFiles)
            assertEquals(3, search.search("48213", SearchEngine.Mode.KEYWORD).hits.size)
            val embeddedText = mutableListOf<String>()
            val embedder = object : Embedder {
                override val signature = signature
                override val backend = Backend.CPU
                override suspend fun embedRaw(parts: List<Part>): FloatArray {
                    embeddedText += parts.filterIsInstance<Part.Text>().map { it.text }
                    return FloatArray(DIMS) { 1f }
                }
                override fun close() = Unit
            }
            engine(embedder).runBatch(1.minutes, false, deepen = true)
            assertEquals(chunkCount.toInt(), store.vectorCount())
            assertTrue(embeddedText.any { OCR in it })
            assertTrue(embeddedText.none { "beach" in it }, "labels are keyword metadata, not document text")
            assertEquals(before, ids.associateWith { store.chunkTexts(it).map { chunk -> chunk.text } })
            assertEquals(4, extractions, "neither OCR nor document extraction runs on model changes")
            assertTrue(store.meaningStates(ids).values.all { it == MeaningState.ADDED })
            assertEquals(ids.size.toLong(), store.countsNow().meaning.added)
            assertEquals(0L, store.countsNow().tailFiles)
        }
        assertEquals(6, visualReads, "only the three stored image locations are reopened per generation")

        val ones = object : Embedder {
            override val signature = Signature("replacement")
            override val backend = Backend.CPU
            override suspend fun embedRaw(parts: List<Part>) = FloatArray(DIMS) { 1f }
            override fun close() = Unit
        }
        val related = SearchEngine(store, ones).search("sunset").hits
        assertTrue(related.isNotEmpty() && related.all { it.matchReason == Words.RELATED }, "found by meaning alone: $related")
    }

    @Test fun aWarmSearchDropsOldVectorsAsSoonAsTheSignatureChanges() = runTest {
        val store = Store(testDriver())
        val source = file(Kind.DOC)
        store.upsertFiles(listOf(source))
        val id = store.pending(1).single().id
        fun embedder(name: String) = object : Embedder {
            override val signature = Signature(name)
            override val backend = Backend.CPU
            override suspend fun embedRaw(parts: List<Part>) = FloatArray(DIMS) { 1f }
            override fun close() = Unit
        }
        val old = embedder("old")
        store.resetForModel(old.signature)
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", "receipt", old.embed(listOf(Part.Text("receipt"))))))
        val search = SearchEngine(store, old)
        assertEquals(1, search.search("receipt", SearchEngine.Mode.VECTOR).hits.size)
        val next = embedder("next")
        store.resetForModel(next.signature)
        search.embedder = next
        assertTrue(search.search("receipt", SearchEngine.Mode.VECTOR).hits.isEmpty())
        assertEquals(1, search.search("receipt", SearchEngine.Mode.KEYWORD).hits.size)
    }

    @Test fun labelReasonSurvivesWhenAnotherChunkWinsTheMeaningLane() = runTest {
        val store = Store(testDriver())
        val photo = file(Kind.PHOTO)
        val other = file(Kind.SCREENSHOT)
        store.upsertFiles(listOf(photo, other))
        val id = store.fileByUri(photo.uri)!!.id
        val otherId = store.fileByUri(other.uri)!!.id
        val signature = Signature("labels")
        store.resetForModel(signature)
        fun vector(at: Int) = Vector.of(FloatArray(DIMS) { if (it == at) 1f else 0f }, signature)
        store.replaceChunks(
            id,
            listOf(
                Store.NewChunk(1, "", null, vector(1), null, "beach", true),
                Store.NewChunk(2, "", "receipt", vector(0)),
            ),
        )
        store.replaceChunks(otherId, listOf(Store.NewChunk(1, "", null, vector(1), null, "beach", true)))
        val e = object : Embedder {
            override val signature = signature
            override val backend = Backend.CPU
            override suspend fun embedRaw(parts: List<Part>) = FloatArray(DIMS) { if (it == 0) 1f else 0f }
            override fun close() = Unit
        }
        val hit = SearchEngine(store, e).search("beach").hits.single { it.fileId == id }
        assertEquals("receipt", hit.snippet)
        assertEquals("Matches: beach", hit.matchReason)
    }

    @Test fun anEmptyPhotoIsNameOnlyUntilItHasAVisionVector() = runTest {
        val store = Store(testDriver())
        val photo = file(Kind.PHOTO)
        store.upsertFiles(listOf(photo))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", null, null, null, "", true)))
        assertEquals(1L, store.nameOnlyCount(), "an empty placeholder is not searchable content")
        val signature = Signature("vision")
        store.resetForModel(signature)
        store.setChunkVector(store.tailChunks(1).single().id, Vector.of(FloatArray(DIMS) { 1f }, signature))
        assertEquals(0L, store.nameOnlyCount())
    }

    @Test fun aFileWaitingForRereadingIsNotAlsoCountedAsPartlyRead() {
        val store = Store(testDriver())
        val source = file(Kind.DOC)
        store.upsertFiles(listOf(source))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", "receipt", null)))
        store.requeue(setOf(Kind.DOC))
        store.resetForModel(Signature("new"))
        assertEquals(0L, store.countsNow().tailFiles)
        assertEquals(0L, store.tailChunkCount())
        assertTrue(store.tailChunks(10).isEmpty())
        assertTrue(store.readExtents(listOf(id)).isEmpty())
        assertEquals(MeaningState.UNREAD, store.meaningStates(listOf(id))[id])
    }

    @Test fun failedMeaningLeavesContentSearchableAndDoesNotClaimCompletion() = runTest {
        val store = Store(testDriver())
        val source = file(Kind.PHOTO)
        store.upsertFiles(listOf(source))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", null, null, null, "beach", visual = true)))
        val sig = Signature("first")
        store.resetForModel(sig)
        val e = object : Embedder {
            override val signature = sig
            override val backend = Backend.CPU
            override suspend fun embedRaw(parts: List<Part>): FloatArray = error("must not embed an absent image")
            override fun close() = Unit
        }
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> = error("must not repeat OCR")
        }
        IndexEngine(store, extractor, e, monitor, { IndexSettings(profile, setOf(Kind.PHOTO)) }).runBatch(1.minutes, false, deepen = true)
        assertEquals(0L, store.tailChunkCount(), "three attempts bound unavailable pixels")
        assertEquals(MeaningState.NOT_ADDED, store.meaningStates(listOf(id))[id])
        assertEquals(FileState.INDEXED, store.fileByUri(source.uri)?.state)
        val hit = SearchEngine(store, NoModel).search("beach", SearchEngine.Mode.KEYWORD).hits.single()
        assertEquals("Matches: beach", hit.matchReason)
        assertNull(hit.snippet)
        store.removeFiles(listOf(source.uri))
        assertTrue(store.keywordSearch("beach", 10).isEmpty())
        assertFalse(store.meaningStates(listOf(id)).containsKey(id))
    }

    private companion object {
        // Long enough to be its own text chunk; a shorter scrap stays on the image's chunk, keyword-only.
        const val OCR = "receipt 48213 for the electricity bill paid in September"
    }
}
