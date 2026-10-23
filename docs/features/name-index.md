# Name index

## Purpose

Find files by their name or folder while the person types, in memory and without the model. It answers the names phase
of every search, feeds the two name lanes of the full search, and decides which file is "named exactly" by the query.
It also covers the first moments after launch: until it is built, the lane falls back to the `file_fts` table.

## Mechanism and why

- **One immutable snapshot over distinct tokens.** Tokens come from `Store.ftsTokens` then `foldLatin` over "name
  folder", the same split FTS uses, so the two lanes agree on what a word is.
  - `tokens`: sorted array. An exact match is a binary search, a prefix is the range that follows it.
  - `postings`: per token, the slots (positions in the entry list) that hold it. Slots are newest first, so recency
    is free inside a match class.
  - `grams`: character trigram to sorted token numbers. Substring candidates are the intersection of the lists of the
    query's trigrams, then a `contains` check on the survivors. A two-character CJK word, too short for a trigram, is
  checked with `contains` against the tokens that hold CJK (`cjk`), since a CJK name is one unspaced token.
- **Four match classes, best first:** `EXACT`, `PREFIX`, `SUBSTRING`, `TYPO` (`MatchClass`). Each query word gets a
  class per slot; a file's class is its worst word's class. `NameHit.exactName` marks an EXACT match whose words are all
  tokens of the file name itself, not just of its folder. Search turns these into the lanes `NAME_EXACT` and `NAME`
  (the rest, in this order; substring and typo hits are marked loose, never strong); see `search-engine.md`.
- **Typos run only when nothing closer matched.** The edit budget is 0 below 3 letters, 1 for 3 to 5, 2 from 6.
  Candidates share enough trigrams with the word (an edit spoils at most 3 trigrams, an adjacent swap 4, so the need
  is `length - 2 - 4 * edits`); a word too short to keep any trigram is compared by length instead. Before the DP, a
  64-bit letter-set mask per token (`letterMask`, built with the index) drops tokens whose character set differs from
  the word's by more than `2 * edits` bits: one edit changes the set by at most two. The check is OSA distance
  (Levenshtein where an adjacent swap is one edit), `withinEdits`, with its rows reused across one query's tokens.
- **Filters before the cap.** `kinds` and the `admit` callback (starred ids, dates) run before `limit`, so a filter
  never empties a full page.
- **Copy on write.** `update(removed, upserts)` returns a new index and leaves the old one valid for searches already
  running. Every update is a full rebuild, marked `ponytail:` in the file; patching postings is the upgrade if batches
  get frequent.
- **Why not a library.** A trie or a Lucene-style fuzzy index would add a dependency and memory for a set of names that
  fits in a few MB of primitive arrays; sorted tokens plus trigrams is the standard shape and sub-millisecond at 50k.

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/NameIndex.kt`: `NameIndex`, `NameEntry`, `NameHit`, `MatchClass`
- `core/src/commonMain/kotlin/app/retriever/core/LibrarySync.kt`: `names`, `loadNames`, patches on sync and removal under `namesLock`
- `core/src/commonMain/kotlin/app/retriever/core/SearchEngine.kt`: `nameLanes`, `names` (the names phase)
- `core/src/commonMain/kotlin/app/retriever/core/Store.kt`: `nameEntries`, `fileKeywordSearch` (the cold fallback)
- `core/src/commonMain/kotlin/app/retriever/core/Fold.kt`: `foldLatin`

## Invariants

1. **Snapshots never change.** `LibrarySync.names` is replaced, never mutated, and only under `namesLock`.
2. **Every word must match; the worst class wins.** If nothing matches all words, the most common word is dropped one at
   a time.
3. **Admit and kinds run before the limit.**
4. **Tokenizer parity with the store.** A name that FTS splits one way is split the same way here.
5. **No typos on very short words.** Below 3 letters only exact and prefix count, and substring for a two-character CJK word.
6. **Folder words count as name tokens,** but `exactName` looks at the file name only, so a folder word pins no file first.
7. **Newest first within a class,** by capture time (taken, else modified), also after an update.

## Tests

- `NameIndexTest` (commonTest): `substringInsideAToken`, `typoFindsTheFile`, `adjacentSwapIsOneTypo`,
  `longWordWithASwapAndATypo`, `exactNameIsTheNameNotTheFolder`, `prefixWhileTyping`, `exactTokenInAnyScript`,
  `accentsFold`, `folderWordsMatchToo`, `everyWordMustMatchAndTheWorstClassWins`, `kindFilterAndOrder`,
  `noTyposForVeryShortWords`, `copyOnWriteSnapshots`, `tokenizerMatchesTheStoreSplit`, `editDistanceBounds`,
  `newestFirstWithinAClassEvenAfterAnUpdate`, `admitRunsBeforeTheCap`, `emptyIndexAndBlankQuery`,
  `typoHitsEqualABruteForceScan` (the prefilters drop nothing a full scan finds), `aTwoCharacterCjkWordInsideAName`.
- `NameIndexBenchTest.fiftyThousandNames` (androidHostTest): 50k names, build at most 300 ms, p95 at most 2 ms, arrays at
  most 8 MB, and each keystroke of a word no name holds (the full typo pass) at most 2 ms. On the M4 that pass went from
  0.66-1.62 ms to 0.16-0.19 ms per keystroke with the letter-set mask.
- `ExactNameTest` and `ScopeBeforeCapTest.nameLanesScopeBeforeTheCap` cover the lanes built from it.
- Method for the numbers: `eval/REPORT.md`, speed section (names p95 well under a millisecond at 50k).

## Known limits

- Every update is a full rebuild, tens of milliseconds at 50k names. Fine for batch-sized changes.
- `arrayBytes` counts the int and reference arrays only. The token strings, the `NameEntry` objects with their name and
  folder strings, and the boxed keys of `grams` are not in it, so real heap use is several times the figure.
- Before the index is built, the fallback reads `file_fts` matches with matchinfo, capped at `FTS_CAP`, with kinds and
  dates in SQL. It is slower than the index (tens of ms for a very common token on a large library).
- Typo matching is per word and bounded by 2 edits (1 for words of 3 to 5 letters), so a word further off is not found.
- Wave 7: A4 (exact name first) is implemented through `NAME_EXACT` and `exactName`.
