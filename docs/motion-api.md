# Mascot motion: how to place it

The animated golden retriever, for Wave 6. One component per platform, one shared rule table, one set of frames.

| | Android | iOS |
|---|---|---|
| Component | `app.retriever.motion.Mascot(mood, modifier)` | `MascotView(mood:)` |
| Size | `Modifier.width(160.dp)` (welcome: `260.dp`); height follows the art | `.frame(width: 160)` (welcome: `260`) |
| Moods | `app.retriever.core.motion.MascotMood` | the same enum, from `RetrieverCore` |
| Gallery (debug) | `adb shell am start -n app.retriever/.MascotGalleryActivity` (`--es mood WALKING` to open on one) | launch with `-route mascot.gallery` |

The component owns all timing: which clip plays next, loops, holds, pausing, and Reduce Motion. A screen only says
what is true now, by passing a mood. It is decorative (no content description, hidden from VoiceOver); the screen's
text carries the state, as today.

## Moods and where each goes

| Mood | What it does | Where (replace the static art there) |
|---|---|---|
| `WALKING` | Walks with its paper, 1.4 s stride. After 2 min on one screen: walk 10 s, stand 50 s, repeat | Reading or downloading is running: Home first run while the file count is 0 (`HomeScreen.EmptyHome` / `HomeView`), onboarding download, tune and "Reading a few of your files", the Status header while reading runs, Duplicates "Looking for copies" |
| `RESTING` | From walking: sits, dozes for three breaths, then holds the doze. Opened while already paused: shows the doze, no sit | The same slots when reading is paused (charger wait, phone in use, cooling down, Pause, waiting for Wi-Fi) |
| `SEARCHING` | Sniffs along the ground, tail up, twice (1.5 s loop), then holds | Search: meaning results still pending **after 400 ms** and nothing has matched yet. Below any keyword hits, never in front of them; none if there are hits |
| `NO_MATCH` | Lifts its head, looks at you with a happy tongue-out smile, once (2.2 s), then holds | Empty states: "No matches", "Nothing like this yet", "No matches in Photos", no starred match, Nothing starred yet, No folders yet, No duplicates found, empty Browse and Home (`EmptyState` in FileRow.kt, `StateView` in ResultViews.swift). **Not** on errors: those use `TROUBLE`, so a failure never gets a grin |
| `TROUBLE` | Still, sheepish; never animated (`mascot_trouble_still.webp`; every failure state draws it through the mascot component, so there is no plain-image copy) | Every failure state: download failed, no space, tune failed, "Search hit a snag", "Couldn't open this file", "Couldn't play this video", "Couldn't show this photo", "Couldn't look for duplicates" |
| `NO_MATCH_QUIET` | The end pose of `NO_MATCH`, no motion | The same places once the person has seen "No matches" 3 times (keep the count in Prefs) |
| `WELCOME` | One hello: dip, head tilt, three tail wags. 2.6 s, then holds | First-run welcome only, at 260 dp |
| `CAUGHT_UP` | One happy bounce: squint, wag, ears up. 1.3 s, then holds | Home status or Status header, the first time either is on screen after the first full read; then never again (Store meta `celebrated`). Haptic on the pop, 0.2 s after it appears: Android `HapticFeedbackType.Confirm`, iOS `.sensoryFeedback(.success, trigger:)` |

Mood changes are graceful: a loop finishes the loop it is in, a one-shot finishes, a hold switches at once. So
`WALKING` → `RESTING` → `WALKING` plays walk, sit, doze, wake, walk with no jumps; `SEARCHING` → `NO_MATCH` goes from
the sniff into the "huh?". Recompose (or re-`id`) the component to replay a one-shot.

Starred, Home with files and Browse with files keep the static art: nothing is happening there, so nothing moves.

## Examples

Android, the Status header:

```kotlin
Mascot(
    if (index.running) MascotMood.WALKING else MascotMood.RESTING,
    Modifier.align(Alignment.CenterHorizontally).width(160.dp),
)
```

iOS, the same:

```swift
MascotView(mood: snap.running ? .walking : .resting)
    .frame(width: 160)
    .frame(maxWidth: .infinity)
```

`StateView(art:)` takes an asset name today; give it a `MascotMood?` (or a `@ViewBuilder` art slot) so the empty
states can pass `.noMatch`.

## When it does not move

It shows the mood's still pose (same framing as the animation, so nothing jumps) when any of these is true:
Android Remove animations (`ValueAnimator.areAnimatorsEnabled()`), iOS Reduce Motion; off screen (Android
`onVisibilityChanged`, at least 20 % visible for 100 ms; iOS `onDisappear`); the app not resumed / scene not active;
Battery Saver / Low Power Mode; thermal status moderate or worse (iOS `.serious`). It rechecks at every step and every
10 s. Coming back never replays the time it missed: an interrupted step starts over, and the rest continues.

## Files and cost

- Frames: `iosApp/Retriever/Motion/Frames/` (14 files, 2.0 MB): one lossy animated WebP per clip at twice the slot
  width (320 px for 160 dp, 400 px for the 260 dp welcome), plus a still per pose. Drawings per second follow the
  motion: 12 for walking, sniffing, the "huh?", waking and caught-up; 8 for the sit, 10 for the welcome, 5 for the
  doze. The "huh?" ends once the smile lands; the still holds the pose after that. iOS bundles the folder (synced group); Android packages the same folder as assets
  (`sourceSets["main"].assets.srcDir(...)` in androidApp/build.gradle.kts). The release APK is 24 MB today, so this is
  about +2.0 MB.
- Players: Android `AnimatedImageDrawable`, iOS `CGAnimateImageAtURLWithBlock`. Both decode frames off the main thread,
  at 5 to 12 drawings a second. Nothing allocates per frame in our code.
- CPU: about 0.85 ms to decode one 480 px frame on an M-series Mac (measured); INFERRED 1 to 2 ms for a 320 px frame on
  a phone's worker thread, 12 times a second while walking, and nothing while holding. Not measured on a device yet: the WP-A1 Perfetto
  gate (embed time over 40 files, dog on and off) should include it. Over 3 % slower: show `RESTING`'s still while
  reading in the background-heavy first read, or cut the walk to the 10 s cadence from the start.
- Logic: `core/src/commonMain/kotlin/app/retriever/core/motion/MascotDirector.kt`, tested in `MascotDirectorTest`
  (cadence, pause, resume, search, one-shots, stills). Android: `MascotTest` (every file ships, clip lengths match the
  director, the motion-allowed rule).

## Art changed on this branch (merge these with it)

- The app icon is redrawn in the illustrations' style (Android adaptive foreground and monochrome in every
  `mipmap-*`, iOS light, dark and tinted in `AppIcon.appiconset`), so the icon dog and the in-app dog are one character.
- `illus_empty` (Android `drawable-nodpi/illus_empty.webp`, iOS `IllusEmpty.imageset/empty.png`) is the new on-model
  sniffing dog, without the paper. It is also the still behind error states, and iOS `SelfTest` embeds it as its sample
  dog photo: still a dog, so the check holds, but its expected neighbours may shift.

## Sources

The walk, rest, sniff and "huh?" were generated with Sora 2 from the shipped illustrations, then cut into seamless
loops and keyed; the welcome and caught-up are rendered from the mesh rig. Scripts and previews:
`~/AndroidStudioProjects/retriever-motion/v5` (Sora) and `v2/rig` (rig). Approved by Dhruv on 2026-10-17.
