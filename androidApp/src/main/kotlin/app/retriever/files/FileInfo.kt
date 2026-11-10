package app.retriever.files

import android.content.ClipData
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.retriever.R
import app.retriever.core.Kind
import app.retriever.core.Locator
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.core.readingLabel
import app.retriever.ui.Format
import app.retriever.ui.components.Thumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "PDF · 2.4 MB". */
internal fun kindAndSize(file: SourceFile) = Words.strings.kindAndSize(Words.kindLabel(file.kind), Format.bytes(file.size))

/** The sheet's rows below the header, in reading order. [measured] is whatever dimensions, duration or pages could be read. */
internal fun infoRows(file: SourceFile, measured: List<Pair<String, String>>, reading: String): List<Pair<String, String>> = buildList {
    add(Words.MODIFIED to Format.date(file.modifiedAt))
    file.takenAt?.let { add(Words.TAKEN to Format.date(it)) }
    addAll(measured)
    add(Words.FOUND_BY to reading)
}

private class Info(val rows: List<Pair<String, String>>, val path: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileInfoSheet(retriever: Retriever, file: SourceFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info by produceState<Info?>(null, file.uri) {
        value = withContext(Dispatchers.IO) {
            val reading = readingLabel(file.kind, retriever.storedFile(file.uri), file.kind in retriever.readInside())
            Info(infoRows(file, measure(context, file), reading), fullPath(context, file))
        }
    }
    var details by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            ListItem(
                leadingContent = { Thumb(file, 56.dp) },
                headlineContent = { SelectionContainer { Text(file.name) } },
                supportingContent = { Text(kindAndSize(file)) },
            )
            ListItem(headlineContent = { Text(friendlyFolder(file.folder)) }, overlineContent = { Text(Words.strings.locationLabel) })
            ListItem(
                modifier = Modifier.clickable(onClickLabel = if (details) Words.strings.hideFullPath else Words.strings.showFullPath) { details = !details },
                headlineContent = { Text(Words.strings.locationDetails) },
                trailingContent = {
                    Icon(painterResource(if (details) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
                },
            )
            info?.let { shown ->
                if (details) {
                    ListItem(
                        headlineContent = { SelectionContainer { Text(shown.path) } },
                        trailingContent = {
                            TextButton(onClick = {
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(Words.strings.pathClipLabel, shown.path))) }
                            }) { Text(Words.strings.copyPath) }
                        },
                    )
                }
                for ((label, value) in shown.rows) ListItem(headlineContent = { Text(value) }, overlineContent = { Text(label) })
            }
        }
    }
}

/** Dimensions, duration or pages, when the file says; nothing when it cannot be read. */
private fun measure(context: Context, file: SourceFile): List<Pair<String, String>> = buildList {
    val uri = file.uri.toUri()
    runCatching {
        when (file.kind) {
            Kind.PHOTO, Kind.SCREENSHOT -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                if (options.outWidth > 0) add(Words.strings.dimensionsLabel to Words.strings.dimensions(options.outWidth, options.outHeight))
            }

            Kind.VIDEO, Kind.AUDIO -> MediaMetadataRetriever().use { media ->
                media.setDataSource(context, uri)
                media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let {
                    add(Words.strings.durationLabel to Locator.time((it / 1000).toInt()))
                }
                val width = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val height = media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                if (width != null && height != null) add(Words.strings.dimensionsLabel to Words.strings.dimensions(width, height))
            }

            // A throwaway renderer: the viewer's own is confined to its thread, this one is opened and closed here.
            Kind.PDF -> context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                PdfRenderer(fd).use { add(Words.strings.pagesLabel to it.pageCount.toString()) }
            }

            else -> Unit
        }
    }
}

/** Where the file lives, as the provider says it: a folder path rather than an address. */
private fun fullPath(context: Context, file: SourceFile): String = runCatching {
    val uri = file.uri.toUri()
    when (sourceOf(file.uri)) {
        FileSource.TREE -> uri.lastPathSegment?.replaceFirst("primary:", Words.strings.internalStorage)

        FileSource.MEDIA -> context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) + file.name else null
        }

        FileSource.OTHER -> null
    }
}.getOrNull() ?: "${file.folder}/${file.name}"
