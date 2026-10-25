package com.local.assistant.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class PhotoPeriodTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    /** Sunday 25 October 2026, 3 pm. */
    private val now = ZonedDateTime.of(2026, 10, 25, 15, 0, 0, 0, zone)

    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun range(phrase: String) = PhotoPeriod.parse(phrase, now)

    private fun assertDays(phrase: String, from: Triple<Int, Int, Int>, until: Triple<Int, Int, Int>) {
        assertEquals(phrase, PhotoPeriod.Range(day(from.first, from.second, from.third), day(until.first, until.second, until.third)), range(phrase))
    }

    @Test
    fun `days`() {
        assertDays("today", Triple(2026, 10, 25), Triple(2026, 10, 26))
        assertDays("yesterday", Triple(2026, 10, 24), Triple(2026, 10, 25))
        assertDays("3 days ago", Triple(2026, 10, 22), Triple(2026, 10, 23))
        assertDays("last sunday", Triple(2026, 10, 18), Triple(2026, 10, 19))
        assertDays("on friday", Triple(2026, 10, 23), Triple(2026, 10, 24))
        assertDays("25 dec 2023", Triple(2023, 12, 25), Triple(2023, 12, 26))
        assertDays("December 25th", Triple(2025, 12, 25), Triple(2025, 12, 26))
        assertDays("on 2 October", Triple(2026, 10, 2), Triple(2026, 10, 3))
    }

    @Test
    fun `weeks months and years`() {
        assertDays("this week", Triple(2026, 10, 19), Triple(2026, 10, 26))
        assertDays("last week", Triple(2026, 10, 12), Triple(2026, 10, 19))
        assertDays("this month", Triple(2026, 10, 1), Triple(2026, 11, 1))
        assertDays("last month", Triple(2026, 9, 1), Triple(2026, 10, 1))
        assertDays("this year", Triple(2026, 1, 1), Triple(2027, 1, 1))
        assertDays("last year", Triple(2025, 1, 1), Triple(2026, 1, 1))
        assertDays("2 years ago", Triple(2024, 1, 1), Triple(2025, 1, 1))
        assertDays("a month ago", Triple(2026, 9, 1), Triple(2026, 10, 1))
        assertDays("in 2023", Triple(2023, 1, 1), Triple(2024, 1, 1))
    }

    @Test
    fun `a month without a year is the most recent one begun`() {
        assertDays("last December", Triple(2025, 12, 1), Triple(2026, 1, 1))
        assertDays("from last december", Triple(2025, 12, 1), Triple(2026, 1, 1))
        assertDays("december", Triple(2025, 12, 1), Triple(2026, 1, 1))
        assertDays("march", Triple(2026, 3, 1), Triple(2026, 4, 1))
        assertDays("october", Triple(2026, 10, 1), Triple(2026, 11, 1))
        assertDays("last october", Triple(2025, 10, 1), Triple(2025, 11, 1))
        assertDays("March 2024", Triple(2024, 3, 1), Triple(2024, 4, 1))
        assertDays("sept 2023", Triple(2023, 9, 1), Triple(2023, 10, 1))
    }

    @Test
    fun `stretches up to now`() {
        val nowMs = now.toInstant().toEpochMilli() + 1
        assertEquals(PhotoPeriod.Range(day(2026, 9, 26), nowMs), range("last 30 days"))
        assertEquals(PhotoPeriod.Range(day(2026, 10, 11), nowMs), range("past 2 weeks"))
        assertEquals(PhotoPeriod.Range(day(2023, 1, 1), nowMs), range("since 2023"))
        assertEquals(PhotoPeriod.Range(day(2026, 3, 1), nowMs), range("since march"))
    }

    @Test
    fun `nothing about time is no period`() {
        assertNull(range("beach"))
        assertNull(range("with shiyas"))
        assertNull(range(""))
    }

    @Test
    fun `time words come out of a query, the rest stays`() {
        assertEquals("beach", PhotoPeriod.withoutTime("beach 2023"))
        assertEquals("beach", PhotoPeriod.withoutTime("beach last december"))
        assertEquals("", PhotoPeriod.withoutTime("from last year"))
        assertEquals("birthday cake", PhotoPeriod.withoutTime("birthday cake 25th march"))
    }
}
