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
// Adapted from google-ai-edge/gallery Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt (Apache-2.0). Modified for Retriever: only the in-progress download branch, driven by ModelDownload's polled progress, then the tune.

package app.retriever.model

import android.util.Log
import app.retriever.access.AccessService
import app.retriever.core.CalibrationResult
import app.retriever.core.ReadingProgress
import app.retriever.core.Retriever
import app.retriever.core.UpgradePolicy
import app.retriever.core.UpgradeStage
import app.retriever.indexing.IndexService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The one upgrade's state: core's stage plus the download and tune this phone runs. */
data class UpgradeUi(
    val stage: UpgradeStage = UpgradeStage.UNAVAILABLE,
    val model: ModelState = ModelState.Missing,
    val tune: TuneState = TuneState.Idle,
    val sizeBytes: Long = 0,
    val askMobileData: Boolean = false,
    /** For the meaning pass's line; null until counted. */
    val progress: ReadingProgress? = null,
)

/**
 * "Search by meaning and what's in your photos": Download, then the tune by itself once the model is in place and files
 * are allowed, then the meaning pass that reading runs. Runs in the app's scope, so leaving a screen never drops it.
 */
class UpgradeFlow(
    private val retriever: Retriever,
    private val models: ModelRepository,
    private val access: AccessService,
    private val index: IndexService,
    private val scope: CoroutineScope,
    private val onMeteredNetwork: () -> Boolean,
    private val calibrate: suspend () -> CalibrationResult,
) {
    private val local = MutableStateFlow(UpgradeUi(sizeBytes = retriever.modelDownloadBytes))

    val ui: StateFlow<UpgradeUi> = local

    /** The stage alone, for screens that must not redraw on each download tick. */
    val stage: StateFlow<UpgradeStage> = local.map { it.stage }.stateIn(scope, SharingStarted.Eagerly, local.value.stage)

    init {
        scope.launch {
            combine(
                combine(retriever.modelPresent, retriever.setUp, models.state, ::Triple),
                retriever.counts().map { it.progress },
            ) { (present, setUp, model), progress ->
                val downloading = model is ModelState.Downloading || model == ModelState.WaitingForWifi ||
                    model == ModelState.Checking || model is ModelState.Failed
                val stage = UpgradePolicy.stage(retriever.textOnly, present, downloading, setUp == true, progress.meaningWaiting)
                Triple(stage, model, progress)
            }.collect { (stage, model, progress) ->
                local.update {
                    // Past the tune it is spent, so a model removed and fetched again tunes again.
                    val tune = if (stage != UpgradeStage.TUNE && it.tune != TuneState.Running) TuneState.Idle else it.tune
                    it.copy(stage = stage, model = model, tune = tune, progress = progress, sizeBytes = retriever.modelDownloadBytes)
                }
            }
        }
        // A verified download makes the model present only once core looks again.
        scope.launch { models.state.map { it == ModelState.Done }.distinctUntilChanged().collect { if (it) retriever.refreshSetUp() } }
        scope.launch {
            combine(local.map { it.stage == UpgradeStage.TUNE && it.tune == TuneState.Idle }, access.state.map { it.ready }) { due, files -> due && files }
                .distinctUntilChanged().collect { if (it) tune() }
        }
    }

    /** Picks up a download a killed process left, or hashes a file already in place. */
    fun refresh() = scope.launch { models.sync(retriever.requiredBuild.value) }

    /** Download: on mobile data it asks first, unless the person allowed mobile data already. */
    fun download() {
        models.clearFailure()
        if (onMeteredNetwork() && !retriever.prefs.value.allowMobileData) {
            local.update { it.copy(askMobileData = true) }
        } else {
            models.start(retriever.requiredBuild.value)
        }
    }

    fun useMobileData() {
        local.update { it.copy(askMobileData = false) }
        scope.launch {
            retriever.updatePrefs { it.copy(allowMobileData = true) }
            // DownloadManager can't lift the Wi-Fi rule from a queued request, so it is queued again.
            if (models.state.value == ModelState.WaitingForWifi) models.cancel(retriever.requiredBuild.value)
            models.start(retriever.requiredBuild.value)
        }
    }

    fun waitForWifi() {
        local.update { it.copy(askMobileData = false) }
        models.start(retriever.requiredBuild.value)
    }

    fun cancel() = scope.launch { models.cancel(retriever.requiredBuild.value) }

    fun dismissOffer() = scope.launch { retriever.dismissUpgradeOffer() }

    /** After a failed tune: once more. */
    fun retryTune() = local.update { it.copy(tune = TuneState.Idle) }

    /** Runs once per chain; a failure waits for Try again rather than looping. */
    private fun tune() {
        local.update { it.copy(tune = TuneState.Running) }
        val before = retriever.requiredBuild.value
        scope.launch {
            val next = try {
                val r = calibrate()
                TuneState.Done(tuneSummary(r.chosen, r.backends.firstOrNull { it.backend == r.chosen }?.imageMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Errors too (a missing API, OOM): a crash here would leave the upgrade unfinishable.
                Log.e("Retriever", "calibration failed", e)
                // The AI chip failed: a new build is required, and tuning runs again after its download.
                if (retriever.requiredBuild.value != before) TuneState.Idle else TuneState.Failed(e.message ?: e.toString())
            }
            retriever.refreshSetUp()
            local.update { it.copy(tune = next) }
            // The meaning pass is reading's work, on the person's schedule.
            if (next is TuneState.Done) index.indexNow()
        }
    }
}
