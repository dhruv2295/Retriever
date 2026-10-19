package app.retriever.core

/** Coverage of the extracted pieces, independent of whether a model is installed right now. */
enum class MeaningState { UNREAD, NOT_ADDED, PARTLY_ADDED, ADDED }

data class MeaningCounts(val notAdded: Long = 0, val partlyAdded: Long = 0, val added: Long = 0)

internal fun meaningRow(id: Long, state: FileState, total: Long, embedded: Long?): Pair<Long, MeaningState> = id to when {
    state != FileState.INDEXED -> MeaningState.UNREAD
    embedded == null || embedded == 0L -> MeaningState.NOT_ADDED
    embedded < total -> MeaningState.PARTLY_ADDED
    else -> MeaningState.ADDED
}
