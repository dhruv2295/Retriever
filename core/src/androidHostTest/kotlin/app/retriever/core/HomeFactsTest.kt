package app.retriever.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HomeFactsTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    private class Fx(files: List<SourceFile> = emptyList(), pieces: Map<String, List<Piece>> = emptyMap()) {
        val store = newStore(Signature.of(ModelVariant.M740.generic, PROFILE.visionTokens, PROFILE.maxTokens)).also { it.setProfile(PROFILE) }
        val source = FakeSource().apply { next = Changes(files, emptyList(), "tok", isFullScan = true) }
        val r = Retriever(
            store, source, FakeExtractor(pieces), FakeFactory(), FakeMonitor(), NoHasher, PIXEL, { true }, { true },
            { _, _ -> }, System::currentTimeMillis, { 0L },
        )

        suspend fun index() {
            r.refreshSetUp()
            r.indexBatch(1.minutes)
        }
    }

    private object NoHasher : ContentHasher {
        override suspend fun quickHash(uri: String, size: Long): String? = null
        override suspend fun fullHash(uri: String): String? = null
    }

    private fun test(body: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20.seconds) { body() } }

    @Test
    fun anOtherFileNewerThanEverythingAndStarredIsNeverShown() = test {
        val other = testFile("msgstore.crypt14", Kind.OTHER, modifiedAt = 99)
        val pdf = testFile("a.pdf", Kind.PDF, modifiedAt = 5)
        val f = Fx(
            listOf(
                other,
                pdf,
                testFile("p.jpg", Kind.PHOTO, modifiedAt = 4),
                testFile("v.mp4", Kind.VIDEO, modifiedAt = 3),
                testFile("s.png", Kind.SCREENSHOT, modifiedAt = 2),
            ),
        )
        f.index()
        f.r.setStarred(other.uri, true)
        f.r.setStarred(pdf.uri, true)

        val h = f.r.home()
        assertEquals(listOf("p.jpg", "v.mp4"), h.newMedia.map { it.name })
        assertEquals(listOf("s.png"), h.newShots.map { it.name })
        assertEquals(listOf("a.pdf"), h.newDocs.map { it.name })
        assertEquals(listOf("a.pdf"), h.starred.map { it.name })
        assertEquals(1L, h.starredCount)
        assertTrue(f.r.starredFiles().any { it.file.uri == other.uri }, "the full Starred list still holds it")
    }

    @Test
    fun onThisDayFindsTheRightYearCountAndCoverAcrossADstDay() = test {
        // 2023's day is 23 hours long (clocks went forward); the range is fixed, as the platform passes it.
        val from23 = 1_000 * day
        val to23 = from23 + 23 * hour - 1
        val from24 = 700 * day
        val to24 = from24 + day - 1
        val from25 = 300 * day
        val to25 = from25 + day - 1
        fun photo(n: String, at: Long, k: Kind = Kind.PHOTO) = testFile(n, k, modifiedAt = at).copy(takenAt = at)
        val f = Fx(
            listOf(
                photo("in1.jpg", from23),
                photo("in2.jpg", to23),
                photo("clip.mp4", from23 + hour, Kind.VIDEO),
                photo("next.jpg", to23 + 1),
                photo("old.jpg", 100 * day),
            ),
        )
        f.index()
        val days = listOf(Triple(2025, from25, to25), Triple(2024, from24, to24), Triple(2023, from23, to23))

        val m = f.r.onThisDay(days).single()
        assertEquals(2023, m.year)
        assertEquals(2L, m.photos)
        assertEquals(1L, m.videos)
        assertEquals("in2.jpg", m.cover.name, "the newest-synced photo covers the day")
    }

    @Test
    fun onThisDayIsEmptyWhenNothingFallsOnTheDay() = test {
        assertEquals(emptyList(), Fx().r.onThisDay(listOf(Triple(2024, 0L, day - 1))), "no media at all")
        val f = Fx(listOf(testFile("p.jpg", Kind.PHOTO, modifiedAt = 50 * day).copy(takenAt = 50 * day)))
        f.index()
        assertEquals(emptyList(), f.r.onThisDay(listOf(Triple(2024, 70 * day, 71 * day - 1), Triple(2023, 10 * day, 11 * day - 1))))
    }

    @Test
    fun nameOnlyCountTracksContentWithoutStateChanges() = test {
        val f = Fx(listOf(testFile("a.txt"), testFile("b.txt")))
        f.index()
        assertEquals(2L, f.r.home().nameOnly)

        // Gives a.txt words behind the cache's back, leaving every state count as it was.
        val a = f.store.fileByUri(testFile("a.txt").uri)!!
        f.store.replaceChunks(a.id, listOf(Store.NewChunk(1, "", "words", null)))
        assertEquals(1L, f.r.home().nameOnly, "usable content changes coverage even with unchanged indexing states")

        f.source.next = Changes(listOf(testFile("c.txt")), emptyList(), "tok2")
        f.r.indexBatch(1.minutes)
        assertEquals(2L, f.r.home().nameOnly, "a commit moved the counts: b.txt and c.txt, now that a.txt has words")
    }

    @Test
    fun fileTextAndFileCountReadTheIndex() = test {
        val f = Fx(listOf(testFile("a.txt"), testFile("b.txt")), mapOf("a.txt" to listOf(textPiece("first", "p1"), textPiece("second", "p2"))))
        f.index()
        val a = f.store.fileByUri(testFile("a.txt").uri)!!
        assertEquals(listOf("first", "second"), f.r.fileText(a.id).filter { it.locator.startsWith("p") }.map { it.text })
        assertEquals(2L, f.r.fileCount())
    }
}
