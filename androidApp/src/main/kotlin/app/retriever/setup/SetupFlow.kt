package app.retriever.setup

import android.util.Log
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.core.Retriever
import app.retriever.core.SampleFile
import app.retriever.core.SetupInputs
import app.retriever.core.SetupMark
import app.retriever.core.SetupPolicy
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
    /** Files beyond the sample, core's [app.retriever.core.ReadingProgress.notScheduled]; null until counted. */
    val moreFiles: Long? = null,
    /** Why reading stopped, core's [app.retriever.core.ReadingStatus.pause]; null unless a stop holds it. */
    val pauseReason: String? = null,
    /** When this process started reading the sample, for core's time line; 0 before. */
    val sampleStartedAt: Long = 0,
    /** Every planned file in plan order, from core; null until its first delivery, which decides what the pile settles. */
    val sample: List<SampleFile>? = null,
    /** The index is reading now. */
    val running: Boolean = false,
) {
    val stage: SetupStage get() = setup.stage

    /** The sample is being planned or read: one test for the mascot, the time line and the pile's freeze. */
    val reading: Boolean get() = setup.step == SetupStep.PLAN || (running && pauseReason == null && (taste?.pending ?: 0) > 0)

    /** Nothing waits beyond the sample, so Try ends in Go to Home, not in the schedule choice; unknown is not nothing. */
    val nothingLeft get() = moreFiles == 0L
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
        scope.launch { retriever.readingStatus().collect { s -> _ui.update { it.copy(pauseReason = s.pause) } } }
        scope.launch { retriever.state.map { it.running }.distinctUntilChanged().collect { r -> _ui.update { it.copy(running = r) } } }
        scope.launch { retriever.sampleFiles().collect { f -> _ui.update { it.copy(sample = f) } } }
        scope.launch { retriever.counts().collect { c -> _ui.update { it.copy(moreFiles = c.progress.notScheduled) } } }
        scope.launch {
            retriever.tasteProgress().collect { p ->
                val sampling = !_ui.value.setup.decided
                _ui.update { it.copy(taste = p) }
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

    /**
     * Setup ends at launch, with access confirmed at once when it is in place; without it the welcome stays, and the
     * screen asks for it as the primary button does. The sample reads in the background.
     */
    fun searchByName() = launch {
        retriever.skipFirstRun()
        if (access.state.value.ready) retriever.setSetupMark(SetupMark.ACCESS_CHOSEN)
    }

    fun continueFromAccess() = mark(SetupMark.ACCESS_CHOSEN)

    fun tryNow() = mark(SetupMark.TRYING)

    /** Back from Try to the sample, or from the choice to Try. */
    fun back() {
        SetupPolicy.back(_ui.value.stage)?.let(::mark)
    }

    /** From Try: the schedule choice, or Home (read everything) when the sample already was everything. */
    fun continueToCommit() = launch {
        SetupPolicy.afterTry(_ui.value.nothingLeft)?.let { retriever.setSetupMark(it) } ?: retriever.commitScope()
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
    val searchByName: () -> Unit = {},
    val allowFiles: () -> Unit = {},
    val chooseInstead: () -> Unit = {},
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
