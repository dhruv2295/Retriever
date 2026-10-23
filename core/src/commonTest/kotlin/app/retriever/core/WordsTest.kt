package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WordsTest {
    private val android = DeviceWords.ANDROID
    private val iphone = DeviceWords.ios("iPhone")

    @Test fun coverageNamesEveryState() {
        val inside = Kind.entries.toSet() - Kind.OTHER
        fun stored(
            kind: Kind = Kind.PDF,
            state: FileState = FileState.INDEXED,
            contentRead: Boolean = true,
            partial: Boolean = false,
            extent: ReadExtent? = null,
        ) = Store.StoredFile(1, SourceFile("u", "a.pdf", "", "", kind, 1, 1), 1, state, partial, extent, contentRead)
        assertEquals("Not processed yet", Words.coverage(stored(state = FileState.PENDING, contentRead = false), inside))
        assertEquals("Found by name", Words.coverage(stored(Kind.OTHER), inside))
        assertEquals("Found by name", Words.coverage(stored(contentRead = false), inside))
        assertEquals("Found by name", Words.coverage(stored(state = FileState.PENDING, contentRead = false), setOf(Kind.DOC)))
        assertEquals("Couldn’t process", Words.coverage(stored(state = FileState.SKIPPED, contentRead = false), inside))
        assertEquals("Processed in part · the rest is still to come", Words.coverage(stored(state = FileState.PENDING), inside))
        assertEquals("Processed in part · couldn’t finish", Words.coverage(stored(state = FileState.SKIPPED), inside))
        assertEquals("Processed in part · only its beginning", Words.coverage(stored(partial = true), inside))
        assertEquals("Processed in part · search by description still to come", Words.coverage(stored(extent = ReadExtent.DEEPENING), inside))
        assertNull(Words.coverage(stored(), inside))
    }

    @Test fun extentSpeaksOnlyForPartialReads() {
        assertNull(Words.extent(null))
        assertNull(Words.extent(ReadExtent.WHOLE))
        assertEquals("Processed in part · only its beginning, without search by description", Words.extent(ReadExtent.FIRST_PART_MEANING_LIMITED))
        assertEquals("Processed in part · only its beginning so far", Words.extent(ReadExtent.FIRST_PART_DEEPENING))
        assertEquals("Processed in part · couldn’t finish search by description", Words.extent(ReadExtent.MEANING_LIMITED))
    }

    @Test fun pluralsCountsAndUnits() {
        assertEquals("1 file", Words.files(1))
        assertEquals("0 files", Words.files(0))
        assertEquals("${formatCount(1_200)} files", Words.files(1_200))
        assertEquals("2 PDFs", Words.plural(2, "PDF"))
        assertEquals("1 file processed", Words.readCount(1))
        assertEquals("58 files processed · 24 processed in part", Words.readCount(58, 24))
        assertEquals("1 of 3 files processed", Words.readOf(1, 3))
        assertEquals("1 of 1 file processed", Words.readOf(1, 1))
        assertEquals("${formatCount(1_204)} of ${formatCount(25_747)} files processed", Words.readOf(1_204, 25_747))
        assertEquals("1 of 1 file has search by description", Words.meaningOf(1, 1))
        assertEquals("${formatCount(1_938)} of ${formatCount(3_142)} files have search by description", Words.meaningOf(1_938, 3_142))
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
        assertEquals(
            "This iPhone has 3 GB of memory, too little for search by description, so Retriever finds files by their names and the words inside them.",
            Words.textOnlyNote(3.4, iphone),
        )
        assertEquals("This phone has 4 GB", Words.textOnlyNote(3.6, android).substringBefore(" of"))
    }

    @Test fun theDeviceIsNamedWhereItIsMeant() {
        assertEquals("Everything happens on your iPhone", Words.onDevice(iphone))
        assertEquals("When your phone is charging and you’re not using it. With lots of files, this can take a few nights.", Words.otherFilesBody(android))
        assertEquals("Uses some battery. Stops early if your phone gets warm. Then carries on while charging.", Words.readNowBody(android))
        assertEquals(
            "Uses some battery. Stops early if your iPad gets warm. Then carries on while charging. Keep Retriever open.",
            Words.readNowBody(DeviceWords.ios("iPad")),
        )
        assertEquals("When your phone is charging and you’re not using it, usually overnight. You can pause any time.", Words.whileChargingBody(android))
        assertEquals("47.6\u00A0MB · kept on this iPhone", Words.keptOn("47.6 MB", iphone))
        assertEquals("Delete from phone" to "This deletes the file from your phone.", Words.deleteFrom(android) to Words.deleteConfirm(android))
    }

    /** Section 4's retired words appear in no shared sentence. */
    @Test fun noSharedSentenceUsesARetiredWord() {
        val sentences = demoAndChoice + listOf(
            Words.SEARCH_FIELD, Words.HELLO_BODY, Words.SHARED_FILES_AND_FOLDERS, Words.READ, Words.CHOOSE_WHEN,
            Words.READING_STATUS, Words.MORE_ON_PHONE, Words.KIND_OFF, Words.NO_MATCHES, Words.NO_MATCHES_DETAIL, Words.MORE_WAITING,
            Words.readCount(3, 1), Words.notReadYet(3), Words.readNowBody(iphone), Words.onDevice(android),
        ) + StopReason.entries.map { ReadingPolicy.stopCopy(it, android) } + ReadExtent.entries.mapNotNull(Words::extent) +
            listOf(true, false).map { readingLabel(Kind.OTHER, null, it) }
        val retired = listOf("library", "waiting to read", "Name only", "partly read", "session", "schedule", "Read now", "first pages", "Tidy up")
        for (s in sentences) {
            for (word in retired) assertFalse(s.contains(word, ignoreCase = true), "“$s” says “$word”")
        }
    }

    /** Every sentence of setup, Home's one-time card and the choice, as the specs word them. */
    private val demoAndChoice = DescriptionState.entries.flatMap {
        listOfNotNull(Words.descriptionLine(it, 64), Words.recentFilesBody(it), Words.recentFilesAction(it, "388 MB"), Words.firstSearchHint(it))
    } + Words.HELLO_EXAMPLES.flatMap { listOf(it.query, it.title, it.detail) } + listOf(
        Words.helloTitle(false), Words.helloTitle(true), Words.HELLO_BODY, Words.FOR_EXAMPLE, Words.GET_STARTED, Words.downloadNote("388 MB"),
        Words.ACCESS_TITLE, Words.ACCESS_BODY, Words.ANDROID_ACCESS_BODY, Words.SOME_PHOTOS, Words.PHOTOS_OFF, Words.PICK_FOLDERS,
        Words.foldersAdded(2), Words.FIRST_LOOK, Words.DESCRIPTION_READY, Words.DESCRIPTION_READY_BODY, Words.HOME_INTRO_TITLE,
        Words.HOME_INTRO_BODY, Words.NEW_FILES, Words.NEW_FILES_BODY, Words.NOTIFY_WHEN_SEARCHABLE, Words.recentFiles(30),
        Words.otherFiles(25_763), Words.otherFiles(null), Words.otherFilesBody(android),
        Words.DEMO_PICKING, Words.DEMO_HINT, Words.demoSearchScope(12, false, android), Words.demoSearchScope(30, true, android), Words.seeAll(30),
        Words.chooseTitle(android), Words.chooseBody(25_763),
        Words.chooseBody(null), Words.choosePace(android), Words.RECOMMENDED, Words.READ_15_NOW, Words.chargingAndNowBody(android),
        Words.NOT_NOW_BODY, Words.START_PROCESSING, Words.GO_TO_HOME, Words.whileChargingBody(android),
        Words.notificationsAsk(true, TasteProgress(emptyMap(), emptySet(), 0, 3, 30)), Words.PROGRESS_CHANNEL,
        Words.PROGRESS_CHANNEL_BODY, Words.DEMO_DONE_NOTICE_TITLE, Words.demoDoneNotice(30, 30),
    )

    /** The app's verb is "process": no "% done", "new file", "try" screen or "sample" in what the demo and the choice say. */
    @Test fun theDemoAndTheChoiceSayDemoAndProcess() {
        for (s in demoAndChoice) {
            for (word in listOf("% done", "new file", "Try it", "one-minute try", "Skip the try", "sample", "Skip for now", "Done")) {
                assertFalse(s.contains(word), "“$s” says “$word”")
            }
        }
    }

    @Test fun theDemoAndTheChoiceInTheSpecsWords() {
        val n = formatCount(25_763)
        assertEquals("Find anything by describing it", Words.helloTitle(textOnly = false))
        assertEquals("Find files by what’s inside them", Words.helloTitle(textOnly = true), "no promise a small phone can't keep")
        assertEquals("Includes a one-time 388 MB download, on Wi-Fi.", Words.downloadNote("388 MB"))
        assertEquals("Downloading search by description · 64%", Words.descriptionLine(DescriptionState.DOWNLOADING, 64))
        assertEquals("Downloading search by description", Words.descriptionLine(DescriptionState.DOWNLOADING, null))
        assertNull(Words.descriptionLine(DescriptionState.READY, null), "the ready banner says it")
        assertEquals("Search by name or words", Words.firstSearchHint(DescriptionState.DOWNLOADING), "no describing before it works")
        assertEquals("Describe a photo or file", Words.firstSearchHint(DescriptionState.READY))
        assertEquals("Use mobile data (388 MB)", Words.recentFilesAction(DescriptionState.WAITING_FOR_WIFI, "388 MB"))
        assertEquals("Download 388 MB", Words.recentFilesAction(DescriptionState.NOT_STARTED, "388 MB"))
        assertNull(Words.recentFilesAction(DescriptionState.PREPARING, "388 MB"))
        assertEquals("The other $n files", Words.otherFiles(25_763))
        assertEquals("2 folders added", Words.foldersAdded(2))
        assertEquals("Your recent files", Words.recentFiles(0), "Continue before the files were picked")
        for (s in DescriptionState.entries.map(Words::recentFilesBody)) {
            assertFalse(Regex("minute|tonight|overnight|tomorrow").containsMatchIn(s), "“$s” promises a time")
        }
        assertEquals("Finds any file on your phone by its name. Looks inside the 12 demo files processed so far.", Words.demoSearchScope(12, false, android))
        assertEquals("Finds any file on your phone by its name. Looks inside the 1 demo file processed so far.", Words.demoSearchScope(1, false, android))
        assertEquals("Finds any file on your phone by its name.", Words.demoSearchScope(0, false, android), "the first frame")
        assertEquals("Finds any file on your phone by its name, and looks inside the 30 demo files.", Words.demoSearchScope(30, true, android))
        assertEquals("See the 30 files" to "See the file", Words.seeAll(30) to Words.seeAll(1))
        assertEquals("Now, the rest of your phone", Words.chooseTitle(android))
        assertEquals("$n files aren’t processed yet. Until they are, you can find them by name, but not by what’s inside.", Words.chooseBody(25_763))
        assertEquals("1 file isn’t processed yet. Until it is, you can find it by name, but not by what’s inside.", Words.chooseBody(1))
        assertEquals(
            "The rest of your files aren’t processed yet. Until they are, you can find them by name, but not by what’s inside.",
            Words.chooseBody(null),
        )
        assertEquals(
            "Processing them takes hours, so Retriever does a little at a time. You can use your phone and Retriever the whole time.",
            Words.choosePace(android),
        )
        assertEquals("Starts right away and uses some battery, then carries on while charging.", Words.chargingAndNowBody(android))
        assertEquals("Start processing" to "Go to Home", Words.START_PROCESSING to Words.GO_TO_HOME)
        assertEquals("The demo is done", Words.DEMO_DONE_NOTICE_TITLE)
        assertEquals("30 of 30 files processed. Open Retriever to search them.", Words.demoDoneNotice(30, 30))
        assertEquals("Processing progress", Words.PROGRESS_CHANNEL)
        assertEquals("Shows how far processing has got, and once when the demo is done.", Words.PROGRESS_CHANNEL_BODY)
    }

    /** Processing's Details is the count's breakdown: its parts add up to the count's X, and "to go" is Y − X. */
    @Test fun theBreakdownIsTheCountsOwnNumbers() {
        val p = ReadingProgress(read = 535, partlyRead = 4, nameOnly = 597, notScheduled = 25_125)
        assertEquals("535 found by what’s inside · 597 by name only · 4 in part · ${formatCount(25_125)} to go", Words.breakdown(p))
        assertEquals(1_136L to 26_261L, p.processed to p.libraryTotal)
        assertEquals(
            "0 found by what’s inside · 0 by name only · 2 couldn’t be opened · 1 to go",
            Words.breakdown(ReadingProgress(couldntRead = 2, scheduled = 1)),
        )
    }

    @Test fun everyCountHasItsTotal() {
        assertEquals("79 of 124 files processed", Words.readOf(79, 124))
        assertEquals("0 of 1 file processed", Words.readOf(0, 1))
    }

    @Test fun aFoldedResultSaysHowManyCopies() {
        assertEquals("2 copies", Words.copies(2))
        assertEquals("Open the copy in Downloads", Words.openCopyIn("Downloads"))
    }
}
