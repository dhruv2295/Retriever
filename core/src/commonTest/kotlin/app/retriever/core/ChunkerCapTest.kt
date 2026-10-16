package app.retriever.core

import kotlin.test.Test
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
}
