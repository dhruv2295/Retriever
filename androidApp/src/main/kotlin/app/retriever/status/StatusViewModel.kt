package app.retriever.status

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.core.IndexState
import app.retriever.core.Prefs
import app.retriever.core.Retriever
import app.retriever.core.Store
import app.retriever.core.Words
import app.retriever.home.celebrationOf
import app.retriever.indexing.IndexService
import app.retriever.ui.launchCatching
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Everything Status draws, as one value. [counts] is null until the first count arrives, so no "0 of 0" or "caught up" is drawn before then. */
data class StatusUi(
    val counts: Store.Counts?,
    val prefs: Prefs,
    val index: IndexState,
    val access: Access,
    val skipped: List<Store.StoredFile> = emptyList(),
    val folderError: String? = null,
    val error: String? = null,
    val readingStatus: app.retriever.core.ReadingStatus? = null,
)

class StatusViewModel(private val retriever: Retriever, private val indexer: IndexService, private val accessService: AccessService) : ViewModel() {
    private val skipped = MutableStateFlow<List<Store.StoredFile>>(emptyList())
    private val folderError = MutableStateFlow<String?>(null)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<StatusUi> = combine(
        retriever.counts(),
        retriever.prefs,
        combine(retriever.state, retriever.readingStatus(), ::Pair),
        accessService.state,
        combine(skipped, folderError, error, ::Triple),
    ) { counts, prefs, (index, status), access, (skipped, folderError, error) ->
        StatusUi(counts, prefs, index, access, skipped, folderError, error, status)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        StatusUi(null, retriever.prefs.value, retriever.state.value, accessService.state.value),
    )

    /** The header's caught-up pop; the same once-only rule as Home's status. */
    val celebration = celebrationOf(retriever)

    /** Permissions can change in Settings while Status is in the background; the screen syncs on resume. */
    fun refreshAccess() = accessService.refresh()

    /** The picker can reveal more photos without changing any permission, so this always syncs. */
    fun photosPicked() = accessService.changed()

    fun addFolder(uri: Uri) {
        folderError.value = accessService.addFolder(uri)
    }

    fun removeFolder(uri: String) = accessService.removeFolder(uri)

    fun resume() = attempt { indexer.resume() }

    fun pause() = attempt { indexer.pause() }

    fun readNow() = attempt { indexer.readNow() }

    fun stopReadNow() = attempt { indexer.endReadNow() }

    fun loadSkipped() = attempt { skipped.value = retriever.skipped(50) }

    fun retrySkipped() = attempt {
        retriever.retrySkipped()
        indexer.indexNow()
        skipped.value = emptyList()
    }

    private fun attempt(block: suspend () -> Unit) {
        error.value = null
        launchCatching(onError = {
            Log.e("Retriever", "StatusViewModel", it)
            error.value = Words.strings.thatDidntWork
        }) { block() }
    }
}
