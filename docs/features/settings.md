# Settings

## Purpose

The switches and the few actions that change how Retriever behaves: which kinds are read inside, read only while
charging, allow mobile data, remember searches, dynamic color, a small "sample" mode, folders the person has excluded,
tune again, model download state, free up space, and the open-source notices. Every row writes one `Prefs` field and
shows the result at once. It never starts work by itself beyond what the switch says (turning a kind on re-queues that
kind; turning charging-only on reschedules).

## Mechanism and why

- **One immutable `SettingsUi`.** `SettingsViewModel` builds it with `combine(...)` over prefs, the index facts, model
  download state, model-missing, the required build and a small local state, then `stateIn(WhileSubscribed(5000))`.
  The screen is a stateless `SettingsContent(ui, actions)`, driven by the screenshot tests (state hoisting).
- **Index progress is reduced before it is combined.** Settings reads only three facts from `IndexState` (backend, detail,
  build) through `map { it.facts() }.distinctUntilChanged()`, so a progress tick during reading does not rebuild the
  page.
- **Prefs are the only store.** Each setter is `retriever.updatePrefs { copy(...) }` inside `launchCatching(::fail)`; a
  failure (a full disk is the likely one) becomes a message in the state and a snackbar, never a crash. Side effects live
  next to the write: `requeue` plus `indexNow` when a kind is turned on, `schedulePeriodic` for charging-only, `restart`
  for sample mode. `updatePrefs` clears saved searches when "remember searches" goes off.
- **Work that must outlive the screen runs in the app scope.** Tune again, freeing up space and cancelling a download use
  an injected `appScope`, so leaving the screen mid-tune does not cancel it; indexing waits meanwhile.
- **Dependencies are injected** (`tuner` is a lambda because a tune needs the app's file sources), which is what lets
  `SettingsViewModelTest` run without a device.
- **iOS.** `SettingsView.swift` over `AppModel+Settings.swift`; toggles update the local value at once and persist once.
  The old mobile-data key moves into `Prefs` a single time.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/settings/SettingsViewModel.kt`, `SettingsScreen.kt`, `Storage.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Prefs.kt`: the prefs and their defaults
- `iosApp/Retriever/Views/SettingsView.swift`, `iosApp/Retriever/AppModel+Settings.swift`

## Invariants

1. **A toggle shows its new value at once** and a failed save becomes a visible message, not a crash.
2. **Settings does not recompose on indexing progress.**
3. **A tune started here is not cancelled by leaving the screen.**
4. **Turning "remember searches" off clears the saved searches and the launcher shortcuts built from them.**
5. **Text-only phones offer only the kinds they can read.**
6. **Free up space removes the model and its temporary files, and names and words keep searching.**

## Tests

- Android, `SettingsViewModelTest`: `aToggleIsSavedAndShown`, `anIndexingProgressTickDoesNotRebuildTheScreen`,
  `aFailedSaveBecomesAMessageNotACrash`, `freeingUpSpaceSaysSoOnceThenClears`,
  `sampleModeStopsTheRunningBatchAndIndexesAgain`. Screenshots: `SettingsShotsTest`.
- Android, `ReadingRulesTest` pins what each reading rule reads (text caps, short reads, one file listed by a folder and
  MediaStore counts once).
- iOS, `iosApp/RetrieverTests/SettingsModelTests.swift`: `testKindTogglesShowAtOnce`,
  `testTextOnlyPhonesOfferOnlyTheKindsTheyCanRead`, `testSettingsPersistThroughPrefsAndLeaveTheRestAlone`,
  `testMobileDataMovesFromTheOldKeyIntoPrefsOnce`, `testFreeUpSpaceRemovesTheModelAndItsTemporaryFiles`.

## Known limits

- The open-source notices page is an in-screen boolean with a `BackHandler`, not a route, so it is invisible to the back
  stack and deep links.
- Tuning runs in the app scope, not WorkManager. If the process is cached and killed mid-tune, the next refresh re-runs
  it. Whether calibration becomes expedited WorkManager work is for `calibration.md` and `background-android.md`.
