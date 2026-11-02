package app.retriever.access

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import app.retriever.core.android.MediaStoreSource
import app.retriever.core.android.SafSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder

/** A provider that answers no query must leave the index as it is, never empty it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NoCursorTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun pickedFolderWithNoListingStaysOutOfScope() = runBlocking {
        // No provider holds this authority, so every query answers null.
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        val changes = SafSource(context, { listOf(tree) }, { emptySet() }).changes(null)
        assertTrue(changes.upserted.isEmpty())
        assertEquals(emptySet<String>(), changes.scope)
    }

    @Test fun mediaStoreWithNoCursorReportsNoChange() = runBlocking {
        Robolectric.setupContentProvider(NoAnswers::class.java, MediaStore.AUTHORITY)
        val volume = StorageVolumeBuilder("emulated", context.filesDir, "Phone", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
            .setIsPrimary(true).setIsEmulated(true).build()
        shadowOf(context.getSystemService(StorageManager::class.java)).addStorageVolume(volume)
        val logged = mutableListOf<String>()
        val changes = MediaStoreSource(context, log = { m, _ -> logged += m }).changes("token")
        assertTrue(logged.any { it.startsWith("MediaStore gave no cursor") })
        assertTrue(changes.upserted.isEmpty() && changes.removed.isEmpty() && !changes.isFullScan)
        assertEquals("token", changes.nextToken)
        assertEquals(emptySet<String>(), changes.scope)
    }

    class NoAnswers : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?) = null

        // MediaStore.getVersion and getGeneration read their answers from these keys.
        override fun call(method: String, arg: String?, extras: Bundle?) = Bundle().apply {
            putString(Intent.EXTRA_TEXT, "1")
            putLong(Intent.EXTRA_INDEX, 1)
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
    }
}
