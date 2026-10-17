package app.retriever.core

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

@PublishedApi
internal val asyncCookie = AtomicInteger()

actual inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}

actual suspend inline fun <T> tracedAsync(name: String, block: suspend () -> T): T {
    val cookie = asyncCookie.incrementAndGet()
    Trace.beginAsyncSection(name, cookie)
    try {
        return block()
    } finally {
        Trace.endAsyncSection(name, cookie)
    }
}
