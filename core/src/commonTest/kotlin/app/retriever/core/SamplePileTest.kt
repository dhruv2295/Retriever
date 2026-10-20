package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SamplePileTest {
    private fun sample(index: Int, read: Boolean) =
        SampleFile(index, SourceFile("f:$index", "$index.jpg", "Photos", "image/jpeg", Kind.PHOTO, 1, 1), read, null)

    @Test fun aSlotFollowsTheIndexAndWrapsAtTwelve() {
        assertEquals(Triple(0f, 0, 0), Triple(SamplePile.angle(0), SamplePile.dx(0), SamplePile.dy(0)))
        assertEquals(Triple(4f, 9, -7), Triple(SamplePile.angle(1), SamplePile.dx(1), SamplePile.dy(1)))
        for (i in 0 until 30) {
            assertEquals(SamplePile.angle(i), SamplePile.angle(i + SamplePile.SHOWN))
            assertEquals(SamplePile.dx(i), SamplePile.dx(i + SamplePile.SHOWN))
            assertEquals(SamplePile.dy(i), SamplePile.dy(i + SamplePile.SHOWN))
        }
        assertNotEquals(SamplePile.angle(11), SamplePile.angle(12))
    }

    @Test fun shownKeepsTheLastTwelveReadInPlanOrder() {
        val files = (0 until 30).map { sample(it, read = it % 3 != 2) }
        val shown = SamplePile.shown(files)
        assertEquals(SamplePile.SHOWN, shown.size)
        assertEquals(files.filter { it.read }.takeLast(12).map { it.index }, shown.map { it.index })
        assertEquals(shown.map { it.index }.sorted(), shown.map { it.index })
        assertEquals(emptyList(), SamplePile.shown(files.map { it.copy(read = false) }))
    }
}
