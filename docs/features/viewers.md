# Viewers

## Purpose

Opening a result opens it in the app, at the match: a PDF at the matched page, a photo or screenshot zoomable and
swipeable through the list it was opened from, audio and video at the matched moment, and text, documents, RTF, HTML and
EPUB in a reader of the stored text with the match marked. "Open in another app" and Share stay as a secondary action on every
viewer. Another app is used only for kinds Retriever cannot render (archives, packages, unknown files).

## Mechanism and why

- **One routing function.** `Opener.routeFor(file, locator, fileId, query)` maps `Kind` to a route: `PDF` to `PdfRoute`
  (page from the locator, else 1; `matched` only when the locator named a page, so a PDF opened from a list or found
  by name says nothing about a match), `AUDIO` and `VIDEO` to `PlayerRoute` (ms from the locator, else 0), `DOC` to
  `DocRoute`, `PHOTO` and `SCREENSHOT` to `PhotoRoute`, `OTHER` to `null` (hand to `ACTION_VIEW`). Navigation is
  type-safe `@Serializable` routes in `AppNav.kt`, and `go` acts only from a settled entry so a double tap cannot push
  twice. iOS makes the same decision in `viewing()`: folder PDFs, all videos and library photos in the app, the rest in
  Quick Look.
- **Photos: Telephoto.** `PhotoScreen` is a `HorizontalPager` of `SubSamplingImage` with `Modifier.zoomable`, fed by
  `SubSamplingImageSource.contentUriOrNull`. Compose `transformable` gives raw gesture deltas only (no bounds, fling,
  double tap, or hand-off of a pan to the pager at the image edge); `me.saket.telephoto` (Apache-2.0) provides those, and
  sub-sampling decodes tiles so a 50 MP photo never becomes one huge bitmap. No Coil is needed. The sharpest thumbnail
  already in memory (`Thumbnails.largestCached`) is the source's `preview`, drawn at once in place of a spinner until the
  tiles load; a photo restored without its list has none. A tap toggles the bar (over a dark scrim) and the system
  bars, so the photo fills the screen; a failed or loading page takes the tap too, so the bar can always come back.
  The snackbar keeps the navigation-bar inset while the content runs edge to edge.
- **The tile grows into the photo.** A photo tile and the viewer's picture are one shared element keyed
  `photo:$origin:$uri` (`rememberPhotoKey`), as Jetsnack keys a snack by its origin. Each morphing list names its
  origin (`recent` and `starred` on Home, `search`, `browse`), `Opener.open` puts it in `PhotoRoute.origin`, and the
  viewer keys the opened page with it, so a photo in both Home strips grows from the tile that was tapped and shrinks
  back into it. A page reached by swiping morphs only if that list has a tile for it.
- **The list for swiping.** A route cannot carry hundreds of uris (the saved-state Bundle would hit
  `TransactionTooLarge`). `Opener.open` hands the list to a Koin `ViewerQueue` before navigating; the route carries only
  the opened photo. After process death `ViewerQueue.itemsFor` falls back to that one photo.
- **PDF: `PdfRenderer` kept.** `PdfPageScreen` is adapted from the platform-samples `PdfRenderer` sample: one renderer
  opened for the screen's lifetime on a single-thread view of the app's one PDF dispatcher (`Thumbnails.pdfDispatcher`,
  which the grids' PDF thumbnails share; `PdfRenderer` is not thread-safe), a lazy list of pages
  sized up front, opened at the matched page, with "Matched here" on that page. On Android 15 and later
  `PdfRenderer.Page.searchText` finds the searched words (`highlightTerms`) as each page renders, and they are filled in
  the tonal container on every page that holds them; a scanned PDF has no text layer, so it gets only the ring. Zoom is Telephoto on a single-page view
  (a zoomable over the whole list would take the vertical drags the list needs), and the render scale grows with zoom up
  to a cap. The zoomed page covers the list, takes its touches and hides it from TalkBack, closes with a visible Close
  or Back, and survives rotation. Plain states cover locked and missing files, with "Open in another app".
- **Jetpack PDF is later.** `androidx.pdf` beta (`pdf-compose` `PdfViewer`, `scrollToPage`) renders sharper at zoom, adds
  search and selection, and parses in an isolated process, with minSdk 28 below the app's 30. It is not adopted because
  it is beta with an experimental opt-in, and whether it can mark the matched page the way "Matched here" does is not
  checked. Revisit when it is stable; `PdfPageFrame` stays until then.
- **Video and audio: Media3.** `PlayerViewModel` owns the one `ExoPlayer` of a route, so a rotation keeps its item and
  place (it stops with the activity and prepares again, paused) and the screen only attaches a `PlayerView`; the position is saved on stop and restored, starting at the hit until a
  position was saved. It opens paused: sound starts only at Play. `ON_STOP` pauses and stops, so nothing plays in the
  background and the decoders and buffers go back (Media3 asks for that); the item and position stay, and `ON_START`
  prepares again, still paused. A player error is a `Failed` state.
- **Text and documents: in-app reader.** `DocViewModel` reads the stored chunks (`Retriever.fileText`), so there is no
  second parser and no file read. The state is `DocUi` (`Loading`, `Text`, `NotReadYet`, `Empty`, `Failed`): a pending
  file with no text yet is `NotReadYet`, never the "couldn't read" `Empty`, and a file the index lacks is `Failed`.
  `loadDoc` picks the paragraph from the hit's locator or the query (a search hit carries its id and the words asked),
  on `Dispatchers.Default` because its match scan runs over every paragraph, and the list opens there with the words marked on a tonal background. The text is selectable. `DocUi.Text.matches` lists every paragraph with the words, and the bar "Match 2 of 5" with Previous / Next match (`stepMatch`, wrapping) scrolls between them, stepping from the paragraph on screen after a hand scroll.
- **File actions.** One order everywhere: Add to / Remove from Starred, Share, Open in another app, File info, Rename, a divider, Delete (error colour), each with a `leadingIcon`. On Android `FileAction` is that order (`entries`), `fileActions(uri)` is the availability (a provider Retriever cannot write to gets no Rename or Delete), `FileActionItems` draws the menu and `FilePressMenu` adds the same actions as accessibility custom actions on the row (it hands the content a modifier to apply). Star defaults to the Retriever's set; Browse passes its Undo toggle. Deleting asks "Delete this file?" / "This removes the original from {folder}." (a MediaStore file goes through the system consent sheet instead). iOS: the platform action model supplies the same menu; Photos assets omit Rename.
- **Viewer bar (Android).** `ViewerTopBar` serves all four viewers: Back, filename over an optional subtitle ("{n} of {count}" for photos, "Match on page {n} · Page {n} of {total}" for PDFs, "Match at {time}" for media), Share, Star (only from 600 dp of window width, `starFitsInBar`; the overflow always has it) and the overflow, which is the shared file menu. A PDF, document or media viewer has a bottom `ViewerStepBar`: Previous file, "2 of 5", Next file, from `ViewerQueue.neighbors` (the list the file was opened from, limited to files of the same viewer: PDFs step to PDFs, audio and video to each other, never to a photo); they replace the viewer in the back stack, so Back still returns to the list and the player is released. Photos swipe instead. `Opener.open` queues the list for every kind when it is given one.
- **File info (Android).** `FileInfoSheet` (`files/FileInfo.kt`, `ModalBottomSheet` + `ListItem`): preview, selectable name, "PDF · 2.4 MB", Location (folder; full path and Copy path under "Location details"), Modified, or Taken when the source says (`takenAt`), dimensions, duration or pages, and Reading from `readingLabel`: Read / Partly read / Waiting to read / Name only / Couldn't read · tried n times (the index records no finer reason).
- **Index mutations.** A rename holds `Retriever`'s scan lock across the provider operation and the in-place store update. The file id, chunks, read state and star stay; the name index and recent entries follow the new URI. Confirmed deletion removes index chunks and recent entries immediately. Lists observe the action revision; deleting from a viewer closes it.
- **States.** Every viewer has explicit loading, content, empty and trouble states; failures use `MascotMood.TROUBLE`
  ("Couldn't open this file", "Couldn't play this video", "Couldn't show this photo").
- **iOS.** `PDFKit` `PDFView` at the matched page with highlighted selections (PDF parse through a `@concurrent` function
  returning `sending PDFDocument?`), `AVPlayerViewController` seeked to the matched ms and left paused (video and
  folder audio), a `TabView` of `ZoomableImage` (a `UIScrollView`) for library photos, Quick Look for the rest.
  `viewing()` carries a `Place` (the files of the same viewer in the list it was opened from); PDFs, media and Quick Look
  documents step with "Previous file" / "Next file" buttons in the bottom bar, never a gesture. The top bar is Share,
  Star (regular width only, the same rule as Android's 600 dp; the overflow always has it) and one overflow built from `FileActionButtons`. Labels:
  "Match on page n", "Page n of total", "Match at m:ss" (only when the locator named one) and "n of count" for photos.
  Missing, no-access, locked and unreadable files are `ViewerProblem`s, each with its own button. `TextReader` is a
  sheet over Quick Look ("Text from this file") of the stored paragraphs, selectable, with `WordMatcher` marks and
  "Match n of total" Previous / Next, counting paragraphs that hold the words like the Android reader.
- **File info (iOS).** `FileInfoSheet`: preview, selectable name and "PDF · 2.4 MB", then Location (folder name; path and
  Copy path under "Location details"), Modified, Created when known, dimensions, duration or pages, and Reading from
  `FileInfo.reading`: Read, Partly read, Waiting to read, Name only, Couldn't read.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/viewer/Opener.kt`, `ViewerQueue.kt`
- `androidApp/src/main/kotlin/app/retriever/viewer/ViewerTopBar.kt` (bar, neighbours), `androidApp/src/main/kotlin/app/retriever/files/FileActions.kt`, `FileInfo.kt`
- `androidApp/src/main/kotlin/app/retriever/viewer/PhotoScreen.kt`, `PdfPageScreen.kt`, `PlayerScreen.kt`,
  `PlayerViewModel.kt`, `DocScreen.kt`, `DocViewModel.kt`
- `androidApp/src/main/kotlin/app/retriever/nav/AppNav.kt` (routes and transitions)
- `iosApp/Retriever/Views/Viewer.swift`; `iosApp/Retriever/Views/RootView.swift` (where `openFile` is set)

## Invariants

1. **A tap on a result opens the app's own viewer** for every kind in the routing table; `routeFor` returns `null` only
   for `OTHER`.
2. **A `PdfRenderer` is opened, used and closed only on its one dispatcher;** the close is queued behind a render still
   running.
3. **The viewers never play in the background.**
4. **The matched place is the first thing shown:** page, moment or paragraph.
5. **A failed, locked or missing file is a state with a way out,** never a blank screen: "Open in another app" for a locked ("This PDF needs a password") or unreadable file, "Go back" for a PDF that is "no longer available".
6. **A photo viewer after process death shows the opened photo alone,** not a stale list.
7. **Route arguments stay small;** lists travel through the queue, not the route.

## Tests

- Routing: `ResultPiecesTest` in `androidApp/src/test/kotlin/app/retriever/search/` covers `routeFor` and locator parsing.
- `PhotoScreenTest`: `openingTheThirdOfFiveShowsPageThreeAndASwipeShowsPageFour`, `aMissingPhotoShowsTheTroubleState`,
  `aRestoredScreenWithoutTheListShowsTheOpenedPhotoAlone`, `aPictureInMemoryIsShownWhileThePhotoLoads`, `aTapOnAPageThatFailedTogglesTheBar`.
- `PdfPageScreenTest`: `matchedHereAppearsOnTheMatchedPageOnly`, `aPdfOpenedWithoutASearchMatchMarksNoPage`, `failedStateOffersOpenInAnotherApp`,
  `zoomBucketGrowsWithZoomAndCaps`, `renderScaleNeverPassesTheBitmapCap`.
- `PlayerViewModelTest`: `theSameViewModelKeepsTheSamePlayerAndReleasesItWhenCleared`,
  `startsAtTheHitUntilAPositionWasSaved`, `stoppingSavesThePositionAndTheRestoredViewModelOpensThere`,
  `stoppingReleasesTheMediaAndStartingLoadsItAgain`, `aPlayerErrorMovesTheStateToFailed`.
- `DocScreenTest`: `loadsTheParagraphsAndOpensOnTheOneWithTheQuery`, `textScrollsToTheMatchedParagraphAndHighlightsIt`,
  `aFileWithNoChunksIsEmpty`, `aFileStillWaitingToBeReadSaysSoNotThatItCouldntBeRead`,
  `aSkippedFileWithNoTextIsEmptyAndAMissingOneFailed`, `aFailingReadShowsTheTroubleState`, `loadingShowsNoText`,
  `theMatchesAreFoundOffTheMainThread`.
- `SearchViewModelTest.openingADocumentHitCarriesItsIdAndTheQueryToTheReader`.
- Actions and info: `FileActionsTest` (menu order and availability per source, the star label, `readingLabel` for every state, `infoRows`). `ViewerQueueTest` (`positionLabel`, `neighbors`, `starFitsInBar`); `PdfPageScreenTest.theBarNamesTheMatchedPageAndWhereTheReaderIs`; `DocScreenTest.everyParagraphWithTheWordsIsAMatchAndStepsWrapRound`, `theMatchBarStepsToTheNextMatch`; `PlayerViewModelTest.opensPausedSoNothingPlaysUntilPlayIsPressed`.
- iOS: `iosApp/RetrieverUITests/AccessibilityAuditTests.swift` audits fixture routes only.

## Known limits

- Android: a revoked folder grant throws `SecurityException` from `openFileDescriptor` ("Access to this folder has changed" / Manage access) and a password-protected PDF from `PdfRenderer` ("needs a password"); a search opens a viewer without the list it came from (Search passes no siblings), so Previous / Next file appear only for files opened from Browse or Status.
- The reader shows the text the index holds. The chunker collapses single newlines, so code and CSV lose their line
  layout, and a file read only in part (a read cap) shows only that part.
- Zoom on the PDF is a re-rendered bucket at a capped scale, not vector zoom (Jetpack PDF would be).
- iOS: folder images open in Quick Look; Quick Look has no match highlight (the "Text from this file" sheet does); PDFs
  have no previous/next match; the audio route relies on AVPlayer reading the format, with "Open in another app" as the way out.
- Document tiles: Android has no system thumbnailer for Office files, so unless the file's provider gives a thumbnail a
  docx, xlsx, pptx, csv, txt and the like show a type card (`DocCard`: the extension on a page, or the kind's icon when
  it does not fit), not the page. iOS shows Quick Look's picture, or the type's system icon. Square tiles centre-crop a
  portrait page, as they do PDFs.
- Audio focus is ExoPlayer's own (`setAudioAttributes(AudioAttributes.DEFAULT, true)`); how it behaves against a call or another player is not judged without a device.
