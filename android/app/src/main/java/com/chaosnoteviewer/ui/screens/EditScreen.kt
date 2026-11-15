package com.chaosnoteviewer.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.chaosnoteviewer.ui.Dates
import com.chaosnoteviewer.ui.NotesViewModel

// noteId == null: a new note
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: NotesViewModel, noteId: String?, onDone: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var dueDate by rememberSaveable { mutableStateOf<Long?>(null) }     // local midnight
    var loaded by rememberSaveable { mutableStateOf(noteId == null) }
    var picking by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(noteId) {
        if (!loaded && noteId != null) {
            vm.get(noteId)?.let { title = it.title; description = it.description; dueDate = it.dueDate }
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
                        onClick = { vm.save(noteId, title.trim(), description.trim(), dueDate); onDone() },
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                minLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picking = true }) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.padding(end = 8.dp))
                    Text(dueDate?.let { "Deadline: ${Dates.format(it)}" } ?: "Add deadline")
                }
                if (dueDate != null)
                    IconButton(onClick = { dueDate = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove deadline")
                    }
            }
        }
    }

    if (picking) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = dueDate?.let { Dates.dueToPicker(it) })
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedDateMillis != null,
                    onClick = {
                        picker.selectedDateMillis?.let { dueDate = Dates.pickerToDue(it) }
                        picking = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = picker)
        }
    }
}
