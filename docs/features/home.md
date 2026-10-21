# Home

## Purpose

The first screen once the app is set up, and where you start looking for something. In order: the title
"Retriever", one status line (core's `ReadingPolicy.line`: what is happening and whether to do anything), Starred,
Recently added, On this day, Browse, and the optional search-by-meaning offer last under "Optional". The search bar sits
at the bottom and asks "What are you looking for?"; its suggestions are recent searches, or "Try:" words from the
person's own files when there are none. Design and reasons: `retriever-flow/clarity-proposal.md`; as built:
`clarity.md`. The search bar and results are in `search-ui.md`.

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
- **Sections draw together.** Below the status line, nothing draws until the home facts and On this day (where it can
  show) have answered (`sectionsReady`, on both platforms), so no section lands above one already shown. The
  suggestions belong to the search bar now and don't hold Home back. A failed On this day lookup counts as an answer with no memories, a failed
  recent-searches read as none, and a failed suggestions read as no suggestions.
- **Long press.** Recently added and Starred tiles carry the same file menu as Search and
  Browse ("More options", with matching screen-reader actions).
- **Fixed order, hide at zero.** Sections never move because a count changed; one with nothing to say is not drawn.
  Sections carry no reading subtitles; how far reading has got is the status line's job.
- **User-facing kinds only, enforced in SQL.** No Home picture or "newest" list can hold an OTHER file; the Starred strip
  and its count use the visible-starred queries, while the full Starred list still holds OTHER stars.
- **On this day without a date library in core.** The platform computes each past year's local-day range with real zone
  rules (`onThisDayRanges`, `dayRange` on Android; `Calendar` on iOS) and core answers with `Retriever.onThisDay`. The
  result is asked once per local date and again when the date changes. The person can hide it (`showOnThisDay`).
- **The status line.** One line under the title, the dog beside it and a thin bar only while reading or
  downloading; its one button is spelled out on a row of its own ("Choose when to process" opens the choice sheet,
  "Process for 15 minutes", "Stop", "Resume", "Allow access", "Try again"); the whole line opens Processing. A
  download or its setup is the line itself. It shows only once core's live `readingStatus()` has answered, since that
  carries the saved "N% done" mark. All done carries an [x].
- **Browse.** Photos, Videos, Screenshots, Documents (PDFs too), Audio, then Folders: name and count only, a kind
  with no files hidden; sizes are in Settings → Storage. Three a row, two at large text.
- **Optional.** The search-by-meaning offer is a flat row at the end with a tonal Download and Not now
  (`UpgradePolicy.showOnHome`), never the strongest button on Home.
- **Dismissal.** `Prefs.dismissedReadingCycle` hides All caught up across launches. The first actual file processed after dismissal advances `readingCycle`; empty scans and renames do not. The existing celebration budget remains separate.
- **Mascot.** Placement follows `docs/motion-api.md`: `WALKING` on the empty first-scan state, `NO_MATCH` on an empty
  library, `CAUGHT_UP` once ever on the status line (`Celebration`, Store meta `celebrated`, haptic 0.2 s after the pop).
  Starred, Home with files and Browse with files stay static.
- **Thumbnails** are `ui/components/Thumbnails.kt` on Android and `Thumbnail.swift` on iOS (one cache, bucketed sizes,
  keys that change with the file's revision; see `browse-status.md`).
- **iOS.** `AppModel` (`@Observable`, one `Snap` value per snapshot) feeds `HomeView.swift` and `HomeSections.swift`;
  tiles use `@ScaledMetric` and layouts switch at accessibility sizes.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/home/HomeViewModel.kt`: `HomeUi`, `Celebration`, day ranges, `reusing`
- `androidApp/src/main/kotlin/app/retriever/home/HomeScreen.kt`: the status line, the choice sheet, empty Home, celebration effect
- `androidApp/src/main/kotlin/app/retriever/home/HomeSections.kt`: Starred, Recently added, On this day, Browse
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
- Android screenshots: `HomeShotsTest` (every status-line state in light and dark, `defaultLargestText` with two Browse
  columns) and `AdaptiveShotsTest`; recomposition: `RecompositionTest` in `search/`.
- iOS, `iosApp/RetrieverTests/HomeModelTests.swift`: `testNoOtherFileInAnyHomeList`,
  `testFirstRunSaysLookingUntilAScanFinishes`, `testSectionsHideWhenTheyHaveNothingToSay`,
  `testTilesAreFixedOrderAndOnlyForCountsAboveZero`, `testCaughtUpIsCelebratedOnceEverNotForALaterCatchUpOrAfterRelaunch`, `testAnUnchangedSnapshotDoesNotReassignSnap`.

## Known limits

- iOS `Snap` is one coarse observable value, so any screen that reads a field re-renders per snapshot while reading;
  splitting it is a refactor the model tests would guard.
- The 50 ms budget for `home()` is a Mac figure with the Pixel inferred at 3 to 5 times; it has not been timed on a
  device yet.
- The caught-up pop and the walking dog have no device Perfetto numbers yet (the cost gate is in `mascot.md`).
