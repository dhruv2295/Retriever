package app.retriever.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.core.Retriever
import app.retriever.home.HomeUi
import app.retriever.nav.Route
import app.retriever.search.SearchActions
import app.retriever.search.SearchContent
import app.retriever.search.SearchUi
import app.retriever.search.SearchViewModel
import app.retriever.search.actions
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
    TryItContent(ui, search, searchActions, actions)
}

@Composable
fun TryItContent(ui: SetupUi, search: SearchUi, searchActions: SearchActions, actions: SetupActions, modifier: Modifier = Modifier) {
    SearchContent(
        search,
        HomeUi(),
        searchActions,
        modifier,
        topBarAction = { TextButton(onClick = actions.skip) { Text("Skip") } },
    ) { padding, _, side, _ -> TryItBody(ui, padding, side, actions) }
}

@Composable
private fun TryItBody(ui: SetupUi, padding: PaddingValues, side: Dp, actions: SetupActions) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp + side, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Try it on your files", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(
            if (ui.textOnly) {
                "Try a word you remember from a screenshot or a PDF."
            } else {
                "Try your own words: something you remember seeing in a photo or a screenshot."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Button(onClick = actions.continueToCommit, modifier = Modifier.fillMaxWidth()) { Text(if (ui.nothingLeft) "Done" else "Continue") }
    }
}
