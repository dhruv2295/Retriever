package app.retriever.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.retriever.R
import app.retriever.core.Person
import app.retriever.core.Words
import app.retriever.ui.components.FaceAvatar

/** A person's top bar actions: name them, or say another group is them too. */
@Composable
internal fun PersonActions(person: Person?, others: List<Person>, onRename: (String) -> Unit, onSameAs: (Long) -> Unit) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = { renaming = true }) {
        Icon(painterResource(R.drawable.ic_edit), contentDescription = if (person?.name == null) Words.ADD_A_NAME else "Rename")
    }
    if (others.isNotEmpty()) {
        IconButton(onClick = { menu = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options")
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(Words.SAME_PERSON_AS) }, onClick = {
                    menu = false
                    picking = true
                })
            }
        }
    }
    if (renaming) {
        var name by rememberSaveable { mutableStateOf(person?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(Words.personTitle(person?.name)) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(Words.NAME) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    onRename(name)
                    renaming = false
                }) { Text(Words.SAVE) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(Words.CANCEL) } },
        )
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(Words.SAME_PERSON_AS) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(others, key = { it.id }) { p ->
                        ListItem(
                            headlineContent = { Text(Words.personTitle(p.name)) },
                            supportingContent = { Text(Words.photos(p.photos.toLong())) },
                            leadingContent = { FaceAvatar(p.cover, p.name, 40.dp) },
                            modifier = Modifier.clickable {
                                onSameAs(p.id)
                                picking = false
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text(Words.CANCEL) } },
        )
    }
}
