package app.retriever.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
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
    val palette = libraryColors()
    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("library-hero"), shape = MaterialTheme.shapes.large, color = colors.surfaceContainer) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Your library", style = MaterialTheme.typography.titleMedium)
                Text("${Format.count(ui.searchable)} searchable of ${Format.count(total)}", style = MaterialTheme.typography.headlineSmall.tabular())
            }
            Canvas(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(16.dp).clip(MaterialTheme.shapes.small)) {
                val kinds = libraryKinds.filter { (counts[it] ?: 0) > 0 }
                val minimum = minOf(size.height, size.width / kinds.size.coerceAtLeast(1))
                val remaining = (size.width - minimum * kinds.size).coerceAtLeast(0f)
                var x = 0f
                for (kind in kinds) {
                    val count = counts.getValue(kind)
                    val width = minimum + remaining * count / total
                    val color = palette[libraryKinds.indexOf(kind)]
                    val read = if (kind in ui.inside && kind != Kind.OTHER) (ui.readByKind[kind] ?: 0).coerceIn(0, count) else 0
                    drawRect(color.copy(alpha = 0.18f), Offset(x, 0f), Size(width, size.height))
                    if (read > 0) {
                        val readWidth = maxOf(size.height / 2, width * read / count).coerceAtMost(width)
                        drawRect(color, Offset(x, 0f), Size(readWidth, size.height))
                        if (ui.trial) drawLine(colors.onSurface, Offset(x, size.height), Offset(x + readWidth, size.height), 2.dp.toPx())
                    }
                    x += width
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(libraryKinds.filter { (counts[it] ?: 0L) > 0 }, key = { it.name }) { kind ->
                    AssistChip(
                        onClick = {
                            go(BrowseRoute(kind = kind.name, title = Format.kindLabelPlural(kind)))
                        },
                        label = { Text("${Format.kindLabelPlural(kind)} ${Format.count(counts.getValue(kind))}") },
                        leadingIcon = { Box(Modifier.size(10.dp).background(palette[libraryKinds.indexOf(kind)], CircleShape)) },
                        modifier = Modifier.semantics {
                            val read = if (kind in ui.inside && kind != Kind.OTHER) ui.readByKind[kind] ?: 0 else 0
                            contentDescription = "${Format.kindLabelPlural(kind)}: ${Format.count(read)} of ${Format.count(counts.getValue(kind))} read"
                        },
                    )
                }
            }
            status()
        }
    }
}

@Composable
internal fun LibraryFolders(ui: HomeUi, go: (Route) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
        items(ui.home?.topFolders.orEmpty(), key = { it.path }) { folder ->
            val name = folder.path.trimEnd('/').substringAfterLast('/').ifBlank { "Files" }
            AssistChip(onClick = { go(BrowseRoute(folder = folder.path, title = name)) }, label = { Text("$name ${Format.count(folder.count)}") })
        }
        item { AssistChip(onClick = { go(FoldersRoute) }, label = { Text("All folders") }) }
    }
}

/** Distinct kind hues keep their meaning under wallpaper themes; tone follows the actual surface. */
@Composable
@ReadOnlyComposable
internal fun libraryColors(): List<Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) {
        listOf(0xFF9CCAFF, 0xFFD0BCFF, 0xFF7CDAC4, 0xFFFFBC7D, 0xFFFFB0CD, 0xFFCAD982, 0xFFC8C6C0).map { Color(it) }
    } else {
        listOf(0xFF205EA6, 0xFF7041A0, 0xFF006B58, 0xFF955000, 0xFF9E3764, 0xFF596500, 0xFF60605A).map { Color(it) }
    }
}
