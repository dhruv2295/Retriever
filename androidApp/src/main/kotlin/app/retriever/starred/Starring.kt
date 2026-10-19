package app.retriever.starred

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.retriever.R
import app.retriever.core.Retriever
import app.retriever.core.SourceFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A screen's star state: the shared set from Retriever, and toggles that report a failure instead of dropping it. */
class Starring(val starred: StateFlow<Set<String>>, private val write: suspend (uri: String, on: Boolean) -> Unit, private val scope: CoroutineScope) {
    constructor(retriever: Retriever, scope: CoroutineScope) : this(retriever.starred, retriever::setStarred, scope)

    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun set(uri: String, on: Boolean) {
        scope.launch {
            try {
                write(uri, on)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _failed.value = true
            }
        }
    }

    fun toggle(uri: String) = set(uri, uri !in starred.value)
    fun failureShown() {
        _failed.value = false
    }
}

/** The PDF and player screens' star state; Browse has its own in [BrowseViewModel]. */
class StarHolder(retriever: Retriever) : ViewModel() {
    val stars = Starring(retriever, viewModelScope)
}

/** Shows a star failure once, then clears it. */
@Composable
internal fun StarFailureEffect(stars: Starring, snackbar: SnackbarHostState) {
    val failed by stars.failed.collectAsStateWithLifecycle()
    LaunchedEffect(failed) {
        if (failed) {
            snackbar.showSnackbar("Couldn't change the star. Try again.")
            stars.failureShown()
        }
    }
}

/** The viewers' top-bar star. */
@Composable
fun StarButton(on: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            painterResource(if (on) R.drawable.ic_star else R.drawable.ic_star_border),
            contentDescription = if (on) "Remove from Starred" else "Add to Starred",
        )
    }
}

/** Stars or unstars a file and says so in [snackbar], with Undo. */
internal fun starWithUndo(onStar: (SourceFile, Boolean) -> Unit, snackbar: SnackbarHostState, scope: CoroutineScope): (SourceFile, Boolean) -> Unit =
    { file, on ->
        onStar(file, on)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val said = snackbar.showSnackbar(if (on) "Starred" else "Star removed", "Undo", duration = SnackbarDuration.Short)
            if (said == SnackbarResult.ActionPerformed) onStar(file, !on)
        }
    }
