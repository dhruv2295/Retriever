package app.retriever.setup

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.DeviceWords
import app.retriever.core.Kind
import app.retriever.core.Retriever
import app.retriever.core.SamplePile
import app.retriever.core.TasteProgress
import app.retriever.core.Words
import app.retriever.home.HomeUi
import app.retriever.model.UpgradeFlow
import app.retriever.motion.Mascot
import app.retriever.nav.Route
import app.retriever.search.SearchActions
import app.retriever.search.SearchContent
import app.retriever.search.SearchUi
import app.retriever.search.SearchViewModel
import app.retriever.search.actions
import app.retriever.ui.components.CountBar
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** The demo: processing and search on one screen. Names cover the whole phone, the words inside only the demo's files. */
@Composable
fun DemoScreen(
    ui: SetupUi,
    actions: SetupActions,
    onNavigate: (Route) -> Unit,
    retriever: Retriever = koinInject(),
    upgrade: UpgradeFlow = koinInject(),
    vm: SearchViewModel = koinViewModel(),
) {
    val ids by retriever.scopeIds.collectAsStateWithLifecycle()
    LaunchedEffect(ids) { vm.setScope(ids) }
    val search by vm.ui.collectAsStateWithLifecycle()
    val stage by upgrade.stage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val searchActions = remember(context, onNavigate) { vm.actions(context, onNavigate) }
    val suggestions by remember(ids) { retriever.suggestions(ids?.toSet() ?: emptySet()) }.collectAsStateWithLifecycle(null)
    val shown = remember(search, stage) { search.copy(upgrade = stage) }
    DemoContent(ui, shown, searchActions, actions, suggestions = suggestions)
}

/**
 * The demo inside the real Search: its body above, the search field and Continue pinned below. The field works from the
 * first frame; its results open the full-screen search, and one Back returns here.
 */
@Composable
fun DemoContent(
    ui: SetupUi,
    search: SearchUi,
    searchActions: SearchActions,
    actions: SetupActions,
    modifier: Modifier = Modifier,
    /** Null until core answers; chips once there are three, the hint until then. */
    suggestions: List<String>? = emptyList(),
    reduceMotion: Boolean = !ValueAnimator.areAnimatorsEnabled(),
) {
    SearchContent(
        search,
        HomeUi(suggestions = suggestions),
        searchActions,
        modifier,
        placeholder = Words.SEARCH_DEMO,
        footer = {
            Button(onClick = actions.continueToCommit, modifier = Modifier.widthIn(max = 808.dp).fillMaxWidth()) { Text(Words.CONTINUE) }
        },
    ) { padding, _, side, fill ->
        // At least the viewport tall with the spare height split above and below, as the welcome and the choice do.
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                    .wrapContentWidth().widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 24.dp + side, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                DemoBody(ui, ui.taste ?: TasteProgress(emptyMap(), emptySet(), 0, null, 0, 0), suggestions, actions, reduceMotion, fill)
            }
        }
    }
}

/**
 * The title, the dog beside the pile, the count with its bar, one muted line, the chips or the hint, and See the files.
 * Every row is laid out from the first frame, so the group never shifts as the files are picked and processed.
 */
@Composable
internal fun DemoBody(
    ui: SetupUi,
    taste: TasteProgress,
    suggestions: List<String>?,
    actions: SetupActions,
    reduceMotion: Boolean,
    fill: (String, Boolean) -> Unit,
) {
    val planned = taste.planned.toLong()
    val done = taste.done.toLong()
    val title = Words.demoTitle(planned)
    // The plan's progress per kind is said, not drawn.
    val spoken = (listOf(title) + taste.byKind.map { (k, n) -> Words.kindRead(n.first.toLong(), n.second.toLong(), k) }).joinToString(", ")
    val picking = planned == 0L
    val count = if (picking) Words.DEMO_PICKING else Words.readOf(done, planned)
    val kinds = (taste.byKind.keys.toList() + listOf(Kind.PHOTO, Kind.PDF, Kind.DOC)).distinct().take(SamplePile.SHOWN)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Two lines held: the title before picking is shorter, and the group must not move when the number lands.
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            minLines = 2,
            modifier = Modifier.semantics {
                heading()
                contentDescription = spoken
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // A fixed box: the resting pose is taller than the walking one, and the row must not grow when the demo ends.
            Mascot(ui.mood(), Modifier.size(96.dp), settles = false)
            // A new pile at the first delivery, so the files already processed then never replay.
            key(ui.sample == null) {
                SamplePile(
                    SamplePile.shown(ui.sample.orEmpty()),
                    kinds,
                    reduceMotion,
                    count,
                    Words.seeAll(planned),
                    actions.seeAll.takeIf { !picking },
                    Modifier.width(SamplePile.FOOTPRINT_DP.dp),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(count, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            CountBar(done, planned, Modifier.fillMaxWidth().clearAndSetSemantics {})
        }
        // One muted slot, two lines held: picking, done, a stop's reason, else the time left.
        val note = when {
            picking -> Words.DEMO_PICKING_NOTE
            taste.pending == 0 -> Words.demoDone(ui.rest, DeviceWords.ANDROID)
            ui.pauseReason != null -> ui.pauseReason
            else -> Words.demoNote(taste.secondsLeft)
        }
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, minLines = 2)
        // One chip row's height, held from the first frame.
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            if (suggestions != null && suggestions.size >= DEMO_CHIPS) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(Words.TRY_LABEL, style = MaterialTheme.typography.labelLarge)
                    for (term in suggestions) key(term) { SuggestionChip(onClick = { fill(term, true) }, label = { Text(term) }) }
                }
            } else {
                Text(Words.DEMO_HINT, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        // Laid out before the files are picked too, so it never pushes the group when it appears.
        TextButton(onClick = actions.seeAll, enabled = !picking, modifier = if (picking) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier) {
            Text(Words.seeAll(planned))
            Icon(painterResource(R.drawable.ic_keyboard_arrow_right), contentDescription = null)
        }
    }
}

/** Suggestions before the chips replace the hint. */
private const val DEMO_CHIPS = 3
