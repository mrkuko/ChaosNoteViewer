package com.chaosnoteviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chaosnoteviewer.ChaosApp
import com.chaosnoteviewer.account.AccountManager
import kotlinx.coroutines.launch

class AccountViewModel(private val manager: AccountManager) : ViewModel() {
    val configured: Boolean get() = manager.configured
    val state = manager.state

    init {
        viewModelScope.launch { manager.restore() }
    }

    fun authenticate(create: Boolean, email: String, password: String) =
        viewModelScope.launch { manager.authenticate(create, email, password) }

    fun checkVerified() = viewModelScope.launch { manager.checkVerified() }
    fun resendVerification() = viewModelScope.launch { manager.resendVerification() }
    fun cancelPending() = manager.cancelPending()
    fun resetPassword(email: String) = viewModelScope.launch { manager.resetPassword(email) }
    fun signOut() = manager.signOut()

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { AccountViewModel((this[APPLICATION_KEY] as ChaosApp).account) }
        }
    }
}
