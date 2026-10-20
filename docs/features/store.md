# Store

## Purpose

One SQLite database (`retriever.db`) holds everything the index knows: the files, their text and vector chunks, two FTS4
keyword tables, small key-value meta, recent queries and stars. `Store` is the only class that talks to it. Settings and
stars are also mirrored to a JSON snapshot, so they survive an index reset or a new phone. This note covers the schema,
how it migrates and how the migrations are verified.

## Mechanism and why

- **SQLDelight, one database `IndexDb`** (`core/build.gradle.kts`), dialect `sqlite-3-25`. minSdk 30 ships SQLite 3.28:
  upsert (3.24) and `remove_diacritics=2` (3.27) work, newer syntax does not. Typed queries live in `Index.sq`; migrations
  are `1.sqm` (v1 to v2), `2.sqm` (v2 to v3) and `3.sqm` (v3 to v4). `MigrationTest` pins the current version.
- **Schema.** `files` (identity, kind, state, attempts, `partial`, `taken_at`), `chunks` (text, int8 vector blob, scale,
  `tail_attempts`; `AUTOINCREMENT` id), `chunk_fts` and `file_fts` (FTS4), `index_meta` (key and value), `recent_queries`,
  `starred`. The `vector_deleted` trigger counts deleted vector rows in meta so the search engine can notice with one read.
  Indexes: state, kind and state, `files_by_modified`, `files_by_size`, `files_by_kind_time` (Home lists, capture-time
  filter, star relinking) and the partial index `chunks_tail` for the deepen pass.
- **Drivers.** Android: `AndroidSqliteDriver` with write-ahead logging switched on in `onConfigure` (the driver does not
  expose it), in `androidApp/.../di/AppModule.kt`. iOS: `NativeSqliteDriver` with four reader connections in WAL mode
  (`IosStore.kt`). WAL lets the UI read while the indexer holds a write transaction.
- **FTS4, not FTS5.** Android's framework SQLite builds FTS3 and FTS4 and the app bundles no SQLite. Tokenizer: unicode61
  with `remove_diacritics=2` and a generated `tokenchars` list of combining marks, so Indic and Southeast Asian words stay
  whole. Ranking is BM25 in Kotlin from `matchinfo`; `ORDER BY rowid DESC LIMIT` is answered by the FTS module itself.
- **Vectors as blobs in `chunks`, scanned in memory.** `SearchEngine.refresh` owns the in-memory `VectorTable` and loads
  only rows above the last id it saw. That relies on `AUTOINCREMENT` (ids never go back), on `setChunkVector` giving a
  deepened chunk a new id, and on the deleted-vector counter and the `taken_at_backfills` meta key (more than 10%
  deleted, or any backfill, means a full rebuild).
- **Settings and stars: no second database.** Prefs live in `index_meta['prefs']` and stars in `starred`, inside
  `retriever.db`, which is kept out of backups. Every change is mirrored to `user-state.json` (Android `AtomicFile` in
  `filesDir`; iOS beside the index directory as `<dir>-user-state.json`). The snapshot is read back into a database that
  has no prefs row yet (`restoreUserState`). Prefs are read inside SQL transactions and stars JOIN `files`, so DataStore
  or a second database would add plumbing for no gain.
- **Reset is one statement set.** `resetForModel` drops every chunk and queues every file again under the new
  `Signature`. `replaceChunks` checks the signature and a caller-supplied `stillCurrent` inside its transaction.
- **After a schema bump that recreates the FTS tables** (`2.sqm`), the tables start empty and a meta flag
  `fts_rebuild` makes `rebuildFtsIfPending` refill them once after the open, off the migration (150k chunks take seconds).
- **Verifier.** SQLDelight's `verifyCommonMainIndexDbMigration` replays every later `N.sqm` on the snapshot
  `core/src/commonMain/sqldelight/databases/N.db` and diffs the result against `Index.sq`. `generateCommonMainIndexDbSchema`
  writes the next snapshot. `verifyMigrations` stays off: it compiles the `.sqm` files from an empty schema, and `1.sqm`
  alters tables that no migration creates (version 1 was `Index.sq` itself). To add a migration: write `N.sqm`, change
  `Index.sq` to match, run `generateCommonMainIndexDbSchema` for the old version's snapshot if it is missing, run
  `verifyCommonMainIndexDbMigration`, then add a case to `MigrationTest`.

## Owner files

- `core/src/commonMain/sqldelight/app/retriever/core/db/Index.sq`: schema and every query
- `core/src/commonMain/sqldelight/app/retriever/core/db/1.sqm`, `2.sqm`, `3.sqm`: migrations
- `core/src/commonMain/sqldelight/databases/3.db`: schema snapshot used by the verifier
- `core/src/commonMain/kotlin/app/retriever/core/Store.kt`: the only DB access, plus `VectorTable`, BM25, `retryingMatch`, user-state mirror
- `core/src/commonMain/kotlin/app/retriever/core/UserState.kt` and `Prefs.kt`
- `androidApp/src/main/kotlin/app/retriever/di/AppModule.kt` and `core/src/iosMain/kotlin/app/retriever/core/IosStore.kt`: drivers
- `core/src/commonMain/kotlin/app/retriever/core/SearchEngine.kt`: `refresh`, `Loaded` (cache owner)

## Invariants

1. **FTS rows are deleted before their chunk rows** (`removeFiles`, `replaceChunks`, `setChunkVector`, the kind deletes).
   Required today because foreign keys are off, and tomorrow if `chunk_fts` becomes external-content.
2. **`ON DELETE CASCADE` on `chunks.file_id` is inert.** Foreign keys are off on both drivers, so every file delete goes
   through `Store.removeFiles`.
3. **`SELECT changes()` is read inside the transaction that wrote** (`upsertFiles`, `relinkStars`). On iOS a bare read can
   go to a reader connection whose `changes()` is not the writer's.
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
  also asserts the query plans use the new indexes and sort nothing).
- `SchemaV3Test`: `generatedTokenchars`, `tokencharsAreCombiningMarksOutsideTheFoldedDiacriticBlocks`,
  `wordsInAbugidasStayWholeAndAccentsStillFold`, `starsAreKeptByUriAndSurviveAModelReset`,
  `aStarOnARemovedFileIsHiddenAndComesBackWithTheFile`, `longUriListsRunInPartsUnderAndroid11sVariableLimit`,
  `kindByStateCountsSumToTheStateAndKindCounts`, `nameOnlyCountsIndexedFilesWithNothingInsideInFullAndTextOnlyMode`,
  `takenAtIsKeptAndNeverQueuesAFileAgain`, `keywordSearchFiltersByCaptureDate`, `newPrefsDefaultWhenAnOlderAppSavedThem`.
- `StoreSearchIndexTest.kt` (class `StoreTest`): `replaceChunksMarksFileIndexed`, `identicalUpsertKeepsIndexed`, `newerUpsertMarksPending`,
  `replaceChunksRejectsOtherSignature`, the keyword-search cases, `removeFilesDropsChunksAndKeywordHits`,
  `resetForModelResetsIndex`, `markFailedKeepsPendingUntilThirdAttempt`, `retrySkippedGivesSkippedFilesFreshAttempts`,
  `recentQueriesKeepTheNewestTenWithoutDuplicates`. `StoreCommonTest` (commonTest) adds the accent, Devanagari and
  file-name keyword cases.
- `UserStateTest`: `aDeletedIndexKeepsStarsAndSettings`, `aStarFindsItsFileUnderANewUriButNotAFileOfAnotherSize`,
  `aStarAddedBeforeTheFirstReadDoesNotOverwriteTheSnapshot`, `aCorruptSnapshotIsIgnoredAndLogsNoName`.
- `DeadVectorsTest`: dead rows hidden, backfill reload, `aThrowingFtsRefillStillLeavesNamesLoaded`.
- Not covered yet: `verifyCommonMainIndexDbMigration` is a Gradle task that nothing in `scripts/verify.sh` or CI runs, so
  run it by hand when `Index.sq` or a `.sqm` changes; `tokenchars` equality between `Index.sq` and `2.sqm` (each is only
  checked against the generator); restore versus a concurrent transaction; a plan-regression test for the other browse
  queries (only the Home list and relink plans are pinned).

## Known limits

- After the v3 upgrade, keyword search is empty until `warmSearch` refills the FTS tables (seconds, one write
  transaction). Names answer meanwhile.
- The keyword lane ranks only the `FTS_CAP` most recent matches (`ponytail:` in `Store.kt`).
- Starred is encoded twice: in-memory file ids for the scan and name lanes, and SQL `uri IN (SELECT uri FROM starred)` for
  the keyword lane. Both come from the same JOIN with `files`, so they agree.
- `chunk_fts` stores every text a second time. Making it external-content (`content="chunks"`) would turn
  `resetForNewModel` and `rebuildFts` into one `'rebuild'` statement; measure `SUM(LENGTH(text))` on a real library
  first. `file_fts` must stay a normal table because `upsertFile` updates `files` before the FTS row.
- `resetForNewModel` runs the `vector_deleted` trigger once per vector row, all in one transaction. Rare (model change),
  but star and prefs writes queue behind it.
- Memory is rows times `DIMS` bytes, with a 2x peak during a full rebuild while a running search still holds the old view.
- Restore is keyed on "no prefs row". If a snapshot write failed (only logged), a deleted star can come back on the next
  launch that restores.
- `pendingFiles` sorts every pending row for each page of 64 files. Fine below about 100k pending files.
- The tail counts read each row to check `tail_attempts`; a covering index (`vector IS NULL` as a column) cuts that from
  tens of milliseconds to a few on a large synthetic library, but must be re-checked on API 30's SQLite 3.28 first.
