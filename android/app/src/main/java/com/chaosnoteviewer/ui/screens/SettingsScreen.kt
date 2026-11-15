package com.chaosnoteviewer.ui.screens

import androidx.compose.foundation.layout.Arrangement
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

// Settings: the account (sign in, email verification). Encryption key and sync state arrive in phase 5.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AccountViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(onBack) }) },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Account", style = MaterialTheme.typography.titleMedium)
            when {
                !vm.configured -> Text(
                    "Notes are stored on this device. Sign-in is not set up: add google-services.json from your Firebase project to android/app/ and build again.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.account is Account.SignedIn -> SignedIn((state.account as Account.SignedIn).email, vm)
                state.account is Account.Pending -> Pending((state.account as Account.Pending).email, state, vm)
                else -> SignedOut(state, vm)
            }
        }
    }
}

@Composable
private fun SignedIn(email: String, vm: AccountViewModel) {
    Text("Signed in as $email")
    Text(
        "Notes are stored on this device. Syncing between devices arrives soon.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = vm::signOut) { Text("Sign out") }
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
