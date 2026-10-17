package app.retriever

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Launches in [viewModelScope]; a failure goes to [onError] (logged by default) instead of crashing the app. */
fun ViewModel.launchCatching(
    onError: (Exception) -> Unit = { Log.e("Retriever", "${this::class.simpleName}", it) },
    block: suspend CoroutineScope.() -> Unit,
): Job = viewModelScope.launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
    }
}
