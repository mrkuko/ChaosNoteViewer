package com.chaosnoteviewer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chaosnoteviewer.ui.NotesViewModel

// noteId == null: a new note
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: NotesViewModel, noteId: String?, onDone: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var loaded by rememberSaveable { mutableStateOf(noteId == null) }

    LaunchedEffect(noteId) {
        if (!loaded && noteId != null) {
            vm.get(noteId)?.let { title = it.title; description = it.description }
            loaded = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noteId == null) "New note" else "Edit note") },
                navigationIcon = { BackButton(onDone) },
                actions = {
                    TextButton(
                        enabled = loaded && title.isNotBlank(),
                        onClick = { vm.save(noteId, title.trim(), description.trim()); onDone() },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}
