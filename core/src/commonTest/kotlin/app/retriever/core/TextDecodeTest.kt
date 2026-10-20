package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** A text file reads as its own text whatever it was saved in; bytes that are not text read as nothing. */
class TextDecodeTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun utf8WithAndWithoutItsMark() {
        assertEquals("café;2€", decodeText("café;2€".encodeToByteArray()))
        assertEquals("café", decodeText(bytes(0xEF, 0xBB, 0xBF) + "café".encodeToByteArray()))
    }

    @Test fun aCapThatCutsACharacterInHalfDropsOnlyThatCharacter() {
        val whole = "prix 2€".encodeToByteArray()
        assertEquals("prix 2", decodeText(whole.copyOf(whole.size - 1)))
    }

    @Test fun latin1AndWindows1252ReadAsThemselvesNotReplacementCharacters() {
        // "café;2€" as a spreadsheet saved on Windows writes it.
        assertEquals("café;2€", decodeText(bytes(0x63, 0x61, 0x66, 0xE9, 0x3B, 0x32, 0x80)))
        assertEquals("naïve “quotes”", decodeText(bytes(0x6E, 0x61, 0xEF, 0x76, 0x65, 0x20, 0x93, 0x71, 0x75, 0x6F, 0x74, 0x65, 0x73, 0x94)))
    }

    @Test fun utf16ByItsMarkOrItsZeroBytes() {
        assertEquals("a;é", decodeText(bytes(0xFF, 0xFE, 0x61, 0x00, 0x3B, 0x00, 0xE9, 0x00)))
        assertEquals("a;é", decodeText(bytes(0xFE, 0xFF, 0x00, 0x61, 0x00, 0x3B, 0x00, 0xE9)))
        assertEquals("name,qty", decodeText("name,qty".flatMap { listOf(it.code, 0) }.let { b -> ByteArray(b.size) { b[it].toByte() } }))
    }

    @Test fun binaryIsNotText() {
        assertEquals("", decodeText(bytes(0x00, 0x00, 0x13, 0x37, 0x00, 0xFF, 0x00, 0x00, 0x01, 0x00)))
    }
}
