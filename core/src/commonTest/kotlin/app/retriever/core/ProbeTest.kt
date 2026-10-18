package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProbeTest {
    @Test
    fun nonFinite() {
        assertTrue(hasNonFinite(floatArrayOf(0.1f, Float.NaN)))
        assertTrue(hasNonFinite(floatArrayOf(Float.POSITIVE_INFINITY)))
        assertTrue(hasNonFinite(floatArrayOf(Float.NEGATIVE_INFINITY, 0f)))
        assertFalse(hasNonFinite(floatArrayOf(0.1f, -0.2f, 0f)))
    }

    @Test
    fun probeKind() {
        val img = Part.Image(byteArrayOf(1))
        val audio = Part.Audio(ShortArray(1))
        assertEquals(Modality.IMAGE, probeKindFor(listOf(img, Part.Text("t"))))
        assertEquals(Modality.AUDIO, probeKindFor(listOf(audio)))
        assertEquals(Modality.AUDIO, probeKindFor(listOf(img, audio)))
        assertEquals(Modality.TEXT, probeKindFor(listOf(Part.Text("t"))))
    }
}
