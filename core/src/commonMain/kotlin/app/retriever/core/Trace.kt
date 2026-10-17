package app.retriever.core

/**
 * Named sections for a Perfetto capture (Android); iOS and JVM just run the block.
 * Don't wrap a block that suspends in [traced]: a coroutine may resume on another thread and end the section there.
 */
expect inline fun <T> traced(name: String, block: () -> T): T

/** Like [traced] for suspending code: an async slice, which may begin and end on different threads. */
expect suspend inline fun <T> tracedAsync(name: String, block: suspend () -> T): T
