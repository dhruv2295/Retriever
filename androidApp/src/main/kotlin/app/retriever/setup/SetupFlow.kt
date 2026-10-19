/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt (Apache-2.0). Modified for Retriever: only the in-progress download branch, driven by ModelDownload's polled progress, with a free-space check and a hash check before the model counts as ready; notification permission uses the same check-then-RequestPermission pattern as ui/common/Utils.kt.

package app.retriever.setup

import android.util.Log
import app.retriever.access.Access
import app.retriever.access.AccessService
import app.retriever.core.CalibrationResult
import app.retriever.core.ModelBuild
import app.retriever.core.ReadEstimate
import app.retriever.core.Retriever
import app.retriever.core.TasteProgress
import app.retriever.indexing.IndexService
import app.retriever.model.ModelDownload
import app.retriever.model.ModelRepository
import app.retriever.model.ModelState
import app.retriever.model.TuneState
import app.retriever.model.tuneSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SetupUi(
    val started: Boolean = false,
    val access: Access = Access.NONE,
    /** Too little memory for the model: no download, no tuning, names and words only. */
    val textOnly: Boolean = false,
    val ramGb: Double = 0.0,
    val notificationAsk: Boolean = false,
    val build: ModelBuild? = null,
    /** The chip-specific model failed here; the screen explains the second download. */
    val fellBack: Boolean = false,
    val model: ModelState = ModelState.Missing,
    val tune: TuneState = TuneState.Idle,
    val askMobileData: Boolean = false,
    val ready: Boolean = false,
    /** Null until the user picks mobile data or Wi-Fi. */
    val allowMetered: Boolean? = null,
    /** The file list was synced in this process, so names are findable. */
    val namesSynced: Boolean = false,
    val scheduled: Boolean = false,
    /** The user stopped the download; it waits for them to start it again. */
    val downloadStopped: Boolean = false,
    /** The short read's progress, from core; null until first read. */
    val taste: TasteProgress? = null,
    /** No short read waits on the person: they chose, chose Read everything, or set up before the first run had one. */
    val decided: Boolean = true,
    val trying: Boolean = false,
    /** The rest of the library at this phone's pace; null until a pace is measured. No files left: the plan was everything. */
    val estimate: ReadEstimate? = null,
    val committing: Boolean = false,
    val moreFiles: Long? = null,
    /** Skip: Home now, whatever runs carries on. */
    val skipped: Boolean = false,
    /** The shared reading policy's explanation, ready for the setup screen. */
    val pauseReason: String? = null,
) {
    val files get() = access.ready
    val canSearch get() = started && files

    /** Nothing waits beyond the short read, so try-it ends in Done, not in the commit choice. */
    val nothingLeft get() = estimate?.files == 0L

    /** A short read is planned and its try-it and commit screens are still to come. */
    val firstRun get() = !decided && !taste?.byKind.isNullOrEmpty()

    /** What the screen shows; every input is stored (Prefs, Store meta, the model file) or re-read, so it survives the process. */
    val stage: SetupStage get() = when {
        // The mobile-data question comes before the access screen.
        !started || (askMobileData && !files) -> SetupStage.WELCOME

        !files -> SetupStage.ACCESS

        skipped -> SetupStage.DONE

        !ready -> SetupStage.WAITING

        firstRun -> when {
            committing -> SetupStage.COMMIT
            trying -> SetupStage.TRY
            else -> SetupStage.READ_FEW
        }

        else -> SetupStage.DONE
    }
}

enum class SetupStage { WELCOME, ACCESS, WAITING, READ_FEW, TRY, COMMIT, DONE }

enum class SetupStep { NONE, ASK_FILES, SYNC_NAMES, ASK_MOBILE_DATA, DOWNLOAD, VERIFY, TUNE, SCHEDULE, NEEDS_SPACE, READ_FEW, TRY, COMMIT }

/** The next thing setup should do. Every step it names is safe to run twice. */
fun nextStep(ui: SetupUi, hasSpace: Boolean, metered: Boolean, profileExists: Boolean): SetupStep = when {
    !ui.started -> SetupStep.NONE

    // The download needs no file access, so it starts on the welcome tap and runs while access is asked for.
    !ui.ready && !ui.textOnly && ui.model == ModelState.Missing && !ui.downloadStopped -> when {
        !hasSpace -> SetupStep.NEEDS_SPACE
        metered && ui.allowMetered == null -> SetupStep.ASK_MOBILE_DATA
        else -> SetupStep.DOWNLOAD
    }

    !ui.files -> SetupStep.ASK_FILES

    !ui.namesSynced -> SetupStep.SYNC_NAMES

    ui.ready -> when {
        ui.firstRun -> when {
            ui.committing -> SetupStep.COMMIT
            ui.trying -> SetupStep.TRY
            else -> SetupStep.READ_FEW
        }

        ui.scheduled -> SetupStep.NONE

        else -> SetupStep.SCHEDULE
    }

    ui.textOnly -> SetupStep.NONE

    ui.model == ModelState.Checking -> SetupStep.VERIFY

    ui.model == ModelState.Done && !profileExists && ui.tune == TuneState.Idle -> SetupStep.TUNE

    else -> SetupStep.NONE
}

/**
 * Setup as one chain in the app's scope: leaving the screen or the app never stops it, and [refresh]
 * picks it up again when the user comes back.
 */
class SetupFlow(
    private val retriever: Retriever,
    private val models: ModelRepository,
    private val access: AccessService,
    private val index: IndexService,
    private val download: ModelDownload,
    private val scope: CoroutineScope,
    private val onMeteredNetwork: () -> Boolean,
    private val notificationsGranted: () -> Boolean,
    private val calibrate: suspend () -> CalibrationResult,
) {
    val reading = retriever.state

    // Seeded synchronously: AppNav reads canSearch once to pick the first screen. Built only after setUp settles, so
    // the prefs are read; a finished setup counts as started for installs from before Prefs.setupStarted.
    private val _ui = MutableStateFlow(
        SetupUi(
            started = retriever.prefs.value.setupStarted || retriever.setUp.value == true,
            access = access.state.value,
            textOnly = retriever.textOnly,
            ramGb = retriever.probe.ramGb,
            allowMetered = if (retriever.prefs.value.allowMobileData) true else null,
        ),
    )
    val ui: StateFlow<SetupUi> = _ui
    private val mutex = Mutex()

    /** Set up with the model gone, as last seen; guarded by [mutex]. */
    private var wasMissing = false

    init {
        scope.launch { retriever.readingStatus().collect { s -> _ui.update { it.copy(pauseReason = if (s.active) null else s.title) } } }
        scope.launch { retriever.counts().collect { c -> _ui.update { it.copy(moreFiles = c.progress.notScheduled) } } }
        scope.launch { retriever.prefs.collect { p -> _ui.update { it.copy(started = it.started || p.setupStarted) } } }
        scope.launch {
            models.state.collect { model ->
                val done = model == ModelState.Done
                _ui.update {
                    it.copy(
                        model = model,
                        fellBack = it.fellBack && !done,
                        tune = if (done) tuneAfterRefresh(it.tune, it.fellBack) else it.tune,
                    )
                }
                if (done || model is ModelState.Failed || model == ModelState.Missing) advance()
            }
        }
        scope.launch {
            combine(retriever.setUp, retriever.modelMissing, ::Pair).collect { (setUp, missing) ->
                mutex.withLock {
                    // Only a ready flip acts here: before the first reload the model state is still a guess.
                    if (applyReady(setUp == true, missing)) step()
                }
            }
        }
        scope.launch {
            retriever.tasteProgress().collect { p ->
                val was = _ui.value
                _ui.update { withTaste(it, p) }
                if (_ui.value.trying) refreshRest()
                // "Ready to try" reaches a person who left during the short read; on screen, the button says it.
                // Claimed while visible too, so a sample seen on screen is never announced later.
                if (p.readyToTry && was.firstRun && (retriever.uiVisible || notificationsGranted()) &&
                    retriever.claimSampleNotification()
                ) {
                    index.notifyReadyToTry(p.read)
                }
            }
        }
    }

    /** A process that starts with the short read already past "Try it now" resumes on try-it, as the person left it. */
    private fun withTaste(s: SetupUi, p: TasteProgress) = s.copy(taste = p, trying = s.trying || (s.taste == null && p.byKind.isNotEmpty() && p.readyToTry))

    /**
     * Ready is set up with the model in place. When the model goes after setup (an AI-chip fallback needs a new
     * build, or Free up space) the download is offered, never fetched unasked: the two look the same from here.
     * Returns whether setup is ready.
     */
    private fun applyReady(setUp: Boolean, missing: Boolean): Boolean {
        val gone = setUp && missing
        val ready = setUp && !missing
        _ui.update {
            it.copy(ready = ready, scheduled = it.scheduled && ready, downloadStopped = it.downloadStopped || (gone && !wasMissing))
        }
        wasMissing = gone
        return ready
    }

    /** Permissions, the model file and the required build can all change while the user is away. */
    fun refresh() {
        scope.launch {
            mutex.withLock {
                reload()
                step()
            }
        }
    }

    /**
     * The welcome tap: the download starts now. Trying first writes the scope at once (an empty plan, filled by the names
     * sync), so the choice outlives the process; Read everything writes nothing, which reads as today's flow.
     */
    fun getStarted(readEverything: Boolean = false) {
        markStarted()
        scope.launch {
            mutex.withLock {
                if (!readEverything) plan()
                reload()
                step()
            }
        }
    }

    fun tryNow() {
        _ui.update { it.copy(trying = true) }
        advance()
    }

    /** Continue from try-it: the commit choice, or Done (read everything) when the plan already was everything. */
    fun continueToCommit() {
        if (_ui.value.nothingLeft) return settle { retriever.commitScope() }
        scope.launch {
            refreshRest()
            _ui.update { it.copy(committing = true) }
            advance()
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

    /** Read while charging or Read now: the rest is read, and the person is done choosing. */
    fun commit(whileCharging: Boolean) = settle { retriever.commit(whileCharging) }

    /** [choose] settles the scope (and the schedule, when the person chose one); then the reading is scheduled by it. */
    private fun settle(choose: suspend () -> Unit) {
        scope.launch {
            choose()
            index.schedulePeriodic(retriever.prefs.value.onlyWhileCharging)
            index.indexNow()
            _ui.update { it.copy(decided = true) }
            advance()
        }
    }

    /** Not yet: the scope stays on the short read and Home offers the choice again. */
    fun decideLater() {
        scope.launch {
            retriever.decideLater()
            index.cancel()
            _ui.update { it.copy(decided = true) }
            advance()
        }
    }

    /** Home now; the download, tune and short read carry on. Home offers the choice once the short read is done. */
    fun skip() {
        _ui.update { it.copy(skipped = true) }
    }

    fun useMobileData() {
        _ui.update { it.copy(allowMetered = true, askMobileData = false) }
        scope.launch {
            retriever.updatePrefs { it.copy(allowMobileData = true) }
            mutex.withLock {
                // DownloadManager can't lift the Wi-Fi rule from a queued request, so it is queued again.
                if (models.state.value == ModelState.WaitingForWifi) models.cancel(retriever.requiredBuild.value)
                step()
            }
        }
    }

    fun waitForWifi() {
        _ui.update { it.copy(allowMetered = false, askMobileData = false) }
        scope.launch { retriever.updatePrefs { it.copy(allowMobileData = false) } }
        advance()
    }

    fun retry() {
        val modelFailed = models.state.value is ModelState.Failed
        models.clearFailure()
        _ui.update {
            when {
                modelFailed || it.downloadStopped -> it.copy(downloadStopped = false)
                it.tune is TuneState.Failed -> it.copy(tune = TuneState.Idle)
                else -> it
            }
        }
        refresh()
    }

    fun cancelDownload() {
        scope.launch {
            mutex.withLock {
                models.cancel(retriever.requiredBuild.value)
                _ui.update { it.copy(downloadStopped = true) }
            }
        }
    }

    fun dismissNotificationAsk() {
        _ui.update { it.copy(notificationAsk = false) }
        scope.launch { retriever.updatePrefs { it.copy(notificationsDismissed = true) } }
    }

    private fun markStarted() {
        _ui.update { it.copy(started = true) }
        scope.launch { retriever.updatePrefs { it.copy(setupStarted = true) } }
    }

    private fun advance() {
        scope.launch { mutex.withLock { step() } }
    }

    private suspend fun reload() {
        retriever.refreshSetUp()
        val b = retriever.requiredBuild.value
        val fellBack = withContext(Dispatchers.IO) { retriever.fellBack() }
        access.refresh()
        applyReady(retriever.setUp.value == true, retriever.modelMissing.value)
        _ui.update { s ->
            s.copy(
                access = access.state.value,
                notificationAsk = !notificationsGranted() && !retriever.prefs.value.notificationsDismissed,
                build = b,
                fellBack = fellBack,
                tune = tuneAfterRefresh(s.tune, fellBack),
            )
        }
        loadTaste()
        // A file from before hashes were pinned (or one downloaded before a crash) is checked, never fetched twice.
        models.sync(b)
    }

    private suspend fun decide(): SetupStep {
        // The repository's state, not the mirrored copy, so a download just queued is never queued again.
        val ui = _ui.value.copy(model = models.state.value)
        val b = retriever.requiredBuild.value
        val missing = ui.started && !ui.ready && !ui.textOnly && ui.model == ModelState.Missing && !ui.downloadStopped
        val hasSpace = !missing || withContext(Dispatchers.IO) {
            // A failed NPU build is useless now: free its space before checking for the generic one.
            if (!b.isNpu) download.prune(b)
            download.hasSpaceFor(b)
        }
        val metered = missing && onMeteredNetwork()
        // With the model in place and access granted, set-up is exactly "a profile exists".
        return nextStep(ui, hasSpace, metered, profileExists = retriever.setUp.value == true)
    }

    private suspend fun step() {
        var s = decide()
        if (s == SetupStep.SYNC_NAMES) {
            // Both sync the file list, so names are findable while the model downloads. The plan is made once, here,
            // before the tune; a plan made already (the process died mid-run) is kept, never redrawn.
            val ui = _ui.value
            if (!ui.decided && ui.taste?.byKind.isNullOrEmpty()) plan() else index.indexNow()
            _ui.update { it.copy(namesSynced = true) }
            s = decide()
        }
        val b = retriever.requiredBuild.value
        when (s) {
            // VERIFY: the hash check reload started is still running. TRY and COMMIT are the person's to answer.
            SetupStep.NONE, SetupStep.ASK_FILES, SetupStep.SYNC_NAMES, SetupStep.VERIFY -> Unit

            SetupStep.READ_FEW, SetupStep.TRY, SetupStep.COMMIT -> readFew()

            SetupStep.ASK_MOBILE_DATA -> _ui.update { it.copy(askMobileData = true) }

            // The repository checks the space again and says why it can't start.
            SetupStep.NEEDS_SPACE, SetupStep.DOWNLOAD -> models.start(b)

            SetupStep.TUNE -> tune()

            SetupStep.SCHEDULE -> {
                // Runs once per process, so opening the app reads files even on battery: by design, a visible app
                // indexes now (Retriever.indexBatch). "Only read while charging" governs background reading.
                index.indexNow()
                index.schedulePeriodic(retriever.prefs.value.onlyWhileCharging)
                _ui.update { it.copy(scheduled = true) }
            }
        }
    }

    /**
     * Plans the short read and sets the scope to it; a plan with nothing in it once files are allowed means nothing to
     * try, so the scope goes to everything and new files are read as they arrive. A failure leaves today's flow.
     */
    private suspend fun plan() {
        try {
            val p = retriever.planTaste()
            // An empty plan means only "no eligible files".
            if (p.ids.isEmpty() && _ui.value.files) retriever.commitScope()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Retriever", "first-run plan failed", e)
        }
        loadTaste()
    }

    private suspend fun loadTaste() {
        val decided = retriever.tasteDecided()
        val p = retriever.tasteProgress().first()
        _ui.update { withTaste(it, p).copy(decided = decided) }
    }

    /** Once per process: the short read runs here, in sight; hidden, it stops and the worker carries on within the plan. */
    private fun readFew() {
        if (_ui.value.scheduled) return
        _ui.update { it.copy(scheduled = true) }
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

    private fun tuneAfterRefresh(t: TuneState, fellBack: Boolean): TuneState = if (t is TuneState.Idle && retriever.setUp.value == true) {
        // After a fallback the last backend seen may be the chip that just failed.
        val backend = retriever.state.value.backend.takeUnless { fellBack }
        TuneState.Done(backend?.let { tuneSummary(it, null) } ?: "Tuned for this phone")
    } else {
        t
    }

    /** Runs once per chain; a failure waits for Try again rather than looping. */
    private fun tune() {
        _ui.update { it.copy(tune = TuneState.Running) }
        val before = retriever.requiredBuild.value
        scope.launch {
            val next = try {
                val r = calibrate()
                TuneState.Done(tuneSummary(r.chosen, r.backends.firstOrNull { it.backend == r.chosen }?.imageMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Errors too (a missing API, OOM): a crash here would leave setup unfinishable.
                Log.e("Retriever", "calibration failed", e)
                // The AI chip failed: a new build is required, and tuning runs again after its download.
                if (retriever.requiredBuild.value != before) TuneState.Idle else TuneState.Failed(e.message ?: e.toString())
            }
            _ui.update { it.copy(tune = next) }
            refresh()
        }
    }
}

class SetupActions(
    val getStarted: () -> Unit = {},
    val readEverything: () -> Unit = {},
    val allowFiles: () -> Unit = {},
    val allowMedia: () -> Unit = {},
    val addFolder: () -> Unit = {},
    val allowNotifications: () -> Unit = {},
    val dismissNotifications: () -> Unit = {},
    val useMobileData: () -> Unit = {},
    val waitForWifi: () -> Unit = {},
    val retry: () -> Unit = {},
    val stopDownload: () -> Unit = {},
    val search: () -> Unit = {},
    val tryNow: () -> Unit = {},
    val continueToCommit: () -> Unit = {},
    val commit: (whileCharging: Boolean) -> Unit = {},
    val decideLater: () -> Unit = {},
    val skip: () -> Unit = {},
)
