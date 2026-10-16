package app.rummage

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import app.rummage.core.DuplicateFinder
import app.rummage.core.DuplicateGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

data class DuplicatesState(
    val scanning: Boolean = true,
    val done: Int = 0,
    val total: Int = 0,
    val scannedFiles: Long = 0,
    val groups: List<DuplicateGroup> = emptyList(),
    val selected: Set<String> = emptySet(),
    val deleting: Boolean = false,
    val failed: Int = 0,
    val error: String? = null,
    val keepOne: Boolean = false,
)

class DuplicatesViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as RummageApp).graph
    private val _state = MutableStateFlow(DuplicatesState())
    val state = _state

    init { scan() }

    fun scan() {
        _state.value = DuplicatesState()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val groups = DuplicateFinder(graph.store, graph.hasher).find { done, total ->
                    _state.update { it.copy(done = done, total = total) }
                }
                // Keep the oldest copy of each group (files arrive oldest first).
                val selected = groups.flatMap { g -> g.files.drop(1).map { it.uri } }.toSet()
                _state.update {
                    it.copy(scanning = false, groups = groups, selected = selected, scannedFiles = graph.store.fileCount())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Rummage", "duplicate scan failed", e)
                _state.update { it.copy(scanning = false, error = "Couldn't look for duplicates") }
            }
        }
    }

    /** Never selects the last unselected copy in a group: deleting every copy would lose the file. */
    fun toggle(uri: String) = _state.update { s ->
        if (uri in s.selected) return@update s.copy(selected = s.selected - uri, keepOne = false)
        val group = s.groups.firstOrNull { g -> g.files.any { it.uri == uri } }
        val lastCopy = group != null && group.files.all { it.uri == uri || it.uri in s.selected }
        if (lastCopy) s.copy(keepOne = true) else s.copy(selected = s.selected + uri, keepOne = false)
    }

    fun selectedBytes(s: DuplicatesState): Long =
        s.groups.sumOf { g -> g.size * g.files.count { it.uri in s.selected } }

    fun deleteSelected() {
        val uris = _state.value.selected.toList()
        if (uris.isEmpty() || _state.value.deleting) return
        _state.update { it.copy(deleting = true, failed = 0) }
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val deleted = withContext(Dispatchers.IO) {
                uris.filter { uri ->
                    try {
                        resolver.delete(Uri.parse(uri), null, null) > 0
                    } catch (e: Exception) {
                        Log.w("Rummage", "delete failed: $uri", e)
                        false
                    }
                }
            }
            withContext(Dispatchers.IO) { graph.store.removeFiles(deleted) }
            val gone = deleted.toSet()
            _state.update { s ->
                val groups = s.groups
                    .map { g -> g.copy(files = g.files.filter { it.uri !in gone }) }
                    .filter { it.files.size >= 2 }
                s.copy(
                    groups = groups,
                    selected = s.selected - gone,
                    deleting = false,
                    failed = uris.size - deleted.size,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(onNavigate: (Any) -> Unit, onBack: () -> Unit) {
    val vm: DuplicatesViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    var confirming by rememberSaveable { mutableStateOf(false) }
    val reclaimable = s.groups.sumOf { it.wastedBytes }
    val selectedCount = s.selected.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duplicates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            if (!s.scanning && s.groups.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (s.keepOne) {
                            Text("Keep at least one copy of each file", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (s.failed > 0) {
                            Text(
                                "${s.failed} files couldn't be deleted",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(
                            onClick = { confirming = true },
                            enabled = selectedCount > 0 && !s.deleting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Delete $selectedCount selected (${Format.bytes(vm.selectedBytes(s))})")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                s.scanning -> Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Looking for copies…", style = MaterialTheme.typography.titleMedium)
                    if (s.total > 0) {
                        LinearProgressIndicator(progress = { s.done / s.total.toFloat() }, Modifier.fillMaxWidth())
                        Text("${s.done} of ${s.total} files checked", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                s.error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(s.error!!, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = vm::scan) { Text("Try again") }
                }

                s.groups.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("No duplicates found", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Rummage compared ${"%,d".format(s.scannedFiles)} files.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(
                            "${Format.bytes(reclaimable)} could be freed",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    s.groups.forEach { g ->
                        item(key = "h" + g.files.first().uri) {
                            Text(
                                "${g.files.size} copies · ${Format.bytes(g.size)} each",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(g.files, key = { it.uri }) { file ->
                            val checked = file.uri in s.selected
                            // The row is the one toggle, so TalkBack reads the file name with the state.
                            FileRow(
                                file = file,
                                modifier = Modifier.semantics {
                                    role = Role.Checkbox
                                    toggleableState = ToggleableState(checked)
                                },
                                subtitle = file.folder + " · " + Format.date(file.modifiedAt),
                                leading = { Checkbox(checked = checked, onCheckedChange = null) },
                                onClick = { vm.toggle(file.uri) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirming) {
        ConfirmDialog(
            title = "Delete files?",
            message = "Delete $selectedCount files? This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { confirming = false; vm.deleteSelected() },
            onDismiss = { confirming = false },
        )
    }
}

// Adapted from google-ai-edge/gallery
// Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/videomomentfinder/ConfirmDeleteDialog.kt
// (Apache-2.0, Copyright 2026 Google LLC). Modified for Rummage: plain strings, no analytics hook,
// title and confirm label as parameters so Folders reuses it.
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
