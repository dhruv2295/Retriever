# Browse and Status

The shared counts, current schedule and UI contract are in [Reading](reading.md). It supersedes older reading-policy and copy descriptions below.


## Purpose

Two plain screens that answer "what do you have?" and "how far along is reading?".

- **Browse** is one list route for every way into a set of files: a kind (Photos, Videos, Documents, Audio, Other), a
  folder, the largest files, the Starred list, or the photos and videos of one day (an On this day card). It sorts and
  pages, and every row opens the app's own viewer.
- **Status** shares `ReadingProgress` and its phase with Home: a read head is searchable, pending tails are partly read, and both display the same whole-library total. Counts are read in one transaction, including trial scope. Browse uses core read extents. Status shows reading progress and what Retriever can see: counts of searchable, waiting and skipped files, pause
  and resume, the access it has (files, media, picked folders) with ways to add or remove, files it could not read with
  a retry, and the On this day switch.

Home reaches both through headers and tiles (`home.md`); Starred is a Browse route (`starred.md`).

## Mechanism and why

- **One `BrowseRoute`, not a screen per entry.** The type-safe route carries `kind`, `folder`, `sort`, `title`,
  `starredOnly`, and an optional `takenFrom` and `takenTo` range. `BrowseViewModel` reads it with `toRoute`, so the
  arguments and the chosen sort survive rotation and process death through `SavedStateHandle`.
- **Paged by hand, on purpose.** `BrowseUi` (`files`, `sort`, `loading`, `error`, `exhausted`) is filled 200 rows at a
  time with `loadMore`. A generation counter drops a late page from a replaced sort, and a failed page sets `error` and
  waits for `retry` instead of failing on every scroll. `loading` is the first page only, so the empty state draws only
  once a page has come back. Pages are de-duplicated by file id because rows can shift between OFFSET pages while the
  indexer works.
- **Coverage on each row, not a global promise.** `coverageNote` gives a list row one honest line: "Found by name only"
  for waiting or name-only files, "Couldn't be read" for skipped ones, and the partial-read note for a file read only
  in part. Search results do the same through their own reason line.
- **Scrolling yields reading.** Both Browse layouts use the same `ScrollingEffect` as Home/search to call
  `setUiBusy` through the ViewModel. Gesture end, lifecycle stop/disposal and ViewModel clearing release the gate;
  `awaitUi` pauses extraction and embedding between native calls.
- **Thumbnails.** `Thumbnails.kt` keeps one `LruCache` of bitmaps sized to a fraction of the heap and a miss cache, with
  decoding on a dispatcher capped at four. The cache key includes the file's `modifiedAt` and `size` and the requested
  size bucket, so an edited file gets a new key instead of a stale picture; PDF pages are queued on a separate render
  dispatcher. It is adapted from the Gallery and platform-samples caches (Apache-2.0). Coil would be worth adding only
  with Telephoto's Coil module, which is not needed.
- **Status is one value.** `StatusViewModel` combines counts, prefs, index state and access into `StatusUi`; `counts` is
  `null` until the first count arrives, so the screen draws neither "0 of 0" nor "caught up" early. Actions go through
  `attempt`, which turns a failure into `StatusUi.error` instead of a crash. The header mascot is `WALKING` while reading
  runs and `RESTING` when paused or waiting, and the first-ever catch-up pops `CAUGHT_UP` through the same once-only
  `Celebration` Home uses.
- **Permissions can change behind the app.** Status re-syncs access on resume (`refreshAccess`), and a photo-picker
  choice that changes no permission still syncs (`photosPicked`).
- **iOS.** `BrowseView.swift` and `StatusView.swift` show the same lists over `AppModel`; `FileListView` sets
  `loading = true` before its first suspension, so a sort change never flashes "Nothing here yet".

## Owner files

- `androidApp/src/main/kotlin/app/retriever/browse/BrowseViewModel.kt`, `BrowseScreen.kt`
- `androidApp/src/main/kotlin/app/retriever/status/StatusViewModel.kt`, `StatusScreen.kt`
- `androidApp/src/main/kotlin/app/retriever/ui/components/FileRow.kt` (`FileRow`, `coverageNote`, `EmptyState`),
  `Thumbnails.kt`, `Common.kt`
- `androidApp/src/main/kotlin/app/retriever/nav/AppNav.kt` (`BrowseRoute`, `StatusRoute`)
- `iosApp/Retriever/Views/BrowseView.swift`, `iosApp/Retriever/Views/StatusView.swift`, `iosApp/Retriever/Thumbnail.swift`

## Invariants

1. **An empty or "done" state shows only after its data has loaded** (`BrowseUi.loading`, `StatusUi.counts`).
2. **Every view-model coroutine that can throw ends in a visible state** (`launchCatching`, `attempt`).
3. **Sort and route survive recreation;** changing sort restarts paging and drops answers for the old sort.
4. **A thumbnail key changes when the file changes.**
5. **A list row says how much of the file is searchable** when it is not all of it.
6. **Unstarring in the Starred list removes the row at once** (`starred.md`).

## Tests

- `BrowseViewModelTest`: `loadsTheRouteFromTheSavedStateOnCreation`, `sortSurvivesRecreationAndRestartsPaging`,
  `failedPageShowsTheErrorAndRetryResumesPaging`, `starredListFiltersInTheViewModel`,
  `aYearCardListsOnlyThePhotosAndVideosOfItsDay`; screenshots `BrowseShotsTest`.
- `StatusViewModelTest`: `pauseAndResumeShowInTheOneUiState`, `theHeaderSharesHomesOnceOnlyCelebration`,
  `firstEmissionHasNoCountsYet`, `failedActionLandsInStateInsteadOfCrashing`, `noCountsDrawsNeitherZeroOfZeroNorCaughtUp`;
  screenshots in `SetupStatusShotsTest`.
- `FileRowTest`: `coverageLinePerState`, `nameHighlightComesFromCoreRanges`. `ThumbnailKeyTest`: `editedFileGetsNewKey`,
  `onlyPdfPagesQueueOnTheRenderDispatcher`, `requestSizesRoundUpToABucket`.

## Known limits

- Browse is a snapshot paged with OFFSET: it does not refresh when the indexer adds or removes files. The live
  alternative is Paging 3 with SQLDelight's `androidx-paging3-extensions`; deferred until a user-visible bug asks for it.
- Recomposition counts are pinned for the search tiles (`RecompositionTest`) but not yet for Browse rows or Status.
- Per-kind progress on Status is counts, not an ETA; a per-kind, per-unit ETA is a separate core item.
