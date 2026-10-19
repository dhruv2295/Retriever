package app.retriever.viewer

import app.retriever.core.Kind
import app.retriever.core.SourceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerQueueTest {
    private fun file(name: String, kind: Kind) = SourceFile("file:///$name", name, "Docs", "application/octet-stream", kind, 1, 1)

    private val list = listOf(
        "a.pdf" to Kind.PDF,
        "b.jpg" to Kind.PHOTO,
        "c.bin" to Kind.OTHER,
        "d.pdf" to Kind.PDF,
        "e.mp4" to Kind.VIDEO,
        "f.m4a" to Kind.AUDIO,
    ).map { (name, kind) -> file(name, kind) }

    @Test fun thePhotoViewerSaysWhereYouAre() {
        assertEquals("3 of 5", positionLabel(2, 5))
        assertNull(positionLabel(0, 1))
    }

    @Test fun neighboursStayWithinTheSameKindOfViewerAndStopAtTheEnds() {
        val queue = ViewerQueue().apply { set(list) }
        queue.neighbors("file:///a.pdf").let {
            assertNull(it.previous)
            assertEquals("d.pdf", it.next?.name)
            assertEquals("1 of 2", it.label)
        }
        queue.neighbors("file:///d.pdf").let {
            assertEquals("a.pdf", it.previous?.name)
            assertNull(it.next)
        }
        queue.neighbors("file:///f.m4a").let {
            assertEquals("e.mp4", it.previous?.name)
            assertEquals("2 of 2", it.label)
        }
    }

    @Test fun aLoneFileOfItsKindHasNoStepBar() {
        val none = ViewerQueue().apply { set(list) }.neighbors("file:///b.jpg")
        assertTrue(none.previous == null && none.next == null && none.label == null)
    }

    @Test fun aFileNotInTheListHasNoNeighbours() {
        val none = ViewerQueue().apply { set(list) }.neighbors("file:///elsewhere.pdf")
        assertTrue(none.previous == null && none.next == null)
    }

    @Test fun theBarKeepsStarOnlyOffCompactWidths() {
        assertTrue(starFitsInBar(840))
        assertTrue(!starFitsInBar(411))
    }
}
