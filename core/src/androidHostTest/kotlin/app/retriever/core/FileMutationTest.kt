package app.retriever.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class FileMutationTest {
    @Test fun renamedFileKeepsItsIdStarAndRecentEntryAndDeletionRemovesThem() = runBlocking {
        val store = newStore()
        store.setProfile(PROFILE)
        val file = testFile("before.txt")
        val retriever = testRetriever(store, listOf(file), FakeExtractor(mapOf(file.name to listOf(textPiece("electricity bill")))))
        retriever.refreshSetUp()
        retriever.indexBatch(1.minutes)
        val id = assertNotNull(retriever.storedFile(file.uri)).id
        retriever.setStarred(file.uri, true)
        retriever.dismissCaughtUp()
        assertTrue(retriever.prefs.value.caughtUpDismissed)
        val changed = assertNotNull(retriever.renameFile(file.uri) { it.copy(uri = "content://test/after.txt", name = "after.txt") })
        assertEquals(id, retriever.storedFile(changed.uri)?.id)
        assertEquals(setOf(changed.uri), retriever.starred.value)
        assertEquals(changed, retriever.state.value.recentFiles.single())
        assertTrue(retriever.prefs.value.caughtUpDismissed, "renaming doesn't start another read")
        retriever.deletedFiles(listOf(changed.uri))
        assertNull(retriever.storedFile(changed.uri))
        assertTrue(retriever.state.value.recentFiles.isEmpty())
        assertTrue(retriever.starred.value.isEmpty())
    }

    @Test fun failedPlatformRenameLeavesTheIndexAndStarsAlone() = runBlocking {
        val store = newStore()
        val file = testFile("before.txt")
        store.upsertFiles(listOf(file))
        val retriever = testRetriever(store, emptyList(), FakeExtractor())
        retriever.setStarred(file.uri, true)
        assertNull(retriever.renameFile(file.uri) { null })
        assertEquals(file, retriever.storedFile(file.uri)?.file)
        assertEquals(setOf(file.uri), retriever.starred.value)
        assertEquals(0L, retriever.fileRevision.value)
    }

    @Test fun onlyRealReadingRearmsTheDismissedCompletion() = runBlocking {
        val store = newStore()
        store.setProfile(PROFILE)
        val file = testFile("new.txt")
        val retriever = testRetriever(store, listOf(file), FakeExtractor(mapOf(file.name to listOf(textPiece("new content")))))
        retriever.refreshSetUp()
        retriever.dismissCaughtUp()
        retriever.indexBatch(1.minutes)
        assertFalse(retriever.prefs.value.caughtUpDismissed)
        retriever.dismissCaughtUp()
        retriever.indexBatch(1.minutes)
        assertTrue(retriever.prefs.value.caughtUpDismissed, "an empty scan does not rearm the banner")
    }
}
