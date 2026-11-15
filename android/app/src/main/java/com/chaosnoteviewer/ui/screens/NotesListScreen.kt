package com.chaosnoteviewer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NoteFilter
import com.chaosnoteviewer.data.NoteQuery
import com.chaosnoteviewer.data.SortField
import com.chaosnoteviewer.ui.ListState
import com.chaosnoteviewer.ui.NotesViewModel
import com.chaosnoteviewer.ui.SyncViewModel
import com.chaosnoteviewer.sync.KeyPhase
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import com.chaosnoteviewer.ui.linkify
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(vm: NotesViewModel, syncVm: SyncViewModel, onOpenNote: (String) -> Unit, onNewNote: () -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.list.collectAsStateWithLifecycle()
    val sync by syncVm.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var confirmDeleteCompleted by remember { mutableStateOf(false) }

    // Back closes the open drawer first
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    // Side navigation, pulled out from the left edge or with the menu button: which notes the list
    // shows, settings at the bottom (same structure as the desktop app).
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Chaos Note Viewer",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                )
                for (f in NoteFilter.entries)
                    NavigationDrawerItem(
                        icon = { Icon(f.icon, contentDescription = null) },
                        label = { Text(f.menuLabel) },
                        badge = { Text((state.counts[f] ?: 0).toString()) },
                        selected = state.filter == f,
                        onClick = { vm.setFilter(f); scope.launch { drawer.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                Spacer(Modifier.weight(1f))
                HorizontalDivider(Modifier.padding(horizontal = 28.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenSettings() },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.filter.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        // sync: only when signed in; while the key is not ready it leads to the key in Settings
                        if (sync.signedIn)
                            IconButton(
                                onClick = { if (sync.phase == KeyPhase.Ready) syncVm.sync() else onOpenSettings() },
                                enabled = !sync.syncing,
                            ) {
                                if (sync.syncing)
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Sync",
                                        tint = if (sync.syncError.isNotEmpty() || sync.phase != KeyPhase.Ready) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                            }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Sort")
                            }
                            SortMenu(state, expanded = sortMenu, onDismiss = { sortMenu = false }, onSort = vm::setSort)
                        }
                        // desktop shows this button only in the Completed list
                        if (state.filter == NoteFilter.COMPLETED && state.completedCount > 0)
                            Box {
                                IconButton(onClick = { moreMenu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Delete all completed (${state.completedCount})") },
                                        onClick = { moreMenu = false; confirmDeleteCompleted = true },
                                    )
                                }
                            }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onNewNote) {
                    Icon(Icons.Filled.Add, contentDescription = "New note")
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                // a forced order is on: show it, a tap goes back to your own order
                if (state.sorted)
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        AssistChip(
                            onClick = { vm.setSort(SortField.OWN, false) },
                            label = { Text(state.sortText) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Back to your order") },
                        )
                    }
                if (state.loaded && state.shown.isEmpty())
                    EmptyText(state)
                else
                    NotesList(state, vm, onOpenNote)
            }
        }
    }

    if (confirmDeleteCompleted) {
        val n = state.completedCount
        ConfirmDialog(
            title = "Delete completed notes?",
            text = if (n == 1) "1 completed note will be deleted." else "$n completed notes will be deleted.",
            confirm = "Delete",
            onConfirm = { vm.deleteCompleted() },
            onDismiss = { confirmDeleteCompleted = false },
        )
    }
}

private val NoteFilter.title: String
    get() = when (this) {
        NoteFilter.OPEN -> "Open notes"
        NoteFilter.COMPLETED -> "Completed"
        NoteFilter.ALL -> "All notes"
        NoteFilter.IMPORTANT -> "Important"
    }

private val NoteFilter.menuLabel: String
    get() = when (this) {
        NoteFilter.IMPORTANT -> "Important"
        else -> label
    }

private val NoteFilter.icon: ImageVector
    get() = when (this) {
        NoteFilter.OPEN -> Icons.Outlined.CheckCircle
        NoteFilter.COMPLETED -> Icons.Filled.CheckCircle
        NoteFilter.ALL -> Icons.AutoMirrored.Filled.List
        NoteFilter.IMPORTANT -> Icons.Filled.Warning
    }

// Fields; picking the current forced field again flips its direction.
@Composable
private fun SortMenu(state: ListState, expanded: Boolean, onDismiss: () -> Unit, onSort: (SortField, Boolean) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        for (field in SortField.entries) {
            val current = state.sort == field
            DropdownMenuItem(
                text = {
                    Column {
                        Text(field.label)
                        if (current && field != SortField.OWN)
                            Text(
                                (if (state.desc) field.descending else field.ascending) + " · tap to flip",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                },
                leadingIcon = { if (current) Icon(Icons.Filled.Check, contentDescription = null) },
                onClick = {
                    if (current && field != SortField.OWN) onSort(field, !state.desc)
                    else onSort(field, field.defaultDesc)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun EmptyText(state: ListState) {
    val text = when {
        state.total == 0 -> "No notes yet.\nPress + to add one."
        state.filter == NoteFilter.IMPORTANT ->
            "Nothing urgent.\nOpen notes that are overdue or due in the next ${NoteFilter.IMPORTANT_DAYS} days show up here."
        else -> "Nothing here"
    }
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Drag the handle to reorder (your own order only). The rows move in a local copy while dragging;
// on release the one moved note gets its new position in the database.
@Composable
private fun NotesList(state: ListState, vm: NotesViewModel, onOpenNote: (String) -> Unit) {
    var rows by remember { mutableStateOf(state.shown) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragSnapshot by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }

    LaunchedEffect(state.shown) {
        if (dragging == null) rows = state.shown
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        rows = rows.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.id }) { note ->
            ReorderableItem(reorder, key = note.id, enabled = !state.sorted) { isDragging ->
                Surface(tonalElevation = if (isDragging) 6.dp else 0.dp, shadowElevation = if (isDragging) 6.dp else 0.dp) {
                    NoteRow(
                        note = note,
                        startOfToday = state.startOfToday,
                        missing = note.id in state.missing,
                        onOpen = { onOpenNote(note.id) },
                        onCompleted = { vm.setCompleted(note.id, it) },
                        handle = if (state.sorted) null else {
                            {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(
                                        onDragStarted = {
                                            dragging = note.id
                                            dragSnapshot = state.shown
                                        },
                                        onDragStopped = {
                                            val from = dragSnapshot.indexOfFirst { it.id == note.id }
                                            val to = rows.indexOfFirst { it.id == note.id }
                                            if (from >= 0 && to >= 0 && from != to)
                                                vm.move(dragSnapshot, from, to)
                                            dragging = null
                                        },
                                    ),
                                ) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Drag to reorder")
                                }
                            }
                        },
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun NoteRow(
    note: NoteEntity,
    startOfToday: Long,
    missing: Boolean,
    onOpen: () -> Unit,
    onCompleted: (Boolean) -> Unit,
    handle: (@Composable () -> Unit)?,
) {
    val link = MaterialTheme.colorScheme.primary
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen).alpha(if (note.completed) 0.55f else 1f),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        leadingContent = { Checkbox(checked = note.completed, onCheckedChange = onCompleted) },
        headlineContent = {
            Text(
                linkify(note.title, link),
                textDecoration = if (note.completed) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (note.description.isEmpty() && note.dueDate == null && !missing) null else {
            {
                Column {
                    if (missing)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Text(
                                " Not in the cloud any more",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    if (note.description.isNotEmpty())
                        Text(linkify(note.description, link), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (note.dueDate != null)
                        Box(Modifier.padding(top = 4.dp)) {
                            DueDate(note.dueDate, NoteQuery.isOverdue(note, startOfToday))
                        }
                }
            }
        },
        trailingContent = handle,
    )
}
