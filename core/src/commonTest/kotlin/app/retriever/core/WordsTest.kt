package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WordsTest {
    private val android = DeviceWords.ANDROID
    private val iphone = DeviceWords.ios("iPhone")
    private val waiting = ReadingProgress(read = 3, scheduled = 2)

    @Test fun everyStopReadsForBothDevices() {
        val expected = mapOf(
            StopReason.PAUSED to ("Reading paused" to "Reading paused"),
            StopReason.POWER_SAVE to ("Paused for Battery Saver" to "Paused for Low Power Mode"),
            StopReason.HOT to ("Paused to cool down" to "Paused to cool down"),
            StopReason.LOW_BATTERY to ("Paused for low battery" to "Paused for low battery"),
            StopReason.BUSY to ("Waiting until you put your phone down" to "Waiting until you put your iPhone down"),
            StopReason.UNPLUGGED to ("Waiting to charge" to "Waiting to charge"),
            StopReason.MANUAL to ("Reading stopped for now" to "Reading stopped for now"),
            StopReason.DONE to ("Waiting for Android" to "Waiting for iOS"),
            StopReason.BUDGET to ("Waiting for Android" to "Waiting for iOS"),
        )
        assertEquals(StopReason.entries.toSet(), expected.keys)
        for ((stop, words) in expected) {
            assertEquals(words.first, ReadingPolicy.stopCopy(stop, android))
            assertEquals(words.second, ReadingPolicy.stopCopy(stop, iphone))
            // Status, Home and setup all show the same title for a stop.
            assertEquals(words.second, ReadingPolicy.decide(waiting, stop, false, false, iphone).title)
        }
    }

    @Test fun deviceWordsReachTheDetails() {
        assertEquals("Reading can resume when your phone cools down.", ReadingPolicy.decide(waiting, StopReason.HOT, false, false, android).detail)
        assertEquals(
            "Reading can resume when your iPad cools down.",
            ReadingPolicy.decide(waiting, StopReason.HOT, false, false, DeviceWords.ios("iPad")).detail,
        )
    }

    @Test fun homeStatusMapsEveryReadingPhase() {
        fun home(blocker: StopReason?, progress: ReadingProgress = waiting, running: Boolean = false, reading: Boolean = false) =
            ReadingPolicy.homeStatus(true, progress.libraryTotal.coerceAtLeast(1), ReadingPolicy.decide(progress, blocker, running, reading, android))

        assertEquals(
            HomeStatus(HomePhase.SETTING_UP, "Finishing setup. For now Retriever matches file names."),
            ReadingPolicy.homeStatus(false, 0, ReadingStatus(ReadingPhase.CAUGHT_UP, "")),
        )
        assertNull(ReadingPolicy.homeStatus(true, 0, ReadingStatus(ReadingPhase.CAUGHT_UP, "Up to date")), "an empty library shows Home's empty state")
        assertEquals(HomeStatus(HomePhase.PAUSED, "Reading paused", "Search still works. Resume whenever you’re ready."), home(StopReason.PAUSED))
        assertEquals(HomePhase.WAITING, home(StopReason.UNPLUGGED)?.phase)
        assertEquals("Waiting to charge", home(StopReason.UNPLUGGED)?.title)
        assertEquals(HomePhase.WAITING, home(null)?.phase)
        assertEquals(HomeStatus(HomePhase.READING, "Reading your files"), home(null, reading = true))
        assertEquals(HomeStatus(HomePhase.READING, "Starting reading…"), home(null, running = true))
        assertEquals(HomeStatus(HomePhase.READING, "Reading your sample"), home(null, waiting.copy(sample = true), reading = true))
        assertEquals(HomePhase.LOOKING, home(null, ReadingProgress(read = 3), running = true)?.phase)
        assertEquals(HomeStatus(HomePhase.CAUGHT_UP, "Up to date", "New files will be read using your schedule."), home(null, ReadingProgress(read = 3)))
        assertEquals(HomePhase.CAUGHT_UP, home(null, ReadingProgress(read = 3, partlyRead = 1))?.phase)
        assertEquals(
            HomeStatus(HomePhase.WAITING, "Your sample is ready to try", "Choose when to read more."),
            home(null, ReadingProgress(read = 3, sample = true)),
        )
    }

    @Test fun aSectionIsNameOnlyOnlyWhenNoneOfItsKindsIsReadInside() {
        val docs = listOf(Kind.PDF, Kind.DOC)
        assertEquals("3 waiting to read", Words.kindProgress(docs, setOf(Kind.DOC), mapOf(Kind.DOC to 3L, Kind.PDF to 5L), 3))
        assertEquals("8 waiting to read", Words.kindProgress(docs, setOf(Kind.PDF, Kind.DOC), mapOf(Kind.DOC to 3L, Kind.PDF to 5L), 8))
        assertNull(Words.kindProgress(docs, setOf(Kind.PDF), mapOf(Kind.DOC to 3L), 3), "nothing readable waits")
        assertEquals("Name only", Words.kindProgress(docs, emptySet(), mapOf(Kind.PDF to 5L), 5))
        assertEquals("Name only", Words.kindProgress(listOf(Kind.OTHER), Kind.entries.toSet() - Kind.OTHER, emptyMap(), 0))
        assertEquals("${formatCount(1_200)} waiting to read", Words.kindProgress(listOf(Kind.PHOTO), setOf(Kind.PHOTO), mapOf(Kind.PHOTO to 1_200L), 1_200))
        assertNull(Words.kindProgress(listOf(Kind.PHOTO), setOf(Kind.PHOTO), mapOf(Kind.PHOTO to 50L), 0), "pending files already read are not waiting")
    }

    @Test fun coverageNamesEveryState() {
        val inside = Kind.entries.toSet() - Kind.OTHER
        fun stored(
            kind: Kind = Kind.PDF,
            state: FileState = FileState.INDEXED,
            contentRead: Boolean = true,
            partial: Boolean = false,
            extent: ReadExtent? = null,
        ) = Store.StoredFile(1, SourceFile("u", "a.pdf", "", "", kind, 1, 1), 1, state, partial, extent, contentRead)
        assertEquals("Waiting to read", Words.coverage(stored(state = FileState.PENDING, contentRead = false), inside))
        assertEquals("Name only", Words.coverage(stored(Kind.OTHER), inside))
        assertEquals("Name only", Words.coverage(stored(contentRead = false), inside))
        assertEquals("Name only", Words.coverage(stored(state = FileState.PENDING, contentRead = false), setOf(Kind.DOC)))
        assertEquals("Couldn’t read", Words.coverage(stored(state = FileState.SKIPPED, contentRead = false), inside))
        assertEquals("Partly read · waiting to read more", Words.coverage(stored(state = FileState.PENDING), inside))
        assertEquals("Partly read · couldn’t finish reading", Words.coverage(stored(state = FileState.SKIPPED), inside))
        assertEquals("Partly read · only the beginning is included", Words.coverage(stored(partial = true), inside))
        assertEquals("Partly read · more meaning search to come", Words.coverage(stored(extent = ReadExtent.DEEPENING), inside))
        assertNull(Words.coverage(stored(), inside))
    }

    @Test fun extentSpeaksOnlyForPartialReads() {
        assertNull(Words.extent(null))
        assertNull(Words.extent(ReadExtent.WHOLE))
        assertEquals("Partly read · only the beginning is included; couldn’t finish meaning search", Words.extent(ReadExtent.FIRST_PART_MEANING_LIMITED))
        assertEquals("Partly read · only the beginning is included; more meaning search to come", Words.extent(ReadExtent.FIRST_PART_DEEPENING))
        assertEquals("Partly read · couldn’t finish meaning search", Words.extent(ReadExtent.MEANING_LIMITED))
    }

    @Test fun pluralsCountsAndUnits() {
        assertEquals("1 file", Words.files(1))
        assertEquals("0 files", Words.files(0))
        assertEquals("${formatCount(1_200)} files", Words.files(1_200))
        assertEquals("2 PDFs", Words.plural(2, "PDF"))
        assertEquals("1 file read", Words.readCount(1))
        assertEquals("58 files read · 24 partly read", Words.readCount(58, 24))
        assertEquals("200.5 kB", Words.keepTogether("200.5 kB"))
        assertEquals("1 file over 100${Words.NBSP}MB", Words.largeFiles(1))
        assertEquals("Modified" to "Taken", Words.MODIFIED to Words.TAKEN)
    }

    @Test fun labelsAndSentences() {
        assertEquals(listOf("Photo", "Screenshot", "PDF", "Document", "Audio", "Video", "File"), Kind.entries.map(Words::kindLabel))
        assertEquals(listOf("Photos", "Screenshots", "PDFs", "Documents", "Audio", "Videos", "Other files"), Kind.entries.map(Words::kindLabelPlural))
        assertEquals("page 12", Words.spokenPosition("p. 12"))
        assertEquals("at 1 minute 5 seconds", Words.spokenPosition("1:05"))
        assertEquals("at 2 hours 1 second", Words.spokenPosition("2:00:01"))
        assertEquals("at 0 seconds", Words.spokenPosition("0:00"))
        assertNull(Words.spokenPosition(""))
        assertEquals("Closest in these 79 files", Words.closestIn(79))
        assertEquals("Still reading ${formatCount(1_200)} more", Words.stillReading(1_200))
        assertEquals("${formatCount(26_214)} more files can be read. You can already find them by name.", Words.moreFiles(26_214))
        assertEquals("You can already find your files by name.", Words.moreFiles(null))
        assertEquals(
            "This iPhone has 3 GB of memory, too little to understand photos or meaning, so Retriever finds files by their names and the words inside them.",
            Words.textOnlyNote(3.4, iphone),
        )
        assertEquals("This phone has 4 GB", Words.textOnlyNote(3.6, android).substringBefore(" of"))
    }
}
