package app.retriever.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * Where core's work runs. Tests pass a test dispatcher, so Retriever and Store run on virtual time. [default] is for CPU
 * work (the vector scan, the name index); [io] for blocking database and file reads, which would otherwise hold one of
 * [default]'s few threads (one per core) while they wait, and follows a test's [default]. [readAhead] runs the extraction
 * read ahead of the file embedding on [indexing]: its own threads (the embed holds [indexing]'s one), at its priority.
 */
data class CoreDispatchers(
    val default: CoroutineDispatcher = Dispatchers.Default,
    val indexing: CoroutineDispatcher = default,
    val readAhead: CoroutineDispatcher = default,
    val io: CoroutineDispatcher = if (default == Dispatchers.Default) Dispatchers.IO else default,
)

/** Process-owned, low-priority execution for sequential file reading. Search stays on [Dispatchers.Default]. */
expect val indexingDispatcher: CoroutineDispatcher

/**
 * Runs [block] under one process-wide lock, for a short section that reads and then publishes and must not suspend: a
 * read that began before another's write would otherwise publish after it, stale. Not reentrant on iOS.
 */
internal expect fun <T> exclusive(block: () -> T): T
