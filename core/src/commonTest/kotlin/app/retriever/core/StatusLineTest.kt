package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One title, one count, one detail and at most one action for every state, the same on both apps (demo-flow.md §5). */
class StatusLineTest {
    @Test fun faceOnlySafetyWaitDoesNotClaimZeroLongFilesRemain() {
        for (stop in listOf(StopReason.PAUSED, StopReason.HOT, StopReason.POWER_SAVE, StopReason.UNPLUGGED)) {
            val status = ReadingPolicy.decide(
                ReadingProgress(read = 5),
                stop,
                false,
                false,
                DeviceWords.ANDROID,
                facesWaiting = FacesProgress(2, 3),
                processingStarted = true,
            )
            assertFalse(status.detail.orEmpty().contains("0 long files"))
            assertEquals("2 of 5 photos checked for people", status.count)
        }
    }

    /** With only faces left between batches, the line is about people, never the description pass all over again. */
    @Test fun aFacesOnlyWaitSaysFacesNotDescriptions() {
        val status = ReadingPolicy.decide(ReadingProgress(read = 5), null, false, false, DeviceWords.ANDROID, facesWaiting = FacesProgress(2, 3))
        assertEquals(
            listOf("Finding people in your photos", "2 of 5 photos checked for people", Words.strings.facesWaitingDetail(DeviceWords.ANDROID)),
            listOf(status.title, status.count, status.detail),
        )
        val running = ReadingPolicy.decide(ReadingProgress(read = 5), null, true, true, DeviceWords.ANDROID, faces = FacesProgress(2, 3), adaptive = true)
        assertEquals(Words.strings.facesDetail, running.detail, "no files-pass detail swapped in")
    }
    private val android = DeviceWords.ANDROID
    private val iphone = DeviceWords.ios("iPhone")

    /** 3 processed of 5. */
    private val waiting = ReadingProgress(read = 3, scheduled = 2)

    private fun decide(
        p: ReadingProgress = waiting,
        stop: StopReason? = null,
        running: Boolean = false,
        reading: Boolean = false,
        words: DeviceWords = android,
        session: Boolean = false,
        plugged: Boolean = false,
        faces: FacesProgress? = null,
        facesWaiting: FacesProgress? = null,
        people: PeopleModel? = null,
    ) = ReadingPolicy.decide(p, stop, running, reading, words, session, plugged, faces, facesWaiting, people)

    private fun row(s: ReadingStatus) = listOf(s.phase.name, s.title, s.count, s.detail, s.action?.label)

    @Test fun everyReadingStateHasItsTitleCountDetailAndAction() {
        val count = "3 of 5 files processed"
        val rows = listOf(
            decide(stop = StopReason.PAUSED) to listOf("PAUSED", "Paused", count, "Search still works.", "Resume"),
            decide(stop = StopReason.POWER_SAVE) to
                listOf("POWER_SAVE", "Paused · continues when Battery Saver is off", count, "Carries on when Battery Saver is off.", null),
            decide(stop = StopReason.HOT) to listOf("HOT", "Paused · continues when your phone cools down", count, "Carries on by itself.", null),
            decide(stop = StopReason.LOW_BATTERY) to
                listOf("LOW_BATTERY", "Paused · continues when you plug in", count, "Plug in and Retriever carries on.", null),
            decide(stop = StopReason.CHARGING_UP) to listOf(
                "CHARGING_UP",
                "Paused · continues when the battery has a bit more charge",
                count,
                "Processing starts once your battery has a bit more charge.",
                null,
            ),
            decide(stop = StopReason.UNPLUGGED) to
                listOf("CHARGING", "Paused · continues when you plug in", count, "Retriever carries on when your phone is charging.", "Start processing"),
            decide(stop = StopReason.MANUAL) to
                listOf("MANUAL", "Processing only when you start it", count, "You can find any file by its name.", "Start processing"),
            decide(stop = StopReason.BUDGET) to
                listOf("WAITING", "Paused", count, Words.strings.processingReadyDetail, "Start processing"),
            decide(session = false, words = iphone) to
                listOf("WAITING", "Paused", count, Words.strings.processingReadyDetail, "Start processing"),
            // The demo's own passes after its Continue: the same title and whole-phone count as any read.
            decide(waiting.copy(sample = true), reading = true) to
                listOf("READING", "Processing your files", count, "You can close Retriever; it carries on. Search works the whole time.", "Pause"),
            // Not now pauses: the line says what can be found, counts the whole phone, and offers to start.
            decide(ReadingProgress(read = 30, notScheduled = 25_763, sample = true, notNow = true), stop = StopReason.PAUSED) to
                listOf("NOT_NOW", "Only names can be found for now", "30 of ${formatCount(25_793)} files processed", null, "Start processing"),
            decide(reading = true) to
                listOf("READING", "Processing your files", count, "You can close Retriever; it carries on. Search works the whole time.", "Pause"),
            decide(reading = true, plugged = true) to
                listOf("READING", "Processing your files", count, "You can close Retriever; it carries on. Search works the whole time.", "Pause"),
            decide(ReadingProgress(read = 4, meaningWaiting = 3), reading = true) to listOf(
                "MEANING",
                "Adding search by description",
                "1 of 4 files have search by description",
                "So you can search by what things mean, and by what’s in your photos. On this phone only.",
                "Pause",
            ),
            // Faces come once the files are done: the line counts photos checked.
            decide(running = true, plugged = true, faces = FacesProgress(2_046, 14_659)) to listOf(
                "FACES",
                "Finding people in your photos",
                "2,046 of 16,705 photos checked for people",
                "An optional extra step. Your files are all searchable, and nothing is processed again.",
                "Pause",
            ),
            // Before it, Play delivers People's model: no count of files beside a download.
            decide(running = true, plugged = true, people = PeopleModel.GETTING) to listOf(
                "FACES",
                "Finding people in your photos",
                null,
                "A one-time download for People. Your files are all searchable.",
                "Pause",
            ),
            decide(ReadingProgress(partlyRead = 2, laterPasses = 2), reading = true) to
                listOf("DEEPENING", "Processing the rest of some long files", null, "2 to go. You can find them by their beginning.", "Pause"),
            decide(ReadingProgress(read = 3), running = true) to listOf("LOOKING", "Checking for new files…", "3 of 3 files processed", null, null),
            decide(ReadingProgress(read = 3)) to
                listOf(
                    "CAUGHT_UP",
                    "All done. Retriever has processed everything it can",
                    "3 of 3 files processed",
                    "New files are processed at the times you choose in Settings.",
                    "Dismiss",
                ),
            decide(ReadingProgress(read = 3, partlyRead = 1)) to listOf(
                "LIMITED",
                "All done. Retriever has processed everything it can",
                "4 of 4 files processed",
                "A few long files were processed only in part. You can find them by their beginning.",
                "Dismiss",
            ),
            decide(ReadingProgress(read = 3, scheduled = 2, counted = false), reading = true) to listOf("COUNTING", "Getting ready…", null, null, null),
        )
        for ((status, expected) in rows) assertEquals(expected, row(status))
    }

    @Test fun iosSaysItsOwnDeviceAndThatRetrieverStaysOpen() {
        assertEquals("Paused · continues when your iPhone cools down", decide(stop = StopReason.HOT, words = iphone).title)
        assertEquals(Words.strings.processingReadyDetail, decide(session = false, words = iphone).detail)
        assertEquals(
            "Search works the whole time. Retriever processes your files while it’s open, and sometimes when iOS allows.",
            decide(reading = true, words = iphone).detail,
        )
        assertEquals(Words.strings.processingReadyDetail, decide(stop = StopReason.DONE, words = DeviceWords.ios("iPad")).detail)
        // An empty phone never says "0 of 0 files processed".
        assertNull(decide(ReadingProgress(), stop = StopReason.PAUSED).count)
    }

    /** Start processing records intent; only a running reader claims active processing. */
    @Test fun startProcessingWaitsUntilTheReaderRuns() {
        val tapped = decide(session = true)
        assertEquals(listOf("WAITING", "Processing your files", "Pause"), listOf(tapped.phase.name, tapped.title, tapped.action?.label))
        assertFalse(tapped.active)
        assertFalse(tapped.offersPause(waiting), "the line's Pause is the only one")
        assertEquals(ReadingPhase.READING, decide(running = true, session = true).phase)
        assertEquals(ReadingPhase.MEANING, decide(ReadingProgress(read = 4, meaningWaiting = 3), running = true, session = true).phase)
        assertEquals(ReadingPhase.DEEPENING, decide(ReadingProgress(partlyRead = 2, laterPasses = 2), running = true, session = true).phase)
        assertEquals(ReadingPhase.WAITING, decide(ReadingProgress(read = 4, meaningWaiting = 3), session = true).phase)
        assertEquals(ReadingPhase.WAITING, decide(ReadingProgress(partlyRead = 2, laterPasses = 2), session = true).phase)
        assertEquals(ReadingPhase.HOT, decide(stop = StopReason.HOT, session = true).phase, "a stop still wins")
        assertEquals(ReadingPhase.CAUGHT_UP, decide(ReadingProgress(read = 3), session = true).phase, "nothing left to process")
    }

    @Test fun waitingWorkOffersStartAfterItsSessionIsLost() {
        val lost = decide()
        assertEquals(ReadingPhase.WAITING, lost.phase)
        assertEquals(StatusAction.START, lost.action)
        assertFalse(lost.active)
        assertEquals(StatusAction.START, decide(words = iphone).action)
    }

    /** Every file processed, faces left: a stop counts the photos, not "26,376 of 26,376 files processed" beside work to do. */
    @Test fun aStopAfterEveryFileCountsTheFacesLeft() {
        val allFiles = ReadingProgress(read = 4, partlyRead = 1, laterPasses = 1)
        val hot = decide(allFiles, stop = StopReason.HOT, facesWaiting = FacesProgress(2, 8))
        assertEquals(listOf("HOT", "2 of 10 photos checked for people"), listOf(hot.phase.name, hot.count))
        assertEquals(2L to 10L, hot.done to hot.total)
        assertEquals("3 of 5 files processed", decide(stop = StopReason.HOT, facesWaiting = FacesProgress(2, 8)).count, "files left come first")
        assertEquals(ReadingPhase.HOT, decide(stop = StopReason.HOT, running = true, faces = FacesProgress(1, 9)).phase, "a stop wins over faces")
        // A Pixel paused for heat counted photos for faces while 3,392 photos still waited for search by description,
        // which goes first.
        val meaningLeft = decide(ReadingProgress(read = 5, meaningWaiting = 1), stop = StopReason.HOT, facesWaiting = FacesProgress(2, 8))
        assertEquals("4 of 5 files have search by description", meaningLeft.count, "then the search by description")
        // Photos' words come before faces too (Retriever.runBatch): a batch on them says so, not faces.
        val wordsLeft = decide(ReadingProgress(read = 5, wordsWaiting = 3), running = true, reading = true, facesWaiting = FacesProgress(2, 8))
        assertEquals(listOf("READING", Words.strings.readingPhotoWordsTitle), listOf(wordsLeft.phase.name, wordsLeft.title))
    }

    /** A batch around its faces pass (sync, grouping) stays on faces: it said Finishing long files, then faces, every batch. */
    @Test fun aBatchWithFacesLeftShowsFacesBetweenPasses() {
        val between = decide(ReadingProgress(read = 4, partlyRead = 1, laterPasses = 1), running = true, facesWaiting = FacesProgress(2, 8))
        assertEquals(listOf("FACES", "2 of 10 photos checked for people"), listOf(between.phase.name, between.count))
        assertEquals(Words.strings.facesDetail, between.detail)
        // Once begun, faces finish before files that came meanwhile, so a file waiting doesn't take the line from them.
        val newFile = ReadingPolicy.decide(waiting, null, true, false, android, facesWaiting = FacesProgress(2, 8), facesStarted = true)
        assertEquals(ReadingPhase.FACES, newFile.phase)
    }

    @Test fun queuedAndroidFacesAreStillWorkAfterEveryFileIsProcessed() {
        val queued = ReadingPolicy.decide(
            ReadingProgress(read = 4),
            null,
            false,
            false,
            android,
            facesWaiting = FacesProgress(2, 8),
            processingStarted = true,
        )
        assertEquals(ReadingPhase.WAITING, queued.phase)
        assertEquals(StatusAction.RESUME, queued.action)
        assertEquals("2 of 10 photos checked for people", queued.count)
        assertFalse(queued.active)
        val hot = ReadingPolicy.decide(
            ReadingProgress(read = 4),
            StopReason.HOT,
            false,
            false,
            android,
            facesWaiting = FacesProgress(2, 8),
            processingStarted = true,
        )
        assertEquals(ReadingPhase.HOT, hot.phase)
        assertNull(hot.action)
    }

    /** Processing offers one Pause control for an active session. */
    @Test fun pauseShowsOnlyWhenNoSessionRuns() {
        val session = decide(running = true, reading = true, session = false, words = iphone)
        assertEquals(StatusAction.PAUSE, session.action)
        assertFalse(session.offersPause(waiting), "the line has Pause itself")
        assertFalse(decide(running = true, reading = true, words = iphone).offersPause(waiting), "the line has Pause itself")
        assertFalse(decide(running = true, reading = true).offersPause(waiting), "Android's line has Pause itself")
        assertTrue(decide(stop = StopReason.HOT).offersPause(waiting), "a wait has no button of its own")
        assertFalse(decide(stop = StopReason.PAUSED).offersPause(waiting), "already paused: Resume only")
        assertFalse(decide(ReadingProgress(read = 3)).offersPause(ReadingProgress(read = 3)), "nothing left to pause")
    }

    /** Setup shows a stop's reason only: "All done" before the plan exists is not a pause, nor is the system's turn. */
    @Test fun onlyAStopIsAPause() {
        for (s in listOf(decide(ReadingProgress(read = 3)), decide(ReadingProgress(read = 3), running = true), decide(waiting.copy(sample = true)))) {
            assertNull(s.pause, s.phase.name)
        }
        assertEquals("Paused", decide(stop = StopReason.PAUSED).pause)
        assertEquals("Paused", decide(waiting.copy(sample = true), stop = StopReason.PAUSED).pause, "the demo's own pause still says why")
        assertEquals("Paused · continues when you plug in", decide(stop = StopReason.UNPLUGGED).pause)
    }

    @Test fun manualReadingNeverPromisesToReadByItself() {
        assertNull(decide(ReadingProgress(read = 3), stop = StopReason.MANUAL).detail)
    }

    /**
     * The one count rule: processed is every file no longer waiting (inside, by name only, couldn't open), of every file.
     * The count line and the bar are the same two numbers, and a name-only or failed file counts on both.
     */
    @Test fun theCountAndTheBarAreTheSameNumbers() {
        val status = decide(ReadingProgress(read = 312, nameOnly = 20, couldntRead = 3, scheduled = 25_412), reading = true)
        assertEquals("335 of ${formatCount(25_747)} files processed", status.count)
        assertEquals(335L to 25_747L, status.done to status.total)
        // Home after the demo counts the whole phone; deepening has nothing left of the first reads to count, so no full bar.
        val demo = decide(ReadingProgress(read = 117, scheduled = 7, sample = true), reading = true)
        assertEquals(listOf("117 of 124 files processed", 117L, 124L), listOf(demo.count, demo.done, demo.total))
        assertNull(decide(ReadingProgress(partlyRead = 2, laterPasses = 2), reading = true).total)
        // The download's bar is its percent of 100, the same shape.
        val download = ReadingPolicy.line(status, true, true, 5, android, DownloadState(37, stopped = false, size = "388 MB"))!!
        assertEquals(37L to 100L, download.done to download.total)
        assertNull(download.count)
    }

    /** Home's line moves with every file: 30, 31 and 32 processed are three different lines. */
    @Test fun theCountMovesWithEveryFile() {
        val lines = (30L..32L).map { done -> decide(ReadingProgress(read = done, scheduled = 25_793 - done), reading = true).count }
        assertEquals(3, lines.toSet().size, lines.toString())
        assertEquals("31 of ${formatCount(25_793)} files processed", lines[1])
    }

    /**
     * The demo's 30 carry over: widening from the demo to the whole phone is 30 of 25,793 (about 0.1%), never a full bar,
     * whether or not the rest is queued yet.
     */
    @Test fun wideningFromTheDemoGivesATinyBarNotAFullOne() {
        val queued = ReadingProgress(read = 28, nameOnly = 1, couldntRead = 1, scheduled = 25_763)
        for (p in listOf(queued, queued.copy(scheduled = 0, notScheduled = 25_763))) {
            for (s in listOf(decide(p, reading = true), decide(p), decide(p, stop = StopReason.UNPLUGGED))) {
                assertEquals(30L to 25_793L, s.done to s.total, s.phase.name)
                assertTrue(s.done!!.toDouble() / s.total!! < 0.002, "${s.phase}: a tiny bar")
                assertEquals("30 of ${formatCount(25_793)} files processed", s.count)
            }
        }
    }

    /** Home and Processing draw the same status; Processing's Details breaks the same X down and ends with Y − X to go. */
    @Test fun homeAndProcessingAgree() {
        val p = ReadingProgress(read = 535, partlyRead = 4, nameOnly = 597, scheduled = 25_125)
        val s = decide(p, reading = true)
        assertEquals("${formatCount(1_136)} of ${formatCount(26_261)} files processed", s.count)
        assertEquals(
            s,
            ReadingPolicy.line(s, libraryChosen = true, hasAccess = true, libraryTotal = p.libraryTotal, words = android),
            "Home shows core's status as is",
        )
        assertEquals(535L + 4 + 597, s.done)
        assertEquals(formatCount(25_125), Words.breakdown(p).single { it.label == "Still to process" }.count)
        assertEquals(s.total!! - s.done!!, p.waiting)
    }

    /** Not now and the demo in front differ by one bit: a person's pause during the demo is still a pause. */
    @Test fun notNowIsNotThePausedDemo() {
        val demo = ReadingProgress(read = 12, scheduled = 18, notScheduled = 25_763, sample = true)
        assertEquals(ReadingPhase.PAUSED, decide(demo, stop = StopReason.PAUSED).phase)
        assertEquals(ReadingPhase.NOT_NOW, decide(demo.copy(notNow = true), stop = StopReason.PAUSED).phase)
        assertEquals(
            StatusAction.START,
            decide(ReadingProgress(read = 30, notScheduled = 25_763, sample = true)).action,
            "the demo done, the rest waits for Start processing",
        )
    }

    /** Before the phone's files are all counted the line says so, with no count and no bar; Home shows it even with none yet. */
    @Test fun countingYourFilesUntilTheFirstFullCount() {
        val counting = decide(ReadingProgress(read = 30, scheduled = 400, counted = false), reading = true)
        assertEquals(ReadingPhase.COUNTING to "Getting ready…", counting.phase to counting.title)
        assertEquals(listOf(null, null, null), listOf(counting.count, counting.done, counting.total))
        assertEquals(counting, ReadingPolicy.line(counting, true, true, 0, android))
        val demo = decide(ReadingProgress(read = 3, scheduled = 2, sample = true, counted = false), reading = true)
        assertNotEquals(ReadingPhase.COUNTING, demo.phase, "the demo has its own count")
    }

    @Test fun homeLeadsWithAccessSetupAndTheDownload() {
        val reading = decide(reading = true)
        fun line(
            chosen: Boolean = true,
            access: Boolean = true,
            total: Long = 5,
            words: DeviceWords = android,
            download: DownloadState? = null,
            r: ReadingStatus = reading,
        ) = ReadingPolicy.line(r, chosen, access, total, words, download)

        assertEquals(
            listOf("NO_ACCESS", "Retriever can’t see your files", null, "Search still works on the files it has already processed.", "Allow access"),
            row(line(chosen = false, access = false)!!),
        )
        assertEquals(
            "Search still works on the files it has already processed. This opens your iPhone’s Settings.",
            line(access = false, words = iphone)?.detail,
        )
        assertEquals(
            listOf("SETTING_UP", "Finish setting up", null, "Choose what Retriever can look through.", "Continue"),
            row(line(chosen = false)!!),
        )
        assertNull(line(total = 0), "an empty library shows Home's empty state")
        assertEquals(reading, line())

        val downloading = line(download = DownloadState(37, stopped = false, size = "388 MB"))!!
        assertEquals(listOf("DOWNLOADING", "Downloading search by description · 37%", null, "You can keep using Retriever.", "Stop"), row(downloading))
        assertEquals("Downloading search by description", line(download = DownloadState(null, false, "388 MB"))?.title)

        val stopped = DownloadState(37, stopped = true, size = "388 MB")
        assertEquals(reading, line(download = stopped), "a stopped download gives way while reading runs")
        assertEquals(
            listOf(
                "DOWNLOAD_STOPPED",
                "Download paused",
                null,
                "It carries on when you’re on Wi-Fi. If your phone is full, it needs 388${Words.NBSP}MB free.",
                "Try again",
            ),
            row(line(download = stopped, r = decide(stop = StopReason.UNPLUGGED))!!),
        )
        assertEquals(
            listOf("TUNING", "Getting ready…", null, "About a minute. Finding a processing speed that suits this phone.", null),
            row(line(download = DownloadState(null, false, "388 MB", settingUp = true))!!),
        )
        assertEquals(StatusAction.TRY_AGAIN, line(download = DownloadState(null, true, "388 MB", settingUp = true), r = decide())?.action)
    }

    @Test fun noStateSaysHoursNightsPercentOrNewFiles() {
        val all = StopReason.entries.map { decide(stop = it) } + listOf(
            decide(session = true),
            decide(reading = true),
            decide(waiting.copy(sample = true), reading = true),
            decide(ReadingProgress(read = 3)),
            decide(ReadingProgress(read = 3, sample = true, notNow = true)),
            decide(waiting.copy(counted = false)),
        )
        for (s in all) {
            val text = "${s.title} ${s.count} ${s.detail}"
            for (word in listOf("hour", "night", "morning", "session", "schedule", "% done", "files done")) {
                assertTrue(word !in text, "“$text” says “$word”")
            }
            // "New files" meant old ones in the never-falling count; files that really are new may still be named so.
            assertTrue("new file" !in s.count.orEmpty(), "${s.count}")
            assertTrue("new file" !in s.title || s.phase == ReadingPhase.LOOKING, s.title)
        }
    }

    /** One journey: each stage done, now or next from the same counts, numbered over the stages that apply here. */
    @Test fun theStagesReadAsOneJourney() {
        fun states(s: ReadingStatus) = s.journey?.rows.orEmpty().joinToString(" ") { "${it.stage}:${it.state}" }
        // Mid-stage 2: every photo has search by description, their words are read now.
        val words = ReadingPolicy.decide(ReadingProgress(read = 20, wordsWaiting = 12, laterPasses = 15), null, true, true, android)
        assertEquals("NAMES:DONE MEANING:DONE WORDS:NOW PEOPLE:OFF REST:NEXT", states(words))
        assertEquals("Step 2 of 3", words.journey?.step, "People off is optional, not a step")
        assertEquals(listOf(StageState.DONE, StageState.NOW, StageState.NEXT), words.journey?.segments, "one segment per step")
        assertEquals(12L, words.journey?.now?.left)
        // People on, and no file with later parts once every file is processed: no long-files step. A pause keeps its reason
        // as the title; the step is only the number.
        val paused = ReadingPolicy.decide(ReadingProgress(read = 20, wordsWaiting = 12), StopReason.HOT, false, false, android, peopleOn = true)
        assertEquals("NAMES:DONE MEANING:DONE WORDS:NOW PEOPLE:NEXT", states(paused))
        assertEquals("Step 2 of 3", paused.journey?.step)
        val faces = ReadingPolicy.decide(ReadingProgress(read = 20), null, true, false, android, faces = FacesProgress(2, 8), peopleOn = true)
        assertEquals("Step 3 of 3", faces.journey?.step)
        // Faces begun run ahead of new files, a stop included: the step is People's, as the count is.
        val facesHeld = ReadingPolicy.decide(
            ReadingProgress(read = 20, scheduled = 5),
            StopReason.HOT,
            false,
            false,
            android,
            facesWaiting = FacesProgress(2, 8),
            facesStarted = true,
            peopleOn = true,
        )
        assertEquals("NAMES:DONE MEANING:NEXT WORDS:DONE PEOPLE:NOW REST:NEXT", states(facesHeld))
        assertEquals(Words.facesOf(2, 10), facesHeld.count)
        // The model still to come: files are read for their words first and step 1 lasts until descriptions are added; their
        // later parts may still come, so the long-files step stays.
        val noModel = ReadingPolicy.decide(ReadingProgress(read = 20, meaningWaiting = 20), null, true, false, android)
        assertEquals("NAMES:DONE MEANING:NOW WORDS:NEXT PEOPLE:OFF REST:NEXT", states(noModel))
        assertEquals("Step 1 of 3", noModel.journey?.step)
        // Photos left out of processing have no words to read.
        val noPhotos = ReadingPolicy.decide(ReadingProgress(read = 20, scheduled = 5), null, true, true, android, photosExcluded = true)
        assertEquals("NAMES:DONE MEANING:NOW PEOPLE:OFF REST:NEXT", states(noPhotos))
        // Text-only phones read photos' words in the first pass; all done leaves no step.
        val done = ReadingPolicy.decide(ReadingProgress(read = 20), null, false, false, android, textOnly = true)
        assertEquals("NAMES:DONE MEANING:DONE PEOPLE:OFF", states(done))
        assertNull(done.journey?.step)
        assertNull(ReadingPolicy.decide(ReadingProgress(read = 2, scheduled = 3, sample = true), null, true, true, android).journey, "the demo has none")
    }

    /** The journey as lived: the first sighting seeds silently, a newly finished stage gets one note, new files aren't step 1. */
    @Test fun aJourneyRemembersItsFinishedStages() {
        fun journey(progress: ReadingProgress) = ReadingPolicy.decide(progress, null, true, true, android).journey ?: Journey(emptyList())
        val (_, seeded) = journey(ReadingProgress(read = 20, wordsWaiting = 12, laterPasses = 3)).remembered(Prefs(), peopleOffered = false)
        assertEquals(setOf(Stage.NAMES, Stage.MEANING), seeded?.stagesDone)
        assertNull(seeded?.stageNote, "an update fires no note")
        val restLeft = journey(ReadingProgress(read = 20, laterPasses = 3))
        val (shown, noted) = restLeft.remembered(seeded ?: Prefs(), peopleOffered = false)
        assertEquals(listOf(Stage.WORDS, Stage.WORDS), listOf(noted?.stageNote, shown.note))
        assertTrue(shown.rows.none { it.stage == Stage.PEOPLE }, "no Turn on where People can't work")
        val dismissed = (noted ?: Prefs()).copy(stageNote = null)
        assertNull(restLeft.remembered(dismissed, peopleOffered = false).second, "Got it sticks")
        val newPhoto = journey(ReadingProgress(read = 20, scheduled = 1)).remembered(dismissed, peopleOffered = true).first
        assertEquals(Words.strings.newFiles, newPhoto.step)
        assertTrue(newPhoto.segments.isEmpty(), "no step indicator beside new files")
        assertEquals(StageState.DONE, newPhoto.rows.first { it.stage == Stage.WORDS }.state, "a finished stage stays ticked")
        // All done is the one celebration: the last stage's note is dropped, and the long files' rest counts as finished.
        val (allDone, finished) = journey(ReadingProgress(read = 20)).remembered(noted ?: Prefs(), peopleOffered = false)
        assertEquals(null to null, allDone.note to finished?.stageNote)
        val longFile = journey(ReadingProgress(read = 20, laterPasses = 1)).remembered(finished ?: Prefs(), peopleOffered = false).first
        assertEquals(Words.strings.newFiles, longFile.step)
        val newPhotos = journey(ReadingProgress(read = 20, scheduled = 1)).remembered(finished ?: Prefs(), peopleOffered = false).first
        assertTrue(newPhotos.rows.none { it.stage == Stage.REST }, "no long-files row comes back ticked with new files")
    }
}
