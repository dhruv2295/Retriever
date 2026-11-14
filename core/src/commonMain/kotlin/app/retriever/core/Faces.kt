package app.retriever.core

import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.TimeSource

// People (README.md): the platform finds each face and turns it into SFace's fingerprint; core keeps them,
// groups them into people, and finds a named person's photos. Only while the person has it on (Prefs.recognizePeople).

/** SFace's fingerprint length. */
const val FACE_DIMS = 128

/**
 * A face joins a person when its [CLOSEST] most alike faces of theirs average this cosine or more, and two faces alone
 * start a person at it. Against the old rule (0.5 to the person's mean), on LFW (5,420 faces, 311 people, three orders)
 * faces placed with someone else fell from 5–13 to 2, with 98% of one person's pairs still together against 99.6%;
 * at 0.5 two whole people merged. On a real library a 1,493-face person no longer split off its glasses and selfies,
 * and a group started from two wrong faces let them go (README.md).
 */
const val SAME_PERSON = 0.55f

/**
 * How many of a person's faces a new face is compared with. A mean blurs a person seen over years into nobody in
 * particular, and a person of three faces into whatever those happen to share; their closest faces are neither.
 */
const val CLOSEST = 5

/**
 * Two people whose faces' means are at least this close may be one person, so Home asks. Measured on LFW with SFace:
 * one person split in two scored 0.70 or more in 99% of splits, strangers at most 0.59, and brothers, sisters and father
 * and son at most 0.63. A real library's splits (selfies behind a phone) scored 0.78 and 0.81.
 */
const val ASK_SAME_PERSON = 0.65f

/**
 * Home also asks when half the smaller person's faces are this alike to their [CLOSEST] most alike faces of the other,
 * as grouping compares. A person seen over years has a mean like nobody in particular: on a real library a 7-photo
 * split of a 1,450-photo person scored 0.51 by means and 0.61 here. On LFW grouped as the app does (three orders, 41
 * people left split) this asked about 23 splits and no strangers (at most 0.44); father and son came closest at 0.53.
 * Never joins: no split on the real library got past 0.62.
 */
const val ASK_SAME_PERSON_CLOSEST = 0.55f

/**
 * Two people whose faces' means are at least this close are joined without asking: well past LFW's strangers (0.59) and
 * relatives (0.63). On a real library all five pairs at 0.75 or more were one person, confirmed by its owner.
 */
const val JOIN_SAME_PERSON = 0.75f

/**
 * A face whose raw SFace fingerprint is shorter than this is too unclear to know again, so it isn't kept: SFace gives
 * a face it can't make out (a phone over it, a blur, a sideways head) or something that isn't a face a short one, and
 * those look alike whoever they are, so they grouped strangers, a logo and a dog as one person. On a real library such
 * faces measured 2.9–10.8 and 114 clear ones 10.6–14.6; 26 of 27 such faces are under 9, and 0.5% of LFW's 5,425 faces.
 */
const val MIN_FACE_STRENGTH = 9f

/** An unnamed person shows on Home once they are in this many photos; a named one shows from their first. */
const val PERSON_MIN_PHOTOS = 3

/** A face's place in its photo, as fractions of the upright photo's width and height. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A face the platform found: where it is, and SFace's raw fingerprint (its length says how clear the face is; normalised here). */
class FoundFace(val box: FaceBox, val vector: FloatArray)

/**
 * Whether what People reads faces with is on this device ([FaceReader.ready]). Declared from most to least ready, so a
 * reader that needs several parts reports the least ready of them (`maxOf`).
 */
enum class PeopleModel {
    READY,

    /** Downloading now. */
    GETTING,

    /** Waits for Wi-Fi: mobile data isn't allowed for downloads, or Play asks for it. */
    WAITING_FOR_WIFI,

    /** Couldn't be got now (no network, no Play); asked again on a later batch. */
    UNAVAILABLE,
}

/** Finds the faces in a photo; the platform's face detector and SFace. */
interface FaceReader {
    /** Every face clear enough to know again in the photo at [uri]; empty when it has none, null when it can't be opened. */
    @Throws(Throwable::class)
    suspend fun faces(uri: String): List<FoundFace>?

    /**
     * Whether [faces] can run. Anything but [PeopleModel.READY] has started getting what it needs, once, over mobile data
     * only when [mobileData] (Prefs.allowMobileData): Android's Play build fetches SFace when People is turned on. A
     * platform that ships everything answers READY.
     */
    @Throws(Throwable::class)
    suspend fun ready(mobileData: Boolean): PeopleModel = PeopleModel.READY
}

/** A person's picture: the face at [box] in the photo at [uri]. */
data class FaceCrop(val fileId: Long, val uri: String, val box: FaceBox)

/** [name] is null until the person names them; [photos] counts the photos they are in. */
data class Person(val id: Long, val name: String?, val photos: Int, val cover: FaceCrop?)

/** Two people Home shows who may be one: [first] has at least as many photos as [second]. */
data class SamePerson(val first: Person, val second: Person)

/** Photos looked at for faces, and photos still to look at. */
data class FacesProgress(val done: Long, val pending: Long)

/** Processing’s face counts while People is on: [progress], and the people Home shows. */
data class FacesSummary(val progress: FacesProgress, val people: Int)

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
 * Gives each face with no person that [place] picks one: the person whose [CLOSEST] most alike faces average closest
 * (a person with fewer counts them all), when that is at least [SAME_PERSON]; else, with the closest face still on its
 * own at that distance, a new person ([newPerson]) for the two; else it waits alone for a later face. Faces it doesn't
 * pick keep their person, or lack of one ("Not this person"). Returns face id → person id.
 */
// ponytail: one pass in id order; Apple Photos adds a second agglomerative pass overnight. Add one if people still split.
internal fun groupFaces(rows: List<FaceRow>, place: (FaceRow) -> Boolean, newPerson: () -> Long): Map<Long, Long> {
    val people = HashMap<Long, MutableList<FloatArray>>()
    for (r in rows) r.person?.let { people.getOrPut(it) { ArrayList() } += r.vector }
    val alone = rows.filterTo(ArrayList()) { it.person == null && !place(it) }
    val assigned = HashMap<Long, Long>()
    val top = FloatArray(CLOSEST)
    for (r in rows) {
        if (r.person != null || !place(r)) continue
        var best: Long? = null
        var bestScore = SAME_PERSON
        for ((p, faces) in people) {
            val score = closestAverage(r.vector, faces, top)
            if (score >= bestScore) {
                best = p
                bestScore = score
            }
        }
        if (best != null) {
            assigned[r.id] = best
            people.getValue(best) += r.vector
            continue
        }
        val pair = alone.maxByOrNull { dot(r.vector, it.vector) }
        if (pair != null && dot(r.vector, pair.vector) >= SAME_PERSON) {
            val p = newPerson()
            assigned[r.id] = p
            assigned[pair.id] = p
            people[p] = mutableListOf(pair.vector, r.vector)
            alone.remove(pair)
        } else {
            alone += r
        }
    }
    return assigned
}

/** How alike [v] is to the [CLOSEST] most alike of [faces] (all of them when fewer), on average; [top] is scratch space. */
private fun closestAverage(v: FloatArray, faces: List<FloatArray>, top: FloatArray): Float {
    top.fill(-1f)
    for (f in faces) {
        val cos = dot(v, f)
        if (cos <= top[CLOSEST - 1]) continue
        var i = CLOSEST - 1
        while (i > 0 && top[i - 1] < cos) {
            top[i] = top[i - 1]
            i--
        }
        top[i] = cos
    }
    val k = minOf(CLOSEST, faces.size)
    return (0 until k).sumOf { top[it].toDouble() }.toFloat() / k
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

/** Set while a faces pass is under way: it finishes before files that came meanwhile (Retriever.runBatch). */
private const val FACES_RUN_KEY = "faces_run"

internal fun Store.facesRunning(): Boolean = meta(FACES_RUN_KEY) != null

internal fun Store.setFacesRunning(on: Boolean) = if (on) setMeta(FACES_RUN_KEY, "1") else deleteMeta(FACES_RUN_KEY)

/** Tries per photo; one that stops the app this many times is left out. */
internal const val MAX_FACE_ATTEMPTS = 3

internal class PendingFace(val id: Long, val uri: String, val attempts: Int)

internal fun Store.pendingFaces(limit: Int): List<PendingFace> =
    faces.pendingFaceFiles(MAX_FACE_ATTEMPTS.toLong(), limit.toLong()) { id, uri, attempts -> PendingFace(id, uri, attempts.toInt()) }.executeAsList()

internal fun Store.facesProgress(): FacesProgress =
    FacesProgress(faces.doneFaceCount().executeAsOne(), faces.pendingFaceCount(MAX_FACE_ATTEMPTS.toLong()).executeAsOne())

internal fun Store.setFaceAttempts(fileId: Long, attempts: Int) = faces.setFaceAttempts(fileId, attempts.toLong())

/** [fileId]'s clear faces ([MIN_FACE_STRENGTH]), replacing any it had, and the photo marked done. */
internal fun Store.saveFaces(fileId: Long, found: List<FoundFace>) = inTransaction {
    faces.deleteFacesOfFile(fileId)
    for (f in found) {
        if (length(f.vector) < MIN_FACE_STRENGTH) continue
        val v = FaceVector.of(f.vector)
        faces.insertFace(fileId, f.box.left.toDouble(), f.box.top.toDouble(), f.box.right.toDouble(), f.box.bottom.toDouble(), v.bytes, v.scale.toDouble())
    }
    faces.finishFaceRead(fileId)
}

/**
 * Groups the faces added since the last grouping ([groupFaces]); unnamed people left with no face go. A library grouped
 * by an older rule ([GROUPING_RULE]) is grouped again first: every face in a person is placed afresh, faces taken out
 * stay out, and each name and "not the same person" answer moves to the new person holding most of that person's faces.
 */
internal fun Store.groupNewFaces() = inTransaction {
    val after = meta(GROUPED_KEY)?.toLongOrNull() ?: 0L
    val stored = faces.groupingFaces { id, person, bytes, scale -> FaceRow(id, person, FaceVector(bytes, scale.toFloat()).floats()) }
        .executeAsList()
    val again = meta(RULE_KEY) != GROUPING_RULE
    // Grouping again leaves out the faces waiting alone, and with them any taken out of a person, which a lone face could pair with.
    val rows = if (again) stored.filter { it.person != null || it.id > after }.map { FaceRow(it.id, null, it.vector) } else stored
    val assigned = groupFaces(rows, { again || it.id > after }) {
        faces.insertPerson()
        faces.lastPersonId().executeAsOne()
    }
    if (again) {
        for (r in stored) if (r.person != null && r.id !in assigned) faces.setFacePerson(null, r.id)
        carryOver(stored, assigned)
        setMeta(RULE_KEY, GROUPING_RULE)
    }
    for ((face, person) in assigned) faces.setFacePerson(person, face)
    stored.lastOrNull()?.let { setMeta(GROUPED_KEY, it.id.toString()) }
    faces.deleteEmptyPersons()
    joinSamePeople()
}

/** Names and "not the same person" answers of the people [old] grouped, onto whom [assigned] put most of their faces. */
private fun Store.carryOver(old: List<FaceRow>, assigned: Map<Long, Long>) {
    val successor = old.filter { it.person != null }.groupBy { it.person }.mapNotNull { (person, rows) ->
        val most = rows.mapNotNull { assigned[it.id] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (person != null && most != null) person to most else null
    }.toMap()
    // A name whose faces all went elsewhere stays on its empty person, as when its photos leave the library.
    val taken = HashSet<Long>()
    for ((person, name) in namedPersons()) {
        val next = successor[person] ?: continue
        if (!taken.add(next)) continue
        renamePerson(next, name)
        renamePerson(person, null)
    }
    val declined = declinedPairs().mapNotNull { (a, b) ->
        val na = successor[a] ?: return@mapNotNull null
        val nb = successor[b] ?: return@mapNotNull null
        if (na == nb) null else minOf(na, nb) to maxOf(na, nb)
    }
    setMeta(NOT_SAME_KEY, declined.toSet().joinToString(",") { "${it.first}:${it.second}" })
}

/** Faces grouped by an older rule, which [groupNewFaces] groups again even with no photo left to look at. */
internal fun Store.regroupDue(): Boolean = meta(RULE_KEY) != GROUPING_RULE && facesProgress().done > 0

private const val RULE_KEY = "faces_grouping_rule"

/** Which [groupFaces] rule grouped the library; a library grouped by another is grouped again. */
private const val GROUPING_RULE = "closest-5-0.55"

/**
 * Joins people at [JOIN_SAME_PERSON] or closer, closest first, by the question's rules. Grouping places one face at a
 * time and never looks back, so a run of unusual photos (glasses, a phone over the face) can start a second person.
 */
internal fun Store.joinSamePeople() = inTransaction {
    val declined = declinedPairs()
    while (true) {
        val grouped = groupedFaces()
        val pair = samePersonQuestions(people(grouped = grouped), grouped, declined, JOIN_SAME_PERSON, closest = null).firstOrNull() ?: break
        mergePeople(into = pair.first.id, from = pair.second.id)
    }
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

/**
 * Two people in one photo are usually two people, but one person shows twice in a collage or a mirror: on the Pixel
 * library two splits of one person shared 1 and 2 photos with them.
 */
private const val MAX_SHARED_PHOTOS = 2

/** A face box this close to the photo's edge is taken to be cut off by it. */
private const val EDGE = 0.01f

/**
 * Means further apart than this are never one person by their closest faces either: on a real library (338 people,
 * 56,953 pairs) no pair below it scored more than 0.455 there, and checking only those above takes 828 pairs, not all.
 */
private const val CLOSEST_FROM_MEANS = 0.4f

/**
 * Pairs of people who may be one, closest first: their means [at] or closer, or, with [closest] set, half the smaller
 * one's faces at least that alike to their [CLOSEST] most alike faces of the other. Never two who share more than
 * [MAX_SHARED_PHOTOS] photos, two named differently, or a pair in [declined] (smaller id first).
 */
internal fun samePersonQuestions(
    people: List<Person>,
    faces: Map<Long, List<PersonFace>>,
    declined: Set<Pair<Long, Long>>,
    at: Float = ASK_SAME_PERSON,
    closest: Float? = ASK_SAME_PERSON_CLOSEST,
): List<SamePerson> {
    val shown = people.filter { faces[it.id] != null }
    val sums = shown.associate { p -> p.id to faces.getValue(p.id).let(::sum) }
    val vectors = shown.associate { p -> p.id to faces.getValue(p.id).map { it.vector } }
    val photos = shown.associate { p -> p.id to faces.getValue(p.id).mapTo(HashSet()) { it.crop.fileId } }
    val top = FloatArray(CLOSEST)
    fun closestMedian(a: List<FloatArray>, b: List<FloatArray>): Float {
        val (small, big) = if (a.size <= b.size) a to b else b to a
        return small.map { closestAverage(it, big, top) }.sorted()[small.size / 2]
    }
    val found = ArrayList<Pair<Float, SamePerson>>()
    for ((i, a) in shown.withIndex()) {
        for (b in shown.subList(i + 1, shown.size)) {
            if (a.name != null && b.name != null && !a.name.equals(b.name, ignoreCase = true)) continue
            if (minOf(a.id, b.id) to maxOf(a.id, b.id) in declined) continue
            val sa = sums.getValue(a.id)
            val sb = sums.getValue(b.id)
            val cos = dot(sa, sb) / (length(sa) * length(sb))
            val alike = cos >= at ||
                (closest != null && cos >= CLOSEST_FROM_MEANS && closestMedian(vectors.getValue(a.id), vectors.getValue(b.id)) >= closest)
            if (!alike || photos.getValue(a.id).count { it in photos.getValue(b.id) } > MAX_SHARED_PHOTOS) continue
            found += cos to if (a.photos >= b.photos) SamePerson(a, b) else SamePerson(b, a)
        }
    }
    return found.sortedByDescending { it.first }.map { it.second }
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

/** Home's questions: pairs of people it shows who may be one, closest first. */
internal fun Store.samePersonQuestions(): List<SamePerson> {
    val grouped = groupedFaces()
    return samePersonQuestions(people(grouped = grouped), grouped, declinedPairs())
}

/** "Not the same person": the pair is never asked about again. */
internal fun Store.declineSamePerson(a: Long, b: Long) = inTransaction {
    setMeta(NOT_SAME_KEY, (declinedPairs() + (minOf(a, b) to maxOf(a, b))).joinToString(",") { "${it.first}:${it.second}" })
}

internal fun Store.personFileIds(id: Long): List<Long> = faces.personFileIds(id).executeAsList()

/**
 * Who the largest clear face of [found] is, by grouping's own rule ([SAME_PERSON] over a person's [CLOSEST] faces), so
 * a photo names a person as a face joins one; null when it shows nobody known.
 */
// ponytail: the largest face only, so a group photo asks about one person; place every clear face if that falls short.
internal fun Store.personOf(found: List<FoundFace>): Long? {
    val face = found.filter { length(it.vector) >= MIN_FACE_STRENGTH }.maxByOrNull { it.box.area() } ?: return null
    // Stored as a kept face would be, so it is compared as grouping compares.
    val v = FaceVector.of(face.vector).floats()
    val people = faces.groupingFaces { _, person, bytes, scale -> person to FaceVector(bytes, scale.toFloat()).floats() }.executeAsList()
        .groupBy({ it.first }, { it.second })
    val top = FloatArray(CLOSEST)
    var best: Long? = null
    var bestScore = SAME_PERSON
    for ((person, theirs) in people) {
        if (person == null) continue
        val score = closestAverage(v, theirs, top)
        if (score >= bestScore) {
            best = person
            bestScore = score
        }
    }
    return best
}

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
    deleteMeta(FACES_RUN_KEY)
}

/**
 * Looks at photos for faces until none are left, [budget] runs out, or [stop] names a reason; groups what it found as
 * it goes. Each try is counted first, so a photo that stops the app is left out after [MAX_FACE_ATTEMPTS]. [onLooked]
 * follows each photo, for the status line's count; [onCurrent] comes before it, so Processing shows the photo being looked
 * at as it shows a file being read (without it, a fresh process said "Getting started…" for the whole pass).
 */
internal class FacePass(
    private val store: Store,
    private val reader: FaceReader,
    private val log: (String, Throwable?) -> Unit,
    private val onLooked: () -> Unit = {},
    private val onCurrent: (SourceFile) -> Unit = {},
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
                    store.file(p.id)?.let(onCurrent)
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
                    onLooked()
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
