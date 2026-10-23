# Model download

## Purpose

Optionally fetch the selected M440 build (387.71 MB generic; larger chip-specific builds) once, over Wi-Fi unless the person allows mobile data, prove it is the pinned file, and
survive the app being suspended, killed or offline. The model is "ready" only when its size and a hash marker match.

## Mechanism and why

- **Android: `DownloadManager`** (the Gallery `DownloadAndTryButton` pattern). It is a system service: it survives process
  death, holds to unmetered networks with `setAllowedOverMetered(prefs.allowMobileData)`, resumes, and shows its own
  notification. That is simpler than a WorkManager download worker with a foreground service. The file lands in
  app-specific external storage (`models/`), which needs no permission and is excluded from backup.
- **State lives in the app scope.** `ModelRepository` (one state, one download at a time) runs in `AppScope`, polls
  `ModelDownload.progress` once a second, and exposes `ModelState`: Missing, Downloading, WaitingForWifi, Checking, Failed,
  Done. Leaving a screen never drops a download. `sync` re-attaches to a download a killed process left and re-hashes a
  file already in place.
- **Pinned and verified.** URLs are commit-pinned (`resolve/<commit>/`) with size and SHA-256 in `ModelBuilds`. After
  completion `ModelDownload.verify` streams SHA-256 in 1 MB chunks, writes `<file>.sha256` on a match and deletes the file
  on a mismatch. `ready()` is cheap on purpose: size plus the marker, no hashing. A verified file prunes other model files
  (`prune`), refreshes setup and starts indexing.
- **Space check first.** `hasSpaceFor` requires the file size plus a 300 MB margin before enqueueing, counted with
  `StorageManager.getAllocatableBytes` (free space plus other apps' caches Android can clear; `StatFs` where the volume
  has no UUID). `start` then calls `allocateBytes`, so those caches are cleared before DownloadManager needs the room
  (developer.android.com/training/data-storage/app-specific#query-free-space); its refusal stands, as StatFs would ignore
  the low-storage reserve every index run waits above. A shortfall, or DownloadManager's
  `ERROR_INSUFFICIENT_SPACE` mid-download, is a space failure (`Failed.noSpace`): the card says how much room it needs
  and offers Free up space (`StorageManager.ACTION_MANAGE_STORAGE`) next to Try again. Every Android surface of it does the
  same (`FreeUpSpaceButton`): setup's line under the first search, and Home's one-time card and status line, whose title
  then says there isn't enough free space instead of core's stopped-download line about Wi-Fi.
- **iOS: a background `URLSession`** (`app.retriever.model`), because iOS suspends or kills the app while a large download
  runs. `handleEventsForBackgroundURLSession` stores the system completion handler; resume data is kept per network rule; the
  Wi-Fi-only rule is enforced by `allowsExpensiveNetworkAccess` on the request. A live task with the same
  `taskDescription` (file name, hash, cellular flag) is adopted instead of starting a second download. The same SHA-256 and
  `.sha256` marker scheme applies, and the file is excluded from backup.
- **Copy.** Failures say what to do ("The download stopped. Check your connection and try again.", or the space needed),
  never a code.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/model/ModelDownload.kt`
- `androidApp/src/main/kotlin/app/retriever/model/ModelRepository.kt`
- `androidApp/src/main/kotlin/app/retriever/di/AppModule.kt` (wiring, `PlatformReady`)
- `core/src/commonMain/kotlin/app/retriever/core/ModelBuilds.kt` (URLs, sizes, hashes)
- `iosApp/Retriever/Core/ModelDownload.swift`
- `iosApp/Retriever/RetrieverApp.swift` (background session hand-off)
- `iosApp/Retriever/Text/Copy.swift` (download titles)

## Invariants

1. The file is used only when its size equals the pinned size and the marker holds the pinned hash; a partial download never
   passes `ready()`.
2. Never two downloads of the same build: `active` and `launchOnce` (Android), task adoption (iOS).
3. A running download is never hashed or deleted under itself.
4. A hash mismatch deletes the file, and a download that stopped removes its partial file.
5. A verification failure is a `Failed` state, not a crash.
6. The model file and its marker are never backed up.

## Tests

`ModelDownloadTest`, `ModelRepositoryTest` (androidApp), `ModelBuildsTest`, `CopyTests` (iOS, download titles).

## Known limits

- iOS: a resumed download may present HTTP 206; confirm on a device that the delegate accepts it. Device queue.
- iOS download progress updates the UI on every `didWriteData`; rounding the fraction to whole percent would cap invalidations.
- On a background relaunch the iOS SHA-256 check can be suspended; the marker is written on the next resume or `start()`.
- Android completion work (verify, prune, index) runs only while the process polls; after a death it waits for the next
  app open (`ModelRepository.sync`), which is acceptable because Tune needs the app anyway.
- A MediaTek phone without the vendor NPU runtime could download the NPU build, fail, then download the generic one.
