package com.chaosnoteviewer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaosnoteviewer.ui.NotesViewModel

// Phase 1: every live note in queue order. Filters, sorting and drag arrive in phase 2.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(vm: NotesViewModel, onOpenNote: (String) -> Unit, onNewNote: () -> Unit, onOpenSettings: () -> Unit) {
    val notes by vm.notes.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewNote) {
                Icon(Icons.Filled.Add, contentDescription = "New note")
            }
        },
    ) { padding ->
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No notes yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(notes, key = { it.id }) { note ->
                    ListItem(
                        headlineContent = {
                            Text(
                                note.title,
                                textDecoration = if (note.completed) TextDecoration.LineThrough else null,
                            )
                        },
                        leadingContent = {
                            Checkbox(checked = note.completed, onCheckedChange = { vm.setCompleted(note.id, it) })
                        },
                        modifier = Modifier.clickable { onOpenNote(note.id) },
                    )
                }
            }
        }
    }
}
