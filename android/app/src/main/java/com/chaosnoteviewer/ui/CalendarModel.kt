package com.chaosnoteviewer.ui

import com.chaosnoteviewer.data.NoteEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// The calendar of deadlines, as plain data: the days of a month or week grid, which notes are due on a day and
// the dots under a day number. No Compose here, so it can be unit tested.
object CalendarModel {
    const val MAX_DOTS = 3

    enum class Dot { OPEN, LATE, DONE }

    // the first day of the week [date] is in, for a week that starts on [firstDay]
    fun weekStart(date: LocalDate, firstDay: DayOfWeek): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(firstDay))

    // always six weeks (42 days), starting on the week day [firstDay] on or before the 1st of [month]
    fun monthGrid(month: YearMonth, firstDay: DayOfWeek): List<LocalDate> {
        val start = weekStart(month.atDay(1), firstDay)
        return List(42) { start.plusDays(it.toLong()) }
    }

    fun weekGrid(date: LocalDate, firstDay: DayOfWeek): List<LocalDate> {
        val start = weekStart(date, firstDay)
        return List(7) { start.plusDays(it.toLong()) }
    }

    // the seven week days in grid order
    fun weekDays(firstDay: DayOfWeek): List<DayOfWeek> = List(7) { firstDay.plus(it.toLong()) }

    // notes with a deadline grouped by their deadline day (due_date is local midnight)
    fun byDay(notes: List<NoteEntity>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, List<NoteEntity>> =
        notes.filter { it.dueDate != null }.groupBy { Dates.localDate(it.dueDate!!, zone) }

    // the notes of one day: open ones first, each part in your own order
    fun dayNotes(notes: List<NoteEntity>): List<NoteEntity> =
        notes.sortedWith(compareBy<NoteEntity> { it.completed }.thenBy { it.position })

    // one dot per note, up to MAX_DOTS, in the order of dayNotes: accent for open, red for an open note whose
    // day has passed, faint for completed
    fun dots(day: LocalDate, notes: List<NoteEntity>, today: LocalDate): List<Dot> =
        dayNotes(notes).take(MAX_DOTS).map {
            when {
                it.completed -> Dot.DONE
                day.isBefore(today) -> Dot.LATE
                else -> Dot.OPEN
            }
        }

    // "November 2026"
    fun monthTitle(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } +
            " " + month.year

    // "16 Nov – 22 Nov 2026"; across a year: "28 Dec 2026 – 3 Jan 2027"
    fun weekTitle(start: LocalDate, locale: Locale = Locale.getDefault()): String {
        val end = start.plusDays(6)
        val day = DateTimeFormatter.ofPattern("d MMM", locale)
        val full = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        return if (start.year == end.year) "${start.format(day)} – ${end.format(full)}"
        else "${start.format(full)} – ${end.format(full)}"
    }

    // "Wednesday, 18 November 2026" in the locale's own long form
    fun longDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale))
}
