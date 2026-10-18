package app.retriever.diagnostics

import android.util.Log

/**
 * Where core's log lines land. A release build writes the message, the exception class and its top stack frame;
 * never the exception's message (it can hold a file path) and never the throwable itself, whose logcat dump prints it.
 */
object LogSink {
    private const val TAG = "Retriever"

    fun write(debug: Boolean, message: String, t: Throwable?) {
        if (debug) Log.w(TAG, message, t) else Log.w(TAG, render(message, t))
    }

    internal fun render(message: String, t: Throwable?): String =
        if (t == null) message else "$message [${t::class.simpleName} at ${t.stackTrace.firstOrNull() ?: "unknown"}]"
}
