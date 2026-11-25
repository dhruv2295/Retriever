package app.retriever.duplicates

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import app.retriever.BuildConfig
import app.retriever.access.AccessService
import app.retriever.core.DuplicateGroup
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import app.retriever.core.Words
import app.retriever.core.android.SafSource
import app.retriever.diagnostics.LogSink
import app.retriever.indexing.IndexService
import app.retriever.ui.launchCatching
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

private const val MIN_SIZE = 64L * 1024

/**
 * A group's files with each file once. A picked folder and MediaStore can list the same file: at one path, or on a
 * memory card under two volume names, where it shows as a folder's row and a MediaStore row with the same name and
 * time (the group already shares size and content). Deleting either would lose the only copy, so the folder's row goes.
 */
internal fun oneEntryPerFile(files: List<SourceFile>): List<SourceFile> {
    // MediaStore keeps whole seconds.
    fun SourceFile.identity() = name to modifiedAt / 1000
    val media = files.filterNot { it.uri.startsWith(SafSource.PREFIX) }.mapTo(HashSet()) { it.identity() }
    return files
        .filter { !it.uri.startsWith(SafSource.PREFIX) || it.identity() !in media }
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
)

class DuplicatesViewModel(
    private val retriever: Retriever,
    private val indexer: IndexService,
    private val accessService: AccessService,
    private val deleter: MediaDeleter,
) : ViewModel() {
    private val _state = MutableStateFlow(DuplicatesState())
    val state: StateFlow<DuplicatesState> = _state.asStateFlow()
    private val confirmations = Channel<IntentSender>(Channel.BUFFERED)
    val confirm = confirmations.receiveAsFlow()

    init {
        scan()
    }

    fun scan() {
        _state.value = DuplicatesState()
        launchCatching(onError = {
            LogSink.write(BuildConfig.DEBUG, "duplicate scan failed", it)
            _state.update { s -> s.copy(scanning = false, error = Words.strings.somethingWentWrongTryAgain) }
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
        val now = _state.value
        val uris = now.selected.toList()
        if (uris.isEmpty() || now.deleting) return
        _state.update { it.copy(deleting = true, failed = 0) }
        launchCatching(onError = {
            LogSink.write(BuildConfig.DEBUG, "delete failed", it)
            _state.update { s -> s.copy(deleting = false, error = null, failed = uris.size) }
        }) {
            // Only copies that still match the kept file go; the rest count as not deleted.
            val copies = retriever.verifiedCopies(now.groups, now.selected)
            accessService.refresh()
            val result = deleter.deleteOrAsk(copies, accessService.state.value.allFiles)
            val consent = result.consent
            if (consent == null) finishDelete(uris, result.deleted) else confirmations.send(consent)
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
