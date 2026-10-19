package app.retriever.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.retriever.BuildConfig
import app.retriever.core.Backend
import app.retriever.core.CalibrationResult
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ModelBuild
import app.retriever.core.Prefs
import app.retriever.core.Retriever
import app.retriever.diagnostics.LogSink
import app.retriever.indexing.IndexService
import app.retriever.model.ModelRepository
import app.retriever.model.ModelState
import app.retriever.model.TuneState
import app.retriever.model.tuneSummary
import app.retriever.ui.launchCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val SAMPLE_PER_KIND = 50

/**
 * [appScope] runs what must outlive the screen (a tune, a model removal, a download cancel); [tuner] is one
 * calibration run, passed in because it needs the app's file sources.
 */
class SettingsViewModel(
    private val retriever: Retriever,
    private val models: ModelRepository,
    private val storage: StorageService,
    private val indexer: IndexService,
    private val appScope: CoroutineScope,
    private val tuner: suspend () -> CalibrationResult,
) : ViewModel() {
    private data class Local(val tune: TuneState = TuneState.Idle, val storage: StorageUsage? = null, val message: String? = null)

    private val local = MutableStateFlow(Local())

    // Only the three facts Settings shows: an indexing progress tick must not rebuild the page.
    private val running = retriever.state.map { it.facts() }.distinctUntilChanged()

    val uiState: StateFlow<SettingsUi> = combine(
        combine(retriever.prefs, running, models.state, ::Triple),
        combine(retriever.modelMissing, retriever.requiredBuild, local, ::Triple),
    ) { (prefs, index, download), (missing, build, l) -> ui(prefs, index, download, missing, build, l) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ui(
                retriever.prefs.value,
                retriever.state.value.facts(),
                models.state.value,
                retriever.modelMissing.value,
                retriever.requiredBuild.value,
                local.value,
            ),
        )

    private fun IndexState.facts() = Triple(backend, detail, build)

    private fun ui(prefs: Prefs, facts: Triple<Backend?, String?, ModelBuild?>, download: ModelState, missing: Boolean, build: ModelBuild, l: Local) =
        SettingsUi(
            prefs, backend = facts.first, detail = facts.second, loadedBuild = facts.third, tune = l.tune, storage = l.storage, download = download,
            modelMissing = missing, build = build, textOnly = retriever.textOnly, ramGb = retriever.probe.ramGb,
            readable = retriever.readableKinds, message = l.message,
        )

    private fun fail(e: Exception) {
        // A full disk (SQLiteFullException) lands here too: the person sees it, the app keeps running.
        LogSink.write(BuildConfig.DEBUG, "settings action failed", e)
        local.update { it.copy(message = "Couldn’t save this change. Try again.") }
    }

    /** Like [launchCatching], for work that must outlive the screen. */
    private fun inApp(block: suspend () -> Unit) {
        appScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private suspend fun loadStorage() {
        val usage = storage.usage()
        local.update { it.copy(storage = usage) }
    }

    fun refreshStorage() {
        launchCatching(::fail) { loadStorage() }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    fun setSearchInside(kind: Kind, on: Boolean) {
        launchCatching(::fail) {
            val wasOn = kind in retriever.prefs.value.kinds
            retriever.updatePrefs { p -> p.copy(kinds = if (on) p.kinds + kind else p.kinds - kind) }
            if (on && !wasOn) {
                retriever.requeue(setOf(kind))
                indexer.indexNow()
            }
        }
    }

    fun setReadingSchedule(value: app.retriever.core.ReadingSchedule) = launchCatching(::fail) {
        retriever.setReadingSchedule(value)
        indexer.schedulePeriodic(value == app.retriever.core.ReadingSchedule.CHARGING)
        indexer.indexNow()
    }

    fun readNow() = launchCatching(::fail) {
        retriever.readNow()
        indexer.indexNow()
    }
    fun pause() = launchCatching(::fail) { indexer.pause() }
    fun resume() = launchCatching(::fail) { indexer.resume() }
    fun setOnThisDay(on: Boolean) = launchCatching(::fail) { retriever.updatePrefs { it.copy(showOnThisDay = on) } }

    fun setOnlyWhileCharging(on: Boolean) {
        launchCatching(::fail) {
            retriever.setReadingSchedule(if (on) app.retriever.core.ReadingSchedule.CHARGING else app.retriever.core.ReadingSchedule.BATTERY)
            indexer.schedulePeriodic(on)
        }
    }

    // Turning it off clears the saved searches; Retriever.updatePrefs does that.
    fun setRememberSearches(on: Boolean) {
        launchCatching(::fail) { retriever.updatePrefs { it.copy(rememberSearches = on) } }
    }

    fun setMobileData(on: Boolean) {
        launchCatching(::fail) { retriever.updatePrefs { it.copy(allowMobileData = on) } }
    }

    fun setDynamicColor(on: Boolean) {
        launchCatching(::fail) { retriever.updatePrefs { it.copy(dynamicColor = on) } }
    }

    // Restarts indexing so the next sync trims to (or grows back from) the sample right away.
    fun setSampleMode(on: Boolean) {
        launchCatching(::fail) {
            retriever.updatePrefs { it.copy(sampleSize = if (on) SAMPLE_PER_KIND else null) }
            indexer.restart()
        }
    }

    // The next sync sees the changed exclude list and does a full scan, which brings the folder back.
    fun removeExcluded(folder: String) {
        launchCatching(::fail) {
            retriever.updatePrefs { it.copy(excludedFolders = it.excludedFolders - folder) }
            indexer.indexNow()
        }
    }

    fun tuneAgain() {
        if (local.value.tune is TuneState.Running) return
        local.update { it.copy(tune = TuneState.Running) }
        // The app scope: leaving Settings must not cancel a tune halfway (indexing waits meanwhile).
        appScope.launch {
            val next = try {
                val r = tuner()
                TuneState.Done(tuneSummary(r.chosen, r.backends.firstOrNull { it.backend == r.chosen }?.imageMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LogSink.write(BuildConfig.DEBUG, "calibration failed", e)
                TuneState.Failed(e.message ?: e.toString())
            }
            local.update { it.copy(tune = next) }
        }
    }

    /** Removes the model; names and words keep searching. The message clears with [messageShown]. */
    fun freeUpSpace() = inApp {
        storage.freeUpSpace()
        loadStorage()
        local.update { it.copy(message = "Model removed") }
    }

    init {
        // A download started before the process was killed carries on in DownloadManager: show and finish it.
        launchCatching(::fail) { models.sync(retriever.requiredBuild.value) }
        launchCatching(::fail) { models.state.drop(1).filter { it == ModelState.Done }.collect { loadStorage() } }
    }

    fun downloadAgain() = models.start(retriever.requiredBuild.value)

    fun cancelDownload() = inApp { models.cancel(retriever.requiredBuild.value) }
}
