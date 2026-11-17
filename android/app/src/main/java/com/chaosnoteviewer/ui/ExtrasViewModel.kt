package com.chaosnoteviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chaosnoteviewer.ChaosApp
import com.chaosnoteviewer.extras.Extras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ExtrasState(val reminders: Boolean = false, val loaded: Boolean = false)

// The switch for the deadline reminders (Settings).
class ExtrasViewModel(private val app: ChaosApp) : ViewModel() {
    private val prefs = app.extrasPrefs
    private val _state = MutableStateFlow(ExtrasState())
    val state: StateFlow<ExtrasState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.value = ExtrasState(prefs.reminders(), loaded = true) }
    }

    fun setReminders(on: Boolean) = viewModelScope.launch {
        prefs.setReminders(on)
        _state.value = _state.value.copy(reminders = on)
        Extras.applySwitches(app, prefs, app.notes.alive.first())
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { ExtrasViewModel(this[APPLICATION_KEY] as ChaosApp) }
        }
    }
}
