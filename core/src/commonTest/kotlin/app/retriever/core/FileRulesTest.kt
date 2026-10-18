package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FileRulesTest {
    @Test
    fun classifiesByMimePathAndName() {
        assertEquals(Kind.SCREENSHOT, FileRules.classify("image/png", "Pictures/Screenshots/", "a.png"))
        assertEquals(Kind.PHOTO, FileRules.classify("image/jpeg", "DCIM/Camera/", "b.jpg"))
        assertEquals(Kind.PDF, FileRules.classify("application/pdf", "Download/", "c.pdf"))
        assertEquals(Kind.DOC, FileRules.classify("text/csv", "Download/", "d.csv"))
        assertEquals(
            Kind.DOC,
            FileRules.classify(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "Download/",
                "e.docx",
            ),
        )
        assertEquals(Kind.DOC, FileRules.classify("application/octet-stream", "Download/", "f.odt"))
        assertEquals(Kind.DOC, FileRules.classify("application/epub+zip", "Books/", "g.epub"))
        assertEquals(Kind.DOC, FileRules.classify("application/octet-stream", "Download/", "h.html"))
        assertEquals(Kind.OTHER, FileRules.classify("application/octet-stream", "Download/", "i.bin"))
        assertEquals(Kind.DOC, FileRules.classify("application/rtf", "Download/", "k.rtf"))
        assertEquals(Kind.DOC, FileRules.classify("application/octet-stream", "Download/", "l.rtf"))
        assertEquals(Kind.DOC, FileRules.classify(FileRules.mimeForExtension("rtf")!!, "Download/", "m.rtf"))
    }

    @Test
    fun nullMimeFallsBackToExtension() {
        val mime = FileRules.mimeForExtension("md")!!
        assertEquals(Kind.DOC, FileRules.classify(mime, "Notes/", "j.md"))
        assertEquals(null, FileRules.mimeForExtension("zzz"))
    }

    @Test
    fun skipsPrivateAndHiddenButKeepsAndroidMedia() {
        assertTrue(FileRules.shouldSkip(".thumbnails/", "x.jpg"))
        assertTrue(FileRules.shouldSkip("Android/data/com.x/", "y.pdf"))
        assertTrue(FileRules.shouldSkip("Pictures/", ".nomedia"))
        assertFalse(FileRules.shouldSkip("Android/media/com.whatsapp/", "z.jpg"))
    }

    private val day = 86_400_000L
    private val seeds = mapOf(
        Kind.PHOTO to 750.0,
        Kind.SCREENSHOT to 1500.0,
        Kind.DOC to 1000.0,
        Kind.PDF to 1000.0,
        Kind.VIDEO to 6000.0,
    )

    /** [days] bursts of 3 files a minute apart, folders cycling over 3. */
    private fun library(kind: Kind, days: Int, size: Long = 1000) = (0 until days).flatMap { d ->
        (0 until 3).map { k ->
            val t = d * day + k * 60_000L
            SourceFile("$kind/$d/$k", "$d$k", "F${d % 3}", "x/y", kind, size, t)
        }
    }

    private val big = Kind.entries.filter { it in seeds }.flatMap { library(it, 120) }
    private val plan = FileRules.planTaste(big, { seeds.getValue(it.kind) }, 90.seconds)
    private val byUri = big.associateBy { it.uri }

    @Test
    fun planTasteIsDeterministicAndWithinBudget() {
        assertEquals(plan, FileRules.planTaste(big.reversed(), { seeds.getValue(it.kind) }, 90.seconds))
        assertFalse(plan.everything)
        assertTrue(plan.seconds <= 90, "seconds=${plan.seconds}")
    }

    @Test
    fun planTasteCoversOldestBinAndNewest() {
        val photos = big.filter { it.kind == Kind.PHOTO }.sortedBy { it.modifiedAt }
        val n = plan.byKind.getValue(Kind.PHOTO)
        val firstBin = photos.take(photos.size / (n - 2)).maxOf { it.modifiedAt }
        val times = plan.ids.map { byUri.getValue(it) }.filter { it.kind == Kind.PHOTO }.map { it.modifiedAt }
        assertTrue(times.min() <= firstBin)
        assertEquals(photos.last().modifiedAt, times.max())
        assertEquals(plan.newest, big.maxOf { it.modifiedAt }.takeIf { it == plan.newest })
    }

    @Test
    fun planTasteHonoursBurstAndFolderGuards() {
        for ((kind, n) in plan.byKind) {
            val picks = plan.ids.map { byUri.getValue(it) }.filter { it.kind == kind }
            assertEquals(n, picks.size)
            for (a in picks) for (b in picks) if (a !== b) assertTrue(kotlin.math.abs(a.modifiedAt - b.modifiedAt) >= 3_600_000L)
            val cap = kotlin.math.ceil(0.4 * n).toInt()
            assertTrue(picks.groupingBy { it.folder }.eachCount().values.all { it <= cap }, "$kind")
        }
    }

    @Test
    fun evenATinyBudgetKeepsTheCheapestEligibleFile() {
        val tight = FileRules.planTaste(big, { seeds.getValue(it.kind) }, 1.seconds)
        val cheapest = big.minWith(compareBy<SourceFile> { seeds.getValue(it.kind) }.thenBy { it.uri })
        assertEquals(listOf(cheapest.uri), tight.ids)
        val firstRound = plan.ids.take(8).map { byUri.getValue(it).kind }.groupingBy { it }.eachCount()
        assertEquals((seeds.keys - Kind.VIDEO).associateWith { 2 }, firstRound)
    }

    @Test
    fun planTasteTakesASmallLibraryWhole() {
        val small = library(Kind.PHOTO, 2) + library(Kind.PDF, 1) + library(Kind.AUDIO, 3)
        val p = FileRules.planTaste(small, { seeds.getValue(it.kind) }, 90.seconds)
        assertTrue(p.everything)
        assertEquals(9, p.ids.size)
        assertEquals(mapOf(Kind.PHOTO to 6, Kind.PDF to 3), p.byKind)
    }

    @Test
    fun planTasteVideosPreferSmallFilesUnder200Mb() {
        val vids = (0 until 30).map { SourceFile("v$it", "v", "F", "video/mp4", Kind.VIDEO, if (it == 15) 5_000L else (300L shl 20), it * day) }
        val p = FileRules.planTaste(vids, SpeedBook()::costMs, 30.seconds)
        assertTrue("v15" in p.ids)
    }

    @Test
    fun aVideoMeasuredFasterThanTheSeedKeepsItsSlot() {
        val phone = seeds.keys.flatMap { library(it, 2000, size = 1000) }
        val p = FileRules.planTaste(phone, { if (it.kind == Kind.VIDEO) 20_000.0 else seeds.getValue(it.kind) }, 90.seconds)
        assertEquals(1, p.byKind[Kind.VIDEO])
    }

    @Test
    fun phoneSizedLibraryFitsAtRealisticPacesIncludingSmallAndUnknownVideos() {
        val book = SpeedBook()
        val phone = seeds.keys.flatMap { library(it, 2000, size = if (it == Kind.VIDEO) 5_000_000 else 1000) }
        val p = FileRules.planTaste(phone, book::costMs, 90.seconds)
        val chosen = phone.filter { it.uri in p.ids }
        assertTrue(chosen.sumOf(book::costMs) <= 90_000)
        assertEquals(1, p.byKind[Kind.VIDEO])
        assertEquals(seeds.keys, p.byKind.keys)
        for (size in listOf(0L, 100L, 5_000_000L)) {
            val videos = library(Kind.VIDEO, 1000, size)
            val fast = FileRules.planTaste(videos, { 1.0 }, 90.seconds)
            assertEquals(3, fast.ids.size, "a cheap measurement cannot defeat the budget-derived video ceiling")
            val seeded = FileRules.planTaste(videos, book::costMs, 90.seconds)
            assertEquals(3, seeded.ids.size)
            assertFalse(seeded.everything)
        }
    }
}
