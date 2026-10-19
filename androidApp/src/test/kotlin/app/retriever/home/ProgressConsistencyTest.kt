package app.retriever.home

import app.retriever.core.FileState
import app.retriever.core.IndexState
import app.retriever.core.Kind
import app.retriever.core.StopReason
import app.retriever.core.Store
import app.retriever.status.readingTitle
import app.retriever.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressConsistencyTest {
    @Test fun homeAndStatusRenderTheSameCoreCountsAndPhaseWhileTrialTailsRemain() {
        val counts = app.retriever.readCounts(mapOf(FileState.INDEXED to 58L), mapOf(Kind.DOC to 26_230L), 100, 24, waiting = 26_172, trial = true)
        val home = HomeUi(setUp = true).withCounts(counts)
        val index = IndexState(lastStop = StopReason.DONE, etaMinutes = 2)
        val status = counts.progress
        assertEquals(58L, home.contentRead)
        assertEquals(home.contentRead, status.contentRead)
        assertEquals(Format.readCount(home.contentRead, home.partlyRead), Format.readCount(status.contentRead, status.partlyRead))
        assertEquals(libraryStatus(home, index), readingTitle(status, false, index, false))
        assertEquals("Waiting for Android", libraryStatus(home, index))
        assertEquals(24L, home.partlyRead)
    }
}
