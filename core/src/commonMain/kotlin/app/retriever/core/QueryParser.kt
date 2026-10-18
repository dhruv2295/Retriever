package app.retriever.core

/**
 * A search box entry split into what to look for ([text]) and what to filter by.
 * [from] (inclusive) and [to] (exclusive) are epoch milliseconds at local midnights; null when no date was understood.
 */
data class ParsedQuery(
    val text: String,
    val kinds: Set<Kind> = emptySet(),
    val from: Long? = null,
    val to: Long? = null,
    /** Labels for the screen: the kinds' first, then the date's. */
    val understood: List<String> = emptyList(),
    /** The date's label in [understood], or null when no date was understood. */
    val dateLabel: String? = null,
) {
    /**
     * Said above results when the filters found nothing and were dropped. [scope] is what the search kept, as a plural
     * noun phrase ("starred files", "PDFs"), or null when it kept everything.
     */
    fun relaxedNote(scope: String?): String = "${missing()}, so here " + if (scope == null) "is everything." else "are all your $scope."

    /** What the filters asked for and found none of: "No PDFs from last week", "Nothing from yesterday". */
    fun missing(): String {
        val kinds = understood.filter { it != dateLabel }.joinToString(" and ") { k ->
            if (k.length > 1 && k[1].isUpperCase()) k else k.replaceFirstChar { it.lowercase() }
        }
        return when {
            kinds.isEmpty() -> "Nothing from $dateLabel"
            dateLabel == null -> "No $kinds"
            else -> "No $kinds from $dateLabel"
        }
    }
}

/** Rule-based, pure and allocation-light: kind words become filters, date phrases become a range, both leave the text. */
object QueryParser {
    private const val DAY = 86_400_000L
    private val WS = Regex("\\s+")
    private val ISO = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})")
    private val DMY = Regex("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4})")

    private val KINDS: Map<String, Pair<String, Set<Kind>>> = buildMap {
        fun add(label: String, kinds: Set<Kind>, vararg words: String) = words.forEach { put(it, label to kinds) }
        add("Screenshots", setOf(Kind.SCREENSHOT), "screenshot", "screenshots")
        add("Photos", setOf(Kind.PHOTO), "photo", "photos", "picture", "pictures", "pic", "pics")
        add("PDFs", setOf(Kind.PDF), "pdf", "pdfs")
        add("Videos", setOf(Kind.VIDEO), "video", "videos")
        add("Audio", setOf(Kind.AUDIO), "audio", "audios")
        add("Documents", setOf(Kind.DOC, Kind.PDF), "document", "documents", "doc", "docs")
    }

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
        "november", "december",
    )
    private val MONTH_INDEX: Map<String, Int> =
        MONTHS.withIndex().associate { it.value to it.index + 1 } + ("sept" to 9)

    private val THIS = setOf("this", "इस")
    private val LAST = setOf("last", "previous", "पिछले", "पिछला", "पिछली")
    private val WEEK = setOf("week", "हफ्ते", "हफ्ता", "सप्ताह")
    private val MONTH = setOf("month", "महीने", "महीना")
    private val YEAR = setOf("year", "साल", "वर्ष")
    private val CONNECTORS = setOf("in", "from", "on", "during", "of")

    private class Hit(val used: Int, val label: String, val startDay: Long, val endDay: Long)

    // ponytail: a fixed UTC offset taken at `now`, so ranges that cross a DST change are off by one hour; use a real zone type if one is added.
    fun parse(text: String, now: Long, zoneOffsetMs: Long = 0L): ParsedQuery {
        val raw = text.trim().split(WS).filter { it.isNotEmpty() }
        if (raw.isEmpty()) return ParsedQuery("")
        val w = raw.map {
            it.lowercase().trim { c ->
                !c.isLetterOrDigit() && c.category != CharCategory.NON_SPACING_MARK &&
                    c.category != CharCategory.COMBINING_SPACING_MARK
            }
        }
        val used = BooleanArray(raw.size)
        val today = (now + zoneOffsetMs).floorDiv(DAY)
        var from: Long? = null
        var to: Long? = null
        var dateLabel: String? = null
        var i = 0
        while (i < w.size) {
            val hit = if (from == null) dateAt(w, i, today) else null
            if (hit == null) {
                i++
                continue
            }
            for (k in i until i + hit.used) used[k] = true
            if (i > 0 && w[i - 1] in CONNECTORS) used[i - 1] = true
            from = hit.startDay * DAY - zoneOffsetMs
            to = hit.endDay * DAY - zoneOffsetMs
            dateLabel = hit.label
            i += hit.used
        }
        val kinds = LinkedHashSet<Kind>()
        val labels = ArrayList<String>()
        for (k in w.indices) {
            val (label, ks) = KINDS[w[k]] ?: continue
            if (!asksForKind(w, k, used)) continue
            used[k] = true
            kinds += ks
            if (label !in labels) labels += label
        }
        dateLabel?.let { labels += it }
        val rest = raw.filterIndexed { k, _ -> !used[k] }.joinToString(" ")
        return ParsedQuery(rest, kinds, from, to, labels, dateLabel)
    }

    /**
     * A kind word names what to look for when it is plural ("screenshots wifi") or ends its phrase: the last word, or
     * one before a connector, a date or another kind word ("wifi screenshot", "pdf from march", "pdf march"). A singular
     * one before another word only describes that word ("photo id", "video call notes", "doc appointment").
     */
    private fun asksForKind(w: List<String>, k: Int, dated: BooleanArray): Boolean {
        val word = w[k]
        if (word.endsWith("s")) return true
        val next = k + 1
        return next == w.size || dated[next] || w[next] in CONNECTORS || w[next] in KINDS
    }

    private fun dateAt(w: List<String>, i: Int, today: Long): Hit? {
        val a = w[i]
        val b = w.getOrNull(i + 1)
        val c = w.getOrNull(i + 2)
        when (a) {
            "today", "आज" -> return Hit(1, "today", today, today + 1)
            "yesterday", "कल" -> return Hit(1, "yesterday", today - 1, today)
            "परसों" -> return Hit(1, "2 days ago", today - 2, today - 1)
            "day" -> if (b == "before" && c == "yesterday") return Hit(3, "2 days ago", today - 2, today - 1)
        }
        if (b != null && (a in THIS || a in LAST)) {
            val back = if (a in LAST) 1 else 0
            val label = (if (back == 1) "last " else "this ")
            when (b) {
                in WEEK -> {
                    val monday = today - (today + 3).mod(7L) - 7L * back
                    return Hit(2, label + "week", monday, monday + 7)
                }

                in MONTH -> {
                    val (y, m, _) = civil(today)
                    val start = monthStart(y, m - back)
                    return Hit(2, label + "month", start, monthStart(y, m - back + 1))
                }

                in YEAR -> {
                    val y = civil(today).first - back
                    return Hit(2, label + "year", daysFromCivil(y, 1, 1), daysFromCivil(y + 1, 1, 1))
                }
            }
        }
        val n = a.toIntOrNull()
        if (n != null && n in 1..3650 && (b == "day" || b == "days") && c == "ago") {
            return Hit(3, "$n days ago", today - n, today - n + 1)
        }
        ISO.matchEntire(a)?.let { m -> return explicit(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), 1) }
        DMY.matchEntire(a)?.let { m ->
            val (p, q, y) = m.destructured.toList().map { it.toInt() }
            return if (q > 12) explicit(y, p, q, 1) else explicit(y, q, p, 1)
        }
        // "15 march [2024]"
        if (n != null && n in 1..31 && b != null) {
            val month = MONTH_INDEX[b]
            if (month != null) {
                val y = c?.takeIf(::isYear)?.toInt()
                return namedDate(today, month, n, y, if (y != null) 3 else 2)
            }
        }
        // "march 2023", "march 15", "march 15 2024", and "march" alone where it reads as a date ("pdf march", "in may")
        MONTH_INDEX[a]?.let { month ->
            if (b != null && isYear(b)) return namedDate(today, month, null, b.toInt(), 2)
            val d = b?.toIntOrNull()
            if (d != null && d in 1..31) {
                val y = c?.takeIf(::isYear)?.toInt()
                return namedDate(today, month, d, y, if (y != null) 3 else 2)
            }
            if (nearFilter(w, i)) return namedDate(today, month, null, null, 1)
        }
        if (isYear(a) && nearFilter(w, i)) return Hit(1, a, daysFromCivil(a.toInt(), 1, 1), daysFromCivil(a.toInt() + 1, 1, 1))
        return null
    }

    /** A bare month or year is a date only beside a connector or a kind word; else it is a word ("march for climate", "i may"). */
    private fun nearFilter(w: List<String>, i: Int): Boolean {
        val prev = w.getOrNull(i - 1)
        return prev in CONNECTORS || prev in KINDS || w.getOrNull(i + 1) in KINDS
    }

    private fun isYear(s: String) = s.length == 4 && s.toIntOrNull()?.let { it in 1990..2100 } == true

    private fun explicit(y: Int, m: Int, d: Int, used: Int): Hit? {
        if (m !in 1..12 || d !in 1..daysIn(y, m)) return null
        val day = daysFromCivil(y, m, d)
        return Hit(used, "$d ${MONTHS[m - 1].take(3).replaceFirstChar { it.uppercase() }} $y", day, day + 1)
    }

    /** A month (or day) without a year means its most recent occurrence. */
    private fun namedDate(today: Long, month: Int, day: Int?, year: Int?, used: Int): Hit? {
        val (cy, cm, _) = civil(today)
        val y = year ?: if (if (day != null) daysFromCivil(cy, month, day) <= today else month <= cm) cy else cy - 1
        val name = MONTHS[month - 1].replaceFirstChar { it.uppercase() }
        if (day == null) return Hit(used, if (year != null) "$name $y" else name, monthStart(y, month), monthStart(y, month + 1))
        if (day > daysIn(y, month)) return null
        val start = daysFromCivil(y, month, day)
        return Hit(used, "$day ${name.take(3)}" + if (year != null) " $y" else "", start, start + 1)
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
}
