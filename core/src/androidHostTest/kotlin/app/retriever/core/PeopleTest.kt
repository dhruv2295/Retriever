package app.retriever.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** People end to end: the faces pass alongside reading, a name, a search by it, and turning it off. */
class PeopleTest {
    private fun face(i: Int) = FloatArray(FACE_DIMS).also {
        it[i] = 12f
        it[i + 1] = 2.4f
    }

    @Test
    fun aNamedPersonIsSearchableUntilPeopleIsTurnedOff() = runBlocking {
        val photos = (1..6).map { testFile("p$it.jpg", Kind.PHOTO, modifiedAt = it.toLong(), takenAt = it * 1000L) }
        val who = mapOf("p1.jpg" to 0, "p2.jpg" to 0, "p3.jpg" to 0, "p4.jpg" to 9, "p5.jpg" to 9)
        val reader = object : FaceReader {
            override suspend fun faces(uri: String): List<FoundFace> =
                listOfNotNull(who[uri.substringAfterLast('/')]?.let { FoundFace(FaceBox(0f, 0f, 0.5f, 0.5f), face(it)) })
        }
        val store = newStore()
        val r = testRetriever(store, photos, FakeExtractor(), faceReader = reader)
        store.setProfile(PROFILE)
        r.uiVisible = true
        r.updatePrefs { it.copy(recognizePeople = true) }
        assertEquals(NextRun.SOON, r.indexBatch(1.minutes), "the photos are read first, and faces follow at once")
        assertEquals(NextRun.DONE, r.indexBatch(1.minutes))
        assertEquals(FacesProgress(6, 0), r.facesProgress())

        val asha = r.peopleNow().single() // the second person is in 2 photos, below the minimum
        assertEquals(3, asha.photos)
        r.renamePerson(asha.id, "Asha")
        val found = r.search("asha", null, 10)
        assertEquals(listOf("Asha"), found.parsed?.people)
        assertEquals(listOf("p3.jpg", "p2.jpg", "p1.jpg"), found.hits.map { it.file.name })
        assertEquals(listOf("p3.jpg", "p2.jpg", "p1.jpg"), r.personPhotos(asha.id, 10, 0).map { it.file.name })

        r.updatePrefs { it.copy(recognizePeople = false) }
        assertEquals(emptyList(), r.peopleNow())
        assertEquals(emptyList(), store.people(1), "turning People off forgets every face and name")
        assertEquals(emptyList(), r.search("asha", null, 10).parsed?.people.orEmpty())
    }

    /** The model matches a scene, not who is in it: a search by a photo of someone known leads with their photos. */
    @Test
    fun aPhotoOfAKnownPersonFindsTheirPhotosFirst() = runBlocking {
        val query = ByteArray(33)
        fun shot(vararg sizes: Int) = listOf(Piece("", sizes.map { Part.Image(ByteArray(it)) }, null))
        val photos = listOf("beach.jpg", "asha1.jpg", "asha2.jpg").map { testFile(it, Kind.PHOTO) }
        // The beach is the query's scene exactly; Asha's photos are less and less like it.
        val extractor = FakeExtractor(mapOf("beach.jpg" to shot(33), "asha1.jpg" to shot(33, 40), "asha2.jpg" to shot(33, 40, 40)))
        val reader = object : FaceReader {
            override suspend fun faces(uri: String): List<FoundFace> =
                if (uri.endsWith("beach.jpg")) emptyList() else listOf(FoundFace(FaceBox(0f, 0f, 0.5f, 0.5f), face(0)))
        }
        val store = newStore()
        val r = testRetriever(store, photos, extractor, faceReader = reader)
        store.setProfile(PROFILE)
        r.uiVisible = true
        r.updatePrefs { it.copy(recognizePeople = true) }
        r.indexBatch(1.minutes)
        r.indexBatch(1.minutes)
        r.warmSearch()

        assertEquals(listOf("beach.jpg", "asha1.jpg", "asha2.jpg"), r.searchImage(query).hits.map { it.file.name })
        val byFace = r.searchImage(query, uri = "content://picked/asha.jpg")
        assertEquals(listOf("asha1.jpg", "asha2.jpg", "beach.jpg"), byFace.hits.map { it.file.name })
    }

    /**
     * Play delivers People's model when People is turned on. A faces pass without it looks at no photo, so none uses up
     * an attempt, and the next pass runs once the model is there.
     */
    @Test
    fun facesWaitForPeoplesModel() = runBlocking {
        val photos = (1..3).map { testFile("p$it.jpg", Kind.PHOTO, modifiedAt = it.toLong()) }
        var model = PeopleModel.WAITING_FOR_WIFI
        var looked = 0
        val reader = object : FaceReader {
            override suspend fun faces(uri: String): List<FoundFace> = emptyList<FoundFace>().also { looked++ }

            override suspend fun ready(mobileData: Boolean) = model
        }
        val store = newStore()
        val r = testRetriever(store, photos, FakeExtractor(), faceReader = reader)
        store.setProfile(PROFILE)
        r.uiVisible = true
        r.updatePrefs { it.copy(recognizePeople = true) }
        assertEquals(NextRun.SOON, r.indexBatch(1.minutes), "the photos are read first")
        r.indexBatch(1.minutes)
        assertEquals(0, looked)
        assertEquals(FacesProgress(0, 3), r.facesProgress(), "no photo used up an attempt")
        assertEquals(PeopleModel.WAITING_FOR_WIFI, r.state.value.people)

        model = PeopleModel.READY
        assertEquals(NextRun.DONE, r.indexBatch(1.minutes))
        assertEquals(FacesProgress(3, 0), r.facesProgress())
        assertEquals(null, r.state.value.people)
    }

    /**
     * Files go first: faces wait until every file is read and has search by description. Turned on mid-read on a Pixel 11,
     * faces first held 10,000 files and the search by description behind 23,413 photos for hours.
     */
    @Test
    fun filesGoBeforeFaces() = runBlocking {
        val photos = (1..12).map { testFile("p$it.jpg", Kind.PHOTO, modifiedAt = it.toLong()) }
        var retriever: Retriever? = null
        val counts = mutableListOf<FacesProgress?>()
        val shown = mutableListOf<String?>()
        val slow = object : FaceReader {
            override suspend fun faces(uri: String): List<FoundFace> {
                counts += retriever?.state?.value?.faces
                shown += retriever?.state?.value?.currentFile?.uri
                delay(100.milliseconds)
                return emptyList()
            }
        }
        val store = newStore()
        val r = testRetriever(store, photos, FakeExtractor(), faceReader = slow)
        retriever = r
        store.setProfile(PROFILE)
        r.uiVisible = true
        r.updatePrefs { it.copy(recognizePeople = true) }

        assertEquals(NextRun.SOON, r.indexBatch(1.minutes), "read, with faces left: the next batch looks at once")
        assertEquals(0L, store.pendingByKind().values.sum(), "every file is read first")
        assertEquals(FacesProgress(0, 12), r.facesProgress(), "no photo is looked at while files wait")
        // A photo read before the model came still waits for search by description; it goes before faces too.
        store.replaceChunks(store.fileByUri(photos[0].uri)!!.id, listOf(Store.NewChunk(1, "", "sand and sea", null)))
        assertEquals(1L, store.countsNow().progress.meaningWaiting)
        r.indexBatch(1.minutes)
        assertEquals(0L, store.countsNow().progress.meaningWaiting)
        assertEquals(FacesProgress(0, 12), r.facesProgress(), "no photo is looked at while one waits for search by description")

        r.indexBatch(500.milliseconds)
        val looked = r.facesProgress().done
        assertTrue(looked in 1L..11L, "a short batch looks at some photos, not all: $looked")
        assertEquals(listOf(FacesProgress(0, 12), FacesProgress(1, 11)), counts.take(2), "the status line counts photos as they're looked at")
        // Processing shows the photo being looked at, not "Getting started…" for the whole pass.
        assertTrue(shown.isNotEmpty() && shown.all { uri -> photos.any { it.uri == uri } }, "the current file is each photo looked at: $shown")
        assertEquals(null, r.state.value.faces, "and leaves the faces count once the batch ends")

        r.indexBatch(1.minutes)
        assertEquals(FacesProgress(12, 0), r.facesProgress())
    }
}
