package app.retriever.ui.components

import androidx.compose.ui.text.SpanStyle
import app.retriever.core.nameMatchRanges
import org.junit.Assert.assertEquals
import org.junit.Test

class FileRowTest {
    @Test fun nameHighlightComesFromCoreRanges() {
        val name = "Quarterly Report_2024.pdf"
        for (query in listOf("report", "REPORT quarterly", "quart", "2024 x", "  ", "nothing")) {
            val spans = nameHighlighted(name, query, SpanStyle()).spanStyles.map { it.start until it.end }
            assertEquals(query, nameMatchRanges(name, query).map { it.first until it.last + 1 }, spans)
        }
    }
}
