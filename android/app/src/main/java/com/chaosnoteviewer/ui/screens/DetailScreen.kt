package com.chaosnoteviewer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaosnoteviewer.ui.NotesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: NotesViewModel, noteId: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val note by remember(noteId) { vm.observe(noteId) }.collectAsStateWithLifecycle(null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                    }
                    // confirmation dialog arrives in phase 2
                    IconButton(onClick = { vm.delete(noteId); onBack() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            val n = note ?: return@Column
            Text(n.title, style = MaterialTheme.typography.headlineSmall)
            if (n.description.isNotEmpty())
                Text(n.description, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
