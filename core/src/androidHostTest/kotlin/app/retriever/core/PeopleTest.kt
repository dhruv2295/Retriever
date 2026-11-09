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

    /**
     * Files go first: faces wait until every file is read and has search by description. Turned on mid-read on a Pixel 11,
     * faces first held 10,000 files and the search by description behind 23,413 photos for hours.
     */
    @Test
    fun filesGoBeforeFaces() = runBlocking {
        val photos = (1..12).map { testFile("p$it.jpg", Kind.PHOTO, modifiedAt = it.toLong()) }
        var retriever: Retriever? = null
        val counts = mutableListOf<FacesProgress?>()
        val slow = object : FaceReader {
            override suspend fun faces(uri: String): List<FoundFace> {
                counts += retriever?.state?.value?.faces
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

        r.indexBatch(500.milliseconds)
        val looked = r.facesProgress().done
        assertTrue(looked in 1L..11L, "a short batch looks at some photos, not all: $looked")
        assertEquals(listOf(FacesProgress(0, 12), FacesProgress(1, 11)), counts.take(2), "the status line counts photos as they're looked at")
        assertEquals(null, r.state.value.faces, "and leaves the faces count once the batch ends")

        r.indexBatch(1.minutes)
        assertEquals(FacesProgress(12, 0), r.facesProgress())
    }
}
