package app.retriever.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.motion.MascotMood
import app.retriever.nav.DocRoute
import app.retriever.nav.Route
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.StarHolder
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.contentPaddingFor
import app.retriever.ui.components.nameHighlighted
import app.retriever.ui.tabular
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** The in-app reader: the text the index holds for a document, opened on the paragraph that matched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocScreen(route: DocRoute, onBack: () -> Unit, replace: (Route) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ui by koinViewModel<DocViewModel>().ui.collectAsStateWithLifecycle()
    val stars = koinViewModel<StarHolder>().stars
    val snackbar = remember { SnackbarHostState() }
    StarFailureEffect(stars, snackbar)
    val openWith = { Opener.view(context, route.uri, route.mime) }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            ViewerTopBar(route.name, route.uri, stars, onBack)
        },
        bottomBar = { ViewerStepBar(route.uri, replace) },
    ) { padding -> DocContent(ui, padding, openWith) }
}

@Composable
internal fun DocContent(ui: DocUi, padding: PaddingValues, openWith: () -> Unit) {
    when (ui) {
        DocUi.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

        DocUi.NotReadYet -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            EmptyState(
                "Retriever hasn't read this yet",
                "It's waiting its turn with the other new files. Open it in another app to see it now.",
                R.drawable.ic_history,
                action = "Open in another app" to openWith,
            )
        }

        DocUi.Empty -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            EmptyState(
                "Retriever couldn't read text in this file",
                "It may be a scan or hold only pictures. Open it in another app to see it.",
                R.drawable.ic_warning_amber,
                action = "Open in another app" to openWith,
            )
        }

        DocUi.Failed -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            EmptyState(
                "Couldn't open this file",
                "It may have been moved or deleted.",
                R.drawable.ic_warning_amber,
                action = "Open in another app" to openWith,
                mood = MascotMood.TROUBLE,
            )
        }

        is DocUi.Text -> {
            var at by rememberSaveable { mutableIntStateOf(ui.matchIndex) }
            val list = rememberLazyListState(initialFirstVisibleItemIndex = at)
            val scope = rememberCoroutineScope()
            val mark = MaterialTheme.colorScheme.primaryContainer
            val style = remember(mark) { SpanStyle(background = mark, fontWeight = FontWeight.Bold) }
            val bar = ui.matches.isNotEmpty()
            Box(Modifier.fillMaxSize()) {
                SelectionContainer {
                    LazyColumn(
                        state = list,
                        contentPadding = contentPaddingFor(padding, if (bar) 88.dp else 16.dp, side = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(ui.paragraphs) { text ->
                            val shown = remember(text, ui.query, style) { nameHighlighted(text, ui.query, style) }
                            Text(shown, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (bar) {
                    MatchBar(ui.matches, at, { step ->
                        stepMatch(ui.matches, at, step)?.let {
                            at = it
                            scope.launch { list.animateScrollToItem(it) }
                        }
                    }, Modifier.align(Alignment.BottomCenter).padding(bottom = padding.calculateBottomPadding()))
                }
            }
        }
    }
}

/** "Match 2 of 5" with Previous and Next, so a long document is read match by match. */
@Composable
private fun MatchBar(matches: List<Int>, at: Int, step: (Int) -> Unit, modifier: Modifier = Modifier) {
    val position = matches.indexOf(at) + 1
    Surface(modifier.padding(16.dp), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { step(-1) }, enabled = matches.size > 1) {
                Icon(painterResource(R.drawable.ic_expand_less), contentDescription = "Previous match")
            }
            Text(
                if (position > 0) "Match $position of ${matches.size}" else "${matches.size} matches",
                style = MaterialTheme.typography.labelLarge.tabular(),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = { step(1) }, enabled = matches.size > 1) {
                Icon(painterResource(R.drawable.ic_expand_more), contentDescription = "Next match")
            }
        }
    }
}
