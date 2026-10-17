package app.retriever.core

actual inline fun <T> traced(name: String, block: () -> T): T = block()

actual suspend inline fun <T> tracedAsync(name: String, block: suspend () -> T): T = block()
