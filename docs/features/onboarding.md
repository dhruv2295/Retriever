# Onboarding

## Purpose

First run: a welcome that starts the model download at once, access to files, a short read of a planned few files, a
"try it" screen that runs the real search over those files, and a commit choice for the rest of the library. A person
who skips lands on Home with the work carrying on. The flow, copy and timing decisions are in the onboarding spec and
build plan (see the scratchpad inputs listed in the wave brief); this note records how the code holds them and which
parts are deliberately switched off.

## Mechanism and why

- **One app-scoped flow, not a ViewModel.** `SetupFlow` is a Koin singleton holding `SetupUi`. Setup has to outlive the
  screen and survive process death, so every stage is derived from persisted state (Prefs and Store meta), never kept
  only in memory.
- **A pure step machine.** `SetupFlow.stage` maps the state to a `SetupStage` (`WELCOME`, `ACCESS`, `WAITING`, `READ_FEW`,
  `TRY`, `COMMIT`, `DONE`) and the top-level `nextStep(ui, hasSpace, metered, profileExists)` picks the next action
  (`SetupStep`). Because it is a plain function, `SetupStepTest` covers every branch without a screen.
- **The download starts on the welcome tap.** Either welcome button starts it, before the access question, so most of the
  wait happens while the person decides. It goes to the platform's download service (Android `DownloadManager`, a
  background `URLSession` on iOS), the right tool for a large file that must survive process death.
- **First tune keeps tier defaults.** Scope lives in Store meta (`Scope.SAMPLE` or `ALL`), not Prefs, so choosing a short
  read does not write Prefs before the tune and cost a slow phone its tier defaults; a plan made before the tune keeps
  the light defaults.
- **The short read is a plan, not a sample of whatever is first.** `FileRules.planTaste` picks a deterministic set within
  a time budget that covers the oldest bin and the newest; `Retriever.scopeIds` scopes try-it's search to those files, and
  `TasteProgress` reports per-kind progress, readiness (`TRY_AFTER_FILES` read or the plan done) and seconds left.
- **Try it is the real Search.** `TryItScreen` hosts `SearchContent` with a scope, so empty, searching and failure states
  are the ones in `search-ui.md`; nothing is faked.
- **Commit.** "Read everything" sets `Scope.ALL` and schedules the reading; "Not yet" keeps `SAMPLE` and leaves a commit
  card on Home. A returning user (no plan) never sees `READ_FEW`.
- **Mascot** per `docs/motion-api.md`: `WELCOME` at 260 dp, `WALKING` while downloading, tuning and reading, `RESTING`
  when paused, `TROUBLE` on download, space and tune failures.

### What is switched off, and what turns it on

- **Content-derived suggestions.** `Retriever.suggestions(fileIds)` computes three to six TF-IDF terms or recurring two-word phrases from read files'
  `content_text`, against the whole read set. Terms must occur in at least two selected files. Spreadsheet/CSV/JSON/code MIME types and chunks dominated by digits, delimiters or camelCase are excluded, along with English/Hindi stopwords, programming words, mixed alphanumeric tokens and short uppercase codes. Longer words receive more weight.
  With too little text, try-it offers kind filters. The same shared method feeds Home; no fixed phrases are used.
  `ContentSuggestions` caches per-file frequencies by chunk revision. The ready grid opens the in-app viewer; document tiles show the filename and first extracted content lines through the shared thumbnail path.
- **Layout.** Four weighted square cells per row on Android (spacers complete the last row), four flexible columns on
  iOS. URI keys preserve arrival animations. Kind pills wrap, and the ready action stays in the bottom inset.
- **No hours estimate.** `ESTIMATE_CALIBRATED = false` in `Estimate.kt`. While false, `Format` and Home show the fallback
  copy ("Reading the other N takes hours; Retriever measures this phone as it goes", `Format.restSentence`) and `HomeUi.estimate` is `null`.
  It turns on after WP-P1: a full read timed on a device against its own short read, then `ESTIMATE_LOW` and
  `ESTIMATE_HIGH` set from the measured ratio (widened 10 % each side for a single device) and the flag flipped.
  iOS states no night count until a background run has been measured.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/setup/SetupFlow.kt`: `SetupFlow`, `SetupStage`, `SetupStep`, `nextStep`
- `androidApp/src/main/kotlin/app/retriever/setup/SetupScreen.kt`, `FirstRunStages.kt`, `TryItScreen.kt`, `CommitScreen.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Taste.kt` (`Scope`, `TasteProgress`), `Estimate.kt`
  (`ESTIMATE_CALIBRATED`, `ReadEstimate`), `FileRules.kt` (`planTaste`)
- `iosApp/Retriever/Views/SetupView.swift`, `iosApp/Retriever/AppModel+Setup.swift`, `iosApp/Retriever/Text/Copy.swift`

## Invariants

1. **Setup never queues the model twice,** and never fetches it unasked after Free up space.
2. **Every stage resumes after process death** from persisted state.
3. **Suggestions quote real content only.** Semantic concept suggestions remain parked. Hours stay behind
   `ESTIMATE_CALIBRATED` until the timing evidence exists.
4. **The short read reads only its plan,** then stops; every other file is still found by name.
5. **"Read everything now" skips try-it and commit;** a returning user never sees the short read.
6. **Names are searchable from the moment access is granted,** before any model exists.
7. **Skip lands on Home** with the download and reading carrying on.

## Tests

- Android, `SetupStepTest`: `welcomeTapDownloadsBeforeAskingForFiles`, `theWelcomeTapQueuesTheDownloadWithoutFileAccess`,
  `aPlannedShortReadRunsThroughItsStages`, `tryingFirstPlansAtTheNamesSyncAndResumesAfterProcessDeath`,
  `readEverythingNeverSeesTryOrCommit`, `aReturningUserNeverSeesTheShortRead`, `meteredAsksBeforeAnything`,
  `failedDownloadWaitsForTryAgain`, `tuneFailedDoesNotLoop`, `textOnlyNeverDownloadsOrTunes`.
  Screenshots: `SetupStatusShotsTest`.
- Core: `FileRulesTest` (`planTasteIsDeterministicAndWithinBudget`, `planTasteCoversOldestBinAndNewest`),
  `TasteScopeTest` (`aShortReadReadsOnlyThePlanThenCommitReadsTheRestOnce`, `aPlanBeforeTheTuneKeepsTheLightDefaults`,
  `celebratedOnlyOnceMarked`), `TasteProgressTest` (`readyAtTwentyFilesOrWhenThePlanFinishes`), `EstimateTest`
  (`noHoursShipUncalibrated`).
- iOS: `iosApp/RetrieverTests/CopyTests.swift` and `AppModelTests.swift` for the stage copy and state.

## Known limits

- Concept inference and the hours gate still need device evidence; literal content terms do not infer concepts.
- The estimate ranges are placeholders; one phone is one data point.
- Tuning runs in the app scope, not WorkManager (see `settings.md`).
