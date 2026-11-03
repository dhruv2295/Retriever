# Shortcuts

## Purpose

Starts a search from outside the app. On Android, the three latest searches appear as long-press launcher shortcuts and
`am start --es query` opens a search. On iOS, a "Search Retriever" App Intent is available to Shortcuts, Siri and
Spotlight, with a "Search" phrase. A shortcut opens the search screen with the words already asked. Past searches are
published only when the person has "remember searches" on.

## Mechanism and why

- **Platform shortcut APIs only.** Android uses `ShortcutManagerCompat.setDynamicShortcuts` (AndroidX), and iOS uses App
  Intents with an `AppShortcutsProvider`. No shortcut library is needed; both are the documented route.
- **A launch query, taken once.** The intent carries the words as an extra named `query`. On Android `MainActivity` turns
  it into a `searchRequest` (taken in `onCreate` only on a fresh launch, and in `onNewIntent`), and `AppNav` opens
  `SearchRoute(query, run = true)` for it like the status request, so the search screen runs it and a rotation never
  repeats it. On iOS `LaunchQuery.shared` holds one pending string that the search model reads and clears.
- **Ranked and capped.** `RecentShortcuts.push` trims, de-duplicates and keeps at most three, newest first, replacing the
  previous set. `used(query)` tells the launcher a shortcut's search ran so it ranks it up (the search screen calls it when it
  starts a run); a search that did not come from a shortcut matches none.
- **Privacy follows the pref.** `SearchViewModel` pushes a search to the shortcuts only when "remember searches" is on,
  and republishes the list when recent searches change or are cleared (`settings.md`).
- **iOS.** `SearchIntent` has `openAppWhenRun = true`, sets `LaunchQuery.shared.pending` on the main actor, and the
  search model takes it as a new question.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/search/RecentShortcuts.kt`: `RecentShortcuts`
- `androidApp/src/main/kotlin/app/retriever/MainActivity.kt` (`searchRequest`) and `nav/AppNav.kt` (opens it)
- `androidApp/src/main/kotlin/app/retriever/search/SearchScreen.kt` (runs the route's query, reports the shortcut used)
- `iosApp/Retriever/AppShortcuts.swift`: `LaunchQuery`, `SearchIntent`, `RetrieverShortcuts`

## Invariants

1. **A launch query runs once:** a fresh launch or a new intent opens it, a recreation does not.
2. **At most three dynamic shortcuts,** newest first, no blanks, no duplicates.
3. **A search is published as a shortcut only while "remember searches" is on.**
4. **On Android, an empty or whitespace-only query extra is ignored** (`MainActivity`).

## Tests

- Android, `ShortcutsTest` in `androidApp/src/test/kotlin/app/retriever/search/`: `usedReportsOnlyAShortcutsOwnSearch`,
  `pushRecentPublishesAtMostThree`.
- `AppLaunchTest` covers the query extra reaching `searchRequest` and a recreation not repeating it.

## Known limits

- The shortcut icon is the launcher icon for every entry.
- iOS has one phrase and no parameterised Siri suggestion for past searches.
- Android caps launcher shortcuts at three; the system may show fewer.
