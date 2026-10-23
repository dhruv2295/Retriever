# Quality

## The gate

One command runs every check the project relies on:

```
scripts/verify.sh          # default, about 2 minutes
scripts/verify.sh --full   # default plus benchmarks, coverage, inspections, ios-selftest and the UI tests
```

Default layers:

1. One Gradle run: `./gradlew :core:testAndroidHostTest --rerun :core:iosSimulatorArm64Test --rerun :androidApp:testDebugUnitTest --rerun :androidApp:lintDebug :androidApp:lintRelease :androidApp:assembleRelease ktlintCheck`
   (ktlint is Gradle-managed; the format task is `ktlintFormat`, see the root `build.gradle.kts`).
2. `scripts/ios-unit-tests.sh` (unit and UI tests over 3+ simulators; pass `-only-testing`-style names to narrow it)
3. Swift formatter check in check mode: `swift format lint`.
4. Notices check: fails when a group in `:androidApp` `releaseRuntimeClasspath`, a Kotlin library linked into the iOS
   framework (`:core` `iosArm64CompileKlibraries`), or a pin in the iOS `Package.resolved` is missing from
   `THIRD_PARTY_NOTICES.md`.
5. Version check: the Android `versionName` equals the iOS `MARKETING_VERSION`.
6. 16 KB page-size check on release builds (`zipalign -c -P 16` and `check_elf.py`).

`--full` adds `-Pbench`, `-Pcoverage` (a Kover report, no threshold), the headless inspections, `scripts/ios-selftest.sh`
and the iOS UI tests.

Warnings are defects. The build fails on them: lint `warningsAsErrors`, and Kotlin `allWarningsAsErrors`
on every compilation of `:androidApp` and `:core` (Android and iOS, tests included). A dependency-forced deprecation
is a targeted `@Suppress("DEPRECATION")` at its call, with the reason. Swift builds in Swift 6 mode with warnings treated as errors.

The lint baseline is empty: `androidApp/lint-baseline.xml` is deleted and no baseline is configured, so any new
lint issue fails the build. Release builds ship per-ABI APKs (`androidApp-arm64-v8a-release.apk` and
`androidApp-x86_64-release.apk`, no universal APK) via AGP `splits { abi }`, which satisfies `ChromeOsAbiSupport`;
the 16 KB check covers both.

The gate forces only the test tasks (`--rerun`). Compile, lint and R8 outputs are reused, up to date or from the build
cache, when their inputs are unchanged; the warnings-as-errors settings are inputs, so a reused result passed the same
gate. Don't use `--rerun-tasks`: it recompiles everything, Kotlin/Native included. During development, run the tests you
touched and compile the modules you changed. Robolectric tests run in up to 4 forks (`maxParallelForks`).

### Dependency versions

The catalog (`gradle/libs.versions.toml`) holds every version. Lint's version checks are off in the gate; to list newer
versions, run `./gradlew :androidApp:lintDebug -Poutdated`. It turns the checks on (androidApp and, through
`checkDependencies`, `:core`) and fails with one error per outdated library. Expected entries: `litertlm` (pinned, see
its catalog comment) and OkHttp 5 (the constraint stays on 4.x, the line built to keep OkHttp 3's binary API that
ML Kit calls). Gradle itself: `./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sum>`, twice,
with the sum from https://services.gradle.org/versions/current.

`androidApp` constrains two transitive libraries with known CVEs to patched versions: OkHttp (ML Kit
`vision-internal-vkp` asks for 3.0.0) and protobuf-javalite (MediaPipe `tasks-core` asks for 4.26.1). Every class and
member those libraries reference resolves in the constrained versions. Drop a constraint once ML Kit or MediaPipe
depends on a patched version itself.

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

- `pre-commit` runs the formatters on the staged files (`scripts/verify.sh --format-staged`, which calls `ktlintFormat`).
- `pre-push` runs `scripts/verify.sh`.
- `scripts/verify.sh` sets `core.hooksPath` to `.githooks` on its first run.

## Release

`scripts/release.sh`:

- builds `:androidApp:assembleRelease` (up to date right after `verify.sh`), so the mapping matches the tagged commit;
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
- **largeHeap:** decided by measurement on a large index.
- **Fail on Gradle deprecations** (`org.gradle.warning.mode=fail`): once neither plugin emits one. Today AGP 9.4.1 calls
  `Configuration.setVisible` (fixed in AGP 9.5.0, still a release candidate) and KGP 2.4.21 registers the plain enum
  `KotlinNativeBundleArtifactsTypes` as an attribute. Retriever's own scripts emit none (`--warning-mode=all`).
- **Predictive back for the Settings notices overlay:** optional polish.
- **Intent hardening for LaunchQuery:** a length cap, and no recents until the user acts. No real hole today.
- **Privacy manifest `nm -u` re-check:** at release preparation and on every dependency bump.
- **Enhanced Security:** one trial build after the current wave (arm64e may not link with Kotlin/Native and MediaPipe).
- **i18n** (`strings.xml` with plurals, a String Catalog): with a second UI language or at the end of the copy freeze.
- **Real upload signing:** at the first Play or TestFlight upload.
- **Jetpack PDF:** at 1.0 stable.
- **Adaptive list-detail:** with Navigation 3 scenes and a tablet or foldable in the device matrix.
- **Read estimate (WP-P1):** `readEstimate`, `Retriever.estimateAll` and iOS's measured background runs (`BgSpeed`)
  are kept, though no screen shows them yet, until a full read is timed on a device against its own short read and
  `ESTIMATE_CALIBRATED` flips. The live ETA they once fed is gone: no surface showed it.

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
  PendingIntent and exported-component security, `@Throws`, the static framework and linker flags (SQLDelight's plugin
  links `-lsqlite3` into the Kotlin/Native test binaries; Xcode's `OTHER_LDFLAGS` does it for the app), BGTask
  registration, source-set hygiene, and the Compose stability config (`androidApp/stability.conf`, value snapshots
  only).
