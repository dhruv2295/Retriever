package app.retriever.core.android

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
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
 * their rows go and MediaStore's take over. [skip] holds the kinds MediaStore already lists in full; [listed] holds
 * the [mediaKey]s of the single files it lists (photos the person picked), so no file is indexed twice.
 *
 * ponytail: SAF has no change notifications, so every sync walks every tree in full. Fine for picked folders;
 * keep a per-folder mtime map if trees get huge.
 */
class SafSource(
    private val context: Context,
    private val trees: () -> List<String>,
    private val skip: () -> Set<Kind>,
    private val listed: () -> Set<String> = { emptySet() },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) : FileSource {
    override suspend fun changes(since: String?): Changes = withContext(Dispatchers.IO) {
        val trees = trees()
        val skipped = skip()
        val known = if (trees.isEmpty()) emptySet() else listed()
        val describe = volumeDescriber(context)
        val files = ArrayList<SourceFile>()
        val walked = HashSet<String>()
        var failed = false
        for (tree in trees) {
            try {
                walk(tree.toUri(), skipped, known, describe, files)
                walked += "$tree/document/"
            } catch (e: Exception) {
                // Revoked in Settings, or on a card that is out: its files keep their index until it is back.
                failed = true
                log("Couldn't read a picked folder", e)
            }
        }
        Changes(files, emptyList(), "", isFullScan = true, scope = if (failed) walked else setOf(PREFIX))
    }

    private fun walk(tree: Uri, skipped: Set<Kind>, known: Set<String>, describe: (String) -> String?, out: MutableList<SourceFile>) {
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
                    sourceFile(uri, dir, name, mime, c.getLong(3), c.getLong(4), describe, known)?.takeIf { it.kind !in skipped }?.let(out::add)
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
         * MediaStore's relative paths. A memory card's folders start with the name [describe] gives its volume id
         * (the system's, e.g. "SanDisk SD card", as MediaStore shows it), or "SD card" while the card is out.
         */
        fun treeName(documentId: String, describe: (String) -> String? = { null }): String {
            val volume = documentId.substringBefore(':')
            val path = documentId.substringAfter(':').trimEnd('/')
            if (volume == "primary") return path.ifEmpty { "Phone storage" }
            val card = describe(volume) ?: "SD card"
            return if (path.isEmpty()) card else "$card/$path"
        }

        /** Mounted volumes' names by volume id, read once and only when a non-primary folder asks. */
        fun volumeDescriber(context: Context): (String) -> String? {
            val labels by lazy {
                runCatching {
                    context.getSystemService(StorageManager::class.java).storageVolumes
                        .filter { it.uuid != null && it.state.startsWith(Environment.MEDIA_MOUNTED) }
                        .associate { it.uuid!!.uppercase() to it.getDescription(context) }
                }.getOrDefault(emptyMap())
            }
            return { id -> labels[id.uppercase()] }
        }

        /**
         * One file's identity across MediaStore and SAF: MediaStore's VOLUME_NAME ("external_primary", or a card's
         * lowercase id) or a document id's volume ("primary", or the card's uppercase id), the folder's relative path
         * ("DCIM/Camera/") and the name. Shared storage ignores case, so the key does too.
         */
        fun mediaKey(volume: String, relativePath: String, name: String): String {
            val v = if (volume == "external_primary") "primary" else volume
            return "$v:$relativePath$name".lowercase()
        }

        /** "Documents/Work/" for "primary:Documents/Work", "" for a volume root: the form FileRules expects. */
        private fun relativePath(documentId: String) = documentId.substringAfter(':').trimEnd('/').let { if (it.isEmpty()) "" else "$it/" }

        /**
         * One file in the folder [dirId]; null for what the rules skip (hidden, empty, other apps' private data) and
         * for a file MediaStore already lists, by its [mediaKey] in [listed].
         */
        fun sourceFile(
            uri: String,
            dirId: String,
            name: String,
            mime: String?,
            size: Long,
            modifiedMs: Long,
            describe: (String) -> String? = { null },
            listed: Set<String> = emptySet(),
        ): SourceFile? {
            val relative = relativePath(dirId)
            if (size <= 0 || FileRules.shouldSkip(relative, name)) return null
            if (listed.isNotEmpty() && mediaKey(dirId.substringBefore(':'), relative, name) in listed) return null
            val type = mime?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }
                ?: FileRules.mimeForExtension(name.substringAfterLast('.', ""))
                ?: "application/octet-stream"
            return SourceFile(uri, name, treeName(dirId, describe), type, FileRules.classify(type, relative, name), size, modifiedMs)
        }
    }
}
