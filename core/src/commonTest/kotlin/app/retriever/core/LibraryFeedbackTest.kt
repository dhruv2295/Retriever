package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryFeedbackTest {
    private fun file(kind: Kind) = SourceFile("file:///$kind", "file", "Library", "text/plain", kind, 50, 1)

    @Test fun syntheticPhotoKeywordsNeverBecomeASnippet() = runTest {
        val store = Store(testDriver())
        val photo = file(Kind.PHOTO)
        store.upsertFiles(listOf(photo))
        val id = store.pending(1).single().id
        val piece = Piece("", listOf(Part.Text("sunset photo metadata score 0.9")), "sunset photo metadata score 0.9")
        val draft = draftChunks<Vector>(photo, listOf(piece), embed = null).single()
        store.replaceChunks(id, listOf(Store.NewChunk(draft.ordinal, draft.locator, draft.text, draft.vector, draft.contentText)))
        val hit = SearchEngine(store, NoModel).search("sunset", SearchEngine.Mode.KEYWORD).hits.single()
        assertNull(hit.snippet)
        assertTrue(store.chunkTexts(id).isEmpty())
    }

    @Test fun screenshotSnippetUsesOnlyOcrAndHighlightsTheQuery() = runTest {
        contentHit(Kind.SCREENSHOT, imageWithText("", byteArrayOf(1), "The wifi password is retriever.", 1000), "wifi")
    }

    @Test fun documentSnippetUsesOnlyDocumentTextAndHighlightsTheQuery() = runTest {
        contentHit(Kind.DOC, textPieces("", "Your electricity bill is ready to pay. The amount is due on the first of November.", 1000), "electricity")
    }

    @Test fun photoWithOcrCanShowItsOwnWords() = runTest {
        contentHit(Kind.PHOTO, imageWithText("", byteArrayOf(1), "The sign says welcome home.", 1000), "welcome")
    }

    private suspend fun contentHit(kind: Kind, pieces: List<Piece>, query: String) {
        val store = Store(testDriver())
        val source = file(kind)
        store.upsertFiles(listOf(source))
        val id = store.pending(1).single().id
        val chunks = draftChunks<Vector>(source, pieces, embed = null)
        store.replaceChunks(id, chunks.map { Store.NewChunk(it.ordinal, it.locator, it.text, it.vector, it.contentText) })
        val snippet = assertNotNull(SearchEngine(store, NoModel).search(query, SearchEngine.Mode.KEYWORD).hits.single().snippet)
        assertTrue("\u0002$query\u0003" in snippet, snippet)
    }

    @Test fun splittingIndexTextDoesNotPromoteMetadataToContent() {
        val store = Store(testDriver())
        val source = file(Kind.DOC)
        store.upsertFiles(listOf(source))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", "metadata score 0.9 actual words", null, "actual words")))
        store.splitTailChunk(store.chunkTexts(id).single().id, listOf("metadata score 0.9", "actual words"))
        assertTrue(store.chunkTexts(id).none { it.text?.contains("metadata") == true })
    }

    @Test fun dismissalPersistsUntilAnotherReadStartsAndDoesNotResetBetweenBatches() {
        val store = Store(testDriver())
        val dismissed = store.updatePrefs { it.dismissCaughtUp() }
        assertTrue(dismissed.caughtUpDismissed)
        assertTrue(store.prefs().caughtUpDismissed)
        val reading = store.updatePrefs { it.readingStarted() }
        assertFalse(reading.caughtUpDismissed)
        assertEquals(reading, reading.readingStarted())
        assertTrue(reading.dismissCaughtUp().caughtUpDismissed)
        assertEquals(2L, reading.dismissCaughtUp().readingStarted().readingCycle)
    }

    @Test fun renameKeepsIdentityContentReadStateAndStarThenDeleteRemovesSearchHits() = runTest {
        val store = Store(testDriver())
        val original = file(Kind.DOC)
        store.upsertFiles(listOf(original))
        val id = store.pending(1).single().id
        store.replaceChunks(id, listOf(Store.NewChunk(1, "", "electricity receipt", null)), partial = true)
        store.setStarred(original.uri, true, 123)
        val changed = assertNotNull(store.renameFile(original.uri, "bill.txt", "file:///bill.txt", 2))
        assertEquals(id, store.fileByUri(changed.uri)?.id)
        assertEquals(FileState.INDEXED, store.fileByUri(changed.uri)?.state)
        assertTrue(store.fileByUri(changed.uri)?.partial == true)
        assertEquals(setOf(changed.uri), store.starredUris())
        assertEquals(listOf(id), store.fileKeywordSearch("bill", 10))
        assertTrue(store.fileKeywordSearch("file", 10).isEmpty())
        assertEquals("electricity receipt", store.chunkTexts(id).single().text)
        store.removeFiles(listOf(changed.uri))
        assertTrue(store.keywordSearch("electricity", 10).isEmpty())
        assertTrue(store.starredUris().isEmpty())
    }

    @Test fun textTilesUseOnlyTheirOwnContentAndInvalidateAfterEdits() {
        val store = Store(testDriver())
        val first = file(Kind.DOC)
        val second = first.copy(uri = "file:///second", name = "second.xlsx")
        store.upsertFiles(listOf(first, second))
        val firstId = store.fileByUri(first.uri)!!.id
        val secondId = store.fileByUri(second.uri)!!.id
        store.replaceChunks(firstId, listOf(Store.NewChunk(0, "", "hidden metadata", null, null), Store.NewChunk(1, "", "First file prose", null)))
        store.replaceChunks(secondId, listOf(Store.NewChunk(1, "", "Second file cells", null)))
        assertEquals("First file prose", store.textPreview(first))
        assertEquals("Second file cells", store.textPreview(second))
        assertNull(store.textPreview(first.copy(modifiedAt = 2)))
        store.removeFiles(listOf(first.uri))
        assertNull(store.textPreview(first))
    }

    @Test fun folderCountsEqualBrowseAndIncludeEveryKind() {
        val store = Store(testDriver())
        store.upsertFiles(Kind.entries.map { file(it) })
        val (folder, count) = store.folders().single()
        assertEquals(Kind.entries.size.toLong(), count)
        assertEquals(count, store.files(folder = folder).size.toLong())
    }
}
