package com.chaosnoteviewer.extras

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.chaosnoteviewer.ChaosApp
import com.chaosnoteviewer.LaunchRequest
import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.data.NoteQuery
import com.chaosnoteviewer.ui.Dates
import kotlinx.coroutines.flow.first

// The open notes on the home screen, in your own order. Tap a note to open it, the box to complete it, the title
// to open the app, + for a new note. Reads the local database (no sync needed) and is redrawn whenever the notes
// change (Extras.refresh).
class NotesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as ChaosApp
        val first = app.notes.alive.first()
        provideContent {
            // read inside the composition: a widget that is already showing must follow the notes, provideGlance
            // does not run again for it (only a recomposition does)
            val all by app.notes.alive.collectAsState(initial = first)
            GlanceTheme { WidgetContent(context, all.filter { !it.completed }, NoteQuery.startOfToday()) }
        }
    }
}

@Composable
private fun WidgetContent(context: Context, notes: List<NoteEntity>, today: Long) {
    Column(
        GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(8.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (notes.isEmpty()) "Open notes" else "Open notes · ${notes.size}",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp),
                modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(LaunchRequest(filter = com.chaosnoteviewer.data.NoteFilter.OPEN).toIntent(context))),
            )
            Text(
                "+",
                style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 24.sp),
                modifier = GlanceModifier.padding(horizontal = 12.dp).clickable(actionStartActivity(LaunchRequest(newNote = true).toIntent(context))),
            )
        }
        if (notes.isEmpty())
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nothing to do", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant))
            }
        else
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(notes, itemId = { it.id.hashCode().toLong() }) { note ->
                    Row(
                        GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CheckBox(
                            checked = false,
                            onCheckedChange = actionRunCallback<CompleteNoteAction>(actionParametersOf(CompleteNoteAction.NOTE_ID to note.id)),
                        )
                        Column(
                            GlanceModifier.defaultWeight().clickable(actionStartActivity(LaunchRequest(noteId = note.id).toIntent(context))),
                        ) {
                            Text(
                                note.title,
                                maxLines = 2,
                                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
                            )
                            note.dueDate?.let {
                                val overdue = NoteQuery.isOverdue(note, today)
                                Text(
                                    (if (overdue) "Overdue · " else "") + Dates.format(it),
                                    style = TextStyle(
                                        color = if (overdue) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant,
                                        fontSize = 12.sp,
                                    ),
                                )
                            }
                        }
                        Spacer(GlanceModifier.width(4.dp))
                    }
                }
            }
    }
}

// The box in the widget: marks the note as completed (like in the app: it is queued for the next sync).
class CompleteNoteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[NOTE_ID] ?: return
        (context.applicationContext as ChaosApp).notes.setCompleted(id, true)
        // the change makes the app-wide refresh redraw the widget
    }

    companion object {
        val NOTE_ID = ActionParameters.Key<String>("note_id")
    }
}

class NotesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotesWidget()
}
