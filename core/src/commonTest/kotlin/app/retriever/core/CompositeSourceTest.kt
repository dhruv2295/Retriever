package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SAF = "content://com.android.externalstorage.documents/"

class CompositeSourceTest {
    /** Lists [files] in full on every call when [alwaysFull]; otherwise reports no change after the first scan. */
    private class ListSource(val token: String, var files: List<String>, val alwaysFull: Boolean) : FileSource {
        val seen = mutableListOf<String?>()

        override suspend fun changes(since: String?): Changes {
            seen += since
            val all = files.map { SourceFile(it, it.substringAfterLast('/'), "", "image/jpeg", Kind.PHOTO, 10, 1) }
            return if (since == null || alwaysFull) {
                Changes(all, emptyList(), token, isFullScan = true)
            } else {
                Changes(emptyList(), emptyList(), token)
            }
        }
    }

    private fun sources(): Triple<ListSource, ListSource, CompositeSource> {
        val photos = ListSource("p1", listOf("ph://1", "ph://2"), alwaysFull = false)
        val folders = ListSource("f1", listOf("folder://a", "folder://b"), alwaysFull = true)
        return Triple(photos, folders, CompositeSource(mapOf("ph://" to photos, "folder://" to folders)))
    }

    @Test
    fun eachChildGetsItsOwnTokenBack() = runTest {
        val (photos, folders, composite) = sources()
        val first = composite.changes(null)
        composite.changes(first.nextToken)
        assertEquals(listOf(null, "p1"), photos.seen)
        assertEquals(listOf(null, "f1"), folders.seen)
        composite.changes("not json")
        assertNull(photos.seen.last())
        assertEquals(2, composite.rulesVersion)
    }

    @Test
    fun aUriOutsideItsPrefixFailsLoudly() = runTest {
        val photos = ListSource("p", listOf("folder://x"), alwaysFull = false)
        assertFailsWith<IllegalArgumentException> { CompositeSource(mapOf("ph://" to photos)).changes(null) }
    }

    /** Like FolderSource: always full, scoped to the folders that resolved, placeholders reported as present. */
    private class FolderLike(var files: List<String>, var placeholders: Set<String> = emptySet(), var scope: Set<String>? = null) : FileSource {
        override suspend fun changes(since: String?): Changes {
            val all = (files - placeholders).map { SourceFile(it, it.substringAfterLast('/'), "", "application/pdf", Kind.PDF, 10, 1) }
            return Changes(all, emptyList(), "f", isFullScan = true, present = placeholders.ifEmpty { null }, scope = scope)
        }
    }

    @Test
    fun anEvictedFileAndAnUnreachableFolderKeepTheirIndex() = runTest {
        val folders = FolderLike(listOf("folder://a/1.pdf", "folder://a/2.pdf", "folder://b/3.pdf"))
        val photos = ListSource("p", listOf("ph://1"), alwaysFull = false)
        val store = Store(testDriver())
        val sync = LibrarySync(store, CompositeSource(mapOf("ph://" to photos, "folder://" to folders))) { 1_000L }
        sync.sync()

        // iOS evicted 2.pdf to iCloud, and folder b's bookmark didn't resolve.
        folders.placeholders = setOf("folder://a/2.pdf")
        folders.files = listOf("folder://a/1.pdf", "folder://a/2.pdf")
        folders.scope = setOf("folder://a/")
        val all = setOf("ph://1", "folder://a/1.pdf", "folder://a/2.pdf", "folder://b/3.pdf")
        sync.sync()
        assertEquals(all, store.knownUris())
        sync.sync(forceFull = true)
        assertEquals(all, store.knownUris())

        // Photos not allowed: an empty scope wipes nothing, even on a full scan.
        val noPhotos = object : FileSource {
            override suspend fun changes(since: String?) = Changes(emptyList(), emptyList(), "", isFullScan = since == null, scope = emptySet())
        }
        LibrarySync(store, CompositeSource(mapOf("ph://" to noPhotos, "folder://" to folders))) { 1_000L }.sync(forceFull = true)
        assertTrue("ph://1" in store.knownUris())

        // Every folder resolves again and b was removed: the whole prefix is in scope, so b's file goes.
        folders.scope = null
        sync.sync()
        assertEquals(all - "folder://b/3.pdf", store.knownUris())
    }

    /** Like Android's SafSource: always full, the whole prefix in scope unless a tree couldn't be read. */
    private class SafLike(var files: List<String>, var scope: Set<String> = setOf(SAF)) : FileSource {
        override suspend fun changes(since: String?): Changes {
            val all = files.map { SourceFile(it, it.substringAfterLast('/'), "Documents", "application/pdf", Kind.PDF, 10, 1) }
            return Changes(all, emptyList(), "", isFullScan = true, scope = scope)
        }
    }

    @Test
    fun pickedFoldersSitBesideMediaStoreAndGoWhenAllFilesTakesOver() = runTest {
        val photo = "content://media/external_primary/file/1"
        val media = ListSource("m", listOf(photo), alwaysFull = false)
        val tree = "${SAF}tree/primary%3ADocuments/document/"
        val card = "${SAF}tree/1A2B%3APapers/document/"
        val onCard = "${card}1A2B%3APapers%2Fb.pdf"
        val saf = SafLike(listOf("${tree}primary%3ADocuments%2Fa.pdf", onCard))
        val store = Store(testDriver())
        val sync = LibrarySync(store, CompositeSource(mapOf("content://media/" to media, SAF to saf))) { 1_000L }
        sync.sync()
        assertEquals(setOf(photo) + saf.files, store.knownUris())

        // The card is out: only the tree that was read is in scope, so the card's file keeps its index.
        saf.files = listOf("${tree}primary%3ADocuments%2Fa.pdf")
        saf.scope = setOf(tree)
        sync.sync()
        assertTrue(onCard in store.knownUris())

        // All-files access: no trees and the prefix in scope, so every picked-folder row goes and MediaStore's stay.
        saf.files = emptyList()
        saf.scope = setOf(SAF)
        sync.sync()
        assertEquals(setOf(photo), store.knownUris())
    }

    @Test
    fun anIncrementalPhotosSyncKeepsPhotosWhileFoldersDropVanishedFiles() = runTest {
        val (photos, folders, composite) = sources()
        val store = Store(testDriver())
        val sync = LibrarySync(store, composite) { 1_000L }
        sync.sync()
        assertEquals(setOf("ph://1", "ph://2", "folder://a", "folder://b"), store.knownUris())

        photos.files = listOf("ph://1")
        folders.files = listOf("folder://a")
        sync.sync()
        assertEquals(setOf("ph://1", "ph://2", "folder://a"), store.knownUris())

        sync.sync(forceFull = true)
        assertEquals(setOf("ph://1", "folder://a"), store.knownUris())
    }
}
