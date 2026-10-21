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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Setup's screen state: core's [SetupInputs] with this phone's access, and the demo's progress. */
data class SetupUi(
    val setup: SetupInputs = SetupInputs(),
    val access: Access = Access.NONE,
    /** Too little memory for the model: names and words only. */
    val textOnly: Boolean = false,
    val ramGb: Double = 0.0,
    /** The demo's progress, from core; null until first read. */
    val taste: TasteProgress? = null,
    /** Files beyond the demo, core's [app.retriever.core.ReadingProgress.notScheduled]; null until counted. */
    val moreFiles: Long? = null,
    /** Files still to process, core's [app.retriever.core.ReadingProgress.rest]: null until the phone's files are all counted. */
    val rest: Long? = null,
    /** Why reading stopped, core's [app.retriever.core.ReadingStatus.pause]; null unless a stop holds it. */
    val pauseReason: String? = null,
    /** Every planned file in plan order, from core; null until its first delivery, which decides what the pile settles. */
    val sample: List<SampleFile>? = null,
) {
    val stage: SetupStage get() = setup.stage

    /**
     * The sample is being planned or read: one test for the mascot, the time line and the pile's freeze. Not `running`:
     * that drops between two sample batches, and the mascot would sit down for the gap.
     */
    val reading: Boolean get() = setup.step == SetupStep.PLAN || (pauseReason == null && (taste?.pending ?: 0) > 0)

    /** Nothing waits beyond the demo, so its Continue processes everything, with no choice to make; unknown is not nothing. */
    val nothingLeft get() = moreFiles == 0L
}

/**
 * Core's setup machine plus this phone's side effects: the permission state, and reading the sample or scheduling the
 * rest once per process. The machine runs in the app's scope, so leaving the screen never stops it; the library's
 * counts and the sample's files are watched only while a screen collects [ui].
 */
class SetupFlow(
    private val retriever: Retriever,
    private val access: AccessService,
    private val index: IndexService,
    private val scope: CoroutineScope,
    private val notificationsGranted: () -> Boolean,
) {
    private val _ui = MutableStateFlow(
        SetupUi(
            setup = (retriever.setup.value ?: SetupInputs()).withAccess(access.state.value.ready),
            access = access.state.value,
            textOnly = retriever.textOnly,
            ramGb = retriever.probe.ramGb,
        ),
    )

    /** Core's library flows run only while this is collected, and five seconds past, so a rotation keeps them. */
    private val library = channelFlow<Nothing> {
        launch { retriever.readingStatus().collect { s -> _ui.update { it.copy(pauseReason = s.pause) } } }
        launch { retriever.sampleFiles().collect { f -> _ui.update { it.copy(sample = f) } } }
        launch { retriever.counts().collect { c -> _ui.update { it.copy(moreFiles = c.progress.notScheduled, rest = c.progress.rest) } } }
        launch { retriever.tasteProgress().collect { p -> _ui.update { it.copy(taste = p) } } }
    }
    val ui: StateFlow<SetupUi> = merge(_ui, library).stateIn(scope, SharingStarted.WhileSubscribed(5_000), _ui.value)

    /** Setup still shows, from what is stored now: for the start screen, read before anything collects [ui]. */
    val showSetup: Boolean get() = _ui.value.setup.showSetup

    private var sampleRead = false
    private var scheduled = false

    init {
        scope.launch {
            combine(retriever.setup.filterNotNull(), access.state) { s, a -> s.withAccess(a.ready) to a }.collect { (s, a) ->
                _ui.update { it.copy(setup = s, access = a) }
            }
        }
        // "The demo is done" reaches a person who left during the demo; on screen, the demo says it. Claimed while visible
        // too, so a demo seen on screen is never announced later. Watched only until the demo is decided or done.
        scope.launch {
            retriever.setup.filterNotNull().map { !it.decided }.distinctUntilChanged().collectLatest { sampling ->
                if (!sampling) return@collectLatest
                val p = retriever.tasteProgress().first { it.planned > 0 && it.pending == 0 && (retriever.uiVisible || notificationsGranted()) }
                if (retriever.claimSampleNotification()) index.notifyDemoDone(p.done, p.planned)
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
        }
    }

    /**
     * Skip the demo: the choice of when to process, with access confirmed at once when it is in place; without it the
     * welcome stays, and the screen asks for it as the primary button does.
     */
    fun skipDemo() = launch {
        retriever.skipFirstRun()
        if (access.state.value.ready) retriever.setSetupMark(SetupMark.ACCESS_CHOSEN)
    }

    fun continueFromAccess() = mark(SetupMark.ACCESS_CHOSEN)

    /** Back from the choice: to the demo, or to the welcome after Skip the demo. */
    fun back() {
        _ui.value.setup.let { SetupPolicy.back(it.stage, it.skipped) }?.let(::mark)
    }

    /** The demo's Continue: the choice of when to process, or Home (process everything) when the demo already was everything. */
    fun continueToCommit() = launch {
        SetupPolicy.afterDemo(_ui.value.nothingLeft)?.let { retriever.setSetupMark(it) } ?: retriever.commitScope()
    }

    /** While charging or Read now: the rest is read, and the person is done choosing. */
    fun commit(whileCharging: Boolean) = launch { retriever.commit(whileCharging) }

    /** Not now: the scope stays on the demo and Home offers the choice again. */
    fun decideLater() = launch {
        retriever.decideLater()
        index.cancel()
    }

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
    val skipDemo: () -> Unit = {},
    val allowFiles: () -> Unit = {},
    val chooseInstead: () -> Unit = {},
    val allowMedia: () -> Unit = {},
    val addFolder: () -> Unit = {},
    val continueFromAccess: () -> Unit = {},
    val seeAll: () -> Unit = {},
    val continueToCommit: () -> Unit = {},
    val commit: (whileCharging: Boolean) -> Unit = {},
    val decideLater: () -> Unit = {},
)
