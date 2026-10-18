package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocatorTest {
    @Test
    fun pagesRoundTrip() {
        for (n in listOf(1, 12, 1000)) assertEquals(n, Locator.pageOf(Locator.page(n)))
        assertEquals("p. 12", Locator.page(12))
    }

    @Test
    fun timesRoundTrip() {
        for (sec in listOf(0, 5, 225, 3599, 3723)) assertEquals(sec * 1000L, Locator.msOf(Locator.time(sec)))
        assertEquals("3:45", Locator.time(225))
        assertEquals("1:02:03", Locator.time(3723))
    }

    @Test
    fun malformedIsNull() {
        for (l in listOf("", "p. ", "p. 0", "p. x", "12", "p.12", "p. ١٢")) assertNull(Locator.pageOf(l), l)
        for (l in listOf("", "3", ":45", "3:", "a:45", "1:2:3:4", "١:٤٥", "p. 3")) assertNull(Locator.msOf(l), l)
    }
}
