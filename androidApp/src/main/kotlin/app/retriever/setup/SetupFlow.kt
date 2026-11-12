package app.retriever.setup

import android.util.Log
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.access.Photos
import app.retriever.core.ReadingSchedule
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
    /** The model is downloaded and checked; Get started downloads it when not, on a phone that runs it. */
    val modelPresent: Boolean = false,
    val downloadBytes: Long = 0,
    /** The demo's progress, from core; null until first read. */
    val taste: TasteProgress? = null,
    /** Why reading stopped, core's [app.retriever.core.ReadingStatus.pause]; null unless a stop holds it. */
    val pauseReason: String? = null,
    /** Every planned file in plan order, from core; null until its first delivery, which decides what the pile settles. */
    val sample: List<SampleFile>? = null,
    /** Settings' People switch, offered again on the first search. */
    val recognizePeople: Boolean = false,
) {
    val stage: SetupStage get() = setup.stage

    /** People looks at photos, so the first search offers it only once some are allowed. */
    val offersPeople: Boolean get() = access.allFiles || access.photos != Photos.NONE

    /**
     * The sample is being planned or read: one test for the mascot, the time line and the pile's freeze. Not `running`:
     * that drops between two sample batches, and the mascot would sit down for the gap.
     */
    val reading: Boolean get() = setup.step == SetupStep.PLAN || (pauseReason == null && (taste?.pending ?: 0) > 0)

    /** Get started downloads the model too, as Hello's note under it says. */
    val downloadsOnGetStarted: Boolean get() = SetupPolicy.downloadOnGetStarted(textOnly, modelPresent)
}

/**
 * Core's setup machine plus this phone's side effects: the permission state, and reading the sample or scheduling the
 * rest once per process. The machine runs in the app's scope, so leaving the screen never stops it; the sample's progress
 * and files are watched only while a screen collects [ui].
 */
class SetupFlow(
    private val retriever: Retriever,
    private val access: AccessService,
    private val index: IndexService,
    private val scope: CoroutineScope,
    private val notificationsGranted: () -> Boolean,
    /** The model's one-time download, with no question about mobile data. */
    private val startDownload: () -> Unit,
) {
    private val _ui = MutableStateFlow(
        SetupUi(
            setup = (retriever.setup.value ?: SetupInputs()).withAccess(access.state.value.ready),
            access = access.state.value,
            textOnly = retriever.textOnly,
            modelPresent = retriever.modelPresent.value,
            downloadBytes = retriever.modelDownloadBytes,
            recognizePeople = retriever.prefs.value.recognizePeople,
        ),
    )

    /** Core's library flows run only while this is collected, and five seconds past, so a rotation keeps them. */
    private val library = channelFlow<Nothing> {
        launch { retriever.readingStatus().collect { s -> _ui.update { it.copy(pauseReason = s.pause) } } }
        launch { retriever.sampleFiles().collect { f -> _ui.update { it.copy(sample = f) } } }
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
        scope.launch {
            retriever.modelPresent.collect { p -> _ui.update { it.copy(modelPresent = p, downloadBytes = retriever.modelDownloadBytes) } }
        }
        scope.launch { retriever.prefs.map { it.recognizePeople }.distinctUntilChanged().collect { on -> _ui.update { it.copy(recognizePeople = on) } } }
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

    /** Hello's Get started: the access screen next, with the model's download started where it runs and is missing. */
    fun getStarted() = launch {
        if (_ui.value.downloadsOnGetStarted) startDownload()
        // Android reads off the charger too, down to ReadingPolicy.BATTERY_FLOOR; iOS reads hidden only on external power.
        if (retriever.prefs.value.schedule == null) retriever.updatePrefs { it.withSchedule(ReadingSchedule.BATTERY) }
        retriever.setSetupMark(SetupMark.GREETED)
    }

    fun continueFromAccess() = launch {
        if (access.access().ready) index.startSetup()
    }

    /** Back from the access screen to Hello. */
    fun back() {
        SetupPolicy.back(_ui.value.stage)?.let(::mark)
    }

    /** The first search's Continue: everything is processed on the one schedule, and Home says what happens next. */
    fun finish() = launch { retriever.finishSetup() }

    /**
     * The first search's People switch, as Settings' without its confirm: before setup ends no name has been given, so
     * off has nothing to forget. On wakes the reader unless the sample's session holds it, as [readSample] does.
     */
    fun setPeople(on: Boolean) = launch {
        retriever.updatePrefs { it.copy(recognizePeople = on) }
        if (on && !index.foregroundHeld()) index.indexNow()
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

    /** Once per process: the reader starts the sample in front, in ReadingService, which finishes it after leaving too. */
    private fun readSample() {
        if (sampleRead) return
        sampleRead = true
        index.schedulePeriodic(retriever.prefs.value.onlyWhileCharging)
        if (!index.foregroundHeld()) index.indexNow()
    }
}

/** What setup's screens can ask for; every default does nothing, so a test passes only what it checks. */
data class SetupActions(
    val getStarted: () -> Unit = {},
    val allowFiles: () -> Unit = {},
    val chooseInstead: () -> Unit = {},
    val allowMedia: () -> Unit = {},
    val allowAudio: () -> Unit = {},
    val addFolder: () -> Unit = {},
    val continueFromAccess: () -> Unit = {},
    val seeAll: () -> Unit = {},
    val finish: () -> Unit = {},
    /** The first search's People switch. */
    val setPeople: (Boolean) -> Unit = {},
    /** The first search's quiet line: Use mobile data while waiting for Wi-Fi, Try again after a failed download. */
    val useMobileData: () -> Unit = {},
    val retryDownload: () -> Unit = {},
)
