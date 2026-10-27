# Third-party notices

Retriever includes code adapted from the open-source projects below. Each section names its license.
The Apache License, Version 2.0 text is reproduced at the end of this file, followed by the MIT texts
for the code and dependency under that license. Adapted files keep the upstream license header (where
the upstream file had one) and carry an "Adapted from" line describing what was changed.

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
- Converted to `androidApp/src/main/assets/sface.tflite` (float16 weights) and `iosApp/Retriever/SFace.mlpackage`
  (Core ML, float16); the network and its weights are otherwise unchanged
- Copyright OpenCV team and the SFace authors (Zhong et al., "SFace: Sigmoid-Constrained Hypersphere Loss for Robust
  Face Recognition", 2021)
- License: Apache License 2.0. The model card does not name its training data.

## ML Kit face detection (dependency, not adapted code)

- Source: https://developers.google.com/ml-kit/vision/face-detection/android
- Artifact: com.google.mlkit:face-detection (bundled)
- Copyright Google LLC
- License: ML Kit SDK terms (https://developers.google.com/ml-kit/terms); binary client library, not open source

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
- iosApp/Retriever/Thumbnail.swift — Queryable `Queryable/Queryable/PhotoHelper/CachedImageManager.swift` (PHCachingImageManager setup), which Queryable took from Apple sample code. Apple Sample Code License; the upstream file header only says "See the License.txt file for this sample's licensing information", and the licence text was not available offline, so it is not reproduced here.

## Dependencies under other licenses

- ZIPFoundation — https://github.com/weichsel/ZIPFoundation — Copyright (c) 2017-2025 Thomas Zoechling — MIT License (iOS, via Swift Package Manager; not adapted code). Its MIT text is at the end of this file.

## Files containing adapted code

- androidApp/src/main/kotlin/app/retriever/IndexWorker.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/Notifications.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumIndexingWorker.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/Thumbnails.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/services/photolibrary/PhotoLibraryService.kt` (Apache-2.0); platform-samples `samples/graphics/pdf/src/main/java/com/example/platform/graphics/pdf/PdfRendererViewModel.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/PdfPageScreen.kt — platform-samples `samples/graphics/pdf/src/main/java/com/example/platform/graphics/pdf/PdfRenderer.kt` and `PdfRendererViewModel.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/FileRow.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumSearchScreen.kt` BackgroundAssetGridTile (ThumbCell) and `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/EmptyState.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/DuplicatesScreen.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/ConfirmDeleteDialog.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/SetupScreen.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt` and `ui/common/Utils.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/Format.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/Utils.kt` (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/SearchScreen.kt — androidx compose material3 1.4.0 samples `SearchBarSamples.kt` (SimpleSearchBarSample) and gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/smartalbum/SmartAlbumSearchScreen.kt` results grid (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/PlayerScreen.kt — TikTokCompose `app/src/main/java/com/example/tiktokcompose/ui/composables/TikTokScreen.kt` (Apache-2.0)
- androidApp/src/main/AndroidManifest.xml — gallery `Android/src/app/src/main/AndroidManifest.xml` (uses-native-library block, npu uses-feature, foreground service type) (Apache-2.0); platform-samples `samples/storage/src/main/AndroidManifest.xml` (media read permissions) (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/Access.kt — platform-samples `samples/storage/src/main/java/com/example/platform/storage/mediastore/SelectedPhotosAccess.kt` (permission sets per Android version, full / selected / denied check) (Apache-2.0)
- androidApp/src/main/kotlin/app/retriever/ModelDownload.kt — gallery `Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/DownloadAndTryButton.kt` (Apache-2.0)
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
| androidx.activity, androidx.annotation, androidx.appcompat, androidx.arch.core, androidx.autofill, androidx.collection, androidx.compose, androidx.compose.animation, androidx.compose.foundation, androidx.compose.material, androidx.compose.material3, androidx.compose.runtime, androidx.compose.ui, androidx.concurrent, androidx.core, androidx.cursoradapter, androidx.customview, androidx.drawerlayout, androidx.emoji2, androidx.exifinterface, androidx.fragment, androidx.graphics, androidx.interpolator, androidx.lifecycle, androidx.loader, androidx.media3, androidx.navigation, androidx.navigationevent, androidx.profileinstaller, androidx.recyclerview, androidx.resourceinspection, androidx.room, androidx.savedstate, androidx.sqlite, androidx.startup, androidx.tracing, androidx.vectordrawable, androidx.versionedparcelable, androidx.viewpager, androidx.window, androidx.work | Apache-2.0 |
| app.cash.sqldelight | Apache-2.0 |
| co.touchlab | Apache-2.0 |
| com.google.ai.edge.litert, com.google.ai.edge.litertlm | Apache-2.0 |
| com.google.android.datatransport | Apache-2.0 |
| com.google.android.gms | Android Software Development Kit License and ML Kit Terms of Service (Google Play services binaries, not open source) |
| com.google.android.odml | Android Software Development Kit License |
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
