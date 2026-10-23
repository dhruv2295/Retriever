package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcrSizeTest {
    @Test
    fun aTallScreenshotIsCappedByItsLongSide() {
        assertEquals(737 to MAX_PAGE_PX, ocrTargetSize(1080, 6000))
    }

    @Test
    fun aBigPhotoScalesToTheShortSideTarget() {
        assertEquals(1440 to 1080, ocrTargetSize(4000, 3000))
        assertEquals(1080 to 1440, ocrTargetSize(3000, 4000))
    }

    @Test
    fun smallImagesAreNeverUpscaled() {
        assertEquals(720 to 1280, ocrTargetSize(720, 1280))
        assertEquals(1 to 1, ocrTargetSize(1, 1))
    }

    @Test
    fun aHairlineStaysAtLeastOnePixel() {
        assertEquals(1 to MAX_PAGE_PX, ocrTargetSize(1, 100_000))
    }

    @Test
    fun onlyAnImageTheLongCapWouldShrinkIsReadInStrips() {
        assertNull(ocrStrips(1080, 2400), "a phone screenshot")
        assertNull(ocrStrips(1080, MAX_PAGE_PX), "exactly the cap")
        assertNull(ocrStrips(300, 2000), "tall but small: read whole at full size")
        assertNull(ocrStrips(12_000, 1080), "wide, not tall")
        assertNull(ocrStrips(4000, 3000), "a photo")
    }

    @Test
    fun aTallScreenshotIsCutIntoOverlappingFullWidthStrips() {
        val strips = ocrStrips(1080, 12_000)!!
        assertEquals(1, strips.sample)
        assertEquals(listOf(0 until 4096, 4032 until 8128, 8064 until 12_000), strips.bands)
        assertFalse(strips.cut)
    }

    @Test
    fun aStripNeverHoldsMorePixelsThanOneCappedImage() {
        // 1440 wide stays 1440 (half would be under the OCR width), so a strip has fewer rows.
        val wide = ocrStrips(1440, 12_000)!!
        assertEquals(1, wide.sample)
        assertEquals(listOf(0 until 3072, 3008 until 6080, 6016 until 9088, 9024 until 12_000), wide.bands)
        // 2160 wide decodes at half, so each strip spans twice the file's rows.
        val retina = ocrStrips(2160, 20_000)!!
        assertEquals(2, retina.sample)
        assertEquals(listOf(0 until 8192, 8064 until 16_256, 16_128 until 20_000), retina.bands)
        for (s in listOf(wide, retina)) {
            assertTrue(s.bands.all { (s.width / s.sample).toLong() * (it.last + 1 - it.first) / s.sample <= OCR_SHORT_PX.toLong() * MAX_PAGE_PX })
        }
    }

    @Test
    fun aVeryLongScreenshotStopsAtTheStripCapAndSaysSo() {
        val strips = ocrStrips(1080, 100_000)!!
        assertEquals(MAX_OCR_STRIPS, strips.bands.size)
        assertTrue(strips.cut)
    }

    @Test
    fun aLineBothStripsReadIsKeptOnce() {
        assertEquals("a\nb\nc\nd\ne", joinStrips(listOf("a\nb\nc", "c\nd", "", " e ")))
        assertEquals("a\nb", joinStrips(listOf("a", "b")))
        assertEquals("", joinStrips(listOf("", " ")))
    }
}
