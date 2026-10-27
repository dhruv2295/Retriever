package app.retriever.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** People end to end: the faces pass alongside reading, a name, a search by it, and turning it off. */
class PeopleTest {
    private fun face(i: Int) = FloatArray(FACE_DIMS).also {
        it[i] = 1f
        it[i + 1] = 0.2f
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
        assertEquals(NextRun.DONE, r.indexBatch(1.minutes), "faces are looked at while the photos still wait to be read")
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

    /** While files wait to be read faces get a share of the batch, and reading carries on when it runs out. */
    @Test
    fun facesTakeAShareWhileFilesWaitToBeRead() = runBlocking {
        val photos = (1..12).map { testFile("p$it.jpg", Kind.PHOTO, modifiedAt = it.toLong()) }
        val slow = object : FaceReader {
            override suspend fun faces(uri: String): List<FoundFace> {
                delay(100.milliseconds)
                return emptyList()
            }
        }
        val store = newStore()
        val r = testRetriever(store, photos, FakeExtractor(), faceReader = slow)
        store.setProfile(PROFILE)
        r.uiVisible = true
        r.updatePrefs { it.copy(recognizePeople = true) }

        r.indexBatch(2.seconds)

        val looked = r.facesProgress().done
        assertTrue(looked in 1L..11L, "a quarter of the batch looks at some photos, not all: $looked")
        assertEquals(0L, store.pendingByKind().values.sum(), "the rest of the batch reads every file")
    }
}
