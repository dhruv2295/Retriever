# Duplicates

## Purpose

Finds files that are byte-for-byte copies of each other and lets the person delete the extras, keeping at least one copy
of every group. It shows how much space each group wastes and offers "keep one" (the oldest copy stays). It is a tidy-up
tool, not a search feature, and it only ever deletes what the person selected.

## Mechanism and why

- **Cheap to expensive, in core.** `DuplicateFinder.find` starts from the Store's `sizeCollisions` (only files of equal
  size above a 64 KB floor can match), then a `quickHash`, then a `fullHash` only for files whose quick hash agrees. Most
  files fall out on size and never get read. The `ContentHasher` interface is the platform seam: it lets the finder run
  in commonMain and be tested with a fake. A hasher failure on one file drops that file, not the scan; cancellation
  still propagates. Groups are sorted by wasted bytes, and each group's files by modified time.
- **One entry per file.** `oneEntryPerFile` (Android) removes a picked-folder row that duplicates a MediaStore row (same
  name and whole-second modified time, or the same folder and name), because deleting either of two views of one file
  would lose the only copy.
- **One state.** `DuplicatesViewModel` exposes `StateFlow<DuplicatesState>` with `scanning` (true until the first scan
  finishes, so "No duplicates found" never shows early), `scannedFiles`, `groups`, `selected`, `deleting`, `failed`,
  `error` and `confirm`. A scan failure ends `scanning` with an error and the `TROUBLE` mascot ("Couldn't look for
  duplicates").
- **The last copy cannot be selected.** Selection guards the group, so a group never ends with zero files; "keep one"
  selects all but the oldest.
- **Deleting goes through the provider each file came from.** `MediaDeleter.delete` removes what the app may delete
  itself (`DocumentsContract.deleteDocument` for picked folders, the resolver for MediaStore). Without all-files access,
  `MediaStore.createDeleteRequest` returns Android's own "allow Retriever to delete?" prompt as `confirm`. After the
  prompt, whatever the person chose, the index is the truth: sync and scan again, so no state has to survive the prompt
  (the process may die behind it). Cancel is no failure.
- **Mascot.** `WALKING` while it looks, `NO_MATCH` when there are none, `TROUBLE` on failure.
- **iOS.** `DuplicatesView.swift` is a port of the Android screen with the same nil-while-looking state; only files
  in added folders are compared (PhotoKit has no public byte size), they are removed, and the oldest copy is kept by
  default.

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/Duplicates.kt`: `DuplicateFinder`, `DuplicateGroup`, `ContentHasher`
- `androidApp/src/main/kotlin/app/retriever/duplicates/DuplicatesViewModel.kt`, `DuplicatesScreen.kt`, `MediaDeleter.kt`
- `iosApp/Retriever/Views/DuplicatesView.swift`

## Invariants

1. **Never select the last copy of a group.**
2. **Two views of one file count as one entry,** so a folder row and a MediaStore row never make a false duplicate.
3. **"No duplicates found" shows only after a completed scan** (`scanning` is false and there is no error).
4. **A failed delete keeps the files and counts them as failed;** a scan failure is a state, not a crash.
5. **After the system prompt the list is rebuilt from the index,** not from state held across the prompt.
6. **A hash failure on one file never aborts the scan.**

## Tests

- Android, `DuplicatesViewModelTest`: `scanKeepsTheOldestCopyAndGuardsTheLastOne`, `scanFailureShowsAnErrorInsteadOfCrashing`,
  `confirmAfterTheSystemPromptRescansFromTheIndex`, `deleteThatCannotRunCountsAsFailedAndKeepsTheFiles`.
- Android, `ReadingRulesTest.oneFileListedByAFolderAndByMediaStoreIsNotACopy` and `aMemoryCardFolderReadsTheSameFromBothSources`.
- Core, `ProductLogicTest.sizeCollisionsGroupsEqualSizesAboveTheMinimum` (the first stage of the finder).

## Known limits

- Delete is permanent (`createDeleteRequest`). `MediaStore.createTrashRequest` (API 30, the app's minSdk) would make a
  mistake recoverable for 30 days; that is a product decision.
- Only exact copies are found; near-duplicates (resized, re-encoded) are not.
- The scan reads every file that shares a size with another, which on a large library is I/O heavy; it runs on demand,
  not in the background.
