package app.retriever.core

/**
 * The words one language uses for kinds and dates in a search ([QueryParser]). Matched without case or Latin accents,
 * so "ultimo mes" is "último mes". [kinds]: [Kind.DOC] stands for documents, PDFs included. [months]: twelve spellings
 * lists, January first; [weekdays]: seven, Monday first. [connectors] tie a date or kind to the query from before it
 * ("from", "de la"), [postpositions] from after it (Hindi की, Japanese の). [fillers] sit inside a date ("15 de marzo").
 * [agoBefore]/[agoAfter] frame "N days ago" ("hace 3 días", "3 दिन पहले").
 */
internal data class QueryWords(
    val kinds: Map<Kind, List<String>> = emptyMap(),
    val plural: List<String> = emptyList(),
    val kindTails: List<String> = emptyList(),
    val single: Map<String, DateFilter> = emptyMap(),
    val thisWords: List<String> = emptyList(),
    val last: List<String> = emptyList(),
    val units: Map<PeriodUnit, List<String>> = emptyMap(),
    val months: List<List<String>> = emptyList(),
    val weekdays: List<List<String>> = emptyList(),
    val connectors: List<String> = emptyList(),
    val postpositions: List<String> = emptyList(),
    val fillers: List<String> = emptyList(),
    val dayWords: List<String> = emptyList(),
    val agoAfter: List<String> = emptyList(),
    val agoBefore: List<String> = emptyList(),
) {
    companion object {
        /** The words a language adds to the ones always understood (English, Hindi, Japanese); null when it adds none. */
        fun of(language: Language): QueryWords? = when (language) {
            Language.EN, Language.HI, Language.JA -> null
            Language.ES -> ES
            Language.PT_BR -> PT
            Language.DE -> DE
            Language.FR -> FR
        }

        private val TWO_DAYS_AGO = DateFilter.DaysAgo(2)

        val EN = QueryWords(
            kinds = mapOf(
                Kind.SCREENSHOT to listOf("screenshot", "screenshots"),
                Kind.PHOTO to listOf("photo", "photos", "picture", "pictures", "pic", "pics"),
                Kind.PDF to listOf("pdf", "pdfs"),
                Kind.VIDEO to listOf("video", "videos"),
                Kind.AUDIO to listOf("audio", "audios"),
                Kind.DOC to listOf("document", "documents", "doc", "docs"),
            ),
            single = mapOf("today" to DateFilter.Today, "yesterday" to DateFilter.Yesterday),
            thisWords = listOf("this"),
            last = listOf("last", "previous"),
            units = mapOf(PeriodUnit.WEEK to listOf("week"), PeriodUnit.MONTH to listOf("month"), PeriodUnit.YEAR to listOf("year")),
            months = listOf(
                listOf("january"), listOf("february"), listOf("march"), listOf("april"), listOf("may"), listOf("june"), listOf("july"),
                listOf("august"), listOf("september", "sept"), listOf("october"), listOf("november"), listOf("december"),
            ),
            weekdays = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").map(::listOf),
            connectors = listOf("in", "from", "on", "during", "of"),
            dayWords = listOf("day", "days"),
            agoAfter = listOf("ago"),
        )

        val HI = QueryWords(
            kinds = mapOf(
                Kind.SCREENSHOT to listOf("स्क्रीनशॉट", "स्क्रीनशॉट्स", "स्क्रीनशॉटों"),
                Kind.PHOTO to listOf(
                    "फ़ोटो", "फोटो", "फ़ोटोज़", "फोटोज़", "फोटोज", "तस्वीर", "तस्वीरें", "तस्वीरों", "तसवीर", "तसवीरें", "तसवीरों",
                ),
                Kind.PDF to listOf("पीडीएफ", "पीडीएफ़"),
                Kind.VIDEO to listOf("वीडियो"),
                Kind.AUDIO to listOf("ऑडियो"),
                Kind.DOC to listOf("दस्तावेज़", "दस्तावेज", "दस्तावेज़ों", "दस्तावेजों"),
            ),
            plural = listOf(
                "स्क्रीनशॉट्स", "स्क्रीनशॉटों", "फ़ोटोज़", "फोटोज़", "फोटोज", "तस्वीरें", "तस्वीरों", "तसवीरें", "तसवीरों", "दस्तावेज़ों", "दस्तावेजों",
            ),
            single = mapOf("आज" to DateFilter.Today, "कल" to DateFilter.Yesterday, "परसों" to TWO_DAYS_AGO, "परसो" to TWO_DAYS_AGO),
            thisWords = listOf("इस"),
            last = listOf("पिछले", "पिछला", "पिछली", "बीते"),
            units = mapOf(
                PeriodUnit.WEEK to listOf("हफ्ते", "हफ्ता", "हफ़्ते", "हफ़्ता", "सप्ताह"),
                PeriodUnit.MONTH to listOf("महीने", "महीना"),
                PeriodUnit.YEAR to listOf("साल", "वर्ष"),
            ),
            months = listOf(
                listOf("जनवरी"), listOf("फ़रवरी", "फरवरी"), listOf("मार्च"), listOf("अप्रैल"), listOf("मई"), listOf("जून"), listOf("जुलाई"),
                listOf("अगस्त"), listOf("सितंबर", "सितम्बर"), listOf("अक्टूबर"), listOf("नवंबर", "नवम्बर"), listOf("दिसंबर", "दिसम्बर"),
            ),
            weekdays = listOf(
                listOf("सोमवार"),
                listOf("मंगलवार"),
                listOf("बुधवार"),
                listOf("गुरुवार", "बृहस्पतिवार"),
                listOf("शुक्रवार"),
                listOf("शनिवार"),
                listOf("रविवार", "इतवार"),
            ),
            postpositions = listOf("की", "के", "का", "से", "में"),
            dayWords = listOf("दिन"),
            agoAfter = listOf("पहले"),
        )

        val JA = QueryWords(
            kinds = mapOf(
                Kind.SCREENSHOT to listOf("スクリーンショット", "スクショ"),
                Kind.PHOTO to listOf("写真", "画像"),
                Kind.VIDEO to listOf("動画", "ビデオ"),
                Kind.AUDIO to listOf("音声", "オーディオ", "録音"),
                Kind.DOC to listOf("書類", "文書", "ドキュメント"),
            ),
            single = mapOf(
                "今日" to DateFilter.Today,
                "きょう" to DateFilter.Today,
                "昨日" to DateFilter.Yesterday,
                "きのう" to DateFilter.Yesterday,
                "一昨日" to TWO_DAYS_AGO,
                "おととい" to TWO_DAYS_AGO,
                "今週" to DateFilter.Period(PeriodUnit.WEEK, last = false),
                "先週" to DateFilter.Period(PeriodUnit.WEEK, last = true),
                "今月" to DateFilter.Period(PeriodUnit.MONTH, last = false),
                "先月" to DateFilter.Period(PeriodUnit.MONTH, last = true),
                "今年" to DateFilter.Period(PeriodUnit.YEAR, last = false),
                "去年" to DateFilter.Period(PeriodUnit.YEAR, last = true),
                "昨年" to DateFilter.Period(PeriodUnit.YEAR, last = true),
            ),
            weekdays = listOf(
                listOf("月曜日", "月曜"),
                listOf("火曜日", "火曜"),
                listOf("水曜日", "水曜"),
                listOf("木曜日", "木曜"),
                listOf("金曜日", "金曜"),
                listOf("土曜日", "土曜"),
                listOf("日曜日", "日曜"),
            ),
            postpositions = listOf("の", "から", "に", "で"),
        )

        val ES = QueryWords(
            kinds = mapOf(
                Kind.SCREENSHOT to listOf("captura", "capturas", "pantallazo", "pantallazos"),
                Kind.PHOTO to listOf("foto", "fotos", "fotografía", "fotografías", "imagen", "imágenes"),
                Kind.PDF to listOf("pdf", "pdfs"),
                Kind.VIDEO to listOf("video", "videos", "vídeo", "vídeos"),
                Kind.AUDIO to listOf("audio", "audios"),
                Kind.DOC to listOf("documento", "documentos", "doc", "docs"),
            ),
            kindTails = listOf("de pantalla"),
            single = mapOf("hoy" to DateFilter.Today, "ayer" to DateFilter.Yesterday, "anteayer" to TWO_DAYS_AGO, "antier" to TWO_DAYS_AGO),
            thisWords = listOf("este", "esta"),
            last = listOf("pasado", "pasada", "último", "última", "anterior"),
            units = mapOf(PeriodUnit.WEEK to listOf("semana"), PeriodUnit.MONTH to listOf("mes"), PeriodUnit.YEAR to listOf("año")),
            months = listOf(
                listOf("enero"), listOf("febrero"), listOf("marzo"), listOf("abril"), listOf("mayo"), listOf("junio"), listOf("julio"),
                listOf("agosto"), listOf("septiembre", "setiembre"), listOf("octubre"), listOf("noviembre"), listOf("diciembre"),
            ),
            weekdays = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo").map(::listOf),
            connectors = listOf("de", "del", "en", "desde", "el", "la", "los", "las"),
            fillers = listOf("de", "del"),
            dayWords = listOf("día", "días"),
            agoBefore = listOf("hace"),
            agoAfter = listOf("atrás"),
        )

        val PT = QueryWords(
            kinds = mapOf(
                Kind.SCREENSHOT to listOf("captura", "capturas"),
                Kind.PHOTO to listOf("foto", "fotos", "fotografia", "fotografias", "imagem", "imagens"),
                Kind.PDF to listOf("pdf", "pdfs"),
                Kind.VIDEO to listOf("vídeo", "vídeos", "video", "videos"),
                Kind.AUDIO to listOf("áudio", "áudios", "audio", "audios"),
                Kind.DOC to listOf("documento", "documentos", "doc", "docs"),
            ),
            plural = listOf("imagens"),
            kindTails = listOf("de tela"),
            single = mapOf("hoje" to DateFilter.Today, "ontem" to DateFilter.Yesterday, "anteontem" to TWO_DAYS_AGO),
            thisWords = listOf("este", "esta", "neste", "nesta", "esse", "essa", "nesse", "nessa"),
            last = listOf("passado", "passada", "último", "última", "anterior"),
            units = mapOf(PeriodUnit.WEEK to listOf("semana"), PeriodUnit.MONTH to listOf("mês"), PeriodUnit.YEAR to listOf("ano")),
            months = listOf(
                listOf("janeiro"), listOf("fevereiro"), listOf("março"), listOf("abril"), listOf("maio"), listOf("junho"), listOf("julho"),
                listOf("agosto"), listOf("setembro"), listOf("outubro"), listOf("novembro"), listOf("dezembro"),
            ),
            weekdays = listOf(
                listOf("segunda", "segunda-feira"),
                listOf("terça", "terça-feira"),
                listOf("quarta", "quarta-feira"),
                listOf("quinta", "quinta-feira"),
                listOf("sexta", "sexta-feira"),
                listOf("sábado"),
                listOf("domingo"),
            ),
            connectors = listOf("de", "do", "da", "em", "no", "na", "desde", "o", "a"),
            fillers = listOf("de"),
            dayWords = listOf("dia", "dias"),
            agoBefore = listOf("há", "faz"),
            agoAfter = listOf("atrás"),
        )

        val DE = QueryWords(
            kinds = mapOf(
                Kind.SCREENSHOT to listOf("screenshot", "screenshots", "bildschirmfoto", "bildschirmfotos"),
                Kind.PHOTO to listOf("foto", "fotos", "bild", "bilder", "bildern"),
                Kind.PDF to listOf("pdf", "pdfs"),
                Kind.VIDEO to listOf("video", "videos"),
                Kind.AUDIO to listOf("audio", "audios", "audiodatei", "audiodateien"),
                Kind.DOC to listOf("dokument", "dokumente", "dokumenten"),
            ),
            plural = listOf("bilder", "bildern", "dokumente", "dokumenten", "audiodateien"),
            single = mapOf("heute" to DateFilter.Today, "gestern" to DateFilter.Yesterday, "vorgestern" to TWO_DAYS_AGO),
            thisWords = listOf("diese", "diesen", "dieses", "dieser", "diesem"),
            last = listOf(
                "letzte", "letzten", "letztes", "letzter", "letztem", "vorige", "vorigen", "voriges", "voriger", "vorigem",
                "vergangene", "vergangenen", "vergangenes", "vergangener", "vergangenem",
            ),
            units = mapOf(PeriodUnit.WEEK to listOf("woche"), PeriodUnit.MONTH to listOf("monat", "monats"), PeriodUnit.YEAR to listOf("jahr", "jahres")),
            months = listOf(
                listOf("januar", "jänner"), listOf("februar"), listOf("märz", "maerz"), listOf("april"), listOf("mai"), listOf("juni"), listOf("juli"),
                listOf("august"), listOf("september"), listOf("oktober"), listOf("november"), listOf("dezember"),
            ),
            weekdays = listOf(
                listOf("montag"),
                listOf("dienstag"),
                listOf("mittwoch"),
                listOf("donnerstag"),
                listOf("freitag"),
                listOf("samstag", "sonnabend"),
                listOf("sonntag"),
            ),
            connectors = listOf("von", "vom", "aus", "im", "am", "in", "seit", "dem", "der", "des"),
            dayWords = listOf("tag", "tagen"),
            agoBefore = listOf("vor"),
        )

        val FR = QueryWords(
            kinds = mapOf(
                Kind.SCREENSHOT to listOf("capture", "captures", "screenshot", "screenshots"),
                Kind.PHOTO to listOf("photo", "photos", "image", "images"),
                Kind.PDF to listOf("pdf", "pdfs"),
                Kind.VIDEO to listOf("vidéo", "vidéos"),
                Kind.AUDIO to listOf("audio", "audios"),
                Kind.DOC to listOf("document", "documents", "doc", "docs"),
            ),
            kindTails = listOf("d'écran"),
            single = mapOf("aujourd'hui" to DateFilter.Today, "aujourdhui" to DateFilter.Today, "hier" to DateFilter.Yesterday, "avant-hier" to TWO_DAYS_AGO),
            thisWords = listOf("ce", "cet", "cette"),
            last = listOf("dernier", "dernière", "passé", "passée", "précédent", "précédente"),
            units = mapOf(
                PeriodUnit.WEEK to listOf("semaine"),
                PeriodUnit.MONTH to listOf("mois", "mois-ci"),
                PeriodUnit.YEAR to listOf("année", "an"),
            ),
            months = listOf(
                listOf("janvier"), listOf("février"), listOf("mars"), listOf("avril"), listOf("mai"), listOf("juin"), listOf("juillet"),
                listOf("août"), listOf("septembre"), listOf("octobre"), listOf("novembre"), listOf("décembre"),
            ),
            weekdays = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche").map(::listOf),
            connectors = listOf("de", "du", "des", "en", "dans", "depuis", "le", "la", "les"),
            dayWords = listOf("jour", "jours"),
            agoBefore = listOf("il y a"),
        )
    }
}
