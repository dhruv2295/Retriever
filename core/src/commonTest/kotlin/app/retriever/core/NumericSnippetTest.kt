package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class NumericSnippetTest {
    private val profile = DeviceProfile(Tier.LIGHT, ModelVariant.M440, Backend.CPU, 70, 5, false, false)
    private val monitor = object : DeviceMonitor {
        override fun state() = DeviceState(8.0, true, 90, Thermal.NONE)
    }

    @Test fun aNumberRunIsNotShownAsASnippetAndTheReasonSaysTheTextMatched() = runTest {
        val store = Store(testDriver())
        val sheet = SourceFile("file:///sheet", "sheet.doc", "Library", "", Kind.DOC, 100, 1)
        val letter = SourceFile("file:///letter", "letter.doc", "Library", "", Kind.DOC, 100, 1)
        val extractor = object : Extractor {
            override suspend fun extract(file: SourceFile, profile: DeviceProfile): List<Piece> {
                val text = if (file == sheet) "deposit " + "48213; ".repeat(40) else "The deposit is refundable when the tenant leaves."
                return listOf(Piece("", listOf(Part.Text(text)), text, text))
            }
        }
        store.upsertFiles(listOf(sheet, letter))
        IndexEngine(store, extractor, null, monitor, { IndexSettings(profile, Kind.entries.toSet()) }).runBatch(1.minutes, false)

        val hits = SearchEngine(store, NoModel).search("deposit", SearchEngine.Mode.KEYWORD).hits.associateBy { it.file.name }

        assertNull(hits.getValue("sheet.doc").snippet)
        assertEquals("Text matches", hits.getValue("sheet.doc").matchReason)
        assertEquals("The \u0002deposit\u0003 is refundable when the tenant leaves.", hits.getValue("letter.doc").snippet)
        assertNull(hits.getValue("letter.doc").matchReason)
    }
}
