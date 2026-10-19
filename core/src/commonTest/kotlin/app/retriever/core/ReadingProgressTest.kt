package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadingProgressTest {
    @Test fun readHeadsStaySearchableWhileTailsArePending() {
        val counts = Store.Counts(mapOf(FileState.INDEXED to 58), mapOf(Kind.DOC to 26_230), 100, 24, waiting = 26_172, trial = true)
        assertEquals(58L, counts.progress.searchable)
        assertEquals(58L, counts.progress.total)
        assertEquals(24L, counts.progress.partlyRead)
        for (running in listOf(true, false)) {
            assertEquals(ReadingPhase.TRIAL, counts.progress.phase(paused = false, running = running))
        }
        assertEquals(ReadingPhase.TRIAL_READY, counts.copy(tailFiles = 0).progress.phase(false, false))
    }

    @Test fun fullLibraryTailsPreventCaughtUpEvenAfterDoneOrWhilePaused() {
        val progress = ReadingProgress(58, 0, 0, 24, 0, false)
        assertTrue(progress.unfinished)
        assertEquals(ReadingPhase.DEEPENING, progress.phase(false, false))
        assertEquals(ReadingPhase.PAUSED, progress.phase(true, false))
        assertEquals(ReadingPhase.CAUGHT_UP, progress.copy(partlyRead = 0).phase(false, false))
    }

    @Test fun aFilesReadingSaysHowFarItWasReadAndAlreadyReadContentStaysRead() {
        fun label(state: FileState?, kind: Kind = Kind.PDF, partial: Boolean = false, extent: ReadExtent? = null, inside: Boolean = true) =
            readingLabel(kind, state, 3, partial, extent, inside)
        assertEquals("Read", label(FileState.INDEXED))
        assertEquals("Read", label(FileState.INDEXED, extent = ReadExtent.WHOLE))
        assertEquals("Partly read · Only the beginning is included", label(FileState.INDEXED, partial = true))
        assertEquals("Partly read · Only the beginning is included", label(FileState.INDEXED, extent = ReadExtent.FIRST_PART))
        assertEquals("Partly read · More meaning search to come", label(FileState.INDEXED, extent = ReadExtent.DEEPENING))
        assertEquals("Waiting to read", label(FileState.PENDING))
        assertEquals("Waiting to read", label(null))
        assertEquals("Couldn't read · Retriever tried 3 times", label(FileState.SKIPPED))
        assertEquals("Read", label(FileState.INDEXED, inside = false))
        assertEquals("Name only · Reading is off for this type", label(FileState.PENDING, inside = false))
        assertEquals("Name only · Reading is off for this type", label(FileState.SKIPPED, inside = false))
        assertEquals("Name only · Reading isn't supported for this type", label(FileState.INDEXED, Kind.OTHER))
    }
}
