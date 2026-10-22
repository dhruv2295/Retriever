# Background work (iOS)

The shared counts, current schedule and UI contract are in [Reading](reading.md). It supersedes older reading-policy and copy descriptions below.


## Purpose

Keep the index growing when the person is not looking at the app, inside iOS's rules: no GPU work in the background,
background time is limited and can be taken away, and the model download must survive suspension. It also keeps the
foreground loop (reading while the app is open) from fighting the background tasks over the index.

## Mechanism and why

| Need | Mechanism | Why it is the accepted one |
|---|---|---|
| Overnight reading | `BGProcessingTask` `app.retriever.index`; `requiresExternalPower` follows the "only while charging" setting; one `indexBatchSeconds(1500)` per run. | Apple's API for minutes-long deferrable work. Registered before launch ends (`RetrieverApp.init`), resubmitted before `setTaskCompleted`, as the BGTask header says. |
| A read the person starts | `BGContinuedProcessingTask` (iOS 26), `strategy = .fail`, progress on `task.progress`, title and subtitle updated. Fixed identifier `$(PRODUCT_BUNDLE_IDENTIFIER).continued`, listed in Info.plist. | The only API for user-initiated work that continues with system UI. It must report progress. No `.gpu` (needs an entitlement); `backendNow` picks the CPU once the app leaves. |
| Expiration | `expirationHandler = IndexTask.expire` sets a flag and calls `retriever.cancelBatch()`; the batch stops at the next suspension point, then the model is released and `setTaskCompleted` called. | Swift task cancellation does not reach the Kotlin coroutine, so the cancel goes through the facade. `IosEmbedder.close()` waits for the embed mutex. |
| Reading while open | `AppModel+Loop`: 120 s `foregroundBatchSeconds` batches, `NextRun.soon` continues, other results sleep until `wake()` (AFTER_COOLDOWN and RETRY rest for `ThermalRest.RECHECK_SECONDS`). | Waits for the cancelled background batch to release the index, avoiding a false DONE and sleep on comeback. |
| Phase transitions | `AppModel.setPhase` (scene phase, the aggregate of all scenes) starts and stops the loop; `Lifecycle` (UIKit notifications) feeds the monitor (`active`, battery, `uiVisible`) and releases the model under `beginBackgroundTask`. | App-level scene phase means a second iPad window going to the background does not stop reading. |
| Model download | Background `URLSession` (`app.retriever.model`), resume data per network rule, streamed SHA-256, `.sha256` marker. See `model-download.md`. | Apple's pattern for large downloads that outlive the process. |
| Library changes | `PHPhotoLibraryChangeObserver`, registered once access is granted; it calls `notifyChanged()` and wakes the loop. Sync uses persistent change tokens. | Only the delta is read after a suspension. |
| Screen awake | `isIdleTimerDisabled` is on only while a foreground batch runs with files waiting; off when hot, at 20% battery or less, or in Low Power Mode unless charging. | A locked phone backgrounds the app and stops GPU reading. |
| Thermal and power | `IosDeviceMonitor` maps Nominal/Fair/Serious/Critical to NONE/LIGHT/SEVERE/CRITICAL; background Low Power Mode reports POWER_SAVE separately from thermal state. iOS has no headroom, so `DeviceState.hot` reads the status: Serious and Critical pause, Fair narrows read-ahead (which a backgrounded iOS app never runs: it infers on CPU). | Cut discretionary work at Serious (Apple thermal guidance). |

## Owner files

- `iosApp/Retriever/Background/IndexTask.swift`
- `iosApp/Retriever/AppModel+Loop.swift`
- `iosApp/Retriever/AppModel.swift`
- `iosApp/Retriever/Core/Lifecycle.swift`
- `iosApp/Retriever/Core/AppServices.swift`
- `iosApp/Retriever/Core/PhotosSource.swift`
- `iosApp/Retriever/RetrieverApp.swift`
- `iosApp/Retriever/Info.plist`
- `iosApp/Retriever/DebugRoute.swift` (the `-bgdrill` launch drill)
- `core/src/iosMain/kotlin/app/retriever/core/IosDeviceMonitor.kt`
- `core/src/iosMain/kotlin/app/retriever/core/IosEmbedder.kt` (`backendNow`)
- `core/src/iosMain/kotlin/app/retriever/core/RetrieverIos.kt` (the Swift-facing facade)

## Invariants

1. Exactly one batch at a time (`indexLock`). The processing task uses `tryLock`; foreground and continued
   reads wait for the lock because they replace another batch.
2. No GPU work in the background: `backendNow` returns the CPU when the app is not active, and entering the background
   cancels the running batch so the next one reopens on the CPU. The signature excludes the backend.
3. Expiration to `setTaskCompleted` takes at most one in-flight embed plus the release.
4. Every exit of the continued task posts `IndexTask.ended`, and a processing task defers to a running continued read.
5. Every background exit reschedules the processing task unless reading is paused.
6. The model is released before `setTaskCompleted` only while hidden and without a new foreground batch.
7. An expired processing task reports failure, not success.
8. The download is never fetched twice (a live task with the same description is adopted).

## Tests

`AppModelTests`, `CopyTests` (iosApp/RetrieverTests); core `RetrieverTest`, `BackendNowTest`, `BridgesTest`,
`IosEmbedderTest`, `ThermalTest`. Launch drills: `-bgdrill`, `-keepreading`.

## Known limits

- A trial pauses while the app is suspended and resumes on return through the foreground loop. `IndexTask.schedule`
  preserves an opportunistic background run, but iOS controls its timing. Continued processing is submitted only by
  the person's tap while the app is visible; suspension is too late to request an immediate handoff.
- Hidden Low Power Mode returns DONE before library sync. There is no hidden polling loop: foreground return resumes
  reading, and scheduled processing tasks remain the safety net.

- iOS decides when the processing task runs (mostly overnight on the charger). With `requiresExternalPower = false`
  the engine's own charging and Low Power Mode rules gate it.
- The continued read ends (`success: false`) on the first HOT or error stop; a task with no progress is expired.
- With the app open during a continued read, reading stays on the CPU until the next batch reopens on the GPU.
- Device queue: expiration-to-completion latency under `-bgdrill`; whether the continued task's progress stalls on a
  single long file (video, large PDF) long enough to be expired.
- Later: replace the hand-rolled waiter in `AppModel+Loop` (`sleepUntilWoken`, `wake`) with an `AsyncStream` that keeps
  the newest value; `Lifecycle` and `AppModel.setPhase` both cancel the batch on background and should have one owner.
- Add a unit test that Info.plist's `BGTaskSchedulerPermittedIdentifiers` matches `IndexTask.id` and `continuedId`
  (a mismatch makes `submit` raise an uncatchable exception).
