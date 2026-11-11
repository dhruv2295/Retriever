package app.retriever.setup

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.DescriptionState
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.core.SamplePile
import app.retriever.core.TasteProgress
import app.retriever.core.Words
import app.retriever.model.FreeUpSpaceButton
import app.retriever.model.MobileDataAsk
import app.retriever.model.UpgradeFlow
import app.retriever.model.actions
import app.retriever.model.description
import app.retriever.model.percent
import app.retriever.motion.Mascot
import app.retriever.nav.Route
import app.retriever.nav.SearchRoute
import app.retriever.search.HomeShell
import app.retriever.search.SearchActions
import app.retriever.search.SearchUi
import app.retriever.search.SearchViewModel
import app.retriever.search.actions
import app.retriever.ui.components.CountBar
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** The first search: processing and search on one screen. Names cover the whole phone, the words inside only the recent files. */
@Composable
fun DemoScreen(
    ui: SetupUi,
    actions: SetupActions,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
    retriever: Retriever = koinInject(),
    upgrade: UpgradeFlow = koinInject(),
    vm: SearchViewModel = koinViewModel(),
) {
    val ids by retriever.scopeIds.collectAsStateWithLifecycle()
    LaunchedEffect(ids) { vm.setScope(ids) }
    val search by vm.ui.collectAsStateWithLifecycle()
    val up by upgrade.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val searchActions = remember(context, onNavigate) { vm.actions(context, onNavigate) }
    val suggestions by remember(ids) { retriever.suggestions(ids?.toSet() ?: emptySet()) }.collectAsStateWithLifecycle(null)
    val stage = up.stage
    val shown = remember(search, stage) { search.copy(upgrade = stage) }
    DemoContent(ui, shown, searchActions, actions, up.description, up.percent, modifier, pillModifier, suggestions, open = onNavigate)
    // Try again after a failed download asks before using mobile data, as Home does.
    MobileDataAsk(up, remember(upgrade) { upgrade.actions(dismissible = false) })
}

/**
 * The first search in Home's frame: its body above, the search pill and Continue pinned below. The pill and the chips
 * open the real search, kept to the first run's files, and one Back returns here. [description]: search by
 * description's state, [percent] its download's.
 */
@Composable
fun DemoContent(
    ui: SetupUi,
    search: SearchUi,
    searchActions: SearchActions,
    actions: SetupActions,
    description: DescriptionState,
    percent: Int?,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
    /** Null until core answers; chips once there are three, the hint until then. */
    suggestions: List<String>? = emptyList(),
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
    open: (SearchRoute) -> Unit = {},
) {
    val scoped = { route: SearchRoute -> open(route.copy(scoped = true)) }
    val fill = { q: String, run: Boolean -> scoped(SearchRoute(q, run)) }
    HomeShell(
        search,
        searchActions,
        scoped,
        modifier,
        pillModifier = pillModifier,
        placeholder = Words.firstSearchHint(description),
        footer = {
            Button(onClick = actions.finish, modifier = Modifier.widthIn(max = 808.dp).fillMaxWidth().heightIn(min = 56.dp)) { Text(Words.CONTINUE) }
        },
    ) { padding, _ ->
        // At least the viewport tall with the spare height split above and below, as Hello and the access screen do.
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .wrapContentWidth().widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                if (description == DescriptionState.READY) ReadyBanner()
                DemoBody(ui, ui.taste ?: TasteProgress(emptyMap(), emptySet(), 0, 0, 0), description, percent, suggestions, actions, reduceMotion, fill)
            }
        }
    }
}

/** Search by description arrived: the hint below now asks for a description too. */
@Composable
private fun ReadyBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
            }
            Column {
                Text(Words.DESCRIPTION_READY, style = MaterialTheme.typography.titleSmall)
                Text(Words.DESCRIPTION_READY_BODY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The dog, the title, the count with its bar, search by description's line, the pile, and the chips or the hint. Every
 * row but the description line is laid out from the first frame, so the group never shifts as the files are processed.
 */
@Composable
internal fun DemoBody(
    ui: SetupUi,
    taste: TasteProgress,
    description: DescriptionState,
    percent: Int?,
    suggestions: List<String>?,
    actions: SetupActions,
    reduceMotion: Boolean,
    fill: (String, Boolean) -> Unit,
) {
    val planned = taste.planned.toLong()
    val done = taste.done.toLong()
    // The plan's progress per kind is said, not drawn.
    val spoken = (listOf(Words.FIRST_LOOK) + taste.byKind.map { (k, n) -> Words.kindRead(n.first.toLong(), n.second.toLong(), k) }).joinToString(", ")
    val picking = planned == 0L
    val count = if (picking) Words.DEMO_PICKING else Words.readOf(done, planned)
    val kinds = (taste.byKind.keys.toList() + listOf(Kind.PHOTO, Kind.PDF, Kind.DOC)).distinct().take(SamplePile.SHOWN)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A fixed box: the resting pose is taller than the walking one, and the group must not move when processing ends.
        Mascot(ui.mood(), Modifier.size(160.dp, 130.dp), settles = false)
        Text(
            Words.FIRST_LOOK,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).semantics {
                heading()
                contentDescription = spoken
            },
        )
        Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        CountBar(done, planned, Modifier.width(240.dp).clearAndSetSemantics {})
        // A stop holds the processing itself, so it says why before anything about the download.
        val paused = ui.pauseReason
        (paused ?: Words.descriptionLine(description, percent))?.let { line ->
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                if (paused == null) {
                    when (description) {
                        DescriptionState.WAITING_FOR_WIFI -> TextButton(onClick = actions.useMobileData) { Text(Words.USE_MOBILE_DATA) }

                        DescriptionState.FAILED -> TextButton(onClick = actions.retryDownload) { Text(Words.TRY_AGAIN) }

                        DescriptionState.NO_SPACE -> {
                            TextButton(onClick = actions.retryDownload) { Text(Words.TRY_AGAIN) }
                            FreeUpSpaceButton()
                        }

                        else -> Unit
                    }
                }
            }
        }
        // A new pile at the first delivery, so the files already processed then never replay.
        key(ui.sample == null) {
            SamplePile(
                SamplePile.shown(ui.sample.orEmpty()),
                kinds,
                reduceMotion,
                count,
                Words.seeAll(planned),
                actions.seeAll.takeIf { !picking },
                Modifier.padding(top = 10.dp).width(SamplePile.FOOTPRINT_DP.dp),
            )
        }
        // One chip row's height, held from the first frame.
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            if (suggestions != null && suggestions.size >= DEMO_CHIPS) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(Words.TRY_LABEL, style = MaterialTheme.typography.labelLarge)
                    for (term in suggestions) key(term) { SuggestionChip(onClick = { fill(term, true) }, label = { Text(term) }) }
                }
            } else {
                Text(Words.DEMO_HINT, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Suggestions before the chips replace the hint. */
private const val DEMO_CHIPS = 3
