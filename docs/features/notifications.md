# Notifications

## Purpose

Tell the person what Retriever is doing in the background, only when it matters: a progress notification while a visible
batch runs as a foreground service (with a Pause action), and one "Ready to try" note after the first short read when they
have left the app. Android only; iOS posts no local notifications (its progress is the continued task's Lock Screen UI, see
`background-ios.md`, and the download has no notification of its own beyond the system's).

## Mechanism and why

- **One low-importance channel** (`indexing`, `IMPORTANCE_LOW`, created in `Application.onCreate`): silent, no heads-up.
  Channel creation is idempotent, the documented pattern.
- **Progress** is the foreground notification of `IndexWorker`'s `ForegroundInfo`, built by `Notifications.progress`:
  ongoing, alert-once, a determinate progress bar from `IndexState`, the current file name, a tap that opens the app and a
  Pause action. The worker refreshes it at most once a second from `Retriever.state`, and cancels its own post when the run
  ends, because posts made without a foreground service outlive the worker.
- **Pause** is a `PendingIntent.getBroadcast` to `PauseReceiver`, which uses `goAsync` and calls `IndexService.pause()`. It
  does not cancel by work id: that would cancel the periodic request for good.
- **Ready to try** is posted from `SetupFlow` when the first taste read becomes usable while the app is hidden, and only if
  `areNotificationsEnabled()`. It is auto-cancelled on tap and opens the app.
- **Permission.** `POST_NOTIFICATIONS` (API 33+) is asked in setup (`SetupScreen`), after the person has seen what the app
  does, with the same check-then-request pattern as Gallery. A denied permission never blocks indexing: the foreground
  service still runs, only its notification is hidden by the system.
- **Download progress** uses the `DownloadManager` system notification (`model-download.md`), not this channel.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/indexing/Notifications.kt`
- `androidApp/src/main/kotlin/app/retriever/indexing/PauseReceiver.kt`
- `androidApp/src/main/kotlin/app/retriever/IndexWorker.kt` (`foregroundInfo`, `showProgress`)
- `androidApp/src/main/kotlin/app/retriever/indexing/IndexService.kt` (`notifyReadyToTry`)
- `androidApp/src/main/kotlin/app/retriever/setup/SetupFlow.kt`
- `androidApp/src/main/kotlin/app/retriever/setup/SetupScreen.kt` (the permission request)
- `androidApp/src/main/AndroidManifest.xml` (permissions, foreground service types)

## Invariants

1. A notification appears only for work the person can see the reason for; a sync-only run posts none.
2. Posting never blocks or fails indexing (a refused foreground start is caught; a denied permission is fine).
3. The Pause action pauses through the same path as the Status screen's button.
4. Copy is plain and warm: "Reading your files", "Ready to try", never a stack trace or a code.
5. The worker's own posts are cancelled when the worker ends.

## Tests

`IndexWorkerTest` (androidApp) covers the worker that owns the foreground notification; `SetupStepTest` covers the setup
step that triggers "Ready to try". There is no direct test of `Notifications` or `PauseReceiver` yet.

## Known limits

- Add a Robolectric test that the Pause broadcast pauses through `IndexService` and that the channel is created once.
- The progress notification is a foreground-service notification, so Android 14+ lets the person dismiss it while the
  service runs; the work continues regardless.
- Strings are plain literals in `Notifications.kt`; move them to resources if the app is localized.
