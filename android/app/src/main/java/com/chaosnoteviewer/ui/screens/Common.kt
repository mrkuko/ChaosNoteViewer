package com.chaosnoteviewer.ui.screens

import com.chaosnoteviewer.ui.AppIcon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.chaosnoteviewer.ui.Dates

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

// Deadline with a calendar icon; overdue (open and the day has passed) in the error color.
@Composable
fun DueDate(dueDate: Long, overdue: Boolean, style: TextStyle = MaterialTheme.typography.bodySmall) {
    val color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(if (overdue) "calendar-alert" else "calendar", tint = color, size = 16.dp)
        Spacer(Modifier.width(4.dp))
        Text(if (overdue) "Overdue · ${Dates.format(dueDate)}" else Dates.format(dueDate), color = color, style = style)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
