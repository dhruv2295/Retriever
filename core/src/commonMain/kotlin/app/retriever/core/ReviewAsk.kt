package app.retriever.core

/** When to ask the OS for its store-rating prompt: on opening a search result, never more often than this says. */
object ReviewAsk {
    const val FIRST_AT_OPENS = 3
    private const val AGAIN_AFTER_MS = 120L * 24 * 60 * 60 * 1000

    /** [opens] counts this open; [lastAskedAt] is epoch ms of the previous ask, null if never asked. */
    fun due(opens: Int, lastAskedAt: Long?, now: Long): Boolean = if (lastAskedAt == null) {
        opens >= FIRST_AT_OPENS
    } else {
        now - lastAskedAt >= AGAIN_AFTER_MS
    }
}
