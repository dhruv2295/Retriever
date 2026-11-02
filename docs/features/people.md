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
- **Faces before reading.** `FacePass` runs at the start of a batch, before the model opens (faces never need it),
  under the deepen pass's rule: in the background only on the charger with the screen off. It takes the whole batch,
  and reading waits until every photo has been looked at: on a Pixel 8 Pro faces take ~0.3 s a photo against reading's
  ~1.5 s, so 16,700 photos have People whole in about 1.5 hours of a 7 to 8 hour first read. When faces may not run
  (in the background on battery), reading goes on as before. While it runs, the status line on Home, Processing and the
  notification says "Looking for faces in your photos" and counts photos looked at (`ReadingPhase.FACES`, from
  `IndexState.faces`): the files' count stands still meanwhile, and on a Pixel looked stuck. It reads photos a page at a time,
  saves each photo's faces (`saveFaces`), and groups every 25 photos. A photo that stops the app three times is left
  out (`MAX_FACE_ATTEMPTS`).
- **Unclear faces aren't kept.** A face whose raw SFace fingerprint is shorter than 9 (`MIN_FACE_STRENGTH`) is
  dropped when its photo is saved, on both platforms, since both hand core the raw fingerprint. The length tracks how
  well SFace can make the face out (as MagFace found for ArcFace-type models): on the Pixel a 38-face person of
  strangers, mirror selfies with the phone over the face, a logo, a screenshot and a dog measured 2.9–10.8, with 26 of
  27 under 9, against 10.6–14.6 for 114 clear faces; LFW's 5,425 faces (OpenCV's SFace) have 0.5% under 9 and a median
  of 12.5. Such faces look alike whoever they are, so kept, they grouped strangers as one person. Faces saved before
  the rule have no length to check: turning People off and on looks at every photo again.
- **Grouping only places new faces.** `groupNewFaces` places faces added since `faces_grouped_through`: each joins the
  person whose five most alike faces (`CLOSEST`; all of them for a smaller person) average the highest cosine, when
  that is at least 0.55 (`SAME_PERSON`), or pairs with another lone face at 0.55 to start a person. Older faces never
  move, which is what makes "Not this person" and merges stick without a rejection table.
- **Why closest faces, not the mean.** Until October 2026 a face joined the person whose mean fingerprint was closest at
  0.5. On the Pixel (8,412 faces) that failed three ways: a person in 1,493 photos over years had a mean like nobody in
  particular, so their selfies and glasses photos started other people (one scored 0.60 to the mean but 0.66 to its
  five closest faces, against at most 0.43 for a stranger); a person started from two wrong faces gathered a real
  person around them; and a mean of unrelated faces sat close enough to everything to keep taking more (a 38-face
  person of strangers and objects). On LFW (5,420 faces, 311 people, three orders) faces placed with someone else
  fell from 5–13 to 2 and same-photo pairs inside one person on the Pixel from 295 to 259, keeping 98% of one person's
  pairs together against 99.6%. At 0.5 the closest-faces rule merged two whole LFW people, so the bar is 0.55. Earlier
  measures of the bar: OpenCV's 0.363 put strangers together on a 3,474-photo library, and pairs from one photo grouped
  as one person fell from 231 at 0.363 to 109 at 0.5.
- **Grouping again.** A library grouped by an older rule (meta `faces_grouping_rule`) is grouped again once, from the
  stored fingerprints (`regroupDue`, at the start of a batch, even with no photo left to look at): every face in a
  person is placed afresh in id order, faces taken out stay out, and each name and "not the same person" answer moves
  to the new person holding most of the old person's faces. Pairs joined by "Same person?" are found again by grouping
  or asked again.
- **Corrections.** Rename (blank makes them unnamed again), "Same person as…" (the other group's photos join this
  one, and its name when this one has none), and "Not this person" on a photo's long-press menu (the person's faces in
  that photo leave them for good).
- **"Same person?"** One person can split into two groups when a whole series of their photos looks alike and unlike
  the rest (mirror selfies, the phone covering part of the face). Home asks about the closest pair of people it shows
  whose faces' means are at least 0.65 (`ASK_SAME_PERSON`), who share at most two photos (a collage or a mirror shows
  one person twice; on the Pixel two likely splits shared 1 and 2 photos with the person) and aren't named
  differently. Yes merges them into the one in more photos; No is kept (meta `people_not_same`) and that pair is never
  asked about again. It asks rather than merges because a wrong merge has no undo. 0.65 comes from LFW with the same
  SFace (5,420 faces, 311 people with six or more photos): one person split at random into groups of 3 to 10 scored
  0.70 or more in 99% of splits, strangers at most 0.59 in 160,000 pairs, and the relatives LFW has (the Bushes, father
  and sons and brothers; Venus and Serena Williams) at most 0.63. On the Pixel, with 12,906 photos looked at, ten pairs
  of people on Home reached 0.65; the closest (0.82) was one person with and without glasses.
- **Or by closest faces.** A person seen over years has a mean like nobody in particular, so a split of them can sit far
  from it: on the Pixel in November 2026 (338 people, 7,651 faces) a 7-photo split of a 1,450-photo person scored 0.51
  by means. Home also asks when half the smaller person's faces average 0.55 or more (`ASK_SAME_PERSON_CLOSEST`) to
  their five most alike faces of the other, as grouping compares a face to a person; that split scored 0.61. Grouped as
  the app groups (three orders), LFW left 41 people split: means at 0.65 asked about 36 of them, closest faces at 0.55
  about 23, and neither asked about a stranger (means at most 0.53, closest faces at most 0.44). The tight margin is
  relatives: father and son scored 0.53 by closest faces, 0.59 by means. On the Pixel this asks about six more pairs.
  Only pairs whose means are at least 0.4 are compared face by face: on the Pixel no pair below that scored more than
  0.46, and comparing all 56,953 pairs took 35 seconds. It never joins: no split on the Pixel scored past 0.62.
- **One pair at a time.** Home shows the pairs closest first, with previous and next, so a pair the person isn't sure
  about can wait without holding up the rest; answering one shows the next in its place. Debug builds number the
  People tiles and name the card's two tiles, so a tester can say which people they mean (Android).
- **Joining without asking.** Pairs at 0.75 or more (`JOIN_SAME_PERSON`) join by the same rules, closest first, after
  each grouping and after each answer (so a library grouped before joining existed joins too); the question then asks
  only between 0.65 and 0.75. Grouping places one face at a time and never looks back, so the
  first photos of a look (glasses, a phone over the face) can start a second person that later photos of that look
  then match best; comparing whole people is the steadier test. On the Pixel all five pairs at 0.75 or more (0.77 to
  0.86) were one person, confirmed by the owner, and the next pair down scored 0.74. Not measured: identical twins
  (LFW has none). A join, like a merge, has no undo; "Not this person" takes photos out one at a time.
- **A person's picture** is the face most like the person (closest to their faces' mean) among their larger faces (the
  bigger half by area) not cut off by the photo's edge (`coverOf`). The largest face, the earlier rule, was often a
  close-up cut off at the edge: on the Pixel it ranked 403rd of 406 faces in how much it looked like that person.
- **Search.** `Retriever`'s query parsing takes named people out of the query, longest name first (`namedIn`), and
  narrows the results to their photos; the rest of the query searches as usual. A quoted (literal) query skips it.
- **Home and the person's screen.** Home's People strip shows people in at least three photos (named ones from their
  first) as round crops of their picture, then "Same person?" when there is a pair to ask about, and "Looking for
  faces · N to go" while photos wait. A person opens as
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
2. **A face taken out of a person, or moved by a merge, is never placed again** by later grouping; faces move only with
   their whole person, in a merge or a join, or once when a new grouping rule groups the library again.
3. **Faces go first:** while a photo waits to be looked at and faces may run, the batch reads no files.

## Tests

- `FacesTest`: alignment, an unclear face not kept, grouping (a face like a few of a big person's faces joins though their mean is far),
  grouping an older library again (names, answers and taken-out faces kept), naming and forgetting, merging, the pass's retries and a full disk; a person's
  picture; "Same person?" (asks the closest pair, sharing up to two photos; never three, named differently or answered
  No; also by closest faces when a person seen over years has a diffuse mean, never joined that way) and forgetting its
  answers with People; joining pairs at 0.75 or more, never one answered No.
- `PeopleTest` (androidHostTest): a named person is searchable and their photos page; turning People off forgets them;
  reading waits while faces are left and reads every file once they are done.
- `MigrationTest`: schema 10 adds the face tables.
- `BrowseViewModelTest`: a person's photos page, and "Not this person" takes one out.
- `FaceFinderTests` (iOS): core's alignment and SFace know `Person.jpg` (a generated picture of no real person) in its
  mirror image; an unreadable photo is nil. Vision's face landmarks fail on the simulator (and collapse when pinned to
  its CPU), so finding the face, and no face being an empty list, run only on a device.

## Known limits

- Faces seen side on aren't recognized: SFace knows faces from the front, and ML Kit gives no eyes for a profile.
- About 175 photos a minute on a Pixel 8 Pro: ML Kit's accurate face detection (~190 ms a photo) runs one photo at a
  time across the app, so lanes of photos in parallel gained 3%, and its fast mode was 10% quicker but kept 6% fewer
  faces.
- SFace is the ceiling, not our pipeline: OpenCV's own detector and SFace give the same fingerprints (median cosine
  0.95 to ours on one library) and the same overlap, strangers up to ~0.45 and one person down to ~0.3. Grouping
  quality was measured on one library only, without labels beyond faces sharing a photo.
- The bundled model adds 19.3 MB to the APK and to git history; the int8 variant (9.9 MB) missed the fidelity target.
- SFace's training data is not named by its authors (see `THIRD_PARTY_NOTICES.md`).
- With People on, the first read starts only after faces finish (about 1.5 hours of charging for 16,700 photos on a
  Pixel 8 Pro), so search by text and description fills in later; search by file name works throughout.
- On the iOS simulator Vision can't find faces, so each photo's three tries fail and People stays empty there; the
  `home.people` debug route shows the strip.
