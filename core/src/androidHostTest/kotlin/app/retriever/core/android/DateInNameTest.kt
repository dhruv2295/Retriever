package app.retriever.core.android

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** A WhatsApp photo copied to a new phone kept its name's date, not the copy's (4 Nov 2026). */
class DateInNameTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val copied = at(2026, 11, 4, 9, 0, 0)

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int) = LocalDateTime.of(y, m, d, h, min, s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theNameGivesTheDateWhenTheFileHasNone() {
        assertEquals(at(2024, 10, 24, 12, 0, 0), dateInName("IMG-20241024-WA0009.jpg", copied, zone))
        assertEquals(at(2024, 10, 24, 15, 30, 12), dateInName("Screenshot_20241024-153012_WhatsApp.jpg", copied, zone))
        assertEquals(at(2024, 10, 24, 15, 30, 12), dateInName("signal-2024-10-24-153012.jpg", copied, zone))
        assertEquals(at(2024, 10, 24, 12, 0, 0), dateInName("Diwali 2024.10.24 family.mp4", copied, zone), "any app's name")
        assertEquals(null, dateInName("holiday.jpg", copied, zone))
        assertEquals(null, dateInName("IMG_1234.jpg", copied, zone))
        assertEquals(null, dateInName("frame_9920241024123.jpg", copied, zone), "not inside a longer number")
        assertEquals(null, dateInName("IMG-20241324-WA0001.jpg", copied, zone), "no 13th month")
        assertEquals(null, dateInName("IMG-20271024-WA0001.jpg", copied, zone), "not after the file was written")
    }
}
