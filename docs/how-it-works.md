# Search your phone by describing it: EmbeddingGemma 2 on Android and iPhone

[Google Play](https://play.google.com/store/apps/details?id=app.retriever) ·
[App Store](https://apps.apple.com/app/id6829902478) ·
[Source (Apache-2.0)](https://github.com/dhruv2295/Retriever) · free, no ads, no account

<img src="media/demo.gif" width="280" alt="Searching “delicious food” finds a biryani photo; “rental agreement” opens the PDF at the matching page">

*Numbers are from my own test runs, marked where they come from.*

You remember the photo, not its name. "The whiteboard from Monday's meeting." "The electricity bill from March." "The moment the cake comes out." Your phone's gallery can find some of that; a PDF that arrived as `Document_04.pdf` in WhatsApp, a screenshot of a Wi-Fi password, the page in a 40-page lease, mostly not.

Retriever is a free, open-source app that searches everything on your phone — photos, screenshots, documents and videos — by what's in them, entirely on the phone. No account, no server. It runs Google's EmbeddingGemma 2 on-device, on Android and on iPhone, from one Kotlin Multiplatform core.

This post is about what it took to make that usable on a real library, because the model was the easy part.

## How it's built

- **One shared core, two native apps.** Indexing, search, storage and the processing rules are Kotlin Multiplatform (`core/`). The Android app is Jetpack Compose; the iOS app is SwiftUI and talks to the core through a Kotlin/Native framework. Platform adapters do the platform things: MediaStore and ML Kit on Android, PhotoKit and Vision on iOS.
- **The model.** [EmbeddingGemma 2](https://huggingface.co/google/embeddinggemma-2), the 440M text-vision LiteRT-LM export ([`litert-community/embeddinggemma-2-text-vision-440m-litert-lm`](https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm), 388 MB generic download). Recent Pixels (Tensor G5/G6), MediaTek Dimensity 9400/9500 and Snapdragon 8 Gen 2 or newer phones get a chip-specific NPU build (447–798 MB). On Snapdragon, Play also installs Qualcomm's NPU runtime (about 8 MB) for that chip only; it embeds a photo 2–2.6× faster than the GPU (61–110 ms against 155–215 ms, Galaxy S23 to S26). Other phones run the generic build on the GPU or CPU: on first run the app times both and keeps the faster one.
- **The index.** SQLite through SQLDelight: full-text search over names, document text and the text read out of images, plus the first 256 Matryoshka dimensions quantized to `int8` (256 bytes + scale per photo, page or video moment) scanned by a small native/Kotlin dot-product kernel. A query runs both lanes — words and meaning — and merges them, so "invoice" finds the word and "flight tickets for the trip" finds the booking whose file is called `Document_04.pdf`.
- **Everything else on-device too.** Text in images (ML Kit / Apple Vision), photo labels, and an optional People feature (on-device face detection and SFace) that's off unless you turn it on.

## The real problem: the first run

A phone isn't a demo folder. Mine has about 28,000 files, most of them photos, with thousands of screenshots and PDFs. Making each one searchable means opening it, reading any text in it, tagging it and embedding it. Up to version 1.0.2, Retriever did about **85 photos a minute** on a Pixel 11 Pro Fold — hours before search by description covered everything.

Here's where one photo's time went (a Perfetto trace on the Fold):

| Step | Time |
|---|---|
| Open and decode | 28 ms |
| Read the text in it (OCR) | 236 ms |
| Tag what's in it | 87 ms |
| Turn it into a vector (NPU) | ~180 ms |

The image's vector never used the text or the tags. They're for searching *words in* photos — receipts, signs — which matters, but not first.

### Meaning first

So photos now go through two passes. The first only embeds the image, newest first. The second reads text and tags, right after, under the same rules (it doesn't wait for a charger). When both finish, the photo is stored exactly as a single pass would have stored it — a test checks that field by field.

Search by description is now ready for your whole library roughly **four times sooner**: about 350 photos a minute in my tests on the same Fold. Total time to "fully done" barely moves; what changes is when the app becomes useful.

### My own heat rule was the bottleneck

A trace of the new pipeline showed something odd: half the photos sat idle for 160–234 ms after finishing — almost exactly their own processing time. That was a rule I'd written: when Android's thermal forecast passes 0.85, rest as long as the work took. A charging phone doing steady work sits right on that line, so it rested half the time, to forestall throttling the OS already does on its own.

Processing now pauses only when Android reports *severe* heat or forecasts it is close (a headroom of 0.95); on a charger with the screen off, where nobody feels the heat, it runs until the forecast itself reaches severe. On a cool phone the two rules measured the same (about 353 and 342 photos a minute in an A/B on the Fold); the difference shows when a charging phone runs warm.

### What didn't work

- **Reading several photos' text at once.** I tried multiple ML Kit text recognizers in parallel. Reading ML Kit's bytecode showed why it couldn't help: every Latin recognizer feeds one process-wide queue that runs one task at a time. What does help is overlapping that text reading with the next photo's embedding.
- **Tags straight from the vector.** Zero-shot tags from the image vector (score it against a label vocabulary) are nearly free, since the vector already exists. On concrete objects (COCO's labels) they were more precise than ML Kit's and found more. But in a blind judging of everything a person might tag, ML Kit was right more often: 84% against 71%, over 252 tags each. Below a confidence cut-off the vector's tags were right only 55% of the time. So: keep ML Kit, maybe add the confident vector tags.
- **…but the same vector can skip work.** "Does this photo have a face?" from the vector skipped face detection on ~47% of photos while catching 35 of 36 that had faces in a 450-photo test on a Pixel 8 Pro. That's worth building.

## Outside the NPU: Pixel 8 Pro and iPhone 12 mini

Most phones don't have a Tensor G5/G6 NPU, and that's where the trade-offs get sharper.

- **Pixel 8 Pro (Tensor G3, no NPU build).** The generic model runs on the GPU or CPU. In benchmarks on the phone, a full photo took **0.87 s on GPU against 1.97 s on CPU** (about 1.4 s vs 2.1 s during steady charging runs), and the single-pass first read of an 18,000-file library took 7–8 hours on a charger (with optional face detection taking ~0.3 s a photo, about 1.5 hours for 16,700 photos). The catch: GPU inference caused **three times as many janky UI frames** while the app was open. So on phones whose GPU stalls rendering, Retriever switches to the CPU while you're interacting with the app, then moves back to the GPU after 30 seconds untouched on a charger. Moving ML Kit's text readers and labeler to Play services, and the People face model into an on-demand download, also brought the arm64 Play download down from **56.6 MB to 19.1 MB** (`bundletool get-size`).
- **iPhone 12 mini (A14, 4 GB RAM).** On a charger at full speed, the phone hit Apple's `serious` thermal state after **90 seconds** — which is why `serious` now slows reading instead of pausing it, and only `critical` pauses. And while iOS's continued background tasks can request the GPU on newer devices that advertise it, the 12 mini reports no background GPU support, so once you leave the app background reading falls back to the CPU — roughly **10× slower** than while the app is open.

## Making the wait legible

Fast isn't enough when the job takes an hour. Processing is now one journey with steps, and each step says what you can do after it:

1. **Learning what's in your files** → find any photo or file by describing it
2. **Reading the words in your photos** → search receipts, signs, notes
3. **Finding people in your photos** (if you turn it on) → find photos of someone by name
4. **The rest of long files** → search deep inside long PDFs and videos

A short note appears when each step finishes, with something to try. And during that first run a golden retriever acts out the current step on the home screen — sniffing folders, tilting its head at a photo, nose to a receipt — so you can see the app working without reading a number.

## Privacy, concretely

Files, searches and the index never leave the phone, and there's no account or server to send them to. On iOS the App Store label is "Data Not Collected". On Android, Google Play services' text recognizer may send Google its own diagnostics (device model, timings — never your images or text); the privacy policy says so. The model is a one-time download, verified by SHA-256, then search works offline. (On Android, Google Play services may download text-recognition models for some scripts, such as Chinese and Japanese.)

## Try it, read it, break it

- Android: [Google Play](https://play.google.com/store/apps/details?id=app.retriever) · iPhone: [App Store](https://apps.apple.com/app/id6829902478) — free, no ads.
- Code: [GitHub](https://github.com/dhruv2295/Retriever), Apache-2.0. The processing rules, the journey and the mascot director are all in `core/`.
- Next on my list: higher processing-thread priority when the phone is locked (+11–19% in early tests), the face-skip from the vector, and sharper mascot art for big screens.

If you build on-device AI on phones, I'd love to compare notes — especially on keeping a multi-hour first run humane.
