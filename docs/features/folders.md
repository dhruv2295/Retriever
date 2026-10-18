# Folders

## Purpose

Lists the folders Retriever has files from, with a count each, and lets the person stop searching one. An excluded folder
disappears from the index; it can be brought back from Settings. It is a privacy control as much as a tidy one: the
product promises that access and its removal are visible.

## Mechanism and why

- **State.** `FoldersViewModel` exposes one `StateFlow<FoldersUi>`: the folder list (`List<Pair<String, Long>>`),
  `loading`, `loadFailed` and a one-line `error` for the snackbar. `loading` starts true and the empty state ("No folders yet") draws only after it ends, so
  nothing says "empty" before the data is in. A load that failed says that instead of "no folders" (`loadFailed`).
- **Exclude is a pref, and a sync does the rest.** `exclude` adds the folder to `Prefs.excludedFolders` and calls
  `indexNow`; the changed exclude list makes the next sync a full scan, which forgets the folder's files. `refresh` then
  reloads. Settings' `removeExcluded` is the reverse: the next sync does a full scan that brings the folder back.
- **Every coroutine goes through `launchCatching`** and ends in a visible state; a failed exclude is a snackbar, not a
  crash.
- **A folder opens its files** through `BrowseRoute(folder = ...)` (see `browse-status.md`).
- **iOS.** `FoldersView.swift` is a port of the Android screen: folders with counts, the folders picked in the Files app
  (added and removed here), those that cannot be reached listed so they can be removed, and bringing an excluded
  folder back.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/folders/FoldersViewModel.kt`, `FoldersScreen.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Retriever.kt` (`folders()`), `Prefs.kt` (`excludedFolders`)
- `iosApp/Retriever/Views/FoldersView.swift`, `iosApp/Retriever/Core/FolderSource.swift`

## Invariants

1. **"No folders yet" shows only after a successful load.**
2. **Excluding a folder hides it at once and removes its files from search on the next sync.**
3. **A failed load or exclude is a visible message,** never a silent no-op or a crash.
4. **Exclusion is reversible** from Settings.

## Tests

- Android, `FoldersViewModelTest`: `listsFoldersAndExcludingOneHidesItAtOnce`, `anExcludeFailureWithFoldersOnScreenSurfacesTheError`,
  `loadFailureEndsLoadingWithAMessage`.
- Android, `SettingsViewModelTest` covers the Settings side of the exclude list through the prefs tests; iOS:
  `iosApp/RetrieverTests/SettingsModelTests.swift` `testSettingExcludedShowsAtOnceAndPersistsOnce`.

## Known limits

- The list is a snapshot loaded on entry and after an exclude; it does not update while the indexer adds files.
- Folders and their counts come from the index, so a folder shows only once its files have been listed.
