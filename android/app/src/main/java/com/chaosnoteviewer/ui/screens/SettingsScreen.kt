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
import com.chaosnoteviewer.sync.SyncScheduler
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
import com.chaosnoteviewer.account.FirebaseConfig
import com.chaosnoteviewer.ui.AccountViewModel
import com.chaosnoteviewer.ui.SyncViewModel
import com.chaosnoteviewer.ui.QrCode
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

// Settings: reminders and the widget, the Firebase project to sync with, the account and the encryption key.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AccountViewModel, syncVm: SyncViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val config by vm.firebaseConfig.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ExtrasSection()
            FirebaseSection(vm, signedOut = state.account is Account.SignedOut)
            if (config != null) {
                Text("Account", style = MaterialTheme.typography.titleMedium)
                when (val account = state.account) {
                    is Account.SignedIn -> SignedIn(account.email, vm, syncVm)
                    is Account.Pending -> Pending(account.email, state, vm)
                    else -> SignedOut(state, vm)
                }
            }
        }
    }
}

// The user's own Firebase project: entered here (or on first start), changed only while signed out.
@Composable
private fun FirebaseSection(vm: AccountViewModel, signedOut: Boolean) {
    val config by vm.firebaseConfig.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val current = config

    Text("Sync", style = MaterialTheme.typography.titleMedium)
    when {
        current != null && current.emulator -> Text(
            "Debug build: syncs with the local Firebase emulator (${current.projectId}).",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        current == null || editing -> {
            Text(
                if (current == null)
                    "Sync is off: your notes are stored on this device only. To sync them with your other devices, " +
                        "connect your own Firebase project. Your notes are encrypted before they are uploaded."
                else
                    "Changing the project uploads the notes of this device to the new project and sets up the encryption key again.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FirebaseSetupSteps()
            FirebaseForm(
                vm = vm,
                initialKey = current?.apiKey.orEmpty(),
                initialProject = current?.projectId ?: FirebaseConfig.DEFAULT_PROJECT_ID,
                saveLabel = "Save",
                onSaved = { editing = false },
                onCancel = if (current != null) ({ editing = false }) else null,
            )
        }
        else -> {
            Text("Firebase project: ${current.projectId}")
            Text(
                "Web API key …${current.apiKey.takeLast(4)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (signedOut)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = true }) { Text("Change") }
                    TextButton(onClick = { confirmRemove = true }) { Text("Turn sync off") }
                }
            else
                Text(
                    "Sign out to change the project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
    if (confirmRemove)
        ConfirmDialog(
            title = "Turn sync off?",
            text = "The Firebase key is removed from this device. Your notes stay here, and the cloud copy stays in your project.",
            confirm = "Turn off",
            onConfirm = { vm.removeFirebase() },
            onDismiss = { confirmRemove = false },
        )
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
    val backgroundSync by vm.backgroundSync.collectAsStateWithLifecycle()
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    Text("Encryption and sync status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    when (state.phase) {
        KeyPhase.Ready -> {
            Text(
                when {
                    state.syncing -> "Syncing…"
                    state.syncError.isNotEmpty() -> "Last sync failed: ${state.syncError}" +
                        if (state.failures > 1) " Trying again every ${retryText(state.failures)}." else ""
                    state.lastSyncMs > 0 -> "Last sync: ${formatTime(state.lastSyncMs)}"
                    else -> "Your notes are encrypted on this device before they are uploaded."
                },
                color = if (state.syncError.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::sync, enabled = !state.syncing) { Text("Sync now") }
                if (state.syncing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            SwitchRow(
                title = "Sync in the background",
                text = "Also while the app is closed: about every 15 minutes with a network, and as soon as the network is back after changes made offline.",
                checked = backgroundSync,
                onChange = vm::setBackgroundSync,
            )
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
            var scanError by rememberSaveable { mutableStateOf("") }
            val context = LocalContext.current
            Text(
                if (state.mismatch) "The encryption key changed" else "Enter your encryption key",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (state.mismatch)
                    "The notes in the cloud were encrypted with another key (it was reset on another device). Enter the new key to continue."
                else
                    "Your notes in the cloud are encrypted. Enter the key you saved when you first signed in on your other device, " +
                        "or scan its QR code (Settings on the desktop app, Show key).",
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
                OutlinedButton(
                    onClick = {
                        scanError = ""
                        // the scanned text goes the same way as typed text, so a foreign QR gets the usual key error
                        scanKey(context, onText = { typed = it; vm.submitKey(it) }, onError = { scanError = it })
                    },
                    enabled = !state.keyBusy,
                ) { Text("Scan QR code") }
                if (state.keyBusy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            if (scanError.isNotEmpty())
                Text(scanError, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { confirmReset = true }, enabled = !state.keyBusy) { Text("I lost the key") }
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

// the key as four lines, copyable as one, and as a QR code for another phone
@Composable
private fun KeyBlock(block: String) {
    if (block.isEmpty()) return
    val context = LocalContext.current
    // no screenshots or recent-apps preview while the key is on screen
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Text(
        block,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
    // the same content as the desktop's QR: the key on one line
    KeyQr(block.lines().joinToString("-"))
    OutlinedButton(onClick = {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Encryption key", block.lines().joinToString("-")))
    }) { Text("Copy key") }
}

// black on white with the 4-module quiet zone in both themes, so a camera reads it
@Composable
private fun KeyQr(text: String) {
    val qr = remember(text) { QrCode.encode(text) } ?: return
    Canvas(Modifier.size(220.dp).background(Color.White)) {
        val cells = qr.size + 8
        val cell = size.minDimension / cells
        for (y in 0 until qr.size)
            for (x in 0 until qr.size)
                if (qr.isDark(x, y))
                    // a hair larger than the cell, so no seams show between neighbours
                    drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

// Google's code scanner: it draws the camera screen itself, the app needs no camera permission. The scanner
// module is downloaded by Play services on first use.
private fun scanKey(context: Context, onText: (String) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { barcode -> barcode.rawValue?.let(onText) }
        .addOnFailureListener { onError("Could not start the QR scanner (it needs Google Play services). Type the key instead.") }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun formatTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))

// ---- reminders, the widget ----

@Composable
private fun ExtrasSection() {
    val vm: ExtrasViewModel = viewModel(factory = ExtrasViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var blocked by rememberSaveable { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        blocked = !granted
        if (granted) vm.setReminders(true)
    }

    // turning the switch on needs the notification permission (Android 13+) and notifications not blocked
    fun turnOn() {
        blocked = false
        when {
            Notifications.canPost(context) -> vm.setReminders(true)
            Build.VERSION.SDK_INT >= 33 && !Notifications.permissionDenied(context) -> {
                Notifications.rememberAsked(context)
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> blocked = true          // refused before, or blocked in the system settings: only the user can change it
        }
    }

    Text("Reminders and widget", style = MaterialTheme.typography.titleMedium)
    SwitchRow(
        title = "Remind me about deadlines",
        text = "Every morning around 8:00, one notification for each note that is due today or overdue.",
        checked = state.reminders,
        onChange = { if (it) turnOn() else vm.setReminders(false) },
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

// the waiting time between two tries while the sync keeps failing (30 s doubled per failure, as SyncScheduler does)
private fun retryText(failures: Int): String {
    val seconds = SyncScheduler.pollInterval(SyncScheduler.FOREGROUND_POLL_MS, failures) / 1000
    return if (seconds < 120) "$seconds s" else "${seconds / 60} min"
}
