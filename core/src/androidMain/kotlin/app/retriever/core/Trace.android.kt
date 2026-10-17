package app.retriever.core

import android.os.Trace

actual fun traceBegin(name: String) = Trace.beginSection(name)

actual fun traceEnd() = Trace.endSection()
