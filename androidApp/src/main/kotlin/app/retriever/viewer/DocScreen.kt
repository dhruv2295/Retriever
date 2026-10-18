package app.retriever.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.motion.MascotMood
import app.retriever.nav.DocRoute
import app.retriever.starred.StarButton
import app.retriever.starred.StarFailureEffect
import app.retriever.starred.StarHolder
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.contentPaddingFor
import app.retriever.ui.components.nameHighlighted
import org.koin.androidx.compose.koinViewModel

private val MATCH = SpanStyle(fontWeight = FontWeight.Bold)

/** The in-app reader: the text the index holds for a document, opened on the paragraph that matched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocScreen(route: DocRoute, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ui by koinViewModel<DocViewModel>().ui.collectAsStateWithLifecycle()
    val stars = koinViewModel<StarHolder>().stars
    val starred by stars.starred.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    StarFailureEffect(stars, snackbar)
    val openWith = { Opener.view(context, route.uri, route.mime) }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(route.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                },
                actions = {
                    StarButton(route.uri in starred) { stars.toggle(route.uri) }
                    IconButton(onClick = { Opener.share(context, route.uri, route.mime) }) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = "Share")
                    }
                    IconButton(onClick = openWith) {
                        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = "Open with")
                    }
                },
            )
        },
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
            val list = rememberLazyListState(initialFirstVisibleItemIndex = ui.matchIndex)
            LazyColumn(
                state = list,
                contentPadding = contentPaddingFor(padding, 16.dp, side = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(ui.paragraphs) { text ->
                    val shown = remember(text, ui.query) { nameHighlighted(text, ui.query, MATCH) }
                    Text(shown, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
