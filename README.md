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

Outputs are under `androidApp/build/outputs/`: the app bundle, per-ABI APKs and R8 mapping.
`iosApp/Configuration/Config.xcconfig` holds the version and build number for both apps: Android reads
`MARKETING_VERSION` as `versionName` and `CURRENT_PROJECT_VERSION` as `versionCode`. Bump the build number
there before each store upload, and tag the commit after it, e.g. `git tag -a v0.2-5 -m "Retriever 0.2 (5)"`.

Release builds are signed with the Play upload key when these Gradle properties are set, in
`~/.gradle/gradle.properties` (outside the repo) or as `ORG_GRADLE_PROJECT_<name>` environment variables;
without them they fall back to the debug key, which Play rejects:

```properties
retrieverUploadStoreFile=/Users/you/.android-keys/retriever-upload.jks
retrieverUploadStorePassword=...
retrieverUploadKeyAlias=upload
retrieverUploadKeyPassword=...
```

Create the key once with `keytool -genkeypair -v -keystore ~/.android-keys/retriever-upload.jks -alias upload
-keyalg RSA -keysize 2048 -validity 10000` (it asks for the password; a PKCS12 keystore uses the same one for
store and key). Back it up: it is the upload key, and Play App Signing holds the key users' installs check.

Uploads go to Play's internal testing track through the
[Gradle Play Publisher](https://github.com/Triple-T/gradle-play-publisher) plugin, with a Play Console service
account's JSON key kept outside the repo. The first bundle has to be uploaded by hand in the Play Console.

```sh
ANDROID_PUBLISHER_CREDENTIALS="$(cat ~/.config/retriever/play-service-account.json)" \
  ./gradlew :androidApp:publishReleaseBundle
```

While the Play app is still a draft, add `--release-status draft` and roll the release out in the Console.
The bundle carries the R8 mapping and the baseline profile, so Play deobfuscates crashes itself.

The Play Console needs declarations for all-files access (`MANAGE_EXTERNAL_STORAGE`, on-device file search),
the `dataSync` foreground service (local file processing, with a video), and photo and video access
(`READ_MEDIA_IMAGES`/`VIDEO`, searching the whole library is the core feature), plus a privacy policy URL and
the Data safety form. Check model download, processing and background recovery on physical hardware. A
successful build alone does not establish store approval or device performance.

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
