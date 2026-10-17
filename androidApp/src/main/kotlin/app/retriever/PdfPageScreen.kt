/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Adapted from android/platform-samples samples/graphics/pdf/.../PdfRenderer.kt and PdfRendererViewModel.kt (Apache-2.0). Modified for Retriever: one renderer opened for the screen's lifetime on a single-thread dispatcher, a lazy list of pages sized up front, opens at the matched page, and plain states for locked and missing files.

package app.retriever

import android.graphics.Color as AndroidColor
import app.retriever.di.AppScope
import android.graphics.pdf.PdfRenderer
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

// PdfRenderer is not thread-safe: every open, render and close goes through this one thread.
@OptIn(ExperimentalCoroutinesApi::class)
private val pdfDispatcher = Dispatchers.IO.limitedParallelism(1)

private const val MAX_PAGE_PX = 4096

private sealed interface PdfState {
    data object Loading : PdfState
    data object Locked : PdfState
    data object Failed : PdfState
    /** [placeholder]: the matched page's size, the shape every page takes until it renders at its own. */
    class Ready(val renderer: PdfRenderer, val count: Int, val placeholder: IntSize) : PdfState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPageScreen(route: PdfRoute, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appScope: CoroutineScope = koinInject(AppScope)
    val stars = koinViewModel<StarHolder>().stars
    val starred by stars.starred.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    StarFailureEffect(stars, snackbar)
    val state by produceState<PdfState>(PdfState.Loading, route.uri) {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        value = withContext(pdfDispatcher) {
            try {
                val opened = context.contentResolver.openFileDescriptor(route.uri.toUri(), "r") ?: error("no file")
                fd = opened
                val r = PdfRenderer(opened).also { renderer = it }
                // Measures one page, not all of them: a 1,500-page book would hold the spinner for seconds.
                val shape = r.openPage((route.page - 1).coerceIn(0, r.pageCount - 1)).use { IntSize(it.width, it.height) }
                PdfState.Ready(r, r.pageCount, shape)
            } catch (_: SecurityException) {
                PdfState.Locked
            } catch (_: Exception) {
                PdfState.Failed
            }
        }
        awaitDispose {
            // Queued behind any page still rendering, so it never closes underneath one. The app scope outlives
            // the screen, so the close still runs after the composition has left.
            appScope.launch(pdfDispatcher) {
                renderer?.close()
                fd?.close()
            }
        }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (route.page - 1).coerceAtLeast(0))
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val openElsewhere = { Opener.view(context, route.uri, "application/pdf") }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(route.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        (state as? PdfState.Ready)?.let {
                            Text(
                                "Page ${current + 1} of ${it.count}",
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                },
                actions = {
                    StarButton(route.uri in starred) { stars.toggle(route.uri) }
                    IconButton(onClick = openElsewhere) {
                        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = "Open in another app")
                    }
                },
            )
        },
    ) { padding ->
        when (val s = state) {
            PdfState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            PdfState.Locked -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    "This PDF is locked",
                    "It needs a password, so Retriever can only find it by name. Open it in another app to read it.",
                    R.drawable.ic_lock,
                    action = "Open in another app" to openElsewhere,
                )
            }
            PdfState.Failed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    "Couldn't open this PDF",
                    "It may have been moved or deleted.",
                    R.drawable.ic_warning_amber,
                    action = "Open in another app" to openElsewhere,
                )
            }
            is PdfState.Ready -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                var zoom by remember { mutableFloatStateOf(1f) }
                var panX by remember { mutableFloatStateOf(0f) }
                val pinch = rememberTransformableState { _, change, pan, _ ->
                    zoom = (zoom * change).coerceIn(1f, 4f)
                    val limit = constraints.maxWidth * (zoom - 1f) / 2f
                    panX = (panX + pan.x).coerceIn(-limit, limit)
                    // Once zoomed, a one-finger drag pans sideways here and scrolls the page list up and down.
                    listState.dispatchRawDelta(-pan.y / zoom)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = panX }
                        .transformable(pinch, canPan = { zoom > 1f }),
                    contentPadding = contentPaddingFor(padding, 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(s.count, key = { it }) { i ->
                        PdfPage(s.renderer, i, s.count, s.placeholder, widthPx, matched = i == route.page - 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, index: Int, count: Int, placeholder: IntSize, widthPx: Int, matched: Boolean) {
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, index, widthPx) {
        value = withContext(pdfDispatcher) { runCatching { renderPage(renderer, index, widthPx) }.getOrNull() }
    }
    PdfPageFrame(bitmap, placeholder, "Page ${index + 1} of $count", matched)
}

@Composable
internal fun PdfPageFrame(shown: ImageBitmap?, placeholder: IntSize, label: String, matched: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (shown != null) shown.width.toFloat() / shown.height else placeholder.width.toFloat() / placeholder.height)
            .background(Color.White)
            .then(if (matched) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
            .semantics { contentDescription = label + if (matched) ", where your search matched" else "" },
    ) {
        shown?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
        if (matched) {
            Text(
                "Matched here",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Renders at the column width, shrinking a very tall page so its height stays under [MAX_PAGE_PX]. */
private fun renderPage(renderer: PdfRenderer, index: Int, widthPx: Int): ImageBitmap =
    renderer.openPage(index).use { page ->
        val scale = minOf(widthPx.toFloat() / page.width, MAX_PAGE_PX.toFloat() / page.height)
        val bitmap = createBitmap(
            (page.width * scale).toInt().coerceAtLeast(1),
            (page.height * scale).toInt().coerceAtLeast(1),
        )
        bitmap.eraseColor(AndroidColor.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bitmap.asImageBitmap()
    }
