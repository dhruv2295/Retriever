# People

## Purpose

Groups the faces in the person's photos so they can name someone and then find that person's photos by name ("asha
beach"). Off until they turn on **Recognize people** in Settings → Privacy; turning it off forgets every face and name.
Everything runs on the phone: nothing is uploaded, and the fingerprints live only in the index.

## Mechanism and why

- **Two models, one per job.** The platform finds faces and their five landmarks (ML Kit face detection on Android,
  Apple Vision on iOS). SFace (OpenCV Zoo, Apache-2.0) turns each aligned 112 px face into a 128-number fingerprint.
  The detector is the platform's own; SFace is bundled (`androidApp/src/main/assets/sface.tflite`, fp16, 19.3 MB;
  `iosApp/Retriever/Faces/SFace.mlpackage`, Core ML fp16, 18.5 MB), so People needs no download. On iOS the core calls
  the app's `FaceFinder` through `NativeFaceReader` (Bridges.kt).
- **Alignment is core's.** `FaceAlign` fits the five landmarks to SFace's template by least squares; template slots
  follow image x, never the detector's left/right labels. ML Kit gives the nose base where the template has the tip;
  the fit absorbs it (measured: same-person cosine 0.84 vs OpenCV's 0.91 on the same pair, cross-person 0.19).
- **A pass after reading.** `FacePass` runs once nothing waits to be read, before the model opens (faces never need it),
  under the deepen pass's rule: in the background only on the charger with the screen off. It reads photos a page at a
  time, saves each photo's faces (`saveFaces`), and groups every 25 photos. A photo that stops the app three times is
  left out (`MAX_FACE_ATTEMPTS`).
- **Grouping only places new faces.** `groupNewFaces` places faces added since `faces_grouped_through`: each joins the
  person whose mean fingerprint is closest at cosine ≥ 0.363 (OpenCV's SFace threshold), or pairs with another lone
  face to start a person. Older faces never move, which is what makes "Not this person" and merges stick without a
  rejection table.
- **Corrections.** Rename (blank makes them unnamed again), "Same person as…" (the other group's photos join this
  one, and its name when this one has none), and "Not this person" on a photo's long-press menu (the person's faces in
  that photo leave them for good).
- **Search.** `Retriever`'s query parsing takes named people out of the query, longest name first (`namedIn`), and
  narrows the results to their photos; the rest of the query searches as usual. A quoted (literal) query skips it.
- **Home and the person's screen.** Home's People strip shows people in at least three photos (named ones from their
  first) as round crops of their cover face, and "Looking for faces · N to go" while photos wait. A person opens as
  `BrowseRoute(person = id)`: their photos newest first, with rename and merge in the top bar.

## Owner files

- `core/src/commonMain/kotlin/app/retriever/core/Faces.kt`, `db/Faces.sq`, `Retriever.kt` (People API, the pass in
  `indexBatch`, `parsedSearch`), `Prefs.kt` (`recognizePeople`)
- `core/src/androidMain/kotlin/app/retriever/core/android/AndroidFaceReader.kt`
- `androidApp/.../home/HomeSections.kt` (`PeopleSection`), `browse/BrowseScreen.kt`, `browse/PersonActions.kt`,
  `ui/components/FaceAvatar.kt`, `files/FileActions.kt` ("Not this person"), `settings/SettingsScreen.kt`
- `core/src/iosMain/.../Bridges.kt` (`NativeFaceReader`), `RetrieverIos.kt` (`observePeople`)
- `iosApp/Retriever/Core/FaceFinder.swift`, `Views/PeopleViews.swift` (`FaceAvatar`, `PersonActions`),
  `Views/HomeSections.swift` (`PeopleStrip`), `Views/BrowseView.swift`, `Views/ResultViews.swift` ("Not this person"),
  `AppModel+Settings.swift` (people actions), `Views/SettingsView.swift`

## Invariants

1. **Nothing about faces is written while Recognize people is off,** and turning it off empties every face table.
2. **A face taken out of a person, or moved by a merge, never moves again** by later grouping.
3. **Photos are read before faces are looked for;** the faces pass never delays reading.

## Tests

- `FacesTest`: alignment, grouping, naming and forgetting, merging, the pass's retries and a full disk.
- `PeopleTest` (androidHostTest): a named person is searchable and their photos page; turning People off forgets them.
- `MigrationTest`: schema 10 adds the face tables.
- `BrowseViewModelTest`: a person's photos page, and "Not this person" takes one out.
- `FaceFinderTests` (iOS): core's alignment and SFace know `Person.jpg` (a generated picture of no real person) in its
  mirror image; an unreadable photo is nil. Vision's face landmarks fail on the simulator (and collapse when pinned to
  its CPU), so finding the face, and no face being an empty list, run only on a device.

## Known limits

- Grouping quality on real libraries is unmeasured beyond the reference pairs above.
- The bundled model adds 19.3 MB to the APK and to git history; the int8 variant (9.9 MB) missed the fidelity target.
- SFace's training data is not named by its authors (see `THIRD_PARTY_NOTICES.md`).
- A new, large library waits for its whole first read before any face is looked for (invariant 3). Measured on a
  Pixel 8 Pro: about 1.5 s a photo, so 18k files is roughly 7 to 8 hours of charging with the screen off before People
  shows anyone.
- On the iOS simulator Vision can't find faces, so each photo's three tries fail and People stays empty there; the
  `home.people` debug route shows the strip.
