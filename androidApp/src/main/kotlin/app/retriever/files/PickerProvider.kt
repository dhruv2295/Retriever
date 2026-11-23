// Shape from android/storage-samples StorageProvider MyCloudProvider.java (Apache-2.0), read-only and backed by the index.

package app.retriever.files

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.net.toUri
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.di.AppScope
import app.retriever.ui.components.Thumbnails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch

/**
 * Retriever in the system file picker, so a website or app asking for a file can search the library for it. Read-only:
 * a document id is an indexed file's source uri, and only a file still in the index opens, so an excluded or revoked
 * file is gone here too. Only the system picker can call it (MANAGE_DOCUMENTS in the manifest).
 */
class PickerProvider :
    DocumentsProvider(),
    KoinComponent {
    // Android publishes a provider before Application.onCreate, so the picker's first call into a cold process can come
    // while the main thread is still starting Koin there. A post to the main thread runs once that start has finished.
    private val appStarted by lazy { CountDownLatch(1).also { Handler(Looper.getMainLooper()).post(it::countDown) }.await() }

    override fun getKoin(): Koin {
        appStarted
        return super.getKoin()
    }

    private val retriever: Retriever by inject()
    private val scope: CoroutineScope by inject(AppScope)
    private val app: Context by inject()
    private val label get() = app.applicationInfo.loadLabel(app.packageManager)

    override fun onCreate() = true

    override fun queryRoots(projection: Array<String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT)
            .add(Root.COLUMN_TITLE, label)
            .add(Root.COLUMN_ICON, app.applicationInfo.icon)
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_SEARCH)
            .add(Root.COLUMN_MIME_TYPES, "*/*")
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor = MatrixCursor(projection ?: DOC_COLUMNS).apply {
        if (documentId == ROOT) {
            newRow()
                .add(Document.COLUMN_DOCUMENT_ID, ROOT)
                .add(Document.COLUMN_DISPLAY_NAME, label)
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, 0)
        } else {
            add(indexed(documentId) ?: throw FileNotFoundException(documentId))
        }
    }

    // Opening Retriever in the picker: the newest files, and the model starts opening so typed searches go by meaning.
    // No recents: the picker's Recent already lists these files from the system, so they would show twice.
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        scope.launch { retriever.warmSearch() }
        return MatrixCursor(projection ?: DOC_COLUMNS).apply {
            runBlocking { retriever.files(null, null, Store.Sort.NEWEST, LIMIT, 0) }.forEach { add(it.file) }
        }
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor = MatrixCursor(projection ?: DOC_COLUMNS).apply {
        // A binder thread, so blocking is allowed. Search never waits on the model: keywords and names answer until the
        // warm-up has it open, for the searches after this one.
        scope.launch { retriever.warmSearch() }
        val result = runBlocking {
            retriever.loadNames()
            retriever.search(query, limit = LIMIT)
        }
        // The strong matches only, as the app shows before "more"; the picker has no "more".
        result.hits.take(result.strongCount.coerceAtLeast(1)).forEach { add(it.file) }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") throw UnsupportedOperationException("read-only")
        val file = indexed(documentId) ?: throw FileNotFoundException(documentId)
        return app.contentResolver.openFileDescriptor(file.uri.toUri(), "r", signal) ?: throw FileNotFoundException(documentId)
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point, signal: CancellationSignal?): AssetFileDescriptor {
        val file = indexed(documentId) ?: throw FileNotFoundException(documentId)
        val bitmap = runBlocking { Thumbnails.load(app, file, Thumbnails.bucket(maxOf(sizeHint.x, sizeHint.y))) }
            ?: throw FileNotFoundException(documentId)
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        // compress returns false, without throwing, when the picker stops reading (the row scrolled away).
        scope.launch {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        return AssetFileDescriptor(read, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun indexed(documentId: String): SourceFile? = runBlocking { retriever.storedFile(documentId) }?.file

    private fun MatrixCursor.add(file: SourceFile) {
        newRow()
            .add(Document.COLUMN_DOCUMENT_ID, file.uri)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, file.mime)
            .add(Document.COLUMN_SIZE, file.size)
            .add(Document.COLUMN_LAST_MODIFIED, file.modifiedAt)
            .add(Document.COLUMN_FLAGS, if (file.kind == Kind.AUDIO) 0 else Document.FLAG_SUPPORTS_THUMBNAIL)
    }

    private companion object {
        const val ROOT = "retriever"
        const val LIMIT = 100
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
        )
        val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
