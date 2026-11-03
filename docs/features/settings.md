# Settings

The shared counts, current schedule and UI contract are in [Reading](reading.md). It supersedes older reading-policy and copy descriptions below.


## Purpose

The switches and the few actions that change how Retriever behaves. The groups, in order: "How Retriever works and your
privacy", **Reading** (see [Reading](reading.md)), **Search by meaning** (the optional download: the same upgrade card as
Try and Home with what it adds and its size, then its progress with Cancel and the mobile-data switch; once done,
"Downloaded · 388 MB" and Remove search download, with no mobile-data switch; absent on text-only phones), **Included files** (Manage access, Process these
file types, Excluded folders), **Privacy** (Save recent searches, Clear recent searches, Android's Processing
notifications), **Storage** ("Retriever’s data" with "47.6 MB · kept on this phone" beneath, and Temporary files the same
way; no action, only facts), **Appearance** (Show On this day, Android's wallpaper colours) and **About**. Large files and
Find duplicate files are not settings: they are Home's last section, Free up space (`home.md`). Every row writes one `Prefs` field and shows the result at once. It never starts work by
itself beyond what the switch says (turning a kind on re-queues that kind; turning charging-only on reschedules).
Diagnostics (recent problems) and the test and sample modes exist in debug builds only.

Manage access opens Status on Android (its "What Retriever can see" rows change photo access and add folders) and Folders
on iOS. Audio has no switch on either platform: it reads "Name only on this device".

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
6. **Remove search download removes the model and its temporary files, and names and words keep searching.** Removing
   access is the other direction: affected files leave every lane at once (`ExclusionTest`).
7. **Clear recent searches, and turning Save recent searches off, empty the list and Android's launcher shortcuts.**

## Tests

- Words: `RETRIEVERS_DATA`, `keptOn`, `TEMPORARY_FILES`, `SEARCH_BY_MEANING`, `downloaded`, `REMOVE_DOWNLOAD`,
  `REMOVE_DOWNLOAD_ASK`, `NAMES_STILL_WORK` and `removeDownloadMessage` are core's, shared by both apps.
- Android, `SettingsViewModelTest`: `aToggleIsSavedAndShown`, `anIndexingProgressTickDoesNotRebuildTheScreen`,
  `aFailedSaveBecomesAMessageNotACrash`, `freeingUpSpaceSaysSoOnceThenClears`, `clearingRecentSearchesEmptiesTheListAndTheLauncherShortcuts`,
  `sampleModeStopsTheRunningBatchAndIndexesAgain`. Screenshots: `SettingsShotsTest` (`settings_light`, `settings_dark`,
  `settings_text2_0`, `settings_offer_*`, `settings_downloading_dark`; `groupsComeInTheSpecifiedOrder` pins the order).
- Android, `ReadingRulesTest` pins what each reading rule reads (text caps, short reads, one file listed by a folder and
  MediaStore counts once).
- iOS, `iosApp/RetrieverTests/SettingsModelTests.swift`: `testKindTogglesShowAtOnce`,
  `testTextOnlyPhonesOfferOnlyTheKindsTheyCanRead`, `testSettingsPersistThroughPrefsAndLeaveTheRestAlone`,
  `testMobileDataMovesFromTheOldKeyIntoPrefsOnce`, `testFreeUpSpaceRemovesTheModelAndItsTemporaryFiles`. Accessibility:
  `AccessibilityAuditTests.testStatusAndSettingsRoutesPassTheAccessibilityAudit` (and its dark twin).

## Known limits

- The open-source notices page is an in-screen boolean with a `BackHandler`, not a route, so it is invisible to the back
  stack and deep links.
- Tuning runs in the app scope, not WorkManager. If the process is cached and killed mid-tune, the next refresh re-runs
  it. Whether calibration becomes expedited WorkManager work is for `calibration.md` and `background-android.md`.
