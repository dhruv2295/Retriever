# Onboarding

The shared counts, current schedule and UI contract are in [Reading](reading.md). It supersedes older reading-policy and copy descriptions below.


## Purpose

Search by description (the model) is the product (`PRODUCT.md`), so first run proves it on the person's own files and
then gets out of the way. Four steps, one button each (owner-approved design, 2026-10-23; mocks on the "Retriever setup
redesign" canvas):

1. **Hello** (`SetupStage.WELCOME`): the waving mascot, `Words.helloTitle(textOnly)`, a looping illustrated example
   (`HELLO_EXAMPLES`, labelled "For example"), the privacy line `onDevice`, **Get started**. Under it `downloadNote` when
   `SetupPolicy.downloadOnGetStarted`: Get started marks GREETED and starts the one-time download on Wi-Fi.
2. **Access** (`ACCESS`): "Choose what Retriever can search". iOS has no whole-device permission, so one screen holds two
   rows, Photos and videos (the Photos prompt, limited and refused states in the row) and Documents (folders from Files);
   Continue once either is given. Android asks for all-files access, with "Choose what to include" as the alternative.
   Nothing given keeps the person here on purpose: there is nothing to search without access.
3. **First search** (`SAMPLE`): the demo's 30 recent files with search from the first frame. The field says "Search by
   name or words" until search by description is ready, so no one describes too early; a quiet line says where the
   download is (`descriptionLine`, with Try again after a failure, and Free up space too on Android when space ran out),
   and a banner says when it is ready. Continue calls `Retriever.finishSetup()`.
4. **Home**, with a one-time card (`Prefs.homeIntroDue`) under the search field: what the recent files can do now
   (`recentFilesBody`, per `DescriptionState`, with its action), the rest of the files while charging and not in use,
   new files picked up automatically. No time is promised. Got it dismisses it; the status line and upgrade row carry
   the state after that. Android's notification ask lives on this card.

There is no choice of when to process (one automatic strategy, owner decision 2026-10-23) and no skip.
`UpgradePolicy.description` turns the upgrade stage and the platform's `DownloadPhase` into the one `DescriptionState`
both apps and both screens show. An install from before this flow, without the model, is offered it on Home at once.

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
- **Mascot** per `docs/motion-api.md`: `WELCOME` at 180 dp, 160 dp on the setup steps, `WALKING` while reading, `RESTING`
  when paused.

### What is switched off, and what turns it on

- **Content-derived suggestions.** `Retriever.suggestions(fileIds)` returns exactly three TF-IDF terms or recurring two-word phrases from read files'
  `content_text`, against a bounded sample: the newest 300 read files by modified time plus the files asked about, each
  by its first 4,000 characters (`ContentSuggestions.FILES`, `TEXT`). The whole read set cost 922 MB and 3.9 s per
  recount at 17,500 files. Terms must occur in at least two selected files. Spreadsheet/CSV/JSON/code MIME types and chunks dominated by digits, delimiters or camelCase are excluded, along with English/Hindi stopwords, programming words, mixed alphanumeric tokens and short uppercase codes. Longer words receive more weight.
  With fewer than three, it returns none and try-it shows preview tiles instead. The same shared method feeds the search bar's "Try:" suggestions when there is no history; no fixed phrases are used.
  `ContentSuggestions` caches per-file frequencies by chunk revision, inside one shared computation per file set: it
  recounts at most once a second while indexing writes, and its maps go when no screen collects it. The ready grid opens the in-app viewer; document tiles show a real picture where the platform makes one (iOS Quick Look; an Android provider's thumbnail), else on Android a paper card badged with the file's extension (`DocCard`), never extracted text (`try-previews.md`).
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

1. **Get started downloads the model** (reversed 2026-10-23; it used to be "first run never downloads"), on Wi-Fi
   unless the person allows mobile data, only where it runs and is missing, and only with room for it; the size is on
   Hello before the tap.
2. **Every stage resumes after process death** from persisted state.
3. **Suggestions quote real content only.** Semantic concept suggestions remain parked. Hours stay behind
   `ESTIMATE_CALIBRATED` until the timing evidence exists; no setup or Home sentence promises a time.
4. **The demo reads only its plan** until setup finishes; every other file is still found by name.
5. **No describing before it works:** the first search's hint and Home's card say "name or words" until
   `DescriptionState.READY`.
6. **Names are searchable from the moment access is granted,** before any model exists.
7. **Nothing skips access.** The access screen has no skip; a phone too small for the model gets the same flow with
   promises it can keep (`helloTitle(textOnly)`, no download line).
8. **The model finishes setting up without the app in front where the platform allows:** Android verifies the
   download and tunes from background work; iOS tunes only in front (it refuses the GPU in the background) and
   protects the download's check with a background task.

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
