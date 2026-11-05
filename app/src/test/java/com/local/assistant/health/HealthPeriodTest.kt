package com.local.assistant.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HealthPeriodTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    /** Thursday 5 November 2026, 10:42. */
    private val now = ZonedDateTime.of(2026, 11, 5, 10, 42, 0, 0, zone)

    private fun at(d: Int, h: Int, m: Int = 0, month: Int = 11) = ZonedDateTime.of(2026, month, d, h, m, 0, 0, zone)

    private fun span(phrase: String?, at: ZonedDateTime = now) = HealthPeriod.parse(phrase, at)

    private fun assertSpan(phrase: String, from: ZonedDateTime, until: ZonedDateTime, at: ZonedDateTime = now) {
        val span = span(phrase, at)
        assertNotNull(phrase, span)
        assertEquals("$phrase starts", from, span!!.start)
        assertEquals("$phrase ends", until, span.end)
    }

    @Test
    fun `no time or now is the latest`() {
        for (phrase in listOf(null, "", "now", "right now", "currently", "latest")) {
            val span = span(phrase)!!
            assertTrue("$phrase", span.latest)
            assertEquals(at(5, 0), span.start)
            assertEquals(now, span.end)
        }
        assertFalse(span("today")!!.latest)
    }

    @Test
    fun `days run to now at most`() {
        assertSpan("today", at(5, 0), now)
        assertSpan("yesterday", at(4, 0), at(5, 0))
        assertSpan("on Monday", at(2, 0), at(3, 0))
        assertSpan("3 Nov", at(3, 0), at(4, 0))
    }

    @Test
    fun `weeks and months`() {
        assertSpan("this week", at(2, 0), now)
        assertSpan("last week", at(26, 0, month = 10), at(2, 0))
        assertSpan("the past week", at(30, 0, month = 10), now)
        assertSpan("last 7 days", at(30, 0, month = 10), now)
        assertSpan("this month", at(1, 0), now)
        assertSpan("last month", at(1, 0, month = 10), at(1, 0))
    }

    @Test
    fun `parts of the day`() {
        assertSpan("this morning", at(5, 5), now)
        assertSpan("yesterday evening", at(4, 17), at(4, 21))
        assertSpan("yesterday afternoon", at(4, 12), at(4, 17))
        assertSpan("in the morning", at(5, 5), now)
        assertSpan("Monday morning", at(2, 5), at(2, 12))
        assertSpan("since morning", at(5, 5), now)
    }

    @Test
    fun `nights run into the next day and are woken from then`() {
        val lastNight = span("last night")!!
        assertEquals(at(4, 21), lastNight.start)
        assertEquals(at(5, 6), lastNight.end)
        assertTrue(lastNight.night)
        assertEquals(listOf(LocalDate.of(2026, 11, 5)), lastNight.wakeDays())
        assertSpan("yesterday night", at(4, 21), at(5, 6))
        assertEquals(listOf(LocalDate.of(2026, 11, 3)), span("monday night")!!.wakeDays())
        // Asked in the small hours, tonight is the night under way.
        val small = at(5, 2)
        assertSpan("tonight", at(4, 21), small, at = small)
    }

    @Test
    fun `sleep nights are the mornings woken`() {
        assertEquals(listOf(LocalDate.of(2026, 11, 5)), span(null)!!.wakeDays())
        assertEquals(listOf(LocalDate.of(2026, 11, 5)), span("today")!!.wakeDays())
        assertEquals(listOf(LocalDate.of(2026, 11, 4)), span("yesterday")!!.wakeDays())
        assertEquals((2..5).map { LocalDate.of(2026, 11, it) }, span("this week")!!.wakeDays())
    }

    @Test
    fun `the last hours and minutes`() {
        assertSpan("last 2 hours", now.minusHours(2), now)
        assertSpan("in the last hour", now.minusHours(1), now)
        assertSpan("past 30 minutes", now.minusMinutes(30), now)
        assertSpan("the last few hours", now.minusHours(3), now)
    }

    @Test
    fun `a time of day is the half hour around it, the latest that has come`() {
        assertSpan("at 9 am", at(5, 8, 30), at(5, 9, 30))
        // 3 pm hasn't come yet today: 3 am did.
        assertSpan("at 3", at(5, 2, 30), at(5, 3, 30))
        assertSpan("at 9:30", at(5, 9), at(5, 10))
        assertSpan("yesterday at 3 pm", at(4, 14, 30), at(4, 15, 30))
        assertSpan("around 10", at(5, 9, 30), at(5, 10, 30))
        assertSpan("since 8", at(5, 8), now)
        assertSpan("since 9 pm", at(4, 21), now)
    }

    @Test
    fun `the future and nonsense are nothing`() {
        assertNull(span("tomorrow"))
        assertNull(span("next week"))
        assertNull(span("blah blah"))
    }
}
