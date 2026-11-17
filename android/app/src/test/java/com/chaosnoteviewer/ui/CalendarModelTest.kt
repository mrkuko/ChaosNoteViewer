package com.chaosnoteviewer.ui

import com.chaosnoteviewer.data.NoteEntity
import com.chaosnoteviewer.ui.CalendarModel.Dot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale

class CalendarModelTest {
    private val zone = ZoneOffset.UTC

    private fun note(id: String, day: LocalDate?, position: Double = 0.0, completed: Boolean = false) =
        NoteEntity(id = id, title = id, position = position, completed = completed, createdAt = 0, updatedAt = 0,
            dueDate = day?.atStartOfDay(zone)?.toInstant()?.toEpochMilli())

    // a month grid is always 42 days, starts on the first day of the week and contains the 1st and the last day
    @Test fun monthGridForEveryStartWeekdayAndBothFirstDays() {
        for (m in 1..12) for (firstDay in listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)) {
            val month = YearMonth.of(2026, m)
            val grid = CalendarModel.monthGrid(month, firstDay)
            assertEquals(42, grid.size)
            assertEquals(firstDay, grid.first().dayOfWeek)
            assertTrue(month.atDay(1) in grid.take(7))
            assertTrue(month.atEndOfMonth() in grid)
            assertEquals(grid.first().plusDays(41), grid.last())
        }
    }

    // November 2026 starts on a Sunday
    @Test fun monthStartingOnTheFirstDayOfTheWeekHasNoDaysBefore() {
        assertEquals(LocalDate.of(2026, 11, 1), CalendarModel.monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY).first())
        assertEquals(LocalDate.of(2026, 10, 26), CalendarModel.monthGrid(YearMonth.of(2026, 11), DayOfWeek.MONDAY).first())
    }

    @Test fun weekAcrossAMonthAndAYear() {
        val w = CalendarModel.weekGrid(LocalDate.of(2026, 12, 30), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 12, 28), w.first())
        assertEquals(LocalDate.of(2027, 1, 3), w.last())
        assertEquals("28 Dec 2026 – 3 Jan 2027", CalendarModel.weekTitle(w.first(), Locale.UK))
        val n = CalendarModel.weekGrid(LocalDate.of(2026, 11, 18), DayOfWeek.MONDAY)
        assertEquals("16 Nov – 22 Nov 2026", CalendarModel.weekTitle(n.first(), Locale.UK))
        assertEquals(LocalDate.of(2026, 11, 1), CalendarModel.weekGrid(LocalDate.of(2026, 11, 1), DayOfWeek.SUNDAY).first())
    }

    @Test fun weekDaysInGridOrder() =
        assertEquals(DayOfWeek.SATURDAY, CalendarModel.weekDays(DayOfWeek.SUNDAY).last())

    @Test fun monthTitle() = assertEquals("November 2026", CalendarModel.monthTitle(YearMonth.of(2026, 11), Locale.UK))

    @Test fun groupsByDayAndOrdersOpenFirst() {
        val d = LocalDate.of(2026, 11, 18)
        val notes = listOf(note("done", d, 1.0, completed = true), note("b", d, 3.0), note("a", d, 2.0),
            note("none", null), note("other", d.plusDays(1)))
        val byDay = CalendarModel.byDay(notes, zone)
        assertEquals(setOf(d, d.plusDays(1)), byDay.keys)
        assertEquals(listOf("a", "b", "done"), CalendarModel.dayNotes(byDay.getValue(d)).map { it.id })
    }

    // up to three dots; red for an open note whose day has passed, faint for completed
    @Test fun dots() {
        val today = LocalDate.of(2026, 11, 18)
        val past = today.minusDays(1)
        val five = List(5) { note("n$it", today, it.toDouble()) }
        assertEquals(List(3) { Dot.OPEN }, CalendarModel.dots(today, five, today))
        assertEquals(listOf(Dot.LATE, Dot.DONE),
            CalendarModel.dots(past, listOf(note("d", past, 1.0, completed = true), note("o", past, 2.0)), today))
        assertEquals(emptyList<Dot>(), CalendarModel.dots(today, emptyList(), today))
    }
}
