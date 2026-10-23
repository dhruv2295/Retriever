@file:OptIn(ExperimentalForeignApi::class)

package app.retriever.core

import app.retriever.core.vecscan.retriever_dot_rows
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo

/** The C kernel in src/nativeInterop/cinterop/vecscan.def; the arrays are pinned once per block. */
internal actual val dotRows: RowScorer? = RowScorer { rows, scales, count, q, qScale, out ->
    rows.usePinned { r ->
        scales.usePinned { s ->
            q.usePinned { qp ->
                out.usePinned { o -> retriever_dot_rows(r.addressOf(0), s.addressOf(0), count, DIMS, qp.addressOf(0), qScale, o.addressOf(0)) }
            }
        }
    }
}

internal actual val cpuCores: Int = NSProcessInfo.processInfo.activeProcessorCount.toInt()
