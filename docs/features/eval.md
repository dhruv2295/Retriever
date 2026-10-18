# Eval

## Purpose

Measure whether search finds the right file, and how fast, with the real model on a real device, so a change to
ranking, extraction, the model or the scan can be accepted or refused on numbers. The eval is generic: a small
synthetic corpus plus a list of queries, each naming the file it should find. Nothing in the app is fitted to it, and
the relevance-rule fit uses separate public data (see `eval/REPORT.md`).

## Mechanism and why

- **Corpus.** `eval/corpus` is built by `eval/make-corpus.sh` from `eval/src` (HTML rendered to screenshots, PDFs and
  documents with headless Chrome; photos copied from `eval/src/photos`, whose prompts are recorded in `eval/prompts.md` as
  provenance, not as a build step) and by `eval/make-media.sh` (a video whose speech and pictures disagree, two voice
  memos, built with `say` and ffmpeg). The photos are committed because regenerating them would change the measured cosines.
- **Queries.** `eval/queries.json` is a list of `{q, file, kind, tests}`. `file` names the corpus file that should be
  found (null: nothing should match, a no-match query), `kind` is cross-checked against it, and `tests` says what the
  query probes (content, name, ocr, page, hindi, no-match, no-match hindi). The harness takes its query count from the
  file, so adding a query needs no code change; do not write the count into a comment or a doc.
- **Metrics.** `metrics(ranks)` gives recall@1, recall@5 and MRR over queries that have a target. A rank is the file's
  1-based position in the top `RetrievalEval.LIMIT`, null when absent.
- **Harness.** `RetrievalEval.run(corpus, queries, settings)` (shared code, `Eval.kt`) embeds each corpus file once per
  vision-token count, then ranks every query for every `EvalSetting` in a grid (`evalGrid`: dimensions, int8 or float,
  prefixes, vision tokens, RRF constant, keyword weight). It reuses the engine's own lane cut (`CANDIDATES`) and
  fusion, so the eval ranks as production does. `EvalRun.report` prints the table per kind and a verdict against the
  default row: recall@5 up by two or more queries, MRR not down, no kind down by more than one query. It also reports the
  production search (`Retriever.search`) on the same queries.
- **Where it runs.**
  - iOS simulator: launch the debug build with `-eval` (`EvalMode.swift`, `EvalIos.kt`). It reads
    `Documents/eval/corpus` and `queries.json`, writes `Documents/eval/report.md`, prints `EVAL DONE`, exits. The queries
    file is parked out of Documents while the app indexes, because it names every query and file and would top the
    keyword lane.
  - Android: `DebugActivity` with `--es run corpus|scan|latency|query|stats` (`EvalRunner.kt`), with `queries.json`
    and the corpus pushed to the app's `files/eval` folder. The report goes to logcat (tag `RetrieverBench`, ending in
    `done <run>`) and to `files/eval/report-<run>.txt`. `scan` times the vector scan alone, `latency` times search with the
    model cold, idle and during indexing.
  - JVM: `EvalTest` checks the harness itself with a fake embedder (metrics, weighted fusion, that the default row equals
    the engine, prefixes off feeds raw text, one model open per vision-token count). No device needed.
  - Speed gates: `SearchBenchTest` (150k chunks, scan and lane limits) and `NameIndexBenchTest` (50k names).
- **Re-baseline.**
  1. Run the corpus eval on both platforms with the model the release ships (the memory-heavy M440 vs M740 choice needs a
     device measurement and is in the device queue).
  2. Compare with the last table in `eval/REPORT.md`. A change passes only by the verdict rule above.
  3. Append the new run to `eval/REPORT.md` (setting, per-kind recall@1 / recall@5 / MRR, latency) with the date and the
     build; do not edit old tables.
  4. Change a speed budget only with a measured number and the device it came from.
- **Relevance rule.** The strong/weak rule is fitted and measured separately: `RelevanceReplayTest.replayDump` replays a
  dump (runs only with `-Drelevance.dump=...`), `eval/relevance-fit.py` fits and prints the tables, and `scripts/ios-dump.sh`
  makes the dump. The fit failed its gate, the rule stays off, and no research happens this wave. Method and tables:
  `eval/REPORT.md`, "Relevance rule fit" and its "Reproduce" section (linked, not copied).

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/Eval.kt`: `EvalQuery`, `parseEvalQueries`, `EvalSetting`, `evalGrid`, `RetrievalEval`, `metrics`, `report`
- `core/src/iosMain/kotlin/app/retriever/core/EvalIos.kt` and `iosApp/Retriever/EvalMode.swift`: the iOS run
- `androidApp/src/debug/kotlin/app/retriever/EvalRunner.kt` and `DebugActivity.kt`: the Android runs
- `eval/queries.json`, `eval/make-corpus.sh`, `eval/make-media.sh`, `eval/prompts.md`, `eval/src`, `eval/corpus`
- `eval/REPORT.md`, `eval/relevance-fit.py`, `scripts/ios-dump.sh`
- `core/src/androidHostTest/kotlin/app/retriever/core/SearchBenchTest.kt`, `NameIndexBenchTest.kt`, `RelevanceReplayTest.kt`

## Invariants

1. **The eval ranks as production does.** Same lanes, same cut, same fusion; a divergence is a bug in the eval
   (`EvalTest.defaultRowMatchesSearchEngine`).
2. **Nothing is fitted to the corpus.** A rule, constant or list that only helps these queries is refused.
3. **The queries file never sits in an indexed folder during a run.**
4. **A decision cites a table.** A setting changes only when the verdict row passes, on both platforms where it applies.
5. **Reports are append-only.** New runs add to `eval/REPORT.md`; old numbers stay as the record.
6. **Debug-only.** The runners live in debug source sets and the `-eval` branch is `#if DEBUG`; release builds carry none.

## Tests

- `EvalTest` (core commonTest): `metricsCountQueriesAndAverageReciprocalRanks`, `weightedRrfScalesEachLane`,
  `defaultRowMatchesSearchEngine`, `prefixesOffFeedsTheModelRawText`,
  `eachVisionTokenCountOpensItsOwnModelAndEmbedsImagesOnce`.
- `SearchBenchTest.searchStaysFastAtFiftyThousandFiles`, `keywordLanesRunBesideTheEmbed`; `NameIndexBenchTest.fiftyThousandNames`.
- `RelevanceRuleTest.theFitFailedItsGateSoTheRuleShipsOff` pins the off setting with its provenance.
- `ExactNameTest` and `FusionTest` pin the ranking behaviours the eval's name queries rely on.

## Known limits

- The corpus is small and synthetic. It catches regressions and compares settings; it does not estimate absolute quality
  on a real library, and its no-match queries are few.
- Only the host's own model runs in the Android and iOS evals; a model comparison needs one run per model, which takes
  hours on a simulator.
- Latency numbers from a JVM or an emulator are not phone numbers. Calibrate on a device before using them in copy (the
  indexing hours estimate stays off for the same reason).
