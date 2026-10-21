package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SamplePileTest {
    private fun sample(index: Int, read: Boolean) =
        SampleFile(index, SourceFile("f:$index", "$index.jpg", "Photos", "image/jpeg", Kind.PHOTO, 1, 1), read, null)

    /** Three small cards in a small footprint, so no private file fills the screen. */
    @Test fun aSlotFollowsTheIndexAndWrapsAtThree() {
        assertEquals(Triple(3, 96, 128), Triple(SamplePile.SHOWN, SamplePile.CARD_DP, SamplePile.FOOTPRINT_DP))
        assertEquals(Triple(0f, 0, 0), Triple(SamplePile.angle(0), SamplePile.dx(0), SamplePile.dy(0)))
        for (i in 0 until 30) {
            assertEquals(SamplePile.angle(i), SamplePile.angle(i + SamplePile.SHOWN))
            assertEquals(SamplePile.dx(i), SamplePile.dx(i + SamplePile.SHOWN))
            assertEquals(SamplePile.dy(i), SamplePile.dy(i + SamplePile.SHOWN))
        }
        assertNotEquals(SamplePile.angle(1), SamplePile.angle(2))
    }

    @Test fun shownKeepsTheLastThreeReadInPlanOrder() {
        val files = (0 until 30).map { sample(it, read = it % 3 != 2) }
        val shown = SamplePile.shown(files)
        assertEquals(files.filter { it.read }.takeLast(3).map { it.index }, shown.map { it.index })
        assertEquals(shown.map { it.index }.sorted(), shown.map { it.index })
        assertEquals(emptyList(), SamplePile.shown(files.map { it.copy(read = false) }))
    }
}
