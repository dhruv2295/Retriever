# Retrieval eval: EmbeddingGemma 2 M440 on the iOS simulator

# Android: M740 on the emulator (wave 4)

The debug screen's "Run corpus eval" on emulator-5580 (Android 16, arm64, 8 GB, so `ModelBuilds.variantFor` picks
M740: `embeddinggemma-2-740m.litertlm`), CPU, over the 30-file corpus and E1's 31 queries (before the 11 no-match
queries were added). These are **emulator CPU numbers on a desktop host, not phone speeds**.

| | Result |
|---|---|
| Default ranking (d256 int8, prefixes, vt70, k60, kw1.0) | recall@1 31/31 · recall@5 31/31 · MRR 1.00 (every kind 1.00) |
| vt140 | the same, 31 · 31 · 1.00 |
| No prefixes / vector lane alone | 30 · 30 · 0.97 (one photo query drops) |
| Embed time, median per call | vt70 image 304 ms, text 37 ms; vt140 image 693 ms |
| Production path (the app's index, `Retriever.search`) | median query 89 ms on the 13-query phone list, 66.8 ms on a second run |

VERIFIED from the run's eval-corpus.txt and eval.txt (wave 4 scratchpad). M740 ranks the corpus perfectly, so this
corpus can't separate settings for it either.

**Second run, with the 11 no-match queries** (same emulator, M740, CPU, vt70; after the fixes in this wave). Production
search over all 42 queries: **p50 41.2 ms, p95 71.9 ms, max 118.4 ms**, every search on the vector lane. Per group,
from the int8 cosine table (VERIFIED, scratchpad w4fix/eval-corpus-m740.txt):

| Group | Lowest right file, found by meaning | Highest no-match file | Window |
|---|---|---|---|
| text -> photo | 0.699 ("something I ate with rice") | 0.703 ("car insurance policy" -> red car) | **none: they overlap** |
| text -> screenshot | 0.758 ("my flight seat") | 0.700 ("train ticket to Mumbai") | 0.058 |
| text -> text | 0.748 ("solar panels cut our power bill") | 0.675 (Hindi "chess tournament results") | 0.073 |

So M740 keeps no floor (`RelevanceFloor.measuredFor` returns null): no photo floor can keep every right photo strong
without calling the red car strong for "car insurance policy", and a floor for screenshots and text alone is a
decision for a run on more than one corpus. With no floor every hit is strong, and the app's strong counts match the
eval's on all 42 queries.

# Relevance floor (wave 4)

## What changed and why

Search now says when nothing matches well. Each result is **strong** or **weak**: a file found by a keyword lane
(words in its text, or its name or folder) is always strong; a file found only by meaning is strong when its best
chunk's cosine with the query reaches its kind's floor. `SearchResult.strongCount` counts the strong hits, which come
first; weak hits follow, each group in fused order. The split is applied to the first `limit` fused files, so it
reorders results and never adds lookups. Keyword-only results (no model, or another model's index) are all strong
by the keyword rule. Search by photo keeps every hit strong: there is no image-to-image data yet.

Asking "kids swimming in a pool" of the eval corpus used to return 16 files led by unrelated photos; it now returns
the same files with `strongCount == 0`, so the app can say nothing matches well and still show the nearest.

## Chosen constants (`RelevanceFloor()` in core/src/commonMain/.../SearchEngine.kt)

| Kind group | Lowest right file, found by meaning | Highest no-match file | Window | Floor | Room: right above / no-match below |
|---|---|---|---|---|---|
| PHOTO, VIDEO (text -> photo) | 0.697-0.707 ("something I ate with rice", see below) | 0.691 ("car insurance policy" -> red car photo) | 0.006-0.016 | **0.69** | 0.007 / -0.001 |
| SCREENSHOT (text -> screenshot) | 0.754 ("my flight seat") | 0.703 ("train ticket to Mumbai" -> boarding pass) | 0.051 | **0.73** | 0.024 / 0.027 |
| PDF, DOC, rest (text -> text) | 0.703 (Hindi query for the Hindi school letter) | 0.675 (Hindi "passport renewal form") | 0.028 | **0.69** | 0.013 / 0.015 |

Decision rule: keep every right file strong unless no floor can; within that, push every no-match query to zero
strong hits. Screenshot and text floors sit midway in their windows. VERIFIED (run 2 below).

**The photo floor is under the window, not in it.** Run 2 put it at the 0.70 midpoint. Two later runs on the same
model, vision tokens and backend (the W4-F9 worktree, which still had E1's 1024 px copies of the photos) scored the
same right photo, IMG_1006 for "something I ate with rice", at **0.697**: under 0.70, so it was listed as weak and the
query said nothing matched well (VERIFIED, scratchpad f9/report-before.md and report-after.md, row "something I ate
with rice", Right strong **no**). A photo's size alone moves its cosine by 0.01, as much as the whole window, and real
libraries hold every size. So the floor is 0.69: the right photo stays strong at either size, and the cost is that
"car insurance policy" now shows the red car photo (0.691) as one strong hit. That trade follows the decision rule:
a right file hidden under "Nothing matches well" is the worse mistake. Not re-run on the simulator at 0.69: the
cosines are unchanged by the floor, so the strong counts follow from the per-query table (INFERRED from VERIFIED
cosines): right file strong 30/31 as before, no-match queries with 0 strong 10/11.

**Only where measured.** `RelevanceFloor.measuredFor(signature)` returns these floors for M440 at 70 vision tokens
only. Every other model signature, which means every Android phone with 7 GB or more (M740) and every FULL-tier phone
at 140 vision tokens on either platform, gets no floor: every hit counts as strong, no "Less likely matches" split
and no "Nothing matches well", until a floor run exists for it. The M740 run above found no photo window at all.

**The one trade-off.** "school admission application for class six" (an English query for the Hindi letter
Document_04.pdf, found by meaning only) scores 0.620, below the highest no-match text file (0.675). Keeping it strong
needs a text floor of 0.620 or less, and then 8 of the 11 no-match queries show a strong text file (0.627, 0.621,
0.652, 0.628, 0.675, 0.664, 0.623, 0.652). So it is weak: still listed, at rank 4 as before, below the line. Recall@5
over strong hits is **30/31** (previously 31/31 over all hits, which the floor does not change). VERIFIED

**Why absolute and per kind, not relative or flat.**
- A cut relative to the top cosine cannot empty a no-match query: its top file always passes against itself. With the
  absolute floors, queries with a file have 39 strong hits over 31 queries, and the extras are related (the Q3
  whiteboard photo for "product launch meeting notes", 0.79; the meeting notes for the whiteboard query, 0.727), so a
  relative cut on top had nothing to remove. Not added. VERIFIED
- One flat floor would have to sit above every no-match file (0.703) and at or below the lowest right photo (0.707):
  a 0.004 window, and the Hindi letter (0.703) would fall out too. Text-to-image cosines run in a different band from
  text-to-text ones, so each kind gets its own. VERIFIED
- Name queries embed close to everything ("IMG_1007" scores 0.70-0.74 against unrelated files, 7 strong hits). They
  are found by the name lane anyway; noted, not tuned for. VERIFIED

**Scope of these numbers.** They are int8 dot products as search computes them (`dotInt8`, which can wobble ~0.01
from the float cosine), for **M440, 70 vision tokens, 256 dims, CPU on the iOS simulator**. Re-measure with this
eval if the model, the dims, the vision tokens or the prompt prefixes change. Android GPU numerics and the 140-token
FULL tier are not measured here: a device check. On 16 photos, 6 screenshots and 8 documents, the photo window is
0.016 wide; a bigger library will put files on both sides of it. INFERRED

## Results

| | Default ranking, all hits (n=31) | Right file strong | Recall@5 over strong hits | No-match queries with 0 strong (n=11) |
|---|---|---|---|---|
| Before (floor off, run 1) | 29 · 31 · 0.96 | 31/31 (everything strong) | 31/31 | 0/11 (220 strong hits) |
| After (run 2, photo floor 0.70) | 30 · 31 · 0.98 | 30/31 | 30/31 | **11/11** (0 strong hits) |
| Shipped (photo floor 0.69, from run 2's cosines) | 30 · 31 · 0.98 | 30/31 | 30/31 | 10/11 (1 strong hit: the red car photo) |

Cells in the first column: recall@1 · recall@5 · MRR. MRR rises because weak wrong files now sit below strong right
ones: the Hindi school-letter query moves from rank 2 to 1. The production path (the app's index and
`Retriever.search`) agrees with the in-memory eval on 31/31 ranks and on the strong count of all 42 queries.
EvalTest holds the same equality on fake vectors under the default floor and a low one (jvm and iosSimulatorArm64).
VERIFIED

### Per query (run 2: d256 int8, prefixes, vt70; cosines identical in run 1)

"Best wrong" is the highest cosine among other files of that group; for a no-match query every file is wrong.

| Query | Group | Right | Right found by | Best wrong text->photo | Best wrong text->screenshot | Best wrong text->text | Strong | Right strong | Rank | Production strong |
|---|---|---|---|---|---|---|---|---|---|---|
| dog playing on grass | text->photo | 0.783 | meaning | 0.658 | 0.615 | 0.619 | 1 | yes | 1 | 1 |
| my cat sleeping on the couch | text->photo | 0.774 | meaning | 0.63 | 0.634 | 0.588 | 1 | yes | 1 | 1 |
| sunset by the sea | text->photo | 0.775 | meaning | 0.635 | 0.68 | 0.601 | 1 | yes | 1 | 1 |
| hiking path in the mountains | text->photo | 0.8 | meaning | 0.649 | 0.653 | 0.685 | 1 | yes | 1 | 1 |
| cake with candles | text->photo | 0.8 | meaning | 0.656 | 0.629 | 0.605 | 1 | yes | 1 | 1 |
| something I ate with rice | text->photo | 0.707 | meaning | 0.688 | 0.678 | 0.643 | 1 | yes | 1 | 1 |
| tea and biscuits | text->photo | 0.824 | meaning | 0.687 | 0.652 | 0.653 | 1 | yes | 1 | 1 |
| crowded vegetable stalls | text->photo | 0.792 | meaning | 0.624 | 0.618 | 0.678 | 1 | yes | 1 | 1 |
| red car parked on the road | text->photo | 0.793 | meaning | 0.627 | 0.663 | 0.623 | 1 | yes | 1 | 1 |
| whiteboard with Q3 launch and hiring notes | text->photo | 0.856 | meaning | 0.63 | 0.647 | 0.727 | 2 | yes | 1 | 2 |
| shop receipt total 482 | text->photo | 0.786 | meaning | 0.639 | 0.692 | 0.633 | 1 | yes | 1 | 1 |
| list stuck on the fridge with milk and eggs | text->photo | 0.832 | meaning | 0.658 | 0.65 | 0.67 | 1 | yes | 1 | 1 |
| kid's drawing of a house | text->photo | 0.81 | meaning | 0.624 | 0.591 | 0.579 | 1 | yes | 1 | 1 |
| IMG_1007 | text->photo | 0.681 | keyword | 0.706 | 0.74 | 0.701 | 7 | yes | 1 | 7 |
| what did we decide for dinner on Friday | text->screenshot | 0.795 | meaning | 0.651 | 0.641 | 0.652 | 1 | yes | 1 | 1 |
| my flight seat | text->screenshot | 0.754 | meaning | 0.627 | 0.626 | 0.625 | 1 | yes | 1 | 1 |
| how much did I pay Sharma Electricals | text->screenshot | 0.835 | meaning | 0.691 | 0.645 | 0.678 | 1 | yes | 1 | 1 |
| will it rain in Pune | text->screenshot | 0.877 | meaning | 0.686 | 0.666 | 0.635 | 1 | yes | 1 | 1 |
| the app said it couldn't sync my account | text->screenshot | 0.834 | meaning | 0.552 | 0.643 | 0.594 | 1 | yes | 1 | 1 |
| invoice for the dining table and chairs | text->text | 0.82 | meaning | 0.697 | 0.666 | 0.626 | 1 | yes | 1 | 1 |
| rent and security deposit for the flat in Pune | text->text | 0.795 | keyword | 0.621 | 0.707 | 0.648 | 1 | yes | 1 | 1 |
| solar panels cut our power bill | text->text | 0.745 | meaning | 0.611 | 0.692 | 0.63 | 1 | yes | 1 | 1 |
| water supply off on Sunday morning | text->text | 0.842 | meaning | 0.652 | 0.66 | 0.634 | 1 | yes | 1 | 1 |
| school admission application for class six | text->text | 0.62 | meaning | 0.623 | 0.655 | 0.62 | 0 | **no** | 4 | 0 |
| Scan_0001 | text->text | 0.709 | keyword | 0.656 | 0.699 | 0.707 | 2 | yes | 1 | 2 |
| product launch meeting notes | text->text | 0.814 | keyword | 0.79 | 0.669 | 0.679 | 2 | yes | 1 | 2 |
| aloo paratha recipe | text->text | 0.775 | meaning | 0.673 | 0.627 | 0.577 | 1 | yes | 1 | 1 |
| what to pack for the trek | text->text | 0.85 | meaning | 0.68 | 0.658 | 0.626 | 1 | yes | 1 | 1 |
| डॉक्टर की अपॉइंटमेंट कब है | text->screenshot | 0.785 | meaning | 0.596 | 0.654 | 0.65 | 1 | yes | 1 | 1 |
| स्कूल में दाखिले के लिए चिट्ठी | text->text | 0.703 | meaning | 0.649 | 0.709 | 0.627 | 1 | yes | 1 | 1 |
| आलू पराठा बनाने की विधि | text->text | 0.818 | meaning | 0.673 | 0.662 | 0.625 | 1 | yes | 1 | 1 |
| passport renewal form | no match | - | - | 0.612 | 0.643 | 0.627 | 0 | - | - | 0 |
| car insurance policy | no match | - | - | 0.691 | 0.642 | 0.621 | 0 | - | - | 0 |
| train ticket to Mumbai | no match | - | - | 0.65 | 0.703 | 0.652 | 0 | - | - | 0 |
| guitar lessons on Saturday | no match | - | - | 0.603 | 0.673 | 0.59 | 0 | - | - | 0 |
| chess tournament results | no match | - | - | 0.593 | 0.608 | 0.628 | 0 | - | - | 0 |
| an elephant at the zoo | no match | - | - | 0.633 | 0.595 | 0.599 | 0 | - | - | 0 |
| kids swimming in a pool | no match | - | - | 0.62 | 0.575 | 0.583 | 0 | - | - | 0 |
| पासपोर्ट नवीनीकरण का फॉर्म | no match | - | - | 0.595 | 0.652 | 0.675 | 0 | - | - | 0 |
| मुंबई की ट्रेन का टिकट | no match | - | - | 0.644 | 0.676 | 0.664 | 0 | - | - | 0 |
| कार बीमा पॉलिसी | no match | - | - | 0.681 | 0.637 | 0.623 | 0 | - | - | 0 |
| शतरंज प्रतियोगिता के नतीजे | no match | - | - | 0.6 | 0.635 | 0.652 | 0 | - | - | 0 |

### Per group (run 2)

min(right) counts right files found by meaning only; a keyword match is strong anyway. "Best wrong" on queries with
a file is not a target for the floor: those files are often related (the receipt photo for the payment screenshot).

| Group | min(right) | max(best wrong), queries with a file | max(no-match) | Floor |
|---|---|---|---|---|
| text->photo | 0.707 | 0.79 | 0.691 | 0.70 |
| text->screenshot | 0.754 | 0.74 | 0.703 | 0.73 |
| text->text | 0.62 (0.703 without the English query for the Hindi letter) | 0.727 | 0.675 | 0.69 |

## Corpus and queries for wave 4

- The corpus is rebuilt from the repo: `eval/make-corpus.sh` renders screenshots, PDFs and docs from `eval/src` and
  copies the 16 photos from `eval/src/photos/`, which are **768 px copies** (`sips -Z 768`, JPEG quality 80; 2.9 MB
  in all) of the 1024 px generated originals E1 used (`eval/prompts.md` keeps their prompts as provenance). VERIFIED
- The smaller photos moved one rank: "school admission application for class six" went from rank 3 (E1) to 4 with
  the floor off (run 1); default MRR 0.96 both ways, PDF MRR 0.83 -> 0.82. Every photo query still ranks its photo
  first. VERIFIED
- `eval/queries.json`: the 31 E1 queries plus 11 with no file (7 English, 4 Hindi), on things absent from the corpus
  (passport form, car insurance, train ticket to Mumbai, guitar lessons, chess results, zoo elephant, swimming pool).
  None shares all its words with a corpus file, so none is keyword-found. Ranking metrics count only the 31 queries
  with a file. VERIFIED

## Run log (wave 4)

- 2026-10-17, simulator **Retriever-F2C** (iPhone 18 Pro, iOS 27.0, E4895127), app built signed from the W4-F2C
  worktree. M440 copied into Application Support/models (`verifyInPlace` hashed it and wrote its `.sha256` marker),
  corpus from `make-corpus.sh` and `queries.json` into Documents/eval, launched with `-eval`. Calibration: BALANCED,
  CPU, vt70, t512; production index DONE, 30 files, 0 failed; every production search used the vector lane.
- Run 1: every floor at -1 (all strong) to collect the cosines. Run 2, fresh install: the chosen floors. The cosine
  columns of the two runs are identical. Run 2 timings (median ms per call): vt70 image 225, text 50; vt140 image 385.
- `SIM_NAME=Retriever-F2C scripts/ios-selftest.sh`: SELFTEST PASS, including the new check that "kids swimming in a
  pool" (the no-match query with the widest gap, 0.62 against the 0.70 photo floor) returns 16 hits, 0 strong.
  Retriever-F2C shut down afterwards.

---

# Search speed at scale (wave 4)

## Setup

`core/src/jvmTest/.../SearchBenchTest.kt`, part of the normal `:core:jvmTest` gate (about 25-35 s in all, 13-24 s of
it building the index). A file-backed SQLite index built through `Store` in one transaction: **50,000 files** in a
phone's kind mix (55% photos, 15% screenshots, 12% documents, 8% PDFs, 5% videos, 3% other, 2% audio) and **150,000
chunks** (photos and screenshots one each, PDFs 12-13), each 60-120 words drawn from a Zipf vocabulary (eight stop
words on top, 20,000 made-up words below), one chunk in 30 half Hindi, and a random unit int8 vector per chunk. 40
queries: rare words, stop words and other common words, two- and three-word phrases, two 3-4 letter prefixes, two words
found in thousands of file names (`screenshot`, `img`), five kind-filtered queries and one Hindi (`बिजली बिल`). Each
lane runs every query once to warm up, then three times; a query's time is its median, and p50/p95 are over the 40.

- **keyword**: `Store.keywordSearch` + `Store.fileKeywordSearch`, 100 candidates each, as `SearchEngine` asks.
- **vector**: `SearchEngine.scanTop` over the loaded `VectorTable` (150,000 rows, 256 int8 dims), top 100.
- **fused**: `SearchEngine.search`, with a fake embedder returning a fixed vector: both lanes, fusion, the relevance
  floor and the per-hit file lookups.

Machine: Apple M4 Max (16 cores), JVM 17 via Gradle, sqlite-jdbc; shared with other agents' builds (load average
50-350 during the runs, 900 at worst), so single runs vary by up to 2x.

## Results (ms)

| Lane | Before p50 | Before p95 | After p50 | After p95 | p95 limit |
|---|---|---|---|---|---|
| keyword | 24.3 / 37.5 | 295.8 / 338.2 | 14.9 / 10.4 / 10.2 | 45.9 / 34.7 / 36.0 | 150 |
| vector | 8.6 / 12.9 | 8.8 / 21.0 | 9.0 / 8.5 / 8.6 | 9.8 / 9.3 / 8.6 | 50 |
| fused | 29.0 / 31.7 | 287.0 / 392.7 | 26.1 / 21.7 / 35.2 | 50.7 / 48.2 / 136.6 | 200 |

Before: two runs of the same bench on the wave 3 + layer 1 `Store`. After: the three runs the limits were pinned from;
the 136.6 was a load spike (a fourth run: 25.1 / 61.2). The slowest queries before were the stop words and phrases of
them ("to in is" 343 ms, "the" 324 ms); after, the same queries at 35-45 ms.

## What changed and why

The keyword lane ranked **every** FTS match in Kotlin: for "the" that is 149,949 rows, each joined to `chunks` and
`files` only to read its kind, with BM25 computed in the sort comparator. Probing the same index with the sqlite3 CLI
for "the*": the matches with `matchinfo` cost 170-300 ms; the join through the wide `chunks` rows (text and vector
in each) 0.41 s warm and 1.67 s from a cold page cache; the newest 5,000 matches without the join 16 ms.

1. **A cap on ranked matches**, `Store.FTS_CAP` = 5,000: `searchFts` takes the newest 5,000 matches
   (`ORDER BY rowid DESC LIMIT`, which FTS4 serves in docid order without sorting). Every query matching fewer
   chunks, which is every query on the eval corpus, gets the same matches and scores as before; only chunks with
   equal scores now come newest first instead of oldest first. **What it gives up**: a word in more
   than 5,000 chunks is ranked among the 5,000 most recently indexed of them (new and changed files, and text the
   deepen pass has embedded since), so its best BM25 chunk in an older file can be missed. BM25's document counts
   and average length still come from the whole index, so scores within the cap are unchanged. The `ponytail:`
   comment in `Store.keywordSearch` names this ceiling.
2. **No join unless a kind filter asks for one**. Unfiltered searches read only the FTS table.
3. **The kind filter before the cap, without reading chunk rows**: `searchFtsOfKinds` keeps matches whose id is in
   the chunk ids of files of those kinds. The ids come from the existing `chunks_by_file` index, which covers
   (file_id, id), so the filter never touches the wide chunk rows; `+rowid` stops SQLite from turning the set into
   per-id FTS lookups. "the" among screenshots (7,500 of 150,000 chunks): 0.27 s warm and 3.8 s cold through the
   join, 0.04-0.07 s warm and 0.37-0.52 s cold through the id set. A PDF-only search for a common word now finds PDFs
   even when 5,000 newer chunks of other kinds match (`StoreTest.keywordSearchRanksTheNewestMatchesAndFiltersKindsBeforeTheCap`).

No schema change: the index the kind filter needs already existed. Scoring each match once, rather than in the sort
comparator, was tried on `fileKeywordSearch` ("img", 27,500 names) and measured no change (28 -> 30 ms), so that
function is unchanged.

**Not changed, by measurement**: the vector scan is 8.5-9.8 ms at p95 with p95 within 10% of p50, so neither a new
int8 layout nor scanning in parallel slices is justified by these numbers. That holds for the JVM on an M4; the phone
numbers below decide it there.

## Thresholds

`SearchBenchTest` fails when a lane's p95 is over its limit: about 3x the worst p95 of the three runs above, never
under a floor so a loaded CI machine does not flap. keyword 150 ms (3 x 45.9 = 138), vector 50 ms (3 x 9.8 = 29, the
floor), fused 200 ms (3 x 61.2 = 184). The old keyword path (p95 296-338 ms) and fused (287-393 ms) fail them.

## Device checks still to do

These are JVM numbers on a desktop chip. On a phone, measure: the query embedding (the M440 on CPU/GPU, absent here
behind a fake embedder), the int8 scan on ARM under ART and Kotlin/Native (whether either vectorises `dotInt8`; if a
150,000-row scan passes about 50 ms, scan in parallel slices on `Dispatchers.Default`), cold-cache keyword searches
on flash, and search latency while an index batch is writing.

# E1 (wave 3): the ranking defaults

Below is E1's report as written: 31 queries, the 1024 px photos, and no floor.

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
  uses 70 because the facade does not expose the stored tier). Run in wave 4 on the emulator: see "Android: M740 on
  the emulator (wave 4)" at the top.

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
