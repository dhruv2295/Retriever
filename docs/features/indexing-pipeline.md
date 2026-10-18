# Indexing pipeline

## Purpose

Turn the files on the phone into searchable rows and vectors, one bounded batch at a time, on both platforms. A batch
syncs the file list, reads each pending file (extract, then embed), and says what should happen next. The platform
schedulers (WorkManager, BGTaskScheduler, the iOS foreground loop) only decide when a batch runs; the batch itself is
shared code. See `background-android.md` and `background-ios.md` for the schedulers, `extraction.md` for reading a file.

## Mechanism and why

- **One batch at a time.** `Retriever.indexBatch(budget)` takes `indexLock` with `tryLock` and returns `NextRun.DONE`
  (after flagging `changedSinceSync`) when another batch holds it. Only the continued iOS read passes
  `waitForLock = true`, because it must replace the foreground batch. A single lock beats a queue here: a second batch
  would only repeat the first one's work.
- **The budget covers everything.** `runBatch` starts one `TimeSource.Monotonic` mark, then the sync, the model open and
  the engine all draw from it (`budget - started.elapsedNow()`), so a platform's time limit holds (WorkManager's 10 minute
  JobScheduler limit, a BGProcessingTask's window).
- **Stop reason to next run.** The engine ends with a `StopReason`; `Retriever.nextRunFor` maps it to `NextRun`:
  BUDGET to SOON, HOT to AFTER_COOLDOWN, UNPLUGGED to WHEN_CHARGING, BUSY to WHEN_IDLE, DONE to DONE (SOON when files changed
  during the batch), PAUSED to DONE. A thrown error is RETRY. Each platform turns `NextRun` into its own scheduling call.
  This is the shape of Apple's and Android's "do a slice, then ask to be run again" guidance for deferrable work.
- **Phone policy in one place.** `IndexEngine.stopReason` is read before every file: Pause, budget, busy (screen on while
  the app is hidden), thermal at MODERATE or worse, and charging when the setting or a battery at 20% or less requires it
  and the app is hidden. A visible app reads on battery.
- **Per-file loop.** `IndexEngine` pages pending files, extracts, then embeds per piece. Each embed first `awaitUi()`s:
  it suspends while a search runs or the UI is typing or scrolling, so a query waits behind at most one native call.
  Chunks are written per file (`Store.replaceChunks`), so a stop loses at most the file in flight.
- **Attempts are counted before the work.** A file or a tail chunk is charged an attempt first and given it back on
  `CancellationException`, so a process death cannot crash-loop on one file and a stop never charges a file.
- **Long files deepen.** The head of a long file is embedded first; later parts (tail chunks) read when nothing is pending
  and the app is visible, or the phone is charging with the screen off.
- **Idle batches do not open the model.** `runBatch` checks for no pending files, no tail work and a matching signature
  and returns DONE before `ModelHost.open`.
- **ETA.** `Eta` and `SpeedBook` (Estimate.kt) record time per unit per kind, persisted in store meta, and `readEstimate`
  turns pending counts into minutes. The hours estimate stays off in the UI until it is calibrated on a device
  (`ESTIMATE_CALIBRATED`).
- **First run.** `planTaste` picks a varied slice of the library that reads in about 90 seconds and scopes the first batch
  to it (SAMPLE scope); `Store.pendingIn` limits the engine to that plan.

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
3. A stop, cancel or Pause gives back the attempt for the file in flight; a process death does not.
4. A cancelled batch returns SOON; a cancelled caller (`ensureActive`) propagates instead.
5. Policy (heat, power, busy, Pause) is read per file, so a setting flipped mid-batch applies at once.
6. A vector is written only under the current `Signature`; a changed signature wipes vectors and re-reads everything.
7. A file that fails is recorded and skipped after its attempts; one bad file never ends the batch.
8. The model is not opened when there is nothing to embed.

## Tests

`RetrieverTest`, `BatchPolicyTest`, `ThermalTest`, `EtaTest`, `EstimateTest`, `TokenRetryTest`, `ReviewFixesTest`,
`IndexPipelineTest`, `TasteProgressTest`, `LowEndTest`, `RetuneTest` (all under `core/src/androidHostTest` and `core/src/commonTest`).
iOS: `AppModelTests`.

## Known limits

- Extraction (OCR, PDF render, video decode) does not wait on the UI; only embeds do. A typing person shares CPU with it.
- One file longer than the remaining budget overruns it or is cancelled; a video that alone needs more than the budget
  completes only in a longer (visible or iOS foreground) run.
- Thermal policy reads the current status only. Later: `PowerManager.getThermalHeadroom` (API 30) to stop before throttling.
- Wave 7 items that touch this note: A1 (ETA as time per unit, per kind, persisted), A2 (Tune's scan), A8 (skip the model
  open when idle), A9 and A10 (work that grows with the library). Device queue: calibrate the ETA and the hours estimate on
  a phone before enabling it.
- Resolved in this wave: a sync-then-open budget, a stuck busy flag when the app stops, and follow-ups that waited behind
  a charging constraint.
