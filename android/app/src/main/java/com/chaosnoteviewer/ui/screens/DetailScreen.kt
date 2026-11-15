package com.chaosnoteviewer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NoteQuery
import com.chaosnoteviewer.ui.Dates
import com.chaosnoteviewer.ui.NotesViewModel
import com.chaosnoteviewer.ui.linkify

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: NotesViewModel, noteId: String, onBack: () -> Unit, onEdit: () -> Unit) {
    // Unloaded = still reading; null afterwards = the note is gone (deleted), leave the page.
    val note by remember(noteId) { vm.observe(noteId) }.collectAsStateWithLifecycle(Unloaded)
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(note) {
        if (note == null || note?.deletedAt != null) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                },
            )
        },
    ) { padding ->
        val n = note
        if (n == null || n === Unloaded) return@Scaffold
        val link = MaterialTheme.colorScheme.primary
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                linkify(n.title, link),
                style = MaterialTheme.typography.headlineSmall,
                textDecoration = if (n.completed) TextDecoration.LineThrough else null,
            )
            if (n.dueDate != null) {
                Spacer(Modifier.height(8.dp))
                DueDate(n.dueDate, NoteQuery.isOverdue(n, NoteQuery.startOfToday()), MaterialTheme.typography.bodyMedium)
            }
            if (n.description.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(linkify(n.description, link), style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Created ${Dates.format(n.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (n.completed)
                OutlinedButton(onClick = { vm.setCompleted(n.id, false) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.padding(end = 8.dp))
                    Text("Mark as open")
                }
            else
                Button(onClick = { vm.setCompleted(n.id, true) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors()) {
                    Icon(Icons.Filled.Check, contentDescription = null, Modifier.padding(end = 8.dp))
                    Text("Mark as completed")
                }
        }
    }

    if (confirmDelete)
        ConfirmDialog(
            title = "Delete note?",
            text = "\"${(note ?: Unloaded).title}\" will be deleted.",
            confirm = "Delete",
            onConfirm = { vm.delete(noteId) },          // the page closes when the note is gone
            onDismiss = { confirmDelete = false },
        )
}

// placeholder before the first database read
private val Unloaded = NoteEntity(id = "", title = "", position = 0.0, createdAt = 0, updatedAt = 0)
