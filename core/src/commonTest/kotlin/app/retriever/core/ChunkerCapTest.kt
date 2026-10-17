package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkerCapTest {
    @Test
    fun tokenDenseTextStaysUnderTheCharacterCap() {
        val url = "https://example.com/" + "a".repeat(80)
        val text = List(200) { url }.joinToString(" ") + " " + "x".repeat(5000)
        val chunks = Chunker.chunks(text)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.isNotEmpty() && it.length <= Chunker.CHARS_PER_CHUNK }, chunks.map { it.length }.toString())
    }

    private fun assertLossless(text: String) {
        val chunks = Chunker.chunks(text)
        assertEquals(text.filterNot { it.isWhitespace() }, chunks.joinToString("").filterNot { it.isWhitespace() })
        assertTrue(chunks.all { it.length <= Chunker.CHARS_PER_CHUNK })
        // A piece never starts on a low surrogate or a combining mark.
        assertTrue(chunks.drop(1).none { it.first().isLowSurrogate() || it.first().category in marks })
    }

    private val marks = listOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK)

    @Test
    fun unspacedScriptsAndUrlsLoseNothing() {
        assertLossless("春眠不觉晓处处闻啼鸟".repeat(500))
        assertLossless("สวัสดีครับยินดีต้อนรับสู่ประเทศไทย".repeat(100))
        assertLossless("https://example.com/" + "a/b?c=d&".repeat(375))
    }

    @Test
    fun cutsAvoidSurrogatePairsAndCombiningMarks() {
        assertLossless("😀".repeat(1000))
        assertLossless("é".repeat(1000))
        assertLossless("x" + "😀".repeat(500))
    }

    @Test
    fun markOnlyRunStillSplitsAtTheCap() {
        val chunks = Chunker.chunks("a" + "́".repeat(2000))
        assertEquals(2001, chunks.sumOf { it.length })
        assertTrue(chunks.all { it.length <= Chunker.CHARS_PER_CHUNK })
    }
}
