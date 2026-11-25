package app.retriever.folders

import androidx.lifecycle.ViewModel
import app.retriever.BuildConfig
import app.retriever.core.FolderNode
import app.retriever.core.Retriever
import app.retriever.core.Words
import app.retriever.diagnostics.LogSink
import app.retriever.indexing.IndexService
import app.retriever.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [loadFailed]: the list never loaded, so the empty state says that instead of "no folders".
 * [error]: one line for the snackbar, cleared by [FoldersViewModel.errorShown].
 */
data class FoldersUi(
    /** The top of the folder tree; each opens on its own subfolders and files. */
    val folders: List<FolderNode> = emptyList(),
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val error: String? = null,
)

class FoldersViewModel(private val retriever: Retriever, private val indexer: IndexService) : ViewModel() {
    private val _ui = MutableStateFlow(FoldersUi())
    val ui: StateFlow<FoldersUi> = _ui.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        launchCatching(onError = { fail(it, Words.strings.couldntLoadYourFolders, loadFailed = true) }) {
            val folders = retriever.subfolders(null)
            _ui.update { it.copy(folders = folders, loading = false, loadFailed = false) }
        }
    }

    private fun fail(e: Exception, message: String, loadFailed: Boolean = false) {
        LogSink.write(BuildConfig.DEBUG, "FoldersViewModel", e)
        _ui.update { it.copy(loading = false, loadFailed = loadFailed, error = message) }
    }

    fun errorShown() = _ui.update { it.copy(error = null) }

    /** The changed exclude list makes the next sync a full scan, which forgets the folder's files. */
    fun exclude(folder: String) {
        launchCatching(onError = { fail(it, Words.strings.couldntStopSearchingFolder) }) {
            retriever.updatePrefs { it.copy(excludedFolders = it.excludedFolders + folder) }
            indexer.indexNow()
            refresh()
        }
    }
}
