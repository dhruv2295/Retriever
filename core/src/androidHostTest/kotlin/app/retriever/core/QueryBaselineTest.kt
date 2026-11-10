package app.retriever.core

import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the parser makes of a fixed set of English queries, near-misses included, kept in core/fixtures/query-en.txt so
 * teaching it other languages can't change English. `-Dcopy.write=<dir>` writes the file instead of asserting it.
 */
class QueryBaselineTest {
    private val now = 1792195200000L + 12 * 3_600_000L // Saturday 17 Oct 2026, noon UTC

    @Test fun englishMatchesTheBaseline() {
        val before = Words.language to Locale.getDefault()
        Words.language = Language.EN
        Locale.setDefault(Locale.US)
        val text = try {
            QUERIES.joinToString("\n", postfix = "\n") { q ->
                val p = QueryParser.parse(q, now)
                listOf(q, p.text, p.kinds.sorted(), p.from, p.to, p.understood, p.missing(), p.relaxedNote(null), p.relaxedNote("PDFs"))
                    .joinToString(" | ")
            }
        } finally {
            Words.language = before.first
            Locale.setDefault(before.second)
        }
        val write = System.getProperty("copy.write")
        val file = File(write ?: "fixtures", "query-en.txt")
        if (write != null) file.writeText(text) else assertEquals(file.readText(), text, "English parsing changed")
    }

    private companion object {
        val QUERIES = listOf(
            "screenshots from last week", "pdf march", "photos 2023", "trip in 2024", "2024 photos trip from", "IMG_2024",
            "invoice 2024", "report 2023 final", "yesterday", "beach today", "receipt 3 days ago", "2024-03-15",
            "15 march 2024 bill", "15/03/2024", "03/15/2024", "photos from 2023", "last month", "this month", "last year",
            "this year", "this week", "pdf october 25", "october 10", "10 october", "29 february", "march 2023", "sept",
            "photo from last sunday", "last saturday", "this saturday", "screenshots from friday",
            "what did we decide for dinner on Friday", "  mortgage   statement ", "passwords i may have saved",
            "march for climate", "photo id", "video call notes", "doc appointment", "bills in march", "wifi screenshot",
            "screenshots wifi", "pdf from may", "pdf photos from yesterday", "last week", "screenshots", "documents",
            "docs from april", "audio", "videos last week", "pictures of the beach", "pics", "day before yesterday",
            "previous week", "in may", "i may", "do 2024 taxes", "print receipt", "no photos", "hier", "am pdf",
            "the day before", "2 days ago photos", "10 days ago", "photos from monday", "monday photos", "march 15",
            "march 15 2024", "screenshot of the error", "pdf pdf", "video", "my documents", "audio recording from today",
            "photos of asha", "कल", "परसों की फोटो", "बिजली बिल पिछले हफ्ते", "पिछले महीने", "पिछले मंगलवार", "आज",
        )
    }
}
