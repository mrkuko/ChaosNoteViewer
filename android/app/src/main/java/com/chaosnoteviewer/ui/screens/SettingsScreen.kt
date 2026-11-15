package com.chaosnoteviewer.ui.screens

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth as fillWidth
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaosnoteviewer.extras.NotesWidgetReceiver
import com.chaosnoteviewer.extras.Notifications
import com.chaosnoteviewer.ui.ExtrasViewModel
import androidx.compose.foundation.layout.Arrangement
import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.Instant
import com.chaosnoteviewer.sync.KeyPhase
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaosnoteviewer.account.Account
import com.chaosnoteviewer.account.AccountState
import com.chaosnoteviewer.ui.AccountViewModel
import com.chaosnoteviewer.ui.SyncViewModel

// Settings: the account (sign in, email verification). Encryption key and sync state arrive in phase 5.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AccountViewModel, syncVm: SyncViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ExtrasSection()
            Text("Account", style = MaterialTheme.typography.titleMedium)
            when {
                !vm.configured -> Text(
                    "Notes are stored on this device. Sign-in is not set up: add google-services.json from your Firebase project to android/app/ and build again.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.account is Account.SignedIn -> SignedIn((state.account as Account.SignedIn).email, vm, syncVm)
                state.account is Account.Pending -> Pending((state.account as Account.Pending).email, state, vm)
                else -> SignedOut(state, vm)
            }
        }
    }
}

@Composable
private fun SignedIn(email: String, vm: AccountViewModel, syncVm: SyncViewModel) {
    Text("Signed in as $email")
    OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
    SyncSection(syncVm)
}

// signed in at Firebase, the email link was not opened yet
@Composable
private fun Pending(email: String, state: AccountState, vm: AccountViewModel) {
    Text("Verify $email")
    Text(
        "Open the link in the verification email, then press Continue.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Messages(state)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = vm::checkVerified, enabled = !state.busy) { Text("Continue") }
        OutlinedButton(onClick = vm::resendVerification, enabled = !state.busy) { Text("Resend email") }
        TextButton(onClick = vm::cancelPending, enabled = !state.busy) { Text("Cancel") }
        Busy(state)
    }
}

@Composable
private fun SignedOut(state: AccountState, vm: AccountViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val passwordFocus = remember { FocusRequester() }

    // the password never outlives a successful sign-in
    LaunchedEffect(state.account) { password = "" }

    Text(
        "Notes are stored on this device. Sign in to sync them between devices.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("Email") },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        enabled = !state.busy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.authenticate(false, email, password) }),
        modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
    )
    Messages(state)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { vm.authenticate(false, email, password) }, enabled = !state.busy) { Text("Sign in") }
        OutlinedButton(onClick = { vm.authenticate(true, email, password) }, enabled = !state.busy) { Text("Create account") }
        Busy(state)
    }
    TextButton(onClick = { vm.resetPassword(email) }, enabled = !state.busy) { Text("Forgot password?") }
}

@Composable
private fun Messages(state: AccountState) {
    if (state.info.isNotEmpty())
        Text(state.info, color = MaterialTheme.colorScheme.primary)
    if (state.error.isNotEmpty())
        Text(state.error, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Busy(state: AccountState) {
    if (state.busy)
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
}

// ---- sync and the encryption key ----

@Composable
private fun SyncSection(vm: SyncViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    Text("Sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    when (state.phase) {
        KeyPhase.Ready -> {
            Text(
                when {
                    state.syncing -> "Syncing…"
                    state.syncError.isNotEmpty() -> "Last sync failed: ${state.syncError}"
                    state.lastSyncMs > 0 -> "Last sync: ${formatTime(state.lastSyncMs)}"
                    else -> "Your notes are encrypted on this device before they are uploaded."
                },
                color = if (state.syncError.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::sync, enabled = !state.syncing) { Text("Sync now") }
                if (state.syncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            KeyBlock(state.keyBlock)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::toggleKeyShown) { Text(if (state.keyBlock.isEmpty()) "Show encryption key" else "Hide key") }
                TextButton(onClick = { confirmReset = true }) { Text("Lost the key?") }
            }
        }
        KeyPhase.Save -> {
            Text("Save your encryption key", style = MaterialTheme.typography.titleSmall)
            Text(
                "Your notes are encrypted with this key. It exists only on your devices, not in the cloud, so nobody can " +
                    "recover it for you. Write it down or keep it in a password manager: you need it to read your notes on " +
                    "another device. Syncing starts when you confirm.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeyBlock(state.keyBlock)
            Button(onClick = vm::confirmKey, enabled = state.keyBlock.isNotEmpty()) { Text("I saved the key") }
        }
        KeyPhase.Enter -> {
            var typed by rememberSaveable { mutableStateOf("") }
            Text(
                if (state.mismatch) "The encryption key changed" else "Enter your encryption key",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (state.mismatch)
                    "The notes in the cloud were encrypted with another key (it was reset on another device). Enter the new key to continue."
                else
                    "Your notes in the cloud are encrypted. Enter the key you saved when you first signed in on your other device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Encryption key") },
                enabled = !state.keyBusy,
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.submitKey(typed) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.submitKey(typed) }, enabled = !state.keyBusy && typed.isNotBlank()) { Text("Continue") }
                TextButton(onClick = { confirmReset = true }, enabled = !state.keyBusy) { Text("I lost the key") }
                if (state.keyBusy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        KeyPhase.Unknown -> {
            Text(
                if (state.keyBusy) "Checking the encryption key…" else "Could not check the encryption key yet. Check your connection.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::retryKeyFlow, enabled = !state.keyBusy) { Text("Retry") }
                if (state.keyBusy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
    if (state.keyError.isNotEmpty())
        Text(state.keyError, color = MaterialTheme.colorScheme.error)

    if (confirmReset)
        ConfirmDialog(
            title = "Create a new key?",
            text = "This deletes ALL your notes in the cloud, makes a new key and uploads the notes on this device again. " +
                "Notes that exist only on your other devices are lost from the cloud until those devices upload them again. " +
                "Use this only when the old key is lost.",
            confirm = "Delete cloud copy",
            onConfirm = { vm.resetKey() },
            onDismiss = { confirmReset = false },
        )
}

// the key as four lines, copyable as one
@Composable
private fun KeyBlock(block: String) {
    if (block.isEmpty()) return
    val context = LocalContext.current
    Text(
        block,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    OutlinedButton(onClick = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Encryption key", block.lines().joinToString("-")))
    }) { Text("Copy key") }
}

private fun formatTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))

// ---- reminders, the count on the icon, the widget ----

@Composable
private fun ExtrasSection() {
    val vm: ExtrasViewModel = viewModel(factory = ExtrasViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var blocked by rememberSaveable { mutableStateOf(false) }
    // the switch that waits for the answer of the permission dialog: "reminders" or "badge"
    var pending by rememberSaveable { mutableStateOf("") }

    fun apply(which: String) {
        if (which == "reminders") vm.setReminders(true) else vm.setCountBadge(true)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val which = pending
        pending = ""
        blocked = !granted
        if (granted && which.isNotEmpty()) apply(which)
    }

    // turning a switch on needs the notification permission (Android 13+) and notifications not blocked
    fun turnOn(which: String) {
        blocked = false
        when {
            Notifications.canPost(context) -> apply(which)
            Build.VERSION.SDK_INT >= 33 && !Notifications.permissionDenied(context) -> {
                pending = which
                Notifications.rememberAsked(context)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> blocked = true          // refused before, or blocked in the system settings: only the user can change it
        }
    }

    Text("Reminders and widget", style = MaterialTheme.typography.titleMedium)
    SwitchRow(
        title = "Remind me about deadlines",
        text = "Every morning around 8:00, a notification with the notes that are due today or overdue.",
        checked = state.reminders,
        onChange = { if (it) turnOn("reminders") else vm.setReminders(false) },
    )
    SwitchRow(
        title = "Show open notes on the app icon",
        text = "A quiet notification with the number of open notes. Whether the launcher shows the number or only a dot depends on the launcher.",
        checked = state.countBadge,
        onChange = { if (it) turnOn("badge") else vm.setCountBadge(false) },
    )
    if (blocked)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Notifications are blocked for this app. Allow them in the system settings to use this.",
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Open notification settings") }
        }

    val widgets = remember { AppWidgetManager.getInstance(context) }
    if (widgets.isRequestPinAppWidgetSupported)
        OutlinedButton(onClick = {
            widgets.requestPinAppWidget(ComponentName(context, NotesWidgetReceiver::class.java), null, null)
        }) { Text("Add the widget to the home screen") }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
