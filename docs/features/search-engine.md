# Search engine

## Purpose

Take what the person typed (or a photo) and return at most `limit` files, best first, each with the chunk to open
(`chunkId`, `locator`) and a snippet. Everything runs on the device: an int8 vector scan for meaning, SQLite FTS4 with
BM25 for words, and an in-memory index of file names and folders. The names phase answers each keystroke without the
model; the full search follows once the query embed is back. The parser (`query-parser.md`) runs before it and the name
index (`name-index.md`) is one of its lanes. The screens that show the result are in `search-ui.md`.

## Mechanism and why

| Step | Mechanism | Why |
|---|---|---|
| Parse | `QueryParser.parse` turns kind words and date phrases into filters; the rest stays as text | Small, pure, testable. A model-based parser would cost an embed for little gain |
| Names phase | `SearchEngine.names`: name lanes only, no model | Answers within a frame while the person types |
| Meaning lane | Brute-force int8 dot product over a `VectorTable` of primitive arrays in blocks of 16,383 rows, with a size-k min-heap (ties go to the earlier row). From 131,072 rows the scan splits into `min(cores, rows / 65,536)` slices of whole blocks, merged by cosine then row. On iOS a cinterop C kernel (`core/src/nativeInterop/cinterop/vecscan.def`) scores a block at a time; Android keeps the Kotlin loop, which ART vectorises | Exact kNN. At 150k chunks the scan p95 is under 10 ms on the JVM (`SearchBenchTest`), so an ANN index (HNSW) would add memory and code for no visible gain. Blocks mean an append never copies the table (eval/REPORT.md, "Scan in blocks") |
| Keyword lane | `Store.keywordSearch`: FTS4 MATCH on AND-ed tokens, prefix on the last token of 3+ letters. The newest `FTS_CAP` matches are scored by BM25 in Kotlin (matchinfo). It returns chunk ids only: the snippet comes from `content_text` (Snippet row). CJK has no spaces, so the tables hold each run as its overlapping two-character pieces and a query word with CJK is the phrase of its pieces; one CJK character matches as a prefix (`Cjk.kt`, as Lucene's `CJKBigramFilter`) | FTS is the standard SQLite full-text tool. The recency cap is a known ceiling, marked `ponytail:` in `Store.kt` |
| Name lanes | `NameIndex.search` split into two lanes: `NAME_EXACT` (the file's own name) and `NAME`, the rest in NameIndex's order: exact or prefix, then substring and typo matches marked `loose`, which are never strong. Before the index is warm the lane falls back to `file_fts` | Keeps "this is the file's name" separate from weaker evidence. A separate loose lane tied each loose match with a prefix match of the same rank, so a page cut prefix matches for weaker ones (`RelevanceRuleTest.substringNamesNeverPushPrefixNamesPastTheCut`; a 0.5 lane weight still failed at 70 prefix matches). See `name-index.md` |
| Fusion | `rrfMerge`: reciprocal rank fusion, `rrfK` and weight from `SearchTuning`. A file shows its best-ranked chunk, a words chunk wins a rank tie. A file whose own name is an exact match for the query goes first, before the `limit` cut | RRF is the accepted rank-only fusion; it needs no score calibration between cosine and BM25 |
| Strong and weak | Words and exact/prefix names stay visible. For meaning-only hits, the largest score drop after the first three files in each modality separates the tail | The tail is collapsed, never discarded. Absolute no-match confidence remains parked (`RELEVANCE_RULE_ENABLED = false`) |
| Snippet | Only a chunk's content (its text when `chunks.content` is 1, else `content_text`), explicitly supplied by content extractors, can be displayed. `bestSentence` selects content, with the existing query markers; an image can borrow OCR at the same locator. Metadata stays searchable but cannot become a snippet | Both UIs render the markers; migration preserves known legacy document/OCR content and leaves unproven media text hidden |
| Relax | `Retriever.parsedSearch`: if the filters the parser found leave nothing, search once more without them and mark the result `relaxed` | The second search reuses the cached query vector (`lastQuery`), so it embeds once |
| Filters | One `Filter` per query (kinds, dates, starred ids) applied inside every lane before its cap | One rule, no lane adds its own |

Reuse considered: SQLite FTS (kept), no ANN library, no FTS5 (the platform
SQLite is not guaranteed to ship it; matchinfo plus Kotlin BM25 is the shortest path that works everywhere).

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/SearchEngine.kt`: lanes, scan, `Filter`, fusion, snippets, query-vector cache
- `core/src/commonMain/kotlin/app/retriever/core/Vector.kt`: `VectorTable`, `dotInt8`; the iOS kernel in `Vector.ios.kt` and `vecscan.def`
- `core/src/commonMain/kotlin/app/retriever/core/NameIndex.kt`: name index
- `core/src/commonMain/kotlin/app/retriever/core/QueryParser.kt`: parser and `ParsedQuery`
- `core/src/commonMain/kotlin/app/retriever/core/Ranking.kt`: `SearchTuning`, `rrfMerge`, `RelevanceRule`
- `core/src/commonMain/kotlin/app/retriever/core/Store.kt`: `keywordSearch`, `fileKeywordSearch`, `retryingMatch`, `bm25`, `ftsTokens`
- `core/src/commonMain/sqldelight/app/retriever/core/db/Index.sq`: FTS queries
- `core/src/commonMain/kotlin/app/retriever/core/Retriever.kt`: facade (`search`, `searchNames`, `parsedSearch`, `browse`, `searchImage`, `warmSearch`)
- `core/src/commonMain/kotlin/app/retriever/core/LibrarySync.kt`: name index lifecycle (`names`, `loadNames`)
- `core/src/commonMain/kotlin/app/retriever/core/Highlight.kt`: the snippet marks

## Invariants

1. **Filters run before each lane's cap.** Meaning: the kind, UTC-day and file-id checks are inside the scan. Words:
   kinds, dates and starred are in SQL before `LIMIT`. Names: `kinds` and `admit` run before `limit`. `toHits` cuts by
   the exact time afterwards. The words lane checks plain file ids only after its cap, which is safe only because the
   facade always sets file ids and the starred flag together.
2. **There is one filter rule per query**: `Filter`. No lane adds a filter of its own.
3. **Every query word must match, and the worst class wins**, in FTS (implicit AND) and in the name index. The name
   index alone, when nothing matches every word, drops the most common word one at a time (as the store's token retry does).
4. **Name snapshots are immutable.** `NameIndex.update` returns a new index; `LibrarySync.names` is swapped under
   `namesLock`, so a search keeps the snapshot it started with.
5. **The vector view is fixed when it is published.** A scan reads `Loaded.view` and appends go past its size; a new or
   grown block goes into a new block array, never the view's. Dead rows are marked as lookups find them; a full reload
   happens after 10% of rows are deleted or a capture-day backfill.
6. **`byName` is true only when the hit came from a name lane.** A name-only hit has `chunkId = 0` and `locator = ""`
   and opens the file at its start.
7. **`vectorLane` contract.** `vectorLane = false` means the meaning lane did not answer: the model is opening and
   `tryLock` missed, it failed, or the index was built by another model. An empty result with `vectorLane = false` is
   not final while a model is coming. `browse` and an empty starred scope set `vectorLane = true` on purpose. The UI
   half of this rule is in `search-ui.md`.
8. **One chunk per file.** Each file appears once, shown by its best-ranked chunk. A chunk beats a name-only entry; a
   words chunk wins a rank tie.
9. **Exact name first.** A file named exactly by the query is ranked before the others and before the `limit` cut, so
   the names phase and the full search agree on who is first. A folder word pins nothing.
10. **The name tokenizer matches the FTS one** (`ftsTokens` then `foldLatin`).

## Tests

- `NameIndexTest` (commonTest) and `NameIndexBenchTest`: see `name-index.md`.
- `QueryParserTest`: see `query-parser.md`.
- `ExactNameTest`: `anExactNameBeatsWordsAndMeaning`, `theLimitCannotDropTheExactFile`, `aFileNameQueryFindsThatFileFirst`,
  `aFolderWordPinsNothing`, `oneLetterSkipsTheChunkKeywordQuery`.
- `StoreSearchIndexTest.kt` (class `SearchEngineTest`): `mergedSearchRanksSemanticMatchFirst`, `keywordSearchFindsExactNumber`,
  `kindFilterExcludesOtherKinds`, `anotherModelsIndexDegradesToKeywordOnly`, `aClosedEmbedderPropagatesSoTheCallerCanRetireIt`,
  `eachFileAppearsAtMostOnce`, `refreshPicksUpNewFilesAndDropsDeletedOnes`.
- `FusionTest.aLiteralMatchAtEqualRankShowsTheWordsSnippet`, `ScopeBeforeCapTest` (`nameLanesScopeBeforeTheCap`,
  `keywordLaneScopesStarredBeforeTheCap`, `coldNameLaneScopesStarredBeforeItsFtsCap`, `keywordLaneScopesContentBeforeTheCap`), `DeadVectorsTest` (`deletedVectorsDoNotHideLiveOnes`,
  `takenAtBackfillMovesALoadedVectorToItsCaptureDay`), `RecentAndLimitTest.searchHonoursLimit`.
- `RelevanceRuleTest` (`theFitFailedItsGateSoTheRuleShipsOff`, `theShippedSettingCallsEveryHitStrong`,
  `exactAndPrefixNamesAreStrongButASubstringOrTypoAloneIsNot`, `substringNamesNeverPushPrefixNamesPastTheCut`,
  `datesKindsAndFileIdsFilterEveryLane`).
- `SnippetTest`: `aMatchDeepInAnUnpunctuatedRunShows`, `anEarlyMatchOrNoneKeepsTheStart`;
  `RelevanceRuleTest.theSnippetIsTheSentenceSharingTheMostQueryWords` pins `bestSentence`.
- `SearchBenchTest.searchStaysFastAtFiftyThousandFiles`, `keywordLanesRunBesideTheEmbed`: lane limits at scale.
- `VectorScanTest` (commonTest, so also on the iOS simulator): `theNativeKernelScoresBitForBitAsDotInt8`,
  `appendsLeaveEarlierBlocksAndViewsAsTheyWere`, `parallelSlicesFindWhatOneScanFinds`.
- `EvalTest` and the corpus eval: see `eval.md`.

Missing, as far as this note knows: a facade test that a sync swapping `names` mid-search leaves the old snapshot valid
until that search ends; a check that the same snippet renders the same bold spans in `FileRow.kt` and `ResultViews.swift`.

## Known limits

- The keyword lane scores only the newest `FTS_CAP` matches. A common word's best chunk in an old file can be missed.
- Thai, Lao, Khmer and Burmese are also written without spaces, and a run stays one token: a word inside it is found by
  meaning, or by name as a substring. They have no bigram convention, and a dictionary segmenter (ICU) is not available
  in the platform SQLite. A single CJK character at the end of a run is not found by keyword (its pieces start with the
  character before it).
- FTS ANDs all tokens with no stop list. When that finds nothing, `retryingMatch` drops the token in the most chunks
  (fts4aux counts, so no language-specific list) and tries again, down to one token. The prefix (the last token) counts
  every word it starts, summed over an fts4aux term range, so a fragment such as "sea" of "seater" goes before a rarer
  word instead of being the one left (`TokenRetryTest.aPrefixCountsTheWordsItStartsSoAFragmentIsNotKeptOverARarerWord`).
  The sum overcounts a chunk holding two such words; it only orders the drops.
- The day filter in the scan uses UTC days widened by up to one day, so at most 100 candidates can be crowded by rows
  outside the local day.
- A query takes one date phrase (see `query-parser.md`).
- Folder words are name tokens, so "camera" exact-matches every file in that folder. This is deliberate.
- `Hit.score` has no common scale (RRF sum on a merged search, `1/(i+1)` on a single lane, 0 for `browse`). Only debug
  and self-test code reads it.
- FTS5 `bm25()` would replace `Store.bm25`, but the platform SQLite cannot be assumed to ship FTS5. Keep FTS4 and
  matchinfo unless the driver bundles its own SQLite.
- Search and the batch share `Dispatchers.Default`; a query waits behind at most one native embed call
  (`indexing-pipeline.md`, `awaitUi`).
- Wave 7 A4 (exact name first) is the behaviour in invariant 9; the relevance rule stays off and has no further
  research this wave.

Snippets mark whole Unicode words or prefixes only. In a query of several words, words of one or two letters and
function words (`highlightStopwords`: the English stop set, Hindi postpositions and particles) are not marked; the
longer suggestion stop list would also drop words such as "bill" and "home". Names and snippets fold case and Latin
accents, as search does. Match windows dominated by digits and separators yield to the
next readable match window. `SnippetTest` covers Hindi, interior fragments and spreadsheet number runs;
`LikelyMatchesTest` uses synthetic score lists, and the eval shares the production grouping rule.
