@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.Foundation.NSLock
import platform.darwin.DISPATCH_AUTORELEASE_FREQUENCY_WORK_ITEM
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_attr_make_with_autorelease_frequency
import platform.darwin.dispatch_queue_attr_make_with_qos_class
import platform.darwin.dispatch_queue_create
import platform.posix.QOS_CLASS_UTILITY
import kotlin.coroutines.CoroutineContext

actual val indexingDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
    // A pool per work item: a queue made here inherits the global queues' "never", so the NSData, NSNumber and images
    // the bridges autorelease could otherwise pile up across files on the worker thread.
    private val queue = dispatch_queue_create(
        "app.retriever.index",
        dispatch_queue_attr_make_with_qos_class(
            dispatch_queue_attr_make_with_autorelease_frequency(null, DISPATCH_AUTORELEASE_FREQUENCY_WORK_ITEM),
            QOS_CLASS_UTILITY,
            0,
        ),
    )

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(queue) { block.run() }
    }
}

private val exclusiveLock = NSLock()

internal actual fun <T> exclusive(block: () -> T): T {
    exclusiveLock.lock()
    try {
        return block()
    } finally {
        exclusiveLock.unlock()
    }
}
