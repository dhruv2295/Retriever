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
// Pinch (about the fingers' centroid), double-tap zoom and fling come from saket/telephoto's Modifier.zoomable (Apache-2.0), on a single-page view: a zoomable that scales the whole page list would take the vertical drags the list needs. Adapted from android/platform-samples samples/graphics/pdf/.../PdfRenderer.kt and PdfRendererViewModel.kt (Apache-2.0). Modified for Retriever: one renderer opened for the screen's lifetime on a single-thread dispatcher, a lazy list of pages sized up front, opens at the matched page, and plain states for locked and missing files.

package app.retriever.viewer

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.motion.MascotMood
import app.retriever.di.AppScope
import app.retriever.nav.PdfRoute
import app.retriever.starred.StarButton
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.StarHolder
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.contentPaddingFor
import app.retriever.ui.tabular
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import android.graphics.Color as AndroidColor

// PdfRenderer is not thread-safe: every open, render and close goes through this one thread.
@OptIn(ExperimentalCoroutinesApi::class)
private val pdfDispatcher = Dispatchers.IO.limitedParallelism(1)

private const val MAX_PAGE_PX = 4096

/** Render width multiple for a settled zoom: sharp text at 2x and 3x, never past what a bitmap can hold. */
internal fun zoomBucket(userZoom: Float): Int = when {
    userZoom < 1.5f -> 1
    userZoom < 2.5f -> 2
    else -> 3
}

/** Pixels per page point for a render [widthPx] wide: neither side of the bitmap goes past [MAX_PAGE_PX]. */
internal fun renderScale(pageW: Int, pageH: Int, widthPx: Int): Float =
    minOf(widthPx.toFloat() / pageW, MAX_PAGE_PX.toFloat() / pageH, MAX_PAGE_PX.toFloat() / pageW)

/** The page "Matched here" marks, or null when no search matched (a PDF opened from a list, or found by name). */
internal fun PdfRoute.matchedIndex(): Int? = if (matched) page - 1 else null

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
    // The page shown full screen, if any; kept across rotation.
    var zoomed by rememberSaveable { mutableStateOf<Int?>(null) }
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
                                "Page ${(zoomed ?: current) + 1} of ${it.count}",
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (zoomed != null) {
                        IconButton(onClick = { zoomed = null }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close page") }
                    } else {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                    }
                },
                actions = {
                    StarButton(route.uri in starred) { stars.toggle(route.uri) }
                    app.retriever.files.FileOverflow(route.uri, onBack)
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

            PdfState.Failed -> PdfFailed(openElsewhere, Modifier.padding(padding))

            is PdfState.Ready -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
                LazyColumn(
                    state = listState,
                    // Under a zoomed page the list is out of TalkBack's reach.
                    modifier = if (zoomed != null) Modifier.clearAndSetSemantics {} else Modifier,
                    contentPadding = contentPaddingFor(padding, 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(s.count, key = { it }) { i ->
                        PdfPage(s.renderer, i, s.count, s.placeholder, widthPx, matched = i == route.matchedIndex(), onClick = { zoomed = i })
                    }
                }
                zoomed?.let {
                    BackHandler { zoomed = null }
                    PdfZoomPage(
                        s.renderer,
                        it,
                        s.placeholder,
                        widthPx,
                        // The empty pointerInput keeps touches off the list beneath while the page is still rendering.
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {}
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(top = padding.calculateTopPadding()),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PdfFailed(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            "Couldn't open this PDF",
            "It may have been moved or deleted.",
            R.drawable.ic_warning_amber,
            action = "Open in another app" to onOpen,
            mood = MascotMood.TROUBLE,
        )
    }
}

/** One page full screen: pinch, double tap and fling are Telephoto's; once the zoom settles the page is re-rendered at its [zoomBucket]. */
@OptIn(FlowPreview::class)
@Composable
private fun PdfZoomPage(renderer: PdfRenderer, index: Int, placeholder: IntSize, widthPx: Int, modifier: Modifier = Modifier) {
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = 3f))
    var bucket by remember { mutableIntStateOf(1) }
    LaunchedEffect(zoom) {
        snapshotFlow { zoom.contentTransformation.takeIf { it.isSpecified }?.scaleMetadata?.userZoom ?: 1f }
            .debounce(200)
            .collect { bucket = maxOf(bucket, zoomBucket(it)) }
    }
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, index, bucket) {
        value = withContext(pdfDispatcher) { runCatching { renderPage(renderer, index, widthPx * bucket) }.getOrNull() }
    }
    val size = bitmap?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size(placeholder.width.toFloat(), placeholder.height.toFloat())
    LaunchedEffect(size) { zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(size)) }
    Box(modifier.semantics { contentDescription = "Page ${index + 1}, zoomed" }) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().zoomable(zoom)) }
            ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, index: Int, count: Int, placeholder: IntSize, widthPx: Int, matched: Boolean, onClick: () -> Unit) {
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, index, widthPx) {
        value = withContext(pdfDispatcher) { runCatching { renderPage(renderer, index, widthPx) }.getOrNull() }
    }
    PdfPageFrame(bitmap, placeholder, "Page ${index + 1} of $count", matched, Modifier.clickable(onClickLabel = "Zoom page", onClick = onClick))
}

@Composable
internal fun PdfPageFrame(shown: ImageBitmap?, placeholder: IntSize, label: String, matched: Boolean, modifier: Modifier = Modifier) {
    // Motion row 25: the matched page's border starts in the tonal container and fades out once.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { settled = true }
    val ring by animateColorAsState(if (settled) Color.Transparent else MaterialTheme.colorScheme.primaryContainer, tween(900), label = "matchedPulse")
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(if (shown != null) shown.width.toFloat() / shown.height else placeholder.width.toFloat() / placeholder.height)
            .background(Color.White)
            .then(if (matched) Modifier.border(3.dp, ring) else Modifier)
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

/** Renders at [widthPx], shrinking a very large page so neither side passes [MAX_PAGE_PX]. */
private fun renderPage(renderer: PdfRenderer, index: Int, widthPx: Int): ImageBitmap = renderer.openPage(index).use { page ->
    val scale = renderScale(page.width, page.height, widthPx)
    val bitmap = createBitmap(
        (page.width * scale).toInt().coerceAtLeast(1),
        (page.height * scale).toInt().coerceAtLeast(1),
    )
    bitmap.eraseColor(AndroidColor.WHITE)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    bitmap.asImageBitmap()
}
