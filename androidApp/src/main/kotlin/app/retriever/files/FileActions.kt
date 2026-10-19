package app.retriever.files

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.retriever.R
import app.retriever.core.FileState
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.duplicates.MediaDeleter
import app.retriever.ui.Format
import app.retriever.ui.components.PressMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

enum class FileAction(val label: String) { INFO("Info"), SHARE("Share"), OPEN("Open with"), RENAME("Rename"), DELETE("Delete") }
enum class FileSource { MEDIA, TREE, FILE, OTHER }

internal fun sourceOf(uri: String): FileSource = when {
    uri.startsWith("content://media/") -> FileSource.MEDIA
    uri.startsWith("content://") && "/document/" in uri -> FileSource.TREE
    uri.startsWith("file://") -> FileSource.FILE
    else -> FileSource.OTHER
}

internal fun fileActions(uri: String): List<FileAction> = if (sourceOf(uri) == FileSource.OTHER) {
    listOf(FileAction.INFO, FileAction.SHARE, FileAction.OPEN)
} else {
    FileAction.entries
}

internal fun validFileName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }

internal data class Request(val file: SourceFile, val action: FileAction, val deleted: () -> Unit)
internal class FileActions {
    var request by mutableStateOf<Request?>(null)

    fun select(file: SourceFile, action: FileAction, deleted: () -> Unit) {
        request = Request(file, action, deleted)
    }
}

@Composable
internal fun FileActionItems(file: SourceFile, dismiss: () -> Unit, onDeleted: () -> Unit = {}) {
    val actions = KoinPlatform.getKoinOrNull()?.getOrNull<FileActions>()
    Column {
        for (action in fileActions(file.uri)) {
            DropdownMenuItem(
                text = { Text(action.label) },
                leadingIcon = if (action == FileAction.SHARE) ({ Icon(painterResource(R.drawable.ic_share), contentDescription = null) }) else null,
                onClick = {
                    dismiss()
                    actions?.select(file, action, onDeleted)
                },
            )
        }
    }
}

@Composable
internal fun FilePressMenu(file: SourceFile, content: @Composable (() -> Unit) -> Unit) {
    PressMenu(content = content) { dismiss -> FileActionItems(file, dismiss) }
}

@Composable
internal fun FileOverflow(uri: String, onDeleted: () -> Unit) {
    val retriever = KoinPlatform.getKoinOrNull()?.get<Retriever>()
    var file by remember(uri) { mutableStateOf<SourceFile?>(null) }
    LaunchedEffect(uri) { file = retriever?.storedFile(uri)?.file }
    val shown = file ?: return
    PressMenu(content = { open ->
        IconButton(onClick = open) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "File actions") }
    }) { dismiss -> FileActionItems(shown, dismiss, onDeleted) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileActionsHost(retriever: Retriever, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = org.koin.compose.koinInject<FileActions>()
    var request by actions::request
    var name by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pending by remember { mutableStateOf<Pair<Request, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run(work: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                request = null
                Toast.makeText(context, "Couldn't finish that. The file may have moved or access may have changed.", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }
    suspend fun rename(r: Request, wanted: String) {
        val changed = checkNotNull(retriever.renameFile(r.file.uri) { rename(context, it, wanted) })
        request = null
        if (changed.uri != r.file.uri) r.deleted()
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val approved = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && approved != null) {
            run {
                val (r, wanted) = approved
                if (r.action == FileAction.RENAME) {
                    rename(r, wanted)
                } else {
                    retriever.deletedFiles(listOf(r.file.uri))
                    request = null
                    r.deleted()
                }
            }
        }
    }
    content()
    LaunchedEffect(request) {
        val selected = request ?: return@LaunchedEffect
        name = selected.file.name
        info = emptyList()
        if (selected.action == FileAction.SHARE || selected.action == FileAction.OPEN) {
            request = null
            run { shareOrOpen(context, selected.file, selected.action == FileAction.SHARE) }
        }
    }
    val r = request ?: return
    when (r.action) {
        FileAction.INFO -> {
            LaunchedEffect(r.file.uri) {
                run {
                    val stored = retriever.storedFile(r.file.uri)
                    info = withContext(Dispatchers.IO) { fileInfo(context, r.file, stored, r.file.kind in retriever.readInside()) }
                }
            }
            ModalBottomSheet(onDismissRequest = { request = null }) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Text("File info", Modifier.padding(16.dp))
                    for ((label, value) in info) ListItem(headlineContent = { Text(value) }, overlineContent = { Text(label) })
                }
            }
        }

        FileAction.RENAME -> AlertDialog(
            onDismissRequest = { if (!busy) request = null },
            title = { Text("Rename file") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = !busy && pending == null && validFileName(name) && name != r.file.name, onClick = {
                    if (sourceOf(r.file.uri) == FileSource.MEDIA) {
                        run {
                            val sender = MediaStore.createWriteRequest(context.contentResolver, listOf(r.file.uri.toUri())).intentSender
                            pending = r to name
                            consent.launch(IntentSenderRequest.Builder(sender).build())
                        }
                    } else {
                        run { rename(r, name) }
                    }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { request = null }, enabled = !busy) { Text("Cancel") } },
        )

        FileAction.DELETE -> {
            if (sourceOf(r.file.uri) == FileSource.MEDIA) {
                LaunchedEffect(r) {
                    run {
                        val sender = checkNotNull(MediaDeleter(context).request(listOf(r.file.uri)))
                        pending = r to ""
                        request = null
                        consent.launch(IntentSenderRequest.Builder(sender).build())
                    }
                }
            } else {
                AlertDialog(
                    onDismissRequest = { if (!busy) request = null },
                    title = { Text("Delete ${r.file.name}?") },
                    text = { Text("This deletes the file from its folder.") },
                    confirmButton = {
                        TextButton(enabled = !busy, onClick = {
                            run {
                                withContext(Dispatchers.IO) {
                                    check(
                                        if (sourceOf(r.file.uri) ==
                                            FileSource.FILE
                                        ) {
                                            File(checkNotNull(r.file.uri.toUri().path)).delete()
                                        } else {
                                            DocumentsContract.deleteDocument(context.contentResolver, r.file.uri.toUri())
                                        },
                                    )
                                }
                                retriever.deletedFiles(listOf(r.file.uri))
                                request = null
                                r.deleted()
                            }
                        }) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { request = null }, enabled = !busy) { Text("Cancel") } },
                )
            }
        }

        else -> Unit
    }
}

private fun rename(context: Context, file: SourceFile, name: String): SourceFile {
    require(validFileName(name))
    val resolver = context.contentResolver
    val uri = file.uri.toUri()
    val renamed = when (sourceOf(file.uri)) {
        FileSource.MEDIA -> {
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) > 0)
            uri
        }

        FileSource.TREE -> checkNotNull(DocumentsContract.renameDocument(resolver, uri, name))

        FileSource.FILE -> {
            check(Environment.isExternalStorageManager())
            val old = File(checkNotNull(uri.path))
            val next = File(old.parentFile, name)
            check(!next.exists() && old.renameTo(next))
            Uri.fromFile(next)
        }

        FileSource.OTHER -> error("Unsupported source")
    }
    if (renamed.scheme ==
        "file"
    ) {
        return file.copy(uri = renamed.toString(), name = File(checkNotNull(renamed.path)).name, modifiedAt = File(renamed.path!!).lastModified())
    }
    val modified = if (sourceOf(file.uri) == FileSource.MEDIA) MediaStore.MediaColumns.DATE_MODIFIED else DocumentsContract.Document.COLUMN_LAST_MODIFIED
    resolver.query(renamed, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, modified), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            return file.copy(
                uri = renamed.toString(),
                name = cursor.getString(0),
                modifiedAt =
                cursor.getLong(1) * if (sourceOf(file.uri) == FileSource.MEDIA) 1000 else 1,
            )
        }
    }
    return file.copy(uri = renamed.toString(), name = name)
}

private fun shareOrOpen(context: Context, file: SourceFile, share: Boolean) {
    val raw = file.uri.toUri()
    val uri = if (raw.scheme == "file") FileProvider.getUriForFile(context, "${context.packageName}.files", File(checkNotNull(raw.path))) else raw
    val intent = if (share) {
        Intent(
            Intent.ACTION_SEND,
        ).setType(file.mime).putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, if (share) null else "Open with"))
}

private fun fileInfo(context: Context, file: SourceFile, stored: Store.StoredFile?, readInside: Boolean): List<Pair<String, String>> = buildList {
    add("Name" to file.name)
    add("Folder" to file.folder)
    add("Kind" to Format.kindLabel(file.kind))
    add("Size" to Format.bytes(file.size))
    add("Modified" to Format.date(file.modifiedAt))
    file.takenAt?.let { add("Taken" to Format.date(it)) }
    add(
        "Reading" to when {
            !readInside || stored?.file?.kind == Kind.OTHER -> "Name only · Reading is off for this type"
            stored?.contentRead == true -> if (stored.partial || stored.extent != null || stored.state != FileState.INDEXED) "Partly read" else "Read"
            stored?.state == FileState.SKIPPED -> "Couldn’t read"
            stored?.state == FileState.INDEXED -> "Name only · No readable content"
            else -> "Waiting to read"
        },
    )
    runCatching {
        if (file.kind == Kind.PHOTO || file.kind == Kind.SCREENSHOT) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(file.uri.toUri())?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth > 0) add("Dimensions" to "${options.outWidth} × ${options.outHeight}")
        } else if (file.kind == Kind.VIDEO || file.kind == Kind.AUDIO) {
            MediaMetadataRetriever().use { media ->
                media.setDataSource(context, file.uri.toUri())
                media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { add("Duration" to "${it / 1000} seconds") }
                val width = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                if (width != null && height != null) add("Dimensions" to "$width × $height")
            }
        }
    }
}
