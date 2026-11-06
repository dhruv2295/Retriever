# Retriever

Find photos, screenshots and documents on your phone by their names, contents or a description.
Retriever uses a Kotlin Multiplatform core with native Android (Compose Material 3) and iOS (SwiftUI) apps.
Android is the primary release target.

- Name, document-text, OCR and photo-label search work without the large search model.
- A downloadable EmbeddingGemma 2 model adds search by description and visual similarity.
- Browse folders, star files, find duplicate copies, and open matched pages or video moments.
  People recognition is opt-in. Audio files are currently found by name.

## Privacy

File processing, queries and the search index stay on the device. Search-model downloads need internet;
Android may also download OCR models through Google Play services, and cloud file providers may fetch
selected files. Search models are pinned to a commit and verified with SHA-256 before use.
There is no Retriever account or backend.

## Structure

```text
core/        Shared search, indexing, storage and platform adapters; builds RetrieverCore for iOS
androidApp/  Android UI and background services
iosApp/     SwiftUI app and native Apple adapters (Xcode project)
eval/        Synthetic fixtures and optional relevance experiments
scripts/     Verification and optional iOS simulator tools
```

The two Gradle modules and native Xcode app follow Kotlin's [recommended structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html).
`Retriever` coordinates the shared engines; `Store` owns SQLDelight persistence. Platform sources and
extractors supply files and content. Android uses Koin; Swift constructs its services directly.
No extra feature modules, shared UI module or dependency-injection layer is needed.

Keep source changes, indexed content and search visibility consistent: deleted, revised, excluded or
revoked files must not leave stale searchable content. Preserve database migrations and the persisted
`app.retriever.IndexWorker` class name. The UI calls indexing “processing”; native controls and the
shared mascot honor reduced motion and power saving.

## Build

Use JDK 25 and Android SDK 37 (build tools 37.0.0). The Gradle wrapper and dependency versions are pinned.
Set `ANDROID_HOME` or create an untracked `local.properties` with `sdk.dir=/path/to/android/sdk`.
Android requires Android 11+ and a 64-bit device. The search model needs at least 4 GB RAM and roughly
388 MB of storage for the generic download; chip-specific models are larger.

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
```

For iOS, open `iosApp/iosApp.xcodeproj` in Xcode and run the `Retriever` scheme. For a physical device,
put `TEAM_ID=your-team-id` in the ignored `iosApp/Configuration/Local.xcconfig` and select your signing
identity in Xcode. Keep simulator signing enabled so Keychain entitlements work.

## Check changes

```sh
scripts/verify.sh          # Android/core tests, lint, release APKs, migrations, formatting, notices, 16 KB alignment
scripts/verify.sh --quick  # versions, Kotlin formatting and notices
scripts/verify.sh --ios    # also compile/test the shared iOS code and run Swift unit tests (macOS/Xcode)
```

Use focused existing tests while developing, for example
`./gradlew :androidApp:testDebugUnitTest --tests '*SearchViewModelTest'`.
`--full` adds benchmarks, coverage and bundle ABI validation; combined with `--ios`, it also runs UI tests.
Formatting uses `./gradlew ktlintFormat` and Xcode's `xcrun swift-format`. No Git hooks are installed.
To inspect newer dependency versions explicitly, run `./gradlew :androidApp:lintDebug -Poutdated`.

The iOS tests need the generated local corpus: run `eval/make-corpus.sh` (Chrome, macOS tools), then
`eval/make-media.sh` (ffmpeg). `scripts/ios-unit-tests.sh RetrieverTests/CopyTests` selects a test class;
results are in `build/ios-tests/summary.txt`. Model-backed simulator checks are optional:
`MODELS=/path/to/models scripts/ios-selftest.sh`.

## Release

```sh
./gradlew :androidApp:bundleRelease    # build separately from APK tasks
./gradlew :androidApp:assembleRelease
./gradlew :androidApp:installRelease   # test the optimized build locally
```

Outputs are under `androidApp/build/outputs/`: the app bundle, per-ABI APKs and R8 mapping. CLI release
builds use the debug key so you can install and test the optimized app locally. Use Android Studio's
**Generate Signed Bundle / APK** with your upload key for a store build. Keep that key and the matching
R8 mapping outside Git. Choose the next Android version
code before upload; iOS uses `iosApp/Configuration/Config.xcconfig` for its version and build number.

Before store submission, complete the store privacy disclosures and Android declarations for all-files
access and foreground file processing. Check model download, processing and background recovery on
physical hardware. A successful build alone does not establish store approval or device performance.

## Optional experiments

`eval/src` contains synthetic documents and generated photos, not user data; keep these fixtures stable.
Benchmarks are opt-in with `-Pbench`. The custom vector kernel and ranking defaults have measured
reasons to exist; compare device results before replacing them. Relevance rejection remains disabled:
previous calibration did not establish a reliable cutoff. Small-corpus scores do not prove relevance
on a real library.

To regenerate model dumps, set `MODELS` and `PREPARED` (containing `manifest.json`, `queries.json` and
`library/`) and run `scripts/ios-dump.sh M440 70`. Replay with `:core:testAndroidHostTest --tests
'*RelevanceReplayTest.replayDump'` and the `relevance.dump`, `relevance.data`, and `relevance.out` system
properties; `python3 -I eval/relevance-fit.py build/fit` summarizes the resulting TSVs.

## Licensing

A project license has not been chosen. [Third-party notices](THIRD_PARTY_NOTICES.md) retain the
licenses for dependencies, model weights and adapted code, including Apple's platform-specific terms.
The same notices are included in the app.
