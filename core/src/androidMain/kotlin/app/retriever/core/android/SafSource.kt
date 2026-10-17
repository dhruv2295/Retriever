package app.retriever.core.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import app.retriever.core.Changes
import app.retriever.core.FileRules
import app.retriever.core.FileSource
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Folders the person picked with the system file picker, for phones without all-files access. URIs are document
 * URIs under each tree. [trees] is empty with all-files access: the prefix is then in scope with no files, so
 * their rows go and MediaStore's take over. [skip] holds the kinds MediaStore already lists.
 *
 * ponytail: SAF has no change notifications, so every sync walks every tree in full. Fine for picked folders;
 * keep a per-folder mtime map if trees get huge.
 */
class SafSource(
    private val context: Context,
    private val trees: () -> List<String>,
    private val skip: () -> Set<Kind>,
) : FileSource {
    override suspend fun changes(since: String?): Changes = withContext(Dispatchers.IO) {
        val skipped = skip()
        val files = ArrayList<SourceFile>()
        val walked = HashSet<String>()
        var failed = false
        for (tree in trees()) {
            try {
                walk(Uri.parse(tree), skipped, files)
                walked += "$tree/document/"
            } catch (e: Exception) {
                // Revoked in Settings, or on a card that is out: its files keep their index until it is back.
                failed = true
                Log.w("Retriever", "Couldn't read a picked folder: ${e.message}")
            }
        }
        Changes(files, emptyList(), "", isFullScan = true, scope = if (failed) walked else setOf(PREFIX))
    }

    private fun walk(tree: Uri, skipped: Set<Kind>, out: MutableList<SourceFile>) {
        val resolver = context.contentResolver
        val dirs = ArrayDeque(listOf(DocumentsContract.getTreeDocumentId(tree)))
        while (dirs.isNotEmpty()) {
            val dir = dirs.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir)
            val cursor = resolver.query(children, PROJECTION, null, null, null) ?: error("No cursor for $children")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2)
                    if (mime == Document.MIME_TYPE_DIR) {
                        if (!FileRules.shouldSkip(relativePath(dir) + "$name/", name)) dirs += id
                        continue
                    }
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                    sourceFile(uri, dir, name, mime, c.getLong(3), c.getLong(4))?.takeIf { it.kind !in skipped }?.let(out::add)
                }
            }
        }
    }

    companion object {
        const val PREFIX = "content://com.android.externalstorage.documents/"

        private val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        /**
         * A folder as Retriever shows it, from its document id: "Documents/Work" for "primary:Documents/Work", like
         * MediaStore's relative paths; a memory card's folders start with "SD card".
         */
        fun treeName(documentId: String): String {
            val path = documentId.substringAfter(':').trimEnd('/')
            return when {
                documentId.startsWith("primary:") -> path.ifEmpty { "Phone storage" }
                else -> if (path.isEmpty()) "SD card" else "SD card/$path"
            }
        }

        /** "Documents/Work/" for "primary:Documents/Work", "" for a volume root: the form FileRules expects. */
        private fun relativePath(documentId: String) = documentId.substringAfter(':').trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }

        /** One file in the folder [dirId]; null for what the rules skip (hidden, empty, other apps' private data). */
        fun sourceFile(uri: String, dirId: String, name: String, mime: String?, size: Long, modifiedMs: Long): SourceFile? {
            val relative = relativePath(dirId)
            if (size <= 0 || FileRules.shouldSkip(relative, name)) return null
            val type = mime?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
                ?: FileRules.mimeForExtension(name.substringAfterLast('.', ""))
                ?: "application/octet-stream"
            return SourceFile(uri, name, treeName(dirId), type, FileRules.classify(type, relative, name), size, modifiedMs)
        }
    }
}
