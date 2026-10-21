package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The clarity proposal's section 3: one title, one detail and at most one action for every state, the same on both apps. */
class StatusLineTest {
    private val android = DeviceWords.ANDROID
    private val iphone = DeviceWords.ios("iPhone")

    /** 3 read of 5: 60% read. */
    private val waiting = ReadingProgress(read = 3, scheduled = 2)

    private fun decide(
        p: ReadingProgress = waiting,
        stop: StopReason? = null,
        running: Boolean = false,
        reading: Boolean = false,
        words: DeviceWords = android,
        minutes: Int = 0,
        plugged: Boolean = false,
        mark: ReadMark = ReadMark(),
    ) = ReadingPolicy.decide(p, stop, running, reading, words, minutes, plugged, mark)

    private fun row(s: ReadingStatus) = listOf(s.phase.name, s.title, s.detail, s.action?.label)

    @Test fun everyReadingStateHasItsTitleDetailAndAction() {
        val rows = listOf(
            decide(stop = StopReason.PAUSED) to listOf("PAUSED", "Paused", "Search still works.", "Resume"),
            decide(stop = StopReason.POWER_SAVE) to listOf("POWER_SAVE", "Paused while Battery Saver is on", "Carries on when Battery Saver is off.", null),
            decide(stop = StopReason.HOT) to listOf("HOT", "Paused to let your phone cool down", "Carries on by itself.", null),
            decide(stop = StopReason.LOW_BATTERY) to listOf("LOW_BATTERY", "Paused, battery is low", "Plug in and Retriever carries on.", null),
            decide(stop = StopReason.CHARGING_UP) to
                listOf("CHARGING_UP", "Charging up first", "Reading starts once your battery has a bit more charge.", null),
            decide(stop = StopReason.BUSY) to listOf("BUSY", "Waiting until you put your phone down", "Or open Retriever and it reads now.", null),
            decide(stop = StopReason.UNPLUGGED) to
                listOf("CHARGING", "Waiting until you plug in · 60% read", "Retriever carries on when your phone is charging.", "Read for 15 minutes"),
            decide(stop = StopReason.MANUAL) to listOf("MANUAL", "Reading only when you start it", "You can find any file by its name.", "Read for 15 minutes"),
            decide(stop = StopReason.BUDGET) to
                listOf("WAITING", "Taking a short break", "Your phone decides when Retriever carries on. Nothing is lost.", null),
            decide(minutes = 12) to listOf("READ_NOW", "Reading for 15 minutes · 12 min left", "Uses some battery. Stops on its own.", "Stop"),
            decide(waiting.copy(sample = true), reading = true) to
                listOf("SAMPLE", "Trying a few of your files", "About a minute. Nothing is changed or sent.", null),
            decide(
                ReadingProgress(read = 3, sample = true),
            ) to listOf("SAMPLE_READY", "Ready to try", "Search for a word you remember.", "Choose when to read"),
            decide(ReadingProgress(nameOnly = 3, sample = true)) to listOf(
                "NO_CONTENT",
                "Ready to find files by name",
                "To find them by what’s inside, Retriever reads them here on this phone. Nothing is sent anywhere.",
                "Choose when to read",
            ),
            decide(reading = true) to
                listOf("READING", "Reading your files · 60% read", "You can close Retriever; it carries on. Search works the whole time.", null),
            decide(reading = true, plugged = true) to
                listOf("READING", "Reading while you charge · 60% read", "You can close Retriever; it carries on. Search works the whole time.", null),
            decide(ReadingProgress(read = 4, meaningWaiting = 3), reading = true) to
                listOf(
                    "MEANING",
                    "Adding search by meaning · 25%",
                    "So you can search by what things mean, and by what’s in your photos. On this phone only.",
                    null,
                ),
            decide(ReadingProgress(partlyRead = 2, laterPasses = 2), reading = true) to
                listOf("DEEPENING", "Reading the rest of some long files", "You can find them by their beginning.", null),
            decide(ReadingProgress(read = 3), running = true) to listOf("LOOKING", "Checking for new files…", null, null),
            decide(ReadingProgress(read = 3)) to
                listOf("CAUGHT_UP", "All done. Retriever has read everything it can", "It reads new files by itself.", "Dismiss"),
            decide(ReadingProgress(read = 3, partlyRead = 1)) to
                listOf("LIMITED", "All done. A few long files were read only in part", "You can find them by their beginning.", "Dismiss"),
        )
        for ((status, expected) in rows) assertEquals(expected, row(status))
    }

    @Test fun iosSaysItsOwnDeviceAndThatRetrieverStaysOpen() {
        assertEquals("Paused while Low Power Mode is on", decide(stop = StopReason.POWER_SAVE, words = iphone).title)
        assertEquals("Waiting until you put your iPhone down", decide(stop = StopReason.BUSY, words = iphone).title)
        assertEquals("Uses some battery. Keep Retriever open.", decide(minutes = 3, words = iphone).detail)
        assertEquals(
            "Search works the whole time. Retriever reads while it’s open, and sometimes when iOS allows.",
            decide(reading = true, words = iphone).detail,
        )
        assertEquals("Your iPad decides when Retriever carries on. Nothing is lost.", decide(stop = StopReason.DONE, words = DeviceWords.ios("iPad")).detail)
    }

    /** Settings' Read for 15 minutes shows at once: before the reader starts, and before a blocker-free turn would say WAITING. */
    @Test fun readNowShowsFromTheTap() {
        val tapped = decide(minutes = 15)
        assertEquals(ReadingPhase.READ_NOW to StatusAction.STOP, tapped.phase to tapped.action)
        assertTrue(tapped.active)
        assertEquals(ReadingPhase.HOT, decide(stop = StopReason.HOT, minutes = 15).phase, "a stop still wins")
        assertEquals(ReadingPhase.CAUGHT_UP, decide(ReadingProgress(read = 3), minutes = 15).phase, "nothing left to read")
    }

    /** Setup shows a stop's reason only: "All done" before the plan exists is not a pause, nor is the system's turn. */
    @Test fun onlyAStopIsAPause() {
        for (s in listOf(decide(ReadingProgress(read = 3)), decide(ReadingProgress(read = 3), running = true), decide(waiting.copy(sample = true)))) {
            assertNull(s.pause, s.phase.name)
        }
        assertEquals("Paused", decide(stop = StopReason.PAUSED).pause)
        assertEquals("Waiting until you plug in · 60% read", decide(stop = StopReason.UNPLUGGED).pause)
    }

    @Test fun manualReadingNeverPromisesToReadByItself() {
        assertNull(decide(ReadingProgress(read = 3), stop = StopReason.MANUAL).detail)
    }

    /** "N% read" never shows falling: new files are named until the old figure is passed, and the bar holds the figure. */
    @Test fun theShareReadNeverFalls() {
        val before = ReadMark().next(ReadingProgress(read = 40, scheduled = 60))
        assertEquals(ReadMark(40, 100), before)
        // 42 new files arrive: 40 of 142 is 28%.
        val more = ReadingProgress(read = 40, scheduled = 102)
        val status = decide(more, reading = true, mark = before)
        assertEquals("Reading 42 new files" to 40, status.title to status.percent)
        assertEquals(before, before.next(more), "the mark holds")
        // Read past the old figure: the share shows again and the mark moves on.
        val passed = ReadingProgress(read = 60, scheduled = 82)
        assertEquals("Reading your files · 42% read", decide(passed, reading = true, mark = before).title)
        assertEquals(ReadMark(42, 142), before.next(passed))
        // Fewer files (read ones deleted): the old figure stays, with no new files to name.
        assertEquals("Reading your files · 40% read", decide(ReadingProgress(read = 10, scheduled = 60), reading = true, mark = before).title)
        assertEquals("Reading 1 new file", decide(ReadingProgress(read = 40, scheduled = 61), reading = true, mark = before).title)
    }

    @Test fun theShareIsWholeAndReachesAHundredOnlyWhenNothingWaits() {
        assertEquals(99, percentRead(ReadingProgress(read = 999, scheduled = 1)))
        assertEquals(100, percentRead(ReadingProgress(read = 3)))
        assertEquals(100, percentRead(ReadingProgress()))
        assertEquals(0, percentRead(ReadingProgress(scheduled = 1)))
        assertEquals(ReadMark(12, 34), ReadMark.decode(ReadMark(12, 34).encode()))
        assertNull(ReadMark.decode("x,1"))
        assertNull(ReadMark.decode(null))
    }

    /** Run 5: after the download the rest of the library was still scheduled, so nothing said the meaning pass ran. */
    @Test fun theMeaningPassShowsItsProgressWhileFirstReadsRemain() {
        val s = decide(ReadingProgress(read = 40, scheduled = 60, meaningWaiting = 30), reading = true)
        assertEquals(ReadingPhase.READING, s.phase, "the title stays put while the passes interleave")
        assertEquals("Also adding search by meaning · 25%", s.detail)
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
            listOf("NO_ACCESS", "Retriever can’t see your files", "Search still works on the files it has already read.", "Allow access"),
            row(line(chosen = false, access = false)!!),
        )
        assertEquals(
            "Search still works on the files it has already read. This opens your iPhone’s Settings.",
            line(access = false, words = iphone)?.detail,
        )
        assertEquals(
            listOf("SETTING_UP", "Finish setting up", "Choose what Retriever can look through.", "Continue"),
            row(line(chosen = false)!!),
        )
        assertNull(line(total = 0), "an empty library shows Home's empty state")
        assertEquals(reading, line())

        val downloading = line(download = DownloadState(37, stopped = false, size = "388 MB"))!!
        assertEquals(listOf("DOWNLOADING", "Downloading search by meaning · 37%", "You can keep using Retriever.", "Stop"), row(downloading))
        assertEquals(37, downloading.percent)
        assertEquals("Downloading search by meaning", line(download = DownloadState(null, false, "388 MB"))?.title)

        val stopped = DownloadState(37, stopped = true, size = "388 MB")
        assertEquals(reading, line(download = stopped), "a stopped download gives way while reading runs")
        assertEquals(
            listOf(
                "DOWNLOAD_STOPPED",
                "Download paused",
                "It carries on when you’re on Wi-Fi. If your phone is full, it needs 388${Words.NBSP}MB free.",
                "Try again",
            ),
            row(line(download = stopped, r = decide(stop = StopReason.UNPLUGGED))!!),
        )
        assertEquals(
            listOf("TUNING", "Setting up reading for this phone", "About a minute. Finding a reading speed that suits this phone.", null),
            row(line(download = DownloadState(null, false, "388 MB", settingUp = true))!!),
        )
        assertEquals(StatusAction.TRY_AGAIN, line(download = DownloadState(null, true, "388 MB", settingUp = true), r = decide())?.action)
    }

    @Test fun noStateSaysHoursOrNights() {
        val all = StopReason.entries.map { decide(stop = it) } + listOf(
            decide(minutes = 1),
            decide(reading = true),
            decide(waiting.copy(sample = true), reading = true),
            decide(ReadingProgress(read = 3)),
        )
        for (s in all) {
            val text = "${s.title} ${s.detail}"
            for (word in listOf("hour", "night", "morning", "session", "schedule")) assertTrue(word !in text, "“$text” says “$word”")
        }
    }
}
