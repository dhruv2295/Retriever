package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameIndexTest {
    private val index = NameIndex.build(
        listOf(
            NameEntry(1, "myinvoice2024.pdf", "Documents", Kind.PDF),
            NameEntry(2, "Screenshot_2024-03-15-12-00-00.png", "Pictures/Screenshots", Kind.SCREENSHOT),
            NameEntry(3, "बिजली बिल.pdf", "Downloads", Kind.PDF),
            NameEntry(4, "Café menu.jpg", "Pictures", Kind.PHOTO),
            NameEntry(5, "holiday.jpg", "Pictures", Kind.PHOTO),
        ),
    )

    private fun classOf(q: String, id: Long, kinds: Set<Kind>? = null) = index.search(q, kinds).firstOrNull { it.id == id }?.match

    @Test fun substringInsideAToken() = assertEquals(MatchClass.SUBSTRING, classOf("invoice", 1))

    @Test fun typoFindsTheFile() = assertEquals(MatchClass.TYPO, classOf("screnshot", 2))

    @Test fun prefixWhileTyping() = assertEquals(MatchClass.PREFIX, classOf("scr", 2))

    @Test fun exactTokenInAnyScript() {
        assertEquals(MatchClass.EXACT, classOf("बिजली", 3))
        assertEquals(MatchClass.EXACT, classOf("pdf", 1))
    }

    @Test fun accentsFold() {
        assertEquals(MatchClass.EXACT, classOf("cafe", 4))
        assertEquals(MatchClass.EXACT, classOf("café", 4))
        assertEquals("cafe", foldLatin("Café"))
        assertEquals("बिजली", foldLatin("बिजली"))
    }

    @Test fun folderWordsMatchToo() = assertEquals(MatchClass.EXACT, classOf("documents", 1))

    @Test fun everyWordMustMatchAndTheWorstClassWins() {
        assertEquals(MatchClass.PREFIX, classOf("holiday jp", 5))
        assertEquals(emptyList(), index.search("holiday invoice"))
    }

    @Test fun kindFilterAndOrder() {
        assertEquals(listOf(5L), index.search("jpg", setOf(Kind.PHOTO)).map { it.id }.filter { it == 5L })
        assertFalse(index.search("pdf", setOf(Kind.PHOTO)).isNotEmpty())
        val ranked = index.search("scr")
        assertEquals(ranked.sortedBy { it.match }, ranked)
    }

    @Test fun noTyposForVeryShortWords() = assertEquals(emptyList(), index.search("jx"))

    @Test fun copyOnWriteSnapshots() {
        val added = index.update(emptyList(), listOf(NameEntry(6, "passport scan.jpg", "Pictures", Kind.PHOTO), NameEntry(5, "beach.jpg", "Pictures", Kind.PHOTO)))
        assertTrue(added.search("passport").isNotEmpty())
        assertTrue(added.search("holiday").isEmpty())
        assertTrue(added.search("beach").isNotEmpty())
        assertTrue(index.search("passport").isEmpty())
        val removed = added.update(listOf(1L, 2L), emptyList())
        assertEquals(4, removed.size)
        assertTrue(removed.search("invoice").isEmpty())
        assertTrue(index.search("invoice").isNotEmpty())
    }

    @Test fun tokenizerMatchesTheStoreSplit() {
        for (s in listOf("Screenshot_2024-03-15.png", "बिजली बिल.pdf", "Café  menu (2)", "a/b\\c d", "ΑΒΓ-δ", "no.dots,here;ok", "")) {
            val got = ArrayList<String>().also { NameIndex.tokenize(s) { t -> it += t } }
            assertEquals(Store.ftsTokens(s).map(::foldLatin), got, s)
        }
    }

    @Test fun editDistanceBounds() {
        assertTrue(NameIndex.withinEdits("screnshot", "screenshot", 1))
        assertFalse(NameIndex.withinEdits("scrnshot", "screenshot", 1))
        assertTrue(NameIndex.withinEdits("scrnshot", "screenshot", 2))
        assertFalse(NameIndex.withinEdits("abc", "xyzw", 2))
    }

    @Test fun newestFirstWithinAClassEvenAfterAnUpdate() {
        val shots = NameIndex.build((1..300L).map { NameEntry(it, "Screenshot_$it.png", "Screenshots", Kind.SCREENSHOT, at = it) })
        assertEquals(listOf(300L, 299L), shots.search("screenshot", limit = 2).map { it.id })
        // A file added by a later sync is the newest, so it comes first, not last.
        val later = shots.update(emptyList(), listOf(NameEntry(301, "Screenshot_301.png", "Screenshots", Kind.SCREENSHOT, at = 301)))
        assertEquals(301L, later.search("screenshot", limit = 1).single().id)
    }

    @Test fun admitRunsBeforeTheCap() {
        val many = NameIndex.build((1..300L).map { NameEntry(it, "invoice $it.pdf", "Documents", Kind.PDF, at = it) })
        // The one admitted file is far past the first 100 matches, and is still found.
        assertEquals(listOf(7L), many.search("invoice", limit = 100) { it.id == 7L }.map { it.id })
        assertEquals(listOf(12L, 11L, 10L), many.search("invoice", limit = 100) { it.at in 10L..12L }.map { it.id })
    }

    @Test fun emptyIndexAndBlankQuery() {
        assertTrue(NameIndex.EMPTY.search("anything").isEmpty())
        assertTrue(index.search("  ").isEmpty())
    }
}
