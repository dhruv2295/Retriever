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
}
