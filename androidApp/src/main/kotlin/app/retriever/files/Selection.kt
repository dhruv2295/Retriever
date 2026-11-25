package app.retriever.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.lifecycle.SavedStateHandle
import app.retriever.R
import app.retriever.core.DeviceWords
import app.retriever.core.SourceFile
import app.retriever.core.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.mp.KoinPlatform

/** The files picked on a list, by uri; null while not selecting. Kept in [saved] when given, so process death keeps them. */
class Selection(private val saved: SavedStateHandle? = null) {
    private val _picked = MutableStateFlow(saved?.get<ArrayList<String>>(KEY)?.toSet())
    val picked: StateFlow<Set<String>?> = _picked.asStateFlow()

    fun start(uri: String? = null) = set(setOfNotNull(uri))

    /** Unpicking the last file ends selecting, as in Photos and Files. */
    fun toggle(uri: String) {
        val now = _picked.value ?: return
        set((if (uri in now) now - uri else now + uri).ifEmpty { null })
    }

    fun clear() = set(null)

    /** The list reloaded: a file no longer in it can't stay picked. */
    fun keepOnly(present: Collection<String>) {
        val now = _picked.value ?: return
        set(now intersect present.toSet())
    }

    private fun set(value: Set<String>?) {
        _picked.value = value
        saved?.set(KEY, value?.let { ArrayList(it) })
    }

    private companion object {
        const val KEY = "selection.picked"
    }
}

/** A picked row's look and what a screen reader says of it: tinted, and a checkbox that is checked. */
internal fun Modifier.picked(on: Boolean, tint: Color): Modifier = background(if (on) tint else Color.Transparent).semantics {
    role = Role.Checkbox
    toggleableState = ToggleableState(on)
}

/**
 * The bar while selecting, in place of the screen's own: how many are picked, and Share, Star and Delete for them. [files]
 * is the list on screen; Back ends selecting first. Each action ends it too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionBar(
    selection: Selection,
    picked: Set<String>,
    files: List<SourceFile>,
    starred: Set<String>,
    onStar: (SourceFile, Boolean) -> Unit,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val host = remember { KoinPlatform.getKoinOrNull()?.getOrNull<FileActions>() }
    val chosen = files.filter { it.uri in picked }.distinctBy { it.uri }
    BackHandler(onBack = selection::clear)
    TopAppBar(
        title = { Text(Words.strings.selectedCount(picked.size.toLong())) },
        navigationIcon = {
            IconButton(onClick = selection::clear) { Icon(painterResource(R.drawable.ic_close), contentDescription = Words.strings.done) }
        },
        actions = {
            if (chosen.isNotEmpty()) {
                IconButton(onClick = {
                    host?.selectAll(chosen, FileAction.SHARE)
                    selection.clear()
                }) { Icon(painterResource(R.drawable.ic_share), contentDescription = Words.strings.share) }
                val all = chosen.all { it.uri in starred }
                IconButton(onClick = {
                    chosen.forEach { onStar(it, !all) }
                    selection.clear()
                }) {
                    Icon(
                        painterResource(if (all) R.drawable.ic_star else R.drawable.ic_star_border),
                        contentDescription = if (all) Words.strings.removeFromStarred else Words.strings.addToStarred,
                    )
                }
                if (chosen.any { FileAction.DELETE in fileActions(it.uri) }) {
                    // Still picked while the dialog asks, so Cancel loses nothing.
                    IconButton(onClick = {
                        host?.selectAll(chosen, FileAction.DELETE, selection::clear)
                    }) { Icon(painterResource(R.drawable.ic_delete), contentDescription = Words.deleteFrom(DeviceWords.ANDROID)) }
                }
            }
        },
        windowInsets = windowInsets,
    )
}
