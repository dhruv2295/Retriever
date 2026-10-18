package app.retriever.duplicates

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import app.retriever.BuildConfig
import app.retriever.access.AccessService
import app.retriever.core.DuplicateGroup
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.android.SafSource
import app.retriever.diagnostics.LogSink
import app.retriever.indexing.IndexService
import app.retriever.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val MIN_SIZE = 64L * 1024

/**
 * A group's files with each file once. A picked folder and MediaStore can list the same file: at one path, or on a
 * memory card under two volume names, where it shows as a folder's row and a MediaStore row with the same name and
 * time (the group already shares size and content). Deleting either would lose the only copy, so the folder's row goes.
 */
internal fun oneEntryPerFile(files: List<SourceFile>): List<SourceFile> {
    val media = files.filter { !it.uri.startsWith(SafSource.PREFIX) }

    // MediaStore keeps whole seconds.
    fun sameFile(a: SourceFile, b: SourceFile) = a.name == b.name && a.modifiedAt / 1000 == b.modifiedAt / 1000
    return files
        .filter { f -> !f.uri.startsWith(SafSource.PREFIX) || media.none { sameFile(it, f) } }
        .distinctBy { it.folder to it.name }
}

data class DuplicatesState(
    val scanning: Boolean = true,
    val scannedFiles: Long = 0,
    val groups: List<DuplicateGroup> = emptyList(),
    val selected: Set<String> = emptySet(),
    val deleting: Boolean = false,
    val failed: Int = 0,
    val error: String? = null,
    val keepOne: Boolean = false,
    /** Android's own "allow Retriever to delete?" prompt, waiting to be shown. */
    val confirm: IntentSender? = null,
)

class DuplicatesViewModel(
    private val retriever: Retriever,
    private val indexer: IndexService,
    private val accessService: AccessService,
    private val deleter: MediaDeleter,
) : ViewModel() {
    private val _state = MutableStateFlow(DuplicatesState())
    val state: StateFlow<DuplicatesState> = _state.asStateFlow()

    init {
        scan()
    }

    fun scan() {
        _state.value = DuplicatesState()
        launchCatching(onError = {
            LogSink.write(BuildConfig.DEBUG, "duplicate scan failed", it)
            _state.update { s -> s.copy(scanning = false, error = "Something went wrong. Try again.") }
        }) {
            val groups = retriever.duplicates(MIN_SIZE)
                .map { g -> g.copy(files = oneEntryPerFile(g.files)) }
                .filter { it.files.size >= 2 }
            // Keep the oldest copy of each group (files arrive oldest first).
            val selected = groups.flatMap { g -> g.files.drop(1).map { it.uri } }.toSet()
            val scanned = retriever.fileCount()
            _state.update {
                it.copy(scanning = false, groups = groups, selected = selected, scannedFiles = scanned)
            }
        }
    }

    /** Never selects the last unselected copy in a group: deleting every copy would lose the file. */
    fun toggle(uri: String) = _state.update { s ->
        if (uri in s.selected) return@update s.copy(selected = s.selected - uri, keepOne = false)
        val group = s.groups.firstOrNull { g -> g.files.any { it.uri == uri } }
        val lastCopy = group != null && group.files.all { it.uri == uri || it.uri in s.selected }
        if (lastCopy) s.copy(keepOne = true) else s.copy(selected = s.selected + uri, keepOne = false)
    }

    fun selectedBytes(s: DuplicatesState): Long = s.groups.sumOf { g -> g.size * g.files.count { it.uri in s.selected } }

    fun deleteSelected() {
        val uris = _state.value.selected.toList()
        if (uris.isEmpty() || _state.value.deleting) return
        _state.update { it.copy(deleting = true, failed = 0) }
        launchCatching(onError = {
            LogSink.write(BuildConfig.DEBUG, "delete failed", it)
            _state.update { s -> s.copy(deleting = false, error = null, failed = uris.size) }
        }) {
            // Without all-files access, Android itself asks the person before others' photos and videos go.
            accessService.refresh()
            val media = uris.filterNot { it.startsWith(SafSource.PREFIX) }
            val ask = if (accessService.state.value.allFiles) emptyList() else media
            val tried = uris - ask.toSet()
            val deleted = deleter.delete(tried)
            val request = if (ask.isEmpty()) null else deleter.request(ask)
            if (request == null) finishDelete(uris, deleted) else _state.update { it.copy(confirm = request) }
        }
    }

    /**
     * Android's confirmation came back. Whatever the person chose, the index is the truth: sync and look again, so
     * no state has to survive the prompt (the process may have died behind it). Cancel is no failure.
     */
    fun onConfirmed() {
        indexer.indexNow()
        scan()
    }

    fun confirmShown() = _state.update { it.copy(confirm = null) }

    private fun finishDelete(uris: List<String>, deleted: List<String>) {
        // The next sync drops the deleted files from the index.
        indexer.indexNow()
        val gone = deleted.toSet()
        _state.update { s ->
            val groups = s.groups
                .map { g -> g.copy(files = g.files.filter { it.uri !in gone }) }
                .filter { it.files.size >= 2 }
            s.copy(
                groups = groups,
                selected = s.selected - gone,
                deleting = false,
                failed = uris.size - deleted.size,
            )
        }
    }
}
