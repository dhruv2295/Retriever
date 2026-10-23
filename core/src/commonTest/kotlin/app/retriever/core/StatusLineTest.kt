package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One title, one count, one detail and at most one action for every state, the same on both apps (demo-flow.md §5). */
class StatusLineTest {
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
        minutes: Int = 0,
        plugged: Boolean = false,
    ) = ReadingPolicy.decide(p, stop, running, reading, words, minutes, plugged)

    private fun row(s: ReadingStatus) = listOf(s.phase.name, s.title, s.count, s.detail, s.action?.label)

    @Test fun everyReadingStateHasItsTitleCountDetailAndAction() {
        val count = "3 of 5 files processed"
        val rows = listOf(
            decide(stop = StopReason.PAUSED) to listOf("PAUSED", "Paused", count, "Search still works.", "Resume"),
            decide(stop = StopReason.POWER_SAVE) to
                listOf("POWER_SAVE", "Paused while Battery Saver is on", count, "Carries on when Battery Saver is off.", null),
            decide(stop = StopReason.HOT) to listOf("HOT", "Paused to let your phone cool down", count, "Carries on by itself.", null),
            decide(stop = StopReason.LOW_BATTERY) to listOf("LOW_BATTERY", "Paused, battery is low", count, "Plug in and Retriever carries on.", null),
            decide(stop = StopReason.CHARGING_UP) to
                listOf("CHARGING_UP", "Charging up first", count, "Processing starts once your battery has a bit more charge.", null),
            decide(stop = StopReason.BUSY) to
                listOf("BUSY", "Waiting until you put your phone down", count, "Or open Retriever to carry on now.", null),
            decide(stop = StopReason.UNPLUGGED) to
                listOf("CHARGING", "Waits until you charge", count, "Retriever carries on when your phone is charging.", "Process for 15 minutes"),
            decide(stop = StopReason.MANUAL) to
                listOf("MANUAL", "Processing only when you start it", count, "You can find any file by its name.", "Process for 15 minutes"),
            decide(stop = StopReason.BUDGET) to
                listOf("WAITING", "Taking a short break", count, "Your phone decides when Retriever carries on. Nothing is lost.", null),
            decide(minutes = 12) to listOf("READ_NOW", "Processing for 15 minutes · 12 min left", count, "Uses some battery. Stops on its own.", "Stop"),
            decide(waiting.copy(sample = true), reading = true) to
                listOf("SAMPLE", "Processing the demo files", null, "About a minute. Nothing is changed or sent.", null),
            // Not now pauses: the line says what can be found, counts the whole phone, and offers the choice again.
            decide(ReadingProgress(read = 30, notScheduled = 25_763, sample = true, notNow = true), stop = StopReason.PAUSED) to
                listOf("NOT_NOW", "Only names can be found for now", "30 of ${formatCount(25_793)} files processed", null, "Choose when to process"),
            decide(reading = true) to
                listOf("READING", "Processing your files", count, "You can close Retriever; it carries on. Search works the whole time.", null),
            decide(reading = true, plugged = true) to
                listOf("READING", "Processing while you charge", count, "You can close Retriever; it carries on. Search works the whole time.", null),
            decide(ReadingProgress(read = 4, meaningWaiting = 3), reading = true) to listOf(
                "MEANING",
                "Adding search by description",
                "1 of 4 files have search by description",
                "So you can search by what things mean, and by what’s in your photos. On this phone only.",
                null,
            ),
            decide(ReadingProgress(partlyRead = 2, laterPasses = 2), reading = true) to
                listOf("DEEPENING", "Processing the rest of some long files", null, "2 to go. You can find them by their beginning.", null),
            decide(ReadingProgress(read = 3), running = true) to listOf("LOOKING", "Checking for new files…", "3 of 3 files processed", null, null),
            decide(ReadingProgress(read = 3)) to
                listOf(
                    "CAUGHT_UP",
                    "All done. Retriever has processed everything it can",
                    "3 of 3 files processed",
                    "It processes new files by itself.",
                    "Dismiss",
                ),
            decide(ReadingProgress(read = 3, partlyRead = 1)) to listOf(
                "LIMITED",
                "All done. A few long files were processed only in part",
                "4 of 4 files processed",
                "You can find them by their beginning.",
                "Dismiss",
            ),
            decide(ReadingProgress(read = 3, scheduled = 2, counted = false), reading = true) to listOf("COUNTING", "Counting your files…", null, null, null),
        )
        for ((status, expected) in rows) assertEquals(expected, row(status))
    }

    @Test fun iosSaysItsOwnDeviceAndThatRetrieverStaysOpen() {
        assertEquals("Paused while Low Power Mode is on", decide(stop = StopReason.POWER_SAVE, words = iphone).title)
        assertEquals("Waiting until you put your iPhone down", decide(stop = StopReason.BUSY, words = iphone).title)
        assertEquals("Uses some battery. Keep Retriever open.", decide(minutes = 3, words = iphone).detail)
        assertEquals(
            "Search works the whole time. Retriever processes your files while it’s open, and sometimes when iOS allows.",
            decide(reading = true, words = iphone).detail,
        )
        assertEquals("Your iPad decides when Retriever carries on. Nothing is lost.", decide(stop = StopReason.DONE, words = DeviceWords.ios("iPad")).detail)
        // An empty phone never says "0 of 0 files processed".
        assertNull(decide(ReadingProgress(), stop = StopReason.PAUSED).count)
    }

    /** Settings' Read for 15 minutes shows at once: before the reader starts, and before a blocker-free turn would say WAITING. */
    @Test fun readNowShowsFromTheTap() {
        val tapped = decide(minutes = 15)
        assertEquals(ReadingPhase.READ_NOW to StatusAction.STOP, tapped.phase to tapped.action)
        assertTrue(tapped.active)
        assertEquals(ReadingPhase.HOT, decide(stop = StopReason.HOT, minutes = 15).phase, "a stop still wins")
        assertEquals(ReadingPhase.CAUGHT_UP, decide(ReadingProgress(read = 3), minutes = 15).phase, "nothing left to read")
    }

    /** Processing never shows Pause beside a session's Stop: one ends the 15 minutes, the other stopped everything. */
    @Test fun pauseShowsOnlyWhenNoSessionRuns() {
        val session = decide(running = true, reading = true, minutes = 12)
        assertEquals(StatusAction.STOP, session.action)
        assertFalse(session.offersPause(waiting), "the session's Stop is the only button")
        assertTrue(decide(running = true, reading = true).offersPause(waiting), "processing by the schedule can be paused")
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
        assertEquals("Waits until you charge", decide(stop = StopReason.UNPLUGGED).pause)
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
        // The demo has its own count; deepening has nothing left of the first reads to count, so no full bar.
        val demo = decide(ReadingProgress(read = 117, scheduled = 7, sample = true), reading = true)
        assertEquals(listOf(null, null, null), listOf(demo.count, demo.done, demo.total))
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
        assertEquals("${formatCount(25_125)} to go", Words.breakdown(p).substringAfterLast(" · "))
        assertEquals(s.total!! - s.done!!, p.waiting)
    }

    /** Not now and the demo in front differ by one bit: a person's pause during the demo is still a pause. */
    @Test fun notNowIsNotThePausedDemo() {
        val demo = ReadingProgress(read = 12, scheduled = 18, notScheduled = 25_763, sample = true)
        assertEquals(ReadingPhase.PAUSED, decide(demo, stop = StopReason.PAUSED).phase)
        assertEquals(ReadingPhase.NOT_NOW, decide(demo.copy(notNow = true), stop = StopReason.PAUSED).phase)
        assertEquals(
            StatusAction.CHOOSE_WHEN,
            decide(ReadingProgress(read = 30, notScheduled = 25_763, sample = true)).action,
            "the demo done, the choice waits",
        )
    }

    /** Before the phone's files are all counted the line says so, with no count and no bar; Home shows it even with none yet. */
    @Test fun countingYourFilesUntilTheFirstFullCount() {
        val counting = decide(ReadingProgress(read = 30, scheduled = 400, counted = false), reading = true)
        assertEquals(ReadingPhase.COUNTING to "Counting your files…", counting.phase to counting.title)
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
            listOf("TUNING", "Setting up processing for this phone", null, "About a minute. Finding a processing speed that suits this phone.", null),
            row(line(download = DownloadState(null, false, "388 MB", settingUp = true))!!),
        )
        assertEquals(StatusAction.TRY_AGAIN, line(download = DownloadState(null, true, "388 MB", settingUp = true), r = decide())?.action)
    }

    @Test fun noStateSaysHoursNightsPercentOrNewFiles() {
        val all = StopReason.entries.map { decide(stop = it) } + listOf(
            decide(minutes = 1),
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
}
