package app.retriever.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfPiecesTest {
    private val scan = Piece("scan", emptyList(), null)
    private val body = "A page with a real text layer, long enough to count as text and not as noise."

    private fun run(hasTextLayer: Boolean?, text: (Int) -> String): Triple<List<Piece>, Int, Int> {
        var texts = 0
        var ocrs = 0
        var pieces = emptyList<Piece>()
        runTest {
            pieces = pdfPieces(50, hasTextLayer, {
                texts++
                text(it)
            }, { _, _ ->
                ocrs++
                listOf(scan)
            }, 2000)
        }
        return Triple(pieces, texts, ocrs)
    }

    @Test
    fun textlessPdfStopsAtTheOcrCap() {
        for (layer in listOf(false, null)) {
            val (pieces, texts, ocrs) = run(layer) { "" }
            assertEquals(PDF_OCR_PAGES, ocrs)
            assertEquals(PDF_OCR_PAGES, texts)
            assertEquals(CUT_SHORT, pieces.last().locator)
        }
    }

    @Test
    fun textLayerPdfNeverOcrs() {
        val (pieces, texts, ocrs) = run(true) { body }
        assertEquals(0, ocrs)
        assertEquals(50, texts)
        assertTrue(pieces.none { it.locator == CUT_SHORT })
        assertEquals(Locator.page(1), pieces.first().locator)
    }

    @Test
    fun aScannedPdfOcrsItsFirstPagesAndLeavesTheRestForLater() {
        var ocrs = 0
        var pieces = emptyList<Piece>()
        runTest {
            pieces = pdfPieces(50, false, { "" }, { _, _ ->
                ocrs++
                listOf(scan)
            }, 2000, ocrNow = PDF_HEAD_OCR_PAGES)
        }
        assertEquals(PDF_HEAD_OCR_PAGES, ocrs)
        val later = pieces.filter { it.locator.startsWith(LATER) }
        assertEquals((PDF_HEAD_OCR_PAGES + 1..PDF_OCR_PAGES).map { LATER + Locator.page(it) }, later.map { it.locator }, "still within the OCR cap")
        assertEquals(CUT_SHORT, pieces.last().locator)
    }
}
