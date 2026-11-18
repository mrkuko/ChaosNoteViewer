package com.chaosnoteviewer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaosnoteviewer.R
import com.chaosnoteviewer.account.FirebaseConfig
import com.chaosnoteviewer.ui.AccountViewModel
import com.chaosnoteviewer.sync.SetupCode

// Links the setup steps point to. The rules are part of this project; the user publishes them in their own project.
const val FIREBASE_CONSOLE_URL = "https://console.firebase.google.com/"
const val FIRESTORE_RULES_URL = "https://github.com/mrkuko/ChaosNoteViewer/blob/main/core/firebase/firestore.rules"

// First start: the app works on this device right away; syncing needs the user's own Firebase project, entered here
// or later in Settings. Shown once (Back = use on this device only).
@Composable
fun SetupScreen(vm: AccountViewModel, onLocalOnly: () -> Unit, onConfigured: () -> Unit) {
    BackHandler { vm.skipSetup(); onLocalOnly() }
    var showForm by rememberSaveable { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(112.dp).align(Alignment.CenterHorizontally),
            )
            Text("Welcome to Chaos Note Viewer", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Your notes are stored on this phone. The app works fully without an account or a network.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { vm.skipSetup(); onLocalOnly() }, modifier = Modifier.fillMaxWidth()) {
                Text("Use on this device only")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Sync with your other devices", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sync goes through your own Firebase project (the free plan is enough), so your notes are never kept on " +
                    "somebody else's server. They are encrypted on the device before they leave it. You can also set this up " +
                    "later in Settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showForm) {
                FirebaseForm(
                    vm = vm,
                    initialKey = "",
                    initialProject = FirebaseConfig.DEFAULT_PROJECT_ID,
                    saveLabel = "Save and sign in",
                    onSaved = onConfigured,
                    onCancel = { showForm = false },
                )
            } else {
                OutlinedButton(onClick = { showForm = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Set up sync")
                }
            }
        }
    }
}

// What to do in the Firebase console, as short numbered steps.
@Composable
fun FirebaseSetupSteps() {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    val steps = listOf(
        "Create a project in the Firebase console.",
        "Authentication → Sign-in method: enable Email/Password.",
        "Firestore Database: create the database, then publish this app's security rules on its Rules tab.",
        "Project settings → General: copy the Web API key and the Project ID into the fields below.",
        "Use the same project on all your devices.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { open(FIREBASE_CONSOLE_URL) }) { Text("Open Firebase console") }
            TextButton(onClick = { open(FIRESTORE_RULES_URL) }) { Text("Security rules") }
        }
    }
}

// The Web API key and the project id. Pasting the whole web app snippet (or a google-services.json) into either
// field fills in both.
@Composable
fun FirebaseForm(
    vm: AccountViewModel,
    initialKey: String,
    initialProject: String,
    saveLabel: String,
    onSaved: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    var key by rememberSaveable { mutableStateOf(initialKey) }
    var project by rememberSaveable { mutableStateOf(initialProject) }
    var problem by rememberSaveable { mutableStateOf("") }
    var saving by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current

    // a setup code from another device: the project and the encryption key at once
    fun applyCode(text: String) {
        saving = true
        vm.applySetupCode(text) { result ->
            saving = false
            if (result == null) onSaved() else problem = result
        }
    }

    // a pasted snippet: take what it has, keep the field as it was for what it lacks
    fun take(text: String, intoKey: Boolean) {
        problem = ""
        if (SetupCode.looksLikeOne(text)) return applyCode(text)
        val (k, p) = FirebaseConfig.extract(text)
        if (text.length > 60 && (k != null || p != null)) {
            k?.let { key = it }
            p?.let { project = it }
        } else if (intoKey) key = text.trim() else project = text.trim()
    }

    fun save() {
        saving = true
        vm.saveFirebase(key, project) { result ->
            saving = false
            if (result == null) onSaved() else problem = result
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // the quickest way: the QR code another device shows next to its encryption key
        OutlinedButton(
            onClick = { problem = ""; scanQr(context, onText = ::applyCode, onError = { problem = it }) },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan the QR code of another device") }
        Text(
            "Another device already syncs? It shows this code in Settings, Show key. This is your first device? " +
                "Set up a project and enter its two values:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FirebaseSetupSteps()
        OutlinedTextField(
            value = key,
            onValueChange = { take(it, intoKey = true) },
            label = { Text("Web API key") },
            placeholder = { Text("AIza…") },
            singleLine = true,
            enabled = !saving,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = project,
            onValueChange = { take(it, intoKey = false) },
            label = { Text("Project ID") },
            placeholder = { Text(FirebaseConfig.DEFAULT_PROJECT_ID) },
            singleLine = true,
            enabled = !saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!saving) save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Tip: paste the whole firebaseConfig snippet of a web app, or a copied setup code, into a field: the values are taken from it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (problem.isNotEmpty())
            Text(problem, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::save, enabled = !saving && key.isNotBlank() && project.isNotBlank()) { Text(saveLabel) }
            if (onCancel != null)
                TextButton(onClick = onCancel, enabled = !saving) { Text("Cancel") }
        }
    }
}
