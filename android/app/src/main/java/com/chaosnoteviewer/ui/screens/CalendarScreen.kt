package com.chaosnoteviewer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NoteQuery
import com.chaosnoteviewer.ui.AppIcon
import com.chaosnoteviewer.ui.CalendarModel
import com.chaosnoteviewer.ui.NotesViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

// Calendar of deadlines: a month or week grid with dots for the notes due each day, and below it the notes of
// the selected day. Same as the desktop calendar page.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    vm: NotesViewModel,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onEditNote: (String) -> Unit,
    onNewNote: (dueDate: Long) -> Unit,
) {
    val (notes, ghosts) = vm.calendar.collectAsStateWithLifecycle().value
    // starts on today from the system date, never from data that may not be loaded yet
    var selectedDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var weekMode by rememberSaveable { mutableStateOf(false) }
    val selected = LocalDate.ofEpochDay(selectedDay)
    val today = LocalDate.now()
    val firstDay = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val byDay = remember(notes) { CalendarModel.byDay(notes) }

    // a swipe here belongs to this screen; leaving it ends the Undo time of its swipes
    DisposableEffect(Unit) {
        vm.endGhosts()
        onDispose { vm.endGhosts() }
    }

    fun step(by: Long) {
        selectedDay = (if (weekMode) selected.plusWeeks(by) else selected.plusMonths(by)).toEpochDay()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar") },
                navigationIcon = { BackButton(onBack) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                onNewNote(selected.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }) { AppIcon("plus", "New note due on this day") }
        },
    ) { padding ->
        val dayNotes = CalendarModel.dayNotes(byDay[selected].orEmpty())
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                            SegmentedButton(
                                selected = !weekMode,
                                onClick = { weekMode = false },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                            ) { Text("Month") }
                            SegmentedButton(
                                selected = weekMode,
                                onClick = { weekMode = true },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                            ) { Text("Week") }
                        }
                        TextButton(onClick = { selectedDay = LocalDate.now().toEpochDay() }) { Text("Today") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { step(-1) }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                        Text(
                            if (weekMode) CalendarModel.weekTitle(CalendarModel.weekStart(selected, firstDay))
                            else CalendarModel.monthTitle(YearMonth.from(selected)),
                            Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = { step(1) }) { Text("›", style = MaterialTheme.typography.headlineSmall) }
                    }
                    Grid(
                        days = if (weekMode) CalendarModel.weekGrid(selected, firstDay)
                        else CalendarModel.monthGrid(YearMonth.from(selected), firstDay),
                        weekMode = weekMode,
                        month = YearMonth.from(selected),
                        selected = selected,
                        today = today,
                        firstDay = firstDay,
                        byDay = byDay,
                        onSelect = { selectedDay = it.toEpochDay() },
                        onStep = ::step,
                    )
                    Text(
                        CalendarModel.longDate(selected),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                HorizontalDivider()
            }
            if (dayNotes.isEmpty())
                item(key = "empty") {
                    Text(
                        "No deadlines on this day.\nPress + to add a note due on it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            itemsIndexed(dayNotes, key = { _, note -> note.id }) { index, note ->
                DayRow(note, index, ghosts[note.id], vm, onOpenNote, onEditNote)
            }
        }
    }
}

@Composable
private fun DayRow(
    note: NoteEntity,
    index: Int,
    ghost: Boolean?,
    vm: NotesViewModel,
    onOpenNote: (String) -> Unit,
    onEditNote: (String) -> Unit,
) {
    if (ghost != null) {
        GhostRow(note = note, completed = ghost, onUndo = { vm.undoSwipe(note.id) })
    } else {
        SwipeRow(completed = note.completed, enabled = true, onSwiped = { vm.swipe(note, index) }) {
            NoteRow(
                note = note,
                startOfToday = NoteQuery.startOfToday(),
                missing = false,
                onOpen = { onOpenNote(note.id) },
                onStar = { vm.setStarred(note.id, it) },
                handle = { IconButton(onClick = { onEditNote(note.id) }) { AppIcon("pencil", "Edit") } },
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun Grid(
    days: List<LocalDate>,
    weekMode: Boolean,
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    firstDay: java.time.DayOfWeek,
    byDay: Map<LocalDate, List<NoteEntity>>,
    onSelect: (LocalDate) -> Unit,
    onStep: (Long) -> Unit,
) {
    val swipeMin = with(LocalDensity.current) { 64.dp.toPx() }
    Column(
        Modifier.pointerInput(weekMode, month, selected) {
            // a horizontal swipe on the grid steps to the next / previous month or week
            var dx = 0f
            detectHorizontalDragGestures(
                onDragStart = { dx = 0f },
                onDragEnd = { if (dx <= -swipeMin) onStep(1) else if (dx >= swipeMin) onStep(-1) },
            ) { _, amount -> dx += amount }
        },
    ) {
        Row {
            for (d in CalendarModel.weekDays(firstDay))
                Text(
                    d.getDisplayName(TextStyle.SHORT_STANDALONE, Locale.getDefault()),
                    Modifier.weight(1f).padding(vertical = 4.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        for (week in days.chunked(7))
            Row {
                for (day in week)
                    DayCell(
                        day = day,
                        notes = byDay[day].orEmpty(),
                        weekMode = weekMode,
                        outside = !weekMode && YearMonth.from(day) != month,
                        isSelected = day == selected,
                        isToday = day == today,
                        today = today,
                        onClick = { onSelect(day) },
                        modifier = Modifier.weight(1f),
                    )
            }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    notes: List<NoteEntity>,
    weekMode: Boolean,
    outside: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    today: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    var cell = modifier.padding(2.dp)
    cell = if (weekMode) cell.height(88.dp) else cell.aspectRatio(1f)
    cell = cell.background(if (isSelected) colors.primaryContainer else colors.surface, shape)
    if (isToday)
        cell = cell.border(1.5.dp, colors.primary, shape)
    val open = notes.count { !it.completed }
    Column(
        cell.clickable(onClick = onClick).alpha(if (outside) 0.4f else 1f).padding(top = 4.dp)
            .testTag("day-$day"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            day.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (open > 0) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (dot in CalendarModel.dots(day, notes, today))
                Box(
                    Modifier.size(5.dp).background(
                        when (dot) {
                            CalendarModel.Dot.OPEN -> colors.primary
                            CalendarModel.Dot.LATE -> colors.error
                            CalendarModel.Dot.DONE -> colors.onSurface.copy(alpha = 0.3f)
                        },
                        CircleShape,
                    ),
                )
        }
        if (weekMode && notes.isNotEmpty())
            Text(
                "${notes.size} due",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
    }
}
