package app.retriever

import android.content.Context
import androidx.core.content.edit
import app.retriever.core.Backend

internal object DebugVision {
    fun backend(context: Context): Backend? = when (context.getSharedPreferences("vision-debug", Context.MODE_PRIVATE).getString("mode", "policy")) {
        "cpu" -> Backend.CPU
        "gpu" -> Backend.GPU
        else -> null
    }

    fun set(context: Context, mode: String) {
        require(mode in setOf("cpu", "gpu", "policy")) { "Use cpu, gpu or policy" }
        context.getSharedPreferences("vision-debug", Context.MODE_PRIVATE).edit { putString("mode", mode) }
    }
}
