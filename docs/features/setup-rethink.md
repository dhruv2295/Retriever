# Setup rethink: whole phone by default, the retriever's pile

Replaces setup's Access stage and the sample mosaic, on core's machine (`Setup.kt`) and shared `Words`; `onboarding.md`
is updated in the same change. Owner feedback: glitchy mixed-size mosaic, wide Try tiles, "0 files processed · Up to date"
before reading, and four equal access rows that read as a filtered-down app.

## Root causes

- VERIFIED "Up to date": `SetupFlow.kt:81` and `AppModel.swift:442` show any non-active `ReadingStatus.title` as a
  pause, so `CAUGHT_UP` / `NO_CONTENT` / `LOOKING` leak in before the plan exists.
- INFERRED wide Try tiles: weighted `FlowRow` breaks rows by intrinsic width, and `TextThumbnail` answers unbounded
  intrinsics with its text's width, so rows with documents hold two cells. The fixed-size tile test confirms it.
- VERIFIED mixed sizes: `SampleTile` led with `currentFile` at another size and `RecentFileCell` scaled in.

## Screens and states

**Welcome (access folded in; the ACCESS stage is deleted).** Mascot `WELCOME` 260 dp, title, body, `WelcomeExamples`,
then one decision. `textOnlyNote` stays.
- Android, no all-files access: note `wholeDeviceNote`, primary **Search your whole phone**, a quiet `TextButton`
  **Choose what to include instead**, footer `TextButton` **Search by name**. The primary launches
  `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` through `rememberLauncherForActivityResult(StartActivityForResult())`
  (minSdk 30, so always present; `openAllFilesSettings`' fallbacks become the launcher's fallbacks). On return:
  `access.refresh()`; `allFiles` → `continueFromAccess()`; else note `ALL_FILES_OFF` under the button.
- Android, all-files already granted: note `WHOLE_PHONE_READY` with a check icon, primary **Continue** marks
  `ACCESS_CHOSEN` with no Settings trip.
- iOS: note `iosAccessNote`, primary **Search all your photos** (`PHPhotoLibrary.requestAuthorization(for: .readWrite)`;
  `.authorized` → `ACCESS_CHOSEN`; `.limited` → note `ONLY_SELECTED_PHOTOS`, **Choose more photos**, primary **Continue**;
  `.denied`/`.restricted` → the existing Open Settings button replaces the primary), secondary **Add folders from Files** (`fileImporter`, `[.folder]`, multiple; any folder added →
  `ACCESS_CHOSEN`; failures stay in the existing `folderNote`). Already authorized → **Continue** as on Android. No sheet.
- **Search by name** = `skipFirstRun()` then the same primary ask (or `ACCESS_CHOSEN` at once when access is in place).
  Skipped without access stays on Welcome: skipping never skips access.

**Choose what to include (Android `ModalBottomSheet`).** Title `INCLUDE_TITLE`; today's `AccessRow`s moved as they are:
Photos and videos, Audio (both the media permission launcher), Add a folder (`OpenDocumentTree`, chosen folders as the
supporting line, `folderError` below); `NOTHING_SELECTED` once asked with nothing given; **Continue** enabled on
`access.ready`, marks `ACCESS_CHOSEN` and dismisses. The Files and folders row goes: the welcome primary is that choice.

**The sample: the pile.** One predicate drives mood, time line and freeze, replacing `mood()`'s `readyKinds` test:
`reading = step == PLAN || (status.pause == null && taste.pending > 0)`; not `index.running`, which drops between two
sample batches and would sit the mascot down for the gap. Top to bottom, one non-lazy column, no `FlowRow`/`Grid`:
mascot 160 dp (`WALKING` while reading, else `RESTING`, `settles = false`); title `SAMPLE_TITLE` (heading); the pile
(fixed 224 dp square footprint); the count line and a thin determinate `LinearProgressIndicator` / `ProgressView`;
one muted line (time or stop reason); **See all {planned} files**. Footer pinned: **Try search** (always present,
`enabled = taste.readyToTry`) and **Go to Home**.

| State | Pile | Count line | Muted line | Mascot |
|---|---|---|---|---|
| start (read 0, not stopped) | empty pile | `SAMPLE_PICKING` | `sampleNote(...)` | walking |
| reading | last 12 read | `readOf(read, planned)` | `sampleNote(...)` (the time once measured) | walking |
| ready (`readyToTry`, pending > 0) | same | same | same | walking |
| paused (`status.pause != null`) | frozen | same (or `SAMPLE_PICKING` at 0) | `status.pause` | resting |
| done (pending 0) | frozen | same | none | resting |

Superseded in part by `sample-quickstart.md` (title, picking line, `sampleNote`, the measured time, the centred layout).

Bar value = (planned - pending) / planned, so it fills when done; the label counts read files. They differ by the Name
only and Couldn't read files, which See all names. `planned` = sum of `taste.byKind` values' `.second` (planned count). `currentFile` is not a card.

**Empty pile** (Gallery's): three placeholder cards at slots 0-2, `surfaceContainerHigh`, each one kind icon from the
plan's first three kinds (`taste.byKind.keys`, fallback PHOTO, PDF, DOC), cleared from semantics.

**See all** (tap on the pile or the button): the existing sheet, now every planned file in plan order, each a `FileRow`
(Android, `readNote = note ?: Words.READ`) or a row of `FileTile` 44 pt + name + note (iOS `SampleList`). Opening a read
file uses the viewer as today. This answers "what's done and what isn't".

**Try.** "Files you can try" is a grid of the same `FileTile` at one computed size: Android `BoxWithConstraints` →
cell = (maxWidth - 3 × 8 dp) / 4 (2 columns past font scale 1.3), `FlowRow` of `Modifier.size(cell)` tiles, no weights,
no spacers; iOS `LazyVGrid(gridColumns)` of `FileTile`. Data: `ui.sample.filter { it.read }`.

## Motion

One: a new card settles. `graphicsLayer` (Android) from translationY -48 dp, rotation angle + 8°, alpha 0 to the slot,
`spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)` (about 250 ms). A card animates only if its index is
above the highest index present at first composition (`remember`), so reopening never replays. Reduce Motion
(`!ValueAnimator.areAnimatorsEnabled()` param, `accessibilityReduceMotion`): it appears in place. iOS: a custom
transition (`.modifier(active:identity:)` on rotation, offset, opacity) with `.spring(duration: 0.25)` on
`cards.last?.index`. Nothing else moves; the screen's `AnimatedContent` between stages stays.

## Components and APIs

**Shared tile** (pile, Try grid, Recently processed, See all leading):
- Android `ui/components/ReadingFiles.kt`: `FileTile(file: SourceFile, size: Dp, modifier, onOpen: ((SourceFile) -> Unit)? = null)`
  = `ThumbnailImage(file, size, Modifier.size(size).clip(shapes.medium))`. With `onOpen`: today's `FilePressMenu`,
  combinedClickable and "name, kind, date" label; without: `clearAndSetSemantics {}`. Replaces `RecentFileCell` (arrival
  scale dropped) and deletes `MeasuredThumbnailImage`. Fixed size, so `TextThumbnail` never answers intrinsics.
- iOS `ReadingFiles.swift`: `FileTile(file:)`, `Equatable`, square via `GeometryReader`, `Thumb(points:, bucketed: true)`,
  12 pt corners; replaces `RecentFileCell` (appear animation dropped).

**Pile:** Android `SamplePile(cards: List<SampleFile>, kinds: List<Kind>, reduceMotion, onSeeAll)` in
`FirstRunStages.kt`: `BoxWithConstraints`, footprint = min(224 dp, maxWidth), card = footprint × 0.75 (168 dp at
full size, shrinks with width at large text). `for (c in cards) key(c.index) { FileTile(c.file, card, Modifier.zIndex(c.index)
.graphicsLayer { rotationZ; translationX/Y }) }`. One semantics node: `contentDescription = readOf(...)`,
`Role.Button`, `onClickLabel = seeAll(planned)`; the title keeps `kindRead` per kind (`taste.spoken`) for screen readers. iOS `SamplePile` in `SetupView.swift`: `ZStack` +
`rotationEffect`, `offset`, `zIndex`, `.accessibilityElement(children: .ignore)` with the same label and action.
Thumbnails: the existing cache (`Thumbnails` LRU + `produceState`, PDFs on `renderDispatcher`; iOS `Thumb`'s task).
At most 12 cards compose; `SampleFile` goes in `androidApp/stability.conf`, so only the new card composes.

## Core changes

- `Setup.kt`: delete `SetupStage.ACCESS` and `SetupMark.STARTED` (saved marks parse by name via `entries.firstOrNull`,
  so a stored STARTED reads as NONE: same stage). `stage` = WELCOME while `!libraryChosen`; `showSetup` =
  `stage != DONE && (!skipped || stage == WELCOME)`. `SetupFlow.getStarted` and `AccessStep`/`AccessStage` go.
- `ReadingProgress.kt`: `val ReadingStatus.pause: String? get() = title.takeIf { phase.title == null }` (stop phases
  only). Setup on both apps reads it instead of `!active`.
- `Taste.kt`: `data class SampleFile(val index: Int, val file: SourceFile, val read: Boolean, val note: String?)`
  (`index`: plan position; `read`: content searchable; `note`: `Words.coverage`) and
  `object SamplePile { SHOWN = 12; CARD_DP = 168; FOOTPRINT_DP = 224; angle(i); dx(i); dy(i); fun shown(f) = f.filter { it.read }.takeLast(SHOWN) }`
  with Gallery's 12 angles and offsets verbatim (SmartAlbumScreen.kt 450-466), indexed `i % 12`, under
  `// Adapted from google-ai-edge/gallery SmartAlbumScreen.kt (Apache-2.0)`. Read files keep their index: the plan reads
  in rank order and `trimTaste` drops only pending files, which sit after every read one.
- `Retriever.kt`: `fun sampleFiles(): Flow<List<SampleFile>>` = `combine(store.countChanges(), sampleIds)`, plan order,
  `withExtents` (two batched queries, cheap per emission) + `Words.coverage(…, readInside())`, `flowOn(default)`,
  `distinctUntilChanged`; plus
  `observeSampleFiles` for Swift as `observeTasteProgress` does. Delete `readFiles` (callers: `SetupScreen` See all,
  `TryItScreen`, iOS `sampleFiles()`, `FacadeW5Test`). `SetupUi.sample` holds the list.
- `Words`: `sampleNote(reading, readyToTry, secondsLeft)` (was `sampleTime`; see `sample-quickstart.md`).

## Copy (core `Words`)

New: `SEARCH_WHOLE_PHONE` "Search your whole phone"; `wholeDeviceNote(w)` "Retriever processes the files on this {device}
to find them by what’s inside. Nothing leaves your {device}."; `WHOLE_PHONE_READY` "Retriever can search your whole
phone."; `ALL_FILES_OFF` "Turn on “Allow access to manage all files” to search your whole phone, or choose what to
include."; `CHOOSE_INSTEAD` "Choose what to include instead"; `INCLUDE_TITLE` "Choose what to include";
`SEARCH_ALL_PHOTOS` "Search all your photos"; `ADD_FOLDERS_FROM_FILES` "Add folders from Files"; `iosAccessNote(w)`
"iOS lets Retriever read your photos and the folders you pick in Files. Nothing leaves your {device}.";
`SAMPLE_PICKING` "Picking a few of your files…" (was `STARTING`); `readOf(read, planned)` "12 of 40 files processed"; `READ`
"Read". `seeAll(planned)` is reused.
Delete once grep shows no user: `CHOOSE_ACCESS`, `ACCESS_BODY`, `GET_STARTED`, `CHOOSE_FILE_ACCESS`, `FILES_AND_FOLDERS`,
`FOLDERS`, `SAMPLE_STARTING`, `PREVIEW_UNAVAILABLE` (unused today). Keep `READING_NOW` (Status screen).

**Fit:** 390 × 844 pt (6.1"), safe area out ≈ 763: 16 + mascot ≈ 150 + 16 + title 32 + 12 + pile 224 + 16 + count 24 + bar 12
+ muted 40 + See all 40 + footer 128 ≈ 710. Fits at default text; large text scrolls and the pile shrinks to the width.

## Tests

- Core `SetupTest`: WELCOME for NONE, and for skipped without access (`showSetup` true); ACCESS_CHOSEN + access → SAMPLE;
  skipped + chosen → DONE; step NONE until chosen. Remove the ACCESS-stage and STARTED cases.
- Core `WordsTest`: `pause` null for CAUGHT_UP, NO_CONTENT, LOOKING, SAMPLE_READY, set for PAUSED and WAITING;
  `readOf`; `sampleNote` (`SetupTest.sampleNote`). New `SamplePileTest`: same index → same angle and offset, wraps at 12, `shown`
  keeps the last 12 read in plan order.
- Core host `SampleFilesTest`: plan order and indices; `read` only with content; notes from coverage; a trim keeps
  every read file's index.
- Android: `SetupFlowTest` (Continue with all-files already granted marks ACCESS_CHOSEN with no launch; Search by name;
  sheet Continue). `ReadingFilesTest`: Try grid tiles share one size with a document among photos (the screenshot bug);
  pile bounds equal at 0, 1, 12 and 20 cards; at most 12 card nodes; one pile node with the count and See all action;
  reduce motion draws the new card at its slot. `SetupStatusShotsTest`: start, reading, paused, done at font 1.0 and
  2.0; at 411 × 891 dp and font 1.0 the pile and both footer buttons are displayed with no scroll; "Up to date" absent.
- iOS: `ReadingFilesTests` (pile cards and slots, `FileTile`), `AppModelTests` (authorized and limited → accessChosen;
  folder added → accessChosen), `CopyTests`/`WordsFixtureTests` for added and removed words, `AccessibilityAuditTests`
  fixtures: `.access`, `.photosLimited`, `.photosDenied`, `.folderAdded` (and SetupView's `#Preview`s) rehome to welcome
  states (denied = the Open Settings variant); add sample start/paused/done.
Skipped: Gallery's count pill (the count line says it); an audio card (`readableKinds` keeps audio out of the plan).
