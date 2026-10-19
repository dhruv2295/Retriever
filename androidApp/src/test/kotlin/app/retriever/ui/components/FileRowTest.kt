package app.retriever.ui.components

import androidx.compose.ui.text.SpanStyle
import app.retriever.core.FileState
import app.retriever.core.Kind
import app.retriever.core.SourceFile
import app.retriever.core.Store
import app.retriever.core.nameMatchRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileRowTest {
    private fun stored(kind: Kind = Kind.PDF, state: FileState = FileState.INDEXED, partial: Boolean = false) = Store.StoredFile(
        1,
        SourceFile("content://t/a", "a", "", "application/pdf", kind, 1, 1),
        0,
        state,
        partial,
        contentRead =
        state == FileState.INDEXED && kind != Kind.OTHER,
    )

    @Test fun coverageLinePerState() {
        assertEquals("Waiting to read", coverageNote(stored(state = FileState.PENDING)))
        assertEquals("Name only", coverageNote(stored(Kind.OTHER)))
        assertEquals("Partly read · only the beginning is included", coverageNote(stored(partial = true)))
        assertEquals("Couldn’t read", coverageNote(stored(state = FileState.SKIPPED)))
        assertNull(coverageNote(stored()))
    }

    /** The row agrees with the reading counts: a kind not read inside is Name only whatever its state. */
    @Test fun aKindNotReadInsideIsNameOnly() {
        assertEquals("Name only", coverageNote(stored(Kind.OTHER, state = FileState.SKIPPED)))
        assertEquals("Name only", coverageNote(stored(state = FileState.PENDING), inside = setOf(Kind.DOC)))
        assertEquals("Name only", coverageNote(stored(state = FileState.SKIPPED), inside = setOf(Kind.DOC)))
    }

    @Test fun nameHighlightComesFromCoreRanges() {
        val name = "Quarterly Report_2024.pdf"
        for (query in listOf("report", "REPORT quarterly", "quart", "2024 x", "  ", "nothing")) {
            val spans = nameHighlighted(name, query, SpanStyle()).spanStyles.map { it.start until it.end }
            assertEquals(query, nameMatchRanges(name, query).map { it.first until it.last + 1 }, spans)
        }
    }

    @Test fun failedMeaningAndFailedRereadsKeepTheirContentAndExplainTheLimit() {
        val read = stored().copy(contentRead = true)
        assertEquals("Partly read · couldn’t finish meaning search", coverageNote(read.copy(extent = app.retriever.core.ReadExtent.MEANING_LIMITED)))
        assertEquals("Partly read · couldn’t finish reading", coverageNote(read.copy(state = FileState.SKIPPED)))
        assertEquals("Partly read · waiting to read more", coverageNote(read.copy(state = FileState.PENDING)))
    }
}
