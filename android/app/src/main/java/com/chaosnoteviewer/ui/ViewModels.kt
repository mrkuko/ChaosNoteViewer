package com.chaosnoteviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.chaosnoteviewer.ChaosApp
import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NotesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(private val repo: NotesRepository) : ViewModel() {
    val notes: StateFlow<List<NoteEntity>> =
        repo.alive.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observe(id: String) = repo.observe(id)

    fun setCompleted(id: String, completed: Boolean) = viewModelScope.launch { repo.setCompleted(id, completed) }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }

    // id == null: new note
    fun save(id: String?, title: String, description: String) = viewModelScope.launch {
        if (id == null)
            repo.add(title, description)
        else {
            val old = repo.get(id) ?: return@launch
            repo.update(id, title, description, old.dueDate)
        }
    }

    suspend fun get(id: String) = repo.get(id)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { NotesViewModel((this[APPLICATION_KEY] as ChaosApp).notes) }
        }
    }
}
