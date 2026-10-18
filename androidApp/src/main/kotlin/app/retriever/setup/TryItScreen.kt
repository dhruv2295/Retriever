package app.retriever.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.home.HomeUi
import app.retriever.nav.Route
import app.retriever.search.SearchActions
import app.retriever.search.SearchContent
import app.retriever.search.SearchUi
import app.retriever.search.SearchViewModel
import app.retriever.search.actions
import app.retriever.ui.Format
import app.retriever.ui.components.ReadyFiles
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** Try it: the real Search, with every question kept inside the files the short read planned. */
@Composable
fun TryItScreen(ui: SetupUi, actions: SetupActions, onNavigate: (Route) -> Unit, retriever: Retriever = koinInject(), vm: SearchViewModel = koinViewModel()) {
    val ids by retriever.scopeIds.collectAsStateWithLifecycle()
    LaunchedEffect(ids) { vm.setScope(ids) }
    val search by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val searchActions = remember(context, onNavigate) { vm.actions(context, onNavigate) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var files by remember { mutableStateOf(emptyList<SourceFile>()) }
    LaunchedEffect(ids, ui.taste?.read) {
        val scope = ids?.toSet() ?: emptySet()
        files = retriever.readFiles(scope)
        suggestions = retriever.suggestions(scope)
    }
    TryItContent(ui, search, searchActions, actions, files = files, suggestions = suggestions)
}

@Composable
fun TryItContent(
    ui: SetupUi,
    search: SearchUi,
    searchActions: SearchActions,
    actions: SetupActions,
    modifier: Modifier = Modifier,
    files: List<SourceFile> = emptyList(),
    suggestions: List<String> = emptyList(),
) {
    SearchContent(
        search,
        HomeUi(suggestions = suggestions),
        searchActions,
        modifier,
        topBarAction = { TextButton(onClick = actions.skip) { Text("Skip") } },
    ) { padding, _, side, fill ->
        TryItBody(ui, padding, side, actions, files, suggestions, searchActions) { fill(it, true) }
    }
}

@Composable
private fun TryItBody(
    ui: SetupUi,
    padding: PaddingValues,
    side: Dp,
    actions: SetupActions,
    files: List<SourceFile>,
    suggestions: List<String>,
    searchActions: SearchActions,
    search: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp + side, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Try it on your files", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text("Search something you remember, or try one of these.", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (suggestions.isNotEmpty()) {
                    for (term in suggestions) SuggestionChip(onClick = { search(term) }, label = { Text(term) })
                } else {
                    for (kind in files.map { it.kind }.distinct()) {
                        val label = Format.kindLabelPlural(kind)
                        SuggestionChip(onClick = {
                            searchActions.onChip(kind)
                            search(label)
                        }, label = { Text(label) })
                    }
                }
            }
            ReadyFiles(files, files.size, onOpen = searchActions.onOpenFile)
        }
        Button(onClick = actions.continueToCommit, modifier = Modifier.fillMaxWidth()) { Text(if (ui.nothingLeft) "Done" else "Continue") }
    }
}
