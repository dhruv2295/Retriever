package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WordsTest {
    private val android = DeviceWords.ANDROID
    private val iphone = DeviceWords.ios("iPhone")

    @Test fun aSectionIsFoundByNameOnlyWhenNoneOfItsKindsIsReadInside() {
        val docs = listOf(Kind.PDF, Kind.DOC)
        assertEquals("3 to go", Words.kindProgress(docs, setOf(Kind.DOC), mapOf(Kind.DOC to 3L, Kind.PDF to 5L), 3))
        assertEquals("8 to go", Words.kindProgress(docs, setOf(Kind.PDF, Kind.DOC), mapOf(Kind.DOC to 3L, Kind.PDF to 5L), 8))
        assertNull(Words.kindProgress(docs, setOf(Kind.PDF), mapOf(Kind.DOC to 3L), 3), "nothing readable waits")
        assertEquals("Found by name", Words.kindProgress(docs, emptySet(), mapOf(Kind.PDF to 5L), 5))
        assertEquals("Found by name", Words.kindProgress(listOf(Kind.OTHER), Kind.entries.toSet() - Kind.OTHER, emptyMap(), 0))
        assertEquals("${formatCount(1_200)} to go", Words.kindProgress(listOf(Kind.PHOTO), setOf(Kind.PHOTO), mapOf(Kind.PHOTO to 1_200L), 1_200))
        assertNull(Words.kindProgress(listOf(Kind.PHOTO), setOf(Kind.PHOTO), mapOf(Kind.PHOTO to 50L), 0), "pending files already processed are not waiting")
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
        assertEquals("Not processed yet", Words.coverage(stored(state = FileState.PENDING, contentRead = false), inside))
        assertEquals("Found by name", Words.coverage(stored(Kind.OTHER), inside))
        assertEquals("Found by name", Words.coverage(stored(contentRead = false), inside))
        assertEquals("Found by name", Words.coverage(stored(state = FileState.PENDING, contentRead = false), setOf(Kind.DOC)))
        assertEquals("Couldn’t process", Words.coverage(stored(state = FileState.SKIPPED, contentRead = false), inside))
        assertEquals("Processed in part · the rest is still to come", Words.coverage(stored(state = FileState.PENDING), inside))
        assertEquals("Processed in part · couldn’t finish", Words.coverage(stored(state = FileState.SKIPPED), inside))
        assertEquals("Processed in part · only its beginning", Words.coverage(stored(partial = true), inside))
        assertEquals("Processed in part · search by meaning still to come", Words.coverage(stored(extent = ReadExtent.DEEPENING), inside))
        assertNull(Words.coverage(stored(), inside))
    }

    @Test fun extentSpeaksOnlyForPartialReads() {
        assertNull(Words.extent(null))
        assertNull(Words.extent(ReadExtent.WHOLE))
        assertEquals("Processed in part · only its beginning, without search by meaning", Words.extent(ReadExtent.FIRST_PART_MEANING_LIMITED))
        assertEquals("Processed in part · only its beginning so far", Words.extent(ReadExtent.FIRST_PART_DEEPENING))
        assertEquals("Processed in part · couldn’t finish search by meaning", Words.extent(ReadExtent.MEANING_LIMITED))
    }

    @Test fun pluralsCountsAndUnits() {
        assertEquals("1 file", Words.files(1))
        assertEquals("0 files", Words.files(0))
        assertEquals("${formatCount(1_200)} files", Words.files(1_200))
        assertEquals("2 PDFs", Words.plural(2, "PDF"))
        assertEquals("1 file processed", Words.readCount(1))
        assertEquals("58 files processed · 24 processed in part", Words.readCount(58, 24))
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
        assertEquals("Still processing ${formatCount(1_200)} more", Words.stillReading(1_200))
        assertEquals(
            "This iPhone has 3 GB of memory, too little to understand photos or meaning, so Retriever finds files by their names and the words inside them.",
            Words.textOnlyNote(3.4, iphone),
        )
        assertEquals("This phone has 4 GB", Words.textOnlyNote(3.6, android).substringBefore(" of"))
    }

    @Test fun theDeviceIsNamedWhereItIsMeant() {
        assertEquals("Nothing leaves your iPhone. No account, nothing sent anywhere.", Words.nothingSent(iphone))
        assertEquals("Uses some battery. Stops early if your phone gets warm. Then carries on while charging.", Words.readNowBody(android))
        assertEquals(
            "Uses some battery. Stops early if your iPad gets warm. Then carries on while charging. Keep Retriever open.",
            Words.readNowBody(DeviceWords.ios("iPad")),
        )
        assertEquals("Best choice. Only while your phone is charging and you’re not using it. You can pause any time.", Words.whileChargingBody(android))
        assertEquals("What Retriever has processed · stays on this iPhone", Words.whatItHasRead(iphone))
        assertEquals("Delete from phone" to "This deletes the file from your phone.", Words.deleteFrom(android) to Words.deleteConfirm(android))
    }

    /** Section 4's retired words appear in no shared sentence. */
    @Test fun noSharedSentenceUsesARetiredWord() {
        val sentences = listOf(
            Words.SEARCH_FIELD, Words.GO_TO_HOME, Words.SEARCH_BY_NAME, Words.WELCOME_BODY, Words.WHOLE_PHONE_READY,
            Words.SHARED_FILES_AND_FOLDERS, Words.SAMPLE_PICKING, Words.SAMPLE_DONE, Words.READ, Words.CHOOSE_WHEN, Words.CHOOSE_TITLE,
            Words.MORE_FILES, Words.CHOOSE_PACE, Words.READ_15_NOW, Words.NOT_NOW_BODY, Words.READING_STATUS, Words.MORE_ON_PHONE,
            Words.KIND_OFF, Words.NO_MATCHES, Words.NO_MATCHES_DETAIL, Words.MORE_WAITING, Words.readOf(79, 124, 45), Words.readCount(3, 1),
            Words.notReadYet(3), Words.whileChargingBody(android), Words.readNowBody(iphone), Words.nothingSent(android),
            Words.notificationsAsk(false, TasteProgress(emptyMap(), emptySet(), 0, null, false, 3)),
        ) + StopReason.entries.map { ReadingPolicy.stopCopy(it, android) } + ReadExtent.entries.mapNotNull(Words::extent) +
            listOf(true, false).map { readingLabel(Kind.OTHER, null, it) }
        val retired = listOf("library", "waiting to read", "Name only", "partly read", "session", "schedule", "Read now", "first pages", "Tidy up")
        for (s in sentences) {
            for (word in retired) assertFalse(s.contains(word, ignoreCase = true), "“$s” says “$word”")
        }
    }

    @Test fun theSampleCountHasNoTotalUntilThePlanStopsChanging() {
        assertEquals("79 files processed", Words.readOf(79, 124, pending = 45))
        assertEquals("84 of 86 files processed", Words.readOf(84, 86, pending = 0))
        assertEquals("0 of 1 file processed", Words.readOf(0, 1))
    }
}
