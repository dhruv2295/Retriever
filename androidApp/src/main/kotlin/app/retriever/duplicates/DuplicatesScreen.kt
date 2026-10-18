package app.retriever.duplicates

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.retriever.R
import app.retriever.core.Retriever
import app.retriever.core.motion.MascotMood
import app.retriever.motion.Mascot
import app.retriever.nav.Route
import app.retriever.ui.Format
import app.retriever.ui.components.ConfirmDialog
import app.retriever.ui.components.EmptyState
import app.retriever.ui.components.FileRow
import app.retriever.ui.components.contentPaddingFor
import app.retriever.ui.components.gutter
import app.retriever.viewer.Opener
import kotlinx.coroutines.flow.first
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(onNavigate: (Route) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier, vm: DuplicatesViewModel = koinViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var confirming by rememberSaveable { mutableStateOf(false) }
    val confirmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onConfirmed()
    }
    LaunchedEffect(s.confirm) {
        s.confirm?.let {
            confirmLauncher.launch(IntentSenderRequest.Builder(it).build())
            // Cleared once shown: a rotation must not ask twice, and the result still reaches the new launcher.
            vm.confirmShown()
        }
    }
    val context = LocalContext.current
    val reclaimable = s.groups.sumOf { it.wastedBytes }
    val selectedCount = s.selected.size

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Duplicates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                s.scanning -> Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Mascot(MascotMood.WALKING, Modifier.align(Alignment.CenterHorizontally).width(160.dp))
                    Text("Looking for copies…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                s.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "Couldn't look for duplicates",
                        description = s.error!!,
                        icon = R.drawable.ic_warning_amber,
                        action = "Try again" to vm::scan,
                        mood = MascotMood.TROUBLE,
                    )
                }

                s.groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "No duplicates found",
                        description = "Retriever compared ${"%,d".format(s.scannedFiles)} files.",
                        icon = R.drawable.ic_content_copy,
                        mood = MascotMood.NO_MATCH,
                    )
                }

                else -> LazyColumn(
                    contentPadding = contentPaddingFor(PaddingValues(bottom = padding.calculateBottomPadding()), side = gutter()),
                    modifier = Modifier.fillMaxSize(),
                ) {
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
                                // Look before deleting: copies often have identical thumbnails.
                                trailing = {
                                    IconButton(onClick = { Opener.open(context, file, "", onNavigate) }) {
                                        Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = "Open ${file.name}")
                                    }
                                },
                                onClickLabel = if (checked) "Deselect" else "Select",
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
            onConfirm = {
                confirming = false
                vm.deleteSelected()
            },
            onDismiss = { confirming = false },
        )
    }
}
