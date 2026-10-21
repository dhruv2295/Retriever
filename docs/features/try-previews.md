# Try: document tiles, title, chips without the keyboard, a steady scope line

This supersedes `onboarding.md:55` (document tiles show the name and first content lines) and `sample-quickstart.md:
356-357` (one Back leaves a chip's results on Android; run 5 shows it does not). What the owner said: in Try's grid,
many files have no preview and show raw text instead, and the title "Try searching inside" doesn't make sense.

Base: proposal 2 (a real picture where the platform makes one, else a card naming the file's type). From proposal 1:
its verified facts and the grid header in `Words`; not its drawn renderer (new `head()` on both extractors, four Canvas
routines, calibration, and still only the text). Not proposal 2's two content lines: the index has no lines (Chunker.kt:
22,28; Index.sq:508), so they would be raw text again. Fixtures: generic names only (Letter.docx, Budget.xlsx, …).

## What is wrong today (VERIFIED in code)

- **Android:** `Thumbnails.render` (Thumbnails.kt:113-135) returns null for DOC and OTHER, so `TextThumbnail` draws the
  name and up to 6 lines of `textPreview`. That text comes after a SQLite read, so the tile changes after its first frame.
- **iOS:** `Thumb.showsText` (Thumbnail.swift:155-157) skips Quick Look for documents, and `isBlank` (109-131) drops
  real pages because they are mostly white. It also draws on the main actor.

## The tile, per file type

| File | Android | iOS |
|---|---|---|
| Photo, screenshot, video, PDF | unchanged (MediaStore, `MediaMetadataRetriever`, `PdfRenderer`) | unchanged (PhotoKit, `videoFrameJpeg`, Quick Look or `PDFPage`) |
| DOC and OTHER: docx, xlsx, pptx, odf, epub, rtf, html, txt, md, csv, json, xml, yaml, code, zip | `loadThumbnail(uri)` first (a provider with `FLAG_SUPPORTS_THUMBNAIL`, such as Drive, gives a real page), else **DocPage** from the file's head (below), else **DocCard** (epub, zip, encrypted, unreadable, unknown, no text) | Quick Look `generateBestRepresentation`, `representationTypes: .all`: a real page or sheet as Files draws it, else the system's icon for the type (INFERRED) |
| Audio | the kind's icon (unchanged) | SF Symbol until Quick Look answers, then Quick Look's picture |
| No access (`load = false`; iOS `folders.resolve` nil) | the kind's icon (unchanged) | SF Symbol (unchanged) |

**DocCard (Android).** `KindFace(file, iconSize)` in Thumbnails.kt: DocCard for DOC and OTHER, else the kind icon;
`ThumbnailImage`, `ReadingThumb` and `FileRow.Thumb` all call it.
- **The page.** Inset 12% of the tile's short side, `surfaceBright` paper with 1 dp of `outlineVariant` and a 4 dp
  corner. `surfaceBright` is lighter than the tile in both themes and needs only the hairline on Home's
  `surfaceContainerLowest` card. The render step below confirms the colour.
- **The badge.** The extension upper-cased (`name.substringAfterLast('.', "")`), centred, `labelMedium` bold, in
  `onSurfaceVariant`, on one line. No name and no content: the tile's label already says "name, kind, date".
- **The fit rule.** A plain `Layout` measures the badge. It shows if it fits the page less 4 dp on each side.
  Otherwise, or with no extension, the kind icon shows. This replaces `MAX_TEXT_LINES`.
- **Motion.** DocCard comes from the name, so it is there on the first frame. A provider picture or DocPage fades in
  over it (150 ms; no fade with animations off), and `ThumbnailImage` drops the card once the fade ends, because the
  page leaves the tile's colour around it. iOS keeps its 0.2 s fade, none under Reduce Motion.

**DocPage (Android, DocPage.kt).** It reads the file's head, never the index, and draws on `Thumbnails.load`'s IO
dispatcher.
- **Embedded thumbnail first.** A file starting `PK\3\4` gets one `ZipInputStream` pass. It recognises entries by name,
  not by extension, so docm, xlsm and dotx are read too. `docProps/thumbnail.{jpeg,jpg,png}` (OOXML) or
  `Thumbnails/thumbnail.png` (ODF), whole and up to 1 MB, ends the pass and is decoded with `BitmapFactory` sampled to
  the bucket. EMF/WMF are not looked for.
- **Else a drawn page,** like Quick Look's page thumbnail: white paper in both themes with a light hairline, dark type.
  docx: `word/document.xml` paragraphs (field codes and deleted text dropped). xlsx: `sheet1.xml` and `sharedStrings.xml`
  as ruled cells, each in its own column. pptx: `slide1.xml` in a 16:9 frame, the first line bold as the title.
  odt/ods/odp: `content.xml` the same ways (repeated cells honoured, speaker notes dropped). csv/tsv: a grid. txt, md,
  rtf, html (body only, tags, style and script stripped): wrapped prose. json, xml, yaml, log and code: monospace
  lines, cut at the page edge. Text is decoded with core's `decodeText`. A head with control characters other than tab
  and newline (a binary named .txt) shows no text.
- **Caps.** 4 MB inflated per zip pass, counted over every entry read or skipped. 1000 entries. 256 KB per text entry.
  16 KB of a text file. 40 rows, 8 columns. A thumbnail stored past the budget (Office often writes it after the media)
  leaves the drawn page. A zip signature followed by garbage, an OLE2 (encrypted) Office file, or a 64 MB zero entry
  ahead of the text each give null in under 6 ms in the tests, and the tile keeps DocCard.
- **Shape.** A square n x n bitmap, transparent around the page. The page is at most 74% of the width, so Home's
  112 x 150 card, which crops the square's sides, shows it whole. Portrait pages are 1:√2. A tile has about 5 + n/64
  lines, so 9 at Try's 256 px bucket and 13 on the pile's 512: fewer, larger lines on a smaller tile. Cache, misses
  and buckets are `Thumbnails`' own. The tile's label stays "name, kind, date", and drawn text is a bitmap with no
  semantics.

## Sizes and caching (no new cache, dispatcher or dependency)

| Surface | Android | iOS |
|---|---|---|
| Sample pile card | 168 dp (`SamplePile.CARD_DP`), scaled with the footprint | 168 pt × `Slot(scale:)` |
| Try grid | (width − 24 dp) / 4 ≈ 84 dp at 411 dp; 2 columns past font scale 1.3 | `LazyVGrid(gridColumns)` cell |
| Processing now, Recently processed, File info, result rows | 88, 56, 56, 48 dp | `ReadingNow` size, 56, 56, 52 pt |
| Result grid, Home document cards, See all list | `ThumbCell` width; 112 × 150 dp; — | 160 pt; 92 pt × 4/3; 44 pt (SetupView.swift:295) |

- **Android.** The provider probe goes through `Thumbnails.load`: buckets 256/384/512/768 px, the bitmap `LruCache`
  (maxMemory/8, by bytes), and `misses` keyed `uri@modified:size@px`. That is one binder call per file and bucket on
  `Dispatchers.IO`, and a miss is cached (INFERRED: `ExternalStorageProvider` misses for these types). DocCard is one
  text measure and is not cached. That is cheaper than today's 6 lines plus a query.
- **iOS.** Quick Look runs on its own queue into the existing 32 MB `NSCache` under `Thumb.requestID`, so Home, Try and
  See all share hits. The `.task(id:)` is cancelled with the cell. The first render of an Office file is INFERRED at
  tens to a few hundred ms; measure it on a device.
- **Built: the page-shaped Android tile** (DocPage above). It is in androidApp and reuses core's public `readCapped`,
  `decodeText`, `stripXml`, `stripHtml` and `stripRtf`. Core's `zipText` is internal, reads whole entries up to 2 MB and
  has no binary path, and `xlsxText` drops empty cells, so neither fits a tile. Measured in `DocPageTest` (Robolectric,
  JVM, generated fixtures): every type reads and draws in 0.1 to 8 ms. The first call took 4 to 43 ms while the classes
  warmed up. A device was not measured.
- **Still open.** No cold-Try Perfetto trace on the Pixel yet. Streaming misses a thumbnail stored after 4 MB of media.
  A seekable `ZipFile` over the file descriptor would fix that if a trace shows it matters. pptx is always drawn 16:9:
  `presentation.xml`'s slide size is not read. Only `sheet1.xml` is read, which is the first sheet unless the sheets
  were reordered.

## Deleted

- **Android:** `TextThumbnail` and its 3 call sites, `loadTextPreview`, `MAX_TEXT_LINES`, `FileRow.Thumb`'s `preview`
  parameter, and `TextThumbnailTest`.
- **iOS:** `showsText`, the `text` state, the `ViewThatFits` block, the `|\(showsText)` task-id suffix, `isBlank` and
  its call, and the `thumb-text` exemption (AccessibilityAuditTests.swift:354).
- **Core:** `Retriever.textPreview`, `Store.textPreview`, the `textPreview` query in Index.sq, and
  `LibraryFeedbackTest.textTilesUseOnlyTheirOwnContentAndInvalidateAfterEdits`.

## Try's words (core `Words`, once)

| Constant | Sentence |
|---|---|
| `TRY_TITLE` | "Find a file you know" |
| `TRY_BODY` | "Type something you remember from it, like a name, a place or an amount, or tap a suggestion." |
| `TRY_WITHOUT_SUGGESTIONS` | "Open one of these files, then type something you remember from it." |
| `FILES_TO_TRY` (new) | "Files you can try" |

- **Why this title.** It names the next step and what to search with; "Try searching inside" left "inside what?" and
  repeated the last screen's "try". SF 34 pt bold: 308 pt (today 316, ~360 free on a 402 pt phone; proposal 1's
  title 479). Alternative: proposal 2's "Now find something" (316 pt).
- **One body at a time on Android, as on iOS (SearchView.swift:484).** The body slot shows `TRY_WITHOUT_SUGGESTIONS`
  when the suggestions are empty, else `TRY_BODY`. The chip box then stays empty, but keeps its height.
- **Removed words:** "Try searching inside"; "Describe something you remember, or choose a suggestion."; "Open a file,
  then try a word you remember from it."; the Android literals "Files you can try" (ReadingFiles.kt:148) and "Ready
  to search" (the grid's label, now `FILES_TO_TRY`); iOS `Copy.filesYouCanTry` (Copy.swift:163).
- **Not changed:** iOS shows the grid only without suggestions (SearchView.swift:486-498).

## A suggestion searches with the keyboard down; one Back leaves

- **Cause.** VERIFIED in M3 1.4.0: `ExpandedFullScreenSearchBar` calls `focusRequester.requestFocus()` in
  `LaunchedEffect(Unit)` (SearchBar.kt:386). The app's one-shot give-back (SearchScreen.kt:299-307) loses on the
  device, and `mInputShown=true` stays. INFERRED: the IME show lands after the hide, once the dialog window has focus.
- **Fix: stop the focus instead of taking it back.** The dialog's field gets `Modifier.focusProperties { canFocus =
  !picked }`. `picked` clears on the first touch of the field (a pointer-down in the initial pass), and that tap focuses
  it as usual. Not "once the bar settles": a probe test showed M3's request landing after the bar had finished opening,
  so the field took focus anyway. The `focused` state, the give-back effect and `inputField`'s `inDialog` parameter are
  deleted. `closeSearch` takes the first Back.
- **iOS (VERIFIED in code):** a chip only calls `search.setText(term)` and never sets `presented`: no keyboard.

## A steady scope line

VERIFIED cause, TryItScreen.kt:100: `search.scope?.let` waits for `vm.setScope`, so the line lands late and pushes the
grid ~26 dp. Fix: always `Muted(Words.searchingTry(ui.taste?.read?.toLong() ?: 0))`; `ui.taste` is there on the first
frame. iOS already does (SearchView.swift:485).

## Tests (each would have caught its problem)

| Problem | Test |
|---|---|
| Android raw-text tiles | `DocCardTest` (Robolectric, `GraphicsMode.NATIVE`): at 84 dp each of docx, xlsx, pptx, csv, txt, json, xml, yaml, html and zip shows its badge, with no other text and no file name; a name with no extension, and every type at 48 dp with font scale 2, shows the icon; badge contrast ≥ 4.5:1 in both themes. `ReadingFilesTest`: Letter.docx among photos in Try's grid holds no text node but its badge. |
| iOS bypass, white pages, icon fallback | `ThumbnailsTests` (simulator, the fixtures above): `Thumbnails.load` is non-nil for each, including a one-line Notes.txt; the representation is `.thumbnail` for Letter.docx and `.icon` for an unknown extension. |
| Clipping at large text | `AccessibilityAuditTests` at AX5 with the `thumb-text` exemption gone; `DocCardTest` at font scale 2. |
| Title and body | Core `SetupTest` and iOS `CopyTests` (new sentences, `FILES_TO_TRY`, `Copy.filesYouCanTry` gone at :155). `CopyTests.testTryTitleFitsALargeTitle`: width ≤ screen − 40 pt. `SetupStatusShotsTest:409-445`: one body at a time, with the grid's top unchanged when the suggestions answer. |
| Chip opens the keyboard | `SearchBackTest.aChipsSearchNeverFocusesTheField`: with the clock paused, steps 60 frames after the chip's search and finds no focused node at any frame, checks `isSoftInputVisible` is false, then one Back leaves. `aTapFocusesTheFieldAfterAChipsSearch`; `oneBackLeavesAChipsResults` now presses Back once. Still to run on the Pixel: tap a chip, `dumpsys input_method` shows `mInputShown=false`, and one `input keyevent BACK` shows `TRY_TITLE`. iOS `testOneBackLeavesASuggestionsResults` asserts `app.keyboards.count == 0` after the tap. |
| Late scope line | `SetupStatusShotsTest`: `TryItContent` with `search.scope = null` shows `searchingTry(read)`, and the grid's top is equal before and after the scope is set. |

## What shipped, and what is still open

- **Renders.** `DocCardTest` writes `androidApp/build/shots/doc_tiles_{pile,grid,list,recent,home}_{light,dark}.png`
  and `doc_tiles_grid_text2.png`; badge contrast ≥ 4.5:1 is asserted in both themes. `surfaceBright` is set in both
  warm schemes (Theme.kt), so the page is not M3's default purple-tinted colour.
- **Mixed tiles at 48 dp, now narrower.** Readable documents show their page at every size. Only the DocCard fallback
  still mixes badges (CSV, TXT, XML, ZIP) and icons (DOCX, XLSX, PPTX, JSON, YAML, HTML) at 48 dp.
- **DocPage renders.** `DocPageTest` writes `androidApp/build/shots/docpage_{docx,docm,xlsx,pptx,odt,ods,odp,thumbnail,
  csv,txt,md,json,xml,yaml,html,code,rtf,fallback}.png`. Each shows the 84 dp grid tile and the 168 dp pile tile, light
  above dark.
- **Open: a gap above the scope line** when `TRY_WITHOUT_SUGGESTIONS` shows, because the longer body holds its height
  (`setup_tryit_empty_light.png`).
- **Not measured:** the keyboard fix on the Pixel (`dumpsys input_method` after a chip, one Back), and the first Quick
  Look render of an Office file on an iPhone. No xlsx or pptx iOS fixture and no iOS `#Preview` through Quick Look.

## Core API for the apps

- **Words (built):** `TRY_TITLE`, `TRY_BODY`, `TRY_WITHOUT_SUGGESTIONS` now hold the sentences above; `FILES_TO_TRY` is
  new. Kotlin `Words.FILES_TO_TRY`, Swift `Words.shared.FILES_TO_TRY`. Pinned in `SetupTest.newSentences`.
- **No new tile data.** DocCard needs only the file's name (the apps take the extension themselves), and iOS uses Quick
  Look. Core has no structured head to offer: the index keeps flattened chunk text (Chunker.kt:22,28), and neither
  extractor keeps spreadsheet rows or slide titles. Android's DocPage therefore reads the head in androidApp with core's
  public text helpers, and core is unchanged.
- **`textPreview` is gone** with its last callers (Android `loadTextPreview`, iOS `Thumb`); see Deleted.
