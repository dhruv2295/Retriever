package app.retriever

import app.retriever.core.FileState
import app.retriever.core.Kind
import app.retriever.core.ReadingProgress
import app.retriever.core.Store

/** Synthetic UI fixtures: INDEXED entries in these fixtures explicitly mean usable content. */
fun readCounts(
    byState: Map<FileState, Long>,
    byKind: Map<Kind, Long>,
    tailChunks: Long,
    tailFiles: Long = 0,
    byKindState: Map<Pair<Kind, FileState>, Long> = emptyMap(),
    waiting: Long = 0,
    trial: Boolean = false,
): Store.Counts = Store.Counts(
    byState, byKind, tailChunks, tailFiles, byKindState, waiting, trial,
    ReadingProgress(
        (byState[FileState.INDEXED] ?: 0L) - tailFiles,
        tailFiles,
        byState[FileState.PENDING] ?: 0L,
        waiting,
        0,
        byState[FileState.SKIPPED] ?: 0L,
        tailFiles,
        trial,
    ),
    byKindState.filterKeys { it.second == FileState.INDEXED }.mapKeys { it.key.first },
)
