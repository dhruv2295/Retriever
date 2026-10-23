# Search UI

## Purpose

The full-screen search on both platforms: a search bar that sits over Home and expands to the full screen, results in
two phases (file names first, then the full search), kind chips, a Starred chip, the understood-filters line with an X,
search by photo, recent searches, star with Undo, and explicit empty, searching and failed states. The one rule that
matters most is the Empty rule below: "nothing found" is never drawn for a question that has not been fully answered.
The engine behind it is in `search-engine.md`, the parser in `query-parser.md`.

## Mechanism and why

- **One reducer in shared code.** `SearchPhase` (`SearchPhase.kt`) is a sealed state: `Idle`, `Searching(question)`,
  `Results(question, result, complete)`, `Failed(question)`. A `Question` is everything that changes the answer: text,
  kind chip, photo, starred scope, literal flag and the first run's scope. Equal questions get the same answer. Both
  platforms call the same functions (`onAsk`, `onNames`, `onFull`, `onError`, `showsEmpty`, `isStale`, `answerFor`), so the
  rule is written and tested once. This is the standard unidirectional-data-flow shape (one immutable state, one reducer).
- **The Empty rule.** The screen shows "nothing found" only when `phase.showsEmpty(currentQuestion)`: the phase is
  `Results`, for the current question, `complete`, with zero hits. Consequences, each a function of the reducer:
  - a new question starts as `Searching`, never `Empty` (`onAsk`);
  - an empty names answer says nothing: it leaves `Searching` (`onNames`), and a late names answer never replaces a fuller
    answer to the same question;
  - a words-only answer (the meaning lane did not answer, `vectorLane = false`) that will be asked again once the model
    is ready is `complete = false`, so it is not final (`onFull(willRetry = true)`);
  - an answer to older words, another chip, scope or photo is stale (`isStale`) and is never drawn as the answer;
  - a failure belongs to its question; a failure for a replaced question is ignored (`onError`);
  - a retry of the same question keeps its answer on screen (`onAsk`).
- **Timing is shared** (`SearchTiming`): names after 20 ms, the full search after 200 ms of no typing, indexing held off
  1.5 s after a key, words-only answers asked again after 2 s up to 5 times, a progress bar after 300 ms, the mascot's
  searching pose after 400 ms, the quiet no-match mascot after 3 sightings, 60 results.
- **Android.** `SearchViewModel` holds one `StateFlow<SearchUi>` collected with `collectAsStateWithLifecycle`. Two
  `debounce`d `collectLatest` pipelines (names, full) read `keys(wait)`, a `combine` of the settled text, the other
  question fields and a retry counter, so a stale pass is cancelled by the next key. A pass checks `k != keyNow()` before it
  writes, so an old answer never lands. The bar is the Material 3 `SearchBar` with `ExpandedFullScreenSearchBar` (adapted
  from the androidx `SearchBarSamples`, Apache-2.0). Results are a `LazyColumn`; `SearchUi.answer` counts answers to new
  questions so the list starts at its top for a new question and refreshes in place for a better answer to the same one.
  `SearchUi` is a flat state, so Home-only fields can invalidate the pane; `RecompositionTest` pins that counts, Home
  changes and starring one file do not recompose the result tiles, and that a keystroke does not recompose the kind
  chips (`KindChips` takes the filters, not the whole state).
- **iOS.** `SearchModel` (`@Observable`) keeps `phase` and the last `answer`, and derives `screen` (`idle`, `searching`,
  `failed`, `results`, `empty`) from the shared functions, so the view only switches on it. One `Task` per key runs the
  names phase (after the 20 ms floor), then the full phase, and checks `Task.isCancelled` after each await.
- **Search by photo.** The photo is decoded once (Android `photoReader`, iOS `Photo`), becomes part of the `Question`, and
  has no names phase; its relative tail uses the same collapsed disclosure as text search.
- **Re-ask on indexing.** If an answer is empty and files finish being read, the same question is asked again once, after
  a 2 s settle, so a list being read never reshuffles but an empty one fills. Core's `counts()` is watched only while an
  empty answer is on screen, not for every write of a reading run behind other screens or in the background.
- **Hooks to other features.** `setUiBusy` (indexing yields while the person types or scrolls and while a search runs, see
  `indexing-pipeline.md`); `warmSearch` on first focus (opens the model and refills FTS if pending); recent searches and
  launcher shortcuts (`Shortcuts.kt`, `AppShortcuts.swift`, recorded only with "remember searches"); `Prefs.noMatchSeen`
  counts no-match sightings; the mascot moods `SEARCHING`, `NO_MATCH`, `NO_MATCH_QUIET` and `TROUBLE` for the states
  (`docs/motion-api.md`); a row opens in the app's own viewer, not another app.

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/SearchPhase.kt`: `Question`, `SearchPhase`, reducers, `SearchTiming`
- `androidApp/src/main/kotlin/app/retriever/search/SearchViewModel.kt`: `SearchUi`, the two pipelines, retries
- `androidApp/src/main/kotlin/app/retriever/search/SearchScreen.kt`: bar, tab content, suggestions
- `androidApp/src/main/kotlin/app/retriever/search/SearchResults.kt`: chips, understood line, states, rows
- `androidApp/src/main/kotlin/app/retriever/search/Shortcuts.kt`: launcher shortcuts and `LaunchQuery`
- `iosApp/Retriever/Search/SearchModel.swift`: the model; `iosApp/Retriever/Views/SearchView.swift`, `ResultViews.swift`: views

## Invariants

1. **Empty means done.** `showsEmpty` is the only place that decides the empty state, on both platforms.
2. **The answer on screen is for the question asked now,** or it is dimmed and marked stale; it is never labelled with
   words or filters it was not computed for. The understood line comes from the shown answer, not from the field.
3. **Names never overwrite a full answer** to the same question, and an empty names answer never says "nothing".
4. **A words-only answer is not final while a model is coming.** After the retries run out, or when there is no model to
   wait for (text-only phone, model removed, not set up), it is final.
5. **A failure is a state, not a toast,** and belongs to one question.
6. **Clearing the words drops the chip, the starred scope and the literal flag with them;** typing different words
   clears the literal flag and a photo search.
7. **Only the newest key writes.** Every asynchronous step re-checks that its key is still current.
8. **Indexing is held while the person types or scrolls** and released when the screen is cleared (`onCleared`).

## Tests

- `SearchPhaseTest` (core commonTest): `newQuestionIsSearchingNeverEmpty`, `emptyNamesAnswerIsNotShown`,
  `namesWithHitsShowAsIncomplete`, `lateNamesNeverReplaceTheFullAnswer`, `wordsOnlyEmptyWithRetryPendingIsNotEmpty`,
  `retryOfTheSameQuestionKeepsItsAnswer`, `errorForSupersededQuestionIsIgnored`, `failureThenNewTextSearches`,
  `anyQuestionChangeMakesAnOldEmptyAnswerStale`.
- Android, `SearchViewModelTest` (androidApp unit tests): `allAfterAnEmptyChipNeverSaysNothingFound`,
  `starredOffNeverSaysNothingFound`, `wordsAsTypedSayNothingFoundOnlyForThemselves`,
  `aPhotoPickedAfterAnEmptyAnswerNeverSaysNothingFound`, `aFreshQuestionNeverSaysNothingFoundOnTheWay`,
  `aWordsOnlyAnswerAskedAgainNeverSaysNothingFound`, `aWordsOnlyAnswerWhileTheModelOpensIsAskedAgain`,
  `anEmptyAnswerIsAskedAgainOnceFilesAreRead`, `aReplacedSearchThatFailsIsNoFailure`,
  `theNamesAnswerFirstAndTheFullOneRefreshesInPlace`, `clearingAndRetypingTheSameWordsAnswersFromNamesFirst`,
  `noMatchesGoesQuietAfterItWasSeenEnough`, `typingHoldsReadingOffForAMoment`, `aViewModelClearedMidTypingLeavesReadingFree`,
  `theCountsAreWatchedOnlyWhileAnAnswerIsEmpty`.
  Layout: `RecompositionTest.countsAndHomeChangesLeaveTheTilesAlone`, `RecompositionTest.starringOneFileRecomposesOnlyThatFile`,
  `RecompositionTest.typingLeavesTheKindChipsAlone`.
- iOS, `iosApp/RetrieverTests/SearchModelTests.swift`: `testANewQueryIsSearchingNeverEmpty`,
  `testAnEmptyAnswerToAnOlderQuestionIsSearching`, `testARetryOfTheSameQuestionKeepsItsEmptyAnswer`,
  `testAFailureBelongsToItsQuestion`, `testResultsGoStaleWhenTheQuestionChanges`, `testNothingAnsweredIsStaleOnceActive`,
  `testResultsAreResultsAndSlowShowsOnlyWhileSearching`, `testTheDogMovesForTheFirstNoMatches`,
  `testSearchingMyExactWordsLastsUntilTheWordsChange`, `testARetryAsksTheSameQuestion`.
- Run the Android set with the `:androidApp` unit-test task and the iOS set through `scripts/ios-unit-tests.sh RetrieverTests/<Class>`; the
  harness for this wave only compiles them.

## Known limits

- `SearchUi` is one flat state. Home-only fields can invalidate the search pane; splitting it is a
  refactor that `RecompositionTest` would guard, and is not needed while the tiles stay stable.
- The meaning lane is unavailable for a few seconds after launch (the model opens on first focus). The
  states stay honest (`Searching`, then words-only results that are not final), but the person sees names and words
  first and meaning later.
- Past searches reach the launcher shortcuts only when "remember searches" is on.
- Results open in the app's own viewer (`Opener` on Android); the "Matched here" mark in the PDF viewer and per-file
  coverage are separate wave 7 items, not part of this note.

Android Home intercepts Back only while the search is expanded, has text, or has a photo query. The standard BackHandler clears the query and collapses the bar before navigation can leave Home.

The strong prefix stays visible; the tail is a collapsed “Less likely matches (N)” disclosure on both platforms.
Opening it preserves every returned result. A new question resets the disclosure. Even an all-loose name result keeps
its first file visible. This grouping makes no absolute claim that nothing matches well.
