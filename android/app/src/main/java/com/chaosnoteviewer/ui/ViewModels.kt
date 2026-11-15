package com.chaosnoteviewer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.chaosnoteviewer.ChaosApp
import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NoteFilter
import com.chaosnoteviewer.data.NoteQuery
import com.chaosnoteviewer.data.NotesRepository
import com.chaosnoteviewer.data.SortField
import com.chaosnoteviewer.data.StateKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ListState(
    val shown: List<NoteEntity> = emptyList(),
    val total: Int = 0,                     // live notes, any filter
    val completedCount: Int = 0,            // live completed notes, any filter
    val counts: Map<NoteFilter, Int> = emptyMap(),   // how many notes each filter shows
    val filter: NoteFilter = NoteFilter.OPEN,
    val sort: SortField = SortField.OWN,
    val desc: Boolean = false,
    val startOfToday: Long = NoteQuery.startOfToday(),
    val loaded: Boolean = false,
    // notes the cloud no longer has (deleted on another device), shown with a warning
    val missing: Set<String> = emptySet(),
) {
    val sorted: Boolean get() = sort != SortField.OWN
    val sortText: String get() = if (sorted) "${sort.label}: ${if (desc) sort.descending else sort.ascending}" else sort.label
}

class NotesViewModel(private val repo: NotesRepository, missing: Flow<Set<String>>) : ViewModel() {
    private val filter = MutableStateFlow(NoteFilter.OPEN)
    private val sort = MutableStateFlow(SortField.OWN to false)

    // local midnight of today, emitted again right after each midnight (overdue / Important depend on it)
    private val today = flow {
        while (true) {
            val start = NoteQuery.startOfToday()
            emit(start)
            val nextMidnight = NoteQuery.startOfToday(java.time.ZoneId.systemDefault()).let {
                java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).plusDays(1).toInstant().toEpochMilli()
            }
            delay((nextMidnight - System.currentTimeMillis()).coerceAtLeast(1_000) + 1_000)
        }
    }.distinctUntilChanged()

    val list: StateFlow<ListState> = combine(repo.alive, filter, sort, today, missing) { all, f, (s, d), today, gone ->
        ListState(
            shown = NoteQuery.shown(all, f, s, d, today),
            total = all.size,
            completedCount = all.count { it.completed },
            counts = NoteFilter.entries.associateWith { fl -> all.count { NoteQuery.matches(it, fl, today) } },
            filter = f, sort = s, desc = d, startOfToday = today, loaded = true, missing = gone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState())

    init {
        // the sort is kept on this device (sync_state), like on desktop; the filter starts at Open
        viewModelScope.launch {
            val field = repo.getState(StateKeys.SORT_FIELD).toIntOrNull()
            if (field != null && field in SortField.entries.indices)
                sort.value = SortField.entries[field] to (repo.getState(StateKeys.SORT_DESC) == "1")
        }
    }

    fun setFilter(f: NoteFilter) { filter.value = f }

    fun setSort(field: SortField, desc: Boolean = field.defaultDesc) {
        sort.value = field to desc
        viewModelScope.launch {
            repo.setState(StateKeys.SORT_FIELD, field.ordinal.toString())
            repo.setState(StateKeys.SORT_DESC, if (desc) "1" else "0")
        }
    }

    fun observe(id: String) = repo.observe(id)
    suspend fun get(id: String) = repo.get(id)

    fun setCompleted(id: String, completed: Boolean) = viewModelScope.launch { repo.setCompleted(id, completed) }
    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun deleteCompleted() = viewModelScope.launch { repo.deleteCompleted() }

    // Drag result: the note shown at row [from] of [visible] was dropped at row [to].
    fun move(visible: List<NoteEntity>, from: Int, to: Int) = viewModelScope.launch { repo.move(visible, from, to) }

    // id == null: new note. dueDate: local midnight, null = none.
    fun save(id: String?, title: String, description: String, dueDate: Long?) = viewModelScope.launch {
        if (id == null) repo.add(title, description, dueDate)
        else repo.update(id, title, description, dueDate)
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ChaosApp
                NotesViewModel(app.notes, app.sync.state.map { it.missing })
            }
        }
    }
}
