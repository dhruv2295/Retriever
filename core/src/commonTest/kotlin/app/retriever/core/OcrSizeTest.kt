package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
