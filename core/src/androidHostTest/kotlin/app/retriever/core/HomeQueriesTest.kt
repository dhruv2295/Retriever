package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeQueriesTest {
    private val store = newStore()

    private fun add(vararg files: SourceFile): List<Long> {
        store.upsertFiles(files.toList())
        return files.map { store.fileByUri(it.uri)!!.id }
    }

    @Test
    fun anOtherStarIsHiddenFromHomeButKeptInTheStarredList() {
        val files = arrayOf(testFile("a.pdf", Kind.PDF, 10), testFile("x.bin", Kind.OTHER, 99))
        add(*files)
        store.setStarred(files[0].uri, true, at = 1)
        store.setStarred(files[1].uri, true, at = 2)
        assertEquals(listOf("a.pdf"), store.starredVisible(10).map { it.file.name })
        assertEquals(1L, store.starredVisibleCount())
        assertEquals(listOf("x.bin", "a.pdf"), store.starredFiles(10).map { it.file.name })
    }

    @Test
    fun onThisDayCountsPhotosAndVideosAndPicksAPhotoCover() {
        val (photo, _, _) = add(
            testFile("p.jpg", Kind.PHOTO, 1, takenAt = 100),
            testFile("v.mp4", Kind.VIDEO, 1, takenAt = 150),
            testFile("late.mp4", Kind.VIDEO, 1, takenAt = 900),
        )
        add(testFile("undated.jpg", Kind.PHOTO, 120))
        val span = store.mediaTakenBetween(50, 200)
        assertEquals(Store.MediaSpan(2, 1, photo), span)
        assertEquals(100L, store.mediaTakenSince())
        assertEquals(listOf("p.jpg", "v.mp4"), store.filesTakenBetween(50, 200, 10).map { it.file.name })
        assertEquals(Store.MediaSpan(0, 0, null), store.mediaTakenBetween(300, 400))
    }

    @Test
    fun newestIsByCaptureTime() {
        add(testFile("old-capture.jpg", Kind.PHOTO, 500, takenAt = 10), testFile("new.jpg", Kind.PHOTO, 100, takenAt = 200))
        assertEquals(listOf("new.jpg", "old-capture.jpg"), store.newestFiles(setOf(Kind.PHOTO), 10).map { it.file.name })
    }

    @Test
    fun firstRunAndEtaQueriesKeepToTheirScope() {
        val (a, b, c) = add(testFile("a.pdf", Kind.PDF, size = 10), testFile("b.pdf", Kind.PDF, size = 20), testFile("c.txt", size = 40))
        store.replaceChunks(b, listOf(Store.NewChunk(1, "", "tail b", null)))
        store.replaceChunks(c, listOf(Store.NewChunk(1, "", "tail c", null)))
        assertEquals(listOf(a), store.pendingIn(listOf(a, b)).map { it.id })
        assertEquals(listOf("tail b"), store.tailChunksIn(listOf(a, b)).map { it.text })
        assertEquals(10L, store.pendingBytes(Kind.PDF))
        assertEquals(0L, store.pendingBytes(Kind.DOC))
        assertEquals(listOf("tail c"), store.chunkTexts(c).map { it.text })
        assertNull(store.filesByUris(listOf("nope"))["nope"])
        assertEquals(b, store.filesByUris(listOf(testFile("b.pdf").uri)).values.single().id)
    }

    @Test
    fun nameSearchFiltersKindsBeforeTheCap() {
        val cap = Store.FTS_CAP.toInt()
        val files = listOf(testFile("lease.pdf", Kind.PDF)) + List(cap + 1) { testFile("lease$it.txt") }
        store.inTransaction { store.upsertFiles(files) }
        val pdf = store.fileByUri(files[0].uri)!!.id
        assertEquals(listOf(pdf), store.fileKeywordSearch("lease", 10, setOf(Kind.PDF)))
        assertEquals(emptyList(), store.fileKeywordSearch("lease", 10, setOf(Kind.PDF), dates = 5L..9L))
    }
}
