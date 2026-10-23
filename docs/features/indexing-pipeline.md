# Indexing pipeline

## Purpose

Turn the files on the phone into searchable rows and vectors, one bounded batch at a time, on both platforms. A batch
syncs the file list, reads each pending file (extract, then embed), and says what should happen next. The platform
schedulers (WorkManager, BGTaskScheduler, the iOS foreground loop) only decide when a batch runs; the batch itself is
shared code. See `background-android.md` and `background-ios.md` for the schedulers, `extraction.md` for reading a file.

## Mechanism and why

- **One batch at a time.** `Retriever.indexBatch(budget)` takes `indexLock` with `tryLock` and returns `NextRun.DONE`
  (after flagging `changedSinceSync`) when another batch holds it. Direct trials, Android trial workers, and iOS foreground/continued reads pass
  `waitForLock = true`, so a handoff waits for the cancelled batch without needing another scheduler wake. A single lock beats a queue here: a second batch
  would only repeat the first one's work.
- **The budget covers everything.** `runBatch` starts one `TimeSource.Monotonic` mark, then the sync, the model open and
  the engine all draw from it (`budget - started.elapsedNow()`), so a platform's time limit holds (WorkManager's 10 minute
  JobScheduler limit, a BGProcessingTask's window).
- **Stop reason to next run.** The engine ends with a `StopReason`; `Retriever.nextRunFor` maps it to `NextRun`:
  BUDGET to SOON, HOT to AFTER_COOLDOWN, POWER_SAVE to DONE (wait for a power-state change), UNPLUGGED to WHEN_CHARGING, BUSY to WHEN_IDLE, DONE to DONE (SOON when files changed
  during the batch), PAUSED to DONE, DISK_FULL to RETRY. A thrown error is RETRY. Each platform turns `NextRun` into its own
  scheduling call.
  This is the shape of Apple's and Android's "do a slice, then ask to be run again" guidance for deferrable work.
- **Phone policy in one place.** `IndexEngine.stopReason` is read before every file: Pause, budget, busy (screen on while
  the app is hidden), heat (`DeviceState.hot`: Android's thermal headroom at 0.95 or SEVERE; without a headroom, MODERATE), and charging when the setting or a battery at 20% or less requires it
  and the app is hidden. Battery Saver also pauses hidden reading before sync, so waiting never walks folders. A visible app reads on battery.
- **Per-file loop.** `IndexEngine` pages pending files, extracts, then embeds per piece. Each embed first `awaitUi()`s:
  it suspends while a search runs or the UI is typing or scrolling, so a query waits behind at most one native call.
  Chunks are written per file (`Store.replaceChunks`), so a stop loses at most the file in flight.
- **Read ahead on an accelerator.** While a file embeds on the GPU or NPU with the app hidden, the next files' extraction
  starts beside it (`IndexEngine.readAhead`), so CPU decode and OCR overlap the accelerator. `IndexEngine.readAheadDepth`:
  two files on a charger (one under 6 GB of RAM), one on battery, fewer as the phone warms (`DeviceState.aheadRoom`), none while the app shows.
  Each attempt is counted when its read starts and given back on any stop. Never on CPU inference. Read-ahead runs on
  `CoreDispatchers.readAhead` (Android: two background-priority threads, `readAheadDispatcher`), the same priority as
  inline extraction on `indexing`, whose one thread the embed holds. `ReadAheadBenchTest` (blocking stand-ins at a tenth
  of the Pixel 8 Pro's costs, depth 1): 137 to 88 ms per photo.
- **Attempts are counted before the work.** A file or a tail chunk is charged an attempt first and given it back on
  `CancellationException`, so a process death cannot crash-loop on one file and a stop never charges a file.
- **Long files deepen.** The head of a long file is embedded first; later parts (tail chunks) read when nothing is pending
  and the app is visible, or the phone is charging with the screen off. The head is 2 text chunks for a document or PDF
  (`DOC_HEAD_CHUNKS`), 8 for a screenshot's OCR (`HEAD_TEXT_CHUNKS`); with the model there, a video's first pass embeds
  one moment and a scanned PDF's OCRs its first 3 pages, the rest `LATER` placeholders (visual tails) that the deepen pass
  reads through `Extractor.later` and replaces (`Store.replaceTailChunk`). One tail is embedded per 8 first-pass files
  (`TAIL_EVERY`). Placeholders and image tails with tries left count as Processed in part and later passes, as text tails do.
  `later` returning null is a failed try (three and the placeholder stays); empty drops the placeholder. If the model goes
  (removed, or keyword-only), a batch still runs while placeholders remain: it OCRs left pages without vectors and drops
  left moments (`laterChunks`), so no file waits for a deepen pass that can't come. An undecided foreground trial always includes its tails, even for a heads-only caller. `hasPendingTaste` includes tails so batch boundaries and foreground returns resume them.
- **Idle batches do not open the model.** `runBatch` checks for no pending files, no tail work and a matching signature
  and returns DONE before `ModelHost.open`.
- **ETA.** `Eta` and `SpeedBook` (Estimate.kt) record time per unit per kind, persisted in store meta, and `readEstimate`
  turns pending counts into minutes. The hours estimate stays off in the UI until it is calibrated on a device
  (`ESTIMATE_CALIBRATED`).
- **First run.** `planTaste` picks a varied slice of the library that reads in about 90 seconds and scopes the first batch
  to it (SAMPLE scope); `Store.pendingIn` limits the engine to that plan. Pending counts, including per-kind counts,
  exclude the rest of the library (`Counts.waiting`). Videos use their own size, a 25-second seed floor and a
  budget-derived count ceiling, smallest first. After tuning, at batch start, and after each success, a pending
  estimate above 1.5 times the budget is trimmed back to the budget, dropping videos then costliest files, retaining
  completed work and at least one pending file until a planned file has been read. If no file fits the initial
  plan, it includes the single cheapest eligible file; only no eligible files produces an empty plan. SAMPLE reads one pending file per page so a trim takes effect before the next file.
- **Reading state.** `IndexState.currentFile` is set before extraction or tail embedding and cleared on exit. The head-only ETA is cleared when deepening begins. `recentFiles` contains the
  newest 24 successful INDEXED files, preserved across batches and filtered against surviving rows after removal
  or folder exclusion. A fresh process restores them from reversed plan
  order intersected with INDEXED. Both are carried by the existing StateFlow and iOS `observeAll` snapshot;
  `IndexState.pauseReason` supplies setup copy, and Android `SetupUi` exposes it.

Index batches (including the short read) run on `CoreDispatchers.indexing`: an owned Android background-priority
thread or an iOS utility queue. Extractors and inference preserve that context; shared model creation stays on
the normal dispatcher for interactive search. See `model-lifecycle.md` for the native-pool trade-off.

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/Retriever.kt` (`indexBatch`, `runBatch`, `nextRunFor`, `cancelBatch`, `awaitUi`, `planTaste`, `estimateAll`)
- `core/src/commonMain/kotlin/app/retriever/core/IndexEngine.kt` (`IndexEngine.runBatch`, `stopReason`, tail embedding)
- `core/src/commonMain/kotlin/app/retriever/core/Estimate.kt` (`SpeedBook`, `Eta`, `readEstimate`)
- `core/src/commonMain/kotlin/app/retriever/core/Taste.kt` (first-run plan)
- `core/src/commonMain/kotlin/app/retriever/core/Store.kt` (`pendingFiles`, `replaceChunks`, `tailChunks`, attempt counters)
- `core/src/commonMain/kotlin/app/retriever/core/Model.kt` (`ThermalRest`, `StopReason`, `NextRun`, `IndexState`)

## Invariants

1. At most one batch runs; a second call returns DONE and makes the running batch sync again at its end.
2. Elapsed time for the sync, model open and file loop is charged to one budget.
3. A stop, cancel or Pause gives back the attempt for the file in flight; a process death does not. So does a full disk
   (`SQLITE_FULL` or `ENOSPC`, `isDiskFull`): it stops the batch as DISK_FULL, which the status line names, and no file is
   skipped for it.
4. A cancelled batch returns SOON; a cancelled caller (`ensureActive`) propagates instead.
5. Policy (heat, power, busy, Pause) is read per file, so a setting flipped mid-batch applies at once.
6. A vector is written only under the current `Signature`; a changed signature wipes vectors and re-reads everything.
7. A file that fails is recorded and skipped after its attempts; one bad file never ends the batch.
8. The model is not opened when there is nothing to embed.

## Tests

`RetrieverTest`, `BatchPolicyTest`, `ThermalTest`, `HeadroomPollTest`, `FirstPassTest`, `ReadAheadTest`, `EtaTest`, `EstimateTest`, `TokenRetryTest`, `ReviewFixesTest`,
`IndexPipelineTest`, `TasteProgressTest`, `TrialReadTest`, `TasteScopeTest`, `LowEndTest`, `RetuneTest` (all under `core/src/androidHostTest` and `core/src/commonTest`).
iOS: `AppModelTests`.

## Known limits

- Extraction (OCR, PDF render, video decode) does not wait on the UI; only embeds do. A typing person shares CPU with it.
- One file longer than the remaining budget overruns it or is cancelled; a video that alone needs more than the budget
  completes only in a longer (visible or iOS foreground) run.
- Heat: `PowerManager.getThermalHeadroom(10)` is polled at most every 10 s (`HeadroomPoll`; Android documents NaN when
  asked more often, and NaN means no thermal hardware, so the status decides). Read-ahead narrows from 0.7 and stops at
  0.85; reading pauses at 0.95 or SEVERE. A foreground run rests in place, polling, until read-ahead could run again
  (`cooled`); WorkManager follow-ups and iOS recheck after `ThermalRest.RECHECK`. The thresholds are not calibrated on a
  phone yet.
- Wave 7 items that touch this note: A1 (ETA as time per unit, per kind, persisted), A2 (Tune's scan), A8 (skip the model
  open when idle), A9 and A10 (work that grows with the library). Device queue: calibrate the ETA and the hours estimate on
  a phone before enabling it.
- Resolved in this wave: a sync-then-open budget, a stuck busy flag when the app stops, and follow-ups that waited behind
  a charging constraint.

## Trial interruption regression

A keyword batch before Tune returns `DONE` after reading planned files without a model. A later batch
interrupted by `EmbedderClosedException` returned `SOON`, but `runBatch`'s finally block retained that previous `DONE`
(`stop ?: it.lastStop`). Home combined it with planned pending files and incorrectly said "new files". Search warm-up
could cause that interruption by replacing the shared model mid-batch. Warm-up now shares the index lock, and an
exception no longer inherits a previous batch's stop reason.

The supplied Home snapshot alone does not prove the most recent `indexBatch` returned `DONE`. Regression tests cover
the stale reason, continuation through remaining files, three-attempt failures, deepen tails, and trimmed plans.
`PENDING` in the scoped counts is planned work; `Counts.waiting` is outside-plan work (Android maps these to
`HomeUi.waiting` and `HomeUi.rest` respectively). Both platforms carry explicit trial scope in counts and say
"Finishing your trial · N files left" while planned files remain, including during a pause or interruption.
