package app.retriever.core

/** Named sections for a Perfetto capture (Android). Begin and end must run on the same thread; iOS and JVM do nothing. */
expect fun traceBegin(name: String)

expect fun traceEnd()

/** Don't wrap a block that suspends: a coroutine may resume on another thread and end the section there. */
inline fun <T> traced(name: String, block: () -> T): T {
    traceBegin(name)
    try {
        return block()
    } finally {
        traceEnd()
    }
}
