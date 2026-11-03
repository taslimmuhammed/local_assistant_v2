package com.local.assistant.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class HistoryPeriodTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private val today = LocalDate.of(2026, 11, 3)

    private fun at(day: LocalDate, hour: Int = 12): Long = day.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun byCalendarDayNotHoursAgo() {
        // Just after midnight, a chat from late last night is yesterday's.
        assertEquals(HistoryPeriod.TODAY, HistoryPeriod.of(at(today, 0), today, zone))
        assertEquals(HistoryPeriod.YESTERDAY, HistoryPeriod.of(at(today.minusDays(1), 23), today, zone))
    }

    @Test
    fun furtherBack() {
        assertEquals(HistoryPeriod.WEEK, HistoryPeriod.of(at(today.minusDays(2)), today, zone))
        assertEquals(HistoryPeriod.WEEK, HistoryPeriod.of(at(today.minusDays(6)), today, zone))
        assertEquals(HistoryPeriod.MONTH, HistoryPeriod.of(at(today.minusDays(7)), today, zone))
        assertEquals(HistoryPeriod.OLDER, HistoryPeriod.of(at(today.minusDays(30)), today, zone))
    }

    @Test
    fun clockAheadCountsAsToday() {
        assertEquals(HistoryPeriod.TODAY, HistoryPeriod.of(at(today.plusDays(1)), today, zone))
    }
}
