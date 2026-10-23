# Library sync (sources and the file list)

## Purpose

Keep the `files` table, and the in-memory `NameIndex`, equal to what is on the phone, cheaply and repeatably: list files,
write only what changed, remove only what vanished where the scan could see, and never touch the network. Reading a
file's content is `extraction.md`; this note stops at the row.

## Mechanism and why

| Source | Mechanism | Why it is the accepted one |
|---|---|---|
| MediaStore (Android) | `MediaStore.Files` per external volume. Incremental by `GENERATION_MODIFIED > token`; the token is `getVersion:getGeneration` per volume; a changed version or access level forces a full scan. Deletions come from listing all `_ID`s. | The documented change-detection API (API 30+). MediaStore has no deletion feed, so listing ids is the standard diff. Adapted from Gallery `PhotoLibraryService` and platform-samples `MediaStoreQuery` (Apache-2.0). |
| SAF (Android, no all-files access) | `DocumentsContract.buildChildDocumentsUriUsingTree`, a depth-first walk of each persisted tree, every sync; de-duplicated against MediaStore by `mediaKey`. The walk checks cancellation per directory. | SAF has no change feed; `DocumentsContract` queries are far cheaper than `DocumentFile`. |
| PhotoKit (iOS) | `PHPhotoLibrary.fetchPersistentChanges(since:)` for inserts, updates and deletes; a full `PHAsset.fetchAssets` on token expiry. Identity is the creation date, or the modification date once the asset has adjustments. A new or changed asset's name and type come from `extendedMetadata` (prefetched with the fetch) and `contentType` on iOS 27, from its resources (`assetResources(for:)`, one call per asset) before. | Apple's persistent change history (iOS 16+) is the supported incremental API and reports deletions. |
| Folders (iOS) | Security-scoped bookmarks and `FileManager.enumerator`, full walk each sync. iCloud placeholders are reported present and never downloaded. | The standard for user-picked folders; there is no change feed. |
| `CompositeSource` | One token per child (a JSON map); presence and scope are reported only for children that listed everything. | An incremental Photos sync can never delete folder files, and an unmounted volume keeps its rows. |
| `LibrarySync` | Token plus a daily full scan as a safety net; a full scan is forced on an excludes change or a rules-version change; removals are limited to the scan's `scope`; writes happen under `namesLock`. `SourceFile` mirrors the stored columns, so equality is the change test (kind, mime and taken-at count). A full scan compares against `Store.knownFingerprints`, a 64-bit FNV-1a hash of each stored row's columns by URI (15.8 MB instead of 37.9 MB of rows at 100k files, JVM bench); each kept file leaves the map and what remains is removed. An incremental sync looks up only what changed in one bulk read (`Store.filesByUris`), and diffs presence by paging the stored URIs, holding only the vanished ones. Removals commit 1,000 files a transaction, like upserts; an exclusion is applied with an exact `files_by_folder` range query. One sync at a time (`syncLock`, scan to last write); `syncUnlessRunning` returns null while one runs, so a batch reads instead of waiting. A full scan hands its files to `first` before writing (the sample plan writes its rows and sets the plan), then writes the rest 1,000 rows a transaction. | Row upserts also have a SQL `WHERE` guard, so unchanged rows are not rewritten. Writing the plan first takes the whole upsert (976-987 ms of a 1.05-1.13 s plan at 26,248 files, JVM bench) out of the first run's tap-to-reading gap. |
| Triggers | Android: the WorkManager content-URI trigger `index-media` (`background-android.md`). iOS: the `PHPhotoLibraryChangeObserver` wakes the loop (`background-ios.md`). | Both survive better than an in-process observer. |

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/LibrarySync.kt`
- `core/src/commonMain/kotlin/app/retriever/core/CompositeSource.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Adapters.kt` (`FileSource`, `Changes`, `SourceFile`)
- `core/src/commonMain/kotlin/app/retriever/core/FileRules.kt` (kinds, rules version, exclusions)
- `core/src/commonMain/kotlin/app/retriever/core/NameIndex.kt`
- `core/src/commonMain/kotlin/app/retriever/core/Store.kt` (`upsertFiles`, `removeFiles`, `knownFingerprints`, `knownUris`, `urisInFolder`, `filesByUris`)
- `core/src/commonMain/sqldelight/app/retriever/core/db/Index.sq`
- `core/src/androidMain/kotlin/app/retriever/core/android/MediaStoreSource.kt`
- `core/src/androidMain/kotlin/app/retriever/core/android/SafSource.kt`
- `core/src/iosMain/kotlin/app/retriever/core/Bridges.kt` (`KnownFiles`, `asFileSource`)
- `iosApp/Retriever/Core/PhotosSource.swift`
- `iosApp/Retriever/Core/FolderSource.swift`
- `androidApp/src/main/kotlin/app/retriever/access/AccessService.kt` (what the app may read)

## Invariants

1. A token is saved only after the rows it covers are written, and it is read before the query, so a change landing during a
   scan is seen next time.
2. Files outside the scan's `scope` are never removed (an unmounted card, an unreachable folder, an unanswered Photos prompt).
3. Unchanged rows are not rewritten; a changed row loses its old chunks in the same transaction.
4. The in-memory `NameIndex` equals what `Store.nameEntries()` would build, including kind, mime and taken-at changes.
5. A folder excluded during a scan is not brought back (the scan restarts instead).
6. Every source URI starts with its child's prefix.
7. Nothing is downloaded from iCloud during a scan.
8. All file removals go through `removeLocked` and `Store.removeFiles`, 1,000 a transaction; stars stay with their file.
9. The fingerprint covers exactly the columns `SourceFile.equals` compares besides the URI.

## Tests

`LibrarySyncTest`, `BulkLibrarySyncTest`, `ProductStoreTest`, `IndexPipelineTest`, `CompositeSourceTest`, `ExclusionTest`, `SampleModeTest`,
`FileRulesTest`, `NameIndexTest` (core); `AccessTest`, `ReadingRulesTest` (androidApp). iOS sources have no XCTest; the
on-device `SelfTest.swift` is the only check.

## Known limits

- MediaStore has no deletion feed: each incremental sync lists every `_ID`, at most once a minute by the trigger delay.
  Rate-limiting that presence diff (deleted files stay findable meanwhile) is the owner's call.
- SAF folders and iOS folders are walked in full every sync.
- The daily full scan re-enumerates all assets on iOS.
- PhotoKit has no public byte size, so iOS photo and video sizes are 0. That keeps them out of size-based Duplicates; decide
  whether iOS Duplicates covers folder files only, or gives `ph://` a size proxy. Open.
- Later (Wave 7, A9): work that grows with the library on every sync and every Home refresh.
- Device queue: add instrumented checks for a MediaStore version change, an ejected volume keeping its token, and a now
  hidden row going to `removed`.
