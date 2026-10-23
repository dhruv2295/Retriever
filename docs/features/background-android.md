# Background work (Android)

The shared counts, current schedule and UI contract are in [Reading](reading.md). It supersedes older reading-policy and copy descriptions below.


## Purpose

Index the person's files without the app open, without hurting the phone (battery, heat, a person using it), and without
losing work when Android stops or kills the process. The batch itself is in `indexing-pipeline.md`; this note is the
scheduling around it.

## Mechanism and why

| Piece | Mechanism | Why it is the accepted one |
|---|---|---|
| Scheduling | WorkManager, four unique names owned by `IndexService`: `index-now` (KEEP), `index-continue` (REPLACE), `index-periodic` (6 h, UPDATE, battery not low, charging when the setting says so), `index-media` (content-URI trigger). | The persisted, constraint-aware scheduler for deferrable work. It survives process death and reboot and respects Doze and App Standby. |
| Init | `RetrieverApp` implements `Configuration.Provider` with `KoinWorkerFactory`; the startup initializer is removed in the manifest. `IndexWorker` is a constructor-injected worker (`workerOf` in `AppModule`). | On-demand initialization is the documented path for a custom configuration; Koin's worker factory removes the service-locator calls. |
| Work unit | `IndexWorker : CoroutineWorker` runs one `Retriever.indexBatch(8.minutes)`, then `schedule(next)` maps `NextRun` to a follow-up and returns success. A failed batch (`RETRY`) rests like heat through `index-continue`, never `Result.retry()`: NOW work waiting in backoff would make `index-now`'s KEEP drop every Resume and commit. | Stays under the 10 minute deadline of a non-foreground job. Each stop reason maps to the constraint that ends it: charger, idle, or a delay. |
| Follow-ups | Every follow-up REPLACEs `index-continue`, so the last decision wins and a timed run never waits behind a charger- or idle-gated one. | APPEND_OR_REPLACE chained a SOON run behind a gated one. |
| Stop and cancel | A stop cancels `doWork`, which cancels the batch (`indexBatch` returns SOON, the file's attempt is given back). `stopReason` is logged on API 31+. | Cooperative cancellation is what `CoroutineWorker` expects (`onStopped` is final). |
| Foreground | `setForeground` when set up and either handing off a trial, leaving on a charger, or visible with no other batch. Type `dataSync` on every API level, the only type declared in the manifest. A refused start is caught. | A foreground service started from a visible state, or just after leaving one, is the documented exemption (develop/background-work/services/fgs/restrictions-bg-start). The FGS timeout resets when the app comes to the front. Play's foreground service declaration (support.google.com/googleplay/android-developer/answer/13392821) lists `dataSync` for "Local processing" and `mediaProcessing` only for "Media transcoding", which indexing is not; both get the same 6 h a day on Android 15+, so one type and one declaration ("Local processing: Other", shown with Process for 15 minutes) cover every level. |
| Charging runs | Leaving the app (`screenOff`) on a charger with reading allowed and files or later parts left (`IndexService.startsOnCharger`) enqueues an expedited `CONTINUE` run tagged `charger`, which asks for its foreground service while Android still allows it. A run that holds one starts its next batch at once while the app shows or the phone stays plugged in (`holdsForeground`), and rests in place instead of returning for a follow-up (`restBefore`): heat polls every 10 s until cooled, a model open refused for memory waits 2 minutes, a model closed mid-batch 10 s, and a phone in use is checked every 15 s (`BUSY_POLL`) with its notification reading "Waiting until you put your phone down", so processing carries on within seconds of the screen going off instead of handing back to WorkManager's throttled jobs (verified on a Pixel 8 Pro: first file 18 s after locking). A second screen-off leaves a live charger run alone (`chargerRunAlive`), so its foreground service is never replaced. Pause, the schedule, unplugging, or any other stop ends it, and `schedule(next)` takes over. Refused, it is an ordinary 8 minute run with WorkManager follow-ups. | A foreground worker has no 10 minute job limit, and WorkManager ignores scheduler stops for it (`Processor.stopWork`). Android 15+ caps `dataSync` at 6 h in 24 h; WorkManager 2.10+ stops the worker on `Service.onTimeout` (`STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`) and re-runs the work later. Opening the app resets the 6 h. |
| Progress | Screens and the notification read `Retriever.state`, throttled to once a second for the notification. `setProgress` is not used. | The worker runs in the app's process; the StateFlow carries more than `Data` (ETA, current file, stop reason). |
| Expedited | Pending trial work uses `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` on screen-off and SOON follow-ups. | Short user-started work should not wait for device-idle maintenance; core still checks hidden-use, battery, heat and Battery Saver. Expedited rejects battery/charging/idle constraints; Android 11 receives `getForegroundInfo`. Quota exhaustion falls back to ordinary work. |
| Change trigger | `IndexService.watchMedia` arms a `ContentUriTrigger` on `MediaStore.Files` with a 1 minute update delay and a 10 minute max delay. The media run re-arms with APPEND_OR_REPLACE on its first attempt only. | JobScheduler holds the trigger, so it works with the process gone (the old in-process observer died with it). |
| Held stops | `ProcessLifecycleOwner.onStart` and setup call `IndexService.readTrial` directly, waiting for any cancelled batch; `onStop` and `ACTION_SCREEN_OFF` persist a trial continuation before cancelling a direct read. | A comeback reads directly unless a persisted trial worker already owns it. Screen-on use of other apps still pauses reading. |
| Process death | Attempts are charged before each file, chunks are written per file, WorkManager re-runs the interrupted work. | A file that kills the process is skipped after N tries instead of crash-looping. |
| Memory | `onStop` cancels the foreground warm-up and requests `releaseIfIdle`; batch completion retries a pending release. | Only one copy of the model is in memory, and none while nothing needs it. |

## Owner files

- `androidApp/src/main/kotlin/app/retriever/IndexWorker.kt`
- `androidApp/src/main/kotlin/app/retriever/indexing/IndexService.kt`
- `androidApp/src/main/kotlin/app/retriever/indexing/PauseReceiver.kt`
- `androidApp/src/main/kotlin/app/retriever/indexing/Notifications.kt`
- `androidApp/src/main/kotlin/app/retriever/RetrieverApp.kt`
- `androidApp/src/main/kotlin/app/retriever/di/AppModule.kt`
- `androidApp/src/main/AndroidManifest.xml`
- `core/src/commonMain/kotlin/app/retriever/core/Retriever.kt`
- `core/src/commonMain/kotlin/app/retriever/core/IndexEngine.kt`

## Trial handoff and power changes

Leaving during a direct trial first persists expedited continuation work, then cancels the direct job. The worker
requests a foreground service and waits for the core lock; a quick comeback keeps that worker as owner. Android may
refuse a foreground-service start or exhaust expedited quota, in which case WorkManager still owns the pending work.
A screen-on phone with another app in front remains BUSY. Battery Saver stops before sync and waits for
`ACTION_POWER_SAVE_MODE_CHANGED`; the six-hour periodic run remains the safety net. Only heat uses the thermal cooldown.

## Invariants

1. One batch at a time (`indexLock`); a request made while one runs reaches it through `changedSinceSync`.
2. A background run without a foreground service finishes inside 10 minutes, sync and model open included.
3. A follow-up never waits behind a constraint that does not belong to it (all follow-ups REPLACE one name).
4. Trial workers request a foreground service even after leaving; a refused start retains WorkManager ownership.
5. A stop, cancel or Pause gives back the attempt for the file in flight; process death does not.
6. Pause stops the running batch within one file and cancels `index-now` and `index-continue`; `index-periodic` stays.
7. The model is never closed under a running batch and never loaded twice.
8. `uiBusy` is cleared when the app stops, so a screen left mid-typing cannot hold batches back.

## Tests

`IndexWorkerTest` and `IndexServiceTest` (androidApp unit tests), `KoinModuleTest` (the worker graph), and in core
`RetrieverTest`, `BatchPolicyTest`, `ThermalTest`, `TokenRetryTest`.

## Known limits

- In-memory state (`lastStop`, `uiVisible`, `changedSinceSync`) is lost on process death; a BUSY stop then waits for the
  idle constraint or the 6 h periodic run.
- Android 16 job quotas apply even to jobs started while visible; a chain of 8 minute background runs can hit
  `STOP_REASON_QUOTA` in lower standby buckets (WorkManager re-enqueues). Device queue: measure on an Android 16 phone.
- In the rare or restricted bucket, background indexing runs about once a day. The app never asks to be exempt from
  battery optimization (Play policy).
- SAF folders have no change trigger; they are picked up by the periodic run, app open, or a MediaStore change elsewhere.
- Tune runs in `AppScope` with no process protection; leaving the app mid-Tune can get the cached process killed.
- Heat follows the thermal headroom (see `indexing-pipeline.md`); its thresholds are not calibrated on a phone yet.
- A charger plugged in while the app is already hidden can't start a foreground service (no exemption); those runs stay
  8 minute WorkManager jobs until the app is next opened and left on the charger.
- Screen-off broadcasts require a live, uncached process: Android defers a cached app's registered broadcasts (seen on a
  Pixel 8 Pro: `DEFERRED` in `dumpsys activity broadcasts`), and the IDLE constraint can take an hour. So a batch that stops
  because the phone is in use (`WHEN_IDLE`) on a charger is checked again every 5 minutes (`IndexService.BUSY_RECHECK`);
  on battery it still waits for idle. After process death or quota exhaustion Android still controls when background work runs; foreground trials resume directly when no persisted trial worker owns them.
