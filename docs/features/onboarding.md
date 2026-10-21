# Onboarding

The shared counts, current schedule and UI contract are in [Reading](reading.md). It supersedes older reading-policy and copy descriptions below.


## Purpose

First run, with no download: a welcome that asks for access in one decision (Android: "Search your whole phone" through
`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, or a "Choose what to include" sheet; iOS: "Search all your photos"
through `PHPhotoLibrary`, or "Add folders from Files"; "Continue" when access is already there), a short read of a
planned few files shown as the retriever's pile of real previews, a "try it" screen that runs the real search, and a
choice of when to read the rest ("Choose when to process": While charging, Also on battery, When I start it). "Skip for
now" (`Words.GO_TO_HOME`, and "Skip the quick try") leaves setup for good, once access is chosen, with
the sample still reading. The model download and tune are not part of first run; they are the separate upgrade, "Search by meaning and
what's in your photos" (`UpgradePolicy`, see [Optional model](optional-model.md)), offered after the person has searched.

## Mechanism and why

- **One stage machine, in core.** `Setup.kt` maps `SetupInputs` (the persisted `SetupMark`, skipped, decided, planned,
  plus the app's own access state via `withAccess`) to a `SetupStage` (`WELCOME` until access is chosen, `SAMPLE`, `TRY`,
  `CHOOSE`, `DONE`), `showSetup` for launch routing, and the next `SetupStep` (`PLAN`, `READ_SAMPLE`, `SCHEDULE`). Both apps follow
  `Retriever.setup`; neither keeps its own stage logic. Every input is persisted (Store meta and Prefs), so each stage
  resumes after process death.
- **The apps run only the native side effects.** Android's `SetupFlow` and iOS's `AppModel+Setup` call `planOrCommit()`
  on `PLAN` (it syncs names first, and commits to everything when nothing is eligible), and start the sample read or the
  schedule once per process on `READ_SAMPLE` / `SCHEDULE`. Buttons map to `setSetupMark`, `skipFirstRun`, `commit` and
  `decideLater`.
- **Installs set up before this flow count as done.** `Prefs.setupStarted` (or a saved profile, or saved prefs on a phone
  too small for the model) marks them; apps no longer write it. That rule applies only when no `SetupMark` is stored, so
  a stored mark this version dropped (`STARTED`) reads as `NONE` and returns to the welcome.
- **The pile.** `Retriever.sampleFiles()` emits every planned file as a `SampleFile` (plan index, read, coverage note) in
  plan order; `SamplePile.shown` keeps the last 12 read, placed at Gallery's angles and offsets in a fixed 224 dp
  square. It is one accessibility element (the count, with See all as its action). The count says "79 files processed"
  and See all says "See the files" while files are still pending, since the plan can still shrink; "84 of 86 files
  read" and "See all 86 files" only once it can't, so a total never drops on screen. The count line holds the height
  of the picking sentence ("Trying a few of your recent files to show you how it works…") throughout and sits on the
  bar, so the group never moves between picking, reading and done; done says "All done. Now try finding something." Only a new card moves: it settles
  from above with a spring, and appears in place under Reduce Motion. Mood, the time line and the freeze follow one
  predicate: reading while the index runs, `ReadingStatus.pause` is null and files are pending. `pause` is set for stop
  phases only, so "Up to date" never shows before reading starts.
- **The short read is a plan, not a sample of whatever is first.** `FileRules.planTaste` picks a deterministic set within
  a time budget that covers the oldest bin and the newest; `Retriever.scopeIds` scopes try-it's search to those files, and
  `TasteProgress` reports per-kind progress, readiness (`TRY_AFTER_FILES` read or the plan done) and seconds left in
  wall time, null until this session has measured it (`sample-quickstart.md`). The plan's budget is wall time too: once
  measured, `trimTaste` prices what is left at inference × the session's wall ratio.
- **Try it is the real Search.** `TryItScreen` hosts `SearchContent` with a scope, so empty, searching and failure states
  are the ones in `search-ui.md`; nothing is faked. Names match across the whole library; words and meaning only inside
  the sample ("Searching every file’s name, and inside the {C} files processed so far.", `Words.searchingTry`, where C is
  `TasteProgress.read`).
- **Commit.** "Read everything" sets `Scope.ALL` and schedules the reading; "Not yet" keeps `SAMPLE`, and Home's status line
  offers "Choose when to process" (the same sheet). A returning user (no plan) never sees the sample.
- **Mascot** per `docs/motion-api.md`: `WELCOME` at 260 dp, 160 dp on the setup steps, `WALKING` while reading, `RESTING`
  when paused.

### What is switched off, and what turns it on

- **Content-derived suggestions.** `Retriever.suggestions(fileIds)` returns exactly three TF-IDF terms or recurring two-word phrases from read files'
  `content_text`, against the whole read set. Terms must occur in at least two selected files. Spreadsheet/CSV/JSON/code MIME types and chunks dominated by digits, delimiters or camelCase are excluded, along with English/Hindi stopwords, programming words, mixed alphanumeric tokens and short uppercase codes. Longer words receive more weight.
  With fewer than three, it returns none and try-it shows preview tiles instead. The same shared method feeds the search bar's "Try:" suggestions when there is no history; no fixed phrases are used.
  `ContentSuggestions` caches per-file frequencies by chunk revision. The ready grid opens the in-app viewer; document tiles show a real picture where the platform makes one (iOS Quick Look; an Android provider's thumbnail), else on Android a paper card badged with the file's extension (`DocCard`), never extracted text (`try-previews.md`).
- **Layout.** Try's grid is the shared `FileTile` at one computed size: four cells per row on Android (two past font
  scale 1.3), cell size rounded down to whole pixels so rows never break early, and four flexible columns on iOS. Kind
  pills wrap, and the ready action stays in the bottom inset.
- **No hours estimate.** `ESTIMATE_CALIBRATED = false` in `Estimate.kt`. While false, `Format` and Home show the fallback
  copy and no figure.
  It turns on after WP-P1: a full read timed on a device against its own short read, then `ESTIMATE_LOW` and
  `ESTIMATE_HIGH` set from the measured ratio (widened 10 % each side for a single device) and the flag flipped.
  iOS states no night count until a background run has been measured.

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/Setup.kt`: `SetupMark`, `SetupInputs`, `SetupStage`, `SetupStep`
- `androidApp/src/main/kotlin/app/retriever/setup/SetupFlow.kt`: the native side effects for each `SetupStep`
- `androidApp/src/main/kotlin/app/retriever/setup/SetupScreen.kt`, `FirstRunStages.kt`, `TryItScreen.kt`, `CommitScreen.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Taste.kt` (`Scope`, `TasteProgress`), `Estimate.kt`
  (`ESTIMATE_CALIBRATED`, `ReadEstimate`), `FileRules.kt` (`planTaste`)
- `iosApp/Retriever/Views/SetupView.swift`, `iosApp/Retriever/AppModel+Setup.swift`, `iosApp/Retriever/Text/Copy.swift`

## Invariants

1. **First run never downloads the model;** only the upgrade's Download does.
2. **Every stage resumes after process death** from persisted state, and Go to Home is remembered across launches.
3. **Suggestions quote real content only.** Semantic concept suggestions remain parked. Hours stay behind
   `ESTIMATE_CALIBRATED` until the timing evidence exists.
4. **The short read reads only its plan,** then stops; every other file is still found by name.
5. **"Read everything now" skips try-it and commit;** a returning user never sees the short read.
6. **Names are searchable from the moment access is granted,** before any model exists.
7. **Go to Home lands on Home** with the sample reading carrying on; skipping never skips access: "Search by name"
   without access stays on the welcome.

## Tests

- Core: `SetupTest` (every stage and step transition) and the host `SetupFlowTest` (persistence, the plan made once,
  Go to Home, pre-flow installs, Try's name and word scope, the upgrade offer).
- Core: `SamplePileTest` and the host `SampleFilesTest` (plan order, indices, read only with content, coverage notes).
- Android: `SetupFlowTest` (welcome access, Search by name, Go to Home, the upgrade and mobile data),
  `ReadingFilesTest` (pile footprint, cards, motion, the Try grid's one tile size) and `UpgradeCardTest`. Screenshots:
  `SetupStatusShotsTest` (welcome, the include sheet, the sample states at 1.0 and 2.0 text, fit at 411 × 891 dp).
- Core: `FileRulesTest` (`planTasteIsDeterministicAndWithinBudget`, `planTasteCoversOldestBinAndNewest`),
  `TasteScopeTest` (`aShortReadReadsOnlyThePlanThenCommitReadsTheRestOnce`, `aPlanBeforeTheTuneKeepsTheLightDefaults`,
  `celebratedOnlyOnceMarked`), `TasteProgressTest` (`readyAtTwentyFilesOrWhenThePlanFinishes`), `EstimateTest`
  (`noHoursShipUncalibrated`).
- iOS: `iosApp/RetrieverTests/CopyTests.swift` and `AppModelTests.swift` for the stage copy and state;
  `AccessibilityAuditTests` audits every `setup.*` and `upgrade.*` fixture route.

## Known limits

- Concept inference and the hours gate still need device evidence; literal content terms do not infer concepts.
- The estimate ranges are placeholders; one phone is one data point.
- Tuning runs in the app scope, not WorkManager (see `settings.md`).
