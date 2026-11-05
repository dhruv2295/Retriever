# Model lifecycle and the AI pipeline

## Purpose

One on-device EmbeddingGemma 2 model turns every piece of a file (text chunk, photo, OCR'd screenshot, PDF page, video
moment) and every query into a vector in one shared space, so search matches by meaning beside keywords and
names. This note covers choosing the build, opening, sharing, falling back and releasing the embedder, and the vector
contract. Download is in `model-download.md`, backend measuring in `calibration.md`, OCR in `extraction.md`. No file
content leaves the phone; only the model download uses the network.

## Mechanism and why

- **Runtime.** MediaPipe Tasks `UniversalEmbedder` on LiteRT-LM (Android) and the MediaPipe Tasks Swift package (iOS,
  pinned by revision to the `v1.1.1` commit), the official
  on-device path for EmbeddingGemma 2 and what the mediapipe-samples `universal_embedder` and Google AI Edge Gallery use.
  The API has no batch call (`embedContent` fuses its parts into one vector), so embedding is one call per piece.
- **One embedder, owned by `ModelHost`.** Indexing and search share it (two copies would double a 390 to 900 MB memory
  cost). `ModelHost.open` runs under `ModelHost.lock`; the key is build, text backend, vision backend, vision tokens and max tokens, so a prefs
  switch never reopens it. Each open does a text warm-up embed inside `countedOpen`. Search takes the lock with `tryLock`
  and answers from keywords while the model opens or Tune runs.
- **Compared with Gallery's `OnDeviceEmbedder`** (adapted, Apache-2.0): a coroutine `Mutex` instead of `synchronized` (queries
  queue FIFO without pinning threads); it throws instead of returning null; after a failure a probe embed of known-good
  input separates a bad input (the file fails) from a broken backend (the embedder closes itself and the host records the
  fallback); the compiled-program cache dir comes from `setCacheDir`, keyed by model, delegate, vision tokens and max
  tokens; vision placement follows Tune's measured rendering stalls, with no GPU-name rule; audio always runs on CPU.
- **Fallback ladder.** NPU build to generic build (`markNpuFailed`, `npu_failed` meta, permanent for that file); GPU to
  CPU after a warm-up embed that broke (`demoteGpu`), after a mid-batch break (`broke`), or after two opens that never
  returned (`checkUnfinishedOpens`, `gpu_unsafe`, and only when `lastExitCrashed` says the process died of a native crash, a
  signal or Android 17's per-app memory limit (`REASON_OTHER` with "MemoryLimiter"), not a swipe or the low-memory killer). Any other open failure (a missing or truncated file, low memory) is rethrown and the
  batch retries; it says nothing about the GPU. An open refused for now (`broken = false`, on any backend) rests the
  batch (`AFTER_COOLDOWN`) instead of demoting or looping. On iOS `backendNow` runs the CPU in the background, except
  for a continued read iOS granted the background GPU (`IosDeviceMonitor.backgroundGpu`); a GPU refusal there drops the
  grant without demoting the GPU.
- **Variant.** New downloads select M440 on both platforms (387,710,976 bytes generic). A verified installed M740
  remains selected while its signature owns the index; losing/changing it selects M440. Every device can read words
  and image labels without a download; under 4 GB (`Tiers.MIN_MODEL_RAM_GB`) it stays keyword-only (`NoModel`). NPU builds are commit-pinned with size and
  sha256, for Tensor G5/G6 and MediaTek MT6991/MT6993 (Qualcomm is left out: the AAR has no QNN libraries).
- **Vector contract (both platforms).** L2 normalized output, Matryoshka cut to `DIMS` = 256, re-normalized, stored as int8
  with one scale per vector (`Vector.kt`). Text prefixes only: query `task: search result | query: ...`, document
  `title: <name> | text: ...`; images, audio and video moments go in raw (`Prefix` in Chunker.kt). `Signature` =
  variant/sha8/vision tokens/d256/max tokens; vectors of different signatures never mix. A change drops only vectors, then embeds saved text and reloads pixels
  at saved locators; OCR, document text, FTS and labels stay searchable. The eval
  (`eval/REPORT.md`) found prefixes worth +2 recall@5 and d256 int8 equal to d512 float.
- **Release.** Android starts a separate foreground warm-up before awaiting its trial. `onStop` cancels it, then asks
  `releaseIfIdle()` to free the hidden model. Batch, search and warm-up
  completion also check, so an in-flight user defers release until it finishes. The index lock protects the entire
  batch and the SOON gap; the model lock protects closing and rechecks visibility and active queries. A visible app
  and an imminent follow-up keep the model warm. Explicit `release()` still forces release for storage/debug actions.
  iOS also releases on entering the background (under `beginBackgroundTask`) and on a hidden memory warning.
- **Memory pressure.** `Retriever.trimMemory()` gives back what is rebuilt on demand: the model, even one kept warm for an
  imminent follow-up (not while a batch or search uses it, nor while the app shows), and the search engine's vector
  table, which the next search or warm-up loads again. Android calls it from `RetrieverApp.onTrimMemory` at
  `TRIM_MEMORY_BACKGROUND` and above, as the memory guide advises (the other levels are deprecated from API 34); iOS from
  a hidden memory warning, beside the release above.
- **Caches.** `cacheDir/embed/<model stem>-<delegate>-vt<n>-t<n>`; `keepOnly` drops caches of removed models (Android);
  `ModelDownload.deleteModel` removes a build's caches.
- **Scheduling.** Strictly sequential, batch size 1. Android indexing uses an owned thread set to
  `THREAD_PRIORITY_BACKGROUND` before its first task; iOS uses a serial dispatch queue at utility QoS. Extraction
  and embedding inherit the caller context. Search and the shared model's own caller keep normal priority. The runtime's native pools
  are started in `MediaPipeEmbedder`'s constructor and on its first inference (the host's warm-up), not on the
  creating thread's priority, so `lowerNewThreads` (NativeThreads.kt) runs both under a marker thread name, diffs
  `/proc/self/task` around them and sets each new thread that still carries the marker (a native thread starts with
  its creator's name) to `THREAD_PRIORITY_BACKGROUND`. Threads other code starts meanwhile, such as coroutine workers
  that later run search, keep their priority. A query waits for at most
  the one native call in flight.

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
- `androidApp/src/main/kotlin/app/retriever/RetrieverApp.kt` (`onStop`), `androidApp/src/main/kotlin/app/retriever/IndexWorker.kt` (release after a run)
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
10. LiteRT loads the first dispatch library in its directory, and the APK carries Tensor's and MediaTek's. An NPU open
    points it at `no_backup/npu-dispatch/<library>/`, which holds a link to the build's own vendor's library alone
    (`ModelBuilds.dispatchLibrary`); with the whole `nativeLibraryDir`, a Tensor G6 loaded MediaTek's and failed.

## Tests

`ModelHostTest`, `RetrieverTest`, `BackendNowTest`, `ModelBuildsTest`, `NpuGateTest`, `CoreLogicTest`, `DefaultsTest`,
`IosEmbedderTest`, `BridgesTest`, `OcrScriptsTest`, `EmbedderCacheTest` (androidApp). No instrumented test drives the real
`MediaPipeEmbedder` (GPU to CPU vision fallback, NPU options, cache names), so those wait on a device.

## Known limits

- No batching: extraction and embedding alternate per file.
- Shared model pools are lowered once at creation and first inference; threads the runtime starts later, and runtime or
  driver threads that rename themselves, stay at normal priority. Vendor-created OCR thread priorities and GPU
  scheduling still require device measurement. Tune measures display stalls and moves foreground vision to CPU where needed.
- Query latency has no on-device number; inputs may be padded to the signature length, so a short query may cost a full
  window. Device queue: measure query time on GPU and CPU before choosing a backend by query time instead of image time.
- A GPU or NPU that never crashes but is slightly wrong is caught only by the 0.98 mean-cosine gate at Tune time; NPU
  builds have no CPU reference.
- `gpu_unsafe` and CPU demotion are cleared only by Tune again (`clearGpuUnsafe`), not by an OS or driver update.
- iOS reads the background on CPU (a continued read granted the background GPU would keep it; the request doesn't ask
  for it until the signing profiles carry the entitlement, see background-ios.md); foreground GPU
  text can use CPU vision when Tune measures stalls. Agreement is gated at 0.98 mean.
- M440 saves 96.91 MB against generic M740. Vectors are rebuilt on a signature change; no cross-model compatibility
  is assumed. Audio files are name-only on both platforms. See `optional-model.md` for the UI API and cost measurements.
- ML Kit has no Arabic, Cyrillic, Thai, Hebrew or Tamil; those screenshots read through the Latin pass. iOS Vision reads them.

## Rendering stalls and repeatable A/B

Tune watches main-thread Choreographer callbacks on Android and CADisplayLink on iOS during the existing GPU image
warm-ups and samples. A gap over 250 ms sets `DeviceProfile.gpuVisionStalls`; the measured 150 ms GPU-on p99 and
53 ms paused p99 are below that threshold, while the seconds-long stalls exceed it. Open/compile and text/audio
benchmarks are excluded. The observer includes the first frame after inference and unsubscribes on failure or
cancellation. Hidden frames do not count. No extra model inference runs.

For a GPU profile with stalls, foreground vision runs on CPU and text stays on GPU. Android screen-off reading uses
GPU vision again. iOS already used GPU vision in front (`MediaPipeOpener` set both delegates to GPU), so it has the
same measurement and split-delegate policy; background iOS stays on the CPU because iOS refuses background GPU work,
except for a continued read granted the GPU (`BGContinuedProcessingTaskRequest.Resources.gpu`). On Android, Retriever
left untouched in front on a charger for 30 s (`idleInFront`; `MainActivity` reports input from `onResume` and
`onUserInteraction`) reads as if hidden: GPU, one file read ahead. The next input turns it back. iOS reports no input,
so it never counts as idle. Pixel 8 Pro, 28 Oct 2026: 0.87 s a photo against 1.97 s on CPU, with janky frames at
37 % against 10 %, which nobody is watching. A visibility/configuration change ends the current batch after its current
file; the next batch opens the new configuration (3-9 s on the Pixel), so idle and in use each switch once, not per
touch. Search warm-up also takes the index lock, so it cannot retire a batch's handle. Signatures stay the same.

Debug Android only (persistent across process restarts, applies at the next file/batch boundary):

```sh
adb shell am start -n app.retriever/.DebugActivity --es run vision --es q cpu
adb shell am start -n app.retriever/.DebugActivity --es run vision --es q gpu
adb shell am start -n app.retriever/.DebugActivity --es run vision --es q policy
adb logcat -s RetrieverBench Retriever
```

These force the **vision** delegate with text on GPU, or restore measured policy. Release's source-set implementation
always returns no override, and contains neither the debug activity nor the preference reader/writer.

An offline `profile.backend = CPU` is a calibration result, not an override: an already-running Tune subsequently
calls `applyProfile` with its winner. `ModelHost` then observes the new key and reopens, previously even via search
warm-up during a file. `retierFrom` preserves the stored backend; Android's normal factory never promoted CPU to GPU
by itself. This identifies the overwrite/reopen path, but the reported 32-second event has no trace/DB snapshot in this
checkout to prove which writer ran then. The separate debug preference survives Tune and retiering.
