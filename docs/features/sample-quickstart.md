# The sample as a quick start: title, layout, start gap, pace and time

Supersedes in `setup-rethink.md`: the sample table (46-52), `sampleTime` (114), `STARTING` (124) and Fit (128-129);
in `onboarding.md`: "seconds left" (37-38); both now point here. The sections up to Tests are the design; "Core, as
built" and "Apps, as built" record what shipped where it differs. Owner feedback: the title names the
mechanism, not the quick start; it sits left under a centred mascot and pile; a large empty band sits above the
footer; the dog walked over an empty screen for a few seconds before anything showed.

Base: proposal 3 (title only, no description, centred, fixed pile). Grafted: P1's always-rendered stage and reserved
row heights; the findings' measured time line in place of P3's removal. Not taken: P2's lead paragraph (the owner asked
whether a title is enough; it is, and the lead costs ~70 dp of a tight 6.1" budget), P2's height-driven pile (sizes
vary by device and text; centring uses the space with no new rule), P1's two-state title (live-region plumbing on
both platforms for one sentence). PRODUCT.md and DESIGN.md do not exist in this repo; rules come from the brief.

## Copy (core `Words`, once; both apps read it)

| Constant / function | Sentence |
|---|---|
| `SAMPLE_TITLE` | "Try it on your own files" (owner's decision; was "A quick try on your own files" here) |
| `SAMPLE_PICKING` (replaces `STARTING`) | "Picking a few of your files…" |
| `sampleNote(reading, readyToTry, secondsLeft: Int?)` (replaces `sampleTime`) | see below |

`sampleNote`: null when `!reading`. Otherwise a time part, then a try part:
- time part, only when `secondsLeft != null`: `< 60` "Less than a minute left."; rounds to 1 "About a minute left.";
  else "About N minutes left." (N = `round(secondsLeft / 60.0)`).
- try part: `readyToTry` "You can try search now."; else "You can try search once a few files are read."
- No time and ready: "You can try search now. Retriever keeps reading the rest."
Examples: "About 3 minutes left. You can try search now." / "You can try search once a few files are read."

Removed: "Reading a few files to try", "Starting…", "Usually 1–2 minutes. You can try search before this finishes.",
"This is taking longer on your {device}. You can try the files processed so far." `sampleNote` takes no `DeviceWords`.
The iOS See all sheet's `navigationTitle` (SetupView.swift:272) stops using `SAMPLE_TITLE`; it lists every planned
file, so it uses `Words.readOf(read, planned)` ("21 of 124 files processed"), no new constant.

## States (one muted slot: `status.pause` > `sampleNote` > nothing)

| State | Count line | Bar | Muted line | See all | Mascot |
|---|---|---|---|---|---|
| picking (`setup.step == PLAN`) | `SAMPLE_PICKING` | determinate at 0 | `sampleNote` (try-once line) | laid out, hidden | walking |
| planned, 0 read | `readOf(0, planned)` | 0 | same | shown | walking |
| reading, not ready | `readOf(read, planned)` | (planned - pending) / planned | try-once line, time once measured | shown | walking |
| ready, pending > 0 | same | same | "…You can try search now." | shown | walking |
| paused | same (`SAMPLE_PICKING` at plan 0) | frozen | `status.pause` | shown | resting |
| done (pending 0) | same | full | none (slot kept) | shown | resting |

## Layout (both platforms, native layout only)

Order unchanged: mascot 160, title, pile (fixed 224 footprint), count, bar, muted line, See all; footer pinned (Try
search, Go to Home). No description line.
- **Centred on one axis.** Title, count, muted line and See all centred; the bar stays full width.
  Android: `SampleStage`'s own `Column(horizontalAlignment = CenterHorizontally)`, `textAlign = TextAlign.Center` on its
  texts. iOS: `StepPage(alignment:)` defaulting to `.leading` (Welcome unchanged), `.center` for `SampleStep`, plus
  `.multilineTextAlignment(.center)`.
- **Spare height split above and below.** Android `SetupContent`: wrap the scroll in `BoxWithConstraints`, give the
  scrolled `Column` `heightIn(min = maxHeight)` and `Arrangement.spacedBy(20.dp, Alignment.CenterVertically)` for every
  stage, so no arrangement switches between stages (the mascot is a sibling of `AnimatedContent`, SetupScreen.kt:131;
  switching only for SAMPLE would jump it). AnimatedContent's size transform re-centres smoothly. Welcome and Choose are
  INFERRED to fill the viewport at 1.0, so unchanged; the 411 × 891 shot checks it, and if Welcome moves, apply
  `CenterVertically` for SAMPLE only. No `weight` inside the scroll (unbounded height). iOS: `GeometryReader` around
  `StepPage`'s `ScrollView`, VStack `.frame(minHeight: proxy.size.height, alignment: .center)`. INFERRED that the size
  excludes the footer's `safeAreaInset`; check on the simulator.
- **Fixed footprint from the first frame**, so the centred group never shifts: the count line always present; the bar
  always laid out (progress 0 while `planned == 0`, never 0/0; iOS `total: max(planned, 1)`); the muted line reserves two lines (`minLines = 2`; `.lineLimit(2, reservesSpace: true)`); See all
  laid out but hidden before the plan (Android `alpha(0f)` + `clearAndSetSemantics {}`, no click; iOS `.hidden()`).
- **Never blank** (VERIFIED, SetupScreen.kt:199 `taste?.let`): render `SampleStage` from
  `taste ?: TasteProgress(emptyMap(), emptySet(), 0, null, false)` (public constructor; no new symbol). iOS already
  renders from `model.taste`.
- One-line fix: Android `SamplePile` slot `dx`/`dy` scale by `card / CARD_DP` as iOS `Slot(scale:)` does (the pile
  shrinks to the width at large text). 2× text: the group outgrows the viewport, min height has no effect, it scrolls.
- Delete the tickers: Android `SampleTime` + `TIME_TICK_MS`, iOS `TimelineView(.periodic)`, and `sampleStartedAt`
  (SetupFlow.kt:41, 192; AppModel.swift:170; AppModel+Setup.swift:40). `tasteProgress()` re-emits per file.

## Start gap: plan from the scan, then write (VERIFIED; both platforms)

Cause: `planLocked` (Retriever.kt:813) runs a full `sync.sync()` whose first upsert writes all 26k rows (~7 statements
each, one transaction) before planning; JVM bench at 26,248 files: 976-987 ms of a 1.05-1.13 s `planTaste()`, while
`FileRules.planTaste` takes 4-8 ms.
- One call owns it all under one new `syncLock` Mutex, held from the scan to the last write (no gap a second full
  scan could slip into): `LibrarySync.sync(forceFull, first: (suspend (List<SourceFile>) -> Collection<String>)? = null)`.
  On a full scan with `first`: call it with `keep`, upsert only the returned uris (one small transaction), call back,
  then upsert the rest in 1,000-row transactions so the reader's per-file writes interleave; then removals,
  `relinkStars`, `NameIndex.build`, and `TOKEN` / `LAST_FULL` last, as today (a death mid-tail means a full scan again).
- `planLocked` launches that sync on the default scope with `first` = plan the files (kinds filtered), `setTaste` the
  returned rows, complete a `CompletableDeferred`; it awaits only the deferred, then releases `indexLock`. The
  `_starred` / `reconcileRecentFiles` refresh moves to the sync's end (its counts are whole only then). On an
  incremental sync it plans from `store.knownFiles()` as today.
- `runBatch`'s sync (Retriever.kt:545) uses `syncLock.tryLock`: a sync already running covers this batch, so the sample
  never waits on the tail. No timeout, no cache.
- Names: invariant 6 holds as today (names build after the last row in both orders).
- iOS (INFERRED, check `photo identity: looked up X of Y assets (Z through their resources) in N ms` first):
  below iOS 27 `PhotosSource` runs `assetResources(for:)` per unknown asset inside the scan, before `first` can run
  (iOS 27 prefetches the names with the fetch). If that number dominates, defer
  lookups for non-plan assets to the tail (keep the `localIdentifier` fallback name until then).
- Second sync per batch (INFERRED, device check 1 first): under SAMPLE scope skip `runBatch`'s sync unless
  `changedSinceSync`; a deleted planned file already ends as Couldn't read.

## Per-file overhead (INFERRED from VERIFIED code and JVM bench; device numbers before and after)

Cause: one indexed file fires `countChanges` 4 times, three app-scope `counts()` collectors (SetupFlow.kt:88-92,
UpgradeFlow.kt:75) each run `countsNow`, which always runs the unscoped `readingCounts` over all files (Store.kt:197)
inside a write transaction. JVM floor ≈ 4 × (3 × 21 + 12 + 1) ms ≈ 300 ms per file.
1. `countsNow` under SAMPLE scope with `ids`: skip the unscoped `outcomes` query; setup reads `notScheduled` once.
2. One hot `counts()` in `Retriever` (`shareIn(defaultScope, WhileSubscribed())`); `readingStatus` and app collectors
   reuse it.
3. `countChanges().conflate()` before the map (drops stale intermediate states; not a cache).
4. DEVICE FIRST (`dumpsys dbinfo -v`): no write transaction for a read. `beginTransactionReadOnly` is API 35, above
   minSdk, so drop `inTransaction` there or make the counts one statement.
Thread priority and thumbnail decode: untouched until perfetto shows them after 1-3.

## Budget and trim in wall time (VERIFIED; shared core)

Cause: plan and trim price inference only (Retriever.kt:704, 844-847); measured inference for the plan ≈ 88 s stays
under the 135 s trim line while wall time runs ~3× over. Seeds stay as they are; never retune them to one phone.
- `Eta` keeps `wallStartMs` (set at the first `add` of a session as `now - ms`, reset in `changeBook`) and
  `wallRatio` = (now - wallStartMs) / `sessionMs`. Session-only; never written to `speed_by_kind_*` (background
  reads have no UI fan-out). Two gates: trim uses it once every pending kind has a pace (about 9 files in, since the
  plan fronts 2 per kind); the shown time also waits for a quarter of the budget in wall time (`Eta.shownRatio`), so it never shows a warm-up number.
- `trimTaste`: once trim's gate is met, compare pending inference × ratio against budget − wall elapsed, no 1.5× slack; before it,
  today's rule (FileRules.kt:124 fronts 2 per kind). Plan only shrinks; dropped
  files are pending and sit after every read one, so indices hold.

## Measured time line (VERIFIED cause: Words.kt:173-177 fixed copy; `secondsLeft` unused)

`TasteProgress.of` takes `wallRatio`; `secondsLeft` = `book.ms(pending) × wallRatio / 1000`, null before a quarter of
the budget in wall time (`Eta.shownRatio`) or while any pending kind lacks a pace (`readEstimate`'s `measured` rule). Words rounds to whole minutes, so no extra
hysteresis; add some only if a device run shows flicker.

## Tests (each would have caught its problem)

| Problem | Test |
|---|---|
| title, copy | core `SetupTest` (replace 103-106): `sampleNote` null when not reading; try-once, ready, time+ready, <60 s, about a minute; iOS `CopyTests` (112-129) same strings, `STARTING`/`sampleTime` gone |
| blank start | `SetupStatusShotsTest`: `taste = null`, step PLAN → title, placeholders, `SAMPLE_PICKING`, bar at 0 (no NaN) displayed (replace 237-250) |
| left-aligned, empty band | `SetupStatusShotsTest` at 411 × 891, 1.0: title/count/See all centre x = pile centre x (±1 dp); space above mascot ≈ below See all (±8 dp); group bounds equal at picking, reading, done; Welcome bounds unchanged |
| iOS layout | `AccessibilityAuditTests` fixtures sample picking/reading/done + `#Preview`s; reserved note height constant |
| start gap | core host `SampleFilesTest`/new `PlanFromScanTest`: fake source of 5,000 files; rows in store when `setTaste` runs == plan size; after the sync ends all rows, names, token present; a `runBatch` started the moment `indexLock` frees runs no second full scan and does not wait |
| overhead | core host: one indexed file under SAMPLE → ≤ 1 `countsNow` per collector set and 0 unscoped `readingCounts` (counting driver) |
| budget | `TasteScopeTest`: seeds only, injected ratio 3 → trim drops files; ratio 1 → none; read indices unchanged |
| time line | `TasteProgressTest` (replace 25-29): null before `Eta.shownRatio` allows it, null with an unmeasured pending kind, = inference × ratio |

Device checks before marking done: the brief's checks 1 (tap → plan split), 2 (per-file gap), 3 (dbinfo), 7 (iOS
photo lookups); rerun the 26k-file Pixel flow and record tap → first card and per-file gap before and after.

## Core, as built (2026-10-20)

Decisions over this spec, from the owner: the title is "Try it on your own files"; Try's scope line is
`Words.searchingTry(read)`; the Choose screen's "From your selected files and folders" goes; the notification ask drops
its sample sentence once the sample is done (`Words.notificationsAsk`); Processing keeps the mascot walking while
reading runs.

### The recount storm: what it was (VERIFIED) and what replaced it

Run 4's profile put ~70% of the app's CPU in SQLite under `Retriever.counts → Store.countsNow → readingCounts`, plus
Home's aggregates. Causes, each fixed at its root:
- **Every collector counted on its own.** `counts()` and `readingStatus()` were cold flows; the apps held six of them
  during full reading (SetupFlow.kt:131 and :133, UpgradeFlow.kt:75, StatusViewModel.kt:41, HomeViewModel.kt:170,
  IndexWorker.kt:108), on app-scope or view-model scopes that outlive their screens. Now `counts()`, `readingStatus()`,
  `tasteProgress()`, `sampleFiles()` and `home()` are one shared flow each (`Retriever.watched`): computed once per
  change for every collector, conflated (a recompute in flight skips the states it missed and reads the latest), and
  started only while something collects; each new collector gets a fresh first value, so `first()` is never stale.
- **The counts looked inside every chunk of every file.** `readingCounts` ran four correlated `EXISTS` (each reading
  `content_text` to `trim` it) per file. Now `files.reach` keeps what a file's chunks hold, refreshed by the Store write
  that changes them (`store.md` invariant 11), and the library's counts are one covering scan of `files_by_reach`; the
  meaning counts come from the same rows. `readContentVersions` (suggestions) reads `chunks_by_file` only, for the newest 300 read files.
- **The counts held the write lock.** They ran in a SQLDelight transaction, `BEGIN IMMEDIATE` on Android, so the
  reader's writes queued behind them. They now read without one (`store.md` invariant 12).
- **Home re-read file facts on every read.** `home()` ran a whole `countsNow` for `nameOnly`, and the apps re-ran it with
  `kindTotals` and `folderTotals` on every counts change. `home()` is now a flow that re-reads only when files come, go
  or change (or a star does), and carries the totals; `nameOnly` comes from the counts.
- **Two flows for one write.** `countChanges` combined two table flows, so one write could emit twice. It is now one
  query's flow: one emission per write transaction. A read still writes twice (the attempt, then the chunks).

Under SAMPLE, `countsNow` reads the plan's rows (124 rowid lookups) and the plan's tails; the one library-wide part is
the `files_by_reach` scan the whole-library numbers need (by kind, waiting outside the plan), 1.4 ms at 26,248 files.

### Bench (JVM, file-backed SQLite, 26,248 files)

A host test, not kept: building its two libraries takes 17 s. Files: 18,000 photos, 3,000 screenshots, 1,500 videos,
1,500 documents, 600 PDFs, 600 audio, 1,048 other, in 300 folders, one `upsertFiles`. Two libraries: fresh with a
124-file sample half read; half read (1,000 documents of 40 chunks of 200 words, two embedded and the rest tails, and
9,000 photos of one embedded chunk with labels, written through `replaceChunks`). Each read timed through the public
flow's `first()` (before: the `suspend` call, and `store.countsNow` for counts). Medians of five, ms.

| Read | Sample before | Sample after | Half read before | Half read after |
|---|---|---|---|---|
| counts | 18.8 | 2.9 | 61.0 | 3.3 |
| home (before: with a full count; after: with both totals, and only when files change) | 21.0 | 16.0 | 66.4 | 16.0 |
| suggestions | 0.3 | 0.7 | 30.9 | 4.8 |
| tasteProgress | 2.7 | 1.9 | 0.5 | 0.3 |
| sampleFiles | 2.3 | 1.7 | 0.5 | 0.4 |

Before, `readingCounts`' plan was a scan of `files_by_kind_time` with five correlated subqueries per file; a rewrite as a
grouped join was worse (149 ms half read), the maintained column 1.4 ms.

Database time per file processed with every app collector above subscribed (13 flows as the apps held them: SetupFlow's
four, UpgradeFlow's, Status's and IndexWorker's counts and status, Home's counts, status and facts loop), five reads
each written as `IndexEngine` writes them (`recordAttempt`, then `replaceChunks` of a 40-chunk document) 2.1 s apart so
Home's 2 s throttle ran once per read, then until the database was idle for 300 ms. A `SqlDriver` wrapper summed thread
CPU time inside `executeQuery`/`execute` on every thread; reader alone is the same run with no collector. Measured on
8aa964d (before, in a scratch checkout) and on this change (after); the harness was scratch, not kept:

"With collectors" is the whole run's database time, the reader's own included; the collectors' cost is that less the
reader alone (the reader's range is its spread over runs, so the difference is a range too).

| | Reader alone | With collectors, before | With collectors, after | Collectors only, before | Collectors only, after |
|---|---|---|---|---|---|
| Sample | 14.5-19.8 | 486.4 | 33.2 | 467-472 | 13.4-18.7 |
| Half read | 12.5-13.3 | 3,256.2 | 14.0 | 3,243-3,244 | 0.7-1.5 |

Collectors only, the change cuts their database time about 2,200 to 4,600 times half read (3,243 / 1.5 to 3,244 / 0.7)
and about 25 to 35 times in the sample (467 / 18.7 to 472 / 13.4). Totals against totals, which include the reader's
unchanged ~13-20 ms, it is about 230 and 15 times.

Units. The model below uses the read table above: wall time of each flow's `first()` on the JVM, which is the SQL plus
the Kotlin mapping and the flow machinery. The measurement is thread CPU time inside the driver's `executeQuery` and
`execute` only: the SQL and its cursor, nothing after. So the model overstates the measurement by the non-SQL part of
each read, which nothing here measured; the two are compared for order of magnitude only.

- Half read, before: 6 count collectors × 61 ms × 2 to 4 emissions per read = 732 to 1,464 ms, plus Home's refresh
  (31 + 4 + 5 + 66 = 106 ms) and the sample's two flows: 0.8 to 1.6 s modelled against 3.24 s of collector time. The
  rest is INFERRED to be 13 collectors contending for one database (the profile's mutex and `pread` time).
- Half read, after: at most one shared recompute per write, counts 3.3 + suggestions 4.8 + the sample's flows 0.7 + the
  status decision ≈ 9 ms, × 2 writes ≈ 18 ms modelled, against 0.7 to 1.5 ms of collector time. That is less than one
  counts recompute's SQL (the `files_by_reach` scan alone is 1.4 ms), against two expected per read, so the two do not
  reconcile. SPECULATIVE, either: the reader's two writes land back to back in the harness (no extraction between
  them), so conflation folds them into one recompute and the suggestions were not among the 13 flows; or the wrapper
  missed some collector queries. The harness was not kept, so neither is checked; the phone's per-file gap decides.
- Sample, after: counts 2.9 + tasteProgress 1.9 + sampleFiles 1.7 ≈ 6.5 ms a recompute, × 2 writes ≈ 13 ms, against
  13.4 to 18.7 ms: consistent with two recomputes a read (INFERRED), which the half-read figure above is not.

Phone numbers are the orchestrator's next run.

### Start gap, budget, time line

- `planOrCommit()` plans from the scan: `LibrarySync.sync(first = …)` hands the full scan's files over before writing;
  the plan writes its rows (one small transaction) and sets the plan, `planOrCommit` returns, and the sync writes the
  rest 1,000 rows a transaction, then removals, stars, names and the token. `runBatch` uses `syncUnlessRunning()`, so a
  batch never waits for that tail and scans nothing twice. `planTaste()` waits for the tail (tests, tools).
- `Eta.wallRatio`: wall time per inference ms this session, from the gaps between files within a batch (a batch's first
  file counts its own time, so pauses and the gaps between batches never count). `trimTaste` (`tasteDrops`) prices what
  is left at inference × ratio against the budget less the wall time spent, once every pending kind has a pace; before
  that, seeds with 1.5× slack. `TasteProgress.secondsLeft` = inference × `Eta.shownRatio(budget)`: null until a quarter
  of the sample's budget has passed in wall time (`SHOW_AFTER_BUDGET_SHARE`) over at least two files, and while a
  pending kind has no pace. Not a fixed 30 s of inference: at run 4's ratio of about 5, a sample trimmed to its 90 s
  budget holds only ~18-30 s of inference, at or under that gate; the budget share is reached at 22.5 s on any
  phone, and the time shows for the rest.

### Tests

`SampleQuickstartTest` (plan rows first, 5,000 files; a batch's sync skips a running one; five screens share one count
per write; `tasteDrops` at ratio 1 and 3), `TasteProgressTest` (secondsLeft, the wall ratio across batches),
`MigrationTest.version6FilesGainTheirReachFromTheChunksTheyHold`, `SetupTest.sampleNote` and the new sentences,
`ContentSuggestionsTest` (the "garden hose / garden / hose" shape; a second kind of file beats a third note term),
`TextDecodeTest` (UTF-8, its mark, a cut sequence, Latin-1 and Windows-1252, UTF-16 with and without a mark, binary),
`IosExtractorTest.aLatin1TextFileReadsAsItsText`.

## Core API for the apps

Everything below is in core now; the apps compile against it with the smallest call-site changes (listed last).

### Flows, and how to collect them

| API | What it is | Change |
|---|---|---|
| `counts(): Flow<Store.Counts>` | Shared, conflated; a fresh first value per collector | Was one upstream per call |
| `readingStatus(): Flow<ReadingStatus>` | Shared over `counts()`; its once-a-second blocker check runs once | `readingStatus(counts)` is gone |
| `tasteProgress(): Flow<TasteProgress>` | Shared | `secondsLeft` is measured wall time, null until measured |
| `sampleFiles(): Flow<List<SampleFile>>` | Shared | — |
| `home(): Flow<HomeFacts>` | Shared; again when files come, go or change, or a star does; never because a file was read | Was `suspend`. `HomeFacts` gains `kindTotals` and `folderTotals`, loses `nameOnly` (use `counts().progress.nameOnly`); `kindTotals()` and `folderTotals()` are gone |
| `suggestions(fileIds: Set<Long>? = null): Flow<List<String>>` | Per call, conflated; again as files are read. Three, none holding another's words, from different kinds of file while there are any | Was `suspend` |

Collect each only while its screen shows; a flow nobody collects costs nothing.
- **Android:** in a view model, `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)` and
  `collectAsStateWithLifecycle()` in the screen; in a composable, `remember(key) { flow }.collectAsStateWithLifecycle(initial)`
  (as `TryItScreen` now does for suggestions). Today `SetupFlow` (SetupFlow.kt:131-135) and `UpgradeFlow`
  (UpgradeFlow.kt:75) collect on `AppScope` for the process's life, and `HomeViewModel` collects in `viewModelScope`
  while Home sits in the back stack: move them to lifecycle-bound collection. The app-level `shareIn` wrappers are gone
  (Home, Status, IndexWorker): core shares.
- **iOS:** open an observer when its view appears and close it when the view goes or the app leaves the foreground
  (SwiftUI calls no `onDisappear` then). `observeHome(cb)` and `observeSuggestions(fileIds, cb)` (Home's, and Try's
  through `watchSetup`) are new in `RetrieverIos.kt`; `AppModel.watchHome()`/`unwatchHome()` (AppModel+Settings.swift)
  replace `refreshHome` and its 2 s throttle. `observeTasteProgress` and `observeSampleFiles` are opened in `AppModel.init` for the app's life: open them
  while setup or Try shows.

### Words

| API | Sentence |
|---|---|
| `SAMPLE_TITLE` | "Try it on your own files" |
| `SAMPLE_PICKING` (was `STARTING`) | "Picking a few of your files…" |
| `sampleNote(reading, readyToTry, secondsLeft)` (was `sampleTime`) | null unless reading; "About 3 minutes left. You can try search now." / "You can try search once a few files are read." / "You can try search now. Retriever keeps reading the rest." (rules in Copy above) |
| `searchingTry(read)` (was `searchingSample(files)`) | "Searching every file’s name, and inside the {read} files processed so far." `read` is `TasteProgress.read` |
| `notificationsAsk(decided, sample)` | "See progress and pause a reading session." plus " We can also let you know when your sample is ready." while the sample is undecided, has files to read and is not yet ready to try (once ready, its one message is already claimed) |

Removed: `STARTING`, `sampleTime`, `searchingSample`. App-side only (no core words): drop the Choose screen's "From your
selected files and folders" (Android CommitScreen.kt:35; iOS HomeSections.swift:567 says "photos and folders"); pass
`settles = false` to the mascot on Processing while reading runs (Home's status row keeps the cadence); the iOS See
all sheet's title becomes `readOf(read, planned)`.

### Setup, plan, time

- `planOrCommit()` returns once the plan's files are written; the rest of the library is written in the background
  (`Store` counts and Home grow as it lands). The picking state (`SetupStep.PLAN`) is now short.
- `TasteProgress.secondsLeft`: null until measured; feed it to `sampleNote`. Delete the tickers: Android's `SampleTime`
  and `TIME_TICK_MS` are gone, `SetupUi.sampleStartedAt` (SetupFlow.kt:41, 192) is now unused; iOS's `TimelineView` and
  `sampleStartedAt` are gone.

### Files and text

- `StoredFile.contentRead` is set on every stored file (from `files.reach`), not only after `withExtents`.
- Text files decode with `decodeText` on both platforms (BOM, UTF-16, UTF-8, else Windows-1252; binary reads as
  nothing). iOS reads text through `NativeExtractor.readBytes`; `textHead` is gone from the bridge and from
  `iosApp/Retriever/Core/Extractor.swift`. (`textPreview`, which tiles once drew from, is gone: `try-previews.md`.)
- Schema 7 (`6.sqm`): `files.reach`, and `files_by_reach` replaces `files_by_kind_state`. The upgrade fills `reach` from
  the chunks already read (one pass at first launch).

### Call sites already changed (compile only; the UI work is the app agents')

Android: IndexWorker, HomeViewModel (flows for facts and suggestions; the 2 s loop is gone), HomeScreen (`ui.nameOnly`),
StatusViewModel, TryItScreen, SearchResults and SearchViewModel (`sampleRead`), FirstRunStages (`SAMPLE_PICKING`,
`sampleNote`), ReadingPermission (`notificationsAsk`), and the tests that named the old API. iOS: AppModel,
AppModel+Settings (`watchHome`), AppModel+Setup (`TasteSnap.secondsLeft`), SetupView (`SAMPLE_PICKING`, `sampleNote`),
SearchView (`searchingTry`, `RefreshHome` observes), AppFixture, Extractor.swift, CopyTests,
HomeModelTests.

### Left

- Device: tap → first card and per-file database time on the Pixel (the bench is JVM only).
- Device: the time line should show from 22.5 s of wall time into the sample (a quarter of its 90 s budget); check
  it on the Pixel, and that its minutes hold steady once shown.
- Device, expected: a sample still reading when its first 120 s batch ends is past its 90 s budget at the next batch's
  start, so that batch's `trimTaste` drops every pending file and the pile stops growing.
- iOS photo identity lookups inside the scan (spec, INFERRED): not changed; check the `photo identity` log first.
- Skipping `runBatch`'s second sync under SAMPLE (spec, INFERRED): not done; `syncUnlessRunning` covers the overlap.
- Files read as garbled text before this change stay so in search until they change; nothing requeues them.

## Apps, as built (2026-10-20; Android and iOS alike unless named)

- **Sample.** Centred column (title, count, note, See all), bar full width; spare height split above and below
  (Android `BoxWithConstraints` + `heightIn(min = maxHeight)` on every stage; iOS `StepPage` alignment and min height).
  The note slot always holds two lines; the bar and See all are always laid out (See all invisible, disabled and hidden
  from screen readers before the plan). Android renders from an empty `TasteProgress` until core answers, so no blank
  frame. Android pile offsets scale with the card, as iOS. Differs from the States table: the count reads
  `SAMPLE_PICKING` until a plan exists and its first file is read (`planned == 0 || (read == 0 && pending > 0)`), not
  only during `PLAN`. The iOS See all sheet's title is `readOf(read, planned)`.
- **Collected only while shown.** Android `SetupFlow`, `UpgradeFlow` and `HomeViewModel` hold core's library flows with
  `WhileSubscribed(5_000)`; the step runner, setup and access state and the tune trigger still run for the process,
  none of them reading counts. The "ready to try" notification watcher runs only while the sample is undecided. iOS
  `AppModel.watchSetup(stage:)` opens the sample's progress and files on the sample and Try stages only, and Try's
  suggestions from readiness on, so they are there on Try's first frame; `watchHome()`/`unwatchHome()` follow Home
  while the app is in the foreground (`scenePhase`), as Android's lifecycle-bound collection does.
- **Failures.** No read failure reaches an app's collector: a failed read in a shared flow is logged and skipped, and a
  failed `suggestions()` read is logged and answers none, so Home still draws (an uncaught one ended the process on iOS
  and crashed `HomeViewModel`'s scope on Android).
- **Try.** Scope line `searchingTry(TasteProgress.read)`. Chips or the no-suggestions line never move the page: Android's
  suggestions row holds its height from the first frame, and iOS has them before Try opens (`watchSetup`). A chip's search opens no keyboard on
  either platform, so one Back leaves its results; on Android the field refuses focus until it is touched
  (`try-previews.md`; run 5 showed the earlier give-back losing to the keyboard). The scope line is there from the first
  frame on both.
- **Choose.** The "From your selected … folders" line is gone on both.
- **Processing.** The mascot walks while reading runs (`settles = false`; Android's `Mascot` now reads `settles`
  live, it had captured it once). Core keeps `IndexState.currentFile` from one file to the next and clears it only when
  the batch ends, so the headline says "Processing your files" (or the sample's) throughout and "Getting started…" only
  before a batch's first file. Processing now keeps the last file between batches, updates in place, keeps the old picture
  until the new one decodes (150 ms fade, none under Reduce Motion), and holds an empty row before the first file.
- **Notification ask.** Core's `notificationsAsk(decided, sample)` keeps the sample sentence only while the sample is
  undecided, has files to read and is not yet ready to try; the dialog waits for that answer so its text never changes
  while open.
- **Home.** Sections below the hero draw together once facts, suggestions, On this day and the recent searches have
  answered (`home.md`; Android's `SearchUi.recentLoaded`, iOS's `recentLoaded`); the mosaic, screenshots, documents
  and starred cards gain the file long-press menu.

iOS layout test: `AccessibilityAuditTests.testTheSampleGroupSitsCentredAndHoldsItsPlaceInEveryState` holds the
title, count and bar frames equal across picking, start, reading, paused and done, and checks centring where See all is
shown (XCUITest reports a container's frame as the union of its accessible rows, and See all is hidden before the plan;
on the iPhone 18 Pro simulator the group's centre matched the space's centre to 0.1 pt).

Left for the device: the walking mascot on Status (Robolectric runs no playback), Android's one-Back fix inside the
real search dialog, and the timings above.
