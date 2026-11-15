package com.chaosnoteviewer.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// due_date is local midnight of the deadline day; Material's DatePicker works in UTC midnight.
object Dates {
    fun pickerToDue(pickerMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(pickerMillis).atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun dueToPicker(dueDate: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(dueDate).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun format(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    fun localDate(millis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
