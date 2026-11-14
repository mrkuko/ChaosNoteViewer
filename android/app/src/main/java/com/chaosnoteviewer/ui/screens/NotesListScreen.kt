package com.chaosnoteviewer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Placeholder notes until the local database exists (phase 1).
private val sampleIds = listOf("sample-1", "sample-2", "sample-3")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(onOpenNote: (String) -> Unit, onNewNote: () -> Unit, onOpenSettings: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open notes") },
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
        LazyColumn(Modifier.padding(padding)) {
            items(sampleIds) { id ->
                ListItem(
                    headlineContent = { Text("Sample note $id") },
                    modifier = Modifier.clickable { onOpenNote(id) },
                )
            }
        }
    }
}
