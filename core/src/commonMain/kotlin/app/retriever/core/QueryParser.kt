package app.retriever.core

/**
 * A search box entry split into what to look for ([text]) and what to filter by.
 * [from] (inclusive) and [to] (exclusive) are epoch milliseconds at local midnights; null when no date was understood.
 * The filters are kept as what they mean ([kindFilters], [date]), and said in the current language when asked for.
 */
data class ParsedQuery(
    val text: String,
    val kinds: Set<Kind> = emptySet(),
    val from: Long? = null,
    val to: Long? = null,
    /** The kind filters, in the order asked, each by the kind its label names ([Kind.DOC]: documents, PDFs included). */
    val kindFilters: List<Kind> = emptyList(),
    val date: DateFilter? = null,
    /** The named people the query asked for, as named; also last in [understood]. */
    val people: List<String> = emptyList(),
) {
    /** Labels for the screen: the kinds' first, then the date's, then the people. */
    val understood: List<String> get() = kindFilters.map(Words.strings::kindLabelPlural) + listOfNotNull(dateLabel) + people

    /** The date's label in [understood], or null when no date was understood. */
    val dateLabel: String? get() = date?.let(Words.strings::dateLabel)

    /**
     * Said above results when the filters found nothing and were dropped. [scope] is what the search kept, as a plural
     * noun phrase ("starred files", "PDFs"), or null when it kept everything.
     */
    fun relaxedNote(scope: String?): String = Words.strings.relaxedNote(missing(), scope)

    /** What the filters asked for and found none of: "No PDFs from last week", "Nothing from yesterday", "No photos of Asha". */
    fun missing(): String = Words.strings.noneFound(kindFilters, people, date)
}

/** A date the search words asked for, by what it means; each language says it ([Strings.dateLabel]). */
sealed interface DateFilter {
    data object Today : DateFilter

    data object Yesterday : DateFilter

    data class DaysAgo(val days: Int) : DateFilter

    /** This or last week, month or year. */
    data class Period(val unit: PeriodUnit, val last: Boolean) : DateFilter

    /** [day]: 0 is Monday. */
    data class Weekday(val day: Int, val which: Which) : DateFilter

    data class Month(val month: Int, val year: Int?) : DateFilter

    data class Day(val day: Int, val month: Int, val year: Int?) : DateFilter

    data class Year(val year: Int) : DateFilter
}

enum class PeriodUnit { WEEK, MONTH, YEAR }

/** A weekday on its own ("friday"), this one, or the one before ("last friday"). */
enum class Which { BARE, THIS, LAST }

/**
 * Rule-based, pure and allocation-light: kind words become filters, date phrases become a range, both leave the text.
 * English, Hindi and Japanese words are always understood (their scripts can't be mistaken for each other); Spanish,
 * Portuguese, German and French only with English when they are the app's language, as their words collide ("hier" is
 * French for yesterday and German for here). ponytail: word lists per language, a new language needs its own.
 */
object QueryParser {
    private const val DAY = 86_400_000L
    private val WS = Regex("\\s+")
    private val ISO = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")
    private val DMY = Regex("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})")

    private class Hit(val used: Int, val date: DateFilter)

    /** One piece of the query: [word] is which whitespace-separated word it came from (Japanese splits a word in pieces). */
    private class Token(val raw: String, val key: String, val word: Int)

    /**
     * [zoneOffsetMs]: the local zone's offset from UTC at an instant, from the platform's zone rules, so each end of a
     * range is a real local midnight even when a daylight-saving change lies between them. [language]: the app's.
     */
    fun parse(text: String, now: Long, language: Language = Words.language, zoneOffsetMs: (Long) -> Long = { 0L }): ParsedQuery {
        val v = vocab(language)
        val t = tokens(text, v)
        if (t.isEmpty()) return ParsedQuery("")
        val w = t.map { it.key }
        val used = BooleanArray(t.size)
        val today = (now + zoneOffsetMs(now)).floorDiv(DAY)

        // The offset at the wall-clock midnight, looked up at an instant within an hour or so of it.
        fun midnight(day: Long): Long = (day * DAY).let { wall -> wall - zoneOffsetMs(wall - zoneOffsetMs(wall)) }
        var date: DateFilter? = null
        var range: Pair<Long, Long>? = null
        var i = 0
        while (i < w.size) {
            val hit = if (date == null) dateAt(w, i, today, v) else null
            val days = hit?.let { range(it.date, today) }
            if (hit == null || days == null) {
                i++
                continue
            }
            for (k in i until i + hit.used) used[k] = true
            // "from", "de la", "en": the words that tie the date to the query; Hindi's and Japanese's come after it.
            var b = i - 1
            while (b >= 0 && b >= i - 2 && w[b] in v.connectors && !used[b]) used[b--] = true
            if (i + hit.used < w.size && w[i + hit.used] in v.postpositions) used[i + hit.used] = true
            date = hit.date
            range = midnight(days.first) to midnight(days.second)
            i += hit.used
        }
        val kinds = LinkedHashSet<Kind>()
        val filters = ArrayList<Kind>()
        var k = 0
        while (k < w.size) {
            val kind = v.kinds[w[k]]
            if (kind == null || used[k] || !asksForKind(w, k, used, v)) {
                k++
                continue
            }
            used[k] = true
            if (k > 0 && w[k - 1] in v.postpositions && !used[k - 1]) used[k - 1] = true
            // "captura de pantalla", "capture d'écran": the rest of a kind's name.
            v.kindTails.firstOrNull { tail -> tail.indices.all { w.getOrNull(k + 1 + it) == tail[it] } }?.let { tail ->
                for (n in 1..tail.size) used[k + n] = true
                k += tail.size
            }
            kinds += if (kind == Kind.DOC) setOf(Kind.DOC, Kind.PDF) else setOf(kind)
            if (kind !in filters) filters += kind
            k++
        }
        val rest = t.indices.filter { !used[it] }.groupBy { t[it].word }.values.joinToString(" ") { piece -> piece.joinToString("") { t[it].raw } }
        return ParsedQuery(rest, kinds, range?.first, range?.second, filters, date)
    }

    /**
     * A kind word names what to look for when it is plural ("screenshots wifi") or ends its phrase: the last word, or
     * one before a connector, a date or another kind word ("wifi screenshot", "pdf from march", "pdf march"). A singular
     * one before another word only describes that word ("photo id", "video call notes", "doc appointment").
     */
    private fun asksForKind(w: List<String>, k: Int, dated: BooleanArray, v: Vocab): Boolean {
        val word = w[k]
        if (word in v.plural || (word.endsWith("s") && word.all { it in 'a'..'z' })) return true
        val next = k + 1
        return next == w.size || dated[next] || w[next] in v.connectors || w[next] in v.postpositions || w[next] in v.kinds ||
            v.kindTails.any { it.first() == w[next] }
    }

    private fun dateAt(w: List<String>, i: Int, today: Long, v: Vocab): Hit? {
        val a = w[i]
        val b = w.getOrNull(i + 1)
        val c = w.getOrNull(i + 2)
        v.single[a]?.let { return Hit(1, it) }
        if (a == "day" && b == "before" && c == "yesterday") return Hit(3, DateFilter.DaysAgo(2))
        // "this week", "last friday", and the other way round: "semana pasada", "la semaine dernière", "lundi dernier".
        if (b != null) {
            val (last, thing) = when {
                a in v.last -> true to b
                a in v.thisWords -> false to b
                b in v.last -> true to a
                b in v.thisWords -> false to a
                else -> null to null
            }
            if (last != null && thing != null) {
                v.weekdays[thing]?.let { return Hit(2, DateFilter.Weekday(it, if (last) Which.LAST else Which.THIS)) }
                v.units[thing]?.let { return Hit(2, DateFilter.Period(it, last)) }
            }
        }
        val n = a.toIntOrNull()
        // "3 days ago", "3 दिन पहले"; "hace 3 días", "vor 3 Tagen", "il y a 3 jours".
        if (n != null && n in 1..3650 && b in v.dayWords && c in v.agoAfter) return Hit(3, DateFilter.DaysAgo(n))
        for (before in v.agoBefore) {
            if (before.indices.all { w.getOrNull(i + it) == before[it] }) {
                val m = w.getOrNull(i + before.size)?.toIntOrNull()
                if (m != null && m in 1..3650 && w.getOrNull(i + before.size + 1) in v.dayWords) return Hit(before.size + 2, DateFilter.DaysAgo(m))
            }
        }
        ISO.matchEntire(a)?.let { m -> return explicit(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        DMY.matchEntire(a)?.let { m ->
            val (p, q, y) = m.destructured.toList().map { it.toInt() }
            return if (q > 12) explicit(y, p, q) else explicit(y, q, p)
        }
        // "15 march [2024]", "15 de marzo [de 2024]"
        if (n != null && n in 1..31 && b != null) {
            val gap = if (b in v.fillers) 1 else 0
            val month = v.months[w.getOrNull(i + 1 + gap)]
            if (month != null) {
                var used = 2 + gap
                val yGap = if (w.getOrNull(i + used) in v.fillers) 1 else 0
                val y = w.getOrNull(i + used + yGap)?.takeIf(::isYear)?.toInt()
                if (y != null) used += yGap + 1
                return Hit(used, DateFilter.Day(n, month, y))
            }
        }
        // "march 2023", "march 15", "march 15 2024", and "march" alone where it reads as a date ("pdf march", "in may")
        v.months[a]?.let { month ->
            if (b != null && isYear(b)) return Hit(2, DateFilter.Month(month, b.toInt()))
            if (b in v.fillers && c != null && isYear(c)) return Hit(3, DateFilter.Month(month, c.toInt()))
            val d = b?.toIntOrNull()
            if (d != null && d in 1..31) {
                val y = c?.takeIf(::isYear)?.toInt()
                return Hit(if (y != null) 3 else 2, DateFilter.Day(d, month, y))
            }
            if (nearFilter(w, i, v)) return Hit(1, DateFilter.Month(month, null))
        }
        v.weekdays[a]?.let { if (besideKind(w, i, v)) return Hit(1, DateFilter.Weekday(it, Which.BARE)) }
        if (isYear(a) && nearFilter(w, i, v)) return Hit(1, DateFilter.Year(a.toInt()))
        japanese(a)?.let { return Hit(1, it) }
        return null
    }

    /** "3月", "3月15日", "2024年3月", "2024年3月15日", "2024年", "3日前". */
    private fun japanese(a: String): DateFilter? {
        if (a.none { it == '年' || it == '月' || it == '日' }) return null
        JA_DATE.matchEntire(a)?.let { m ->
            val (y, mo, d) = m.destructured
            val month = mo.toIntOrNull()
            return when {
                month == null -> y.toIntOrNull()?.let { DateFilter.Year(it) }
                d.isNotEmpty() -> DateFilter.Day(d.toInt(), month, y.toIntOrNull())
                else -> DateFilter.Month(month, y.toIntOrNull())
            }
        }
        return JA_AGO.matchEntire(a)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..3650 }?.let { DateFilter.DaysAgo(it) }
    }

    /** The days a date covers, [first, second); null for one that doesn't exist (29 February 2026). */
    private fun range(d: DateFilter, today: Long): Pair<Long, Long>? = when (d) {
        DateFilter.Today -> today to today + 1

        DateFilter.Yesterday -> today - 1 to today

        is DateFilter.DaysAgo -> today - d.days to today - d.days + 1

        is DateFilter.Period -> {
            val back = if (d.last) 1 else 0
            when (d.unit) {
                PeriodUnit.WEEK -> (today - (today + 3).mod(7L) - 7L * back).let { it to it + 7 }
                PeriodUnit.MONTH -> civil(today).let { (y, m, _) -> monthStart(y, m - back) to monthStart(y, m - back + 1) }
                PeriodUnit.YEAR -> (civil(today).first - back).let { y -> daysFromCivil(y, 1, 1) to daysFromCivil(y + 1, 1, 1) }
            }
        }

        // The most recent [day] up to today; with LAST, the one before today.
        is DateFilter.Weekday -> {
            val ago = (today + 3 - d.day).mod(7L).let { if (it == 0L && d.which == Which.LAST) 7L else it }
            today - ago to today - ago + 1
        }

        // A month (or day) without a year means its most recent occurrence.
        is DateFilter.Month -> {
            val y = d.year ?: civil(today).let { (cy, cm, _) -> if (d.month <= cm) cy else cy - 1 }
            monthStart(y, d.month) to monthStart(y, d.month + 1)
        }

        is DateFilter.Day -> {
            val (cy, _, _) = civil(today)
            val y = d.year ?: if (d.day <= daysIn(cy, d.month) && daysFromCivil(cy, d.month, d.day) <= today) cy else cy - 1
            if (d.month !in 1..12 || d.day !in 1..daysIn(y, d.month)) null else daysFromCivil(y, d.month, d.day).let { it to it + 1 }
        }

        is DateFilter.Year -> daysFromCivil(d.year, 1, 1) to daysFromCivil(d.year + 1, 1, 1)
    }

    /** A bare month or year is a date only beside a connector or a kind word; else it is a word ("march for climate", "i may"). */
    private fun nearFilter(w: List<String>, i: Int, v: Vocab): Boolean {
        val prev = w.getOrNull(i - 1)
        val next = w.getOrNull(i + 1)
        return prev in v.connectors || prev in v.kinds || next in v.kinds || next in v.postpositions
    }

    /** A bare weekday is a date only beside a kind word ("photos from friday"); else it may be what a file says ("dinner on friday"). */
    private fun besideKind(w: List<String>, i: Int, v: Vocab): Boolean {
        val prev = w.getOrNull(i - 1)
        return prev in v.kinds || (prev in v.connectors && w.getOrNull(i - 2) in v.kinds) || w.getOrNull(i + 1) in v.kinds ||
            (w.getOrNull(i + 1) in v.postpositions && w.getOrNull(i + 2) in v.kinds)
    }

    private fun isYear(s: String) = s.length == 4 && s.toIntOrNull()?.let { it in 1990..2100 } == true

    private fun explicit(y: Int, m: Int, d: Int): Hit? = if (m in 1..12 && d in 1..daysIn(y, m)) Hit(1, DateFilter.Day(d, m, y)) else null

    /**
     * The query's words, folded (lowercase, no Latin accents, a French elided article dropped: "l'année" is "annee").
     * A run of Japanese is cut where a known word starts or ends, never rewritten: the pieces of one word join again in [parse].
     */
    private fun tokens(text: String, v: Vocab): List<Token> = text.trim().split(WS).filter { it.isNotEmpty() }.withIndex().flatMap { (n, word) ->
        val pieces = if (word.any(::isJapanese)) japanesePieces(word, v) else listOf(word)
        pieces.map { Token(it, key(it, v), n) }
    }

    private fun key(raw: String, v: Vocab): String {
        val k = foldLatin(raw.replace('’', '\''))
            .trim { c -> !c.isLetterOrDigit() && c != '\'' && c.category != CharCategory.NON_SPACING_MARK && c.category != CharCategory.COMBINING_SPACING_MARK }
            .trim('\'')
        val elided = ELISION.matchEntire(k)?.groupValues?.get(1)
        return if (elided != null && elided in v.words) elided else k
    }

    private fun isJapanese(c: Char) = c in '぀'..'ヿ' || c in '一'..'鿿'

    /** Splits a Japanese run at its known words: whole words only, so 写真集 (a photo book) is not a photo filter. */
    private fun japanesePieces(word: String, v: Vocab): List<String> {
        val out = ArrayList<String>()
        var gap = StringBuilder()
        var p = 0
        while (p < word.length) {
            val m = matchAt(word, p, v)
            val start = p == 0 || !isJapanese(word[p - 1]) || word[p - 1] in PARTICLES || (out.isNotEmpty() && gap.isEmpty())
            val end = m?.let { word.length == p + it || !isJapanese(word[p + it]) || word[p + it] in PARTICLES || matchAt(word, p + it, v) != null }
            if (m != null && start && end == true) {
                if (gap.isNotEmpty()) {
                    // The particle that ties the gap to this word is its own piece: 犬の写真 → 犬 の 写真.
                    val last = gap.last()
                    if (last in PARTICLES && gap.length > 1) {
                        out += gap.dropLast(1).toString()
                        out += last.toString()
                    } else {
                        out += gap.toString()
                    }
                    gap = StringBuilder()
                }
                out += word.substring(p, p + m)
                p += m
                if (p < word.length && word[p] in PARTICLES) out += word[p++].toString()
            } else {
                gap.append(word[p++])
            }
        }
        if (gap.isNotEmpty()) out += gap.toString()
        return out
    }

    /** The length of the Japanese word or date that starts at [p], longest first. */
    private fun matchAt(s: String, p: Int, v: Vocab): Int? {
        val rest = s.substring(p)
        JA_DATE_PREFIX.find(rest)?.let { if (it.range.first == 0 && it.value.isNotEmpty()) return it.value.length }
        return v.japanese.firstOrNull { rest.startsWith(it) }?.length
    }

    // Howard Hinnant's civil-date algorithms (public domain): https://howardhinnant.github.io/date_algorithms.html
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = (if (m <= 2) y0 - 1 else y0).toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun civil(days: Long): Triple<Int, Int, Int> {
        val z = days + 719468
        val era = z.floorDiv(146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple((yoe + era * 400 + if (m <= 2) 1 else 0).toInt(), m, d)
    }

    /** First day of month [m] in year [y]; [m] may run past 12 or below 1. */
    private fun monthStart(y: Int, m: Int): Long {
        val z = y * 12 + (m - 1)
        return daysFromCivil(z.floorDiv(12), z.mod(12) + 1, 1)
    }

    private fun daysIn(y: Int, m: Int) = (monthStart(y, m + 1) - monthStart(y, m)).toInt()

    private val ELISION = Regex("(?:l|d|qu)'(.+)")
    private val PARTICLES = setOf('の', 'に', 'で', 'は', 'を', 'と', 'も')
    private val JA_DATE = Regex("(?:(\\d{4})年)?(?:(\\d{1,2})月)?(?:(\\d{1,2})日)?")
    private val JA_DATE_PREFIX = Regex("^(?:\\d{4}年(?:\\d{1,2}月(?:\\d{1,2}日)?)?|\\d{1,2}月(?:\\d{1,2}日)?|\\d+日前)")
    private val JA_AGO = Regex("(\\d+)日前")

    private val cache = HashMap<Language, Vocab>()

    private fun vocab(language: Language): Vocab = cache.getOrPut(language) {
        Vocab.of(listOfNotNull(QueryWords.EN, QueryWords.HI, QueryWords.JA, QueryWords.of(language)).distinct())
    }

    /** Every word list in force, folded for lookup. */
    private class Vocab(
        val kinds: Map<String, Kind>,
        val plural: Set<String>,
        val kindTails: List<List<String>>,
        val single: Map<String, DateFilter>,
        val thisWords: Set<String>,
        val last: Set<String>,
        val units: Map<String, PeriodUnit>,
        val months: Map<String, Int>,
        val weekdays: Map<String, Int>,
        val connectors: Set<String>,
        val postpositions: Set<String>,
        val fillers: Set<String>,
        val dayWords: Set<String>,
        val agoAfter: Set<String>,
        val agoBefore: List<List<String>>,
        val japanese: List<String>,
        val words: Set<String>,
    ) {
        companion object {
            fun of(lists: List<QueryWords>): Vocab {
                fun f(s: String) = foldLatin(s.replace('’', '\''))
                fun set(pick: (QueryWords) -> Collection<String>) = lists.flatMap(pick).map(::f).toSet()
                val kinds = lists.flatMap { it.kinds.entries }.flatMap { (kind, ws) -> ws.map { f(it) to kind } }.toMap()
                val single = lists.flatMap { it.single.entries }.associate { f(it.key) to it.value }
                val units = lists.flatMap { it.units.entries }.flatMap { (u, ws) -> ws.map { f(it) to u } }.toMap()
                val months = lists.flatMap { l -> l.months.withIndex().flatMap { (m, ws) -> ws.map { f(it) to m + 1 } } }.toMap()
                val weekdays = lists.flatMap { l -> l.weekdays.withIndex().flatMap { (d, ws) -> ws.map { f(it) to d } } }.toMap()
                val japanese = lists.flatMap { it.kinds.values.flatten() + it.single.keys + it.weekdays.flatten() }
                    .filter { s -> s.any { c -> c in '぀'..'ヿ' || c in '一'..'鿿' } }.sortedByDescending { it.length }
                val tails = lists.flatMap { it.kindTails }.map { t -> t.split(' ').map(::f) }
                return Vocab(
                    kinds, set { it.plural }, tails, single, set { it.thisWords }, set { it.last }, units, months, weekdays,
                    set { it.connectors }, set { it.postpositions }, set { it.fillers }, set { it.dayWords }, set { it.agoAfter },
                    lists.flatMap { it.agoBefore }.map { b -> b.split(' ').map(::f) }, japanese,
                    kinds.keys + single.keys + units.keys + months.keys + weekdays.keys,
                )
            }
        }
    }
}
