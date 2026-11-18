package com.chaosnoteviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chaosnoteviewer.ChaosApp
import com.chaosnoteviewer.account.AccountManager
import com.chaosnoteviewer.account.FirebaseSettings
import kotlinx.coroutines.launch

class AccountViewModel(private val manager: AccountManager, private val firebase: FirebaseSettings) : ViewModel() {
    val state = manager.state

    // the user's Firebase project (null = sync not set up) and whether the first-start setup was shown
    val firebaseConfig = firebase.config
    val setupSeen = firebase.setupSeen
    // false in a debug build for the local emulator: the project is fixed there
    val firebaseEditable: Boolean get() = firebase.editable

    // Saves the project; [onDone] gets what is wrong, or null when it was saved. A session remembered for this
    // project (it was removed and entered again) comes back at once.
    fun saveFirebase(apiKey: String, projectId: String, onDone: (String?) -> Unit) = viewModelScope.launch {
        val problem = firebase.save(apiKey, projectId)
        onDone(problem)
        if (problem == null) manager.restore()
    }

    fun skipSetup() = viewModelScope.launch { firebase.skipSetup() }
    fun removeFirebase() = viewModelScope.launch { firebase.remove() }

    fun authenticate(create: Boolean, email: String, password: String) =
        viewModelScope.launch { manager.authenticate(create, email, password) }

    fun checkVerified() = viewModelScope.launch { manager.checkVerified() }
    fun resendVerification() = viewModelScope.launch { manager.resendVerification() }
    fun cancelPending() = manager.cancelPending()
    fun resetPassword(email: String) = viewModelScope.launch { manager.resetPassword(email) }
    fun signOut() = manager.signOut()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ChaosApp
                AccountViewModel(app.account, app.firebase)
            }
        }
    }
}
