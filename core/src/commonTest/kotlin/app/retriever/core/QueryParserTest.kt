package app.retriever.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueryParserTest {
    private val day = 86_400_000L
    private val oct17 = 1792195200000L // 2026-10-17 (a Saturday) 00:00 UTC
    private val now = oct17 + 12 * 3_600_000L
    private fun parse(q: String) = QueryParser.parse(q, now)

    @Test fun screenshotsFromLastWeek() {
        val p = parse("screenshots from last week")
        assertEquals("", p.text)
        assertEquals(setOf(Kind.SCREENSHOT), p.kinds)
        assertEquals(oct17 - 12 * day, p.from) // Monday 5 Oct
        assertEquals(oct17 - 5 * day, p.to) // Monday 12 Oct
        assertEquals(listOf("Screenshots", "last week"), p.understood)
    }

    @Test fun pdfMarch() {
        val p = parse("pdf march")
        assertEquals(setOf(Kind.PDF), p.kinds)
        assertEquals(1772323200000L, p.from) // 1 Mar 2026
        assertEquals(1775001600000L, p.to) // 1 Apr 2026
        assertEquals(listOf("PDFs", "March"), p.understood)
        assertEquals("", p.text)
    }

    @Test fun photosWithYear() {
        val p = parse("photos 2023")
        assertEquals(setOf(Kind.PHOTO), p.kinds)
        assertEquals(1672531200000L, p.from)
        assertEquals(1672531200000L + 365 * day, p.to)
        assertEquals(listOf("Photos", "2023"), p.understood)
        assertEquals("", p.text)
    }

    @Test fun yearNextToInOrKindOnly() {
        assertEquals(1704067200000L, parse("trip in 2024").from)
        assertEquals("trip", parse("trip in 2024").text)
        assertEquals("trip from", parse("2024 photos trip from").text)
    }

    @Test fun nameWithYearStaysAName() {
        for (q in listOf("IMG_2024", "invoice 2024", "report 2023 final")) {
            val p = parse(q)
            assertEquals(q, p.text)
            assertNull(p.from)
            assertEquals(emptyList(), p.understood)
        }
    }

    @Test fun yesterdayAndToday() {
        val y = parse("yesterday")
        assertEquals(oct17 - day, y.from)
        assertEquals(oct17, y.to)
        assertEquals(listOf("yesterday"), y.understood)
        val t = parse("beach today")
        assertEquals("beach", t.text)
        assertEquals(oct17 + day, t.to)
    }

    @Test fun hindiDateWords() {
        val p = parse("कल")
        assertEquals(oct17 - day, p.from)
        assertEquals("", p.text)
        assertEquals(oct17 - 2 * day, parse("परसों की फोटो").from)
        assertEquals("की फोटो", parse("परसों की फोटो").text)
        val w = parse("बिजली बिल पिछले हफ्ते")
        assertEquals("बिजली बिल", w.text)
        assertEquals(oct17 - 12 * day, w.from)
        assertEquals(parse("पिछले महीने").from, parse("last month").from)
    }

    @Test fun relativeAndExplicitDates() {
        assertEquals(oct17 - 3 * day, parse("receipt 3 days ago").from)
        assertEquals("receipt", parse("receipt 3 days ago").text)
        assertEquals(1710460800000L, parse("2024-03-15").from)
        assertEquals(1710547200000L, parse("2024-03-15").to)
        assertEquals(1710460800000L, parse("15 march 2024 bill").from)
        assertEquals("bill", parse("15 march 2024 bill").text)
        assertEquals(1710460800000L, parse("15/03/2024").from)
        assertEquals(1672531200000L, parse("photos from 2023").from)
        val lastMonth = parse("last month")
        assertEquals(1788220800000L, lastMonth.from) // 1 Sep 2026
        assertEquals(1790812800000L, lastMonth.to) // 1 Oct 2026
    }

    @Test fun nothingToParse() {
        val p = parse("  mortgage   statement ")
        assertEquals(ParsedQuery("mortgage statement"), p)
        assertEquals(ParsedQuery(""), parse("   "))
    }

    @Test fun aMonthOrKindInsideAPhraseStaysAWord() {
        for (q in listOf("passwords i may have saved", "march for climate", "photo id", "video call notes", "doc appointment")) {
            val p = parse(q)
            assertEquals(q, p.text, q)
            assertEquals(emptyList(), p.understood, q)
        }
        // Beside a connector or a kind word, or as the head of the phrase, they still filter.
        assertEquals(listOf("March"), parse("bills in march").understood)
        assertEquals(listOf("Screenshots"), parse("wifi screenshot").understood)
        assertEquals(listOf("Screenshots"), parse("screenshots wifi").understood)
        assertEquals(listOf("PDFs", "May"), parse("pdf from may").understood)
    }

    @Test fun relaxedNoteSaysWhatWasDroppedAndWhatWasKept() {
        val p = parse("pdf photos from yesterday")
        assertEquals("yesterday", p.dateLabel)
        assertEquals("No PDFs and photos from yesterday, so here is everything.", p.relaxedNote(null))
        assertEquals("Nothing from last week, so here are all your starred files.", parse("last week").relaxedNote("starred files"))
        assertEquals("No screenshots, so here are all your PDFs.", parse("screenshots").relaxedNote("PDFs"))
    }

    @Test fun localMidnightsUseTheZoneOffset() {
        val ist = 19_800_000L
        val p = QueryParser.parse("today", oct17 - 4 * 3_600_000L, ist) // 20:00 UTC on the 16th is 01:30 on the 17th in IST
        assertEquals(oct17 - ist, p.from)
        assertEquals(oct17 - ist + day, p.to)
    }
}
