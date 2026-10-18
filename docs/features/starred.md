# Starred

## Purpose

A person marks a file with a star to keep it close. Starred files show as a strip on Home, as their own list (Browse
with `starredOnly`), as a chip that scopes a search, and as a toolbar star in every viewer. Starring from a result row
offers Undo. Stars are the person's, so they survive a re-index of the file.

## Mechanism and why

- **One set in core.** `Retriever.starred` is a `StateFlow<Set<String>>` of uris and `Retriever.setStarred` writes it to
  the Store's `starred` table. Every screen reads that one set, so a star set in a viewer is on the Browse row at once.
- **`Starring` wraps a write that can fail.** `Starring(starred, write, scope)` (Android) launches the write and turns a
  failure into a `failed` flag the screen shows once as a snackbar (`StarFailureEffect`), instead of dropping it.
  `BrowseViewModel` owns one for the list; `StarHolder` (a small `ViewModel`) owns one for the PDF, photo and player
  screens, so a rotation keeps it.
- **Unstar leaves the list at once.** `BrowseViewModel` combines its pages with `starred` and filters in the view model
  when the route is `starredOnly`, so unstarring a row removes it without reloading pages.
- **Undo is a snackbar action,** shown by the helper in `Starring.kt` after a row's star toggles.
- **Starred as a search scope.** The starred scope is part of the search `Question` (see `search-ui.md`); clearing the
  words drops the scope. The filter runs over every kind.
- **OTHER stays out of pictures.** Home's strip and count use the visible-starred queries (`kind != 'OTHER'`); the full
  list (`starredFiles`) still returns OTHER stars so a starred backup is not lost.
- **iOS.** Stars cross views through the SwiftUI environment, not a global; `AppModel.setStarred` updates the local set
  at once and persists through the facade.
- **Mascot.** An empty list uses `NO_MATCH` ("Nothing starred yet"), counted into `NO_MATCH_QUIET` like other empties.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/starred/Starring.kt`: `Starring`, `StarHolder`, `StarFailureEffect`, `StarButton`
- `androidApp/src/main/kotlin/app/retriever/browse/BrowseViewModel.kt`, `BrowseScreen.kt`: the Starred list
- `core/src/commonMain/kotlin/app/retriever/core/Retriever.kt`, `Store.kt`: `starred`, `setStarred`, `starredFiles`
- `iosApp/Retriever/AppModel.swift`, `iosApp/Retriever/Views/BrowseView.swift`

## Invariants

1. **A star write that fails is shown,** never swallowed, and the screen shows it once.
2. **One source of truth:** the core set; no screen keeps its own copy of "is starred".
3. **Unstarring in the Starred list removes the row immediately.**
4. **Starred files of kind OTHER never appear in a Home picture or count,** only in the full list.
5. **Stars are keyed by uri,** so re-reading or editing a file does not lose its star.

## Tests

- Android, `BrowseViewModelTest`: `starredListFiltersInTheViewModel`, `starToggleFailureLandsInState`.
  Home: `HomeViewModelTest.starredFilesAreCounted`, `HomeViewModelTest.noHomeListHoldsAnOtherFile`.
- iOS, `iosApp/RetrieverTests/StarredTests.swift`: `testSetStarredUpdatesStarredAtOnce`,
  `testStarsCrossViewsThroughTheEnvironmentNotAGlobal`, `testStarredFilesPageIsItsOwnQuery`,
  `testStarredIsASearchFilterOverEveryKind`.
- Search: the starring-one-file recomposition guard is `RecompositionTest.starringOneFileRecomposesNoTile`.

## Known limits

- The list joins `starred` to `files` by uri, so a star whose file is no longer in the index does not show.
- Stars are not synced between devices.
