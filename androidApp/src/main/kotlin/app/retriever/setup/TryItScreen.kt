package app.retriever.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.core.Retriever
import app.retriever.core.UpgradePolicy
import app.retriever.core.Words
import app.retriever.home.HomeUi
import app.retriever.model.UpgradeActions
import app.retriever.model.UpgradeCard
import app.retriever.model.UpgradeFlow
import app.retriever.model.UpgradeUi
import app.retriever.model.actions
import app.retriever.nav.Route
import app.retriever.search.SearchActions
import app.retriever.search.SearchContent
import app.retriever.search.SearchUi
import app.retriever.search.SearchViewModel
import app.retriever.search.actions
import app.retriever.ui.components.Muted
import app.retriever.ui.components.ReadyFiles
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Try: the real Search; names cover the whole library, the words inside only the sample. */
@Composable
fun TryItScreen(
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
    val prefs by retriever.prefs.collectAsStateWithLifecycle()
    val up by upgrade.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val searchActions = remember(context, onNavigate) { vm.actions(context, onNavigate) }
    val upgradeActions = remember(upgrade) { upgrade.actions(dismissible = true) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(ids, ui.taste?.read) { suggestions = retriever.suggestions(ids?.toSet() ?: emptySet()) }
    // Home's rule: offered once the person has searched and until Not now, then the download and the tune.
    val offer = up.takeIf { UpgradePolicy.showOnHome(it.stage, prefs) }
    val shown = remember(search, up.stage) { search.copy(upgrade = up.stage) }
    TryItContent(ui, shown, searchActions, actions, suggestions = suggestions, upgrade = offer, upgradeActions = upgradeActions)
}

@Composable
fun TryItContent(
    ui: SetupUi,
    search: SearchUi,
    searchActions: SearchActions,
    actions: SetupActions,
    modifier: Modifier = Modifier,
    suggestions: List<String> = emptyList(),
    upgrade: UpgradeUi? = null,
    upgradeActions: UpgradeActions = UpgradeActions(),
) {
    SearchContent(
        search,
        HomeUi(suggestions = suggestions),
        searchActions,
        modifier,
        topBarAction = { TextButton(onClick = actions.skip) { Text(Words.GO_TO_HOME) } },
    ) { padding, _, side, fill ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp + side, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Words.TRY_TITLE, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(Words.TRY_BODY, style = MaterialTheme.typography.bodyLarge)
            search.scope?.let { Muted(Words.searchingSample(it.size.toLong())) }
            if (suggestions.isEmpty()) {
                Text(Words.TRY_WITHOUT_SUGGESTIONS, style = MaterialTheme.typography.bodyMedium)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (term in suggestions) key(term) { SuggestionChip(onClick = { fill(term, true) }, label = { Text(term) }) }
                }
            }
            val files = remember(ui.sample) { ui.sample.orEmpty().filter { it.read }.map { it.file } }
            ReadyFiles(files, onOpen = searchActions.onOpenFile)
            Button(onClick = actions.continueToCommit, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.nothingLeft) Words.GO_TO_HOME else Words.CHOOSE_WHEN)
            }
            upgrade?.let { UpgradeCard(it, upgradeActions) }
        }
    }
}
