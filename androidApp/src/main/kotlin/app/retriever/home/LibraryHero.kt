package app.retriever.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.retriever.core.Kind
import app.retriever.core.Words
import app.retriever.ui.tabular

internal val libraryKinds = listOf(Kind.PHOTO, Kind.VIDEO, Kind.SCREENSHOT, Kind.PDF, Kind.DOC, Kind.AUDIO, Kind.OTHER)

/** The compact hero: the library, what has been read of it, and one status row. */
@Composable
internal fun LibraryHero(ui: HomeUi, status: @Composable () -> Unit) {
    Surface(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("library-hero"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(Words.YOUR_FILES, style = MaterialTheme.typography.titleMedium)
                Text(Words.inLibrary(ui.progress.libraryTotal), style = MaterialTheme.typography.headlineSmall.tabular())
                Text(Words.readCount(ui.contentRead, ui.partlyRead), style = MaterialTheme.typography.bodyMedium.tabular())
            }
            status()
        }
    }
}
