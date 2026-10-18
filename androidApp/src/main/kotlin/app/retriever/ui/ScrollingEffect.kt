package app.retriever.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.LifecycleStartEffect

/** Home, search and Browse all yield reading during a gesture and release it when their screen leaves. */
@Composable
fun ScrollingEffect(scrolling: () -> Boolean, onScrolling: (Boolean) -> Unit) {
    val read by rememberUpdatedState(scrolling)
    val report by rememberUpdatedState(onScrolling)
    LaunchedEffect(Unit) { snapshotFlow { read() }.collect { report(it) } }
    LifecycleStartEffect(Unit) {
        report(read())
        onStopOrDispose { report(false) }
    }
}
