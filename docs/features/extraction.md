# Extraction (reading one file)

## Purpose

Read one file into pieces the embedder can take: text chunks, images, video moments, within fixed caps, on
the phone, with no network. OCR feeds both the text lane and the keyword index. Also the content hasher that Duplicates uses.

## Mechanism and why

| Part | Mechanism | Why it is the accepted one |
|---|---|---|
| PDF (Android) | Text layer via `PdfRenderer.Page.textContents` (API 35+) or `PdfRendererPreV` (S-extension 13, API 31 to 34). OCR only for thin or broken pages, at most 30 (`PDF_OCR_PAGES`); at most 1000 text pages. With the model there, the first pass OCRs the first 3 such pages (`PDF_HEAD_OCR_PAGES`) and leaves the rest as `LATER` placeholders, which the deepen pass renders and OCRs (`Extractor.later`); keyword-only reading has no deepen pass, so it OCRs them all now. Scanned pages render `OCR_WIDTH` wide with the height capped (`MAX_PAGE_PX`). | Platform APIs; Jetpack `androidx.pdf` uses the same `PdfRendererPreV` gate, so it adds nothing for extraction. |
| PDF (iOS) | PDFKit `page.string`, a render for OCR, a one-entry document cache. | Standard. |
| OCR (Android) | ML Kit text recognition v2: Latin and Devanagari bundled, Chinese/Japanese/Korean through Play services (`ModuleInstall` requested once). `readScripts` tries the scripts of the phone's languages, in their order, until a reading is not sparse. A script the phone doesn't list is never tried just because a reading was unsure; Devanagari is added only when an unsure reading holds Devanagari letters. Photos and screenshots are read. Photos fit 1080 px short / 1600 px long; screenshots keep their 4096 px long cap. Empty readings stop additional scripts. | The official on-device OCR. |
| OCR (iOS) | Vision `VNRecognizeTextRequest(.accurate)` with automatic language detection on photos and screenshots, with the same photo bounds. | The official on-device OCR. |
| Image labels | Bundled ML Kit Image Labeling 17.0.9 (Android), Vision `VNClassifyImageRequest` (iOS), confidence >= 0.7, 512 px input. Android labels the same 512 px thumbnail bitmap it then compresses for the embed (`ImageBytes.thumbnail`), with no JPEG round trip. Both photos and screenshots; a separate FTS labels field, never content/snippets. | Platform/base models, no embedding download. |
| Documents | DOCX/ODF one entry, PPTX slides, XLSX (shared strings first, then sheets), EPUB chapters, HTML/RTF/text head. Android streams `ZipInputStream`, iOS uses random-access ZIPFoundation; one 8 MB `ZipBudget` per zip, with a reserve so shared strings always fit. | No heavy dependency (POI, Tika); stripping XML is enough for search text. |
| Media (Android) | `MediaMetadataRetriever` key frames, at most 8 moments, black-frame skip. Decode loops check cancellation. With the model there, the first pass decodes one moment (the middle, then outwards past black frames: `headOrder`) and leaves the others as `LATER` placeholders for the deepen pass, which decodes each with the same ±1 s black-frame fallback (`frameJpeg`). | Gallery's proven path (Apache-2.0). |
| Media (iOS) | `AVAssetImageGenerator`, frames only; no audio. PhotoKit waits have a 20 s timeout and cancel the request. | Standard. |
| Hasher | SHA-256 of size, the first 64 KB and the last 64 KB, then a full SHA-256 in 1 MB chunks only inside a group that matches on the quick hash. | Standard two-stage duplicate detection. |

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/Extraction.kt` (caps, `ZipBudget`, XML and PDF rules, `ocrTargetSize`)
- `core/src/commonMain/kotlin/app/retriever/core/OcrScripts.kt`
- `core/src/commonMain/kotlin/app/retriever/core/VideoMoments.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Chunker.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Duplicates.kt`
- `core/src/androidMain/kotlin/app/retriever/core/android/AndroidExtractor.kt`
- `core/src/androidMain/kotlin/app/retriever/core/android/MediaDecoding.kt`
- `core/src/androidMain/kotlin/app/retriever/core/android/AndroidContentHasher.kt`
- `core/src/iosMain/kotlin/app/retriever/core/IosExtractor.kt`
- `core/src/iosMain/kotlin/app/retriever/core/IosContentHasher.kt`
- `iosApp/Retriever/Core/Extractor.swift`

## Invariants

1. Extraction is bounded: 1000 text pages, 30 OCR pages, a 2 MB text head, 8 MB per zip, 8 video moments. A
   file that is cut short is recorded `partial` and its remainder can be deepened later.
2. A stop never charges a file: `CancellationException` gives the attempt back, and every extractor rethrows it.
3. A bad input fails one file, never the batch.
4. Task prefixes go on text only (`model-lifecycle.md`); images and moments go in raw.
5. OCR text that looks broken (`looksBroken`) is not indexed.
6. OCR under 40 characters (`MIN_TEXT_CHARS`, the floor `textPieces` uses) is a scrap: it stays on the image's chunk (keyword search, content text) and is never embedded on its own. Its vector stays an image's (`vectorsAfter.hasText` ignores visual chunks).
6. Nothing is downloaded from iCloud while reading; an image fetch uses Wi-Fi unless mobile data is allowed.

## Tests

`ExtractionTest`, `ZipTextTest`, `PdfPiecesTest`, `OcrSizeTest`, `DocFormatTest`, `RtfTest`, `TextQualityTest`,
`OcrScriptsTest`, `VideoMomentsTest`, `ChunkerCapTest`, `DuplicateFinderTest` (duplicate grouping with fake hashers). iOS
extraction is covered by `SelfTest.swift`; the cross-platform `MeaningUpgradeTest` proves stored content survives model changes.

## Known limits

- Android 12 to 14 without S-extension 13 has no PDF text layer, so only the first 30 pages are read by OCR.
- ML Kit has no Arabic, Cyrillic, Thai, Hebrew or Tamil; those screenshots read through the Latin pass and store
  low-confidence text. A CJK screenshot read while its Play-services recognizer is still downloading is not re-read.
- ML Kit tries the next script only for nonempty uncertain/broken text; a blank first reading stops immediately. An
  English-only phone no longer falls back to Devanagari on an unsure reading; ML Kit's Latin recognizer writes Hindi as
  Latin guesses, so its Devanagari-letter trigger rarely fires and Hindi images there keep only that Latin guess. A phone
  that lists a Devanagari language reads them as before. Device queue: measure how often the trigger fires.
- EPUB reads chapters in zip order and PPTX in slide-file order, not spine or presentation order. HTML and text are decoded as
  UTF-8 only.
- Audio files are name-only on both platforms; video moments have no audio.
- iOS `usesLanguageCorrection` can alter codes and passwords in screenshots.
- Later: a one-entry archive cache for iOS `zipEntryText` (it reopens the archive per entry), and DOCX/ODF streaming still
  inflates entries after the one it needs.
- Device queue: OCR quality at the current scan size on real scanned PDFs and receipts; fixture-PDF instrumented tests.
