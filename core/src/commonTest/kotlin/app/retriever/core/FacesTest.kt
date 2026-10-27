package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class FacesTest {
    /** Unit vector [i], tilted towards [j] by [w]: faces of one person share [i]. */
    private fun face(i: Int, j: Int = (i + 1) % FACE_DIMS, w: Float = 0.2f) = FloatArray(FACE_DIMS).also {
        it[i] = 1f
        it[j] += w
    }

    private val box = FaceBox(0.1f, 0.1f, 0.3f, 0.4f)

    private fun storeWithPhotos(n: Int): Pair<Store, List<Long>> {
        val store = Store(testDriver())
        store.upsertFiles((1..n).map { SourceFile("photo://$it", "$it.jpg", "Camera", "image/jpeg", Kind.PHOTO, 10, it.toLong(), takenAt = it * 1000L) })
        return store to (1..n).map { store.fileByUri("photo://$it")?.id ?: -1L }
    }

    @Test
    fun theTemplateAlignsToItself() {
        val t = FaceAlign.transform(FaceAlign.POINTS) ?: error("no transform")
        listOf(1f, 0f, 0f, 0f).zip(t.toList()).forEach { (want, got) -> assertTrue(abs(want - got) < 1e-3f, t.toList().toString()) }
    }

    @Test
    fun aScaledTurnedMovedFaceIsBroughtBack() {
        val angle = 0.4
        val p = FaceAlign.POINTS
        val moved = FloatArray(p.size) { k ->
            val x = p[k - k % 2].toDouble()
            val y = p[k - k % 2 + 1].toDouble()
            (if (k % 2 == 0) 3 * (x * cos(angle) - y * sin(angle)) + 500 else 3 * (x * sin(angle) + y * cos(angle)) + 200).toFloat()
        }
        val (a, b, tx, ty) = FaceAlign.transform(moved)?.toList() ?: error("no transform")
        for (i in 0 until 5) {
            val x = moved[2 * i]
            val y = moved[2 * i + 1]
            assertTrue(abs(a * x - b * y + tx - p[2 * i]) < 0.01f)
            assertTrue(abs(b * x + a * y + ty - p[2 * i + 1]) < 0.01f)
        }
        assertNull(FaceAlign.transform(FloatArray(10) { 7f }))
    }

    @Test
    fun groupingPairsJoinsAndLeavesOlderFacesAlone() {
        var next = 100L
        fun row(id: Long, v: FloatArray, person: Long? = null) = FaceRow(id, person, normalized(v))
        val rows = listOf(
            row(1, face(0)),
            row(2, face(5)),
            row(3, face(0, w = 0.3f)),
            row(4, face(0, w = 0.1f)),
            row(5, face(9)),
        )
        val first = groupFaces(rows, 0) { next++ }
        assertEquals(mapOf(1L to 100L, 3L to 100L, 4L to 100L), first, "the 2nd of a person pairs with the 1st, the 3rd joins")
        assertEquals(101L, next)

        // Face 2 was taken out of person 100 ("Not this person"): it is not placed again, and a new face of 100 joins 100.
        val later = listOf(row(1, face(0), 100), row(2, face(0)), row(6, face(0, w = 0.25f)), row(7, face(5)))
        assertEquals(mapOf(6L to 100L), groupFaces(later, 5) { next++ })
    }

    @Test
    fun aNameInTheQueryIsTakenOutLongestFirst() {
        val named = listOf(1L to "Asha", 2L to "Asha Rao", 3L to "Dev")
        assertEquals("photos of beach" to listOf(2L to "Asha Rao"), namedIn("photos of Asha Rao beach", named))
        assertEquals("with" to listOf(1L to "Asha", 3L to "Dev"), namedIn("asha, with dev", named))
        assertEquals("ashanti" to emptyList(), namedIn("ashanti", named))
    }

    @Test
    fun missingNamesThePeople() {
        assertEquals("No photos of Asha", ParsedQuery("", setOf(Kind.PHOTO), understood = listOf("Photos", "Asha"), people = listOf("Asha")).missing())
        val today = ParsedQuery("", understood = listOf("today", "Asha"), dateLabel = "today", people = listOf("Asha"))
        assertEquals("Nothing of Asha from today", today.missing())
    }

    @Test
    fun peopleAreGroupedNamedCorrectedAndForgotten() {
        val (store, ids) = storeWithPhotos(5)
        ids.take(3).forEach { store.saveFaces(it, listOf(FoundFace(box, face(0)))) }
        store.saveFaces(ids[3], listOf(FoundFace(box, face(7))))
        store.saveFaces(ids[4], emptyList())
        store.groupNewFaces()
        assertEquals(FacesProgress(5, 0), store.facesProgress())
        val asha = store.people().single()
        assertEquals(3, asha.photos)
        assertNull(asha.name)
        assertTrue(asha.cover?.fileId in ids.take(3))
        assertEquals(ids.take(3).reversed(), store.personFileIds(asha.id), "newest first")

        store.renamePerson(asha.id, "  Asha ")
        assertEquals(listOf(asha.id to "Asha"), store.namedPersons())

        store.notThisPerson(asha.id, ids[2])
        store.groupNewFaces()
        assertEquals(ids.take(2).reversed(), store.personFileIds(asha.id), "a face taken out is not grouped back")
        assertEquals(2, store.people().single().photos, "a named person shows below the photo minimum")

        // A changed photo is looked at again; a removed one takes its faces with it.
        store.upsertFiles(listOf(SourceFile("photo://1", "1.jpg", "Camera", "image/jpeg", Kind.PHOTO, 11, 99, takenAt = 1000L)))
        store.removeFiles(listOf("photo://2"))
        assertEquals(emptyList(), store.personFileIds(asha.id))
        assertEquals(FacesProgress(3, 1), store.facesProgress())

        store.renamePerson(asha.id, " ")
        assertEquals(emptyList(), store.namedPersons())
        store.forgetAllFaces()
        assertEquals(emptyList(), store.people(1))
        assertEquals(FacesProgress(0, 4), store.facesProgress())
    }

    @Test
    fun mergingMovesFacesAndKeepsATakenOutFaceOut() {
        val (store, ids) = storeWithPhotos(5)
        ids.take(2).forEach { store.saveFaces(it, listOf(FoundFace(box, face(0)))) }
        ids.drop(2).forEach { store.saveFaces(it, listOf(FoundFace(box, face(9)))) }
        store.groupNewFaces()
        val (big, small) = store.people(1)
        store.notThisPerson(small.id, ids[1])
        store.renamePerson(small.id, "Asha")
        store.mergePeople(into = big.id, from = small.id)
        assertEquals(listOf(4), store.people(1).map { it.photos })
        assertEquals(listOf("Asha"), store.people(1).map { it.name }, "the kept person takes the name they lacked")
        store.groupNewFaces()
        assertEquals(4, store.people(1).single().photos, "the face taken out of the merged person stays out")
    }

    @Test
    fun thePassReadsEachPhotoGroupsAndGivesUpOnOneThatFails() = runTest {
        val (store, ids) = storeWithPhotos(4)
        val reads = mutableListOf<String>()
        val reader = object : FaceReader {
            override suspend fun faces(uri: String): List<FoundFace> {
                reads += uri
                if (uri == "photo://4") throw IllegalStateException("decoder")
                return listOf(FoundFace(box, face(3)))
            }
        }
        var grouped = 0
        val pass = FacePass(store, reader, { _, _ -> }) { grouped++ }
        assertEquals(StopReason.MANUAL, pass.run(1.minutes, { StopReason.MANUAL }, {}))
        assertTrue(reads.isEmpty())

        assertEquals(StopReason.DONE, pass.run(1.minutes, { null }, {}))
        assertEquals(MAX_FACE_ATTEMPTS, reads.count { it == "photo://4" })
        assertEquals(FacesProgress(3, 0), store.facesProgress())
        assertEquals(1, grouped)
        assertEquals(ids.take(3).reversed(), store.personFileIds(store.people().single().id))
    }

    @Test
    fun aPassOverPhotosWithNoFaceStillSaysSo() = runTest {
        val (store, _) = storeWithPhotos(2)
        val none = object : FaceReader {
            override suspend fun faces(uri: String): List<FoundFace> = emptyList()
        }
        var told = 0
        assertEquals(StopReason.DONE, FacePass(store, none, { _, _ -> }) { told++ }.run(1.minutes, { null }, {}))
        assertEquals(1, told, "Home's count of photos to go reads again")
        assertEquals(FacesProgress(2, 0), store.facesProgress())
    }

    private fun personFace(fileId: Long, v: FloatArray, box: FaceBox = this.box) = PersonFace(FaceCrop(fileId, "photo://$fileId", box), normalized(v))

    @Test
    fun theCoverIsALargerWholeFaceMostLikeThePerson() {
        val typical = personFace(1, face(3))
        val odd = personFace(2, face(3, w = 0.9f), FaceBox(0.1f, 0.1f, 0.5f, 0.6f))
        val cutOff = personFace(3, face(3), FaceBox(0f, 0.2f, 0.4f, 0.7f))
        val small = personFace(4, face(3), FaceBox(0.1f, 0.1f, 0.15f, 0.15f))
        assertEquals(typical.crop, coverOf(listOf(odd, cutOff, small, typical, personFace(5, face(3)))))
        assertEquals(cutOff.crop, coverOf(listOf(cutOff)), "a face cut off is still better than none")
        assertNull(coverOf(emptyList()))
    }

    @Test
    fun homeAsksAboutTwoPeopleWhoMayBeOne() {
        fun person(id: Long, photos: Int, name: String? = null) = Person(id, name, photos, null)
        val asha = person(1, 3)
        val ashaBehindAPhone = person(2, 4)
        val ravi = person(3, 3)
        val faces = mapOf(
            1L to (1L..3L).map { personFace(it, face(3)) },
            2L to (4L..7L).map { personFace(it, face(3, j = 10, w = 0.5f)) },
            3L to (8L..10L).map { personFace(it, face(20)) },
        )
        val people = listOf(asha, ashaBehindAPhone, ravi)
        assertEquals(SamePerson(ashaBehindAPhone, asha), samePersonQuestion(people, faces, emptySet()), "the one in more photos first")
        assertNull(samePersonQuestion(people, faces, setOf(1L to 2L)), "a pair answered no is never asked again")
        assertNull(samePersonQuestion(listOf(asha.copy(name = "Asha"), ashaBehindAPhone.copy(name = "Meera"), ravi), faces, emptySet()))
        val together = faces + (2L to faces.getValue(2L) + personFace(1, face(3, j = 10, w = 0.5f)))
        assertNull(samePersonQuestion(people, together, emptySet()), "two people in one photo are two people")
    }

    @Test
    fun answeringNoIsKeptAndForgottenWithPeople() {
        val (store, _) = storeWithPhotos(1)
        store.declineSamePerson(5, 2)
        store.declineSamePerson(3, 4)
        assertEquals(setOf(2L to 5L, 3L to 4L), store.declinedPairs())
        store.forgetAllFaces()
        assertEquals(emptySet(), store.declinedPairs())
    }

    @Test
    fun aFullDiskStopsThePassWithoutSpendingATry() = runTest {
        val (store, _) = storeWithPhotos(1)
        val full = object : FaceReader {
            override suspend fun faces(uri: String): List<FoundFace> = throw IllegalStateException("database or disk is full")
        }
        assertEquals(StopReason.DISK_FULL, FacePass(store, full, { _, _ -> }) {}.run(1.minutes, { null }, {}))
        assertEquals(0, store.pendingFaces(1).single().attempts)
    }
}
