package app.retriever.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** Home, search, Browse and the PDF reader all yield reading during a gesture and release it when their screen leaves. */
@Composable
fun ScrollingEffect(scrolling: () -> Boolean, onScrolling: (Boolean) -> Unit) {
    val read by rememberUpdatedState(scrolling)
    val report by rememberUpdatedState(onScrolling)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                snapshotFlow { read() }.collect { report(it) }
            } finally {
                report(false)
            }
        }
    }
}
