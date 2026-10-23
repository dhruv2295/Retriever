package app.retriever.core

import kotlin.random.Random
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

    @Test fun aNonPositiveLimitReturnsNoHits() {
        for (limit in listOf(0, -1)) {
            assertEquals(emptyList(), index.search("pdf", limit = limit))
        }
        assertEquals(1, index.search("pdf", limit = 1).size)
    }

    @Test fun typoFindsTheFile() = assertEquals(MatchClass.TYPO, classOf("screnshot", 2))

    @Test fun adjacentSwapIsOneTypo() {
        val talk = NameIndex.build(listOf(NameEntry(1, "talk notes.txt", "Documents", Kind.DOC)))
        assertEquals(MatchClass.TYPO, talk.search("tlak").single().match)
        assertTrue(NameIndex.withinEdits("tlak", "talk", 1))
    }

    @Test fun longWordWithASwapAndATypo() {
        // A swap spoils 4 trigrams, not 3: this pair shares only 2 of 9, and is 2 OSA edits apart.
        val cert = NameIndex.build(listOf(NameEntry(1, "certificate.pdf", "Documents", Kind.PDF)))
        assertEquals(MatchClass.TYPO, cert.search("cetrifixate").single().match)
    }

    @Test fun exactNameIsTheNameNotTheFolder() {
        val cam = NameIndex.build(listOf(NameEntry(1, "camera.jpg", "Pictures", Kind.PHOTO), NameEntry(2, "IMG_1.jpg", "DCIM/Camera", Kind.PHOTO)))
        assertEquals(mapOf(1L to true, 2L to false), cam.search("camera").associate { it.id to it.exactName })
        assertTrue(index.search("holiday jp").none { it.exactName })
    }

    @Test fun aMatchFoundOnlyByDroppingAWordIsNoExactName() {
        val docs = NameIndex.build(
            listOf(
                NameEntry(1, "march invoice.pdf", "Documents", Kind.PDF),
                NameEntry(2, "acme report.pdf", "Documents", Kind.PDF),
                NameEntry(3, "acme letter.pdf", "Documents", Kind.PDF),
            ),
        )
        // No name holds all three words; "acme" (in the most names) is dropped and "march invoice" matches.
        assertEquals(listOf(NameHit(1, MatchClass.EXACT, exactName = false)), docs.search("acme invoice march"))
        assertTrue(docs.search("invoice march").single().exactName)
    }

    @Test fun prefixWhileTyping() = assertEquals(MatchClass.PREFIX, classOf("scr", 2))

    /** A CJK name is one token, and its words are two characters: one inside it is a substring match. */
    @Test fun aTwoCharacterCjkWordInsideAName() {
        val names = NameIndex.build(listOf(NameEntry(1, "会議資料.pdf", "書類", Kind.PDF), NameEntry(2, "資料.pdf", "", Kind.PDF)))
        assertEquals(listOf(2L to MatchClass.EXACT, 1L to MatchClass.SUBSTRING), names.search("資料").map { it.id to it.match })
        assertEquals(listOf(1L to MatchClass.PREFIX), names.search("会議").map { it.id to it.match })
        assertEquals(emptyList(), names.search("ab"), "two Latin letters are still too few for a substring")
    }

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
        assertEquals(emptyList(), index.search("qqqq xxxx")) // no word matches: nothing to retry with
        assertEquals(listOf(1L), index.search("pictures invoice").map { it.id }) // else the most common word is dropped
    }

    @Test fun kindFilterAndOrder() {
        assertEquals(listOf(5L), index.search("jpg", setOf(Kind.PHOTO)).map { it.id }.filter { it == 5L })
        assertFalse(index.search("pdf", setOf(Kind.PHOTO)).isNotEmpty())
        val ranked = index.search("scr")
        assertEquals(ranked.sortedBy { it.match }, ranked)
    }

    @Test fun noTyposForVeryShortWords() = assertEquals(emptyList(), index.search("jx"))

    @Test fun copyOnWriteSnapshots() {
        val added = index.update(
            emptyList(),
            listOf(NameEntry(6, "passport scan.jpg", "Pictures", Kind.PHOTO), NameEntry(5, "beach.jpg", "Pictures", Kind.PHOTO)),
        )
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

    /** The typo pass's prefilters (length, letter set, shared trigrams) drop only tokens that no edit budget reaches. */
    @Test fun typoHitsEqualABruteForceScan() {
        val rnd = Random(3)
        val letters = "abdeilmnorstu"
        fun word(n: Int) = CharArray(n) { letters[rnd.nextInt(letters.length)] }.concatToString()
        fun edited(w: String): String {
            val i = rnd.nextInt(w.length)
            return when (rnd.nextInt(4)) {
                0 -> w.substring(0, i) + word(1) + w.substring(i + 1)
                1 -> w.substring(0, i) + word(1) + w.substring(i)
                2 -> w.removeRange(i, i + 1)
                else -> if (i + 1 < w.length) w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) else w
            }
        }
        val entries = List(3_000) { NameEntry(it.toLong(), word(rnd.nextInt(3, 15)), "", Kind.DOC) }
        val names = NameIndex.build(entries)
        var checked = 0
        repeat(600) {
            val q = edited(edited(entries[rnd.nextInt(entries.size)].name)).takeIf { it.length >= 3 } ?: return@repeat
            val hits = names.search(q, limit = Int.MAX_VALUE)
            // Typos run only when nothing closer matched.
            if (hits.any { it.match != MatchClass.TYPO }) return@repeat
            val edits = if (q.length >= 6) 2 else 1
            assertEquals(entries.filter { NameIndex.withinEdits(q, it.name, edits) }.map { it.id }.toSet(), hits.map { it.id }.toSet(), q)
            checked++
        }
        assertTrue(checked > 300, "$checked typo queries")
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
