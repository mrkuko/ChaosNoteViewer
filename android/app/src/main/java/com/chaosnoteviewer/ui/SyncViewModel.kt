package com.chaosnoteviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chaosnoteviewer.ChaosApp
import com.chaosnoteviewer.sync.SyncManager
import com.chaosnoteviewer.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// The sync screens. The account and the sync themselves live in ChaosApp (a background job needs them without a screen).
class SyncViewModel(private val app: ChaosApp, private val manager: SyncManager) : ViewModel() {
    val state = manager.state

    // sync while the app is closed, on unless the user turned it off
    private val _backgroundSync = MutableStateFlow(true)
    val backgroundSync: StateFlow<Boolean> = _backgroundSync.asStateFlow()

    init {
        viewModelScope.launch { _backgroundSync.value = app.extrasPrefs.backgroundSync() }
    }

    fun setBackgroundSync(on: Boolean) = viewModelScope.launch {
        app.extrasPrefs.setBackgroundSync(on)
        _backgroundSync.value = on
        if (on && manager.state.value.signedIn) SyncScheduler.scheduleBackground(app) else if (!on) SyncScheduler.cancelBackground(app)
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
                SyncViewModel(app, app.sync)
            }
        }
    }
}
