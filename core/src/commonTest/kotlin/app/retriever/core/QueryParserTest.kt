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
        assertEquals(setOf(Kind.PHOTO), parse("परसों की फोटो").kinds)
        assertEquals("", parse("परसों की फोटो").text)
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

    @Test fun namedDateWithoutYearIsTheMostRecentPastDay() {
        assertEquals(oct17 - 357 * day, parse("pdf october 25").from) // 25 Oct 2025
        assertEquals(oct17 - 7 * day, parse("october 10").from) // 10 Oct 2026
        assertEquals(oct17 - 7 * day, parse("10 october").from)
        assertEquals(null, parse("29 february").from) // 2026 is not a leap year
    }

    @Test fun weekdays() {
        val p = parse("photo from last sunday") // today is Saturday 17 Oct
        assertEquals(setOf(Kind.PHOTO), p.kinds)
        assertEquals(oct17 - 6 * day, p.from) // Sunday 11 Oct
        assertEquals(oct17 - 5 * day, p.to)
        assertEquals(listOf("Photos", "last Sunday"), p.understood)
        assertEquals("", p.text)
        assertEquals(oct17 - 7 * day, parse("last saturday").from) // today's weekday: the week before
        assertEquals(oct17, parse("this saturday").from)
        assertEquals(oct17 - day, parse("screenshots from friday").from)
        assertEquals(oct17 - 4 * day, parse("पिछले मंगलवार").from)
        // Alone, a weekday may be what a file says, not when it was made.
        val dinner = parse("what did we decide for dinner on Friday")
        assertEquals("what did we decide for dinner on Friday", dinner.text)
        assertNull(dinner.from)
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
        val p = QueryParser.parse("today", oct17 - 4 * 3_600_000L) { ist } // 20:00 UTC on the 16th is 01:30 on the 17th in IST
        assertEquals(oct17 - ist, p.from)
        assertEquals(oct17 - ist + day, p.to)
    }

    /** Each end takes the offset in force at its own midnight: Berlin is +2 from 29 March to 25 October 2026, else +1. */
    @Test fun aRangeAcrossADaylightSavingChangeEndsAtLocalMidnights() {
        val hour = 3_600_000L
        val mar1 = 1772323200000L
        val apr1 = 1775001600000L
        val oct1 = 1790812800000L
        val nov1 = 1793491200000L
        val berlin = { at: Long -> if (at in 1774746000000L until 1792890000000L) 2 * hour else hour }
        val march = QueryParser.parse("photos from march", apr1 + 9 * day, zoneOffsetMs = berlin)
        assertEquals(mar1 - hour, march.from)
        assertEquals(apr1 - 2 * hour, march.to)
        val october = QueryParser.parse("photos from last month", nov1 + 9 * day, zoneOffsetMs = berlin)
        assertEquals(oct1 - 2 * hour, october.from)
        assertEquals(nov1 - hour, october.to)
    }

    private fun parse(q: String, language: Language) = QueryParser.parse(q, now, language)

    private val lastWeek = oct17 - 12 * day

    @Test fun spanish() {
        val es = Language.ES
        parse("capturas de pantalla de la semana pasada", es).let {
            assertEquals(setOf(Kind.SCREENSHOT), it.kinds)
            assertEquals(lastWeek, it.from)
            assertEquals("", it.text)
        }
        assertEquals(oct17 - day, parse("fotos de ayer", es).from)
        assertEquals(1710460800000L, parse("factura 15 de marzo de 2024", es).from)
        assertEquals("factura", parse("factura 15 de marzo de 2024", es).text)
        assertEquals(parse("last month").from, parse("ultimo mes", es).from)
        assertEquals(oct17 - 5 * day, parse("el lunes pasado", es).from)
        assertEquals(oct17 - 3 * day, parse("hace 3 días", es).from)
        assertEquals("mañana", parse("mañana", es).text)
        assertNull(parse("ayer", Language.EN).from, "Spanish words only with Spanish")
    }

    @Test fun portuguese() {
        val pt = Language.PT_BR
        assertEquals(lastWeek, parse("fotos da semana passada", pt).from)
        parse("capturas de tela de ontem", pt).let {
            assertEquals(setOf(Kind.SCREENSHOT), it.kinds)
            assertEquals(oct17 - day, it.from)
            assertEquals("", it.text)
        }
        assertEquals(oct17 - 3 * day, parse("ha 3 dias", pt).from)
        assertEquals(parse("last month").from, parse("mes passado", pt).from)
        assertEquals(1772323200000L, parse("imagens de março", pt).from)
    }

    @Test fun german() {
        val de = Language.DE
        parse("Bilder von letzter Woche", de).let {
            assertEquals(setOf(Kind.PHOTO), it.kinds)
            assertEquals(lastWeek, it.from)
            assertEquals("", it.text)
        }
        assertEquals(setOf(Kind.DOC, Kind.PDF), parse("Dokumente aus dem März", de).kinds)
        assertEquals(1772323200000L, parse("Dokumente aus dem März", de).from)
        assertEquals(oct17 - 3 * day, parse("vor 3 Tagen", de).from)
        assertEquals(1710460800000L, parse("15. März 2024", de).from)
        assertEquals("hier", parse("hier", de).text, "German hier is here, not yesterday")
    }

    @Test fun french() {
        val fr = Language.FR
        for (q in listOf("l'année dernière", "l’année dernière")) assertEquals(parse("last year").from, parse(q, fr).from, q)
        for (q in listOf("aujourd'hui", "aujourd’hui")) assertEquals(oct17, parse(q, fr).from, q)
        parse("captures d'écran d'hier", fr).let {
            assertEquals(setOf(Kind.SCREENSHOT), it.kinds)
            assertEquals(oct17 - day, it.from)
            assertEquals("", it.text)
        }
        assertEquals(1772323200000L, parse("photos de mars", fr).from)
        assertEquals(oct17 - 3 * day, parse("il y a 3 jours", fr).from)
        assertEquals(parse("last month").from, parse("le mois dernier", fr).from)
    }

    @Test fun hindiKindsAndDates() {
        parse("पिछले हफ़्ते की फ़ोटो").let {
            assertEquals(setOf(Kind.PHOTO), it.kinds)
            assertEquals(lastWeek, it.from)
            assertEquals("", it.text)
        }
        assertEquals("शादी", parse("शादी की तस्वीरें").text)
        assertEquals(oct17 - 3 * day, parse("3 दिन पहले").from)
        assertEquals(1772323200000L, parse("मार्च में").from)
    }

    @Test fun japaneseIsSplitWithoutRewriting() {
        parse("先週の写真").let {
            assertEquals(setOf(Kind.PHOTO), it.kinds)
            assertEquals(lastWeek, it.from)
            assertEquals("", it.text)
        }
        assertEquals("犬", parse("犬の写真").text)
        parse("写真集").let {
            assertEquals("写真集", it.text, "a photo book is not a photo filter")
            assertEquals(emptySet(), it.kinds)
        }
        assertEquals("先週末", parse("先週末").text)
        assertEquals(oct17 - day, parse("昨日のスクショ").from)
        assertEquals(oct17 - 3 * day, parse("3日前").from)
        assertEquals(1710460800000L, parse("2024年3月15日の書類").from)
        assertEquals(setOf(Kind.DOC, Kind.PDF), parse("2024年3月15日の書類").kinds)
    }

    /** Every language's no-match hint suggests a time to add: what it suggests must work. */
    @Test fun eachHintsExampleIsUnderstood() {
        for (language in Language.entries) {
            val hint = language.strings.noMatchesDetail(false)
            val example = Regex("[“„«「]\\u00A0?([^”“»」]+?)\\u00A0?[”“»」]").findAll(hint).last().groupValues[1]
            assertEquals(true, parse(example, language).date != null, "$language: \"$example\" from \"$hint\"")
        }
    }

    @Test fun eachLanguageSaysWhatWasNotFound() {
        val week = DateFilter.Period(PeriodUnit.WEEK, last = true)
        assertEquals("No photos from last week", Language.EN.strings.noneFound(listOf(Kind.PHOTO), emptyList(), week))
        assertEquals("Keine Fotos von letzter Woche", Language.DE.strings.noneFound(listOf(Kind.PHOTO), emptyList(), week))
        assertEquals("No hay nada del mes pasado", Language.ES.strings.noneFound(emptyList(), emptyList(), DateFilter.Period(PeriodUnit.MONTH, last = true)))
        assertEquals("Pas de photos avec Asha", Language.FR.strings.noneFound(listOf(Kind.PHOTO), listOf("Asha"), null))
    }

    @Test fun localizedVariantsKeepTheirSearchContent() {
        assertEquals("शादी", parse("तस्वीरें शादी", Language.HI).text)
        assertEquals(listOf(Kind.PHOTO), parse("तस्वीरें शादी", Language.HI).kindFilters)
        assertEquals(parse("this month").date, parse("Fotos aus diesem Monat", Language.DE).date)
        assertEquals("", parse("PDFs des letzten Jahres", Language.DE).text)
        assertEquals(parse("last year").date, parse("PDFs des letzten Jahres", Language.DE).date)
        assertEquals(parse("pdf march").date, parse("Dokumente aus dem Maerz", Language.DE).date)
        assertEquals(DateFilter.DaysAgo(3), parse("3 dias atras", Language.PT_BR).date)
        assertEquals(DateFilter.DaysAgo(3), parse("3 dias atras", Language.ES).date)
        assertEquals(parse("this week").date, parse("nessa semana", Language.PT_BR).date)
        assertEquals(DateFilter.Today, parse("aujourdhui", Language.FR).date)
        assertEquals("", parse("きのうのスクショ", Language.JA).text)
        assertEquals(DateFilter.Yesterday, parse("きのうのスクショ", Language.JA).date)
        assertEquals("日曜大工", parse("日曜大工", Language.JA).text)
        assertEquals("print receipt", parse("print receipt", Language.PT_BR).text)
    }

    @Test fun localizedNoResultsCopyAgreesWithKindsAndScopes() {
        val hi = Language.HI.strings
        assertEquals("शुक्रवार को आशा की कोई फ़ोटो नहीं मिली", hi.noneFound(listOf(Kind.PHOTO), listOf("आशा"), DateFilter.Weekday(4, Which.BARE)))
        assertEquals("आशा का कोई दस्तावेज़ नहीं मिला", hi.noneFound(listOf(Kind.DOC), listOf("आशा"), null))
        assertEquals("आशा की कोई फ़ोटो नहीं मिली और आशा का कोई स्क्रीनशॉट नहीं मिला", hi.noneFound(listOf(Kind.PHOTO, Kind.SCREENSHOT), listOf("आशा"), null))
        assertEquals("आशा से जुड़ी कोई फ़ाइल नहीं मिली", hi.noneFound(emptyList(), listOf("आशा"), null))
        assertEquals("कुछ नहीं मिला", hi.noneFound(emptyList(), emptyList(), null))
        assertEquals("कुछ नहीं मिला, इसलिए यहाँ स्टार वाली फ़ाइलों में मौजूद सब कुछ दिखाया जा रहा है.", hi.relaxedNote("कुछ नहीं मिला", hi.starredScope(null)))
        val pt = Language.PT_BR.strings
        assertEquals("Sem fotos, então aqui estão todas as suas fotos com estrela.", pt.relaxedNote("Sem fotos", pt.starredScope("fotos")))
        assertEquals("Sem fotos, então aqui estão todos os seus PDFs.", pt.relaxedNote("Sem fotos", "PDFs"))
        val de = Language.DE.strings
        assertEquals("Keine Fotos, deshalb siehst du hier alle markierten Fotos.", de.relaxedNote("Keine Fotos", de.starredScope("Fotos")))
        assertEquals("Keine Fotos, deshalb siehst du hier alle anderen Dateien.", de.relaxedNote("Keine Fotos", de.kindsInSentence(Kind.OTHER)))
        assertEquals("Pas d’autres fichiers avec Hugo d’hier", Language.FR.strings.noneFound(listOf(Kind.OTHER), listOf("Hugo"), DateFilter.Yesterday))
        assertEquals("直近の土曜日", Language.JA.strings.dateLabel(DateFilter.Weekday(5, Which.THIS)))
    }

    /** Home's suggested search is worded per language and must filter as it says. */
    @Test fun eachLanguagesSuggestedSearchFiltersAsItSays() {
        val dates = listOf(DateFilter.Period(PeriodUnit.WEEK, last = true), DateFilter.Period(PeriodUnit.MONTH, last = true))
        for (language in Language.entries) {
            for (date in dates) {
                for (kind in listOf(Kind.PHOTO, Kind.SCREENSHOT, Kind.PDF, Kind.VIDEO)) {
                    val q = language.strings.filterQuery(kind, date)
                    val p = parse(q, language)
                    assertEquals(Triple(listOf(kind), date, ""), Triple(p.kindFilters, p.date, p.text), "$language: $q")
                }
            }
        }
        assertEquals("pdfs from last week", Language.EN.strings.filterQuery(Kind.PDF, dates[0]))
    }
}
