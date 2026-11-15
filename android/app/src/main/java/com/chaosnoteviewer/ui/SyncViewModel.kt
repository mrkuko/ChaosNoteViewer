package com.chaosnoteviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chaosnoteviewer.ChaosApp
import com.chaosnoteviewer.account.Account
import com.chaosnoteviewer.account.AccountManager
import com.chaosnoteviewer.sync.SyncManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SyncViewModel(private val manager: SyncManager, account: AccountManager) : ViewModel() {
    val state = manager.state

    init {
        // the key flow and the first sync start when the account signs in (also at app start, from the remembered session)
        viewModelScope.launch {
            account.state.map { it.account is Account.SignedIn }.distinctUntilChanged().collect { manager.onSignedIn(it) }
        }
    }

    fun sync() = viewModelScope.launch { manager.syncNow() }
    fun retryKeyFlow() = viewModelScope.launch { manager.startKeyFlow() }
    fun submitKey(text: String) = viewModelScope.launch { manager.submitKey(text) }
    fun confirmKey() = viewModelScope.launch { manager.confirmKey() }
    fun toggleKeyShown() = viewModelScope.launch { manager.toggleKeyShown() }
    fun resetKey() = viewModelScope.launch { manager.resetKey() }
    fun reuploadNote(id: String) = viewModelScope.launch { manager.reuploadNote(id) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ChaosApp
                SyncViewModel(app.sync, app.account)
            }
        }
    }
}
