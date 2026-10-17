package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtfTest {
    /** "U" stands for backslash-u, so these sources never hold a Unicode escape that tools might decode. */
    private fun u(rtf: String) = rtf.replace("U", "\\u")

    @Test
    fun plainParagraphs() {
        val rtf = """{\rtf1\ansi\deff0 {\fonttbl{\f0\fswiss Helvetica;}}
\pard\f0\fs24 Houseboat weekend in Alleppey\par
Pack the {\b rain jacket}\tab and charger.\line Done.\par
}"""
        assertEquals("Houseboat weekend in Alleppey\nPack the rain jacket\tand charger.\nDone.\n", stripRtf(rtf))
    }

    @Test
    fun hexEscapesAreWindows1252() {
        assertEquals("café — “ok” €5", stripRtf("""{\rtf1\ansi caf\'e9 \'97 \'93ok\'94 \'805}"""))
    }

    @Test
    fun unicodeDropsItsFallback() {
        assertEquals("⌉ and ₹ then x", stripRtf(u("""{\rtf1 U8969? and \uc2U8377 ab then x}""")))
        assertEquals("ok", stripRtf("""{\rtf1 {\uc0\u111}{\u107\'3f}}"""))
    }

    @Test
    fun skippedDestinationsDropTheirGroup() {
        val rtf = """{\rtf1\ansi{\fonttbl{\f0\fnil\fcharset0 Helvetica;}{\f1\froman Times{\*\panose 02020603050405020304};}}
{\colortbl;\red255\green0\blue0;}{\stylesheet{\s1 Heading 1;}}{\info{\title Secret title}{\author Asha}}
{\header Page header}{\footer Page footer}{\*\generator Writer 1.0;}{\*\unknownthing x}
Body only.}"""
        assertEquals("Body only.", stripRtf(rtf).trim())
    }

    @Test
    fun devanagariUnicodeRun() {
        val rtf = u("""{\rtf1\ansi\uc1 U2310?U2354?U2370? U2346?U2352?U2366?U2336?U2366? }""")
        assertEquals("आलू पराठा", stripRtf(rtf).trim())
    }

    @Test
    fun controlSymbolsAndEscapedBraces() {
        assertEquals("a {b} \\ c-d e f", stripRtf("""{\rtf1 a \{b\} \\ c\_d e\~f\-}"""))
    }

    @Test
    fun brokenInputDoesNotThrow() {
        for (rtf in listOf("}}}{\\rtf1 text", "{\\rtf1 {{{ unclosed", "{\\rtf1 cut at \\u12", "{\\rtf1 cut at \\'e", "\\", "{\\bin5 abc")) {
            stripRtf(rtf)
        }
        assertTrue("text" in stripRtf("}}}{\\rtf1 text"))
        assertFalse("rtf1" in stripRtf("{\\rtf1 {{{ unclosed"))
    }

    @Test
    fun negativeBinaryCountDoesNotLoop() {
        val text = stripRtf("{\\rtf1 a\\bin-10 b}")
        assertTrue("a" in text, text)
    }
}
