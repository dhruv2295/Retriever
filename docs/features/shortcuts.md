# Shortcuts

## Purpose

Starts a search from outside the app. On Android, the three latest searches appear as long-press launcher shortcuts and
`am start --es query` opens a search. On iOS, a "Search Retriever" App Intent is available to Shortcuts, Siri and
Spotlight, with a "Search" phrase. A shortcut opens the search screen with the words already asked. Past searches are
published only when the person has "remember searches" on.

## Mechanism and why

- **Platform shortcut APIs only.** Android uses `ShortcutManagerCompat.setDynamicShortcuts` (AndroidX), and iOS uses App
  Intents with an `AppShortcutsProvider`. No shortcut library is needed; both are the documented route.
- **A launch query, taken once.** The intent carries the words as an extra named `query`. `LaunchQuery` (Android) and
  `LaunchQuery.shared` (iOS) hold one pending string; the search screen reads it, runs the search, and clears it
  (`consume`), so a rotation or a re-composition never repeats it.
- **Ranked and capped.** `RecentShortcuts.push` trims, de-duplicates and keeps at most three, newest first, replacing the
  previous set. `used(query)` tells the launcher a shortcut's search ran so it ranks it up; a search that did not come
  from a shortcut matches none.
- **Privacy follows the pref.** `SearchViewModel` pushes a search to the shortcuts only when "remember searches" is on,
  and republishes the list when recent searches change or are cleared (`settings.md`).
- **iOS.** `SearchIntent` has `openAppWhenRun = true`, sets `LaunchQuery.shared.pending` on the main actor, and the
  search model takes it as a new question.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/search/Shortcuts.kt`: `LaunchQuery`, `RecentShortcuts`
- `androidApp/src/main/kotlin/app/retriever/MainActivity.kt` (passes the intent to `LaunchQuery.from`)
- `androidApp/src/main/kotlin/app/retriever/search/SearchViewModel.kt` (takes and clears the pending query)
- `iosApp/Retriever/AppShortcuts.swift`: `LaunchQuery`, `SearchIntent`, `RetrieverShortcuts`

## Invariants

1. **A launch query runs once** and is cleared after it is taken.
2. **At most three dynamic shortcuts,** newest first, no blanks, no duplicates.
3. **A search is published as a shortcut only while "remember searches" is on.**
4. **On Android, an empty or whitespace-only query extra is ignored** (`LaunchQuery.from`).

## Tests

- Android, `ShortcutsTest` in `androidApp/src/test/kotlin/app/retriever/search/`: `queryExtraReachesLaunchQuery`,
  `pushRecentPublishesAtMostThree`.
- The search side of a launched query is covered by `SearchViewModelTest`.

## Known limits

- The shortcut icon is the launcher icon for every entry.
- iOS has one phrase and no parameterised Siri suggestion for past searches.
- Android caps launcher shortcuts at three; the system may show fewer.
