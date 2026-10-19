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
    Surface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("library-hero"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Your files", style = MaterialTheme.typography.titleMedium)
                Text("${Format.count(ui.progress.libraryTotal)} files in your library", style = MaterialTheme.typography.headlineSmall.tabular())
                Text(Format.readCount(ui.contentRead, ui.partlyRead), style = MaterialTheme.typography.bodyMedium.tabular())
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
