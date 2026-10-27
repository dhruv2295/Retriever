package app.retriever.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.TimeSource

// People (docs/features/people.md): the platform finds each face and turns it into SFace's fingerprint; core keeps them,
// groups them into people, and finds a named person's photos. Only while the person has it on (Prefs.recognizePeople).

/** SFace's fingerprint length. */
const val FACE_DIMS = 128

/**
 * Two fingerprints at or above this cosine are taken to be one person. OpenCV's own SFace threshold (0.363, tuned on
 * LFW's celebrities) put strangers in one person on a real 3,500-photo library; at 0.5 faces sharing a photo stopped
 * joining one person except in collages, where they are one person (docs/features/people.md).
 */
const val SAME_PERSON = 0.5f

/**
 * Two people whose faces' means are at least this close may be one person, so Home asks. Measured on LFW with SFace:
 * one person split in two scored 0.70 or more in 99% of splits, strangers at most 0.59, and brothers, sisters and father
 * and son at most 0.63. A real library's splits (selfies behind a phone) scored 0.78 and 0.81.
 */
const val ASK_SAME_PERSON = 0.65f

/** An unnamed person shows on Home once they are in this many photos; a named one shows from their first. */
const val PERSON_MIN_PHOTOS = 3

/** A face's place in its photo, as fractions of the upright photo's width and height. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A face the platform found: where it is, and its SFace fingerprint (any length; normalised here). */
class FoundFace(val box: FaceBox, val vector: FloatArray)

/** Finds the faces in a photo; the platform's face detector and SFace. */
interface FaceReader {
    /** Every face clear enough to know again in the photo at [uri]; empty when it has none, null when it can't be opened. */
    @Throws(Throwable::class)
    suspend fun faces(uri: String): List<FoundFace>?
}

/** A person's picture: the face at [box] in the photo at [uri]. */
data class FaceCrop(val fileId: Long, val uri: String, val box: FaceBox)

/** [name] is null until the person names them; [photos] counts the photos they are in. */
data class Person(val id: Long, val name: String?, val photos: Int, val cover: FaceCrop?)

/** Two people Home shows who may be one: [first] has at least as many photos as [second]. */
data class SamePerson(val first: Person, val second: Person)

/** Photos looked at for faces, and photos still to look at. */
data class FacesProgress(val done: Long, val pending: Long)

/** Where SFace wants a face: its five points in the 112 px input it reads, and the transform that puts a face there. */
object FaceAlign {
    const val SIZE = 112

    /**
     * The eye on the image's left, the other eye, the nose tip, then the mouth corner on the image's left and the other
     * one, as x, y pairs: OpenCV's FaceRecognizerSF.alignCrop template (ArcFace's).
     */
    val POINTS = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

    /**
     * The rotation, uniform scale and shift that take [points] (the five, in the same order, in photo pixels) closest to
     * [POINTS] in least squares, as (a, b, tx, ty): x' = a·x − b·y + tx and y' = b·x + a·y + ty. Null when the points
     * all coincide.
     */
    fun transform(points: FloatArray): FloatArray? {
        val n = POINTS.size / 2
        var sx = 0.0
        var sy = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in 0 until n) {
            sx += points[2 * i]
            sy += points[2 * i + 1]
            dx += POINTS[2 * i]
            dy += POINTS[2 * i + 1]
        }
        sx /= n
        sy /= n
        dx /= n
        dy /= n
        var norm = 0.0
        var dotSum = 0.0
        var crossSum = 0.0
        for (i in 0 until n) {
            val x = points[2 * i] - sx
            val y = points[2 * i + 1] - sy
            val u = POINTS[2 * i] - dx
            val v = POINTS[2 * i + 1] - dy
            norm += x * x + y * y
            dotSum += x * u + y * v
            crossSum += x * v - y * u
        }
        if (norm < 1e-9) return null
        val a = dotSum / norm
        val b = crossSum / norm
        return floatArrayOf(a.toFloat(), b.toFloat(), (dx - a * sx + b * sy).toFloat(), (dy - b * sx - a * sy).toFloat())
    }
}

/** A fingerprint as stored: unit length, int8, value[i] ≈ bytes[i] * scale. */
internal class FaceVector(val bytes: ByteArray, val scale: Float) {
    fun floats(): FloatArray = FloatArray(bytes.size) { bytes[it] * scale }

    companion object {
        fun of(raw: FloatArray): FaceVector {
            val unit = normalized(raw)
            val scale = unit.maxOf { abs(it) }.coerceAtLeast(1e-12f) / 127f
            return FaceVector(ByteArray(unit.size) { (unit[it] / scale).roundToInt().coerceIn(-127, 127).toByte() }, scale)
        }
    }
}

/** One face as grouping sees it. */
internal class FaceRow(val id: Long, val person: Long?, val vector: FloatArray)

private fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

private fun length(a: FloatArray): Float = sqrt(dot(a, a)).coerceAtLeast(1e-12f)

/**
 * Gives each face above [after] that has no person one: the person whose faces' mean is closest, when it is at least
 * [SAME_PERSON]; else, with the closest face still on its own at that distance, a new person ([newPerson]) for the
 * two; else it waits alone for a later face. Faces at or below [after] keep their person, or lack of one ("Not this
 * person"). Returns face id → person id.
 */
// ponytail: one pass, nearest mean; Apple Photos adds a second agglomerative pass overnight. Add one if people split or merge.
internal fun groupFaces(rows: List<FaceRow>, after: Long, newPerson: () -> Long): Map<Long, Long> {
    val sums = HashMap<Long, FloatArray>()
    for (r in rows) {
        val p = r.person ?: continue
        val sum = sums.getOrPut(p) { FloatArray(r.vector.size) }
        for (i in sum.indices) sum[i] += r.vector[i]
    }
    val alone = rows.filterTo(ArrayList()) { it.person == null && it.id <= after }
    val assigned = HashMap<Long, Long>()
    for (r in rows) {
        if (r.id <= after || r.person != null) continue
        var best: Long? = null
        var bestCos = SAME_PERSON
        for ((p, sum) in sums) {
            val cos = dot(r.vector, sum) / length(sum)
            if (cos >= bestCos) {
                best = p
                bestCos = cos
            }
        }
        if (best != null) {
            assigned[r.id] = best
            sums.getValue(best).let { sum -> for (i in sum.indices) sum[i] += r.vector[i] }
            continue
        }
        val pair = alone.maxByOrNull { dot(r.vector, it.vector) }
        if (pair != null && dot(r.vector, pair.vector) >= SAME_PERSON) {
            val p = newPerson()
            assigned[r.id] = p
            assigned[pair.id] = p
            sums[p] = FloatArray(r.vector.size) { r.vector[it] + pair.vector[it] }
            alone.remove(pair)
        } else {
            alone += r
        }
    }
    return assigned
}

/** The named people a query names, longest names first, and the query without them. */
internal fun namedIn(text: String, named: List<Pair<Long, String>>): Pair<String, List<Pair<Long, String>>> {
    val raw = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    val words = raw.map { w -> w.lowercase().trim { !it.isLetterOrDigit() } }
    val used = BooleanArray(raw.size)
    val found = ArrayList<Pair<Long, String>>()
    for ((id, name) in named.sortedByDescending { it.second.length }) {
        val parts = name.lowercase().split(WHITESPACE).map { w -> w.trim { !it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) continue
        val at = (0..words.size - parts.size).firstOrNull { i -> parts.indices.all { k -> !used[i + k] && words[i + k] == parts[k] } } ?: continue
        for (k in parts.indices) used[at + k] = true
        found += id to name.trim()
    }
    return raw.filterIndexed { i, _ -> !used[i] }.joinToString(" ") to found
}

private val WHITESPACE = Regex("\\s+")

private const val GROUPED_KEY = "faces_grouped_through"

/** Tries per photo; one that stops the app this many times is left out. */
internal const val MAX_FACE_ATTEMPTS = 3

internal class PendingFace(val id: Long, val uri: String, val attempts: Int)

internal fun Store.pendingFaces(limit: Int): List<PendingFace> =
    faces.pendingFaceFiles(MAX_FACE_ATTEMPTS.toLong(), limit.toLong()) { id, uri, attempts -> PendingFace(id, uri, attempts.toInt()) }.executeAsList()

internal fun Store.facesProgress(): FacesProgress =
    FacesProgress(faces.doneFaceCount().executeAsOne(), faces.pendingFaceCount(MAX_FACE_ATTEMPTS.toLong()).executeAsOne())

internal fun Store.setFaceAttempts(fileId: Long, attempts: Int) = faces.setFaceAttempts(fileId, attempts.toLong())

/** [fileId]'s faces, replacing any it had, and the photo marked done. */
internal fun Store.saveFaces(fileId: Long, found: List<FoundFace>) = inTransaction {
    faces.deleteFacesOfFile(fileId)
    for (f in found) {
        val v = FaceVector.of(f.vector)
        faces.insertFace(fileId, f.box.left.toDouble(), f.box.top.toDouble(), f.box.right.toDouble(), f.box.bottom.toDouble(), v.bytes, v.scale.toDouble())
    }
    faces.finishFaceRead(fileId)
}

/** Groups the faces added since the last grouping ([groupFaces]); unnamed people left with no face go. */
internal fun Store.groupNewFaces() = inTransaction {
    val after = meta(GROUPED_KEY)?.toLongOrNull() ?: 0L
    val rows = faces.groupingFaces { id, person, bytes, scale -> FaceRow(id, person, FaceVector(bytes, scale.toFloat()).floats()) }
        .executeAsList()
    val assigned = groupFaces(rows, after) {
        faces.insertPerson()
        faces.lastPersonId().executeAsOne()
    }
    for ((face, person) in assigned) faces.setFacePerson(person, face)
    rows.lastOrNull()?.let { setMeta(GROUPED_KEY, it.id.toString()) }
    faces.deleteEmptyPersons()
}

/** A face in a person, as their picture and the same-person question read it. */
internal class PersonFace(val crop: FaceCrop, val vector: FloatArray)

/** Sums of unit vectors: the mean's direction, which is all a cosine needs. */
private fun sum(faces: List<PersonFace>): FloatArray = FloatArray(FACE_DIMS).also { s ->
    for (f in faces) for (i in s.indices) s[i] += f.vector[i]
}

private fun FaceBox.area() = (right - left) * (bottom - top)

/**
 * A person's picture: of their larger faces (the bigger half) not cut off by the photo's edge, the one most like them
 * all. The largest face alone was often a close-up cut off at the edge and among the least like the person (on a real
 * library it ranked 403rd of 406 faces and 69th of 74).
 */
internal fun coverOf(faces: List<PersonFace>): FaceCrop? {
    val whole = faces.filter { minOf(it.crop.box.left, it.crop.box.top, 1 - it.crop.box.right, 1 - it.crop.box.bottom) >= EDGE }.ifEmpty { faces }
    val median = whole.map { it.crop.box.area() }.sorted().getOrNull(whole.size / 2) ?: return null
    val mean = sum(faces)
    return whole.filter { it.crop.box.area() >= median }.maxByOrNull { dot(it.vector, mean) }?.crop
}

/** A face box this close to the photo's edge is taken to be cut off by it. */
private const val EDGE = 0.01f

/**
 * The two people most likely to be one ([ASK_SAME_PERSON] or closer), or null. Never two who share a photo (one photo,
 * two people, bar collages), two named differently, or a pair in [declined] (smaller id first).
 */
internal fun samePersonQuestion(people: List<Person>, faces: Map<Long, List<PersonFace>>, declined: Set<Pair<Long, Long>>): SamePerson? {
    val shown = people.filter { faces[it.id] != null }
    val sums = shown.associate { p -> p.id to faces.getValue(p.id).let(::sum) }
    val photos = shown.associate { p -> p.id to faces.getValue(p.id).mapTo(HashSet()) { it.crop.fileId } }
    var best: SamePerson? = null
    var bestCos = ASK_SAME_PERSON
    for ((i, a) in shown.withIndex()) {
        for (b in shown.subList(i + 1, shown.size)) {
            if (a.name != null && b.name != null && !a.name.equals(b.name, ignoreCase = true)) continue
            if (minOf(a.id, b.id) to maxOf(a.id, b.id) in declined) continue
            val sa = sums.getValue(a.id)
            val sb = sums.getValue(b.id)
            val cos = dot(sa, sb) / (length(sa) * length(sb))
            if (cos < bestCos || photos.getValue(a.id).any { it in photos.getValue(b.id) }) continue
            best = if (a.photos >= b.photos) SamePerson(a, b) else SamePerson(b, a)
            bestCos = cos
        }
    }
    return best
}

private fun Store.groupedFaces(): Map<Long, List<PersonFace>> = faces.groupedFaces { person, fileId, uri, l, t, r, b, bytes, scale ->
    val crop = FaceCrop(fileId, uri, FaceBox(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()))
    person to PersonFace(crop, FaceVector(bytes, scale.toFloat()).floats())
}.executeAsList().groupBy({ it.first }, { it.second })

internal fun Store.people(minPhotos: Int = PERSON_MIN_PHOTOS, grouped: Map<Long, List<PersonFace>> = groupedFaces()): List<Person> =
    faces.people(minPhotos.toLong()) { id, name, photos -> Person(id, name, photos.toInt(), grouped[id]?.let(::coverOf)) }.executeAsList()

private const val NOT_SAME_KEY = "people_not_same"

internal fun Store.declinedPairs(): Set<Pair<Long, Long>> = meta(NOT_SAME_KEY).orEmpty().split(',').mapNotNullTo(HashSet()) { pair ->
    val ids = pair.split(':').mapNotNull { it.toLongOrNull() }
    if (ids.size == 2) ids[0] to ids[1] else null
}

/** Home's question, if any: two people it shows who may be one. */
internal fun Store.samePersonQuestion(): SamePerson? {
    val grouped = groupedFaces()
    return samePersonQuestion(people(grouped = grouped), grouped, declinedPairs())
}

/** "Not the same person": the pair is never asked about again. */
internal fun Store.declineSamePerson(a: Long, b: Long) = inTransaction {
    setMeta(NOT_SAME_KEY, (declinedPairs() + (minOf(a, b) to maxOf(a, b))).joinToString(",") { "${it.first}:${it.second}" })
}

internal fun Store.personFileIds(id: Long): List<Long> = faces.personFileIds(id).executeAsList()

internal fun Store.namedPersons(): List<Pair<Long, String>> = faces.namedPersons { id, name -> id to name.orEmpty() }.executeAsList()

internal fun Store.renamePerson(id: Long, name: String?) = faces.renamePerson(name?.trim()?.ifEmpty { null }, id)

internal fun Store.mergePeople(into: Long, from: Long) = inTransaction {
    if (into == from) return@inTransaction
    faces.moveFaces(into, from)
    faces.adoptName(from, into)
    faces.deletePerson(from)
}

internal fun Store.notThisPerson(person: Long, fileId: Long) = inTransaction {
    faces.rejectFaces(person, fileId)
    faces.deleteEmptyPersons()
}

/** Turning People off: every face, person and name goes. */
internal fun Store.forgetAllFaces() = inTransaction {
    faces.deleteAllFaces()
    faces.deleteAllPersons()
    faces.deleteAllFaceReads()
    deleteMeta(GROUPED_KEY)
    deleteMeta(NOT_SAME_KEY)
}

/**
 * Looks at photos for faces until none are left, [budget] runs out, or [stop] names a reason; groups what it found as
 * it goes. Each try is counted first, so a photo that stops the app is left out after [MAX_FACE_ATTEMPTS].
 */
internal class FacePass(
    private val store: Store,
    private val reader: FaceReader,
    private val log: (String, Throwable?) -> Unit,
    private val onGrouped: () -> Unit,
) {
    suspend fun run(budget: Duration, stop: () -> StopReason?, awaitUi: suspend () -> Unit): StopReason {
        val start = TimeSource.Monotonic.markNow()
        var sinceGrouping = 0
        var looked = false
        try {
            while (true) {
                val page = store.pendingFaces(PAGE).ifEmpty { return StopReason.DONE }
                for (p in page) {
                    awaitUi()
                    stop()?.let { return it }
                    if (start.elapsedNow() >= budget) return StopReason.BUDGET
                    store.setFaceAttempts(p.id, p.attempts + 1)
                    val found = try {
                        reader.faces(p.uri)
                    } catch (e: CancellationException) {
                        store.setFaceAttempts(p.id, p.attempts)
                        throw e
                    } catch (e: Exception) {
                        if (e.isDiskFull()) {
                            store.setFaceAttempts(p.id, p.attempts)
                            return StopReason.DISK_FULL
                        }
                        log("Couldn't look for faces in a photo", e)
                        null
                    }
                    looked = true
                    if (found != null) {
                        store.saveFaces(p.id, found)
                        if (found.isNotEmpty() && ++sinceGrouping >= GROUP_EVERY) {
                            store.groupNewFaces()
                            sinceGrouping = 0
                            onGrouped()
                        }
                    }
                }
            }
        } finally {
            if (sinceGrouping > 0) store.groupNewFaces()
            // Also after photos with no face, so a count of photos to go reaches 0.
            if (looked) onGrouped()
        }
    }

    private companion object {
        const val PAGE = 32

        /** Photos with faces between groupings, so Home's people fill in while the pass runs. */
        const val GROUP_EVERY = 25
    }
}
