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
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListState(
    val shown: List<NoteEntity> = emptyList(),
    val total: Int = 0,                     // live notes, any filter
    val completedCount: Int = 0,            // live completed notes, any filter
    val counts: Map<NoteFilter, Int> = emptyMap(),   // how many notes each filter shows
    val filter: NoteFilter = NoteFilter.OPEN,
    val sort: SortField = SortField.OWN,
    val desc: Boolean = false,
    val starredFirst: Boolean = false,
    val startOfToday: Long = NoteQuery.startOfToday(),
    val loaded: Boolean = false,
    // notes the cloud no longer has (deleted on another device), shown with a warning
    val missing: Set<String> = emptySet(),
    // rows that were just swiped: id -> true = completed, false = reopened. They show "Marked as done" and Undo.
    val ghosts: Map<String, Boolean> = emptyMap(),
) {
    val sorted: Boolean get() = sort != SortField.OWN
    // a forced order of any kind: dragging to reorder is off
    val dragLocked: Boolean get() = sorted || starredFirst
    val sortText: String get() = if (sorted) "${sort.label}: ${if (desc) sort.descending else sort.ascending}" else sort.label
}

class NotesViewModel(
    private val repo: NotesRepository,
    missing: Flow<Set<String>>,
    // how long a swiped row stays as a ghost with its Undo
    private val ghostMillis: Long = GHOST_MILLIS,
) : ViewModel() {
    private val filter = MutableStateFlow(NoteFilter.OPEN)
    private val recent = MutableStateFlow<Map<String, RecentChange>>(emptyMap())
    private val ghostTimers = HashMap<String, Job>()
    private val sort = MutableStateFlow(SortField.OWN to false)
    private val starredFirst = MutableStateFlow(false)

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

    private val base: Flow<ListState> = combine(repo.alive, filter, combine(sort, starredFirst) { so, sf -> so to sf }, today, missing) { all, f, (so, sf), today, gone ->
        val (s, d) = so
        ListState(
            shown = NoteQuery.shown(all, f, s, d, today, starredFirst = sf),
            total = all.size,
            completedCount = all.count { it.completed },
            counts = NoteFilter.entries.associateWith { fl -> all.count { NoteQuery.matches(it, fl, today) } },
            filter = f, sort = s, desc = d, starredFirst = sf, startOfToday = today, loaded = true, missing = gone,
        )
    }

    val list: StateFlow<ListState> = combine(base, recent) { state, changes ->
        state.copy(shown = RecentChanges.apply(state.shown, changes), ghosts = RecentChanges.ghosts(changes))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListState())

    init {
        // the sort is kept on this device (sync_state), like on desktop; the filter starts at Open
        viewModelScope.launch {
            val field = repo.getState(StateKeys.SORT_FIELD).toIntOrNull()
            if (field != null && field in SortField.entries.indices)
                sort.value = SortField.entries[field] to (repo.getState(StateKeys.SORT_DESC) == "1")
            starredFirst.value = repo.getState(StateKeys.STARRED_FIRST) == "1"
        }
    }

    fun setFilter(f: NoteFilter) {
        endGhosts()          // the ghosts belong to the list they were swiped in
        filter.value = f
    }

    fun setSort(field: SortField, desc: Boolean = field.defaultDesc) {
        sort.value = field to desc
        viewModelScope.launch {
            repo.setState(StateKeys.SORT_FIELD, field.ordinal.toString())
            repo.setState(StateKeys.SORT_DESC, if (desc) "1" else "0")
        }
    }

    fun setStarredFirst(on: Boolean) {
        starredFirst.value = on
        viewModelScope.launch { repo.setState(StateKeys.STARRED_FIRST, if (on) "1" else "0") }
    }

    fun observe(id: String) = repo.observe(id)
    suspend fun get(id: String) = repo.get(id)

    fun setCompleted(id: String, completed: Boolean) = viewModelScope.launch { repo.setCompleted(id, completed) }

    // A swipe on the row at [index] of the list: complete the note, or reopen it when it is completed. The note
    // is changed at once; its row stays as a ghost with an Undo for [ghostMillis].
    fun swipe(note: NoteEntity, index: Int) {
        val toCompleted = !note.completed
        recent.update { it + (note.id to RecentChange(note, toCompleted, index)) }
        ghostTimers.remove(note.id)?.cancel()
        ghostTimers[note.id] = viewModelScope.launch {
            delay(ghostMillis)
            ghostTimers.remove(note.id)
            recent.update { it - note.id }
        }
        viewModelScope.launch { repo.setCompleted(note.id, toCompleted) }
    }

    // Undo on a ghost: the note goes back to how it was, its row is a normal row again.
    fun undoSwipe(id: String) {
        val change = recent.value[id] ?: return
        ghostTimers.remove(id)?.cancel()
        recent.update { it - id }
        viewModelScope.launch { repo.setCompleted(id, change.note.completed) }
    }

    private fun endGhosts() {
        ghostTimers.values.forEach { it.cancel() }
        ghostTimers.clear()
        recent.value = emptyMap()
    }
    fun setStarred(id: String, starred: Boolean) = viewModelScope.launch { repo.setStarred(id, starred) }
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
        const val GHOST_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ChaosApp
                NotesViewModel(app.notes, app.sync.state.map { it.missing })
            }
        }
    }
}
