# First run: a short demo, then one clear choice

> **Superseded in part (2026-10-23), see [Onboarding](onboarding.md).** The choice screen (§3) and "Skip the demo" are
> gone: processing runs on one automatic strategy, and the first search's Continue ends setup with a one-time Home card.
> The welcome's paragraphs are replaced by Hello. The model download is now part of setup (this doc's "Leave out" item
> on it is reversed). The count rule, the pile and the demo's search below still hold. The demo's time left is gone
> (`TasteProgress.secondsLeft`, `Words.demoNote` and the wall clock behind them): no screen of the redesign shows it.
> Nor do `DEMO_PICKING_NOTE`, `demoDone`, `SEARCH_DEMO` and `demoEnded`, which are gone from `Words` too.

**Pick.** Design 1's shape: the demo and its search on one screen, Continue pinned at the bottom, the grid gone, the
number of files fixed when they are picked, one count rule from the demo to Home. **Grafted from Design 2:** a welcome
that says the whole order up front and explains "process" once; "Skip the demo" leads to the choice, not past it; a
line on the choice screen saying how the demo ended; "Counting your files…" until the phone's files are all counted.
**Changed from both:** the choice stays the existing full screen, reworded (not a new sheet), because its stage, Back
and resume already work. Search keeps today's reach and its words change (below).

Tags: VERIFIED = read in code (file:line). INFERRED = not checked on the Pixel.

## What is wrong today, in your order

1. **Nothing says it is a demo.** "A one-minute try" read as a free trial, a test, or the real setup. The heading
   "Try it on your own files" read as an order to the person. Nothing said "the demo is over", and the first hint
   that most of the phone was still to do came on "When should Retriever process your files?". All three personas
   only worked it out there. Reverses your earlier pick of that title (sample-quickstart.md:19).
2. **The count was wrong: "117 of 124 files processed", "All done", full bar.** Two different numbers on one screen.
   - The words count files whose insides became searchable (`TasteProgress.read`, Taste.kt:15).
   - The bar counts files no longer waiting, `(planned − pending) / planned` (FirstRunStages.kt:170). A file found by
     name only, or one that couldn't be opened, finishes without being "read". Those were the 7. VERIFIED.
   - No total until the end: `readOf` and `seeAll` hide "of N" while files wait (Words.kt:355-363), because
     `trimTaste` can shrink the plan mid-way (Retriever.kt:949). That shrink turned 81 into 77. VERIFIED.
   - "Try search" stayed grey at 16: it unlocks at `TRY_AFTER_FILES = 20` (Taste.kt:82); the screen says "a few".
3. **Full bars on Home and the Processing screen.**
   - Home draws `shown.percent`, a saved high mark that never falls (ReadingProgress.kt:147, :324). VERIFIED.
     It sat at 100 while 25,676 files waited. INFERRED: it latched at the moment the demo widened to the whole
     phone, before the rest were queued (nothing waiting reads as 100%). Moot once the mark is deleted.
   - The Processing screen calls `decide` without the saved mark (StatusScreen.kt:200), so it showed the true 2%
     while Home showed full. Two screens, two answers. VERIFIED.
   - Even an honest bar looks full: Material's bar draws its strong-coloured track end to end with a dot at the
     right, and a gap before the filled part. At 0 or 2% it reads as a full line with dots.
4. **Home has no "done" count.** Only "Processing 25,676 new files" or "2% done", never "X of Y". And "new files"
   meant old files.
5. **The count never moved.** `newFiles = contentRead + waiting − mark.files` (ReadingProgress.kt:155). When a file
   goes from waiting to processed, one goes down and the other up by one, so the sum never changes. VERIFIED by
   arithmetic. The feed is live (Home re-reads counts on every change, Retriever.kt:420-480); the number was not.
   "2% done" also looks stuck: on this phone 1% is about 260 files, which takes minutes. "X of Y" moves every file.

Also from the walkthroughs: a private screenshot shown large mid-screen; the next step hidden under a long grid of
private files; "Skip for now" on three screens without saying what it skips; "Done" on a button that starts hours of
work; "it can take a while" with no size.

## The one count rule (core, both apps)

- **Processed = no longer waiting.** A file whose insides are searchable, one found by name only, and one Retriever
  couldn't open all count: Retriever looked at each of them.
- **Total = every file of the kinds Retriever looks at** (`ReadingProgress.libraryTotal`, ReadingProgress.kt:20).
- Every count line reads **"X of Y files processed"**, and every bar draws exactly **X / Y**. They cannot disagree.
- In the demo, Y is fixed when the files are picked. Nothing removes a file from the plan after that.
- On Home, X never falls. Y grows only when new files arrive, so the bar may dip a hair. That is honest and readable,
  and replaces the never-falling percent (clarity.md:46 reversed; it caused problems 3 to 5).
- The demo's files carry over: 30 of 30 in the demo is "30 of 25,793" on Home.
- Screen 18 under this rule: about 1,136 of 26,260 processed (535 + 4 + 597 looked at; 25,125 to go), 4%, not 2%.
  This is a change of definition: today's percent leaves name-only files out of both sides.

## The flow

Welcome → Demo (processing and search, one screen) → When to process the rest → notification ask (unchanged, all
three personas found it clear) → Home.

### 1. Welcome (after access is given)
Title, body, "Nothing leaves your phone" and the tick line stay. The "next" paragraph becomes:

> Next, a short demo. Retriever processes up to 30 of your recent files, so you can watch it work and search them.
> Processing means Retriever looks at the words and pictures inside a file, here on your phone. Nothing is changed,
> copied or sent. It takes a minute or two.
> After the demo, you choose when Retriever processes the rest of your phone. That takes hours, so most people let it
> happen while the phone charges.

Button **Start the demo**. Text button **Skip the demo** (goes to screen 3).
"Up to 30": the welcome runs before the files are picked, so it must not promise an exact number. Why 30
(INFERRED): 124 files fit the old 90 s budget at seed pace and ran 3 to 5 times slower on the Pixel, so 30 is about
one to two minutes. Device check: time 30 files on the Pixel.

### 2. Demo (replaces "Try it on your own files", "Find a file you know" and the grid)
```
  A short demo on 30 of your recent files       ← title describes, never orders
            [dog, 96]   [pile of 3 small cards]
          12 of 30 files processed
  [████████░░░░░░░░░░░░░░░░░░░░]                ← exactly 12/30, quiet track, no dot
  Try: (word) (word) (word)                     ← or the hint below; height kept
  See the 30 files ›
  ───────────────────────── pinned footer ──
  [ 🔍 Search the demo files               ]
  [             Continue                    ]
```

| State | Count line | Muted line (two lines' height kept) | Dog |
|---|---|---|---|
| Picking (under a second) | "Picking your recent files…" | "You can search them while you wait." | walking |
| Processing | "12 of 30 files processed" | none (the time line is gone) | walking |
| Paused | same | the pause reason (today's `status.pause`) | resting |
| Done | "30 of 30 files processed" | "The demo is done. Next: the other 25,763 files on your phone." | resting |

- If the phone has fewer recent files, the title and counts use the real number ("12 of 12").
- **Search works from the first frame.** No grey button, no "once a few files are processed".
- **Words above the results** (search reach unchanged: names already cover the whole phone, Retriever.kt:1080):
  while running "Finds any file on your phone by its name. Looks inside the 12 demo files processed so far."; when
  done "Finds any file on your phone by its name, and looks inside the 30 demo files." Regular weight, not bold.
- **Chips:** today's `suggestions` for the demo files once there are three; until then the same slot says "Type a
  word you remember from a recent photo, screenshot or document."
- Results open the existing full-screen search; one Back returns to the demo.
- **The pile** keeps the last 3 cards at 96 dp in a 128 dp footprint, so no private file fills the screen.
- **See the 30 files** opens the existing list sheet, each file with its note ("Found by name only", …).
- **Continue** is always enabled and is the only way on. Tapped early, the demo stops; its unfinished files join the
  rest, nothing is lost. "Skip for now" goes from every screen.

### 3. When to process the rest (the existing choose screen, reworded)
```
  Demo done: 30 of 30 files processed           ← or "Demo stopped at 12 of 30." / nothing after a skip
  Now, the rest of your phone
  25,763 files aren't processed yet. Until they are, you can
  find them by name, but not by what's inside.
  Processing them takes hours, so Retriever does a little at a
  time. You can use your phone and Retriever the whole time.

  (●) While charging                          Recommended
      When your phone is charging and you're not using it,
      usually overnight. You can pause any time.
  ( ) While charging, and 15 minutes now
      Starts right away and uses some battery, then carries on
      while charging.
  ( ) Not now
      Only names can be found until you choose. You can
      choose any time from Home.

  [            Start processing            ]   ← "Go to Home" when Not now is picked
```
- "takes hours" stays vague on purpose: `ESTIMATE_CALIBRATED = false` (Estimate.kt:188). When it flips, the line
  takes the range from the existing estimate.
- Until the phone's files are all counted, the body says "the rest of your files" instead of a number.
- The button never says "Done": the person is starting a long job, not finishing one.
- Back returns to the demo (done state), or to the welcome after a skip.

### 4. Notification ask: unchanged.

### 5. Home, and the Processing screen
```
  [dog 24] Processing while you charge                 ›
           30 of 25,793 files processed
           [▏░░░░░░░░░░░░░░░░░░░░░░░░░░░░]              ← true start, not a fault
```
| Situation | Title | Line under it |
|---|---|---|
| While charging, plugged in | "Processing while you charge" | "30 of 25,793 files processed" |
| While charging, unplugged | "Waits until you charge" | same |
| 15 minutes now | "Processing for 15 minutes · 14 min left" | same |
| Not now | "Only names can be found for now" | same, plus the **Choose when to process** button |
| Before the count is ready | "Counting your files…" | no bar |

- The Processing screen uses the same title, count and bar. Its Details block becomes the breakdown of X:
  "535 found by what's inside · 597 by name only · 4 in part · 25,125 to go".
- "% done" and "new files" go everywhere. The bar is X / Y on every screen.

## Skip path
- Welcome → **Skip the demo** → screen 3 (no demo line) → notification ask → Home.
- Demo → **Continue** early → screen 3 ("Demo stopped at 12 of 30.") → the unfinished demo files join the rest.
- "Not now" on screen 3 is the only way out without a choice, and Home keeps offering it.

## What changes in code (high level)

**Core (shared words and setup machine)**
- Setup.kt: delete `SetupStage.TRY` and `SetupMark.TRYING` (a saved TRYING reads as SAMPLE); `skipped` routes to
  CHOOSE instead of DONE, and setup shows until a choice is made; `back(CHOOSE)` returns to SAMPLE.
- Taste.kt / Retriever.kt: cap the plan at 30 from `planTaste`'s existing order (two of each kind first) and keep
  the budget for picking it and for the time line; delete `tasteDrops`, `trimTaste`, `TRY_AFTER_FILES`, `readyToTry`.
  A file deleted mid-demo ends as "couldn't process" and still counts as done.
- Words.kt: `readOf(done, total)` always shows "of N"; `seeAll` always shows N; new welcome, demo, search-scope and
  choose sentences above, written once for both apps; retire `SAMPLE_TITLE`, `TRY_TITLE`, `TRY_BODY`,
  `FILES_TO_TRY`, `MORE_FILES`, `CHOOSE_PACE`, the old `searchingTry`, "% done" and "new file(s)".
- ReadingProgress.kt: delete `ReadMark`, `newFiles`, `READ_MARK_KEY` and `percentRead`; the status gives `done` and
  `total` (X, Y) instead of a percent, with "Counting your files…" until the first full count is written.

**Android**
- One demo screen: merge the try screen's search field and chips into the sample stage; delete the grid and the
  upgrade card there (it stays on Home after a search); pinned footer with the search field and `Button`.
- Choose screen: radio rows (`Modifier.selectable`, `Role.RadioButton`), new button label.
- Every determinate bar (FirstRunStages.kt:169, HomeScreen.kt:302, StatusScreen.kt:252):
  `LinearProgressIndicator(progress = { x / y }, trackColor = surfaceContainerHighest, gapSize = 0.dp,
  drawStopIndicator = {})`, so an empty bar looks empty.

**iOS**
- The same merge in `SetupView` / `SearchView`: drop the Try stage; footer in `safeAreaInset(edge: .bottom)` with a
  `.borderedProminent` button; `ProgressView(value: x, total: y)` everywhere; inline `Picker` or checkmark rows for
  the choice.

**Tests (each fails on the bug it guards)**
- `TasteProgressTest`: with one name-only and one failed file, the count and the bar give "30 of 30" and 1.0.
- `SampleQuickstartTest`: at a wall ratio of 5, the total is the same from the first file to the last.
- `WordsTest`: `readOf` always has "of N"; no "new file" or "% done" string remains.
- `StatusLineTest`: Home's line at 30, 31 and 32 done gives three different strings; widening from the demo to the
  whole phone gives a bar of about 0.1%, not 100%; Home and Processing give the same numbers.
- `SetupTest`: skip leads to CHOOSE; Back from CHOOSE leads to SAMPLE; a saved TRYING resumes as SAMPLE.
- Screenshot check at 411 × 891, font scale 1.0: title, pile, count, chips, search field and Continue fit without
  scrolling; at 2.0 the screen scrolls. iOS accessibility audit: the same, plus the choose screen.

## Leave out
- A separate search step, "step 1 of 2" labels, and the grid of every processed file.
- A bottom sheet for the choice (the full screen exists and resumes).
- Narrowing name search to the demo files: names already cover the phone; the words now say so.
- Any hours, nights or "15 minutes does N files" figure until the estimate is calibrated.
- The "Search by meaning" download during setup; it stays on Home after a first search.
- New animation or mascot poses: walking and resting only.

## Also found (separate)
- Search results listed the same document twice (screen 09): missing de-duplication in the results list.

## Core API for the apps

As built in `core/` (branch `claude/demo-flow`). The apps compile against it once their screens are rebuilt; the call
sites to change are listed last.

### Setup (Setup.kt, Retriever)

| API | What it is |
|---|---|
| `SetupStage { WELCOME, SAMPLE, CHOOSE, DONE }` | `TRY` is gone. SAMPLE is the one demo screen (processing and search). |
| `SetupMark { NONE, ACCESS_CHOSEN, CHOOSING }` | `TRYING` is gone; a saved `"TRYING"` resumes as SAMPLE (`SetupMark.saved`, internal). |
| `SetupInputs.stage` | `decided` → DONE; `skipped` or CHOOSING → CHOOSE; else SAMPLE. `showSetup` is `stage != DONE`: setup shows until a choice is made (Skip the demo no longer leads to Home). |
| `SetupPolicy.back(stage, skipped)` | Only from CHOOSE: `ACCESS_CHOSEN` (the demo, done state), or `NONE` after a skip (the welcome). Null elsewhere. |
| `SetupPolicy.afterDemo(nothingLeft)` | Was `afterTry`: the demo's Continue. CHOOSING, or null with nothing beyond the demo (call `commitScope()`). |
| `Retriever.setSetupMark(mark): SetupInputs` | CHOOSING stops the demo where it is (cancels the batch; the demo no longer runs in front, so the count holds for "Demo stopped at 12 of 30."). NONE clears a skip, so Start the demo after Back runs the demo. |
| `Retriever.skipFirstRun()` | Skip the demo: setup goes to CHOOSE with no demo line. A skipped demo doesn't run in front either. |
| `Retriever.hasPendingTaste()`, `claimSampleNotification()` | False once the demo is stopped, skipped or decided. |

### The demo (Taste.kt, FileRules)

| API | What it is |
|---|---|
| `DEMO_FILES = 30` | `FileRules.planTaste` cuts its existing order (two of each kind first) at 30; `byKind`, `seconds`, `everything` describe the cut plan. The 90 s budget still picks the files. |
| `TasteProgress(byKind, readyKinds, read, pending, planned)` + `done` | `planned` is fixed at picking (`tasteIds().size`); `done = planned − pending`: name-only, couldn't-open and deleted files count. `read` stays (files searchable inside). `readyToTry` is gone. Count line `Words.readOf(done, planned)`; bar exactly `done / planned`. |
| `SamplePile` | 3 cards, `CARD_DP` 96, `FOOTPRINT_DP` 128. |

Deleted: `trimTaste`, `tasteDrops`, `TRY_AFTER_FILES`, `readyToTry`. Nothing removes a file from the plan after picking.

### The one count rule (ReadingProgress.kt)

| API | What it is |
|---|---|
| `ReadingProgress.processed` | `contentRead + nameOnly + couldntRead`: every file no longer waiting. `libraryTotal = processed + waiting`. |
| `ReadingProgress.counted` | The first full scan is written (`last_full_scan_ms`); set by core's counts. Defaults true for hand-built values. |
| `ReadingProgress.notNow` | Not now was chosen (scope still SAMPLE, decided). |
| `ReadingProgress.rest` | `waiting`, or null until counted: feed it to `Words.demoDone` and `Words.chooseBody`. |
| `ReadingStatus(phase, title, detail, action, count, done, total)` | `percent` is gone. `count` is the line "30 of 25,793 files processed" (`Words.readOf`); the bar is exactly `done / total` (Long). All three null with no bar: COUNTING, the demo (SAMPLE), DEEPENING, an empty phone. MEANING's are the meaning pass's ("1,938 of 3,142 files have search by meaning"); DOWNLOADING's are `(percent, 100)` with no count. `detail` is the sentence only; the count moved out of it. |
| `ReadingPhase.COUNTING` | New: "Counting your files…", no count, no bar, until `counted`; `ReadingPolicy.line` shows it even before any file is in. |
| `ReadingPhase.NOT_NOW` | New, replaces `SAMPLE_READY` and `NO_CONTENT`: "Only names can be found for now", the whole phone's count, action `CHOOSE_WHEN`. Also the demo done with the choice still waiting. |
| Titles | READING plugged in "Processing while you charge"; CHARGING (and `stopCopy(UNPLUGGED)`, `words.tsv`) "Waits until you charge"; READ_NOW "Processing for 15 minutes · 14 min left"; SAMPLE "Processing the demo files". |

Processing's Details is `Words.breakdown(progress)`: "535 found by what’s inside · 597 by name only · 4 in part · 25,125 to
go" (in part, and "N couldn’t be opened", only when non-zero). Its parts add up to the count's X; "to go" is Y − X.
Home and Processing both draw `readingStatus()` as is, so they cannot disagree.

### Words

| New | Sentence |
|---|---|
| `START_THE_DEMO`, `SKIP_THE_DEMO` | "Start the demo", "Skip the demo" |
| `welcomeNext(words)`, `welcomeAfterDemo(words)` | the welcome's two "next" paragraphs ("up to 30") |
| `demoTitle(planned)` | "A short demo on 30 of your recent files" (real number; "your most recent file"; "your recent files" before picking) |
| `DEMO_PICKING`, `DEMO_PICKING_NOTE` | "Picking your recent files…", "You can search them while you wait." |
| `readOf(done, total)` | every count line, always "of N": "12 of 30 files processed" |
| `demoDone(rest, words)` | "The demo is done. Next: the other 25,763 files on your phone." ("the rest of your files" before counted) |
| `SEARCH_DEMO`, `DEMO_HINT` | "Search the demo files"; the chips' slot until three suggestions |
| `demoSearchScope(done, finished, words)` | "Finds any file on your phone by its name. Looks inside the 12 demo files processed so far." / "…, and looks inside the 30 demo files."; at 0 only "Finds any file on your phone by its name." |
| `seeAll(planned)` | "See the 30 files" |
| `demoEnded(done, planned, skipped)` | "Demo done: 30 of 30 files processed" / "Demo stopped at 12 of 30." / null after a skip |
| `chooseTitle(words)`, `chooseBody(rest)`, `choosePace(words)` | "Now, the rest of your phone"; "25,763 files aren’t processed yet. Until they are, …" ("The rest of your files" before counted; "1 file isn’t"); "Processing them takes hours, …" |
| `RECOMMENDED`, `READ_15_NOW`, `chargingAndNowBody(words)`, `NOT_NOW_BODY`, `whileChargingBody(words)` | the three options as §3 words them (`whileChargingBody` is Settings' too) |
| `START_PROCESSING`, `GO_TO_HOME` | "Start processing"; "Go to Home" when Not now is picked |
| `meaningOf`, `breakdown` | above |

Retired: `filesDone`, `sampleNote`, `SAMPLE_TITLE`, `SAMPLE_PICKING`, `SAMPLE_DONE`, `TRY_SEARCH`, `TRY_TITLE`, `TRY_BODY`,
`TRY_WITHOUT_SUGGESTIONS`, `FILES_TO_TRY`, `searchingTry`, `CHOOSE_TITLE`, `MORE_FILES`, `CHOOSE_PACE`, `NEXT_TRY`,
`START_THE_TRY`, `SEARCH_BY_NAME`, "Skip for now", "Ready to try", "Trying a few of your files", "files done". `DONE`
stays for closing sheets only. `notificationsAsk` now promises "when the demo is done" (only while undecided).

Not changed, worth a look: CAUGHT_UP's "It processes new files by itself." and LOOKING's "Checking for new files…" name
files that really are new; the spec's "new files" was the never-falling count's title, already gone.

### Tests

Core, all failing without the change: `TasteProgressTest` (name-only and failed files give "30 of 30" and 1.0; a deleted
file counts; Continue stops the demo at 11 of 30), `SampleQuickstartTest.theDemosTotalIsFixedFromTheFirstFileToTheLast`
(15 s a file, five times the budget: the total is 30 at every file), `WordsTest` (every new sentence, plurals, "of N"
always, no try/sample/"% done" words), `StatusLineTest` (every row with its count; 30, 31, 32 give three lines; widening
gives 30 of 25,793, a tiny bar; Home and Processing agree; Not now vs a paused demo; Counting), `SetupTest` and
`SetupFlowTest` (skip → CHOOSE; Back → SAMPLE, or the welcome after a skip; saved TRYING → SAMPLE), `FileRulesTest`
(the cut at 30), `SamplePileTest`. `ReadingContractTest.theLineCountsOnceTheFirstFullScanIsWritten` pins Counting →
count without a relaunch; it passes even without the sync's wake-up (`synced` bumping on a full scan), because a batch
re-reads the counts for other reasons, so that race has no test.
Run: `:core:testAndroidHostTest` (621, all pass) and `:core:iosSimulatorArm64Test` for the common classes; ktlint clean.

### App call sites to change

Android (`androidApp/src/main`): `SetupFlow.kt` (107 `readyToTry`, 156 `tryNow`/`TRYING`, 160 `back(stage)` → `back(stage,
skipped)`, 165 `afterTry` → `afterDemo`); `SetupScreen.kt` (91 `back`, 136/210 `SetupStage.TRY`, 172 `readyToTry`, 205
`TasteProgress(...)` placeholder → `TasteProgress(emptyMap(), emptySet(), 0, null, 0, 0)`); `FirstRunStages.kt` (71-80
`NEXT_TRY`/`START_THE_TRY`/`SEARCH_BY_NAME`, 127-189 `SAMPLE_*`/`readOf(read, planned, pending)`/`seeAll(n, pending)`/
`sampleNote`, 257-258 `TRY_SEARCH`/`GO_TO_HOME`); `TryItScreen.kt` (whole screen: `TRY_*`, `searchingTry`);
`CommitScreen.kt` (32-36, 54: `CHOOSE_TITLE`, `MORE_FILES`, `CHOOSE_PACE`, the Done button label); `SearchResults.kt:168`
(`searchingTry` → `demoSearchScope`); `ReadingFiles.kt:141,151` (`FILES_TO_TRY`, the grid); `HomeScreen.kt:285-301` and
`StatusScreen.kt:250-252` (`status.percent` → `done`/`total`; show `status.count`); `Notifications.kt:49` (detail no longer
holds the count); `IndexService.kt:177` (`readyToTry` → e.g. `pending == 0 && planned > 0`). `HomeViewModel.kt:70`
compiles (new fields have defaults). Tests: `SetupFlowTest`, `SetupStatusShotsTest`, `HomeShotsTest`, `SettingsShotsTest`,
`ReadingPermissionTest` (`TRY_AFTER_FILES`), `ReadingFiles`/`StatusViewModel` tests asserting "files done" or `percent`.

iOS (`iosApp/Retriever`): `AppModel.swift:72` (`ReadingProgress(...)` needs `counted:` and `notNow:`);
`AppModel+Setup.swift` (90 `afterTry`, 95 `back(stage:)` → `back(stage:skipped:)`, 103/124 `.try_` and `readyToTry`, 274-296
`TasteSnap.readyToTry`); `SetupView.swift` (18 `.try_`, 97-111 welcome words, 136-182 the demo's words, `.trying`,
`readyToTry`, 290 `readOf`, 346-348 `GO_TO_HOME`); `SearchView.swift` (66 `TRY_TITLE`, 343/470-471 `searchingTry`,
`TRY_BODY`); `HomeView.swift:203,258-259` (`percent` → `done`/`total`, show `count`); `HomeSections.swift:303-307` (choose
words); `ReadingFiles.swift:71,83` (`FILES_TO_TRY`); `DebugRoute.swift:149-150`, `Preview/AppFixture.swift:67,127-128`
(`.trying`, `readyToTry`). Tests: `CopyTests` (`percent`, `.noContent`, "Skip for now", `sampleNote`), `ReadingFilesTests`
(`TasteSnap(readyToTry:)`), `AccessibilityAuditTests`.
