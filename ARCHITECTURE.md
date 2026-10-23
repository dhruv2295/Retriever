# Architecture

Retriever searches a phone's files by what is inside them, on the device. This page describes the code as it is in the
working tree. Paths are from the repo root; `core/` means `core/src/commonMain/kotlin/app/retriever/core/`.

## Modules

| Module | Holds | Source |
|---|---|---|
| `:core` | Kotlin Multiplatform: all logic, one `Retriever` facade. Targets an Android library (host tests: `commonTest` and `androidHostTest` run on the JVM), `iosArm64`, `iosSimulatorArm64` | `core/build.gradle.kts`, `settings.gradle.kts` |
| `:androidApp` | Compose Material 3 UI, WorkManager worker, Koin graph | `androidApp/src/main/kotlin/app/retriever/` |
| `iosApp` | SwiftUI app (Xcode project, not a Gradle module). Links the static `RetrieverCore` framework built from `:core` | `iosApp/Retriever/`, `core/build.gradle.kts` (`baseName = "RetrieverCore"`) |

Package map. `:androidApp` is by feature under `androidApp/src/main/kotlin/app/retriever/`: `access`, `browse`, `diagnostics`,
`di`, `duplicates`, `folders`, `home`, `indexing`, `model`, `motion`, `nav`, `search`, `settings`, `setup`, `starred`,
`status`, `ui`, `viewer`; each screen sits with its ViewModel. `IndexWorker` stays at the package root on purpose:
WorkManager stores the worker's fully qualified class name (`app.retriever.IndexWorker`) in its database, so moving or
renaming it strands queued work. `:core` is one package, `app.retriever.core`, one file per feature
(`core/Retriever.kt` the facade; `IndexEngine`, `LibrarySync`, `SearchEngine`, `NameIndex`, `QueryParser`, `Store`,
`Calibration`, `Duplicates`, `Eval` and so on) and `core/motion/MascotDirector.kt`. `ModelHost` (`core/ModelHost.kt`) owns the one
embedder: open, lock, backend fallback, release. `Retriever` takes it as a collaborator. Feature notes are in
`docs/features/README.md`.

Platform code in `:core` lives beside the shared code: `androidMain/.../core/android/` (MediaStore and SAF sources,
MediaPipe embedder, extractor), `iosMain/.../core/` (Swift bridges, SQLite store, device probe). Swift supplies the
native pieces (Photos, Files, MediaPipe, text extraction) as `Native*` interfaces that `createRetriever` wraps
(`core/src/iosMain/kotlin/app/retriever/core/RetrieverIos.kt:30`).

## The pipeline

source -> extract -> chunk -> embed -> store -> search. `Retriever` owns the order and the locks.

1. **Source.** `FileSource.changes(since)` returns upserts, removals and a next token (`core/Adapters.kt:4`).
   `CompositeSource` routes by URI prefix (`core/CompositeSource.kt`; Android `content://media/` and the SAF prefix
   in `androidApp/.../di/AppModule.kt`, iOS `ph://` and `folder://` in `RetrieverIos.kt:48`).
2. **Sync.** `LibrarySync.sync` diffs the source against the store and writes names (`core/LibrarySync.kt:22`).
   Names are searchable here, before anything is read inside.
3. **Extract.** `Extractor.extract(file, profile)` returns `Piece`s (`core/Adapters.kt:28`). Caps are constants in
   `core/Extraction.kt` (`PDF_TEXT_PAGES`, `PDF_OCR_PAGES`, `TEXT_BYTES`); a cap hit appends the `CUT_SHORT` marker, and
   the file is stored `partial` (`core/IndexEngine.kt:126`).
4. **Chunk and embed.** `draftChunks` is the one rule for what a file stores: the first 8 text pieces get vectors,
   later ones wait as keyword-only "tail" chunks, an over-long piece is halved, and a name vector is made only if nothing
   else embedded (`core/IndexEngine.kt:144,162`). The deepen pass (`embedTail`) fills tail vectors later.
5. **Store.** `Store.replaceChunks` writes chunks, their FTS rows and `INDEXED` state in one transaction
   (`core/Store.kt:183`). `IndexEngine` counts an attempt before each file and gives it back on cancel, so a file that
   crashes the process ends `SKIPPED` after 3 tries (`core/IndexEngine.kt:104,131`; `Store.markFailed`).
6. **Search.** `SearchEngine` fuses a vector lane (in-memory `VectorTable` scan), keyword lanes (`chunk_fts`,
   `file_fts`) and a name lane (`NameIndex`) with reciprocal rank fusion (`core/SearchEngine.kt:140,384`). Phase one,
   `searchNames`, answers from names alone with no model (`core/Retriever.kt:511`). With no usable model
   `NoModel` stands in and search is keyword-only (`core/Retriever.kt:803`).

`Retriever.indexBatch(budget)` runs one batch under `indexLock`, then returns a `NextRun` the platform schedules
(`core/Retriever.kt:75,231,833`). Platforms: Android `IndexWorker` (`androidApp/.../IndexWorker.kt`), iOS `IndexTask`
(`iosApp/Retriever/Background/IndexTask.swift`).

## Schema and migrations

The database is `retriever.db` (`Store.DB_NAME`, `core/Store.kt:470`), defined in
`core/src/commonMain/sqldelight/app/retriever/core/db/Index.sq`: `files`, `chunks`, `chunk_fts`, `file_fts`
(FTS4, `unicode61` with generated `tokenchars`), `index_meta`, `recent_queries`, `starred`, and the `vector_deleted`
trigger. Schema version is 3: `1.sqm` and `2.sqm` are the migrations.

- `1.sqm` (v1 to v2): adds `files.partial`, re-queues PDFs and large DOCs the old caps could have cut short, adds
  `files_by_kind_state`.
- `2.sqm` (v2 to v3): adds `starred` and `files.taken_at`, clears `source_token` so one full sync backfills `taken_at`,
  recreates both FTS tables and sets `fts_rebuild`. The tables are refilled after the open by `rebuildFtsIfPending`,
  called last in `Retriever.warmSearch` (`core/Store.kt:463`, `core/Retriever.kt:613`), not inside the migration.

Android runs migrations through `AndroidSqliteDriver.Callback(IndexDb.Schema)` and logs the upgrade time
(`androidApp/.../di/AppModule.kt`, `onUpgrade`); iOS uses `NativeSqliteDriver(IndexDb.Schema, ...)` in WAL mode
(`core/src/iosMain/.../IosStore.kt:29`). A changed embedding model is not a migration: `resetForModel` drops every chunk
and re-queues every file when the stored signature differs (`core/Store.kt:175`, `core/Retriever.kt:273`).

## Dispatchers and scopes

- `CoreDispatchers(default)` is the one injectable dispatcher in core (`core/CoreDispatchers.kt`). `Retriever` runs its
  work on `dispatchers.default` through `io {}` and `withContext` (`core/Retriever.kt:158,723`); `Store.counts()` maps
  its SQLDelight flows on it (`core/Store.kt:356`). Tests pass a test dispatcher.
- Platform adapters that block (file reads, hashing, decoding) use `Dispatchers.IO` or `Default` directly, e.g.
  `core/src/androidMain/.../MediaStoreSource.kt:49`, `core/src/iosMain/.../IosExtractor.kt:16`. `CoreDispatchers` does not
  reach them.
- Android scopes: `AppScope` (`SupervisorJob() + Dispatchers.IO`) for work that outlives a screen
  (`androidApp/.../di/AppModule.kt`); `viewModelScope` in ViewModels; `IndexWorker.doWork` on `Dispatchers.IO`.
- Locks inside `Retriever`: `indexLock` (one batch, or Tune), `embedderLock` (one model copy; `search` takes it with
  `tryLock` and falls back to keywords instead of waiting), `ftsRefill` (one FTS refill) (`core/Retriever.kt:146,589,640`).

## Dependency injection

- **Android: Koin.** `appModule` in `androidApp/.../di/AppModule.kt` builds the whole graph: store, retriever, sources,
  services, ViewModels (`viewModelOf`). Every `single` is lazy, so the first `get` happens off the main thread in
  `RetrieverApp.start` (`androidApp/.../RetrieverApp.kt`). `IndexWorker` takes its dependencies in its constructor:
  `worker { IndexWorker(…) }` in `AppModule.kt`, built by the `KoinWorkerFactory` that `RetrieverApp` gives WorkManager.
  Debug builds swap parts via `DemoMode` (`AppModule.kt`).
- **iOS: `createRetriever`.** Swift's `AppServices` builds the native pieces and calls it once
  (`iosApp/Retriever/Core/AppServices.swift:61`, `RetrieverIos.kt:30`).
- **No Koin in `:core`.** Its dependencies are coroutines, serialization and SQLDelight (`core/build.gradle.kts`
  `commonMain.dependencies`). `Retriever`'s constructor takes every collaborator as a plain parameter
  (`core/Retriever.kt:78`), so both platforms and the JVM tests compose it the same way, and iOS needs no container.

## Who decides what is searchable, and who invalidates

One owner per question:

| Question | Owner | Source |
|---|---|---|
| Is this file in the index at all (source rules, excluded folders, removed files) | `LibrarySync` | `core/LibrarySync.kt` |
| Which kinds are read inside | `Prefs.kinds`, applied per file by `IndexEngine` and by `Retriever.updatePrefs` | `core/IndexEngine.kt:107`, `core/Retriever.kt:195` |
| Is this indexed file in this query's scope (kinds, dates, starred, file ids) | `SearchEngine.Filter` | `core/SearchEngine.kt:360` |

- **Index level.** Source-side skips (`FileRules.shouldSkip`) run in the source (`MediaStoreSource.kt:152`). `sync`
  drops excluded folders, removes vanished files, and forces a full scan when excludes or the source's `rulesVersion`
  changed or a day passed (`LibrarySync.kt:26`). `dropExcluded` applies a new exclusion at once, with no scan
  (`LibrarySync.kt:88`). `removeLocked` is the one way files leave, under `namesLock`, and patches the in-memory
  `NameIndex` (`LibrarySync.kt:103`). `Store.upsertFiles` clears a changed file's old chunks and FTS rows at once, so old
  text never outlives the change (`core/Store.kt:54`).
- **Query level.** `Filter` is the single per-query rule; the file's own comment says no lane adds a filter of its own
  (`SearchEngine.kt:355`). `Retriever.parsedSearch` builds the request and relaxes parsed filters once if they leave
  nothing (`core/Retriever.kt:523`).
- **Invalidation.** Every path that deletes a vector row ends in `DELETE FROM chunks`: `removeFiles`,
  `replaceChunks`, `setChunkVector`, `splitTailChunk`, `deleteChunksOfKinds`, `resetForNewModel` (`Store.kt:78,183,237,219,106`,
  `Index.sq` `resetForNewModel`). The `vector_deleted` trigger bumps `index_meta.vectors_deleted` on each
  (`Index.sq`, trigger). `Store.takenAtBackfills` is the second counter, bumped by `upsertFiles` when a capture day is
  filled in (`Store.kt:74,275`). `SearchEngine.refresh` reads both: it appends new rows by id, and rebuilds the table
  when backfills changed or more than 10% of loaded rows were deleted (`SearchEngine.kt:112`). Between rebuilds, a scan
  that picks a row whose chunk is gone marks it dead and rescans (`SearchEngine.kt:196`). Counts for the UI come from
  SQLDelight query flows (`Store.counts`, `Store.kt:355`).

## User state and backup

Two stores, different lifetimes:

- `retriever.db` is the index. It is rebuilt from the files, so it is excluded from backup. Android:
  `androidApp/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` exclude `retriever.db`, its `-wal`/`-shm`,
  the demo database and `models/`. iOS: the data directory is marked `NSURLIsExcludedFromBackupKey` (`IosStore.kt:28`).
- User state is the settings (`Prefs`) and the stars: a JSON file (`UserState`, `core/UserState.kt`). `Store` writes
  it on every prefs or star change (`setMeta` for the `prefs` key, `setStarred`, `relinkStars`) and restores it once,
  lazily, into a database that has no settings yet (`core/Store.kt:430,434,450`). `UserStateFile` is the platform
  seam: write a temp file, then rename. Android: `files/user-state.json` via `AtomicFile`, backed up
  (`AppModule.kt`, rules files above). iOS: `<dataDir>-user-state.json` beside the data directory, so it is backed up
  while the directory is not (`IosStore.kt:32`). Demo mode passes no file.

## What may log

Core's `log: (String, Throwable?) -> Unit` receives messages that hold no file names, folder names, paths or queries:
kind, MIME, size, exception class, counts (`core/Retriever.kt:327`; `Store` logs the class only,
`Store.kt:441`). `core/src/androidHostTest/.../LogPrivacyTest.kt` fails if a name, folder, query or path reaches the sink.
Platform sinks: Android `LogSink` writes the message plus the exception class and top stack frame in release, never the
exception message (`androidApp/.../diagnostics/LogSink.kt`); iOS logs through `os.Logger` with the message public because core
guarantees it is clean (`AppServices.swift:59,74`). Crash and hang reports on iOS stay on the device in
`problems.json` for the person to share by hand (`iosApp/Retriever/Diagnostics.swift`).

## Checks

`scripts/verify.sh` runs the gate: core JVM and iOS-simulator tests, Android unit tests and lint, iOS unit tests,
formatters, notices and version checks (`docs/quality.md`).
