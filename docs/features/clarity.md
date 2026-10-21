# Clarity: Home as a starting place, one status line, plain words

As-built record of the clarity work (branch `claude/clarity`). The design and its reasons are in
`retriever-flow/clarity-proposal.md`; its "Decided on top of this proposal" box overrides the body (previews stay,
"Try:" suggestions stay content-based, the search-by-meaning offer stays at the end of Home marked "Optional", Processing
now keeps the file's name). Supersedes `home.md`'s hero and `Words.kindProgress` subtitle notes and `reading.md`'s
state titles where they differ.

## The app's verb: "process"

Wherever the app describes its own work on files (extracting text, OCR, labels, meaning) it says "process", never
"read", "analyze", "index", "learn" or "scan": "Processing your files · N% done", "N files processed", "N to go",
"Processed in part", "Processing now", "Recently processed", "Couldn't process N files", "Process for 15 minutes",
"When to process", and the Processing screen. The first-day line says Retriever "processes them here on this
{device}: it looks at the words and pictures inside each file. Nothing is sent anywhere." "Read" stays for the
document reader, the OS's access ("iOS lets Retriever see your photos") and loading a list. Code names
(`ReadingProgress`, `readCount`, `WHEN_TO_READ`, routes, tags) are unchanged. The shared labels both screens use
(`DETAILS`, `STARTING`, `PROCESSING_NOW`, `RECENTLY_PROCESSED`, `PROCESSING_YOUR_FILES`, `PROCESS_THESE_KINDS`,
`couldntProcess(n)`) live in `Words`.

## Core API for the apps

Everything below is in core now. The apps compile against it with the smallest call-site changes (listed last); the
UI work is the app agents'.

### The status line (proposal section 3)

| API | What it is | Change |
|---|---|---|
| `ReadingStatus(phase, title, detail, action, percent)` | The one status model: Home's line, Processing, setup's pause reason, the notification | Gains `action` and `percent`; `HomeStatus` and `HomePhase` are gone |
| `ReadingPhase` | Every state, including the new `SETTING_UP`, `NO_ACCESS`, `DOWNLOADING`, `DOWNLOAD_STOPPED`, `TUNING`, `READ_NOW` | No titles on the enum any more; all words are in `ReadingPolicy` |
| `StatusAction` | `CONTINUE`, `ALLOW_ACCESS`, `CHOOSE_WHEN`, `READ_FOR_15`, `STOP`, `RESUME`, `DISMISS`, `TRY_AGAIN`; `.label` is the button's words | New. Map each to a button and a call: `DISMISS` is the [x] (`dismissedReadingCycle`), `STOP` is `endReadNowSession()` (or the download's cancel), `READ_FOR_15` is `readNow()` |
| `ReadingStatus.percent` | The line's figure: the share read (never falling), the meaning pass's share for MEANING, the download's for DOWNLOADING | New. Draw the thin bar only while `active` or downloading; announce the title, not each percent |
| `ReadingStatus.active` | READ_NOW, READING, SAMPLE, MEANING, DEEPENING | Was a HomePhase; use it for the walking mascot |
| `readingStatus(): Flow<ReadingStatus>` | Shared, as before; now also says minutes left of a Process for 15 minutes, "while you charge" when plugged in, and keeps the "N% done" high-water mark in Store meta (`read_mark`) | The once-a-second check runs on `CoreDispatchers.default` (was hard-wired to `Dispatchers.Default`: the flaky `StatusViewModelTest`) |
| `ReadingPolicy.line(reading?, libraryChosen, hasAccess, libraryTotal, words, download)` | Home's line: lost access, then setup's access step, then the download or its setup, else `reading`; null with no files, and no reading line before core's live status arrives (so a first frame never shows a share under the saved mark) | Replaces `homeStatus`. A stopped download gives way while reading runs |
| `DownloadState(percent, stopped, size, settingUp)` | What the platform knows of the model download and its tune | New; pass null when neither is under way |
| `ReadingPolicy.decide(progress, blocker, running, reading, words, readNowMinutes = 0, pluggedIn = false, mark = ReadMark())` | The reading half. Android no longer calls it for a fallback (Home waits for the flow); iOS calls it only for fixtures, since every real `RetrieverSnapshot` carries core's `reading` | Three new parameters with defaults (Swift passes them all) |
| `ReadMark(percent, files)` | The highest share shown and the files counted then | New |
| `ReadingPolicy.stopCopy(stop, words)` | A stop's title, as `core/fixtures/words.tsv` pins it | New words; same 32 fixture rows |

What it decides, beyond the proposal's table:

- **Process for 15 minutes shows from the tap** (`READ_NOW` ahead of the reader starting), so Settings' button gets
  visible feedback through the line; a stop (heat, Battery Saver, low battery) still wins.
- **"N% done" never falls.** When new files lower the share, READING says "Processing 42 new files" until the old figure
  is passed; other states keep the old figure. 100% only when nothing waits.
- **The meaning pass after the download.** While first reads remain it only interleaves (one in eight), so the title
  stays "Processing your files · N% done" and the detail says "Also adding search by meaning · 25%"; once first reads are
  done the line is MEANING, "Adding search by meaning · N%" ("So you can search by what things mean, and by what's in
  your photos."): the pass covers documents too, so it never says "photos" alone. No title flicker.
- **READING's detail differs by platform**: Android "You can close Retriever; it carries on. Search works the whole
  time."; iOS "Search works the whole time. Retriever processes your files while it's open, and sometimes when iOS allows."
- **LIMITED is dismissable** like CAUGHT_UP (the proposal gave it no action, so it would never leave Home).
  CAUGHT_UP's "It processes new files by itself." is left out under "When I start it".
- **DOWNLOADING says "You can keep using Retriever."** without "Only on Wi-Fi": a person who chose mobile data
  would read it as false. The tune reuses `Words.tuneTitle`/`tuneBody`/`tuneFailed`.
- `UpgradePolicy.showOnHome` is unchanged: Try still needs the card while downloading. Home should stop drawing the
  card once its line shows the download.

### Home (proposal section 2)

| API | What it is |
|---|---|
| `HomeFacts.recentlyAdded` | "Recently added": `newMedia + newShots + newDocs` merged newest first (taken, else modified); no new query, so `home()` still never re-reads per file processed |
| `HomeFacts.browse: List<BrowseKind(title, kinds, files)>` | Browse: Photos, Videos, Screenshots, Documents (DOC + PDF), Audio, in that order; no sizes; a kind with no files is left out. Folders is the app's last tile (`HomeFacts.folders`) |

`topFolders` and `FolderCount` are deleted. `newMedia`, `newShots` and `newDocs` stay as `recentlyAdded`'s inputs;
`largeCount`/`largeBytes` feed Settings' Free up space.

### Words (proposal section 4)

Changed in place (same names, so call sites compile): `SEARCH_FIELD` "What are you looking for?", `GO_TO_HOME` "Skip
for now", `SEARCH_BY_NAME` "Skip the try", `WHOLE_PHONE_READY`, `SHARED_FILES_AND_FOLDERS` "Files and folders on
this phone", `SAMPLE_PICKING`, `READ` "Found by what's inside", `CHOOSE_WHEN` "Choose when to process", `READING_STATUS`
"Reading" (the screen title), `MORE_ON_PHONE` "Browse", `NO_MATCHES` "Nothing found", `NO_MATCHES_DETAIL`,
`MORE_WAITING`, `notificationsAsk`, `sampleNote`, `readCount` ("read in part"), `extent`, `coverage`, `kindProgress`
("Found by name", "N to go"), `readingLabel` (File info's value under `FOUND_BY`, e.g. "Name and what's inside").

| New | Sentence |
|---|---|
| `readOf(read, planned, pending = 0)` | "79 files processed" while `pending > 0` (the plan can still shrink), "84 of 86 files processed" after |
| `notReadYet(n)` (was `waitingToRead`) | "3 to go"; Processing only |
| `WELCOME_BODY`, `nothingSent(words)` (was `welcomeBody`) | the welcome's body, and its own larger line "Nothing leaves your phone. No account, nothing sent anywhere." |
| `MORE_FILES` (was `moreFiles(n)`), `CHOOSE_TITLE`, `CHOOSE_PACE`, `READ_15_NOW`, `NOT_NOW_BODY`, `whileChargingBody(words)`, `readNowBody(words)`, `DONE` | Choose when to process |
| `READ_FOR_15`, `OPTIONAL`, `TRY_LABEL` "Try:", `RECENTLY_ADDED`, `SAMPLE_DONE`, `FOUND_BY_NAME`, `FOUND_BY` | Home, the sample, File info |
| `HOW_IT_WORKS`, `WHEN_TO_READ`, `WHAT_IT_CAN_SEE`, `FOLDERS_TO_SKIP`, `KIND_OFF`, `SHOW_ON_THIS_DAY`, `whatItHasRead(words)`, `FREE_UP_SPACE`, `FIND_DUPLICATES`, `NEVER_DELETES`, `deleteFrom(words)`, `deleteConfirm(words)` | Settings and the file menu (both apps hard-code the old ones today) |
| `DeviceWords.ios` | iOS reads only while open and opens Settings for access |

Removed (nothing used them, or their surface is cut): `inLibrary`, `MORE_ON_PHONE_BODY`, `CHOOSE_ACCESS`,
`READING_NOW`, `closestIn`, `searchHint`, `sizeUnavailableFor`, `waitingToRead`, `moreFiles`, `welcomeBody`.
Also removed in the review pass: `WORDS_ONLY` (search shows no "Words only" label; the download hint under few results
stays), `NAME_SEARCH_HINT`, `YOUR_FILES`, `Prefs.nameSearchHintDismissed`/`scheduleOfferDismissed` and their
`Retriever.dismiss*` calls (old saved prefs still load: the JSON ignores unknown keys). Still used: `documentsTitle`,
`addingMeaning` (the card's MEANING stage). `NBSP` is written `'\u00A0'` so an editor can't turn it into a space; the
fixture's two `textOnlyNote` rows pin it. `seeAll(files, pending)` says "See the files" while the plan can still shrink.
`wholeDeviceNote` and `iosAccessNote` no longer end "Nothing leaves your phone" (the welcome's line above says it).
`readingLabel` values start "Its name." where they said "Name only.".

### The apps as built

- **Home** (both): "Retriever", the status line (dog 24 dp / 28 pt, bar only while reading or downloading, its one
  button on its own row; the whole line opens Processing), Starred, Recently added, On this day, Browse, then the
  "Optional" offer as a flat row with a tonal (Android) or bordered (iOS) Download and Not now. Strip tiles are 112 dp
  grown with the font scale on Android; Browse is three a row, two at large text (cell width rounded down to whole dp).
  On iOS, Browse rows put the count under the name at accessibility sizes, and the add-a-folder nudge shows in the
  status section only when no line or note is there.
- **Search bar**: placeholder "What are you looking for?" (wraps to two lines at large text on Android), "Try:" words
  from the person's own files when there is no history.
- **Processing**: the status line with Resume / Process for 15 minutes / Stop, Processing now with the file's name beside
  a small tile, counts under Details. Recently processed fills at its end and holds still while reading runs; on Android the
  row reads a local copy of the held list, because a LazyRow content lambda capturing only the holder was skipped and
  the strip never updated.
- **Settings**: "How Retriever works and your privacy" first; Reading shows the live status line, so Read for 15
  minutes visibly turns it into "Processing for 15 minutes · N min left". When to process under "While charging" uses
  `Words.whileChargingBody` on both apps.
- **iOS tiles and headers**: a strip tile is a `Menu` with a primary action (tap opens, long press shows the file menu):
  `.contextMenu` on tiles inside one List row collapsed into the row's menu, so every tile offered the first tile's
  actions. Home's section headers and Browse counts use primary colour, and Settings uses `.headerProminence(.increased)`:
  the list's default grey nearly failed the contrast audit. When to process is an inline picker (three rows with a tick).
- **Sample**: the count line holds the picking sentence's height throughout and sits on the bar, so the group never
  moves between picking, reading and done.

### Left

- The status line reads the device and clock fresh on every decision (`tick()` inside the read), so Resume and Read for
  15 minutes show at once; there is no timing test for that lag.
- `Retriever.markFor` is read-modify-write on a volatile; a per-collector first read racing the shared read could save
  a lower mark. Each decision still shows the higher of the mark and the current share, so a visible fall needs that
  race and a falling share together.
- Browse's Documents opens PDFs only (or documents when there are none): a file list takes one kind.
- iOS still hard-codes "Starred", "On This Day", "Pause processing" and some Settings footnotes; they belong in core
  `Words`.
- The iPad sidebar keeps its folder list; only its words changed.

### Tests

`StatusLineTest` (every section-3 row, iOS words, read-now from the tap, the share never falling, the meaning pass
under first reads, `line`'s order, no hours or "session"), `WordsTest` (retired words in no shared sentence, the
sample count), `ReadingContractTest.theStatusLineShowsReadNowAtOnceAndKeepsItsShareAcrossLaunches`,
`HomeFactsTest` (Recently added order, Browse), and `core/fixtures/words.tsv` on all three platforms. Apps:
`HomeShotsTest` (every Home state, two Browse columns at the largest text), `ReadingFilesTest.theStatusStripNeverShiftsWhileReading`,
`SetupStatusShotsTest.theSampleIsCentredAndNeverShifts`, iOS `CopyTests.testTheStatusLineCoversEveryHomeState`,
`AccessibilityAuditTests` (each route's line and button on screen) and `ClarityShotsTests` (the renders).
