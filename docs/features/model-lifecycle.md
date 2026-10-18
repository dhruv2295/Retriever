# Model lifecycle and the AI pipeline

## Purpose

One on-device EmbeddingGemma 2 model turns every piece of a file (text chunk, photo, OCR'd screenshot, PDF page, video
moment, audio window) and every query into a vector in one shared space, so search matches by meaning beside keywords and
names. This note covers choosing the build, opening, sharing, falling back and releasing the embedder, and the vector
contract. Download is in `model-download.md`, backend measuring in `calibration.md`, OCR in `extraction.md`. No file
content leaves the phone; only the model download uses the network.

## Mechanism and why

- **Runtime.** MediaPipe Tasks `UniversalEmbedder` on LiteRT-LM (Android) and the MediaPipe Tasks pod (iOS), the official
  on-device path for EmbeddingGemma 2 and what the mediapipe-samples `universal_embedder` and Google AI Edge Gallery use.
  The API has no batch call (`embedContent` fuses its parts into one vector), so embedding is one call per piece.
- **One embedder, owned by `ModelHost`.** Indexing and search share it (two copies would double a 390 to 900 MB memory
  cost). `ModelHost.open` runs under `ModelHost.lock`; the key is build, backend, vision tokens and max tokens, so a prefs
  switch never reopens it. Each open does a text warm-up embed inside `countedOpen`. Search takes the lock with `tryLock`
  and answers from keywords while the model opens or Tune runs.
- **Compared with Gallery's `OnDeviceEmbedder`** (adapted, Apache-2.0): a coroutine `Mutex` instead of `synchronized` (queries
  queue FIFO without pinning threads); it throws instead of returning null; after a failure a probe embed of known-good
  input separates a bad input (the file fails) from a broken backend (the embedder closes itself and the host records the
  fallback); the compiled-program cache dir comes from `setCacheDir`, keyed by model, delegate, vision tokens and max
  tokens; the old-Mali vision-on-CPU rule and the NPU dispatch options are copied as is; audio always runs on CPU.
- **Fallback ladder.** NPU build to generic build (`markNpuFailed`, `npu_failed` meta, permanent for that file); GPU to
  CPU after a warm-up embed that broke (`demoteGpu`), after a mid-batch break (`broke`), or after two opens that never
  returned (`checkUnfinishedOpens`, `gpu_unsafe`, and only when `lastExitCrashed` says the process died of a crash, not a
  swipe or the low-memory killer). Any other open failure (a missing or truncated file, low memory) is rethrown and the
  batch retries; it says nothing about the GPU. An open refused for now (`broken = false`, on any backend) rests the
  batch (`AFTER_COOLDOWN`) instead of demoting or looping. On iOS `backendNow` runs the CPU in the background.
- **Variant.** `ModelBuilds.variantFor`: iOS always M440; Android M740 at 7 GB nominal RAM or more, else M440. Under 4 GB
  (`Tiers.MIN_MODEL_RAM_GB`) the phone runs text-only with no model (`NoModel`). NPU builds are commit-pinned with size and
  sha256, for Tensor G5/G6 and MediaTek MT6991/MT6993 (Qualcomm is left out: the AAR has no QNN libraries).
- **Vector contract (both platforms).** L2 normalized output, Matryoshka cut to `DIMS` = 256, re-normalized, stored as int8
  with one scale per vector (`Vector.kt`). Text prefixes only: query `task: search result | query: ...`, document
  `title: <name> | text: ...`; images, audio and video moments go in raw (`Prefix` in Chunker.kt). `Signature` =
  variant/sha8/vision tokens/d256/max tokens; vectors of different signatures never mix and a change re-indexes. The eval
  (`eval/REPORT.md`) found prefixes worth +2 recall@5 and d256 int8 equal to d512 float.
- **Release.** `release()` frees the model: Android after a worker run that nothing follows soon and on
  `onTrimMemory(BACKGROUND)`; iOS on entering the background (under `beginBackgroundTask`) and on a memory warning while
  not in front.
- **Caches.** `cacheDir/embed/<model stem>-<delegate>-vt<n>-t<n>`; `keepOnly` drops caches of removed models (Android);
  `ModelDownload.deleteModel` removes a build's caches.
- **Scheduling.** Strictly sequential, batch size 1. A query waits for at most the one native call in flight.

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/ModelHost.kt` (open, fallback ladder, release, unfinished-open counter)
- `core/src/commonMain/kotlin/app/retriever/core/Retriever.kt` (owns the host; `calibrate`, `retierFrom`, `release`)
- `core/src/commonMain/kotlin/app/retriever/core/ModelBuilds.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Model.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Vector.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Chunker.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Adapters.kt` (`Embedder`, `EmbedderFactory`)
- `core/src/androidMain/kotlin/app/retriever/core/android/MediaPipeEmbedder.kt`
- `core/src/androidMain/kotlin/app/retriever/core/android/Device.kt`
- `core/src/iosMain/kotlin/app/retriever/core/IosEmbedder.kt`
- `iosApp/Retriever/Core/MediaPipeEmbedder.swift`
- `androidApp/src/main/kotlin/app/retriever/RetrieverApp.kt` (`onTrimMemory`), `androidApp/src/main/kotlin/app/retriever/IndexWorker.kt` (release after a run)
- `iosApp/Retriever/Core/Lifecycle.swift`

## Invariants

1. At most one live embedder, opened only under `ModelHost.lock`; every handle is closed on every path (failed warm-up,
   calibration, release, fallback).
2. `close()` never runs concurrently with a native call on the same handle and never on the main thread.
3. A vector is compared only with vectors of the same `Signature`; prefixes, `DIMS` and normalization are identical on both
   platforms.
4. A bad input fails one file; a bad backend is retired and recorded, never silently.
5. An open that never returned is counted before it starts, so a native crash cannot loop; two such opens in a row after a
   crash demote.
6. An NPU build runs only on NPU; its failure is permanent for that build file.
7. The model is used only when its size and sha256 marker match (`model-download.md`).
8. Indexing never delays a query by more than one native call.
9. `useLegacyPackaging = true` in `androidApp/build.gradle.kts` stays: NPU dispatch reads `nativeLibraryDir`.

## Tests

`ModelHostTest`, `RetrieverTest`, `BackendNowTest`, `ModelBuildsTest`, `NpuGateTest`, `CoreLogicTest`, `DefaultsTest`,
`IosEmbedderTest`, `BridgesTest`, `OcrScriptsTest`, `EmbedderCacheTest` (androidApp). No instrumented test drives the real
`MediaPipeEmbedder` (GPU to CPU vision fallback, NPU options, cache names), so those wait on a device.

## Known limits

- No batching: extraction and embedding alternate per file.
- Query latency has no on-device number; inputs may be padded to the signature length, so a short query may cost a full
  window. Device queue: measure query time on GPU and CPU before choosing a backend by query time instead of image time.
- A GPU or NPU that never crashes but is slightly wrong is caught only by the 0.98 mean-cosine gate at Tune time; NPU
  builds have no CPU reference.
- `gpu_unsafe` and CPU demotion are cleared only by Tune again (`clearGpuUnsafe`), not by an OS or driver update.
- iOS reads the background on CPU and the foreground on GPU into one index; agreement was gated only at 0.98 mean.
- M440 versus M740 (decision, deviceQueue): M740 costs about +97 MB download, a 91 MB audio-encoder cache and extra heap even
  with audio off, and text and vision vectors are believed identical to M440 but the signature differs, so a switch
  re-indexes. Needs a device memory measurement and a cosine comparison on the eval corpus before changing the rule.
- ML Kit has no Arabic, Cyrillic, Thai, Hebrew or Tamil; those screenshots read through the Latin pass. iOS Vision reads them.
