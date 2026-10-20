# Home

## Purpose

The first screen once the app is set up: the search bar, one library hero with reading status and top folders, one row of recent or content-derived
searches, then the person's own pictures and files in a fixed order (photos and videos mosaic, screenshots, documents,
On this day, Starred, kind tiles, Tidy up). It says what can be searched and how far reading has got. The visual
decisions (Gallery layout, grafts taken and rejected) are in the Home v2 spec; this note records only how the code
holds them. The search bar and results are in `search-ui.md`.

## Mechanism and why

- **One immutable state.** `HomeViewModel` exposes `StateFlow<HomeUi>`, collected with `collectAsStateWithLifecycle`.
  `HomeUi.counts` is `null` until the store answers, and `null` draws nothing, so neither "empty library" nor "caught up"
  shows before the data is in (the premature-empty rule applied to Home).
- **One store subscription, in core.** `counts()`, `readingStatus()` and `home()` are flows core shares across every
  collector and conflates; `home()` (newest files, stars, folders, large files, the totals) reads again only when files
  come, go or change, never because a file was read, and `suggestions()` follows what is read
  (`sample-quickstart.md`, Core API for the apps). Collect them only while Home shows.
- **Stable lists.** `HomeFacts.reusing` hands back the old list instances for strips that did not change, so the
  `LazyStaggeredGrid` items they feed skip recomposition on a refresh. `HomeUi` is `@Immutable`.
- **Sections draw together.** Below the hero, nothing draws until the home facts, the suggestions and On this day
  (where it can show) have all answered (`sectionsReady`; iOS also waits for recent searches), so no section lands above
  one already shown. A failed On this day lookup counts as an answer with no memories.
- **Long press.** The photo mosaic, screenshots, documents and starred cards carry the same file menu as Search and
  Browse ("More options", with matching screen-reader actions).
- **Fixed order, hide at zero.** Sections never move because a count changed; one with nothing to say is not drawn.
  `Words.kindProgress` (core, shared by both apps) turns counts into the section subtitle: "Name only", "{N} waiting to read" while files are scheduled, otherwise nothing.
- **User-facing kinds only, enforced in SQL.** No Home picture or "newest" list can hold an OTHER file; the Starred strip
  and its count use the visible-starred queries, while the full Starred list still holds OTHER stars.
- **On this day without a date library in core.** The platform computes each past year's local-day range with real zone
  rules (`onThisDayRanges`, `dayRange` on Android; `Calendar` on iOS) and core answers with `Retriever.onThisDay`. The
  result is asked once per local date and again when the date changes. The person can hide it (`showOnThisDay`).
- **Library hero.** Compact: "Your files", "{L} files in your library", "{C} files read · {P} partly read", and one
  status row with the mascot and a "Reading status" link; no kind bar. The status follows the actual
  reading/paused/caught-up state, one reason and one remedy at a time, and waits on `SetupInputs.libraryChosen`.
  The upgrade row sits under the status. The commit choice sits outside the hero.
- **More on your phone.** A list with count and size per kind (`kindTotals`, every kind including Other), then the
  largest folders (`folderTotals`, each folder's own files, most first) and All folders, opening existing Browse/Folder
  routes. Unknown sizes are counted apart: "Size unavailable", never "0 B".
- **Status and commit choice.** The existing status mapping and mascot sit inside the hero. The
  commit card shows only when the first run's short read is done and files still wait; "Read everything" opens the
  setup's three answers as a sheet. The hours estimate is `null` until `ESTIMATE_CALIBRATED` (see `onboarding.md`).
- **Dismissal.** `Prefs.dismissedReadingCycle` hides All caught up across launches. The first actual file read after dismissal advances `readingCycle`; empty scans and renames do not. The existing celebration budget remains separate.
- **Mascot.** Placement follows `docs/motion-api.md`: `WALKING` on the empty first-scan state, `NO_MATCH` on an empty
  library, `CAUGHT_UP` once ever on the status line (`Celebration`, Store meta `celebrated`, haptic 0.2 s after the pop).
  Starred, Home with files and Browse with files stay static.
- **Thumbnails** are `ui/components/Thumbnails.kt` on Android and `Thumbnail.swift` on iOS (one cache, bucketed sizes,
  keys that change with the file's revision; see `browse-status.md`).
- **iOS.** `AppModel` (`@Observable`, one `Snap` value per snapshot) feeds `HomeView.swift` and `HomeSections.swift`;
  tiles use `@ScaledMetric` and layouts switch at accessibility sizes.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/home/HomeViewModel.kt`: `HomeUi`, `Celebration`, day ranges, `reusing`
- `androidApp/src/main/kotlin/app/retriever/home/HomeScreen.kt`: status, commit card, empty Home, celebration effect
- `androidApp/src/main/kotlin/app/retriever/home/HomeSections.kt`: the strips, mosaic, On this day, kind tiles
- `core/src/commonMain/kotlin/app/retriever/core/Retriever.kt`: `home()`, `onThisDay`, `counts()`, `celebrated()`
- `iosApp/Retriever/Views/HomeView.swift`, `iosApp/Retriever/Views/HomeSections.swift`, `iosApp/Retriever/AppModel.swift`

## Invariants

1. **No empty or "done" state before the data is loaded** (`counts == null` draws nothing).
2. **No OTHER file in any Home picture or recent list.**
3. **Section order is fixed;** a section hides at zero but never moves.
4. **No hours figure while `ESTIMATE_CALIBRATED` is false.**
5. **The caught-up pop happens once ever,** and is marked spent only after the haptic, so a screen left in that moment
   gets it next time.
6. **Home work runs through `Retriever` off the main thread;** nothing in composition touches the store.
7. **The date-dependent "On this day" never runs inside the refresh throttle** and does not re-run for the same date.

## Tests

- Android, `HomeViewModelTest`: `homeFollowsTheIndexAndAccess`, `noHomeListHoldsAnOtherFile`,
  `unchangedListsKeepTheirInstances`, `readinessSaysWhereAKindStands`, `starredFilesAreCounted`,
  `countsAreSubscribedToOncePerViewModel`, `theCelebrationFiresOnceAndNeverForALaterCatchUpOrAfterARelaunch`,
  `theCelebrationIsNotSpentUntilItIsMarked`, `readEverythingCommitsTheScopeAndSchedulesTheReading`,
  `aDayIsRangedByTheZonesRulesAcrossDstChanges`, `twentyNinthOfFebruaryIsOnlyInLeapYears`,
  `onThisDayAsksOncePerLocalDateAndAgainWhenTheDateChanges`, `hidingOnThisDayIsAtOnceAndTheStatusSwitchBringsItBack`.
- Android screenshots: `HomeShotsTest` (`caughtUpLight`, `caughtUpDark`, `aKindNotReadInsideIsFoundByName`) and
  `AdaptiveShotsTest`; recomposition: `RecompositionTest` in `search/`.
- iOS, `iosApp/RetrieverTests/HomeModelTests.swift`: `testNoOtherFileInAnyHomeList`,
  `testFirstRunSaysLookingUntilAScanFinishes`, `testSectionsHideWhenTheyHaveNothingToSay`,
  `testTilesAreFixedOrderAndOnlyForCountsAboveZero`, `testCommitCardShowsOnlyWhenTheShortReadIsDoneAndFilesWait`,
  `testCaughtUpIsCelebratedOnceEverNotForALaterCatchUpOrAfterRelaunch`, `testAnUnchangedSnapshotDoesNotReassignSnap`.

## Known limits

- iOS `Snap` is one coarse observable value, so any screen that reads a field re-renders per snapshot while reading;
  splitting it is a refactor the model tests would guard.
- The 50 ms budget for `home()` is a Mac figure with the Pixel inferred at 3 to 5 times; it has not been timed on a
  device yet.
- The caught-up pop and the walking dog have no device Perfetto numbers yet (the cost gate is in `mascot.md`).
