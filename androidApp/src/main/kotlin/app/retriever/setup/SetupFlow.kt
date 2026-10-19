package app.retriever.setup

import android.util.Log
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.core.ReadEstimate
import app.retriever.core.Retriever
import app.retriever.core.SetupInputs
import app.retriever.core.SetupMark
import app.retriever.core.SetupStage
import app.retriever.core.SetupStep
import app.retriever.core.TasteProgress
import app.retriever.indexing.IndexService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Setup's screen state: core's [SetupInputs] with this phone's access, and the sample's progress. */
data class SetupUi(
    val setup: SetupInputs = SetupInputs(),
    val access: Access = Access.NONE,
    /** Too little memory for the model: names and words only. */
    val textOnly: Boolean = false,
    val ramGb: Double = 0.0,
    /** The sample's progress, from core; null until first read. */
    val taste: TasteProgress? = null,
    /** The rest of the library at this phone's pace; null until a pace is measured. No files left: the plan was everything. */
    val estimate: ReadEstimate? = null,
    val moreFiles: Long? = null,
    /** The shared reading policy's explanation while reading waits; null while it reads. */
    val pauseReason: String? = null,
    /** When this process started reading the sample, for core's time line; 0 before. */
    val sampleStartedAt: Long = 0,
) {
    val stage: SetupStage get() = setup.stage

    /** Nothing waits beyond the sample, so Try ends in Go to Home, not in the schedule choice. */
    val nothingLeft get() = estimate?.files == 0L
}

/**
 * Core's setup machine plus this phone's side effects: the permission state, and reading the sample or scheduling the
 * rest once per process. Runs in the app's scope, so leaving the screen never stops it.
 */
class SetupFlow(
    private val retriever: Retriever,
    private val access: AccessService,
    private val index: IndexService,
    private val scope: CoroutineScope,
    private val notificationsGranted: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val reading = retriever.state

    private val _ui = MutableStateFlow(
        SetupUi(
            setup = (retriever.setup.value ?: SetupInputs()).withAccess(access.state.value.ready),
            access = access.state.value,
            textOnly = retriever.textOnly,
            ramGb = retriever.probe.ramGb,
        ),
    )
    val ui: StateFlow<SetupUi> = _ui

    private var sampleRead = false
    private var scheduled = false

    init {
        scope.launch {
            combine(retriever.setup.filterNotNull(), access.state) { s, a -> s.withAccess(a.ready) to a }.collect { (s, a) ->
                _ui.update { it.copy(setup = s, access = a) }
            }
        }
        scope.launch { retriever.readingStatus().collect { s -> _ui.update { it.copy(pauseReason = if (s.active) null else s.title) } } }
        scope.launch { retriever.counts().collect { c -> _ui.update { it.copy(moreFiles = c.progress.notScheduled) } } }
        scope.launch {
            retriever.tasteProgress().collect { p ->
                val sampling = !_ui.value.setup.decided
                _ui.update { it.copy(taste = p) }
                if (_ui.value.stage == SetupStage.TRY) refreshRest()
                // "Ready to try" reaches a person who left during the sample; on screen, the button says it. Claimed while
                // visible too, so a sample seen on screen is never announced later.
                if (p.readyToTry && sampling && p.byKind.isNotEmpty() && (retriever.uiVisible || notificationsGranted()) &&
                    retriever.claimSampleNotification()
                ) {
                    index.notifyReadyToTry(p.read)
                }
            }
        }
        // Each step is safe twice; a failed plan is not retried in a loop, only on the next launch or access change.
        scope.launch { _ui.map { it.setup.step }.distinctUntilChanged().collect(::run) }
    }

    private suspend fun run(step: SetupStep) {
        when (step) {
            SetupStep.NONE -> Unit

            SetupStep.PLAN -> try {
                retriever.planOrCommit()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Retriever", "first-run plan failed", e)
            }

            SetupStep.READ_SAMPLE -> readSample()

            SetupStep.SCHEDULE -> if (!scheduled) {
                scheduled = true
                index.indexNow()
                index.schedulePeriodic(retriever.prefs.value.onlyWhileCharging)
            }
        }
    }

    /** Permissions can change while the person is away. */
    fun refresh() {
        scope.launch {
            retriever.refreshSetUp()
            access.refresh()
            _ui.update { it.copy(taste = retriever.tasteProgress().first()) }
        }
    }

    fun getStarted() = mark(SetupMark.STARTED)

    /** Access still shows, then Home; the sample reads in the background. */
    fun searchByName() = launch {
        retriever.setSetupMark(SetupMark.STARTED)
        retriever.skipFirstRun()
    }

    fun continueFromAccess() = mark(SetupMark.ACCESS_CHOSEN)

    fun tryNow() = mark(SetupMark.TRYING)

    /** Back from Try to the sample, or from the choice to Try. */
    fun back() = when (_ui.value.stage) {
        SetupStage.TRY -> mark(SetupMark.ACCESS_CHOSEN)
        SetupStage.CHOOSE -> mark(SetupMark.TRYING)
        else -> Unit
    }

    /** From Try: the schedule choice, or Home (read everything) when the sample already was everything. */
    fun continueToCommit() = launch {
        refreshRest()
        if (_ui.value.nothingLeft) retriever.commitScope() else retriever.setSetupMark(SetupMark.CHOOSING)
    }

    /** While charging or Read now: the rest is read, and the person is done choosing. */
    fun commit(whileCharging: Boolean) = launch { retriever.commit(whileCharging) }

    /** Not now: the scope stays on the sample and Home offers the choice again. */
    fun decideLater() = launch {
        retriever.decideLater()
        index.cancel()
    }

    /** Go to Home; the sample carries on. */
    fun skip() = launch { retriever.skipFirstRun() }

    private fun mark(m: SetupMark) = launch { retriever.setSetupMark(m) }

    private fun launch(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Retriever", "setup action failed", e)
            }
        }
    }

    private suspend fun refreshRest() {
        val rest = try {
            retriever.estimateAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Retriever", "estimate failed", e)
            null
        }
        _ui.update { if (it.estimate == rest) it else it.copy(estimate = rest) }
    }

    /** Once per process: the sample runs here, in sight; hidden, it stops and the worker carries on within the plan. */
    private fun readSample() {
        if (sampleRead) return
        sampleRead = true
        _ui.update { it.copy(sampleStartedAt = now()) }
        index.schedulePeriodic(retriever.prefs.value.onlyWhileCharging)
        scope.launch {
            try {
                if (!index.readTrial()) index.indexNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Retriever", "short read failed", e)
                index.indexNow()
            }
        }
    }
}

/** What setup's screens can ask for; every default does nothing, so a test passes only what it checks. */
class SetupActions(
    val getStarted: () -> Unit = {},
    val searchByName: () -> Unit = {},
    val allowFiles: () -> Unit = {},
    val allowMedia: () -> Unit = {},
    val addFolder: () -> Unit = {},
    val continueFromAccess: () -> Unit = {},
    val tryNow: () -> Unit = {},
    val seeAll: () -> Unit = {},
    val continueToCommit: () -> Unit = {},
    val commit: (whileCharging: Boolean) -> Unit = {},
    val decideLater: () -> Unit = {},
    val skip: () -> Unit = {},
)
