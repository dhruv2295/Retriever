@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_attr_make_with_qos_class
import platform.darwin.dispatch_queue_create
import platform.posix.QOS_CLASS_UTILITY
import kotlin.coroutines.CoroutineContext

actual val indexingDispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
    private val queue = dispatch_queue_create("app.retriever.index", dispatch_queue_attr_make_with_qos_class(null, QOS_CLASS_UTILITY, 0))

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(queue) { block.run() }
    }
}
