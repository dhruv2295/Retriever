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

/**
 * Says how far Retriever has read one file, in the app's one vocabulary, for File info on both platforms. [state] null is a
 * file the index has not met yet. Content already read stays "Read" even if reading was switched off since; the index records
 * no reason for a failure, only that it tried [attempts] times.
 */
fun readingLabel(kind: Kind, state: FileState?, attempts: Int, partial: Boolean, extent: ReadExtent?, readInside: Boolean): String = when {
    kind == Kind.OTHER -> "Name only · Reading isn't supported for this type"

    state == FileState.INDEXED -> when {
        partial || extent == ReadExtent.FIRST_PART -> "Partly read · Only the beginning is included"
        extent == ReadExtent.DEEPENING -> "Partly read · More meaning search to come"
        else -> "Read"
    }

    !readInside -> "Name only · Reading is off for this type"

    state == FileState.SKIPPED -> "Couldn't read · Retriever tried $attempts times"

    else -> "Waiting to read"
}
