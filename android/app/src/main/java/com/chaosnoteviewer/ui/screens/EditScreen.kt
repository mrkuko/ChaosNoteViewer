package com.chaosnoteviewer.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// noteId == null: a new note
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(noteId: String?, onDone: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noteId == null) "New note" else "Edit note") },
                navigationIcon = { BackButton(onDone) },
                actions = { TextButton(onClick = onDone) { Text("Save") } },
            )
        },
    ) { padding ->
        Text(if (noteId == null) "New note form" else "Editing $noteId", Modifier.padding(padding).padding(16.dp))
    }
}
