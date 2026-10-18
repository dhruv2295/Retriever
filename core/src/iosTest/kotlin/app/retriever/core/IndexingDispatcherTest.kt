@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.retriever.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.posix.QOS_CLASS_UTILITY
import platform.posix.qos_class_self
import kotlin.test.Test
import kotlin.test.assertEquals

class IndexingDispatcherTest {
    @Test
    fun indexingResumesAtUtilityQos() = runTest {
        withContext(indexingDispatcher) {
            assertEquals(QOS_CLASS_UTILITY, qos_class_self())
            delay(1)
            assertEquals(QOS_CLASS_UTILITY, qos_class_self())
        }
    }
}
