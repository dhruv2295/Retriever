# Retrieval eval (E1): EmbeddingGemma 2 M440 on the iOS simulator

## What this measured

Every query in `eval/queries.json` (31) against the 30-file eval corpus, ranked by the real M440 model through the
app's real extraction (FolderSource, IosExtractor, MediaPipe UniversalEmbedder, CPU). Production fusion is reproduced
in memory by `RetrievalEval` (core/src/commonMain/.../Eval.kt), which uses IndexEngine's own `draftChunks` and
SearchEngine's lane cuts and `rrfMerge`, so 320 settings rank from one set of embeddings per (prefixes, vision tokens)
pair. Simulator CPU times are not a phone speed claim.

- Corpus: 16 PHOTO, 6 SCREENSHOT, 5 PDF, 3 DOC (40 pieces). Queries by expected kind: PHOTO 14, SCREENSHOT 6, PDF 7, DOC 4 (incl. 3 Hindi queries, 2 name queries). No kind mismatches between queries.json and the files. VERIFIED (report header).
- Profile chosen by calibration on the simulator: BALANCED, CPU, 70 vision tokens, 512 max tokens. VERIFIED (log `calibrated: BALANCED CPU vt70 t512`).
- Cells below: recall@1 · recall@5 (queries out of n) · MRR.

## Does the in-memory eval match the app?

| | All (n=31) | PHOTO | SCREENSHOT | PDF | DOC |
|---|---|---|---|---|---|
| In-memory default (d256 int8, prefixes, vt70, k60, kw1.0) | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 |
| Production path: Documents indexed by `AppServices.retriever` (30 files processed, 0 failed), then `retriever.search` | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 |

Per-query ranks agree on **31/31** queries (bar: within one). Every production search used the vector lane. VERIFIED
(run 1, fresh index; run 2 re-searched the same index with the same result). EvalTest also holds the default row, and
a k10/kw2 row, to `SearchEngine.search`'s file order query by query over a store indexed by IndexEngine with the same
fake vectors (jvm and iosSimulatorArm64). VERIFIED.

## Decision: keep every default

Rule: change only if recall@5 rises by at least 2 queries, MRR does not fall, and no kind loses more than one query.
The default already finds 31/31 in the top 5, so recall@5 cannot rise by 2 (max 31 - 31 = 0): **no alternative can
pass on this corpus**, and none did (the report's "vs default" column runs the rule on every row). VERIFIED.

| Factor | Best alternative vs default (fused) | Arithmetic | Cost | Verdict |
|---|---|---|---|---|
| Dimensions / int8 | d512, d768 float: identical (29 · 31 · 0.96). d256 float = d256 int8 except PDF MRR 0.82 vs 0.83 (rounding of one rank-3 tie-break). | r@5 +0, MRR +0.00 | 512: 2x memory and scan, 768: 3x, both force a full re-index through the signature | Keep 256 int8. VERIFIED |
| d128 float | 31 · 31 · 1.00: the two Hindi queries for Document_04.pdf move from ranks 3 and 2 to 1 | r@5 +0, r@1 +2, MRR +0.04 | half the memory, re-index | Fails the rule (r@5 +0). Two phrasings of one file is one case, not two; vector lane alone 128 = 256 at r@5 (30). Shrinking dims on a 30-file corpus says little about a 50k-file phone, where 128 dims crowd more. INFERRED |
| Task prefixes off | 29 · 29 · 0.94 (PHOTO 13 · 13, PDF 6 · 6) | r@5 -2, MRR -0.02, worst kind -1 | none | Keep prefixes. Vector lane alone shows it more: 30 -> 27 at r@5, MRR 0.93 -> 0.88 (d256 int8 vt70). VERIFIED |
| Vision tokens 140 | 30 · 31 · 0.98 (one Document_04 query to rank 1) | r@5 +0, r@1 +1, MRR +0.02 | image embed 1.7x (run 1: 366 vs 212 ms) to 4.1x (run 2: 781 vs 191 ms) on simulator CPU | Keep tiers: 70 for BALANCED/LIGHT, 140 for FULL (fast GPU, 8 GB+). No evidence either way for FULL. VERIFIED numbers; tier call INFERRED |
| Vision tokens 280 | the model refuses to open: "Requested target capacity (280) exceeds maximum available signature length (140)" | - | - | 140 is the M440 export's maximum. VERIFIED |
| RRF k 10 / 30 / 100 | every rank identical to k60 | r@5 +0, MRR +0.00 | none | Keep 60. VERIFIED |
| Keyword weight 0.5 / 2 | every rank identical to 1.0 | r@5 +0, MRR +0.00 | none | Keep 1.0. VERIFIED |
| Keyword weight 0 (vector lane alone, diagnostic) | 28 · 30 · 0.93: the name query "IMG_1007" falls from 1 to 12 | r@5 -1 | - | The keyword lanes earn their place for names. VERIFIED |

Best of all 320 combinations: d128 float, prefixes, vt70, k10, kw1.0 at 31 · 31 · 1.00 (+2 at r@1 from the same two
Hindi queries, and tied with 9 more rows). Picking a best row out of 320 on 31 queries is overfitting; it is listed,
not adopted.

Pinned by `core/src/commonTest/.../DefaultsTest.kt` with these numbers in its comments: `DIMS == 256`,
`SearchTuning() == SearchTuning(60, 1.0)`, Tiers FULL 140 / BALANCED 70 / LIGHT 70, and the Prefix.query /
Prefix.document formats. No default changed, so nothing re-indexes.

## What the eval cannot tell yet

- The corpus saturates: one file per topic, so the vector lane alone puts the right file first for 28-29 of 31
  queries. Fusion settings, dims and vision tokens only differ on the Hindi letter (Document_04.pdf, outranked by the
  Hindi doctor screenshot) and the name query. To measure dims or tokens the eval needs near-duplicate distractors
  (several invoices, receipts, beach photos, lease drafts) and ~100 queries. INFERRED
- Phone timings: queued for the device. The Android debug screen now has "Run corpus eval": push `eval/corpus/**` and
  `eval/queries.json` to `/sdcard/Download/retriever-eval/`, let the app sync, run it, pull
  `/sdcard/Android/data/app.retriever/files/eval/eval-corpus.txt` (same grid with vision tokens 70/140; its default row
  uses 70 because the facade does not expose the stored tier). Not run: no adb in this package.

## Run log

- Simulator Retriever-E1 (iPhone 18 Pro, iOS 27.0), M440 copied into Application Support/models, corpus and queries
  into Documents/eval. App built from a scratch snapshot of the working tree (HEAD's UI shell instead of the
  half-written wave-3 Views, which did not compile at the time), so EvalMode ran on the current core and Swift adapters.
- Run 1 timings (median ms per call): vt70 image 212, text 46; vt140 image 366, text 56.
- Run 2 timings: vt70 image 191, text 69; vt140 image 781, text 106. The full run-2 report follows.

---

## Simulator run

Model embeddinggemma-2-text-vision-440m.litertlm, CPU, tier BALANCED, vision tokens 70, max tokens 512. Production index: DONE; every production search used the vector lane.

Cells: recall@1 · recall@5 (queries) · MRR. Ranks count within the top 20, as search returns them.

### Corpus
3 DOC, 5 PDF, 16 PHOTO, 6 SCREENSHOT files; 31 queries.
- vision tokens 280: the model didn't open (INVALID_ARGUMENT: ERROR: [third_party/odml/litert_lm/runtime/core/embedding_engine_impl.cc:496]
└ ERROR: [third_party/odml/litert_lm/runtime/core/embedding_engine_impl.cc:285]
└ Requested target capacity (280) exceeds maximum available signature length (140).)

### Embed time (median ms per model call)
| Vision tokens | Image piece | Text piece |
|---|---|---|
| 70 | 191 | 69 |
| 140 | 781 | 106 |

### Default
| Setting | All (n=31) | PHOTO (n=14) | SCREENSHOT (n=6) | PDF (n=7) | DOC (n=4) | vs default |
|---|---|---|---|---|---|---|
| d256 int8 · prefixes · vt70 · k60 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | default |
| production path (Retriever.search) | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | agrees on 31/31 ranks |

### Dimensions and int8 (all else default)
| Setting | All (n=31) | PHOTO (n=14) | SCREENSHOT (n=6) | PDF (n=7) | DOC (n=4) | vs default |
|---|---|---|---|---|---|---|
| d128 float · prefixes · vt70 · k60 · kw1.0 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |
| d256 float · prefixes · vt70 · k60 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.82 | 4 · 4 · 1.00 | +0 r@5, 0.00 MRR, worst kind 0: no |
| d512 float · prefixes · vt70 · k60 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.81 | 4 · 4 · 1.00 | +0 r@5, 0.00 MRR, worst kind 0: no |
| d768 float · prefixes · vt70 · k60 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | +0 r@5, +0.00 MRR, worst kind 0: no |
| d256 int8 · prefixes · vt70 · k60 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | default |

### Task prefixes (all else default)
| Setting | All (n=31) | PHOTO (n=14) | SCREENSHOT (n=6) | PDF (n=7) | DOC (n=4) | vs default |
|---|---|---|---|---|---|---|
| d256 int8 · prefixes · vt70 · k60 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | default |
| d256 int8 · no prefixes · vt70 · k60 · kw1.0 | 29 · 29 · 0.94 | 13 · 13 · 0.94 | 6 · 6 · 1.00 | 6 · 6 · 0.87 | 4 · 4 · 1.00 | -2 r@5, -0.02 MRR, worst kind -1: no |

### Vision tokens per image (all else default)
| Setting | All (n=31) | PHOTO (n=14) | SCREENSHOT (n=6) | PDF (n=7) | DOC (n=4) | vs default |
|---|---|---|---|---|---|---|
| d256 int8 · prefixes · vt70 · k60 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | default |
| d256 int8 · prefixes · vt140 · k60 · kw1.0 | 30 · 31 · 0.98 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 6 · 7 · 0.90 | 4 · 4 · 1.00 | +0 r@5, +0.02 MRR, worst kind 0: no |

### RRF k (all else default)
| Setting | All (n=31) | PHOTO (n=14) | SCREENSHOT (n=6) | PDF (n=7) | DOC (n=4) | vs default |
|---|---|---|---|---|---|---|
| d256 int8 · prefixes · vt70 · k10 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | +0 r@5, +0.00 MRR, worst kind 0: no |
| d256 int8 · prefixes · vt70 · k30 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | +0 r@5, +0.00 MRR, worst kind 0: no |
| d256 int8 · prefixes · vt70 · k60 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | default |
| d256 int8 · prefixes · vt70 · k100 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | +0 r@5, +0.00 MRR, worst kind 0: no |

### Keyword weight (all else default)
| Setting | All (n=31) | PHOTO (n=14) | SCREENSHOT (n=6) | PDF (n=7) | DOC (n=4) | vs default |
|---|---|---|---|---|---|---|
| d256 int8 · prefixes · vt70 · k60 · kw0.0 | 28 · 30 · 0.93 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | -1 r@5, -0.03 MRR, worst kind -1: no |
| d256 int8 · prefixes · vt70 · k60 · kw0.5 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | +0 r@5, +0.00 MRR, worst kind 0: no |
| d256 int8 · prefixes · vt70 · k60 · kw1.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | default |
| d256 int8 · prefixes · vt70 · k60 · kw2.0 | 29 · 31 · 0.96 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | +0 r@5, +0.00 MRR, worst kind 0: no |

### Vector lane alone (keyword weight 0): the model's own ranking for each model-side setting
| Setting | All (n=31) | PHOTO (n=14) | SCREENSHOT (n=6) | PDF (n=7) | DOC (n=4) | vs default |
|---|---|---|---|---|---|---|
| d128 float · prefixes · vt70 · k60 · kw0.0 | 29 · 30 · 0.95 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 6 · 7 · 0.89 | 4 · 4 · 1.00 | -1 r@5, -0.02 MRR, worst kind -1: no |
| d128 float · prefixes · vt140 · k60 · kw0.0 | 28 · 30 · 0.93 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | -1 r@5, -0.03 MRR, worst kind -1: no |
| d128 float · no prefixes · vt70 · k60 · kw0.0 | 20 · 26 · 0.74 | 5 · 10 · 0.55 | 6 · 6 · 1.00 | 5 · 6 · 0.77 | 4 · 4 · 1.00 | -5 r@5, -0.22 MRR, worst kind -4: no |
| d128 float · no prefixes · vt140 · k60 · kw0.0 | 21 · 26 · 0.76 | 6 · 10 · 0.58 | 6 · 6 · 1.00 | 5 · 6 · 0.76 | 4 · 4 · 1.00 | -5 r@5, -0.21 MRR, worst kind -4: no |
| d256 float · prefixes · vt70 · k60 · kw0.0 | 28 · 30 · 0.93 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 5 · 7 · 0.82 | 4 · 4 · 1.00 | -1 r@5, -0.03 MRR, worst kind -1: no |
| d256 float · prefixes · vt140 · k60 · kw0.0 | 29 · 30 · 0.95 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 6 · 7 · 0.90 | 4 · 4 · 1.00 | -1 r@5, -0.01 MRR, worst kind -1: no |
| d256 float · no prefixes · vt70 · k60 · kw0.0 | 27 · 27 · 0.88 | 12 · 12 · 0.87 | 6 · 6 · 1.00 | 5 · 5 · 0.74 | 4 · 4 · 1.00 | -4 r@5, -0.08 MRR, worst kind -2: no |
| d256 float · no prefixes · vt140 · k60 · kw0.0 | 27 · 27 · 0.88 | 12 · 12 · 0.87 | 6 · 6 · 1.00 | 5 · 5 · 0.75 | 4 · 4 · 1.00 | -4 r@5, -0.08 MRR, worst kind -2: no |
| d512 float · prefixes · vt70 · k60 · kw0.0 | 28 · 30 · 0.93 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 5 · 7 · 0.81 | 4 · 4 · 1.00 | -1 r@5, -0.03 MRR, worst kind -1: no |
| d512 float · prefixes · vt140 · k60 · kw0.0 | 29 · 30 · 0.94 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 6 · 7 · 0.89 | 4 · 4 · 1.00 | -1 r@5, -0.02 MRR, worst kind -1: no |
| d512 float · no prefixes · vt70 · k60 · kw0.0 | 27 · 27 · 0.88 | 12 · 12 · 0.86 | 6 · 6 · 1.00 | 5 · 5 · 0.75 | 4 · 4 · 1.00 | -4 r@5, -0.08 MRR, worst kind -2: no |
| d512 float · no prefixes · vt140 · k60 · kw0.0 | 27 · 27 · 0.88 | 12 · 12 · 0.86 | 6 · 6 · 1.00 | 5 · 5 · 0.75 | 4 · 4 · 1.00 | -4 r@5, -0.08 MRR, worst kind -2: no |
| d768 float · prefixes · vt70 · k60 · kw0.0 | 28 · 30 · 0.93 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | -1 r@5, -0.03 MRR, worst kind -1: no |
| d768 float · prefixes · vt140 · k60 · kw0.0 | 29 · 30 · 0.95 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 6 · 7 · 0.93 | 4 · 4 · 1.00 | -1 r@5, -0.01 MRR, worst kind -1: no |
| d768 float · no prefixes · vt70 · k60 · kw0.0 | 27 · 27 · 0.88 | 12 · 12 · 0.86 | 6 · 6 · 1.00 | 5 · 5 · 0.75 | 4 · 4 · 1.00 | -4 r@5, -0.08 MRR, worst kind -2: no |
| d768 float · no prefixes · vt140 · k60 · kw0.0 | 27 · 27 · 0.88 | 12 · 12 · 0.86 | 6 · 6 · 1.00 | 5 · 5 · 0.75 | 4 · 4 · 1.00 | -4 r@5, -0.08 MRR, worst kind -2: no |
| d256 int8 · prefixes · vt70 · k60 · kw0.0 | 28 · 30 · 0.93 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 5 · 7 · 0.83 | 4 · 4 · 1.00 | -1 r@5, -0.03 MRR, worst kind -1: no |
| d256 int8 · prefixes · vt140 · k60 · kw0.0 | 29 · 30 · 0.95 | 13 · 13 · 0.93 | 6 · 6 · 1.00 | 6 · 7 · 0.90 | 4 · 4 · 1.00 | -1 r@5, -0.01 MRR, worst kind -1: no |
| d256 int8 · no prefixes · vt70 · k60 · kw0.0 | 27 · 27 · 0.88 | 12 · 12 · 0.87 | 6 · 6 · 1.00 | 5 · 5 · 0.75 | 4 · 4 · 1.00 | -4 r@5, -0.08 MRR, worst kind -2: no |
| d256 int8 · no prefixes · vt140 · k60 · kw0.0 | 27 · 27 · 0.88 | 12 · 12 · 0.87 | 6 · 6 · 1.00 | 5 · 5 · 0.75 | 4 · 4 · 1.00 | -4 r@5, -0.08 MRR, worst kind -2: no |

### Best of 320 combinations (recall@5, then MRR, then recall@1); ten best follow it
| Setting | All (n=31) | PHOTO (n=14) | SCREENSHOT (n=6) | PDF (n=7) | DOC (n=4) | vs default |
|---|---|---|---|---|---|---|
| d128 float · prefixes · vt70 · k10 · kw1.0 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |
| d128 float · prefixes · vt70 · k10 · kw2.0 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |
| d128 float · prefixes · vt70 · k30 · kw0.5 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |
| d128 float · prefixes · vt70 · k30 · kw1.0 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |
| d128 float · prefixes · vt70 · k30 · kw2.0 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |
| d128 float · prefixes · vt70 · k60 · kw0.5 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |
| d128 float · prefixes · vt70 · k60 · kw1.0 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |
| d128 float · prefixes · vt70 · k60 · kw2.0 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |
| d128 float · prefixes · vt70 · k100 · kw0.5 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |
| d128 float · prefixes · vt70 · k100 · kw1.0 | 31 · 31 · 1.00 | 14 · 14 · 1.00 | 6 · 6 · 1.00 | 7 · 7 · 1.00 | 4 · 4 · 1.00 | +0 r@5, +0.04 MRR, worst kind 0: no |

### Per-query ranks (- means not in the top 20)
| Query | File | Kind | Default | Vector alone | Best | Production | Default top 3 |
|---|---|---|---|---|---|---|---|
| dog playing on grass | IMG_1001.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1001.jpg, IMG_1013.jpg, IMG_1015.jpg |
| my cat sleeping on the couch | IMG_1002.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1002.jpg, Screenshot 2026-10-06 at 10.16.06.png, IMG_1005.jpg |
| sunset by the sea | IMG_1003.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1003.jpg, Screenshot 2026-10-01 at 10.11.01.png, Screenshot 2026-10-04 at 10.14.04.png |
| hiking path in the mountains | IMG_1004.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1004.jpg, Notes_C.txt, Screenshot 2026-10-01 at 10.11.01.png |
| cake with candles | IMG_1005.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1005.jpg, IMG_1007.jpg, IMG_1013.jpg |
| something I ate with rice | IMG_1006.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1006.jpg, IMG_1013.jpg, Screenshot 2026-10-01 at 10.11.01.png |
| tea and biscuits | IMG_1007.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1007.jpg, IMG_1005.jpg, IMG_1012.jpg |
| crowded vegetable stalls | IMG_1008.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1008.jpg, Document_03.pdf, IMG_1012.jpg |
| red car parked on the road | IMG_1009.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1009.jpg, Screenshot 2026-10-01 at 10.11.01.png, Screenshot 2026-10-04 at 10.14.04.png |
| whiteboard with Q3 launch and hiring notes | IMG_1011.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1011.jpg, Doc_A.docx, Document_03.pdf |
| shop receipt total 482 | IMG_1012.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1012.jpg, Screenshot 2026-10-03 at 10.13.03.png, IMG_1008.jpg |
| list stuck on the fridge with milk and eggs | IMG_1013.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1013.jpg, Notes_C.txt, IMG_1012.jpg |
| kid's drawing of a house | IMG_1015.jpg | PHOTO | 1 | 1 | 1 | 1 | IMG_1015.jpg, IMG_1013.jpg, Screenshot 2026-10-01 at 10.11.01.png |
| IMG_1007 | IMG_1007.jpg | PHOTO | 1 | 12 | 1 | 1 | IMG_1007.jpg, Screenshot 2026-10-06 at 10.16.06.png, Screenshot 2026-10-04 at 10.14.04.png |
| what did we decide for dinner on Friday | Screenshot 2026-10-01 at 10.11.01.png | SCREENSHOT | 1 | 1 | 1 | 1 | Screenshot 2026-10-01 at 10.11.01.png, Doc_A.docx, IMG_1006.jpg |
| my flight seat | Screenshot 2026-10-02 at 10.12.02.png | SCREENSHOT | 1 | 1 | 1 | 1 | Screenshot 2026-10-02 at 10.12.02.png, IMG_1006.jpg, Screenshot 2026-10-01 at 10.11.01.png |
| how much did I pay Sharma Electricals | Screenshot 2026-10-03 at 10.13.03.png | SCREENSHOT | 1 | 1 | 1 | 1 | Screenshot 2026-10-03 at 10.13.03.png, IMG_1012.jpg, Document_01.pdf |
| will it rain in Pune | Screenshot 2026-10-04 at 10.14.04.png | SCREENSHOT | 1 | 1 | 1 | 1 | Screenshot 2026-10-04 at 10.14.04.png, IMG_1014.jpg, Screenshot 2026-10-01 at 10.11.01.png |
| the app said it couldn't sync my account | Screenshot 2026-10-06 at 10.16.06.png | SCREENSHOT | 1 | 1 | 1 | 1 | Screenshot 2026-10-06 at 10.16.06.png, Screenshot 2026-10-04 at 10.14.04.png, Screenshot 2026-10-01 at 10.11.01.png |
| invoice for the dining table and chairs | Document_01.pdf | PDF | 1 | 1 | 1 | 1 | Document_01.pdf, IMG_1012.jpg, Screenshot 2026-10-01 at 10.11.01.png |
| rent and security deposit for the flat in Pune | Document_02.pdf | PDF | 1 | 1 | 1 | 1 | Document_02.pdf, Screenshot 2026-10-04 at 10.14.04.png, Screenshot 2026-10-05 at 10.15.05.png |
| solar panels cut our power bill | Document_03.pdf | PDF | 1 | 1 | 1 | 1 | Document_03.pdf, Screenshot 2026-10-03 at 10.13.03.png, Scan_0001.pdf |
| water supply off on Sunday morning | Scan_0001.pdf | PDF | 1 | 1 | 1 | 1 | Scan_0001.pdf, IMG_1012.jpg, Screenshot 2026-10-04 at 10.14.04.png |
| school admission application for class six | Document_04.pdf | PDF | 3 | 3 | 1 | 3 | Screenshot 2026-10-05 at 10.15.05.png, Screenshot 2026-10-02 at 10.12.02.png, Document_04.pdf |
| Scan_0001 | Scan_0001.pdf | PDF | 1 | 1 | 1 | 1 | Scan_0001.pdf, Document_04.pdf, Screenshot 2026-10-06 at 10.16.06.png |
| product launch meeting notes | Doc_A.docx | DOC | 1 | 1 | 1 | 1 | Doc_A.docx, IMG_1011.jpg, Document_03.pdf |
| aloo paratha recipe | Doc_B.docx | DOC | 1 | 1 | 1 | 1 | Doc_B.docx, IMG_1006.jpg, Screenshot 2026-10-05 at 10.15.05.png |
| what to pack for the trek | Notes_C.txt | DOC | 1 | 1 | 1 | 1 | Notes_C.txt, IMG_1004.jpg, IMG_1008.jpg |
| डॉक्टर की अपॉइंटमेंट कब है | Screenshot 2026-10-05 at 10.15.05.png | SCREENSHOT | 1 | 1 | 1 | 1 | Screenshot 2026-10-05 at 10.15.05.png, Screenshot 2026-10-01 at 10.11.01.png, Document_04.pdf |
| स्कूल में दाखिले के लिए चिट्ठी | Document_04.pdf | PDF | 2 | 2 | 1 | 2 | Screenshot 2026-10-05 at 10.15.05.png, Document_04.pdf, Screenshot 2026-10-02 at 10.12.02.png |
| आलू पराठा बनाने की विधि | Doc_B.docx | DOC | 1 | 1 | 1 | 1 | Doc_B.docx, IMG_1006.jpg, Screenshot 2026-10-05 at 10.15.05.png |

Best: d128 float · prefixes · vt70 · k10 · kw1.0
