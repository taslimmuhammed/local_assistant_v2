package com.local.assistant.memory.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WhenResolverTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    /** Monday 21 September 2026, 10:00 in Bengaluru. */
    private val now = ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, zone)
    private val resolver = WhenResolver()

    private fun at(month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(LocalDateTime.of(2026, month, day, hour, minute), zone)

    private fun check(phrase: String, expected: ZonedDateTime, dateOnly: Boolean = false) {
        val resolved = resolver.resolve(phrase, now)
        assertEquals("'$phrase'", expected, resolved?.at)
        assertEquals("'$phrase' dateOnly", dateOnly, resolved?.dateOnly)
    }

    @Test
    fun `the brief's own examples`() {
        check("tomorrow at 11", at(9, 22, 11))
        check("kal subah 11 baje", at(9, 22, 11))
    }

    @Test
    fun `bare hours read as a person would say them`() {
        check("at 11", at(9, 21, 11))
        check("at 3", at(9, 21, 15))
        check("at 12", at(9, 21, 12))
        check("5 o'clock", at(9, 21, 17))
    }

    @Test
    fun `a time that has already passed today means tomorrow`() {
        check("at 9", at(9, 22, 9))
        check("at 7", at(9, 22, 7))
        check("morning", at(9, 22, 9))
    }

    @Test
    fun `explicit am and pm, minutes and 24-hour times`() {
        check("11:30 pm", at(9, 21, 23, 30))
        check("11.30pm", at(9, 21, 23, 30))
        check("11am tomorrow", at(9, 22, 11))
        check("at 18:45", at(9, 21, 18, 45))
        check("noon", at(9, 21, 12))
        check("midnight", at(9, 22, 0))
    }

    @Test
    fun `parts of the day and their defaults`() {
        check("tonight", at(9, 21, 21))
        check("tonight at 10", at(9, 21, 22))
        check("this evening", at(9, 21, 18))
        check("afternoon", at(9, 21, 14))
        check("tomorrow morning", at(9, 22, 9))
    }

    @Test
    fun `hinglish`() {
        check("shaam 7 baje", at(9, 21, 19))
        check("raat 10 baje", at(9, 21, 22))
        check("raat 1 baje", at(9, 22, 1))
        check("parso shaam 6 baje", at(9, 23, 18))
        check("2 ghante mein", at(9, 21, 12))
        check("kal", at(9, 22, 8), dateOnly = true)
        check("ravivar", at(9, 27, 8), dateOnly = true)
        check("aaj dopahar 3 baje", at(9, 21, 15))
    }

    @Test
    fun `relative moments`() {
        check("in 2 hours", at(9, 21, 12))
        check("in 30 minutes", at(9, 21, 10, 30))
        check("in an hour", at(9, 21, 11))
        check("in half an hour", at(9, 21, 10, 30))
        check("in 3 days", at(9, 24, 8), dateOnly = true)
        check("in a week", at(9, 28, 8), dateOnly = true)
    }

    @Test
    fun `weekdays are the next one after today`() {
        check("on Friday", at(9, 25, 8), dateOnly = true)
        check("friday 5pm", at(9, 25, 17))
        check("next Monday", at(9, 28, 8), dateOnly = true)
        check("sunday", at(9, 27, 8), dateOnly = true)
        check("this monday", at(9, 21, 8), dateOnly = true)
    }

    @Test
    fun `days of the month and calendar dates`() {
        check("day after tomorrow", at(9, 23, 8), dateOnly = true)
        check("25th", at(9, 25, 8), dateOnly = true)
        check("on the 20th", at(10, 20, 8), dateOnly = true)
        check("25 Oct", at(10, 25, 8), dateOnly = true)
        check("Oct 25 at 5pm", at(10, 25, 17))
        check("2026-10-01", at(10, 1, 8), dateOnly = true)
        check("1/10", at(10, 1, 8), dateOnly = true)
        check("12 May", ZonedDateTime.of(LocalDateTime.of(2027, 5, 12, 8, 0), zone), dateOnly = true)
    }

    @Test
    fun `coarse periods`() {
        check("next week", at(9, 28, 8), dateOnly = true)
        check("next month", at(10, 1, 8), dateOnly = true)
        check("this weekend", at(9, 26, 8), dateOnly = true)
    }

    @Test
    fun `an explicit day in the past stays in the past, for the caller to refuse`() {
        check("today at 9", at(9, 21, 9))
    }

    @Test
    fun `for alarms a bare hour is whichever comes first, and the morning when a day is named`() {
        fun alarm(phrase: String, at: ZonedDateTime = now) = resolver.resolve(phrase, at, soonestBareHour = true)!!.at
        assertEquals(at(9, 22, 5, 30), alarm("tomorrow at 5:30"))
        assertEquals(at(9, 21, 18), alarm("6"))
        val lateEvening = ZonedDateTime.of(2026, 9, 21, 23, 0, 0, 0, zone)
        assertEquals(at(9, 22, 6), alarm("6", lateEvening))
        assertEquals(at(9, 22, 5, 30), alarm("5:30", lateEvening))
        // Anything that says am or pm, or a part of the day, is taken at its word.
        assertEquals(at(9, 21, 17, 30), alarm("5:30 pm"))
        assertEquals(at(9, 22, 6), alarm("subah 6 baje"))
    }

    @Test
    fun `whether a day was named is reported`() {
        assertEquals(false, resolver.resolve("at 3", now)!!.daySaid)
        assertEquals(false, resolver.resolve("shaam 7 baje", now)!!.daySaid)
        assertEquals(true, resolver.resolve("tomorrow at 3", now)!!.daySaid)
        assertEquals(true, resolver.resolve("friday", now)!!.daySaid)
    }

    @Test
    fun `nothing time-like resolves to nothing`() {
        assertNull(resolver.resolve("", now))
        assertNull(resolver.resolve("someday", now))
        assertNull(resolver.resolve("when I get a chance", now))
    }

    @Test
    fun `the defaults can be changed`() {
        val early = WhenResolver(WhenDefaults(morning = java.time.LocalTime.of(7, 30)))
        assertEquals(at(9, 22, 7, 30), early.resolve("tomorrow morning", now)!!.at)
    }
}
