package app.retriever.core

/** Read files by how many of their extracted pieces have a vector (none, some, all), whether or not a model is installed now. */
data class MeaningCounts(val notAdded: Long = 0, val partlyAdded: Long = 0, val added: Long = 0)
