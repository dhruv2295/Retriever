# Retriever

Retriever searches everything on your phone by what is inside it: the text in a screenshot, the words
in a PDF, the speech in a video, the faces and objects in a photo. It runs on the device, with a
Gemma-based embedding model (EmbeddingGemma 2), on Android (Jetpack Compose) and iOS (SwiftUI).

## Privacy

The only network call the app makes is the pinned model download, which fetches the model file at a
fixed commit and checks its hash before use. Your photos, files, queries and index never leave the device.

## Layout

- `core/`: Kotlin Multiplatform library (`commonMain`, `androidMain`, `iosMain`). Extraction, chunking,
  embedding, the index and search.
- `androidApp/`: Android app (Compose Material 3).
- `iosApp/`: iOS app (SwiftUI). `iosApp/Configuration/Config.xcconfig` holds the build settings.
- `scripts/`: verification and simulator scripts.
- `docs/quality.md`: the quality gate, hooks and standing decisions.

## Build and run

Android (Android Studio, or the command line):

```
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
```

iOS: open `iosApp/iosApp.xcodeproj` in Xcode and run the `Retriever` scheme on a simulator or device.
Keep signing on ("Sign to Run Locally"). An unsigned app has no Keychain entitlements.

## Verify

```
scripts/verify.sh          # default gate, about 2 minutes
scripts/verify.sh --full   # adds benchmarks, coverage, inspections and the UI tests
```

The individual iOS checks are:

- `scripts/ios-unit-tests.sh [Bundle/Class/method ...]`: `RetrieverTests` and the UI tests, spread over 3+ simulators (created if missing) by measured duration; results in `build/ios-tests/summary.txt`.
- `scripts/ios-selftest.sh`: seeds a clean simulator, indexes a corpus and searches it. It passes only when
  the log contains `SELFTEST PASS`.

The model for the self-test is read from the `MODELS` environment variable. It is the folder that holds
`embeddinggemma-2-text-vision-440m.litertlm`:

```
MODELS=/path/to/models scripts/ios-selftest.sh
```

Optional variables: `SIM_NAME` (simulator name) and `DEVICE_TYPE` (device type used when creating it).

## Signing

Release builds on both platforms currently use the debug signing key. This is a placeholder until the first
store upload. Real upload signing (a keystore for Android, a distribution identity for iOS) is a separate step
and is not in this repository.

## Licences

Third-party code and runtime libraries, with their licences, are in `THIRD_PARTY_NOTICES.md`.
