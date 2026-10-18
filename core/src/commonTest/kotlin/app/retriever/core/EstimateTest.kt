package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EstimateTest {
    private val book = SpeedBook()
        .add(Kind.PHOTO, 400.0, 1.0).add(Kind.PHOTO, 600.0, 1.0) // 500 ms a photo
        .add(Kind.DOC, 300.0, 3.0).add(Kind.DOC, 100.0, 1.0) // 4 chunks over 2 docs in 400 ms: 200 ms a doc

    @Test
    fun perKindMeansTimesCounts() {
        assertEquals(1_200 * 500.0 + 600 * 200.0, book.ms(mapOf(Kind.PHOTO to 1_200L, Kind.DOC to 600L), 0))
        val e = readEstimate(book, mapOf(Kind.PHOTO to 1_200L, Kind.DOC to 600L), 0, ios = false, bg = null)!!
        // 12 minutes: the range is [0.9, 1.4] times it, outward.
        assertEquals(ReadEstimate(1_800, 10, 17, 1, measured = true), e)
    }

    @Test
    fun videoIsReadByBytes() {
        val b = SpeedBook().add(Kind.VIDEO, 1_000.0, 1e6) // 1 ms per kB
        assertEquals(60_000.0, b.ms(mapOf(Kind.VIDEO to 1L), 60_000_000L), "one 60 MB video is a minute")
        assertEquals(6_000.0, b.ms(mapOf(Kind.VIDEO to 9L), 6_000_000L), "small videos are quick whatever their count")
    }

    @Test
    fun unmeasuredKindsUseSeedsAndSayNotMeasured() {
        val seeded = SpeedBook(photoSeedMs = 900.0).add(Kind.DOC, 200.0, 1.0)
        assertEquals(10 * 900.0 + 200.0, seeded.ms(mapOf(Kind.PHOTO to 10L, Kind.DOC to 1L), 0))
        assertFalse(readEstimate(seeded, mapOf(Kind.PHOTO to 10L), 0, ios = false, bg = null)!!.measured)
        assertTrue(readEstimate(seeded, mapOf(Kind.PHOTO to 0L, Kind.DOC to 4L), 0, ios = false, bg = null)!!.measured)
    }

    @Test
    fun nothingMeasuredIsNull() {
        assertNull(readEstimate(SpeedBook(photoSeedMs = 500.0), mapOf(Kind.PHOTO to 10L), 0, ios = false, bg = null))
    }

    @Test
    fun androidCountsSixHourNightsOverTheHighEnd() {
        val b = SpeedBook().add(Kind.PHOTO, 1_000.0, 1.0)
        fun nights(minutes: Int) = readEstimate(b, mapOf(Kind.PHOTO to minutes * 60L), 0, ios = false, bg = null)!!.nightsOnCharger
        assertEquals(1, nights(250), "high 350 minutes")
        assertEquals(2, nights(300), "high 420 minutes")
        assertEquals(0, nights(0))
    }

    @Test
    fun iosCountsNightsOnlyOnceABackgroundRunIsMeasured() {
        val pending = mapOf(Kind.PHOTO to 1_200L)
        assertNull(readEstimate(book, pending, 0, ios = true, bg = null)!!.nightsOnCharger)
        assertNull(readEstimate(book, pending, 0, ios = true, bg = BgSpeed().add(0, 600))!!.nightsOnCharger, "a run that read nothing")
        assertEquals(3, readEstimate(book, pending, 0, ios = true, bg = BgSpeed().add(500, 3_600))!!.nightsOnCharger)
    }

    @Test
    fun bookSurvivesItsEncodingAndAGarbledOne() {
        assertEquals(book.copy(photoSeedMs = 480.0), SpeedBook.decode(book.copy(photoSeedMs = 480.0).encode()))
        assertEquals(SpeedBook(), SpeedBook.decode("{not json"))
        assertNull(BgSpeed.decode(null))
    }

    @Test
    fun noHoursShipUncalibrated() {
        assertFalse(ESTIMATE_CALIBRATED)
        assertEquals(0.9, ESTIMATE_LOW)
        assertEquals(1.4, ESTIMATE_HIGH)
    }
}
