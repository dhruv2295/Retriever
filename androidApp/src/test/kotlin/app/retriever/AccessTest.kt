package app.retriever

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_AUDIO
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VIDEO
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import app.retriever.core.Kind
import app.retriever.core.android.MediaStoreSource
import app.retriever.core.android.SafSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWork"
    private val work = PickedFolder(tree, "Documents/Work")

    private fun access(sdk: Int, vararg granted: String, allFiles: Boolean = false, persisted: List<String> = emptyList()) =
        Access.of(sdk, allFiles, { it in granted }, persisted)

    @Test fun permissionsPerAndroidVersion() {
        assertEquals(listOf(READ_EXTERNAL_STORAGE), Access.mediaPermissions(30))
        assertEquals(listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO), Access.mediaPermissions(33))
        assertEquals(
            listOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO, READ_MEDIA_VISUAL_USER_SELECTED),
            Access.mediaPermissions(34),
        )
    }

    @Test fun grantsReadPerAndroidVersion() {
        assertEquals(Access(false, Photos.ALL, videos = true, audio = true), access(30, READ_EXTERNAL_STORAGE))
        assertEquals(Access.NONE, access(32))
        assertEquals(Access(false, Photos.ALL, videos = true), access(33, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO))
        // Android 13 has no picked-photos grant.
        assertEquals(Access.NONE, access(33, READ_MEDIA_VISUAL_USER_SELECTED))
        assertEquals(Access(false, Photos.SELECTED), access(34, READ_MEDIA_VISUAL_USER_SELECTED))
        // Full access on 14 also leaves the picked-photos grant on.
        assertEquals(Photos.ALL, access(34, READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED).photos)
        assertEquals(Access(false, audio = true), access(34, READ_MEDIA_AUDIO))
    }

    @Test fun readyWithAnyAccess() {
        assertFalse(Access.NONE.ready)
        assertTrue(Access.ALL.ready)
        assertTrue(Access(false, Photos.SELECTED).ready)
        assertTrue(Access(false, videos = true).ready)
        assertTrue(Access(false, audio = true).ready)
        assertTrue(Access(false, folders = listOf(work)).ready)
    }

    @Test fun coverageLines() {
        assertEquals(listOf(CoverageLine("All files on this phone")), Access(true, folders = listOf(work)).coverage())
        assertEquals(
            listOf(
                CoverageLine("Photos and videos"),
                CoverageLine("Music and recordings"),
                CoverageLine("Not covered: documents and downloads", Fix.ALL_FILES),
            ),
            Access(false, Photos.ALL, videos = true, audio = true).coverage(),
        )
        assertEquals(
            listOf(
                CoverageLine("Only the photos you picked", Fix.CHOOSE_PHOTOS),
                CoverageLine("Videos"),
                CoverageLine("Not covered: music", Fix.ALLOW_MEDIA),
                CoverageLine("Not covered: documents and downloads outside the folders you added", Fix.ALL_FILES),
            ),
            Access(false, Photos.SELECTED, videos = true, folders = listOf(work)).coverage(),
        )
        assertEquals(
            listOf(
                CoverageLine("Photos"),
                CoverageLine("Not covered: videos and music", Fix.ALLOW_MEDIA),
                CoverageLine("Not covered: documents and downloads", Fix.ALL_FILES),
            ),
            Access(false, Photos.ALL).coverage(),
        )
        // Started with a folder only: the photo library is named as left out, with a way in.
        assertEquals(
            listOf(
                CoverageLine("Not covered: photos, videos and music", Fix.ALLOW_MEDIA),
                CoverageLine("Not covered: documents and downloads outside the folders you added", Fix.ALL_FILES),
            ),
            Access(false, folders = listOf(work)).coverage(),
        )
        assertTrue(Access(false, folders = listOf(work)).coverage().all { it.gap })
    }

    @Test fun foldersComeFromThisPhonesStorageOnly() {
        val other = "content://com.google.android.apps.docs.storage/tree/abc"
        val card = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3APapers"
        assertEquals(
            listOf(work, PickedFolder(card, "SD card/Papers")),
            access(34, persisted = listOf(card, other, tree)).folders,
        )
    }

    @Test fun picksOutsideStorageOrInsideAnotherFolderAreRefused() {
        assertEquals("Pick a folder on this phone's storage.", Access.refusePick("content://com.google.android.apps.docs.storage/tree/x", emptyList()))
        assertNull(Access.refusePick("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWorkshop", listOf(work)))
        val inside = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWork%2F2024"
        assertEquals("Retriever already searches that folder.", Access.refusePick(inside, listOf(work)))
        assertEquals("Retriever already searches that folder.", Access.refusePick(tree, listOf(work)))
    }

    @Test fun pickingAParentTakesThePlaceOfTheFoldersInsideIt() {
        val parent = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        val pictures = PickedFolder("content://com.android.externalstorage.documents/tree/primary%3APictures", "Pictures")
        assertNull(Access.refusePick(parent, listOf(work, pictures)))
        assertEquals(listOf(work), Access.inside(parent, listOf(work, pictures)))
        // The whole volume covers every folder on it.
        assertEquals(listOf(work, pictures), Access.inside("content://com.android.externalstorage.documents/tree/primary%3A", listOf(work, pictures)))
        assertEquals(emptyList<PickedFolder>(), Access.inside(parent, listOf(pictures)))
    }

    @Test fun allFilesLeavesPickedFoldersToMediaStore() {
        assertEquals(emptyList<String>(), Access(true, folders = listOf(work)).trees)
        assertEquals(listOf(tree), Access(false, folders = listOf(work)).trees)
        assertEquals(setOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.AUDIO), Access(false, Photos.ALL, audio = true).mediaKinds)
        // Only some photos visible to MediaStore: the folder still lists its photos.
        assertEquals(emptySet<Kind>(), Access(false, Photos.SELECTED).mediaKinds)
    }

    @Test fun changedAccessForcesAFullMediaStoreScan() {
        val photos = Access(false, Photos.ALL).fingerprint
        val tokens = mapOf("access" to photos, "external_primary" to "v1:42")
        assertEquals(tokens, MediaStoreSource.resumable(tokens, photos))
        // Wider access: rows that appear keep their old generation, so the old token can't be used.
        assertEquals(emptyMap<String, String>(), MediaStoreSource.resumable(tokens, Access.ALL.fingerprint))
        assertEquals(emptyMap<String, String>(), MediaStoreSource.resumable(mapOf("external_primary" to "v1:42"), photos))
        val fingerprints = listOf(Access.ALL, Access.NONE, Access(false, Photos.ALL), Access(false, Photos.SELECTED), Access(false, videos = true), Access(false, audio = true))
            .map { it.fingerprint }
        assertEquals(fingerprints.size, fingerprints.toSet().size)
        // Folders never reach MediaStore, so adding one must not rescan it.
        assertEquals(Access.NONE.fingerprint, Access(false, folders = listOf(work)).fingerprint)
        assertNotEquals(Access.NONE.fingerprint, Access(true).fingerprint)
    }

    @Test fun safRowsBecomeSourceFiles() {
        val pdf = SafSource.sourceFile("u1", "primary:Documents/Work", "Lease.pdf", "application/pdf", 2_000, 1_700_000_000_000)!!
        assertEquals("Documents/Work", pdf.folder)
        assertEquals(Kind.PDF, pdf.kind)
        assertEquals(1_700_000_000_000, pdf.modifiedAt)
        // An unknown MIME falls back to the extension.
        val doc = SafSource.sourceFile("u2", "primary:Documents", "Notes.docx", "application/octet-stream", 10, 0)!!
        assertEquals(Kind.DOC, doc.kind)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", doc.mime)
        assertEquals(Kind.SCREENSHOT, SafSource.sourceFile("u3", "primary:Pictures/Screenshots", "a.png", "image/png", 10, 0)!!.kind)
        assertEquals("SD card/Scans", SafSource.sourceFile("u4", "1A2B-3C4D:Scans", "s.pdf", "application/pdf", 10, 0)!!.folder)
        assertEquals("Phone storage", SafSource.treeName("primary:"))
        assertNull(SafSource.sourceFile("u5", "primary:Documents", ".hidden.txt", "text/plain", 10, 0))
        assertNull(SafSource.sourceFile("u6", "primary:Documents", "empty.txt", "text/plain", 0, 0))
        assertNull(SafSource.sourceFile("u7", "primary:Android/data/com.app", "x.txt", "text/plain", 10, 0))
    }
}
