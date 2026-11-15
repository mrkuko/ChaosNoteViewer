package com.chaosnoteviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class DatesTest {
    private val zones = listOf("Europe/Bratislava", "America/Los_Angeles", "Pacific/Auckland", "UTC").map(ZoneId::of)
    private val day = LocalDate.of(2026, 3, 29)     // DST change in Europe

    @Test fun pickerDayBecomesLocalMidnightOfTheSameDay() {
        val picker = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        for (zone in zones) {
            val due = Dates.pickerToDue(picker, zone)
            assertEquals(day.atStartOfDay(zone).toInstant().toEpochMilli(), due)
            assertEquals(day, Dates.localDate(due, zone))
        }
    }

    @Test fun roundTrip() {
        for (zone in zones) {
            val due = day.atStartOfDay(zone).toInstant().toEpochMilli()
            assertEquals(due, Dates.pickerToDue(Dates.dueToPicker(due, zone), zone))
        }
    }
}
