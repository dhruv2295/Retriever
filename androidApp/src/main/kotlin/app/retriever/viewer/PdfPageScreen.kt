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
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import app.retriever.R
import app.retriever.core.Retriever
import app.retriever.core.highlightTerms
import app.retriever.core.motion.MascotMood
import app.retriever.di.AppScope
import app.retriever.nav.FoldersRoute
import app.retriever.nav.PdfRoute
import app.retriever.nav.Route
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.StarHolder
import app.retriever.ui.ScrollingEffect
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.Thumbnails
import app.retriever.ui.components.contentPaddingFor
import kotlinx.coroutines.CoroutineScope
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
import java.io.FileNotFoundException
import android.graphics.Color as AndroidColor

// PdfRenderer is not thread-safe: this screen's one renderer, one call at a time (every open, render and close), within the
// process's PDF budget, which the grids' PDF thumbnails share.
@OptIn(ExperimentalCoroutinesApi::class)
private val pdfDispatcher = Thumbnails.pdfDispatcher.limitedParallelism(1)

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

/** The bar's second line: where the search matched, then where the reader is. */
internal fun pdfSubtitle(page: Int, count: Int, matchedPage: Int?): String = (matchedPage?.let { "Match on page $it · " } ?: "") + "Page $page of $count"

/** The page "Matched here" marks, or null when no search matched (a PDF opened from a list, or found by name). */
internal fun PdfRoute.matchedIndex(): Int? = if (matched) page - 1 else null

private sealed interface PdfState {
    data object Loading : PdfState
    data object Locked : PdfState
    data object NoAccess : PdfState
    data object Missing : PdfState
    data object Failed : PdfState

    /** [placeholder]: the matched page's size, the shape every page takes until it renders at its own. */
    class Ready(val renderer: PdfRenderer, val count: Int, val placeholder: IntSize) : PdfState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPageScreen(
    route: PdfRoute,
    onBack: () -> Unit,
    replace: (Route) -> Unit,
    modifier: Modifier = Modifier,
    pane: PaneExpand? = null,
    retriever: Retriever = koinInject(),
) {
    val context = LocalContext.current
    val appScope: CoroutineScope = koinInject(AppScope)
    val stars = koinViewModel<StarHolder>().stars
    val snackbar = remember { SnackbarHostState() }
    StarFailureEffect(stars, snackbar)
    val state by produceState<PdfState>(PdfState.Loading, route.uri) {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        value = withContext(pdfDispatcher) {
            try {
                val opened = try {
                    context.contentResolver.openFileDescriptor(route.uri.toUri(), "r") ?: return@withContext PdfState.Failed
                } catch (_: SecurityException) {
                    return@withContext PdfState.NoAccess
                }
                fd = opened
                val r = PdfRenderer(opened).also { renderer = it }
                // Measures one page, not all of them: a 1,500-page book would hold the spinner for seconds.
                val shape = r.openPage((route.page - 1).coerceIn(0, r.pageCount - 1)).use { IntSize(it.width, it.height) }
                PdfState.Ready(r, r.pageCount, shape)
            } catch (_: SecurityException) {
                PdfState.Locked
            } catch (_: FileNotFoundException) {
                PdfState.Missing
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
    val terms = remember(route.query) { highlightTerms(route.query) }
    // Before Android 15 the pages and the indexer's PDF reads share PdfRenderer's process-wide lock.
    ScrollingEffect({ listState.isScrollInProgress }, retriever::setUiBusy)
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    // The page shown full screen, if any; kept across rotation.
    var zoomed by rememberSaveable { mutableStateOf<Int?>(null) }
    val openElsewhere = { Opener.view(context, route.uri, "application/pdf") }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ViewerTopBar(
                route.title,
                route.uri,
                stars,
                onBack,
                subtitle = (state as? PdfState.Ready)?.let { pdfSubtitle((zoomed ?: current) + 1, it.count, route.page.takeIf { route.matched }) },
                navigationIcon = {
                    if (zoomed != null) {
                        IconButton(onClick = { zoomed = null }) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close page") }
                    } else {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                    }
                },
                pane = pane,
            )
        },
        bottomBar = { ViewerStepBar(route.uri, replace) },
    ) { padding ->
        when (val s = state) {
            PdfState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            PdfState.Locked -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    "This PDF needs a password",
                    "Retriever can still find it by name. You can open it in another app.",
                    R.drawable.ic_lock,
                    action = "Open in another app" to openElsewhere,
                )
            }

            PdfState.NoAccess -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    "Access to this folder has changed",
                    "Choose the folder again to open this file.",
                    R.drawable.ic_folder,
                    action = "Manage access" to { replace(FoldersRoute) },
                    mood = MascotMood.TROUBLE,
                )
            }

            PdfState.Missing -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    "This file is no longer available",
                    "It may have been moved or deleted.",
                    R.drawable.ic_warning_amber,
                    action = "Go back" to onBack,
                    mood = MascotMood.TROUBLE,
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
                        PdfPage(s.renderer, i, s.count, s.placeholder, widthPx, terms, matched = i == route.matchedIndex(), onClick = { zoomed = i })
                    }
                }
                zoomed?.let {
                    BackHandler { zoomed = null }
                    PdfZoomPage(
                        s.renderer,
                        it,
                        s.placeholder,
                        widthPx,
                        terms,
                        // The empty pointerInput keeps touches off the list beneath while the page is still rendering.
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {}
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
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
private fun PdfZoomPage(renderer: PdfRenderer, index: Int, placeholder: IntSize, widthPx: Int, terms: List<String>, modifier: Modifier = Modifier) {
    val zoom = rememberZoomableState(ZoomSpec(maxZoomFactor = 3f))
    var bucket by remember { mutableIntStateOf(1) }
    LaunchedEffect(zoom) {
        snapshotFlow { zoom.contentTransformation.takeIf { it.isSpecified }?.scaleMetadata?.userZoom ?: 1f }
            .debounce(200)
            .collect { bucket = maxOf(bucket, zoomBucket(it)) }
    }
    // The words are searched on the first render only; a sharper bucket keeps its marks.
    var marks by remember { mutableStateOf<List<Rect>?>(null) }
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, index, bucket) {
        val page = withContext(pdfDispatcher) {
            runCatching {
                renderPage(
                    renderer,
                    index,
                    widthPx * bucket,
                    if (marks ==
                        null
                    ) {
                        terms
                    } else {
                        emptyList()
                    },
                )
            }.getOrNull()
        }
        if (marks == null) marks = page?.marks
        value = page?.bitmap
    }
    val mark = MaterialTheme.colorScheme.primaryContainer
    val size = bitmap?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size(placeholder.width.toFloat(), placeholder.height.toFloat())
    LaunchedEffect(size) { zoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(size)) }
    Box(modifier.semantics { contentDescription = "Page ${index + 1}, zoomed" }) {
        bitmap?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().zoomable(zoom).drawWithContent {
                    drawContent()
                    val fit = minOf(this.size.width / it.width, this.size.height / it.height)
                    val shown = Size(it.width * fit, it.height * fit)
                    drawMarks(marks.orEmpty(), Offset((this.size.width - shown.width) / 2, (this.size.height - shown.height) / 2), shown, mark)
                },
            )
        }
            ?: CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

@Composable
private fun PdfPage(
    renderer: PdfRenderer,
    index: Int,
    count: Int,
    placeholder: IntSize,
    widthPx: Int,
    terms: List<String>,
    matched: Boolean,
    onClick: () -> Unit,
) {
    val page: RenderedPage? by produceState<RenderedPage?>(null, index, widthPx) {
        value = withContext(pdfDispatcher) { runCatching { renderPage(renderer, index, widthPx, terms) }.getOrNull() }
    }
    PdfPageFrame(
        page?.bitmap,
        placeholder,
        "Page ${index + 1} of $count",
        matched,
        Modifier.clickable(onClickLabel = "Zoom page", onClick = onClick),
        page?.marks.orEmpty(),
    )
}

@Composable
internal fun PdfPageFrame(
    shown: ImageBitmap?,
    placeholder: IntSize,
    label: String,
    matched: Boolean,
    modifier: Modifier = Modifier,
    marks: List<Rect> = emptyList(),
) {
    // Motion row 25: the matched page's border starts in the tonal container and fades out once.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { settled = true }
    // Read while drawing, so the fade redraws the page and does not recompose it on every frame.
    val ring = animateColorAsState(if (settled) Color.Transparent else MaterialTheme.colorScheme.primaryContainer, tween(900), label = "matchedPulse")
    val mark = MaterialTheme.colorScheme.primaryContainer
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(if (shown != null) shown.width.toFloat() / shown.height else placeholder.width.toFloat() / placeholder.height)
            .background(Color.White)
            .then(
                if (matched) {
                    Modifier.drawWithContent {
                        drawContent()
                        // Inside the edge, as Modifier.border draws it.
                        val stroke = 3.dp.toPx()
                        drawRect(ring.value, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                    }
                } else {
                    Modifier
                },
            )
            .semantics { contentDescription = label + if (matched) ", where your search matched" else "" },
    ) {
        shown?.let {
            Image(
                it,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    drawMarks(marks, Offset.Zero, size, mark)
                },
            )
        }
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

/** A rendered page and where the searched words sit on it, as fractions of the page. */
private class RenderedPage(val bitmap: ImageBitmap, val marks: List<Rect>)

/** Fills each of [marks] (fractions of the page) over the page drawn at [origin] in [shown]. */
private fun DrawScope.drawMarks(marks: List<Rect>, origin: Offset, shown: Size, color: Color) {
    for (m in marks) {
        drawRect(color, origin + Offset(m.left * shown.width, m.top * shown.height), Size(m.width * shown.width, m.height * shown.height), alpha = 0.4f)
    }
}

/**
 * Renders at [widthPx], shrinking a very large page so neither side passes [MAX_PAGE_PX], and on Android 15 and later
 * finds [terms] in the page's text layer. A scanned page has none, so it gets no marks.
 */
private fun renderPage(renderer: PdfRenderer, index: Int, widthPx: Int, terms: List<String>): RenderedPage = renderer.openPage(index).use { page ->
    val scale = renderScale(page.width, page.height, widthPx)
    val bitmap = createBitmap(
        (page.width * scale).toInt().coerceAtLeast(1),
        (page.height * scale).toInt().coerceAtLeast(1),
    )
    bitmap.eraseColor(AndroidColor.WHITE)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    val marks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && terms.isNotEmpty()) {
        // Bounds are in page points, and one match can span lines.
        terms.flatMap { page.searchText(it) }.flatMap { it.bounds }.map {
            Rect(it.left / page.width, it.top / page.height, it.right / page.width, it.bottom / page.height)
        }
    } else {
        emptyList()
    }
    RenderedPage(bitmap.asImageBitmap(), marks)
}
