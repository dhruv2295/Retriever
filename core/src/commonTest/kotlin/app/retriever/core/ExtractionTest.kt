package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtractionTest {
    private val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Lease agreement</w:t></w:r></w:p>
<w:p><w:r><w:t xml:space="preserve">Rent is </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>&#8377;25,000 &amp; due on the 5th</w:t></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Deposit</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>&lt;two months&gt;</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
</w:body></w:document>"""

    @Test
    fun stripXmlKeepsDocxParagraphText() {
        val text = stripXml(documentXml)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("Lease agreement" in lines, text)
        assertTrue(lines.any { it.startsWith("Rent is ") && "& due on the 5th" in it }, text)
        assertTrue("<two months>" in lines, text)
        assertFalse("w:" in text, text)
    }

    @Test
    fun stripXmlSplitsOdfParagraphs() {
        val text = stripXml("<office:text><text:h>Notes</text:h><text:p>first</text:p><text:p>second</text:p></office:text>")
        assertEquals(listOf("Notes", "first", "second"), text.lines().filter { it.isNotBlank() })
    }

    @Test
    fun stripHtmlDropsScriptStyleAndTags() {
        val html = """<html><head><style>body { color: red }</style><SCRIPT type="x">var a = "<b>hi</b>";</SCRIPT></head>
<body><h1>Trip&nbsp;plan</h1><p>Rock &amp; roll, it&#39;s &lt;fine&gt;</p><br/>end</body></html>"""
        val text = stripHtml(html)
        assertFalse("color" in text, text)
        assertFalse("var a" in text, text)
        assertFalse("<" in text.replace("<fine>", ""), text)
        assertTrue("Trip plan" in text, text)
        assertTrue("Rock & roll, it's <fine>" in text, text)
        assertTrue("end" in text, text)
    }

    @Test
    fun epubChapterYieldsBodyText() {
        val xhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Chapter 1</title><link rel="stylesheet" href="style.css"/><style>p { margin: 0 }</style></head>
<body><section epub:type="chapter"><h2>The River</h2><p>She walked down to the water.</p><p>It was cold.</p></section></body></html>"""
        val lines = stripHtml(xhtml).lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("The River" in lines, lines.toString())
        assertTrue("She walked down to the water." in lines, lines.toString())
        assertTrue("It was cold." in lines, lines.toString())
        assertFalse(lines.any { "margin" in it || "xml" in it || "DOCTYPE" in it }, lines.toString())
    }

    @Test
    fun chunkCharsScalesWithTheTokenWindow() {
        assertEquals(800, chunkChars(512))
        assertEquals(400, chunkChars(256))
    }

    @Test
    fun textPiecesAt256TokensStayUnder400Chars() {
        val prose = List(400) { "word$it" }.joinToString(" ") + "\n\n" + "https://example.com/" + "a".repeat(900)
        val pieces = textPieces("p. 1", prose, chunkChars(256))
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.locator == "p. 1" && it.keywordText!!.length <= 400 }, pieces.map { it.keywordText!!.length }.toString())
    }

    @Test
    fun shortTextMakesNoPieces() {
        assertTrue(textPieces("", "too short", chunkChars(512)).isEmpty())
    }

    @Test
    fun denseCsvYieldsPiecesUnderTheCap() {
        val csv = buildString {
            appendLine("date,merchant,amount,balance,ref")
            for (i in 0 until 70) appendLine("2026-0${i % 9 + 1}-1${i % 9},UPI/${100000 + i}/PAYTM,${i * 37}.50,${90000 - i * 11}.25,TXN${i}X${i * 7}")
        }
        assertTrue(csv.length >= 3 * 1024, csv.length.toString())
        val pieces = textPieces("", csv, chunkChars(512))
        assertTrue(pieces.isNotEmpty())
        assertTrue(pieces.all { it.keywordText!!.length <= 800 })
        assertTrue(pieces.joinToString(" ") { it.keywordText!! }.contains("UPI/100000/PAYTM"))
    }

    @Test
    fun audioWindowsFor25SecondsMatchTheOldCount() {
        val pcm = ShortArray(25 * AUDIO_SAMPLE_RATE) { 1 }
        val windows = audioWindows(pcm, audioWindowSec(512))
        assertEquals(listOf(0, 10, 20), windows.map { it.first })
        assertEquals(listOf(10, 10, 5).map { it * AUDIO_SAMPLE_RATE }, windows.map { it.second.size })
    }

    @Test
    fun audioWindowsDropATailUnderTwoSeconds() {
        val pcm = ShortArray(21 * AUDIO_SAMPLE_RATE)
        assertEquals(2, audioWindows(pcm, 10).size)
    }

    @Test
    fun slideOrderReadsSlideNumbers() {
        assertEquals(12, slideOrder("ppt/slides/slide12.xml"))
        assertEquals(null, slideOrder("ppt/slides/_rels/slide1.xml.rels"))
    }
}
