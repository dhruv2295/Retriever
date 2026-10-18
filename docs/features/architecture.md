# Architecture across features

## Purpose

Which module, package and file owns each feature, so a change lands in one place. The full map is in `ARCHITECTURE.md`.

## Mechanism and why

- Three units: `:core` (KMP, all logic behind the `Retriever` facade), `:androidApp` (Compose M3, WorkManager, Koin) and
  `iosApp` (SwiftUI over the static `RetrieverCore` framework). Shared logic with native UI is the standard KMP split.
- `:core` has no DI container: collaborators are constructor parameters, composed by Koin on Android
  (`androidApp/src/main/kotlin/app/retriever/di/AppModule.kt`) and by hand on iOS
  (`core/src/iosMain/kotlin/app/retriever/core/RetrieverIos.kt`).
- `ModelHost` (`core/src/commonMain/kotlin/app/retriever/core/ModelHost.kt`) is the single owner of the embedder.
- Android is by feature, one package per screen group. `IndexWorker` keeps its fully qualified name
  (`app.retriever.IndexWorker`) because WorkManager persists it.
- Host tests: `commonTest` and `androidHostTest` run on the JVM; there is no `jvm` target.

## Files

- `ARCHITECTURE.md`, `docs/quality.md`
- `androidApp/src/main/kotlin/app/retriever/IndexWorker.kt`
