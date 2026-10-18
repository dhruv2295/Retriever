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
    private fun stored(kind: Kind = Kind.PDF, state: FileState = FileState.INDEXED, partial: Boolean = false) =
        Store.StoredFile(1, SourceFile("content://t/a", "a", "", "application/pdf", kind, 1, 1), 0, state, partial)

    @Test fun coverageLinePerState() {
        assertEquals("Found by name only", coverageNote(stored(state = FileState.PENDING)))
        assertEquals("Found by name only", coverageNote(stored(Kind.OTHER)))
        assertEquals("Only the first part of this file was read", coverageNote(stored(partial = true)))
        assertEquals("Couldn't be read", coverageNote(stored(state = FileState.SKIPPED)))
        assertNull(coverageNote(stored()))
    }

    @Test fun nameHighlightComesFromCoreRanges() {
        val name = "Quarterly Report_2024.pdf"
        for (query in listOf("report", "REPORT quarterly", "quart", "2024 x", "  ", "nothing")) {
            val spans = nameHighlighted(name, query, SpanStyle()).spanStyles.map { it.start until it.end }
            assertEquals(query, nameMatchRanges(name, query).map { it.first until it.last + 1 }, spans)
        }
    }
}
