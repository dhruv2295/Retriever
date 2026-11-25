package app.retriever.files

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.access.AccessService
import app.retriever.core.DeviceWords
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.duplicates.MediaDeleter
import app.retriever.duplicates.mediaItemUri
import app.retriever.indexing.IndexService
import app.retriever.nav.BrowseRoute
import app.retriever.ui.components.PressMenu
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform
import kotlin.coroutines.cancellation.CancellationException

/** The menu's items in the order every surface shows them. [STAR] is carried out by the caller, the rest by [FileActionsHost]. */
enum class FileAction(private val label: () -> String, @DrawableRes val icon: Int) {
    STAR({ Words.strings.addToStarred }, R.drawable.ic_star_border),
    SHARE({ Words.strings.share }, R.drawable.ic_share),
    OPEN({ Words.strings.openInAnotherApp }, R.drawable.ic_open_in_new),
    INFO({ Words.strings.fileInfo }, R.drawable.ic_info),
    RENAME({ Words.strings.rename }, R.drawable.ic_edit),
    DELETE({ Words.deleteFrom(DeviceWords.ANDROID) }, R.drawable.ic_delete),
    ;

    fun label(starred: Boolean) = if (this == STAR && starred) Words.strings.removeFromStarred else label()
    fun icon(starred: Boolean) = if (this == STAR && starred) R.drawable.ic_star else icon
}
enum class FileSource { MEDIA, TREE, OTHER }

internal fun sourceOf(uri: String): FileSource = when {
    uri.startsWith("content://media/") -> FileSource.MEDIA
    uri.startsWith("content://") && "/document/" in uri -> FileSource.TREE
    else -> FileSource.OTHER
}

/** What can be done to a file at [uri]: a provider Retriever cannot write to offers no rename or delete. */
internal fun fileActions(uri: String): List<FileAction> = if (sourceOf(uri) == FileSource.OTHER) {
    listOf(FileAction.STAR, FileAction.SHARE, FileAction.OPEN, FileAction.INFO)
} else {
    FileAction.entries
}

/** The folder's own name, as Folders shows it; "Top level" for a file that sits at the root. */
internal fun friendlyFolder(folder: String) = folder.substringAfterLast('/').ifEmpty { Words.strings.topLevel }

/** A folder's own Browse. Titled the same way from everywhere, so the route is the same key and going there again goes back. */
internal fun folderRoute(folder: String) = BrowseRoute(folder = folder, title = friendlyFolder(folder))

/** The one mime type of [mimes], else their common top level with any subtype (all images), else anything. */
internal fun shareType(mimes: List<String>): String {
    val types = mimes.distinct()
    types.singleOrNull()?.let { return it }
    return types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
}

internal fun validFileName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }

internal data class Request(val file: SourceFile, val action: FileAction, val deleted: () -> Unit)

/** [action] (Share or Delete) on several files at once; [done] once it has been carried out. */
internal class Batch(val files: List<SourceFile>, val action: FileAction, val done: () -> Unit)
internal class FileActions {
    var request by mutableStateOf<Request?>(null)
    var batch by mutableStateOf<Batch?>(null)

    fun select(file: SourceFile, action: FileAction, deleted: () -> Unit) {
        request = Request(file, action, deleted)
    }

    /** One file takes the one-file way, with its own dialog. */
    fun selectAll(files: List<SourceFile>, action: FileAction, done: () -> Unit = {}) {
        val one = files.singleOrNull()
        if (one != null) {
            select(one, action, done)
        } else if (files.isNotEmpty()) {
            batch = Batch(files, action, done)
        }
    }
}

/** Whether the file at [uri] is starred, recomposing only when that changes. False without a Retriever (a bare UI test). */
@Composable
private fun rememberStarred(uri: String): Boolean {
    val retriever = remember { KoinPlatform.getKoinOrNull()?.getOrNull<Retriever>() }
    val flow = remember(uri) { retriever?.starred?.map { uri in it }?.distinctUntilChanged() ?: flowOf(false) }
    return flow.collectAsStateWithLifecycle(false).value
}

/** Stars or unstars [uri] through the Retriever, and says so when the write fails. */
@Composable
private fun rememberStarWriter(uri: String): (Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val retriever = remember { KoinPlatform.getKoinOrNull()?.getOrNull<Retriever>() }
    return { on ->
        scope.launch {
            try {
                retriever?.setStarred(uri, on)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, Words.strings.couldntChangeStar, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun perform(action: FileAction, file: SourceFile, starred: Boolean, onStar: (Boolean) -> Unit, host: FileActions?, onDeleted: () -> Unit) {
    if (action == FileAction.STAR) onStar(!starred) else host?.select(file, action, onDeleted)
}

/**
 * The one file menu: [fileActions] in order, Delete last under a divider. [starred] and [onStar] default to the Retriever's
 * set; a list that shows Undo passes its own. [similar] adds Find similar after Open, for an image. [copies] lists a
 * search result's other copies first, each opened by [openCopy].
 */
@Composable
internal fun FileActionItems(
    file: SourceFile,
    dismiss: () -> Unit,
    starred: Boolean? = null,
    onStar: ((Boolean) -> Unit)? = null,
    onDeleted: () -> Unit = {},
    similar: (() -> Unit)? = null,
    copies: List<SourceFile> = emptyList(),
    openCopy: (SourceFile) -> Unit = {},
    notThisPerson: (() -> Unit)? = null,
    select: (() -> Unit)? = null,
    showInFolder: (() -> Unit)? = null,
) {
    val host = remember { KoinPlatform.getKoinOrNull()?.getOrNull<FileActions>() }
    val on = starred ?: rememberStarred(file.uri)
    val write = onStar ?: rememberStarWriter(file.uri)
    Column {
        if (select != null) {
            DropdownMenuItem(
                text = { Text(Words.strings.select) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_check_circle_outlined), contentDescription = null) },
                onClick = {
                    dismiss()
                    select()
                },
            )
        }
        if (notThisPerson != null) {
            DropdownMenuItem(
                text = { Text(Words.NOT_THIS_PERSON) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_person_remove), contentDescription = null) },
                onClick = {
                    dismiss()
                    notThisPerson()
                },
            )
            HorizontalDivider()
        }
        for (copy in copies) {
            DropdownMenuItem(
                text = { Text(Words.openCopyIn(friendlyFolder(copy.folder))) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null) },
                onClick = {
                    dismiss()
                    openCopy(copy)
                },
            )
        }
        if (copies.isNotEmpty()) HorizontalDivider()
        for (action in fileActions(file.uri)) {
            if (action == FileAction.INFO && similar != null) {
                DropdownMenuItem(
                    text = { Text(Words.strings.findSimilar) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_image_search), contentDescription = null) },
                    onClick = {
                        dismiss()
                        similar()
                    },
                )
            }
            if (action == FileAction.INFO && showInFolder != null) {
                DropdownMenuItem(
                    text = { Text(Words.strings.showInFolder) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null) },
                    onClick = {
                        dismiss()
                        showInFolder()
                    },
                )
            }
            if (action == FileAction.DELETE) HorizontalDivider()
            val error = MaterialTheme.colorScheme.error
            DropdownMenuItem(
                text = { Text(action.label(on)) },
                leadingIcon = { Icon(painterResource(action.icon(on)), contentDescription = null) },
                colors = if (action == FileAction.DELETE) MenuDefaults.itemColors(textColor = error, leadingIconColor = error) else MenuDefaults.itemColors(),
                onClick = {
                    dismiss()
                    perform(action, file, on, write, host, onDeleted)
                },
            )
        }
    }
}

/**
 * [content] with the file menu on long press. It is given the long press handler and a modifier that carries the same
 * actions for a screen reader, to be applied to the node a person focuses.
 */
@Composable
internal fun FilePressMenu(
    file: SourceFile,
    modifier: Modifier = Modifier,
    starred: Boolean? = null,
    onStar: ((Boolean) -> Unit)? = null,
    onDeleted: () -> Unit = {},
    similar: (() -> Unit)? = null,
    copies: List<SourceFile> = emptyList(),
    openCopy: (SourceFile) -> Unit = {},
    notThisPerson: (() -> Unit)? = null,
    select: (() -> Unit)? = null,
    showInFolder: (() -> Unit)? = null,
    content: @Composable (menu: () -> Unit, actions: Modifier) -> Unit,
) {
    val host = remember { KoinPlatform.getKoinOrNull()?.getOrNull<FileActions>() }
    val on = starred ?: rememberStarred(file.uri)
    val write = onStar ?: rememberStarWriter(file.uri)
    // Called through the latest lambdas, so a list's new ones each frame don't rebuild every row's actions.
    val selectNow by rememberUpdatedState(select)
    val showNow by rememberUpdatedState(showInFolder)
    val actions = remember(file.uri, on, host, copies, notThisPerson, select != null, showInFolder != null) {
        Modifier.semantics {
            customActions = listOfNotNull(
                select?.let {
                    CustomAccessibilityAction(Words.strings.select) {
                        selectNow?.invoke()
                        true
                    }
                },
                showInFolder?.let {
                    CustomAccessibilityAction(Words.strings.showInFolder) {
                        showNow?.invoke()
                        true
                    }
                },
                notThisPerson?.let { act ->
                    CustomAccessibilityAction(Words.NOT_THIS_PERSON) {
                        act()
                        true
                    }
                },
            ) + copies.map { copy ->
                CustomAccessibilityAction(Words.openCopyIn(friendlyFolder(copy.folder))) {
                    openCopy(copy)
                    true
                }
            } + fileActions(file.uri).map { action ->
                CustomAccessibilityAction(action.label(on)) {
                    perform(action, file, on, write, host, onDeleted)
                    true
                }
            }
        }
    }
    PressMenu(content = { menu -> content(menu, actions) }, modifier = modifier) { dismiss ->
        FileActionItems(file, dismiss, on, write, onDeleted, similar, copies, openCopy, notThisPerson, select, showInFolder)
    }
}

@Composable
internal fun FileActionsHost(retriever: Retriever, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = org.koin.compose.koinInject<FileActions>()
    val deleter = org.koin.compose.koinInject<MediaDeleter>()
    val access = org.koin.compose.koinInject<AccessService>()
    val indexer = org.koin.compose.koinInject<IndexService>()
    var request by actions::request
    var batch by actions::batch
    var name by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<Request, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun failed() {
        request = null
        batch = null
        Toast.makeText(context, Words.strings.couldntFinishThat, Toast.LENGTH_LONG).show()
    }
    fun run(work: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed()
            } finally {
                busy = false
            }
        }
    }
    suspend fun rename(r: Request, wanted: String) {
        val changed = retriever.renameFile(r.file.uri) { rename(context, it, wanted) } ?: return failed()
        request = null
        if (changed.uri != r.file.uri) r.deleted()
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val approved = pending
        pending = null
        // Several files (or one whose request didn't survive the prompt): whatever the person chose, the index is the
        // truth, so sync and let the lists reload, as Duplicates does.
        if (approved == null) {
            indexer.indexNow()
        } else if (result.resultCode == Activity.RESULT_OK) {
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
        if (selected.action == FileAction.SHARE || selected.action == FileAction.OPEN) {
            request = null
            run { shareOrOpen(context, selected.file, selected.action == FileAction.SHARE) }
        }
    }
    LaunchedEffect(batch) {
        val b = batch ?: return@LaunchedEffect
        if (b.action == FileAction.SHARE) {
            batch = null
            run { shareAll(context, b.files) }
        }
    }
    batch?.takeIf { it.action == FileAction.DELETE }?.let { b ->
        val n = b.files.size.toLong()
        AlertDialog(
            onDismissRequest = { if (!busy) batch = null },
            title = { Text(Words.strings.deleteFilesAsk(n)) },
            text = { Text(Words.strings.deleteFilesMessage(n)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    run {
                        // A provider Retriever can't write to keeps its files; they count as not deleted.
                        val writable = b.files.map { it.uri }.filter { FileAction.DELETE in fileActions(it) }
                        val result = deleter.deleteOrAsk(writable, access.access().allFiles)
                        if (result.deleted.isNotEmpty()) retriever.deletedFiles(result.deleted)
                        batch = null
                        b.done()
                        val left = b.files.size - result.deleted.size - result.asked.size
                        if (left > 0) Toast.makeText(context, Words.strings.filesWerentDeleted(left.toLong()), Toast.LENGTH_LONG).show()
                        result.consent?.let { consent.launch(IntentSenderRequest.Builder(it).build()) }
                    }
                }) { Text(Words.deleteFrom(DeviceWords.ANDROID)) }
            },
            dismissButton = { TextButton(onClick = { batch = null }, enabled = !busy) { Text(Words.CANCEL) } },
        )
    }
    val r = request ?: return
    when (r.action) {
        FileAction.INFO -> FileInfoSheet(retriever, r.file) { request = null }

        FileAction.RENAME -> AlertDialog(
            onDismissRequest = { if (!busy) request = null },
            title = { Text(Words.strings.renameFile) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(Words.strings.fileNameField) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = !busy && pending == null && validFileName(name) && name != r.file.name, onClick = {
                    if (sourceOf(r.file.uri) == FileSource.MEDIA) {
                        run {
                            val item = mediaItemUri(context.contentResolver, r.file.uri) ?: return@run failed()
                            val sender = MediaStore.createWriteRequest(context.contentResolver, listOf(item)).intentSender
                            pending = r to name
                            consent.launch(IntentSenderRequest.Builder(sender).build())
                        }
                    } else {
                        run { rename(r, name) }
                    }
                }) { Text(Words.strings.rename) }
            },
            dismissButton = { TextButton(onClick = { request = null }, enabled = !busy) { Text(Words.CANCEL) } },
        )

        // Asked here for every file, as Duplicates does: with all-files access Android deletes a photo without asking.
        FileAction.DELETE -> AlertDialog(
            onDismissRequest = { if (!busy) request = null },
            title = { Text(Words.strings.deleteThisFile) },
            text = { Text(Words.deleteConfirm(DeviceWords.ANDROID)) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    run {
                        if (sourceOf(r.file.uri) == FileSource.MEDIA && !access.access().allFiles) {
                            val sender = deleter.request(listOf(r.file.uri)) ?: return@run failed()
                            pending = r to ""
                            request = null
                            consent.launch(IntentSenderRequest.Builder(sender).build())
                        } else {
                            if (deleter.delete(listOf(r.file.uri)).isEmpty()) return@run failed()
                            retriever.deletedFiles(listOf(r.file.uri))
                            request = null
                            r.deleted()
                        }
                    }
                }) { Text(Words.deleteFrom(DeviceWords.ANDROID)) }
            },
            dismissButton = { TextButton(onClick = { request = null }, enabled = !busy) { Text(Words.CANCEL) } },
        )

        else -> Unit
    }
}

/** The renamed file, or null when it couldn't be renamed. */
private fun rename(context: Context, file: SourceFile, name: String): SourceFile? {
    if (!validFileName(name)) return null
    val resolver = context.contentResolver
    val uri = file.uri.toUri()
    val renamed = when (sourceOf(file.uri)) {
        FileSource.MEDIA -> {
            // Through the address the write request granted; the index keeps the Files one.
            val item = mediaItemUri(resolver, file.uri) ?: return null
            if (resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) <= 0) return null
            uri
        }

        FileSource.TREE -> DocumentsContract.renameDocument(resolver, uri, name) ?: return null

        FileSource.OTHER -> return null
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
    val uri = file.uri.toUri()
    val intent = if (share) {
        Intent(
            Intent.ACTION_SEND,
        ).setType(file.mime).putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, file.mime)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, if (share) null else Words.strings.openInAnotherApp))
}

/** Several files to one app; the read grant covers the uris the ClipData lists. */
private fun shareAll(context: Context, files: List<SourceFile>) {
    val uris = files.map { it.uri.toUri() }
    val clip = ClipData.newRawUri(null, uris.firstOrNull() ?: return)
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType(shareType(files.map { it.mime }))
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = clip
    context.startActivity(Intent.createChooser(intent, null))
}
