package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoMomentsTest {
    @Test
    fun momentsCoverTheVideoWithoutGaps() {
        val m = moments(120_000, 5)
        assertEquals(8, m.size)
        assertEquals(0L, m.first().startMs)
        assertEquals(120_000L, m.last().endMs)
        m.zipWithNext { a, b -> assertEquals(a.endMs, b.startMs) }
        for (x in m) {
            assertEquals((x.startMs + x.endMs) / 2, x.frameMs)
        }
    }

    @Test
    fun shortAndEmptyVideos() {
        assertEquals(Moment(0, 9_000, 4_500), moments(9_000, 5).single())
        assertTrue(moments(0, 5).isEmpty())
        assertTrue(moments(60_000, 0).isEmpty())
    }

    @Test
    fun audioWindowsFitTheTokenWindow() {
        assertEquals(10, audioWindowSec(512))
        assertEquals(9, audioWindowSec(256))
        for (max in listOf(512, 256)) {
            assertTrue(audioWindowSec(max) * AUDIO_TOKENS_PER_SEC + MARKER_TOKENS + SEQUENCE_TOKENS <= max, "audio file $max")
        }
    }

    @Test
    fun timestamps() {
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
