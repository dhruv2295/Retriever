package app.retriever

import android.content.Context

/** Release builds have no demo mode. */
object Demo {
    @Suppress("UNUSED_PARAMETER")
    fun parts(context: Context): DemoParts? = null
}
