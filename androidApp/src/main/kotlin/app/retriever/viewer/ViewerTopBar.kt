package app.retriever.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.files.FileAction
import app.retriever.files.FileActionItems
import app.retriever.files.FileActions
import app.retriever.nav.Route
import app.retriever.starred.StarButton
import app.retriever.starred.Starring
import app.retriever.ui.components.PressMenu
import app.retriever.ui.tabular
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform

/** Compact widths (a phone) keep Share and the overflow in the bar and let Star live in the overflow, so the filename keeps its room. */
private const val STAR_IN_BAR_DP = 600

internal fun starFitsInBar(screenWidthDp: Int) = screenWidthDp >= STAR_IN_BAR_DP

/** A viewer shown beside its list: [toggle] gives it the whole window, or the list its pane back. */
class PaneExpand(val expanded: Boolean, val toggle: () -> Unit)

/**
 * Every viewer's bar: Back (or [navigationIcon]), the filename over an optional [subtitle], then Share, Star and the
 * file menu. [onBack] also closes the viewer when its file is deleted or renamed away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerTopBar(
    title: String,
    uri: String,
    stars: Starring,
    onBack: () -> Unit,
    subtitle: String? = null,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    navigationIcon: @Composable () -> Unit = {
        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = Words.strings.back) }
    },
    /** Set for a document or PDF beside its list; null when the viewer has the window anyway. */
    pane: PaneExpand? = null,
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = {
            pane?.let { pane ->
                IconButton(onClick = pane.toggle) {
                    if (pane.expanded) {
                        Icon(painterResource(R.drawable.ic_close_fullscreen), contentDescription = Words.strings.showBesideList)
                    } else {
                        Icon(painterResource(R.drawable.ic_open_in_full), contentDescription = Words.strings.fullScreen)
                    }
                }
            }
            ViewerActions(uri, stars, onBack)
        },
        colors = colors,
    )
}

@Composable
private fun RowScope.ViewerActions(uri: String, stars: Starring, onDeleted: () -> Unit) {
    val retriever = remember { KoinPlatform.getKoinOrNull()?.getOrNull<Retriever>() }
    val host: FileActions = koinInject()
    var file by remember(uri) { mutableStateOf<SourceFile?>(null) }
    LaunchedEffect(uri) { file = retriever?.storedFile(uri)?.file }
    val starred by stars.starred.collectAsStateWithLifecycle()
    val on = uri in starred
    IconButton(enabled = file != null, onClick = { file?.let { host.select(it, FileAction.SHARE) {} } }) {
        Icon(painterResource(R.drawable.ic_share), contentDescription = Words.strings.share)
    }
    if (starFitsInBar(with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toInt() })) StarButton(on) { stars.toggle(uri) }
    PressMenu(content = { open ->
        IconButton(enabled = file != null, onClick = open) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = Words.strings.moreOptions) }
    }) { dismiss ->
        val shown = file ?: return@PressMenu
        FileActionItems(shown, dismiss, starred = on, onStar = { stars.set(uri, it) }, onDeleted = onDeleted)
    }
}

/** Previous and next file as visible buttons around "2 of 5", under the viewer; nothing when the list holds no neighbour. */
@Composable
internal fun ViewerStepBar(uri: String, onOpen: (Route) -> Unit) {
    val queue: ViewerQueue = koinInject()
    val near = remember(uri) { queue.neighbors(uri) }
    val label = near.label ?: return
    val step = { to: SourceFile? -> to?.let { Opener.routeFor(it) }?.let(onOpen) }
    BottomAppBar {
        IconButton(enabled = near.previous != null, onClick = { step(near.previous) }) {
            Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = Words.strings.previousFile)
        }
        Spacer(Modifier.weight(1f))
        Text(label, style = MaterialTheme.typography.labelLarge.tabular())
        Spacer(Modifier.weight(1f))
        IconButton(enabled = near.next != null, onClick = { step(near.next) }) {
            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = Words.strings.nextFile)
        }
    }
}
