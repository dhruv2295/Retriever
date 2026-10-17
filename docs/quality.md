# Quality

## The gate

One command runs every check the project relies on:

```
scripts/verify.sh          # default, about 2 minutes
scripts/verify.sh --full   # default plus benchmarks, coverage, inspections, ios-selftest and the UI tests
```

Default layers:

1. `./gradlew --rerun-tasks :core:testAndroidHostTest :core:iosSimulatorArm64Test :androidApp:testDebugUnitTest :androidApp:lintDebug :androidApp:lintRelease`
2. `scripts/ios-unit-tests.sh`
3. Formatter checks in check mode (`swift format lint`, `ktlint`).
4. Notices check: fails when a group in `:androidApp` `releaseRuntimeClasspath`, or a pin in the iOS
   `Package.resolved`, is missing from `THIRD_PARTY_NOTICES.md`.
5. Version check: the Android `versionName` equals the iOS `MARKETING_VERSION`.
6. 16 KB page-size check on release builds (`zipalign -c -P 16` and `check_elf.py`).

`--full` adds `-Pbench`, `-Pcoverage` (a Kover report, no threshold), the headless inspections, `scripts/ios-selftest.sh`
and the iOS UI tests.

Warnings are defects. The build fails on them: lint `warningsAsErrors`, and Kotlin `allWarningsAsErrors`
on `:androidApp` main. `:core` main is warning-clean except `Eval.kt:16` (relevance-fit's file), so its flag is
off: enable after relevance-fit merges. Test sources may keep a dependency-forced deprecation, documented where it
occurs. Swift builds in Swift 6 mode with warnings treated as errors.

Use `--rerun-tasks` only for the final gate. During development, run the tests you touched and compile the
modules you changed.

### Inspections

`verify.sh --full` runs, once per directory (`androidApp`, `core`), with Android Studio closed:

```
nice -n 10 '/Applications/Android Studio.app/Contents/bin/inspect.sh' <repo> \
  <repo>/.idea/inspectionProfiles/Project_Default.xml <repo>/build/inspections/<dir> -v2 -d <repo>/<dir>
```

The profile is the IDE's project default: every default inspection, plus the Compose Preview overrides. `.idea/`
is not tracked, so a fresh clone gets the same profile from Android Studio's defaults. The step fails when a
directory produces no report, because `inspect.sh` exits 0 even when it inspected nothing.

Result, Android Studio 2026.2.1 (AI-262.9437.185), 2026-10-18: **no report.** Every run, before and after a Gradle
sync finished in the IDE (13 s, the model saved), opened the project with `0 modules added`, chose the directory
scope, and closed within a second with an empty output directory. `-e` instead of the profile path fails with
`Tools are not initialized`. So `--full` fails at this step until the headless loader sees the Gradle modules
(retry on each Android Studio update). Until then the gate's coverage of the same ground is Android lint with
`warningsAsErrors` and Kotlin `allWarningsAsErrors`.

Counts: before and after, none (no report). Accepted findings: none.

### Architecture checks

Grep-checked on 2026-10-18 over `androidApp/src/main` and `core/src` main source sets:

- No `android.*` import in `core/src/commonMain`.
- No `GlobalScope`.
- No ViewModel (`SearchViewModel`, `SettingsViewModel`, `DuplicatesViewModel`, `FoldersViewModel`,
  `StatusViewModel`, `BrowseViewModel`, `StarHolder`) takes or holds a `Context`, `View` or `Activity`. The
  `Context` import in `SearchViewModel.kt` belongs to the top-level `photoReader` factory, which Koin calls with
  the application context.
- No `@Composable` lambda is stored outside a composition (the one local, `inputField` in `SearchScreen.kt`, lives
  inside its composable).
- Every launch runs in `viewModelScope`, a `rememberCoroutineScope`, `produceState`, a `WorkManager` worker or the
  Koin `AppScope`. Fixed: `PdfPageScreen` closed its renderer in an orphan `CoroutineScope(pdfDispatcher)`; it now
  uses `AppScope` on the same dispatcher. Accepted: the iOS bridges in `RetrieverIos.kt` (`cancellable`,
  `observeAll`) create a scope per call and hand Swift an `AutoCloseable` that cancels it, so the Swift owner's
  lifetime bounds them.

## Hooks

- `pre-commit` runs the formatters on the staged files.
- `pre-push` runs `scripts/verify.sh`.
- `scripts/verify.sh` sets `core.hooksPath` to `.githooks` on its first run.

## Release

`scripts/release.sh`:

- copies `mapping.txt` to `~/Retriever-releases/<versionName>-<versionCode>/`;
- tags the commit;
- fails if `versionCode` and `CURRENT_PROJECT_VERSION` were not bumped together.

Release builds are signed with the debug key until the first store upload (see the README).

## Standing decisions

### Later (each with its trigger)

- **Navigation 3.** Strongly recommended by the official guidance. First item after the current wave, with a
  navigator unit test. The sealed `Route` and `koinViewModel` already prepare for it. Skip
  `koin-compose-navigation3` while it is experimental.
- **Navigation tests:** with Navigation 3.
- **Kotlin Swift export:** at Beta. It would remove the hand-written bridges, casts and boxed wrappers.
- **detekt 2.0:** when stable, for its coroutine rules (25 `runCatching` sites in main sources).
- **Compose stability config:** only if a device frame metric shows a problem.
- **largeHeap:** decided by measurement on a large index.
- **Predictive back for the Settings notices overlay:** optional polish.
- **Intent hardening for LaunchQuery:** a length cap, and no recents until the user acts. No real hole today.
- **Privacy manifest `nm -u` re-check:** at release preparation and on every dependency bump.
- **Enhanced Security:** one trial build after the current wave (arm64e may not link with Kotlin/Native and MediaPipe).
- **i18n** (`strings.xml` with plurals, a String Catalog): with a second UI language or at the end of the copy freeze.
- **Real upload signing:** at the first Play or TestFlight upload.
- **Jetpack PDF:** at 1.0 stable.
- **Adaptive list-detail:** with Navigation 3 scenes and a tablet or foldable in the device matrix.

### No (each with its reason)

- **Koin on iOS or in `:core`.** It trades compile-checked construction for runtime `declare()` calls, and puts
  koin-core in the IPA for a few lines of wiring. Koin is Android only.
- **Default MainActor isolation.** It hides the Kotlin-thread hazard behind runtime traps.
- **SKIE.** Kotlin upgrades would wait on a third party, and about 30 lines fix the only defect.
- **KMP-NativeCoroutines.** It needs KSP.
- **SwiftLint.** It overlaps the compiler errors and the bundled swift-format.
- **Swift Testing migration.** Two test frameworks side by side, and XCUITest needs XCTest anyway.
- **Trimming the ObjC export surface.** Dead stripping is already on; the only gain would be compile time.
- **App extensions.** An extension cannot load the model within its memory budget.
- **Kermit.** The log seam and the native sinks already exist.
- **Feature modules, build-logic and `:core:testing`.** About 6k lines with one author; each KMP module compiles four targets.
- **Domain layer and use cases.** Retriever is the single facade iOS needs. Split it only when a change requires it,
  or past about 1,000 lines.
- **Package reshuffle.** No dedicated pass. New classes go in `data/` and `di/` only.
- **Paging 3.** The OFFSET ceiling is documented in the code. Move to keyset paging if skipped rows are observed.
- **Startup Profiles and dex layout.** The app has a single dex.
- **koin-androidx-startup.** No provider needs the graph before `onCreate`.
- **Network security config.** The defaults are strict, and the hash check is stronger than pinning.
- **Google oss-licenses and AboutLibraries.** Both are heavier than a checked-in text file.
- **Coil.** It does not fix the thumbnail problem and costs 150-400 KB.
- **AppSearch.** A 3.78 MB duplicate index.
- **LICENSE for Retriever itself.** The owner's call, before any publication.
- **Already right, no change needed:** edge-to-edge and IME, splash, foreground service types, R8 full mode and keep rules,
  PendingIntent and exported-component security, `@Throws`, the static framework and linker flags, BGTask
  registration, and source-set hygiene.
