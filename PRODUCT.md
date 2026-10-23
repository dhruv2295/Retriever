# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Users

Everyday phone owners, most of them not technical, with thousands of photos, screenshots, downloads and documents on
their phone. Their job is to find one particular thing they half remember ("the wifi password the landlord sent",
"my passport photo") without knowing its name, date or folder. Privacy reassures them; it is not why they came.

## Product Purpose

Retriever finds files on the phone by what is inside them and by what the person means, not by name. Success is a
first search that finds something real in the person's own files within a couple of minutes of opening the app, and
after that a phone whose whole library becomes searchable by meaning without the person managing anything.

## Positioning

Search by meaning across every kind of file (photos, screenshots, PDFs, documents, video moments) in one box, running
entirely on the phone. The meaning model (EmbeddingGemma M440) is the product: without it, Retriever's name, text and
photo-label search mostly matches what the system Photos and Files apps already do.

## Operating Context

- First run happens on the person's own phone and files; there is no sample data.
- The model is a one-time download (387.71 MB generic M440; chip-specific builds are larger), over Wi-Fi unless the
  person allows mobile data, followed by a one-time tune on the device. It is the only network call the app makes.
- Processing the whole library takes hours (about 10 to 12 for 26k files on a Pixel 8 Pro); names, document text,
  OCR and labels come quickly, meaning is the slow pass. Processing runs automatically: the backlog while charging
  with the screen off, a few new files at once.
- Phones under 4 GB of RAM cannot run the model and stay in keyword search for good.

## Capabilities and Constraints

- Names are searchable as soon as access is granted; document text, OCR and photo labels need no model.
- Meaning search, image understanding and video moments need the model and its tune.
- Android asks for all-files access or chosen media and folders; iOS gets photos through the Photos permission and
  folders only through Files' folder picker.
- One automatic processing strategy, no user configuration (owner decision, 2026-10-23); Pause and "Process for 15
  minutes" remain as overrides.
- Vocabulary: the app "processes" files; never "reads", "analyzes", "indexes" or "scans" in the UI.
- Hours estimates stay hidden until calibrated (`ESTIMATE_CALIBRATED`).

## Brand Commitments

- The golden retriever mascot, animated per mood (`docs/motion-api.md`), one character across app icon and screens.
- Warm, plain copy; honest without losing personality.
- Native on each platform: Compose Material 3 on Android, SwiftUI on iOS.

## Evidence on Hand

- Mascot clips and stills: `iosApp/Retriever/Motion/Frames/` (shared with Android as assets).
- Illustrations: `IllusWelcome`, `IllusReading`, `IllusEmpty` (iOS asset catalog; Android `drawable-nodpi`).
- No testimonials, ratings, user counts or benchmarks against other apps exist; do not invent them.

## Product Principles

1. Show, don't tell: let the person's own files prove the app before asking for hours of work.
2. Meaning is the point; everything else is the floor it stands on.
3. One path, decided for the person; overrides exist but are never required.
4. Every screen makes sense to a first-time, non-technical user.
5. Nothing leaves the phone, and the app never claims more than it has done.

## Accessibility & Inclusion

Reduce Motion and power saving stop the mascot on a still pose; screens are audited with the iOS accessibility audit
and must work at the largest text sizes (scrolling allowed). The mascot is decorative; text carries every state.
