# Mascot

## Purpose

The golden retriever that stands for what the app is doing: walking while it reads or downloads, resting when reading is
paused, sniffing while a search waits on meaning, a happy "huh?" on an empty result, sheepish when something failed,
a hello on the welcome, and one bounce when the library is first fully read. It is decorative: the screen's text carries
every state, and the dog has no content description. **Where each mood goes, sizes, and the examples are in
`docs/motion-api.md`; this note records how the code holds it.**

## Mechanism and why

- **A screen only says what is true now.** It passes a `MascotMood`; the component owns timing, loops, holds, pausing and
  Reduce Motion. That keeps motion logic out of every screen and makes placement testable as data.
- **One state machine in core.** `MascotDirector` (commonMain) is a pure function from (mood, current clip, elapsed) to
  the next `MascotStep` (`ONCE`, `LOOP`, `HOLD`), plus `still(mood)` for the reduced-motion pose. Both platforms play what
  it says, so cadence, the two-minute walk/stand cycle, graceful mood changes (a loop finishes the loop it is in) and the
  stills are written and tested once.
- **Platform players, no animation library.** Android `Mascot` plays animated WebP through `AnimatedImageDrawable`
  inside an `ImageView` (`AndroidView`), decoding off the main thread; iOS `MascotView` uses
  `CGAnimateImageAtURLWithBlock`. Frames are lossy WebPs in `iosApp/Retriever/Motion/Frames/`, which Android packages as
  assets from the same folder (one copy, +2 MB).
- **Stills for every case that should not move.** Remove animations or Reduce Motion, off screen, app not resumed,
  Battery Saver or Low Power Mode, and a warm phone (thermal moderate or worse; iOS serious) all show the mood's still,
  re-checked at every step and every 10 s. Coming back never replays missed time.
- **TROUBLE is a still, by design.** Every failure state uses it (`MascotClip.TROUBLE` is a hold, never animated), so a
  failure never gets a grin; `NO_MATCH` is for empty states only.
- **The three hooks.** `SEARCHING` appears when meaning results are pending after 400 ms with nothing matched, below any
  keyword hits and never in front of them (`SearchTiming`). `NO_MATCH_QUIET` replaces `NO_MATCH` after the person has
  seen "No matches" three times (`Prefs.noMatchSeen`, `SearchTiming.NO_MATCH_QUIET_AFTER`). `CAUGHT_UP` plays once ever on
  Home's status or Status's header, tracked in Store meta `celebrated` through `Celebration`, with a haptic 0.2 s after it
  appears (Android `HapticFeedbackType.Confirm`, iOS `sensoryFeedback(.success)`).
- **Shared empty and error slots.** `EmptyState` (`ui/components/Common.kt`) and `StateView` (iOS `ResultViews.swift`)
  take a mood, so a new empty state gets the right dog by naming a mood, not by choosing art.

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/motion/MascotDirector.kt`: `MascotMood`, `MascotClip`, `MascotDirector`
- `androidApp/src/main/kotlin/app/retriever/motion/Mascot.kt`; debug gallery `androidApp/src/debug/kotlin/app/retriever/MascotGalleryActivity.kt`
- `iosApp/Retriever/Motion/MascotView.swift`, `MascotGallery.swift`, and `iosApp/Retriever/Motion/Frames/`
- Placement call sites, under `androidApp/src/main/kotlin/app/retriever/`: `home/HomeScreen.kt`, `status/StatusScreen.kt`,
  `setup/SetupScreen.kt`, `search/SearchResults.kt`, `duplicates/DuplicatesScreen.kt`, `ui/components/Common.kt`; iOS
  under `iosApp/Retriever/Views/`: `HomeSections.swift`, `SetupView.swift`, `SearchView.swift`, `ResultViews.swift`
- `docs/motion-api.md`: the placement guide, moods, files and cost

## Invariants

1. **Screens pass a mood and nothing else;** no screen owns a timer or a clip.
2. **Failures use `TROUBLE`,** never `NO_MATCH`; empty states never use `TROUBLE`.
3. **`SEARCHING` never shows in front of keyword hits.**
4. **It does not move when it should not:** Remove animations, Reduce Motion, off screen, not resumed, power saving, heat.
5. **`CAUGHT_UP` happens once ever.**
6. **Every mood has a still,** so reduced motion always has a pose to show.
7. **Decorative only:** hidden from TalkBack and VoiceOver; the text carries the state.

## Tests

- Core, `MascotDirectorTest`: `walksSteadilyThenSettlesIntoACadenceAfterTwoMinutes`,
  `pausingSitsDozesThreeBreathsThenHoldsStill`, `openingAScreenWhilePausedShowsTheDozeWithoutTheSit`,
  `resumingWakesThenWalks`, `sniffsTwiceThenHoldsAndNoMatchLooksUpOnce`, `quietNoMatchNeverMoves`,
  `welcomeAndCaughtUpPlayOnceThenHold`, `everyMoodHasAStillForReducedMotion`.
- Android, `MascotTest`: `everyClipAndStillShipsAsWebp`, `clipLengthsMatchTheirFrames`,
  `motionStopsForRemoveAnimationsBatterySaverAndHeat`.
- Android, `MascotPlacementTest`: `emptyStatesGrin`, `failuresUseTroubleNeverNoMatch`,
  `workingScreensWalkAndNoErrorKeepsTheOldArt`.

## Known limits

- **Cost is not measured on a device.** The decode time is measured on a Mac and inferred for a phone. The Perfetto gate
  (embed time over 40 files with the dog on and off; over 3 % slower means show `RESTING`'s still while reading, or
  walk on the 10 s cadence from the start) is in the device queue.
