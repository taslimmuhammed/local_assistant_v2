package com.local.assistant.memory.tools

import com.local.assistant.memory.tools.RepeatRule.Freq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RepeatRuleTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(year: Int, month: Int, day: Int, hour: Int = 9) =
        ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, 0), zone)

    @Test
    fun `everyday words become rules`() {
        assertEquals(RepeatRule(Freq.DAILY), RepeatRule.parse("every day"))
        assertEquals(RepeatRule(Freq.DAILY), RepeatRule.parse("daily"))
        assertEquals(RepeatRule(Freq.DAILY), RepeatRule.parse("roz"))
        assertEquals(RepeatRule(Freq.DAILY, interval = 2), RepeatRule.parse("every other day"))
        assertEquals(RepeatRule(Freq.WEEKLY, byDay = setOf(DayOfWeek.MONDAY)), RepeatRule.parse("every Monday"))
        assertEquals(RepeatRule(Freq.WEEKLY, byDay = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)), RepeatRule.parse("mondays and thursdays"))
        assertEquals(RepeatRule(Freq.WEEKLY, byDay = setOf(DayOfWeek.MONDAY)), RepeatRule.parse("har somvar"))
        assertEquals(5, RepeatRule.parse("weekdays")!!.byDay.size)
        assertEquals(RepeatRule(Freq.WEEKLY, interval = 2), RepeatRule.parse("every 2 weeks"))
        assertEquals(RepeatRule(Freq.MONTHLY, byMonthDay = 5), RepeatRule.parse("every month on the 5th"))
        assertEquals(RepeatRule(Freq.MONTHLY), RepeatRule.parse("har mahine"))
        assertEquals(RepeatRule(Freq.YEARLY), RepeatRule.parse("every year"))
        assertEquals(RepeatRule(Freq.DAILY, count = 5), RepeatRule.parse("daily for 5 times"))
        assertNull(RepeatRule.parse("sometimes"))
    }

    @Test
    fun `rrule text round-trips`() {
        val rule = RepeatRule(Freq.WEEKLY, interval = 2, byDay = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), count = 4, until = LocalDate.of(2026, 12, 31))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR;COUNT=4;UNTIL=20261231", rule.toRrule())
        assertEquals(rule, RepeatRule.parse(rule.toRrule()))
        assertEquals(RepeatRule(Freq.DAILY), RepeatRule.parse("RRULE:FREQ=DAILY"))
    }

    @Test
    fun `daily and interval steps keep the time of day`() {
        val anchor = at(2026, 9, 21, 11)
        assertEquals(at(2026, 9, 22, 11), RepeatRule(Freq.DAILY).next(anchor, anchor))
        assertEquals(at(2026, 9, 23, 11), RepeatRule(Freq.DAILY, interval = 2).next(anchor, anchor))
    }

    @Test
    fun `weekly with named days walks to the next listed day`() {
        val monday = at(2026, 9, 21)
        val rule = RepeatRule(Freq.WEEKLY, byDay = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY))
        assertEquals(at(2026, 9, 24), rule.next(monday, monday))
        assertEquals(at(2026, 9, 28), rule.next(monday, at(2026, 9, 24)))
    }

    @Test
    fun `every other week skips the off weeks`() {
        val monday = at(2026, 9, 21)
        val rule = RepeatRule(Freq.WEEKLY, interval = 2, byDay = setOf(DayOfWeek.MONDAY))
        assertEquals(at(2026, 10, 5), rule.next(monday, monday))
    }

    @Test
    fun `monthly on the 31st lands on the last day of short months`() {
        val anchor = at(2026, 1, 31)
        assertEquals(at(2026, 2, 28), RepeatRule(Freq.MONTHLY).next(anchor, anchor))
    }

    @Test
    fun `a series long past jumps straight to the next occurrence`() {
        val anchor = at(2020, 1, 1)
        assertEquals(at(2026, 9, 22), RepeatRule(Freq.DAILY).next(anchor, at(2026, 9, 21, 10)))
        assertEquals(at(2027, 1, 1), RepeatRule(Freq.YEARLY).next(anchor, at(2026, 9, 21)))
    }

    @Test
    fun `count and until end the series`() {
        val anchor = at(2026, 9, 21)
        assertNull(RepeatRule(Freq.DAILY, count = 1).next(anchor, anchor))
        assertEquals(RepeatRule(Freq.DAILY, count = 2), RepeatRule(Freq.DAILY, count = 3).afterOccurrence())
        assertNull(RepeatRule(Freq.DAILY, until = LocalDate.of(2026, 9, 21)).next(anchor, anchor))
    }
}
