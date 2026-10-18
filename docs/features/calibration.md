# Calibration (Tune)

## Purpose

Pick the fastest backend this phone can run correctly, and a tier (vision tokens, video moment spacing, audio) that the
phone can sustain. It runs once in setup and again on request ("Tune again"), and a running library can move the tier up
from real read times.

## Mechanism and why

- **CPU first, as the reference.** `Calibration.run` measures the CPU, then every other backend the build lists (an NPU build
  lists only NPU; a generic build lists GPU then CPU; iOS lists GPU then CPU, CPU only on the simulator). Outputs are
  compared one to one by index, so the sample order is fixed: images, texts, audio, queries.
- **Correct before fast.** A backend must agree with the CPU (mean cosine at least 0.98, no NaN) and finish within
  `max(3 x CPU total, 30 s)`; the 30 s floor covers a GPU compiling its kernels on first open. The fastest agreeing
  backend by median image plus query time wins. The warm-ups are discarded.
- **Tier from time and memory.** `Tiers.profileFor(imageMs, ramGb, ...)`: FULL at 150 ms or less and 8 GB or more, BALANCED at
  1200 ms or less and 6 GB or more, else LIGHT. Calibration measures at the reference 70 vision tokens, so Tune again keeps
  the stored tokens (changing them would make every vector stale).
- **Runs where the model runs.** `Retriever.calibrate` cancels the batch, then holds `indexLock` and `ModelHost.lock`, so
  searches answer from keywords and two copies of the model never load. The whole run is a `countedOpen`, and
  `ModelHost.measurable` leaves out a GPU that crashed twice. An NPU failure marks the build failed and rethrows.
- **Samples are real.** `CalibrationSamples` (Android) and the iOS equivalent harvest photos, text and queries from the
  library, with bundled fallbacks (`Samples.FALLBACK_TEXTS`) so a phone with no photos still tunes.
- **Moves up, never down on its own.** `Retriever.retierFrom` keeps a window of five photo embed times; when their median
  would justify a higher tier, `applyProfile` raises it. Only Tune again lowers a tier. Settings still at the old tier's
  defaults take the new ones (`Prefs.retuned`); kinds that now read more are re-queued.

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/Calibration.kt` (`Calibration`, `Samples`, `Tiers`)
- `core/src/commonMain/kotlin/app/retriever/core/Retriever.kt` (`calibrate`, `applyProfile`, `retierFrom`)
- `core/src/commonMain/kotlin/app/retriever/core/ModelHost.kt` (`measurable`, `clearGpuUnsafe`)
- `core/src/commonMain/kotlin/app/retriever/core/Prefs.kt` (`defaultFor`, `retuned`)
- `androidApp/src/main/kotlin/app/retriever/model/CalibrationSamples.kt`
- `androidApp/src/main/kotlin/app/retriever/model/ModelRepository.kt` (`TuneState`, `tuneSummary`)
- `iosApp/Retriever/AppModel+Setup.swift`

## Invariants

1. Calibration measures what will run: the same backend, vision tokens and prefixes as production.
2. A backend that disagrees with the CPU, or is far slower than it, is never chosen; no half-initialized backend is left open.
3. The model is closed before Tune and reopened by the next batch; no batch or search holds a second copy.
4. The stored profile changes only through `applyProfile`, which also reconciles prefs and re-queues files.
5. Tune again never changes vision tokens.
6. A tier moves up from observed read times and down only on request.

## Tests

`CalibrationTest`, `CoreLogicTest`, `RetuneTest`, `RetrieverTest` (Tune counts as an open, skips a crashed GPU),
`CalibrationSamplesTest` (androidApp), `NpuGateTest`.

## Known limits

- Tune measures at 70 vision tokens, so a FULL phone's first 140-token open compiles in production, not in Tune.
- An NPU build has no CPU reference; the fallback to the generic build then chooses its first backend uncalibrated.
- If the CPU reference itself fails, every other backend fails the agreement check with the same message.
- The backend is picked by image time, not query time. Device queue: measure both.
- Later (Wave 7, A2): limit Tune's sample scan so Tune again does not walk the whole library to pick its samples.
- Tier thresholds are measured on few devices; the memory floor (`MIN_MODEL_RAM_GB`) is inferred and needs a device check.

## Rendering and configuration pace

The existing GPU image calls (including warm-ups) also measure main-thread display gaps; see `model-lifecycle.md`.
The profile stores the maximum-gap decision and both CPU/GPU image medians. The medians seed the corresponding
vision configuration; real per-kind reads replace those seeds. A mixed text-GPU/vision-CPU image initially uses the
CPU benchmark as its estimate, then learns its own pace. No additional hybrid benchmark runs.

Paces persist separately as `speed_by_kind_<text>_<vision>`. Changing configuration resets the live ETA smoothing and
retier window and loads that configuration's book. The planner and trimming use the selected configuration's book;
ETA counts are refreshed against the *current* trimmed plan. The old unlabelled `speed_by_kind` book is ignored,
because it cannot say which delegate produced its measurements. Retiering preserves stall decisions and both medians.
