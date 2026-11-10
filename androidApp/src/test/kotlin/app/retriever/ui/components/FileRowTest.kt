package app.retriever.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

    @Test fun cappedCellsKeepTheAdaptiveColumnsWithinTheCap() {
        val adaptive = GridCells.Adaptive(110.dp)
        var cell = 0
        val capped = CappedCells(adaptive, 808.dp) { cell = it }
        fun GridCells.sizes(width: Int) = with(this) { Density(1f).calculateCrossAxisCellSizes(width, 8) }
        // A phone is under the cap: the grid's own columns.
        assertEquals(adaptive.sizes(400), capped.sizes(400))
        // A wide window gets the columns of the cap, not of its width.
        assertEquals(adaptive.sizes(808), capped.sizes(1200))
        assertEquals(capped.sizes(1200).first(), cell)
    }
}
