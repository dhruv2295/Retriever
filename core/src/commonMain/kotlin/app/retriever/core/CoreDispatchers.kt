package app.retriever.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Where core's work runs. Tests pass a test dispatcher, so Retriever and Store run on virtual time. */
data class CoreDispatchers(val default: CoroutineDispatcher = Dispatchers.Default, val indexing: CoroutineDispatcher = default)

/** Process-owned, low-priority execution for sequential file reading. Search stays on [Dispatchers.Default]. */
expect val indexingDispatcher: CoroutineDispatcher
