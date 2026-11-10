# Third-party notices

Retriever includes code adapted from the projects below, runtime libraries and model weights. Each section names its license.
The Apache License, Version 2.0 text is reproduced at the end of this file, followed by the MIT texts
for the code and dependency under that license. Adapted files keep the upstream license header (where
the upstream file had one) and carry an "Adapted from" line describing what was changed.
The English Swift Playgrounds agreement from Apple's original sample is also reproduced below.

## google-ai-edge/gallery

- Source: https://github.com/google-ai-edge/gallery
- Copyright 2025-2026 Google LLC
- License: Apache License 2.0

## android/platform-samples

- Source: https://github.com/android/platform-samples
- Copyright 2023 The Android Open Source Project
- License: Apache License 2.0

## ML Kit text recognition (dependencies, not adapted code)

- Source: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- Artifacts: com.google.mlkit:text-recognition and text-recognition-devanagari (bundled);
  com.google.android.gms:play-services-mlkit-text-recognition-chinese, -japanese and -korean (unbundled, Google Play services)
- Copyright Google LLC
- License: ML Kit SDK terms (https://developers.google.com/ml-kit/terms); binary client libraries, not open source

## SFace face recognition model (bundled model, not adapted code)

- Source: https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface
  (`face_recognition_sface_2021dec.onnx`, sha256 0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79)
- Converted to `androidApp/src/main/assets/sface.tflite` (float16 weights) and `iosApp/Retriever/Faces/SFace.mlpackage`
  (Core ML, float16); the network and its weights are otherwise unchanged
- Copyright OpenCV team and the SFace authors (Zhong et al., "SFace: Sigmoid-Constrained Hypersphere Loss for Robust
  Face Recognition", 2021)
- License: Apache License 2.0. The model card does not name its training data.

## ML Kit face detection (dependency, not adapted code)

- Source: https://developers.google.com/ml-kit/vision/face-detection/android
- Artifact: com.google.mlkit:face-detection (bundled)
- Copyright Google LLC
- License: ML Kit SDK terms (https://developers.google.com/ml-kit/terms); binary client library, not open source

## EmbeddingGemma 2 (downloaded model weights, not adapted code)

- Main model card: https://huggingface.co/google/embeddinggemma-2
- Authors: Google DeepMind; LiteRT exports: litert-community
- Retriever downloads the CPU/GPU or chip-specific NPU files from these pinned revisions:
  - Text Vision 440M: https://huggingface.co/litert-community/embeddinggemma-2-text-vision-440m-litert-lm/tree/e301f74d5551b0c2641bd5cb4652a76239d5c5f8
  - Legacy 740M: https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/tree/24d962e906c7d332c6428e71c9676855024569e2
- License: Apache License 2.0, declared by both pinned export model cards and the main model card
  (https://ai.google.dev/gemma/apache_2). The Apache text below applies to these weights as well as the Apache-licensed code.

## OkHttp and Okio (dependencies, not adapted code)

- Source: https://github.com/square/okhttp and https://github.com/square/okio
- Artifacts: com.squareup.okhttp3:okhttp and com.squareup.okio:okio, pulled in by ML Kit image labeling
  (com.google.mlkit:vision-internal-vkp)
- Copyright Square, Inc.
- License: Apache License 2.0
- OkHttp bundles `publicsuffixes.gz`, compiled from the Public Suffix List
  (https://publicsuffix.org/list/public_suffix_list.dat) and subject to the Mozilla Public License 2.0
  (https://mozilla.org/MPL/2.0/)

## oguzhaneksi/TikTokCompose

- Source: https://github.com/oguzhaneksi/TikTokCompose
- Copyright its contributors (the upstream file carries no header)
- License: Apache License 2.0

## androidx compose material3 samples

- Source: https://github.com/androidx/androidx
- Copyright The Android Open Source Project (see the upstream file header)
- License: Apache License 2.0

## google-ai-edge/mediapipe-samples

- Source: https://github.com/google-ai-edge/mediapipe-samples
- Copyright 2026 The MediaPipe Authors
- License: Apache License 2.0

## mazzzystar/Queryable

- Source: https://github.com/mazzzystar/Queryable
- Copyright (c) 2023 Ke Fang
- License: MIT License (text at the end of this file). Its PhotoHelper files are themselves from Apple sample code.

## Apple sample code

- "Adding a search interface to your app" (https://developer.apple.com/documentation/swiftui/adding-a-search-interface-to-your-app), Apple Sample Code License. Used as a layout reference for the iOS search screen; no code copied.
- iosApp/Retriever/Thumbnail.swift — `PHCachingImageManager` setup adapted through Queryable's
  `Queryable/Queryable/PhotoHelper/CachedImageManager.swift` from Apple's "Capturing and Displaying Photos" sample
  (https://developer.apple.com/tutorials/sample-apps/capturingphotos-camerapreview).
  The [official archive](https://developer.apple.com/tutorials/downloads/com.apple.sample-apps/CapturingPhotos.zip)
  contains `Capturing Photos.swiftpm/App/CachedImageManager.swift` and `License.txt`, whose license is the
  **Swift Playgrounds Software License Agreement**, EA1796, 04/12/2022. Its full English text is reproduced below.
  Section 3.D limits reusable code that interacts with Apple APIs to the specified Playgrounds or Xcode applications
  for Apple-branded devices and forbids use on other platforms. This portion retains those terms.

## Dependencies under other licenses

- ZIPFoundation — https://github.com/weichsel/ZIPFoundation — Copyright (c) 2017-2025 Thomas Zoechling — MIT License (iOS, via Swift Package Manager; not adapted code). Its MIT text is at the end of this file.

## Files containing adapted code

- androidApp/src/main/kotlin/app/retriever/IndexWorker.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/indexing/Notifications.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/ui/components/Thumbnails.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt` (Apache-2.0); platform-samples `samples/graphics/pdf/src/main/java/com/example/platform/graphics/pdf/PdfRendererViewModel.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/viewer/PdfPageScreen.kt — platform-samples `samples/graphics/pdf/src/main/java/com/example/platform/graphics/pdf/PdfRenderer.kt` and `PdfRendererViewModel.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/ui/components/FileRow.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumSearchScreen.kt` BackgroundAssetGridTile (ThumbCell) (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/duplicates/DuplicatesScreen.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/ConfirmDeleteDialog.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/setup/SetupScreen.kt — earlier download and formatting adaptations now live in `model/ModelRepository.kt`, `model/UpgradeFlow.kt` and `ui/Format.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/ui/Format.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/Utils.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/search/SearchScreen.kt — androidx compose material3 1.4.0 samples `SearchBarSamples.kt` (SimpleSearchBarSample) and gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumSearchScreen.kt` results grid (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/viewer/PlayerScreen.kt — TikTokCompose `app/src/main/java/com/example/tiktokcompose/ui/composables/TikTokScreen.kt` (Apache-2.0)
- androidApp/src/main/AndroidManifest.xml — gallery `Android/src/app/src/main/AndroidManifest.xml` (uses-native-library block, npu uses-feature, foreground service type) (Apache-2.0); platform-samples `samples/storage/src/main/AndroidManifest.xml` (media read permissions) (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/access/Access.kt — platform-samples `samples/storage/src/main/java/com/example/platform/storage/mediastore/SelectedPhotosAccess.kt` (permission sets per Android version, full / selected / denied check) (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/model/ModelDownload.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/model/ModelRepository.kt and androidApp/src/main/kotlin/app/retriever/model/UpgradeFlow.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/ui/components/Common.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/EmptyState.kt` and `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/ConfirmDeleteDialog.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/di/AppModule.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/search/SearchResults.kt — androidx compose material3 1.4.0 samples `SearchBarSamples.kt` (SimpleSearchBarSample) and gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumSearchScreen.kt` results grid (Apache-2.0)
- core/src/commonMain/kotlin/app/retriever/core/Taste.kt — gallery `SmartAlbumScreen.kt` stack angles and offsets (Apache-2.0)
- core/src/commonMain/kotlin/app/retriever/core/Retriever.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingCoordinator.kt` (Apache-2.0)
- core/src/commonMain/kotlin/app/retriever/core/ModelBuilds.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt` (Apache-2.0)
- core/src/commonMain/kotlin/app/retriever/core/Model.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/ModelAllowlist.kt` (commit-pinned resolve URLs) (Apache-2.0)
- core/src/androidMain/kotlin/app/retriever/core/android/MediaStoreSource.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt` (Apache-2.0); platform-samples `samples/storage/src/main/java/com/example/platform/storage/mediastore/MediaStoreQuery.kt` (Apache-2.0)
- core/src/androidMain/kotlin/app/retriever/core/android/Device.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/common/HardwareUtils.kt` (Apache-2.0)
- core/src/commonMain/kotlin/app/retriever/core/VideoMoments.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/AudioInterleaving.kt` (Apache-2.0)
- core/src/androidMain/kotlin/app/retriever/core/android/MediaPipeEmbedder.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/OnDeviceEmbedder.kt` and `Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/GemmaEmbeddingModelStore.kt` (old-Mali rule) (Apache-2.0)
- core/src/androidMain/kotlin/app/retriever/core/android/MediaDecoding.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/VideoAudioExtractor.kt`, `Common.kt`, `VideoDecoderSupport.kt`, `VideoThumbnails.kt`, and `services/photolibrary/PhotoLibraryService.kt` (Apache-2.0)
- core/src/androidMain/kotlin/app/retriever/core/android/AndroidExtractor.kt — ImageBytes.bitmap's loadThumbnail fallback, gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt` (Apache-2.0)
- core/src/iosMain/kotlin/app/retriever/core/IosEmbedder.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/OnDeviceEmbedder.kt`, by way of MediaPipeEmbedder.kt (Apache-2.0)
- iosApp/Retriever/Core/MediaPipeEmbedder.swift — mediapipe-samples `examples/universal_embedder/ios/UniversalEmbedder/UniversalEmbedderHelper.swift` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/viewer/PhotoScreen.kt — saket/telephoto `zoomable` and `sub-sampling-image` usage, as a dependency (Apache-2.0)
- iosApp/Retriever/Views/ResultViews.swift — Queryable `Queryable/Queryable/View/PhotoResult` (grid layout reference) (MIT)

## Runtime libraries

Every group in the `:androidApp` `releaseRuntimeClasspath` (from `./gradlew :androidApp:dependencies --configuration releaseRuntimeClasspath`),
every group linked into the iOS framework (`./gradlew :core:dependencies --configuration iosArm64CompileKlibraries`),
and every pin in the iOS `Package.resolved`. Licences are read from each artifact's POM in the Gradle cache. Groups marked
"upstream" had no POM cached, so the licence is the upstream project's. `scripts/verify.sh` fails when a group is missing from this list.

| Group | Licence |
|---|---|
| androidx.activity, androidx.annotation, androidx.appcompat, androidx.arch.core, androidx.autofill, androidx.collection, androidx.compose, androidx.compose.animation, androidx.compose.foundation, androidx.compose.material, androidx.compose.material3, androidx.compose.material3.adaptive, androidx.compose.runtime, androidx.compose.ui, androidx.concurrent, androidx.core, androidx.cursoradapter, androidx.customview, androidx.drawerlayout, androidx.emoji2, androidx.exifinterface, androidx.fragment, androidx.graphics, androidx.interpolator, androidx.lifecycle, androidx.loader, androidx.media3, androidx.navigation3, androidx.navigationevent, androidx.profileinstaller, androidx.recyclerview, androidx.resourceinspection, androidx.room, androidx.savedstate, androidx.sqlite, androidx.startup, androidx.tracing, androidx.vectordrawable, androidx.versionedparcelable, androidx.viewpager, androidx.window, androidx.work | Apache-2.0 |
| app.cash.sqldelight | Apache-2.0 |
| co.touchlab | Apache-2.0 |
| com.google.ai.edge.litert, com.google.ai.edge.litertlm | Apache-2.0 |
| com.google.android.datatransport | Apache-2.0 |
| com.google.android.gms | Android Software Development Kit License and ML Kit Terms of Service (Google Play services binaries, not open source) |
| com.google.android.odml | Android Software Development Kit License |
| com.google.android.play | Play Core Software Development Kit Terms of Service (ai-delivery, asset-delivery and core-common, by way of LiteRT; not open source) |
| com.google.code.findbugs | Apache-2.0 |
| com.google.code.gson | Apache-2.0 |
| com.google.errorprone | Apache-2.0 |
| com.google.firebase | Apache-2.0 |
| com.google.flogger | Apache-2.0 |
| com.google.guava | Apache-2.0 (upstream) |
| com.google.j2objc | Apache-2.0 |
| com.google.mediapipe | Apache-2.0 (also the iOS pin `mediapipe`) |
| com.google.mlkit | ML Kit Terms of Service (binary client libraries, not open source) |
| com.google.protobuf | BSD-3-Clause (upstream) |
| com.squareup.okio | Apache-2.0 (okio, a Telephoto dependency) |
| dev.drewhamilton.poko | Apache-2.0 (annotations only, a Telephoto dependency) |
| io.insert-koin | Apache-2.0 (koin-android, koin-androidx-compose, koin-androidx-workmanager) |
| javax.inject | Apache-2.0 |
| org.checkerframework | MIT (checker-qual) and GPL-2.0 with the Classpath Exception (checker-compat-qual); used at compile time only |
| me.saket.telephoto | Apache-2.0 (zoomable, sub-sampling-image: the photo viewer's pinch, double-tap zoom, fling and tiled decoding) |
| org.jetbrains | Apache-2.0 |
| org.jetbrains.androidx.lifecycle, org.jetbrains.androidx.savedstate | Apache-2.0 |
| org.jetbrains.compose.animation, org.jetbrains.compose.annotation-internal, org.jetbrains.compose.collection-internal, org.jetbrains.compose.foundation, org.jetbrains.compose.runtime, org.jetbrains.compose.ui | Apache-2.0 |
| org.jetbrains.kotlin, org.jetbrains.kotlinx | Apache-2.0 |
| org.jspecify | Apache-2.0 |
| iOS: zipfoundation | MIT (see the MIT License (ZIPFoundation) section) |

## Apache License 2.0

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

   Copyright [yyyy] [name of copyright owner]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

## MIT License (ZIPFoundation)

Source: https://github.com/weichsel/ZIPFoundation (text from the package's LICENSE file)

```
MIT License

Copyright (c) 2017-2025 Thomas Zoechling (https://www.peakstep.com)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## MIT License (Queryable)

Source: https://github.com/mazzzystar/Queryable (text from the repository's LICENSE file)

```
MIT License

Copyright (c) 2023 Ke Fang

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## stopwords-iso English and Hindi

https://github.com/stopwords-iso/stopwords-en
https://github.com/stopwords-iso/stopwords-hi

The MIT License (MIT)

Copyright (c) 2016 Gene Diaz

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Swift Playgrounds Software License Agreement (Apple Capturing Photos sample)

English section of `Capturing Photos.swiftpm/License.txt` from the official archive linked above.

```text
ENGLISH

APPLE INC.
SWIFT PLAYGROUNDS SOFTWARE LICENSE AGREEMENT

PLEASE READ THIS SOFTWARE LICENSE AGREEMENT (“LICENSE”) CAREFULLY BEFORE USING THE APPLE SOFTWARE.  BY USING THE APPLE SOFTWARE, YOU ARE AGREEING TO BE BOUND BY THE TERMS OF THIS LICENSE.  IF YOU DO NOT AGREE TO THE TERMS OF THIS LICENSE, DO NOT INSTALL AND/OR USE THE SOFTWARE.

IMPORTANT NOTE: To the extent that this software may be used to reproduce, modify, publish and distribute materials, it is licensed to You only for reproduction, modification, publication and distribution of non-copyrighted materials, materials in which You own the copyright, or materials You are authorized or legally permitted to reproduce, modify, publish or distribute.  If You are uncertain about Your right to copy, modify, publish or distribute any material, You should contact Your legal advisor.

1. Definitions.

Whenever capitalized in this Agreement:

“Agreement” means this Swift Playgrounds Agreement.

“Apple” means Apple Inc., a California corporation with its principal place of business at One Infinite Loop, Cupertino, California 95014, U.S.A.

“Apple Developer Program License Agreement” means a separate agreement that may be entered into between You and Apple regarding the development and submission of Applications to the App Store for approval and digital signing by Apple, development of libraries for iOS, watchOS, iPadOS, and/or tvOS, and the use of Apple services such as the Apple Push Notification Service, In-App Purchase, and iCloud, among other things.

“Apple Maps Service” means the mapping platform and Map Data provided by Apple via the MapKit API (which is the documented API that enables You to add mapping features or functionality to Applications).

“Apple Services” or “Services” means the developer services that Apple provides to You under this Agreement solely for use with Your Applications and not for use by You as an end-user (e.g., Game Center, Apple Maps Service, any Apple-certificate issuance services, etc.), including any Updates thereto (if any) that may be provided to You by Apple.

“Apple Software” means Apple software including the Swift Playgrounds Application and the Apple SDKs, Documentation, interfaces, content, fonts, data and any Updates thereto (if any) that may be provided to You by Apple.

“Apple SDKs” means the Apple-proprietary Software Development Kits (SDKs) provided hereunder, including but not limited to header files, APIs, libraries, simulators, and software (source code and object code) labeled as part of iOS, iPadOS, and/or macOS SDK and included in the Xcode Developer Tools package and Swift Playgrounds for purposes of targeting Apple-branded products running iOS, iPadOS, and/or macOS, respectively.

“Application” means a software program (including extensions and media that are enclosed in a single software bundle) developed by You hereunder, for use under Your own name, trademark or brand, and specifically for use on Apple-branded products running iPadOS, iOS, macOS, as applicable, including new releases and new versions of such software program, but excluding libraries for iPadOS, and/or iOS applications.

“Documentation” means the technical or other specifications or documentation that Apple may provide to You for use in connection with the Apple Software, Apple Services, Apple certificates, or as otherwise provided hereunder.

“FOSS” (Free and Open Source Software) means any software that is subject to terms that, as a condition of use, copying, modification or redistribution, require such software and/or derivative works thereof to be disclosed or distributed in source code form, to be licensed for the purpose of making derivative works, or to be redistributed free of charge, including without limitation software distributed under the GNU General Public License or GNU Lesser/Library GPL.

“Map Data” means any content, data or information provided through the Apple Maps Service including, but not limited to, imagery, terrain data, latitude and longitude coordinates, transit data, points of interest and traffic data.

“Program Requirements” mean the technical, human interface, design, product category, security, performance, and other criteria and requirements for Applications specified by Apple in the Apple Developer Program License Agreement.

“Updates” means bug fixes, updates, upgrades, modifications, enhancements and supplements to the Apple Software or Apple Services, and any new releases or versions thereof.

“You” or “Your” means the person(s) or entity using the Apple Software or Services or otherwise exercising rights under this Agreement.  If You are entering into this Agreement on behalf of Your company, organization, educational institution, or an agency, instrumentality, or department of the federal government, “You” or “Your” refers to your company, organization, educational institution or agency, instrumentality, or department of the federal government as well.

2. General.
A. The Apple Software is licensed, not sold, to You by Apple for use only under the terms of this License.  Apple and/or Apple’s licensors retain ownership of the Apple Software itself and reserve all rights not expressly granted to You.

B. Apple, at its discretion, may make available future Updates to the Apple Software for Your compatible Apple-branded device.  The Apple Software Updates, if any, may not necessarily include all existing software features or new features that Apple releases for newer or other models of Apple-branded devices.  The terms of this License will govern any software Updates provided by Apple to the original Apple Software product, unless such Updates are accompanied by a separate license in which case You agree that the terms of that license will govern such Updates.

C. Apple is not obligated to provide any maintenance, technical or other support for the Apple Software and Apple Services.  You acknowledge that Apple has no express or implied obligation to announce or make available any Updates of the Apple Software and Apple Services to anyone in the future.  Should an Update be made available, it may have APIs, features, services, content, or functionality that are different from those found in the Apple Software and Apple Services licensed hereunder.

3. Permitted License Uses and Restrictions.
A. License.  Subject to the terms and conditions of this License and as permitted in the “App Store Product Usage Rules” set forth in the App Store Terms and Conditions (https://www.apple.com/legal/internet-services/itunes/) (“Usage Rules”), unless You obtained the Apple Software as described in Section 2B, You are granted a limited, non-transferable, non-exclusive license to install and use the Apple Software on any compatible Apple-branded device that You own or control.  You may not distribute or make the Apple Software available over a network where it could be used by multiple devices at the same time.

B. Volume or Maintenance License.  If You obtained the Apple Software under a volume or maintenance license program with Apple, the terms of Your volume or maintenance license will determine the number of copies of the Apple Software You are permitted to download, install, use and run on Apple-branded devices You own or control.  Except as agreed to in writing by Apple, all other terms and conditions of this License shall apply to Your use of the Apple Software obtained under a volume or maintenance license.

C. System Requirements.  Apple Software is supported only on Apple-branded hardware that meets specified system requirements as indicated by Apple.

D. Swift Playgrounds Application (“Playgrounds App”).  The Playgrounds App provides content including but not limited to graphics, artwork, images, photographs, sounds, books, examples, and computer code (“Reusable Computer Code”) (collectively, the “Playgrounds Content”) for use in creating Your own documents and applications within the Playgrounds App in accordance with this License.

Except as otherwise provided herein, during the term of this License and conditioned upon Your compliance with these terms, You may: (i) use and modify the Playgrounds Content to create documents and applications within the Playgrounds App; and (ii) use and modify the Playgrounds Content in the documents and applications You create using the Playgrounds App, unless any such content is accompanied by another license, in which case, that license applies to the use and distribution of such content.

Images owned by Apple of characters (e.g., Byte, Hopper, Blu, Expert) and any pre-rendered images owned by Apple (e.g., images from Byte’s World (from Learn to Code 1 & 2)) may not be used outside the Playgrounds App and will remain the property of Apple.

Reusable Computer Code that enables access to or interacts with an Apple application programming interface may be used only within documents running within the Playgrounds App on an Apple-branded device, within an Application developed on the Playgrounds App for an Apple-branded device or within an Application developed using Xcode for an Apple-branded device and may not be copied, reverse engineered, or used on any other platform.

You may use the Apple SDKs within the Playgrounds App solely to test and develop Applications that are specifically for use with Apple-branded products running iPadOS, iOS, macOS, as applicable, and You may use the Apple Services solely to test and develop Applications that are specifically for use on Apple-branded products, unless otherwise permitted by Apple in writing.

Unless otherwise expressly permitted by Apple in writing, You agree not to rent, lease, lend, upload to or host on any website or server, sell, redistribute, or sublicense the Apple Software and Apple Services, in whole or in part, or to enable others to do so.

You may not use the Apple Software and Apple Services for any purpose not expressly permitted by this Agreement.  You may not and You agree not to, or to enable others to, copy (except as expressly permitted under this Agreement), decompile, reverse engineer, disassemble, attempt to derive the source code of, modify, decrypt, or create derivative works of the Apple Software or Services, or any  part thereof (except as and only to the extent any foregoing restriction is prohibited by applicable law, or to the extent as may be permitted by licensing terms governing use of open-sourced components or Reusable Computer Code included with the Apple Software).

You agree not to exploit the Apple Software or Apple Services in any unauthorized way whatsoever, including but not limited to, by trespass or burdening network capacity.  Further, as a condition to using the Apple Software and Apple Services, You agree that You will not, directly or indirectly, commit any act intended to interfere with the Apple Software or Apple Services.  All licenses not expressly granted in this Agreement are reserved and no other licenses, immunity or rights, express or implied are granted by Apple, by implication or otherwise.

This Agreement does not grant You any rights to use any trademarks, logos or service marks belonging to Apple, including but not limited to the iPhone or iPod word marks.  If You make reference to any Apple products or technology or use Apple’s trademarks, You agree to comply with the guidelines at: https://www.apple.com/legal/intellectual-property/guidelinesfor3rdparties.html, as modified by Apple from time to time.

You further acknowledge that in order to create any application for use outside the Playgrounds App on an Apple device and/or for distribution via Apple’s App Store, You will need to enter into Apple’s Developer Program License Agreement and Your Application must comply with the Program Requirements and Documentation.  For further information, please refer to information about the Apple Developer Program at https://developer.apple.com. Apple reserves the right to change the Program Requirements and/or the terms of the Apple Developer Program License Agreement from time to time.  You are fully responsible for any and all costs, expenses or other liabilities You may incur as a result of Your development or use of the Apple Software or Services.

You may distribute the documents You create in the Playgrounds App and You may modify such documents using Xcode on an Apple-branded device; however, unless accompanied by another license, the Playgrounds Content may not be used in any applications running outside of the Playgrounds App and may not otherwise be used, extracted, copied, modified, distributed, or repackaged as content, audio samples, clip art, music beds, sound effects, sound files, sound libraries, stock animation, or similar assets.

This License does not grant You any rights in any trademarks, service marks, trade names, or logos of Apple or any third party that may be referenced or included within the Playgrounds App, and any such trademarks, service marks, trade names, or logos are expressly excluded from the definition of “Playgrounds Content”.  Further, this License does not grant You any rights in any Apple software technical documentation that Apple may provide to You in the Playgrounds App.  Such technical documentation may be accessed in the Playgrounds App as a reference and may not be separately copied or used outside of the Playgrounds App (e.g., any images contained in the technical documentation may not be separately used).

E. Title and intellectual property rights in and to any content displayed by or accessed through the Apple Software belong to the respective content owner.  Such content may be protected by copyright or other intellectual property laws and treaties, and may be subject to terms of use of the third party providing such content.  Except as otherwise provided in this License, this License does not grant You any rights to use such content nor does it guarantee that such content will continue to be available to You.

F. Documents and Applications that You create using the Playgrounds App must not contain any malware, malicious or harmful code, program, or other internal component (e.g., computer viruses, Trojan horses, “backdoors”) which could damage, destroy, or adversely affect the Apple Software, services, Apple-branded products, or other software, firmware, hardware, data, systems, services, or networks; and must not be designed for the purpose of harassing, abusing, spamming, stalking, threatening or otherwise violating the legal rights of any other party.

G. FOSS. If Your Application includes any FOSS, You agree to comply with all applicable FOSS licensing terms, and You agree not to use any FOSS in the development of Your Application in such a way that would cause Apple Software, Services, or any Apple-issued certificates to be subject to any FOSS licensing terms or obligations.

H. No Reverse Engineering.  You may not, and You agree not to or enable others to, copy (except as expressly permitted by this License or by the Usage Rules if they are applicable to You), decompile, reverse engineer, disassemble, attempt to derive the source code of, decrypt, modify, or create derivative works of the Apple Software or any services provided by the Apple Software, or any part thereof (except as and only to the extent any foregoing restriction is prohibited by applicable law or to the extent as may be permitted by the licensing terms governing use of open-sourced components included with the Apple Software).

I. Compliance with Laws.  You agree to use the Apple Software and the Apple Services in compliance with all applicable laws, including local laws of the country or region in which You reside or in which You download or use the Apple Software and Apple Services.  You agree that, to the best of r knowledge and belief, r documents will not violate, misappropriate, or infringe any Apple or third party copyrights, trademarks, service marks, trade names, logos, or other proprietary or legal rights (e.g. photography or image rights, logo rights, third party data rights, etc. for content and materials that may be included in r documents).

J. Restrictions. You agree that neither You nor Your Applications will disable, override or otherwise interfere with any Apple-implemented system alerts, warnings, display panels, consent panels and the like.  You understand and agree that You and Your Applications must comply with all applicable criminal, civil and statutory laws and regulations (including privacy, data collection and location service laws and regulations) in any jurisdictions in which Your Applications may be delivered.  You shall be solely responsible for, and Apple shall have no responsibility or liability whatsoever with respect to, any and all claims, suits, liabilities, losses, damages, costs and expenses arising from, related or attributable to, or otherwise involving Your Application.

You agree not to use the Apple Software and Apple Services for any unlawful or illegal activity, nor to develop any Application that would commit or facilitate the commission of a crime, or other tortious, unlawful, or illegal act.  You agree that, to the best of Your knowledge and belief, Your Applications will not violate, misappropriate, or infringe any Apple or third party copyrights, trademarks, rights of privacy and publicity, trade secrets, patents, or other proprietary or legal rights (e.g. musical composition or performance rights, video rights, photography or image rights, logo rights, third party data rights, etc. for content and materials that may be included in Your Application).  Further, You agree not to use the Apple Software or Apple Services to create any Application or other software program that would disable, hack or otherwise interfere with any security, digital signing, digital rights management, content protection, verification or authentication mechanisms implemented in or by the Apple Software or the Apple Services, or by other Apple software, services or technology, or enable others to do so, unless otherwise permitted by Apple in writing.

K. Use of Apple Services in Your Applications. Apple may provide access to Apple Services that Your Applications may call through APIs in the Apple Software and/or that Apple makes available to You through other mechanisms, e.g., through the use of keys that Apple may make accessible to You through the Apple Software or Apple Services.  You agree to access such Apple Services only through the mechanisms provided by Apple for such access and only for use on Apple-branded products.  Except as expressly permitted by Apple in writing, You agree not to share access to mechanisms provided to You by Apple for the use of Apple Services with any third party.  Further, You agree not to create or attempt to create a substitute or similar service through use of or access to the Apple Services.

You agree to access and use such Apple Services only as necessary for providing services and functionality for Your Applications that are eligible to use such Services and only as permitted by Apple in writing, including in the Documentation.  You may not use the Apple Services in any manner that is inconsistent with the terms of this Agreement or that infringes any intellectual property rights of a third party or Apple, or that violates any applicable laws or regulations.  You agree that the Apple Services contain proprietary content, information and material owned by Apple and its licensors, and protected by applicable intellectual property and other laws.  You may not use such proprietary content, information or materials in any way whatsoever, except for the permitted uses of the Apple Services under this Agreement, or as otherwise separately and expressly permitted by Apple in writing.

You acknowledge that there may be storage capacity, transmission, and/or transactional limits for the Apple Services both for You as a developer and for Your Authorized Developers.  If You reach or Your Authorized Developers reach such limits, then You or Your Authorized Developer may be unable to use the Apple Services or may be unable to access or retrieve data from Your Applications or through the applicable end-user accounts.  You agree not to charge any fees to Your Authorized Developers solely for access to or use of the Apple Services through Your Application or for any content, data or information provided therein, and You agree not to sell access to the Apple Services in any way.

Apple Services may not be available in all languages or in all countries, and Apple makes no representation that any such Services would be appropriate, accurate or available for use in any particular location or product.  To the extent You choose to use the Apple Services with Your Applications, You do so at Your own initiative and are responsible for compliance with any applicable laws.

Apple reserves the right to change, suspend, deprecate, remove, limit, or disable access to the Apple Services, or any part thereof, at any time without notice (including revoking entitlements or changing any APIs in the Apple Software that enable access to the Services).  In no event will Apple be liable for the removal of or disabling of access to any of the foregoing.  Apple may also impose limits and restrictions on the use of or access to the Apple Services, may remove the Apple Services for indefinite time periods, may revoke Your access to the Apple Services, or may cancel the Apple Services (or any part thereof) at any time without notice or liability to You and in its sole discretion.

Apple does not guarantee the availability, accuracy, completeness, reliability, or timeliness of any data or information displayed by any Apple Services.  You are responsible for Your use of the Apple Software and Apple Services, and if You use such Services to store any content, data, or information, then it is Your responsibility to maintain appropriate alternate backup of all Your content, information, and data.

You understand and agree that You may not be able to access certain Apple Services upon expiration or termination of this Agreement and that Apple reserves the right to suspend access to or delete content, data or information that You have stored through Your use of such Services provided hereunder.  You should review the Documentation and policy notices posted by Apple prior to using any Apple Services. Certain Apple Services may not be made available for Applications on all Apple-branded products and may not be made available to all developers.  Apple reserves the right to not provide (or to cease providing) the Apple Services to any or all developers at any time in its sole discretion.

L. Use of the Apple Maps Service; Location. If Your Application uses or accesses the Map Kit API from a device running iOS version 6 or later, Your Application will access and use the Apple Maps Service.  All use of the Map Kit API and Apple Maps Service must be in accordance with the terms of this Agreement, and You agree that the Apple Maps Service is solely for use by Applications running on Apple-branded products.  Your Application may use the MapKit API only to access the Apple Maps Service and/or Map Data in Your Applications (except as otherwise set forth below).

As part of such access to the Apple Maps Service and/or Map Data through the MapKit API:

Your Application must not remove, obscure or alter Apple’s or its licensors’ copyright notices, trademarks, or any other proprietary rights or legal notices, documents or hyperlinks that may appear in or be provided through the Apple Maps Service;

You must not use the Apple Maps Service in any manner that enables or permits bulk downloads or feeds of the Map Data, or any portion thereof, or that in any way attempts to extract, scrape or reutilize any portions of the Map Data.  For example, neither You nor Your Application may use or make available the Map Data, or any portion thereof, as part of any secondary or derived database;

Your Application may display the Map Data only as permitted herein, and when displaying it on a map, You agree that it will be displayed only on an Apple map provided through the Apple Maps Service;

Except to the extent expressly permitted herein, You agree not to copy, modify, translate, create a derivative work of, publish or publicly display the Map Data in any way.  Further, You may not use or compare the data provided by the Apple Maps Service for the purpose of improving or creating another mapping service;

Unless otherwise expressly permitted in the MapKit Documentation for the Apple Maps Service, Map Data may not be cached, pre-fetched, or stored by You or Your Application, other than on a temporary and limited basis solely to improve the performance of the Apple Maps Service with Your Application;

You acknowledge and agree that results You may receive from the Apple Maps Service may vary from actual conditions due to variable factors that can affect the accuracy of Map Data, such as road or weather conditions; and

If Your Application accesses the MapKit API from a device running iOS version 5 or earlier, Your Application will access and use only the Google Mobile Maps (GMM) service.  Such use of the GMM is subject to the following terms: https://code.google.com/apis/maps/terms/iPhone.html.  If You do not accept such terms, You may not use the GMM service in Your Application, and You acknowledge and agree that such use will constitute Your acceptance of such terms.

Applications that use location-based APIs (e.g., Core Location, MapKit API) or otherwise provide location-based services may not be designed or marketed for automatic or autonomous control of vehicle behavior, or for emergency or life-saving purposes. Map Data and other location data provided by Apple is not intended to be relied upon in situations where precise location information is needed or where erroneous, delayed, non-existent, inaccurate or incomplete location data may lead to death, personal injury, property or environmental damage.

If You choose to provide Your own location-based service, data and/or information in conjunction with the Apple maps provided through the Apple Maps Service (e.g., overlaying a map or route You have created on top of an Apple map), You are solely responsible for ensuring that Your service, data and/or information correctly aligns with any Apple maps used.  For Applications that use location-based APIs for real-time navigation (including, but not limited to, turn-by-turn route guidance and other routing that is enabled through the use of a sensor), You must have an end-user license agreement that includes the following notice: YOUR USE OF THIS REAL TIME ROUTE GUIDANCE APPLICATION IS AT YOUR SOLE RISK.  LOCATION DATA MAY NOT BE ACCURATE.  Further, any Applications that offer location-based services or location-based functionality must notify and obtain consent from an individual before his or her location data is collected, transmitted or otherwise used by the Application.

M. Third Party Software.  Apple may provide access to certain third party software and/or services as a convenience (e.g., the “Accessories” content in the Playgrounds App) and/or the Apple Software may enable You to load third party software or services for use in the Playgrounds App.  Apple will not be responsible for Your use of any such third party software or services, and in no event shall Apple be liable for any direct, indirect or consequential damages arising out of or related to Your use of any third party software or services included in or accessed through the Playgrounds App, including, but not limited to, any damage to third party hardware that interacts with associated third party content, or any loss or damage of any kind incurred as a result of errors or omissions in third party code.  All third party software that You access through the “Accessories” tab is licensed to You by the applicable third party and, unless such software is accompanied by separate license, Your use will be subject to the same terms and conditions as set forth in this License.  Further, to the extent that the Apple Software contains or provides access to any third party software or services, Apple has no express or implied obligation to provide any technical or other support for such software or services. Please contact the appropriate software vendor, manufacturer or service provider directly for technical support and customer service related to its software, service and/or products.  Apple and its licensors reserve the right to change, suspend, remove, or disable access to any third party software and/or services at any time without notice.  In no event will Apple be liable for the removal of or disabling of access to any such software and/or services.  Apple may also impose limits on the use of or access to certain software and/or services, in any case and without notice or liability.

N. Automatic Updates.  If You choose to allow automatic app updates, Your device will periodically check with Apple for Updates to the Apple Software and, if an Update is available, the Update will automatically download and install onto Your device and, if applicable, Your peripheral devices.  You can turn off the automatic Updates altogether at any time by changing the automatic app updates settings found within Settings.

4. Consent to Use of Data.
A. Diagnostic and Usage Data.  If the device running the Apple Software is opted  in to diagnostic and usage collection, You agree that Apple and its subsidiaries and agents may collect, maintain, process and use diagnostic, technical, usage and related information, including but not limited to unique system or hardware identifiers, information about that device, computer, system and application software, and peripherals, that is gathered periodically to test, provide and improve Apple’s products and services, facilitate the provision of software updates, product support and other services to You or Your organization (if any) related to the Apple Software, and to verify compliance with the terms of this License.  Apple may use this information, as long as it is collected in a form that does not personally identify You, for the purposes described above.  To enable Apple’s partners and third party developers to improve their software, hardware and services designed for use with Apple products, Apple may also provide any such partner or third party developer with a subset of diagnostic information that is relevant to that partner’s or developer’s software, hardware and/or services, as long as the diagnostic information is in a form that does not personally identify You.

If You are running a pre-release version of the Apple Software and Services, then unless You opt out of diagnostic and usage collection, You agree that Apple and its subsidiaries and agents will collect, maintain, process and use diagnostic, technical, usage and related information, including but not limited to unique system or hardware identifiers, information about Your computer, system and application software, and peripherals, that is gathered periodically and automatically as part of the pre-release seeding process to test, provide and improve Apple’s products and services, facilitate the provision of software updates, product support and other services to You (if any) related to the Apple Software, and to verify compliance with the terms of this Agreement.

B. Privacy Policy.  At all times Your information will be treated in accordance with Apple’s Privacy Policy, which can be viewed at: https://www.apple.com/legal/privacy/.

5. Pre-Release (also known as Beta) Software and Services
Note:  This Agreement applies to both the commercial release versions of the Apple Software and pre-release versions of the Apple Software, as applicable.  You should review the release notes provided with the Apple Software if You are uncertain about Your confidentiality obligations or visit developer.apple.com for more information about pre-release versions.

A. Using Pre-Release Apple Software and Services
Pre-release versions of the Apple Software or Apple Services should not be relied upon to perform in the same manner as a final-release, commercial-grade product, nor should they be used with data that is not sufficiently and regularly backed up.  The pre-release Apple Software and Apple Services may contain inaccuracies or errors that could cause failures or loss of data and may be incomplete.  You should back up Your data prior to using any pre-release Apple Software and Apple Services and not rely on them to perform in the same way as a commercial release of the Apple Software and Services.

B. Confidentiality
You agree that pre-release versions of the Apple Software and Apple Services (including pre-release Documentation) will be deemed “Apple Confidential Information”; provided however that upon the commercial release of the Apple Software and Apple Services, such pre-release versions of the Apple Software and Apple Services will no longer be confidential.  Notwithstanding the foregoing, Apple Confidential Information will not include: (i) information that is generally and legitimately available to the public through no fault or breach of Yours, (ii) information that is generally made available to the public by Apple, (iii) information that is independently developed by You without the use of any Apple Confidential Information, (iv) information that was rightfully obtained from a third party who had the right to transfer or disclose it to You without limitation, or (v) any FOSS included in the Apple Software and Apple Services and accompanied by licensing terms that do not impose confidentiality obligations on the use or disclosure of such FOSS.  Further, Apple agrees that You will not be bound by the foregoing confidentiality terms with regard to technical information about pre-release Apple Software or Apple Services disclosed by Apple at WWDC (Apple’s Worldwide Developers Conference), except that You may not post screen shots of, write public reviews of, or redistribute any pre-release Apple Software or Services.

You agree to protect Apple Confidential Information using at least the same degree of care that You use to protect Your own confidential information of similar importance, but no less than a reasonable degree of care.  You agree to use Apple Confidential Information solely for the purpose of exercising Your rights and performing Your obligations under this Agreement and agree not to use Apple Confidential Information for any other purpose, for Your own or any third party’s benefit, without Apple's prior written consent.  You further agree not to disclose or disseminate Apple Confidential Information to anyone other than: (i) Authorized Developers; or (ii) as otherwise agreed or permitted in writing by Apple.  You may disclose Apple Confidential Information to the extent required by law, provided that You take reasonable steps to notify Apple of such requirement before disclosing the Apple Confidential Information and to obtain protective treatment of the Apple Confidential Information.  You acknowledge that damages for improper disclosure of Apple Confidential Information may be irreparable; therefore, Apple is entitled to seek equitable relief, including injunction and preliminary injunction, in addition to all other remedies.

6. Services and Third Party Materials.
A. General.  Services accessed within the Apple Software may be used solely in documents created within the Apple Software on Apple-branded devices in conformance with this License, unless otherwise set forth in an applicable separate license agreement.  Such Services may not be available in all languages or in all countries.  Use of these Services requires Internet access, and use of certain Services may require an Apple ID, may require You to accept additional terms and may be subject to additional fees.  By using this software in connection with an iTunes Store account, Apple ID or other Apple Service, You agree to the applicable terms of service for that Service, such as the latest iTunes Store Terms and Conditions which You may access and review at https://www.apple.com/legal/internet-services/itunes/.

B. If You choose to use the iCloud services through the Apple Software, such use is subject to the following additional terms: iCloud Terms and Conditions available at https://www.apple.com/legal/internet-services/icloud/ and Apple’s Privacy Policy available at https://www.apple.com/legal/privacy/.

C. You understand that by using any of the Services, including but not limited to using any third party code, You may encounter content that may be deemed offensive, indecent, or objectionable, which content may or may not be identified as having explicit language.  Nevertheless, You agree to use the Services at Your sole risk, and You agree that Apple shall have no liability to You for content that may be found to be offensive, indecent, or objectionable.

D. Certain Services may display, include or make available content, data, information, applications or materials from third parties, such as third party code (collectively, “Third Party Materials”) or provide links to certain third party web sites.  By using the Apple Software, including any Services, You acknowledge and agree that Apple is not responsible for examining or evaluating the content, accuracy, completeness, timeliness, validity, copyright compliance, legality, decency, quality or any other aspect of such Third Party Materials or web sites.  Apple, its officers, affiliates and subsidiaries do not warrant or endorse and do not assume and will not have any liability or responsibility to You or any other person for any third party Services, Third Party Materials or web sites, or for any other materials, products, or services of third parties.  Third Party Materials and links to other web sites are provided solely as a convenience to You.

E. To the extent that You upload any content through the use of the Services, You represent that You own all rights in, or have authorization or are otherwise legally permitted to upload, such content and that such content does not violate any terms of service applicable to the Services.  You agree that the Services contain proprietary content, information and material, including but not limited to any Digital Materials, that is owned by Apple, the site owner and/or their licensors, and is protected by applicable intellectual property and other laws, including but not limited to copyright.  You agree that You will not use such proprietary content, information or materials other than for permitted use of the Services or in any manner that is inconsistent with the terms of this License or that infringes any intellectual property rights of a third party or Apple.  No portion of the Services may be reproduced in any form or by any means.  You agree not to modify, rent, lease, loan, sell, distribute, or create derivative works based on the Services, in any manner, and You shall not exploit the Services in any unauthorized way whatsoever, including but not limited to, using the Services to transmit any computer viruses, worms, trojan horses or other malware, or by trespass or burdening network capacity.  You further agree not to use the Services in any manner to harass, abuse, stalk, threaten, defame or otherwise infringe or violate the rights of any other party, and that Apple is not in any way responsible for any such use by You, nor for any harassing, threatening, defamatory, offensive, infringing or illegal messages or transmissions that You may receive as a result of using any of the Services.

F. In addition, Services and Third Party Materials that may be accessed, linked to or displayed through the Apple Software may not be available in all languages or in all countries or regions.  Apple makes no representation that such Services and Third Party Materials are appropriate or available for use in any particular location.  To the extent You choose to use or access such Services and Third Party Materials, You do so at Your own initiative and are responsible for compliance with any applicable laws, including but not limited to applicable local laws and privacy and data collection laws.  Apple and its licensors reserve the right to change, suspend, remove, or disable access to any Services or Third Party Materials at any time without notice.  In no event will Apple be liable for the removal of or disabling of access to any such Services or Third Party Materials.  Apple may also impose limits on the use of or access to certain Services or Third Party Materials, in any case and without notice or liability.

7. Termination.  This License is effective until terminated.  Your rights under this License will terminate automatically or otherwise cease to be effective without notice from Apple if You fail to comply with any term(s) of this License.  All rights hereunder in any pre-release versions of the Apple Software for a particular release will terminate upon the commercial release of such Apple Software.  Upon the termination of this License, You shall cease all use of the Apple Software and destroy all copies, full or partial, of the Apple Software.  Sections 3, 4, 5, 6, 7, 8, 10, 11, and 12 of this License shall survive any such termination.

8. Disclaimer of Warranties.
A. If You are a customer who is a consumer (someone who uses the Apple Software outside of Your trade, business or profession), You may have legal rights in Your country of residence which would prohibit the following limitations from applying to You, and where prohibited they will not apply to You.  To find out more about consumer rights, You should contact a local consumer advice organization.

B. YOU EXPRESSLY ACKNOWLEDGE AND AGREE THAT, TO THE EXTENT PERMITTED BY APPLICABLE LAW, USE OF THE APPLE SOFTWARE AND ANY SERVICES PERFORMED BY OR ACCESSED THROUGH THE APPLE SOFTWARE AND ANY THIRD PARTY SOFTWARE, CONTENT, OR SERVICES INCLUDED IN OR ACCESSED THROUGH THE APPLE SOFTWARE (COLLECTIVELY, “APPLE SOFTWARE” FOR THE PURPOSES OF SECTIONS 6 AND 7) IS AT YOUR SOLE RISK AND THAT THE ENTIRE RISK AS TO SATISFACTORY QUALITY, PERFORMANCE, ACCURACY AND EFFORT IS WITH YOU.

TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, THE APPLE SOFTWARE IS PROVIDED “AS IS” AND “AS AVAILABLE”, WITH ALL FAULTS AND WITHOUT WARRANTY OF ANY KIND, AND APPLE, ITS AFFILIATES, AGENTS, PRINCIPALS, AND THIRD PARTY LICENSORS (COLLECTIVELY REFERRED TO AS “APPLE” FOR THE PURPOSES OF SECTIONS 6 AND 7) HEREBY DISCLAIM ALL WARRANTIES AND CONDITIONS WITH RESPECT TO THE APPLE SOFTWARE, EITHER EXPRESS, IMPLIED OR STATUTORY, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES AND/OR CONDITIONS OF MERCHANTABILITY, SATISFACTORY QUALITY, FITNESS FOR A PARTICULAR PURPOSE, ACCURACY, QUIET ENJOYMENT, AND NON-INFRINGEMENT OF THIRD PARTY RIGHTS.

APPLE DOES NOT WARRANT AGAINST INTERFERENCE WITH YOUR ENJOYMENT OF THE APPLE SOFTWARE, THAT THE FUNCTIONS CONTAINED IN, OR SERVICES PERFORMED OR PROVIDED BY, THE APPLE SOFTWARE WILL MEET YOUR REQUIREMENTS, THAT THE OPERATION OF THE APPLE  SOFTWARE WILL BE UNINTERRUPTED OR ERROR-FREE, THAT ANY SERVICES WILL CONTINUE TO BE MADE AVAILABLE, THAT DEFECTS IN THE APPLE SOFTWARE WILL BE CORRECTED, OR THAT THE APPLE SOFTWARE WILL BE COMPATIBLE OR WORK WITH ANY THIRD PARTY SOFTWARE, APPLICATIONS, HARDWARE, OR THIRD PARTY SERVICES.  INSTALLATION OF THIS SOFTWARE MAY AFFECT THE USABILITY OF THIRD PARTY SOFTWARE, APPLICATIONS, HARDWARE, OR THIRD PARTY SERVICES.

C. YOU FURTHER ACKNOWLEDGE THAT THE APPLE SOFTWARE IS NOT INTENDED OR SUITABLE FOR USE IN SITUATIONS OR ENVIRONMENTS WHERE THE FAILURE OR TIME DELAYS OF, OR ERRORS OR INACCURACIES IN THE CONTENT, DATA OR INFORMATION PROVIDED BY, THE APPLE SOFTWARE COULD LEAD TO DEATH, PERSONAL INJURY, OR SEVERE PHYSICAL OR ENVIRONMENTAL DAMAGE, INCLUDING WITHOUT LIMITATION THE OPERATION OF NUCLEAR FACILITIES, AIRCRAFT NAVIGATION OR COMMUNICATION SYSTEMS, AIR TRAFFIC CONTROL, LIFE SUPPORT OR WEAPONS SYSTEMS.

D. NO ORAL OR WRITTEN INFORMATION OR ADVICE GIVEN BY APPLE OR AN APPLE AUTHORIZED REPRESENTATIVE SHALL CREATE A WARRANTY.  SHOULD THE APPLE SOFTWARE PROVE DEFECTIVE, YOU ASSUME THE ENTIRE COST OF ALL NECESSARY SERVICING, REPAIR OR CORRECTION.  SOME JURISDICTIONS DO NOT ALLOW THE EXCLUSION OF IMPLIED WARRANTIES OR LIMITATIONS ON APPLICABLE STATUTORY RIGHTS OF A CONSUMER, SO THE ABOVE EXCLUSION AND LIMITATIONS MAY NOT APPLY TO YOU.

9.  Limitation of Liability.  TO THE EXTENT NOT PROHIBITED BY APPLICABLE LAW, IN NO EVENT SHALL APPLE BE LIABLE FOR PERSONAL INJURY, OR ANY INCIDENTAL, SPECIAL, INDIRECT OR CONSEQUENTIAL DAMAGES WHATSOEVER, INCLUDING, WITHOUT LIMITATION, DAMAGES FOR LOSS OF PROFITS, CORRUPTION OR LOSS OF DATA, FAILURE TO TRANSMIT OR RECEIVE ANY DATA, BUSINESS INTERRUPTION OR ANY OTHER COMMERCIAL DAMAGES OR LOSSES, ARISING OUT OF OR RELATED TO YOUR USE OR INABILITY TO USE, THE APPLE SOFTWARE OR ANY THIRD PARTY SOFTWARE, HARDWARE, OR APPLICATIONS IN CONJUNCTION WITH THE APPLE SOFTWARE, HOWEVER CAUSED, REGARDLESS OF THE THEORY OF LIABILITY (CONTRACT, TORT OR OTHERWISE) AND EVEN IF APPLE HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.  SOME JURISDICTIONS DO NOT ALLOW THE EXCLUSION OR LIMITATION OF LIABILITY FOR PERSONAL INJURY, OR OF INCIDENTAL OR CONSEQUENTIAL DAMAGES, SO THIS LIMITATION MAY NOT APPLY TO YOU.  In no event shall Apple’s total liability to You for all damages (other than as may be required by applicable law in cases involving personal injury) exceed the amount of fifty dollars ($50.00).  The foregoing limitations will apply even if the above stated remedy fails of its essential purpose.

10. Export Control.  You may not use or otherwise export or re-export the Apple Software except as authorized by United States law and the laws of the jurisdiction(s) in which the Apple Software was obtained.  In particular, but without limitation, the Apple Software may not be exported or re-exported (a) into any U.S. embargoed countries or (b) to anyone on the U.S. Treasury Department’s list of Specially Designated Nationals or the U.S. Department of Commerce Denied Person’s List or Entity List or any other restricted party lists.  By using the Apple Software, You represent and warrant that You are not located in any such country or on any such list. You also agree that You will not use the Apple Software for any purposes prohibited by United States law, including, without limitation, the development, design, manufacture or production of missiles, nuclear, chemical or biological weapons.

11. Government End Users.  The Apple Software and related documentation are “Commercial Items”, as that term is defined at 48 C.F.R. §2.101, consisting of “Commercial Computer Software” and “Commercial Computer Software Documentation”, as such terms are used in 48 C.F.R. §12.212 or 48 C.F.R. §227.7202, as applicable.  Consistent with 48 C.F.R. §12.212 or 48 C.F.R. §227.7202-1 through 227.7202-4, as applicable, the Commercial Computer Software and Commercial Computer Software Documentation are being licensed to U.S. Government end users (a) only as Commercial Items and (b) with only those rights as are granted to all other end users pursuant to the terms and conditions herein.  Unpublished-rights reserved under the copyright laws of the United States.

12. Controlling Law and Severability.  This License will be governed by and construed in accordance with the laws of the State of California, excluding its conflict of law principles.  Notwithstanding the foregoing, if You (as an entity entering into this License) are a U.S. public and accredited educational institution, then (a) this License will be governed and construed in accordance with the laws of the state (within the U.S.) in which Your educational institution is domiciled, except that body of law concerning conflicts of law; and (b) any litigation or other dispute resolution between You and Apple arising out of or relating to this License will take place in federal court within the Northern District of California, and You and Apple hereby consent to the personal jurisdiction of and exclusive venue of such District unless such consent is expressly prohibited by the laws of the state in which Your educational institution is domiciled.  This License shall not be governed by the United Nations Convention on Contracts for the International Sale of Goods, the application of which is expressly excluded.  If You are a consumer based in the United Kingdom, this License will be governed by the laws of the jurisdiction of Your residence.  If for any reason a court of competent jurisdiction finds any provision, or portion thereof, to be unenforceable, the remainder of this License shall continue in full force and effect.

13. Complete Agreement; Governing Language.  This License constitutes the entire agreement between You and Apple relating to the Apple Software, and supersedes all prior or contemporaneous understandings regarding such subject matter.  No amendment to or modification of this License will be binding unless in writing and signed by Apple.  Any translation of this License is done for local requirements and in the event of a dispute between the English and any non-English versions, the English version of this License shall govern, to the extent not prohibited by local law in Your jurisdiction.

14. Third Party Acknowledgements.  Portions of the Apple Software may utilize or include third party software and other copyrighted material.  Acknowledgements, licensing terms and disclaimers for such material are contained in the electronic documentation for the Apple Software, and Your use of such material is governed by their respective terms.

Certain images in Meme Creator (from https://www.si.edu/openaccess) and Image Gallery (from NPS.gov) are subject to the creative commons zero license, which is reproduced below.

CREATIVE COMMONS CORPORATION IS NOT A LAW FIRM AND DOES NOT PROVIDE LEGAL SERVICES. DISTRIBUTION OF THIS DOCUMENT DOES NOT CREATE AN ATTORNEY-CLIENT RELATIONSHIP. CREATIVE COMMONS PROVIDES THIS INFORMATION ON AN "AS-IS" BASIS. CREATIVE COMMONS MAKES NO WARRANTIES REGARDING THE USE OF THIS DOCUMENT OR THE INFORMATION OR WORKS PROVIDED HEREUNDER, AND DISCLAIMS LIABILITY FOR DAMAGES RESULTING FROM THE USE OF THIS DOCUMENT OR THE INFORMATION OR WORKS PROVIDED HEREUNDER.

Statement of Purpose
The laws of most jurisdictions throughout the world automatically confer exclusive Copyright and Related Rights (defined below) upon the creator and subsequent owner(s) (each and all, an "owner") of an original work of authorship and/or a database (each, a "Work").

Certain owners wish to permanently relinquish those rights to a Work for the purpose of contributing to a commons of creative, cultural and scientific works ("Commons") that the public can reliably and without fear of later claims of infringement build upon, modify, incorporate in other works, reuse and redistribute as freely as possible in any form whatsoever and for any purposes, including without limitation commercial purposes. These owners may contribute to the Commons to promote the ideal of a free culture and the further production of creative, cultural and scientific works, or to gain reputation or greater distribution for their Work in part through the use and efforts of others.

For these and/or other purposes and motivations, and without any expectation of additional consideration or compensation, the person associating CC0 with a Work (the "Affirmer"), to the extent that he or she is an owner of Copyright and Related Rights in the Work, voluntarily elects to apply CC0 to the Work and publicly distribute the Work under its terms, with knowledge of his or her Copyright and Related Rights in the Work and the meaning and intended legal effect of CC0 on those rights.

1. Copyright and Related Rights. A Work made available under CC0 may be protected by copyright and related or neighboring rights ("Copyright and Related Rights"). Copyright and Related Rights include, but are not limited to, the following:
i.  the right to reproduce, adapt, distribute, perform, display, communicate, and translate a Work;
ii.  moral rights retained by the original author(s) and/or performer(s);
iii.  publicity and privacy rights pertaining to a person's image or likeness depicted in a Work;
iv.  rights protecting against unfair competition in regards to a Work, subject to the limitations in paragraph 4(a), below;
v.  rights protecting the extraction, dissemination, use and reuse of data in a Work;
vi.  database rights (such as those arising under Directive 96/9/EC of the European Parliament and of the Council of 11 March 1996 on the legal protection of databases, and under any national implementation thereof, including any amended or successor version of such directive); and
vii. other similar, equivalent or corresponding rights throughout the world based on applicable law or treaty, and any national implementations thereof.

2. Waiver. To the greatest extent permitted by, but not in contravention of, applicable law, Affirmer hereby overtly, fully, permanently, irrevocably and unconditionally waives, abandons, and surrenders all of Affirmer's Copyright and Related Rights and associated claims and causes of action, whether now known or unknown (including existing as well as future claims and causes of action), in the Work (i) in all territories worldwide, (ii) for the maximum duration provided by applicable law or treaty (including future time extensions), (iii) in any current or future medium and for any number of copies, and (iv) for any purpose whatsoever, including without limitation commercial, advertising or promotional purposes (the "Waiver"). Affirmer makes the Waiver for the benefit of each member of the public at large and to the detriment of Affirmer's heirs and successors, fully intending that such Waiver shall not be subject to revocation, rescission, cancellation, termination, or any other legal or equitable action to disrupt the quiet enjoyment of the Work by the public as contemplated by Affirmer's express Statement of Purpose.

3. Public License Fallback. Should any part of the Waiver for any reason be judged legally invalid or ineffective under applicable law, then the Waiver shall be preserved to the maximum extent permitted taking into account Affirmer's express Statement of Purpose. In addition, to the extent the Waiver is so judged Affirmer hereby grants to each affected person a royalty-free, non transferable, non sublicensable, non exclusive, irrevocable and unconditional license to exercise Affirmer's Copyright and Related Rights in the Work (i) in all territories worldwide, (ii) for the maximum duration provided by applicable law or treaty (including future time extensions), (iii) in any current or future medium and for any number of copies, and (iv) for any purpose whatsoever, including without limitation commercial, advertising or promotional purposes (the "License"). The License shall be deemed effective as of the date CC0 was applied by Affirmer to the Work. Should any part of the License for any reason be judged legally invalid or ineffective under applicable law, such partial invalidity or ineffectiveness shall not invalidate the remainder of the License, and in such case Affirmer hereby affirms that he or she will not (i) exercise any of his or her remaining Copyright and Related Rights in the Work or (ii) assert any associated claims and causes of action with respect to the Work, in either case contrary to Affirmer's express Statement of Purpose.

4. Limitations and Disclaimers.
a. No trademark or patent rights held by Affirmer are waived, abandoned, surrendered, licensed or otherwise affected by this document.
b. Affirmer offers the Work as-is and makes no representations or warranties of any kind concerning the Work, express, implied, statutory or otherwise, including without limitation warranties of title, merchantability, fitness for a particular purpose, non infringement, or the absence of latent or other defects, accuracy, or the present or absence of errors, whether or not discoverable, all to the greatest extent permissible under applicable law.
c. Affirmer disclaims responsibility for clearing rights of other persons that may apply to the Work or any use thereof, including without limitation any person's Copyright and Related Rights in the Work. Further, Affirmer disclaims responsibility for obtaining any necessary consents, permissions or other rights required for any use of the Work.
d. Affirmer understands and acknowledges that Creative Commons is not a party to this document and has no duty or obligation with respect to this CC0 or use of the Work.

EA1796
04/12/2022
```
