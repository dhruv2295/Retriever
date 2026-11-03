package app.retriever.home

import app.retriever.core.DeviceWords
import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.ReadingPolicy
import app.retriever.core.StopReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressConsistencyTest {
    @Test fun homeAndStatusRenderTheSameCoreCountsAndPhaseWhileTrialTailsRemain() {
        val counts = app.retriever.readCounts(mapOf(FileState.INDEXED to 58L), mapOf(Kind.DOC to 26_230L), 100, 24, waiting = 26_172, trial = true)
        val home = HomeUi(libraryChosen = true).withCounts(counts)
        val index = IndexState(lastStop = StopReason.DONE)
        val status = counts.progress
        assertEquals(58L, home.progress.contentRead)
        assertEquals(status, home.progress)
        // Nothing until core's live status, which carries the saved mark; then that status as it is.
        assertEquals(null, homeStatus(home))
        val live = ReadingPolicy.decide(status, index.lastStop, index.running, false, DeviceWords.ANDROID)
        assertEquals("Taking a short break", homeStatus(home.copy(readingStatus = live))?.title)
        assertEquals(24L, home.progress.partlyRead)
    }
}
