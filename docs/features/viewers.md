# Viewers

## Purpose

Opening a result opens it in the app, at the match: a PDF at the matched page, a photo or screenshot zoomable and
swipeable through the list it was opened from, audio and video at the matched moment, and text, documents, RTF, HTML and
EPUB in a reader of the stored text with the match marked. "Open with" and Share stay as a secondary action on every
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
  sub-sampling decodes tiles so a 50 MP photo never becomes one huge bitmap. No Coil is needed.
- **The list for swiping.** A route cannot carry hundreds of uris (the saved-state Bundle would hit
  `TransactionTooLarge`). `Opener.open` hands the list to a Koin `ViewerQueue` before navigating; the route carries only
  the opened photo. After process death `ViewerQueue.itemsFor` falls back to that one photo.
- **PDF: `PdfRenderer` kept.** `PdfPageScreen` is adapted from the platform-samples `PdfRenderer` sample: one renderer
  opened for the screen's lifetime on a single-thread dispatcher (`PdfRenderer` is not thread-safe), a lazy list of pages
  sized up front, opened at the matched page, with "Matched here" on that page. Zoom is Telephoto on a single-page view
  (a zoomable over the whole list would take the vertical drags the list needs), and the render scale grows with zoom up
  to a cap. The zoomed page covers the list, takes its touches and hides it from TalkBack, closes with a visible Close
  or Back, and survives rotation. Plain states cover locked and missing files, with "Open in another app".
- **Jetpack PDF is later.** `androidx.pdf` beta (`pdf-compose` `PdfViewer`, `scrollToPage`) renders sharper at zoom, adds
  search and selection, and parses in an isolated process, with minSdk 28 below the app's 30. It is not adopted because
  it is beta with an experimental opt-in, and whether it can mark the matched page the way "Matched here" does is not
  checked. Revisit when it is stable; `PdfPageFrame` stays until then.
- **Video and audio: Media3.** `PlayerViewModel` owns the one `ExoPlayer` of a route, so a rotation keeps it playing and
  the screen only attaches a `PlayerView`; the position is saved on stop and restored, starting at the hit until a
  position was saved. `ON_STOP` pauses: nothing plays in the background. A player error is a `Failed` state.
- **Text and documents: in-app reader.** `DocViewModel` reads the stored chunks (`Retriever.fileText`), so there is no
  second parser and no file read. The state is `DocUi` (`Loading`, `Text`, `NotReadYet`, `Empty`, `Failed`): a pending
  file with no text yet is `NotReadYet`, never the "couldn't read" `Empty`, and a file the index lacks is `Failed`.
  `loadDoc` picks the paragraph from the hit's locator or the query (a search hit carries its id and the words asked)
  and the list opens there with the words marked.
- **File actions.** Star and one overflow menu serve every viewer, including the iOS Quick Look wrapper. The same platform action model supplies long-press menus in Search, Browse and Recently read: Info, Share, Open with, Rename and Delete. Info reads optional dimensions/duration off the UI thread. Native share/open and mutation APIs own access and confirmation; Photos assets omit Rename.
- **Index mutations.** A rename holds `Retriever`'s scan lock across the provider operation and the in-place store update. The file id, chunks, read state and star stay; the name index and recent entries follow the new URI. Confirmed deletion removes index chunks and recent entries immediately. Lists observe the action revision; deleting from a viewer closes it.
- **States.** Every viewer has explicit loading, content, empty and trouble states; failures use `MascotMood.TROUBLE`
  ("Couldn't open this file", "Couldn't play this video", "Couldn't show this photo").
- **iOS.** `PDFKit` `PDFView` at the matched page with highlighted selections (PDF parse through a `@concurrent` function
  returning `sending PDFDocument?`), `AVPlayerViewController` seeked to the matched ms, a `TabView` of `ZoomableImage`
  (a `UIScrollView`) for library photos, Quick Look for the rest.

## Owner files

- `androidApp/src/main/kotlin/app/retriever/viewer/Opener.kt`, `ViewerQueue.kt`
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
5. **A failed or missing file is a state with a way out** ("Open in another app"), never a blank screen.
6. **A photo viewer after process death shows the opened photo alone,** not a stale list.
7. **Route arguments stay small;** lists travel through the queue, not the route.

## Tests

- Routing: `ResultPiecesTest` in `androidApp/src/test/kotlin/app/retriever/search/` covers `routeFor` and locator parsing.
- `PhotoScreenTest`: `openingTheThirdOfFiveShowsPageThreeAndASwipeShowsPageFour`, `aMissingPhotoShowsTheTroubleState`,
  `aRestoredScreenWithoutTheListShowsTheOpenedPhotoAlone`.
- `PdfPageScreenTest`: `matchedHereAppearsOnTheMatchedPageOnly`, `aPdfOpenedWithoutASearchMatchMarksNoPage`, `failedStateOffersOpenInAnotherApp`,
  `zoomBucketGrowsWithZoomAndCaps`, `renderScaleNeverPassesTheBitmapCap`.
- `PlayerViewModelTest`: `theSameViewModelKeepsTheSamePlayerAndReleasesItWhenCleared`,
  `startsAtTheHitUntilAPositionWasSaved`, `stoppingSavesThePositionAndTheRestoredViewModelOpensThere`,
  `aPlayerErrorMovesTheStateToFailed`.
- `DocScreenTest`: `loadsTheParagraphsAndOpensOnTheOneWithTheQuery`, `textScrollsToTheMatchedParagraphAndHighlightsIt`,
  `aFileWithNoChunksIsEmpty`, `aFileStillWaitingToBeReadSaysSoNotThatItCouldntBeRead`,
  `aSkippedFileWithNoTextIsEmptyAndAMissingOneFailed`, `aFailingReadShowsTheTroubleState`, `loadingShowsNoText`.
- `SearchViewModelTest.openingADocumentHitCarriesItsIdAndTheQueryToTheReader`.
- iOS: `iosApp/RetrieverUITests/AccessibilityAuditTests.swift` audits fixture routes only.

## Known limits

- The reader shows the text the index holds. The chunker collapses single newlines, so code and CSV lose their line
  layout, and a file read only in part (a read cap) shows only that part.
- Zoom on the PDF is a re-rendered bucket at a capped scale, not vector zoom (Jetpack PDF would be).
- iOS: folder images open in Quick Look (zoomable but without the Star and Share toolbar); Quick Look gets one uri, so no
  swipe between folder results; text documents have no match highlight; the PDF lands on the match after a fixed 0.3 s
  timer.
- Audio focus is ExoPlayer's own (`setAudioAttributes(AudioAttributes.DEFAULT, true)`); how it behaves against a call or another player is not judged without a device.
