# Reading before the optional model

New model downloads use M440 on both platforms: 387,710,976 bytes (387.71 decimal MB) for the generic build.
Tensor G5/G6 and MediaTek MT6991/MT6993 retain their M440 NPU builds; other devices use generic M440.
A verified installed M740 continues serving its matching index. An unavailable or changed build selects M440.
Audio files remain name-only; new video extraction has no audio.

Names are searchable as soon as sync runs. Reading adds document/PDF text, photo/screenshot OCR and classifier labels
without requiring the optional embedding model or calibration. Under 4 GB RAM, keyword mode remains permanent and video stays name-only.
OCR is content; classifier labels occupy `chunks.labels` and a separate FTS column. Labels are excluded from
`content_text`, previews and snippets. Label-only matches expose `Hit.matchReason`, for example `Matches: beach`.

Schema 6 preserves existing chunks, content provenance, FTS rows and vectors. A later signature change clears only
vectors and their attempt counts. Meaning waits for the tune: a downloaded but untuned model adds no vectors, and the
counts leave meaning work out until the tune finishes. Then the existing deepen queue embeds saved text and reloads images at saved locators,
including PDF pages and video moments. No OCR, classification or document extraction repeats during this pass.
Name-only files get a name-vector placeholder. Videos are not opened without the model (their frames are only model
input, about 1.2 s each on a Pixel 8 Pro); they are found by name, and `resetForNewModel` queues every video without
frames to be read, frames and vectors in one pass, when a model arrives. The vector deletion counter invalidates a warm search's old vectors.
Each missing vector gets at most three attempts, and cancellation gives its attempt back. Files stay `INDEXED`, so
keyword search and the searchable total remain available while meaning is added. Failed vectors remain visibly
not/partly added in meaning coverage even when their retry queue is exhausted.

## API for setup and Home

- `Retriever.modelPresent`: a flow of verified selected-build presence, refreshed by `refreshSetUp()` and batches.
- `Retriever.requiredBuild`: the selected build, including `sizeBytes`; `modelDownloadBytes` exposes that size directly.
  Use the actual build size in copy: NPU downloads exceed 388 MB.
- `Retriever.audioContentSupported`: false.
- `Retriever.meaningStates(fileIds)`: `UNREAD`, `NOT_ADDED`, `PARTLY_ADDED` or `ADDED` for each existing file.
- `Retriever.counts().meaning`: aggregate `notAdded`, `partlyAdded` and `added` counts of indexed files.
- Existing `tailFiles`/`ReadExtent.DEEPENING` describe queued meaning work. These are a subset of indexed files,
  compatible with C = F + P. The facade hides the queue while the model is absent or the device is keyword-only.
- `ReadingProgress.meaningWaiting` counts files with queued vectors and none yet; they stay Read (see `reading.md`).
  The meaning pass follows the reading schedule and its policy checks between chunks.
- `setUp` retains its existing tuning/setup meaning; the later setup UI decides how to offer skipping the model.

## Cost evidence (2026-10-19)

`swift scripts/photo-analysis-bench.swift /absolute/path/to/eval/corpus/photos` measures the Vision primitives with
production photo sizes on a host. Apple M4 Max, 16 existing JPEG fixtures, one first pass then three warm passes
(48 samples); no physical device. Decode and OCR/classification are measured separately.

| Warm cost per photo | Median | p95 |
|---|---:|---:|
| Decode both input sizes | 8.8 ms | 10.5 ms |
| Vision accurate OCR | 49.9 ms | 92.3 ms |
| Vision classification | 5.1 ms | 5.8 ms |
| OCR with no text blocks (27 samples) | 20.7 ms | 52.4 ms |

First fixture: decode 38.1 ms, OCR 56.0 ms, classification 17.4 ms. This includes the first requests in this process,
not a guarantee of cold OS caches. Photo OCR targets 1080 px short / 1600 px long; Android's thumbnail fallback requests 1600 px square.
Classification uses a 512 px input. Screenshot OCR retains its existing 4096 px long-side cap.
ML Kit's existing `readScripts` stops on an empty reading. Both classifiers use confidence >= 0.7.

Android planning estimate, not measured: roughly 50–250 ms warm OCR plus 10–50 ms labeling per ordinary photo;
slow devices, dense text, extra script attempts and first-use initialization can exceed this. Native ML Kit cannot
be timed by JVM stubs. Device measurements are still needed for latency, battery, thermal behavior and recall.

The debug arm64 APK grew from 79,408,138 to 89,223,370 bytes (+9,815,232; 9.82 decimal MB), including bundled labeling
and its transitive runtime. This is an APK delta, not a Play compressed delivery or release-size measurement.
ML Kit documents about 5.7 MB for its bundled base model and 400+ labels:
https://developers.google.com/ml-kit/vision/image-labeling/android

`MeaningUpgradeTest` covers no-model reading, label provenance, two successive model signatures, no repeated
extraction, bounded unavailable-image retries, and invalidating a warm vector cache. `MigrationTest` covers v5 → v6;
`LowEndTest` covers the untuned 8 GB facade; `ModelBuildsTest` covers legacy retention and M440 fallback.
