package app.retriever.core

/** The one place a piece's locator is written and read: 'p. 12' for a page, '3:45' or '1:02:03' for a time. */
object Locator {
    private const val PAGE = "p. "

    fun page(n: Int): String = "$PAGE$n"

    fun time(sec: Int): String = if (sec >= 3600) "${sec / 3600}:${pad(sec % 3600 / 60)}:${pad(sec % 60)}" else "${sec / 60}:${pad(sec % 60)}"

    /** 'p. 12' to 12; null for anything else. */
    fun pageOf(l: String): Int? = l.removePrefix(PAGE).takeIf { it.length < l.length && it.isAsciiDigits() }?.toIntOrNull()?.takeIf { it >= 1 }

    /** 'm:ss' or 'h:mm:ss' to milliseconds; null for anything else. */
    fun msOf(l: String): Long? {
        val parts = l.split(':')
        if (parts.size !in 2..3 || parts.any { !it.isAsciiDigits() }) return null
        return parts.fold(0L) { acc, p -> acc * 60 + (p.toLongOrNull() ?: return null) } * 1000
    }

    private fun String.isAsciiDigits() = isNotEmpty() && all { it in '0'..'9' }

    private fun pad(n: Int) = n.toString().padStart(2, '0')
}
