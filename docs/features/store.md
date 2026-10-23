# Store

## Purpose

One SQLite database (`retriever.db`) holds everything the index knows: the files, their text and vector chunks, two FTS4
keyword tables, small key-value meta, recent queries and stars. `Store` is the only class that talks to it. Settings and
stars are also mirrored to a JSON snapshot, so they survive an index reset or a new phone. This note covers the schema,
how it migrates and how the migrations are verified.

## Mechanism and why

- **SQLDelight, one database `IndexDb`** (`core/build.gradle.kts`), dialect `sqlite-3-25`. minSdk 30 ships SQLite 3.28:
  upsert (3.24) and `remove_diacritics=2` (3.27) work, newer syntax does not. Typed queries live in `Index.sq`; migrations
  are `1.sqm` to `8.sqm` (v1 to v9). `MigrationTest` pins the current version.
- **Migrations.** `1.sqm`: `files.partial`, re-reads what version 1's caps cut. `2.sqm`: `starred`, `files.taken_at`, both
  FTS tables recreated with the generated `tokenchars` and refilled after the open. `3.sqm`: the Home list indexes.
  `4.sqm`: `chunks.content_text`, `files_by_folder`. `5.sqm`: `chunks.labels` and `visual`, `chunk_fts` gains `labels`.
  `6.sqm`: `files.reach` and `files_by_reach`. `7.sqm`: the duplicate finder's hashes. `8.sqm`: `chunks.content` replaces
  the copy in `content_text`, the `chunks_later` index, and `reach` recomputed for files with a waiting page image or
  moment (6.sqm's rule predates them).
- **Schema.** `files` (identity, kind, state, attempts, `partial`, `taken_at`, `reach`, the duplicate hashes), `chunks`
  (text, int8 vector blob, scale, `tail_attempts`, `labels`, `visual`, `content`; `AUTOINCREMENT` id), `chunk_fts` and
  `file_fts` (FTS4), `index_meta` (key and value), `recent_queries`, `starred`. A chunk's displayable content is its text
  when `content` is 1, else `content_text` (NULL from every writer today), so the words are stored once in the row (and
  once more in `chunk_fts`). The `vector_deleted` trigger counts deleted vector rows in meta so the search engine can
  notice with one read. Indexes: `files_by_state`, `files_by_reach` (kind, state, partial, reach), `files_by_folder`,
  `files_by_modified`, `files_by_size`, `files_by_kind_time` (Home lists, capture-time filter, star relinking),
  `chunks_by_file`, and the partial indexes `chunks_tail` (the deepen pass) and `chunks_later` (the later queue, read
  through a `CROSS JOIN` because there are no statistics).
- **Bulk reads page by id.** `knownFiles`, `knownFingerprints`, `knownUris`, `nameEntries` and `loadVectors` read
  `PAGE_ROWS` (4,000) rows a query, each page past the last id. Android fills a 2 MB `CursorWindow` and, to refill it,
  runs the query again from its first row, so one big result costs quadratic steps (100k files under Robolectric's
  Android 11 SQLite: 1.18 s unpaged, 0.13 s paged). One loop, `paged`, does each of them and the CJK rewrite (500 rows
  and a transaction a page); a short page is the last.
- **Drivers.** Android: `AndroidSqliteDriver` with write-ahead logging and `synchronous = NORMAL` set in `onConfigure`
  (the driver does not expose them), in `androidApp/.../di/AppModule.kt` (`indexDriver`); NORMAL is AOSP's WAL default,
  set so a device configured for FULL does not sync every commit. iOS: `NativeSqliteDriver` with four reader connections
  in WAL mode and `SynchronousFlag.NORMAL` (`IosStore.kt`); Apple's SQLite caps the `-wal` file at 32 KB after a
  checkpoint (its `journal_size_limit`), Android's at 512 KB. WAL lets the UI read while the indexer holds a write
  transaction. A newer build's index (a downgrade) is dropped and made again (`Store.resetSchema`), on Android from
  `onDowngrade`, on iOS from `onCreateConnection` because sqliter 1.3 has no downgrade hook.
- **FTS4, not FTS5.** Android's framework SQLite builds FTS3 and FTS4 and the app bundles no SQLite. Tokenizer: unicode61
  with `remove_diacritics=2` and a generated `tokenchars` list of combining marks, so Indic and Southeast Asian words stay
  whole. CJK runs, which have no spaces, are written as their overlapping two-character pieces (`Cjk.kt`; every FTS write
  goes through `Store.insertFts`, `insertFileFts` and `insertFileFtsForLastRow`, so keep it so). Ranking is BM25 in Kotlin from `matchinfo`;
  `ORDER BY rowid DESC LIMIT` is answered by the FTS module itself.
- **Vectors as blobs in `chunks`, scanned in memory.** `SearchEngine.refresh` owns the in-memory `VectorTable` and loads
  only rows above the last id it saw. That relies on `AUTOINCREMENT` (ids never go back), on `setChunkVector` giving a
  deepened chunk a new id, and on the deleted-vector counter and the `taken_at_backfills` meta key (more than 10%
  deleted, or any backfill, means a full rebuild).
- **Settings and stars: no second database.** Prefs live in `index_meta['prefs']` and stars in `starred`, inside
  `retriever.db`, which is kept out of backups. Every change is mirrored to `user-state.json` (Android `AtomicFile` in
  `filesDir`; iOS beside the index directory as `<dir>-user-state.json`). The snapshot is read back into a database that
  has no prefs row yet (`restoreUserState`). Prefs are read inside SQL transactions and stars JOIN `files`, so DataStore
  or a second database would add plumbing for no gain.
- **Reset is one statement set.** `resetForModel` keeps every chunk's text and labels, clears the vectors, adds a name
  chunk to read files with none, queues again only videos read without frames, and records the new `Signature`.
  `replaceChunks` checks the signature and a caller-supplied `stillCurrent` inside its transaction.
- **After a schema bump that recreates the FTS tables** (`2.sqm`), the tables start empty and a meta flag
  `fts_rebuild` makes `rebuildFtsIfPending` refill them once after the open, off the migration (150k chunks take seconds).
  The refill copies text as it is, so the same call then rewrites the rows that hold CJK; it also does that once when
  `index_meta['fts_tokens']` is not the code's `FTS_TOKENS` (an index written before CJK pieces). SQL skips plain-ASCII
  rows in one pass, so only non-ASCII text reaches Kotlin.
- **Verifier.** SQLDelight's `verifyCommonMainIndexDbMigration` replays every later `N.sqm` on the snapshot
  `core/src/commonMain/sqldelight/databases/N.db` and diffs the result against `Index.sq`. `generateCommonMainIndexDbSchema`
  writes the next snapshot. `verifyMigrations` stays off: it compiles the `.sqm` files from an empty schema, and `1.sqm`
  alters tables that no migration creates (version 1 was `Index.sq` itself). To add a migration: write `N.sqm`, change
  `Index.sq` to match, run `generateCommonMainIndexDbSchema` for the old version's snapshot if it is missing, run
  `verifyCommonMainIndexDbMigration`, then add a case to `MigrationTest`.

## Owner files

- `core/src/commonMain/sqldelight/app/retriever/core/db/Index.sq`: schema and every query
- `core/src/commonMain/sqldelight/app/retriever/core/db/1.sqm` to `8.sqm`: migrations
- `core/src/commonMain/sqldelight/databases/3.db`: schema snapshot the verifier replays every later migration on
- `core/src/commonMain/kotlin/app/retriever/core/Store.kt`: the only DB access, plus `VectorTable`, BM25, `retryingMatch`, user-state mirror
- `core/src/commonMain/kotlin/app/retriever/core/UserState.kt` and `Prefs.kt`
- `androidApp/src/main/kotlin/app/retriever/di/AppModule.kt` and `core/src/iosMain/kotlin/app/retriever/core/IosStore.kt`: drivers
- `core/src/commonMain/kotlin/app/retriever/core/SearchEngine.kt`: `refresh`, `Loaded` (cache owner)

## Invariants

1. **FTS rows are deleted before their chunk rows** (`removeFiles`, `replaceChunks`, `setChunkVector`, the kind deletes).
   Required today because foreign keys are off, and tomorrow if `chunk_fts` becomes external-content. Those paths delete
   the FTS row of every chunk id, including chunks never indexed (no text, no labels): a normal FTS4 table ignores that,
   but an external-content one miscounts and, repeated, reports "database disk image is malformed" (SQLite 3.28). Limit
   every FTS delete to indexed rows before any switch.
2. **`ON DELETE CASCADE` on `chunks.file_id` is inert.** Foreign keys are off on both drivers, so every file delete goes
   through `Store.removeFiles`.
3. **Row counts come from the writing statement** (`upsertFile`, `setTakenAt`, `relinkStars`): the generated mutator
   returns the driver's changed-row count. Never `SELECT changes()`: on iOS a bare read can go to a reader connection.
4. **The signature check and `resetForModel` run under `Retriever.indexLock`,** and `replaceChunks` re-checks inside its
   transaction. A vector is written only under the current signature.
5. **A sync never rewrites a row being read.** `runBatch` syncs under `indexLock` and skips while another sync runs
   (`LibrarySync.syncUnlessRunning`). The only sync that outlives `indexLock` is the sample plan's tail
   (`Retriever.planLocked`), and it writes each row once, the plan's rows first: the reader only reads rows the tail has
   already written and will not touch again.
6. **`IN ?` lists stay under 999 binds (SQLite 3.28).** URI lists go through `inChunks(500)`; the other lists are bounded
   by `CANDIDATES`, the result limit or `Kind.entries`.
7. **Both `files_by_state` and `files_by_reach` (kind, state, partial, reach) are needed.** There is no `ANALYZE` data,
   so no skip-scan; `files_by_reach` serves (kind, state) lookups by its prefix and covers the reading counts.
8. **The `tokenchars` literal is persisted FTS config.** It must be identical in `Index.sq` and every `.sqm` that
   recreates the tables, and changing it needs a migration.
9. **Restore runs once per process, before the first settings read or save,** and only into a database with no prefs row.
   The lazy that does it is never entered from inside a transaction.
10. **Every schema change is a migration with a snapshot,** and `MigrationTest` proves old data survives it.
11. **`files.reach` is what a file's chunks hold** (`refreshReach` in Index.sq, `Store.Reach`). Every Store write that
    changes a file's chunks refreshes it in the same transaction (`replaceChunks`, `setChunkVector`, `setTailAttempts`,
    `splitTailChunk`, and the library-wide `deleteChunksOfKinds` and `resetForModel`), and `upsertFile` zeroes it with
    the chunks it drops. So the reading counts, `filesWithContent`, `nameOnlyCount`, `skippedFiles`,
    `readContentVersions` and `StoredFile.contentRead` never look inside chunks; the counts of the whole library are one
    scan of `files_by_reach`. Added in `6.sqm` (schema 7), which fills it from the chunks already read.
12. **The counts read outside a transaction.** On Android a SQLDelight transaction is `BEGIN IMMEDIATE`, the write lock,
    so counts read inside one would queue the reader's writes. A write landing between two of their statements notifies
    `countChanges` again, so the counts that follow are whole.

## Tests

- `MigrationTest` (androidHostTest): `version1FilesGainPartialAndWhatTheOldCapsCutIsReadAgain`,
  `version2KeepsEveryFileIndexedAndBecomesSearchableInAnyScript`, `version3GainsTheListIndexesAndTheQueriesUseThem` (it
  also asserts the query plans use the new indexes and sort nothing), and
  `version8ChunksKeepTheirWordsOnceAndFilesTheirReachByTodaysRule` (content, snippets and reader text survive, reach
  equals `refreshReach`, and the generated later-queue and star-count SQL plans through `chunks_later` and `starred`).
- `SchemaV3Test`: `generatedTokenchars`, `tokencharsAreCombiningMarksOutsideTheFoldedDiacriticBlocks`,
  `wordsInAbugidasStayWholeAndAccentsStillFold`, `starsAreKeptByUriAndSurviveAModelReset`,
  `aStarOnARemovedFileIsHiddenAndComesBackWithTheFile`, `longUriListsRunInPartsUnderAndroid11sVariableLimit`,
  `kindByStateCountsSumToTheStateAndKindCounts`, `nameOnlyCountsIndexedFilesWithNothingInsideInFullAndTextOnlyMode`,
  `takenAtIsKeptAndNeverQueuesAFileAgain`, `keywordSearchFiltersByCaptureDate`, `newPrefsDefaultWhenAnOlderAppSavedThem`.
- `StoreSearchIndexTest.kt` (class `StoreTest`): `replaceChunksMarksFileIndexed`, `identicalUpsertKeepsIndexed`, `newerUpsertMarksPending`,
  `replaceChunksRejectsOtherSignature`, the keyword-search cases, `removeFilesDropsChunksAndKeywordHits`,
  `resetForModelResetsIndex`, `markFailedKeepsPendingUntilThirdAttempt`, `retrySkippedGivesSkippedFilesFreshAttempts`,
  `recentQueriesKeepTheNewestTenWithoutDuplicates`. `StoreCommonTest` (commonTest) adds the accent, Devanagari,
  file-name and CJK keyword cases (`aCjkWordInsideARunIsFound`, `cjkRowsWrittenWholeAreRewrittenOnce`).
- `UserStateTest`: `aDeletedIndexKeepsStarsAndSettings`, `aNewerIndexIsMadeAgainAndKeepsStarsAndSettings`,
  `aStarFindsItsFileUnderANewUriButNotAFileOfAnotherSize`, `aStarAddedBeforeTheFirstReadDoesNotOverwriteTheSnapshot`,
  `aCorruptSnapshotIsIgnoredAndLogsNoName`.
- `StoreTest` also: `aChunksWordsAreStoredOnceAndStayContentWhenSplitOrEmbedded`, `bulkReadsPageThroughEveryRowOnce`.
  `BulkLibrarySyncTest.aNewFileCostsALookupItsRowAndItsNameRow` counts statements per new file (3).
  `GatedDriverTest.closeWaitsForTheCommitHooksOfAReplacedLaterChunk`.
- Drivers on their platform's SQLite: `IndexDriverTest` (androidApp, Robolectric, API 30 and 35: `synchronous` is 1
  where the device says FULL; a newer index is made again) and `IosStoreTest` (iOS simulator: the `-wal` file shrinks
  after a big write; a newer index is made again).
- `SyncBenchTest` (`-Pbench`): a 100k-file sync's statements per file, allocation, diff snapshot and longest removal
  transaction.
- `DeadVectorsTest`: dead rows hidden, backfill reload, `aThrowingFtsRefillStillLeavesNamesLoaded`.
- `scripts/verify.sh` runs `verifyCommonMainIndexDbMigration` (up to date unless `Index.sq`, a `.sqm` or a snapshot
  changed).
- Not covered yet: `tokenchars` equality between `Index.sq` and `2.sqm` (each is only checked against the generator);
  restore versus a concurrent transaction; a plan-regression test for the other browse queries (only the Home list and
  relink plans are pinned).

## Known limits

- After the v3 upgrade, keyword search is empty until `warmSearch` refills the FTS tables (seconds, one write
  transaction). Names answer meanwhile.
- The keyword lane ranks only the `FTS_CAP` most recent matches (`ponytail:` in `Store.kt`).
- Starred is encoded twice: in-memory file ids for the scan and name lanes, and SQL `uri IN (SELECT uri FROM starred)` for
  the keyword lane. Both come from the same JOIN with `files`, so they agree.
- `chunk_fts` stores every text a second time. Making it external-content (`content="chunks"`) would turn
  `resetForNewModel` and `rebuildFts` into one `'rebuild'` statement, but only after invariant 1's deletes are limited
  to indexed rows; measure `SUM(LENGTH(text))` on a real library first. `file_fts` must stay a normal table because
  `upsertFile` updates `files` before the FTS row.
- `resetForNewModel` rewrites every chunk row in one transaction. Rare (model change), but star and prefs writes queue
  behind it.
- A library-wide removal commits 1,000 files a transaction, so other writes wait for one part (about 0.1 s of 4.7 s at
  100k files on the JVM); FTS4 writes a segment per commit, so the whole removal takes about 1.5x one transaction.
- Memory is rows times `DIMS` bytes, with a 2x peak during a full rebuild while a running search still holds the old view.
- Restore is keyed on "no prefs row". If a snapshot write failed (only logged), a deleted star can come back on the next
  launch that restores.
- `pendingFiles` sorts every pending row for each page of 64 files. Fine below about 100k pending files.
- The tail counts read each row to check `tail_attempts`; a covering index (`vector IS NULL` as a column) cuts that from
  tens of milliseconds to a few on a large synthetic library, but must be re-checked on API 30's SQLite 3.28 first.
