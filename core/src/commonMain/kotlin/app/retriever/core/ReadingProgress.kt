package app.retriever.core

/** A read head makes a file searchable. Pending tails are a subset of searchable files, never unread files. */
data class ReadingProgress(val searchable: Long, val pending: Long, val skipped: Long, val partlyRead: Long, val waiting: Long, val trial: Boolean) {
    val total: Long get() = searchable + pending + skipped
    val libraryTotal: Long get() = total + waiting
    val unfinished: Boolean get() = pending > 0 || partlyRead > 0

    fun phase(paused: Boolean, running: Boolean): ReadingPhase = when {
        paused -> ReadingPhase.PAUSED
        trial && unfinished -> ReadingPhase.TRIAL
        trial && waiting > 0 -> ReadingPhase.TRIAL_READY
        partlyRead > 0 && pending == 0L -> ReadingPhase.DEEPENING
        running && pending > 0 -> ReadingPhase.READING
        pending > 0 -> ReadingPhase.WAITING
        running -> ReadingPhase.LOOKING
        else -> ReadingPhase.CAUGHT_UP
    }
}

/** Home and Status use the same phase, including while a batch rests with only tails left. */
enum class ReadingPhase(val title: String) {
    PAUSED("Paused"),
    TRIAL("Trial in progress"),
    TRIAL_READY("Trial ready · Choose when to read the rest"),
    DEEPENING("Finishing reading your files"),
    READING("Reading your files"),
    WAITING("Waiting to read"),
    LOOKING("Up to date · Looking for new files…"),
    CAUGHT_UP("All caught up"),
}
