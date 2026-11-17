package com.chaosnoteviewer.ui.screens

import com.chaosnoteviewer.ui.AppIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
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
                        icon = { AppIcon(f.iconName) },
                        label = { Text(f.menuLabel) },
                        badge = { Text((state.counts[f] ?: 0).toString()) },
                        selected = state.filter == f,
                        onClick = { vm.setFilter(f); scope.launch { drawer.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                Spacer(Modifier.weight(1f))
                HorizontalDivider(Modifier.padding(horizontal = 28.dp))
                NavigationDrawerItem(
                    icon = { AppIcon("gear") },
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
                            AppIcon("menu", "Menu")
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
                                    AppIcon(
                                        "sync-arrows",
                                        "Sync",
                                        tint = if (sync.syncError.isNotEmpty() || sync.phase != KeyPhase.Ready) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                            }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                AppIcon("sort", "Sort")
                            }
                            SortMenu(state, expanded = sortMenu, onDismiss = { sortMenu = false }, onSort = vm::setSort)
                        }
                        // desktop shows this button only in the Completed list
                        if (state.filter == NoteFilter.COMPLETED && state.completedCount > 0)
                            Box {
                                IconButton(onClick = { moreMenu = true }) {
                                    AppIcon("more-vertical", "More")
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
                    AppIcon("plus", "New note")
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
                            trailingIcon = { AppIcon("x-circle", "Back to your order", size = 18.dp) },
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

private val NoteFilter.iconName: String
    get() = when (this) {
        NoteFilter.OPEN -> "checkbox"
        NoteFilter.COMPLETED -> "check-circle"
        NoteFilter.ALL -> "list"
        NoteFilter.IMPORTANT -> "exclamation"
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
                leadingIcon = { if (current) AppIcon("check") },
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
        itemsIndexed(rows, key = { _, note -> note.id }) { index, note ->
            val ghost = state.ghosts[note.id]
            if (ghost != null) {
                // just swiped: the row stays here for a few seconds, with a way back
                GhostRow(
                    note = note,
                    completed = ghost,
                    onUndo = { vm.undoSwipe(note.id) },
                    modifier = Modifier.animateItem(),
                )
                HorizontalDivider(Modifier.animateItem())
                return@itemsIndexed
            }
            ReorderableItem(reorder, key = note.id, enabled = !state.sorted) { isDragging ->
                SwipeRow(
                    completed = note.completed,
                    enabled = !isDragging,
                    onSwiped = { vm.swipe(note, index) },
                ) {
                    Surface(tonalElevation = if (isDragging) 6.dp else 0.dp, shadowElevation = if (isDragging) 6.dp else 0.dp) {
                        NoteRow(
                            note = note,
                            startOfToday = state.startOfToday,
                            missing = note.id in state.missing,
                            onOpen = { onOpenNote(note.id) },
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
                                        AppIcon("menu", "Drag to reorder")
                                    }
                                }
                            },
                        )
                    }
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
    handle: (@Composable () -> Unit)?,
) {
    val link = MaterialTheme.colorScheme.primary
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen).alpha(if (note.completed) 0.55f else 1f),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
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
                            AppIcon("exclamation", tint = MaterialTheme.colorScheme.error, size = 16.dp)
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

// A note is completed (or, in the Completed list, reopened) by pulling its row to the left, never by a tap, which
// is too easy to do by accident. Past 40 % of the width the swipe counts, the row then turns into a GhostRow.
// Only to the left: the navigation drawer opens by a drag to the right anywhere on the screen, so a swipe in
// that direction would pull the menu out as well.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(completed: Boolean, enabled: Boolean, onSwiped: () -> Unit, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    // the swipe state is created once per row and keeps the lambda it was given then; the row moves when notes
    // above it come and go, so the current onSwiped (with the current index) has to be looked up at the swipe
    val currentOnSwiped by rememberUpdatedState(onSwiped)
    val state = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.4f },
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) false
            else {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnSwiped()
                true
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            val dir = state.dismissDirection
            if (dir != SwipeToDismissBoxValue.Settled)
                Box(
                    Modifier.fillMaxSize()
                        .background(if (completed) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(if (completed) "undo" else "check-double")
                        Text(
                            if (completed) "  Back to open" else "  Done",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
        },
    ) { content() }
}

// What a swiped row turns into, in the same place: it says what happened and offers to take it back.
// After a few seconds it collapses away (the view model drops it).
@Composable
private fun GhostRow(note: NoteEntity, completed: Boolean, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (completed) "Marked as done" else "Moved back to open notes",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                note.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = if (completed) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // looks like a link, not like a filled button
        Text(
            "Undo",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable(role = Role.Button, onClick = onUndo).padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}
