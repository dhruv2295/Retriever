package app.retriever.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.retriever.core.Kind
import app.retriever.nav.BrowseRoute
import app.retriever.nav.FoldersRoute
import app.retriever.nav.Route
import app.retriever.ui.Format
import app.retriever.ui.tabular

internal val libraryKinds = listOf(Kind.PHOTO, Kind.VIDEO, Kind.SCREENSHOT, Kind.PDF, Kind.DOC, Kind.AUDIO, Kind.OTHER)

@Composable
internal fun LibraryHero(ui: HomeUi, go: (Route) -> Unit, status: @Composable () -> Unit) {
    val counts = ui.counts.orEmpty()
    val total = counts.values.sum()
    val colors = MaterialTheme.colorScheme
    val palette = listOf(colors.primary, colors.secondary, colors.tertiary, colors.primary, colors.secondary, colors.tertiary, colors.outline)
    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Your library", style = MaterialTheme.typography.titleMedium)
                Text(Format.count(total), style = MaterialTheme.typography.displayMedium.tabular())
                Text(if (ui.trial) "Files in your whole library · trial reading" else "Files in your library", style = MaterialTheme.typography.bodyMedium)
            }
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(16.dp).clip(MaterialTheme.shapes.small),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                libraryKinds.forEachIndexed { index, kind ->
                    val count = counts[kind] ?: 0L
                    if (count > 0) {
                        val read = if (kind in ui.inside && kind != Kind.OTHER) (ui.readByKind[kind] ?: 0L).coerceIn(0L, count) else 0L
                        Box(
                            Modifier.weight(count.toFloat()).height(16.dp).background(palette[index].copy(alpha = 0.18f)).semantics {
                                contentDescription = "${Format.kindLabelPlural(kind)}: ${Format.count(read)} of ${Format.count(count)} read"
                            },
                        ) {
                            Box(Modifier.fillMaxWidth(read.toFloat() / count).height(16.dp).background(palette[index]))
                        }
                    }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(libraryKinds.filter { (counts[it] ?: 0L) > 0 }, key = { it.name }) { kind ->
                    AssistChip(onClick = {
                        go(BrowseRoute(kind = kind.name, title = Format.kindLabelPlural(kind)))
                    }, label = { Text("${Format.kindLabelPlural(kind)} ${Format.count(counts.getValue(kind))}") })
                }
            }
            status()
            Text("Top folders", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(ui.home?.topFolders.orEmpty(), key = { it.path }) { folder ->
                    val name = folder.path.trimEnd('/').substringAfterLast('/').ifBlank { "Files" }
                    AssistChip(onClick = { go(BrowseRoute(folder = folder.path, title = name)) }, label = { Text("$name ${Format.count(folder.count)}") })
                }
                item { AssistChip(onClick = { go(FoldersRoute) }, label = { Text("All folders") }) }
            }
        }
    }
}
