package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VideoMomentsTest {
    @Test
    fun momentsCoverTheVideoWithoutGaps() {
        val m = moments(120_000, 5, 10)
        assertEquals(8, m.size)
        assertEquals(0L, m.first().startMs)
        assertEquals(120_000L, m.last().endMs)
        m.zipWithNext { a, b -> assertEquals(a.endMs, b.startMs) }
        for (x in m) {
            assertEquals((x.startMs + x.endMs) / 2, x.frameMs)
            assertEquals(x.frameMs - 5_000, x.audioFromMs)
            assertEquals(x.frameMs + 5_000, x.audioToMs)
        }
    }

    @Test
    fun shortAndEmptyVideos() {
        val one = moments(9_000, 5, 10).single()
        assertEquals(0L, one.audioFromMs)
        assertEquals(9_000L, one.audioToMs)
        assertTrue(moments(0, 5, 10).isEmpty())
        assertTrue(moments(60_000, 0, 10).isEmpty())
        val silent = moments(9_000, 5, 0).single()
        assertEquals(silent.audioFromMs, silent.audioToMs)
    }

    @Test
    fun audioWindowsFitTheTokenWindow() {
        assertEquals(10, audioWindowSec(512))
        assertEquals(9, audioWindowSec(256))
        for (max in listOf(512, 256)) {
            assertTrue(estimateTokens(70, momentAudioSec(max, 70)) <= max, "max $max")
            assertTrue(estimateTokens(0, audioWindowSec(max)) <= max, "audio file $max")
        }
    }

    @Test
    fun partsFollowGalleryLayout() {
        val jpeg = byteArrayOf(1)
        val tav = momentParts(jpeg, 3_000, ShortArray(16))
        assertEquals(4, tav.size)
        assertEquals(Part.Text("00:03"), tav[0])
        assertIs<Part.Audio>(tav[1])
        assertEquals(Part.Text("00:03"), tav[2])
        assertIs<Part.Image>(tav[3])
        assertIs<Part.Image>(momentParts(jpeg, 3_000, null).single())
        assertIs<Part.Image>(momentParts(jpeg, 3_000, ShortArray(0)).single())
    }

    @Test
    fun timestamps() {
        assertEquals("00:03", modelTimestamp(3_000))
        assertEquals("1:02:03", modelTimestamp(3_723_000))
        assertEquals("3:45", timeLocator(225))
        assertEquals("1:02:03", timeLocator(3723))
        assertEquals("0:05", timeLocator(5))
    }

    @Test
    fun theFirstPassTriesTheMiddleMomentThenOutwards() {
        assertEquals(emptyList(), headOrder(0))
        assertEquals(listOf(0), headOrder(1))
        assertEquals(listOf(1, 0), headOrder(2))
        assertEquals(listOf(4, 3, 5, 2, 6, 1, 7, 0), headOrder(MAX_VIDEO_MOMENTS))
    }
}
