package com.local.assistant.memory.tools

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The subset of RFC 5545 RRULE that reminders need: FREQ (daily, weekly, monthly, yearly),
 * INTERVAL, BYDAY, BYMONTHDAY, COUNT and UNTIL. Stored as the RRULE text; parsed from either that
 * or the user's own words ("every Monday", "har mahine").
 */
data class RepeatRule(
    val freq: Freq,
    val interval: Int = 1,
    val byDay: Set<DayOfWeek> = emptySet(),
    val byMonthDay: Int? = null,
    /** Occurrences left, including the pending one. */
    val count: Int? = null,
    val until: LocalDate? = null,
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    fun toRrule(): String = buildList {
        add("FREQ=$freq")
        if (interval != 1) add("INTERVAL=$interval")
        if (byDay.isNotEmpty()) add("BYDAY=" + byDay.sorted().joinToString(",") { DAY_CODES.getValue(it) })
        byMonthDay?.let { add("BYMONTHDAY=$it") }
        count?.let { add("COUNT=$it") }
        until?.let { add("UNTIL=" + it.format(DateTimeFormatter.BASIC_ISO_DATE)) }
    }.joinToString(";")

    /**
     * The first occurrence strictly after [after], at [anchor]'s time of day. [anchor] is the
     * occurrence the series is counted from (for intervals). Null once the rule has run out.
     */
    fun next(anchor: ZonedDateTime, after: ZonedDateTime): ZonedDateTime? {
        if (count != null && count <= 1) return null
        val candidate = when (freq) {
            Freq.DAILY -> stepUntilAfter(anchor, after) { anchor.plusDays(it * interval.toLong()) }
            Freq.WEEKLY -> if (byDay.isEmpty()) {
                stepUntilAfter(anchor, after) { anchor.plusWeeks(it * interval.toLong()) }
            } else {
                nextWeekday(anchor, after)
            }
            Freq.MONTHLY -> stepUntilAfter(anchor, after) { k ->
                val month = anchor.plusMonths(k * interval.toLong())
                val day = (byMonthDay ?: anchor.dayOfMonth).coerceAtMost(month.toLocalDate().lengthOfMonth())
                month.withDayOfMonth(day)
            }
            Freq.YEARLY -> stepUntilAfter(anchor, after) { anchor.plusYears(it * interval.toLong()) }
        } ?: return null
        return candidate.takeIf { until == null || !it.toLocalDate().isAfter(until) }
    }

    /** The rule after one occurrence is used up: COUNT goes down by one. */
    fun afterOccurrence(): RepeatRule = if (count == null) this else copy(count = count - 1)

    private inline fun stepUntilAfter(anchor: ZonedDateTime, after: ZonedDateTime, at: (Long) -> ZonedDateTime): ZonedDateTime? {
        // Jump close first, so a series that started years ago does not take years of steps.
        val unitsBehind = when (freq) {
            Freq.DAILY -> ChronoUnit.DAYS.between(anchor, after)
            Freq.WEEKLY -> ChronoUnit.WEEKS.between(anchor, after)
            Freq.MONTHLY -> ChronoUnit.MONTHS.between(anchor, after)
            Freq.YEARLY -> ChronoUnit.YEARS.between(anchor, after)
        }
        var k = maxOf(0L, unitsBehind / interval - 1)
        repeat(MAX_STEPS) {
            val candidate = at(k)
            if (candidate.isAfter(after)) return candidate
            k++
        }
        return null
    }

    /** Weekly with named days: the next listed day, in a week that is on the interval. */
    private fun nextWeekday(anchor: ZonedDateTime, after: ZonedDateTime): ZonedDateTime? {
        val anchorWeek = anchor.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var day = after.toLocalDate()
        repeat(7 * interval * 2 + 7) {
            val candidate = day.atTime(anchor.toLocalTime()).atZone(anchor.zone)
            val week = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weeksApart = ChronoUnit.WEEKS.between(anchorWeek, week)
            if (candidate.isAfter(after) && day.dayOfWeek in byDay && weeksApart % interval == 0L) return candidate
            day = day.plusDays(1)
        }
        return null
    }

    companion object {
        private const val MAX_STEPS = 400

        private val DAY_CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA",
            DayOfWeek.SUNDAY to "SU",
        )
        private val CODES_TO_DAYS = DAY_CODES.entries.associate { (day, code) -> code to day }

        private val WEEKDAY_NAMES: Map<String, DayOfWeek> = buildMap {
            fun put(day: DayOfWeek, vararg names: String) = names.forEach { put(it, day) }
            put(DayOfWeek.MONDAY, "monday", "mon", "somvar", "somwar")
            put(DayOfWeek.TUESDAY, "tuesday", "tue", "tues", "mangalvar", "mangalwar")
            put(DayOfWeek.WEDNESDAY, "wednesday", "wed", "budhvar", "budhwar")
            put(DayOfWeek.THURSDAY, "thursday", "thu", "thurs", "guruvar", "guruwar")
            put(DayOfWeek.FRIDAY, "friday", "fri", "shukravar", "shukrawar")
            put(DayOfWeek.SATURDAY, "saturday", "sat", "shanivar", "shaniwar")
            put(DayOfWeek.SUNDAY, "sunday", "sun", "ravivar", "raviwar", "itvaar", "itwar")
        }
        private val WORKING_DAYS = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )

        /** Either RRULE text or everyday words. Null if neither makes sense of it. */
        fun parse(text: String): RepeatRule? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            return if ("FREQ=" in trimmed.uppercase(Locale.ROOT)) {
                parseRrule(trimmed)
            } else {
                parseWords(trimmed)
            }
        }

        private fun parseRrule(text: String): RepeatRule? {
            val parts = text.substringAfter("RRULE:").split(';').mapNotNull {
                val (key, value) = it.split('=', limit = 2).takeIf { kv -> kv.size == 2 } ?: return@mapNotNull null
                key.trim().uppercase(Locale.ROOT) to value.trim().uppercase(Locale.ROOT)
            }.toMap()
            val freq = parts["FREQ"]?.let { runCatching { Freq.valueOf(it) }.getOrNull() } ?: return null
            return RepeatRule(
                freq = freq,
                interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                byDay = parts["BYDAY"]?.split(',')?.mapNotNull { CODES_TO_DAYS[it.takeLast(2)] }?.toSet().orEmpty(),
                byMonthDay = parts["BYMONTHDAY"]?.toIntOrNull()?.takeIf { it in 1..31 },
                count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 },
                until = parts["UNTIL"]?.take(8)?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() },
            )
        }

        private fun parseWords(text: String): RepeatRule? {
            val words = " " + text.lowercase(Locale.ROOT).replace(Regex("[,&]"), " ").replace(Regex("\\s+"), " ") + " "
            val count = Regex(" (?:for )?(\\d+) (?:times|baar) ").find(words)?.groupValues?.get(1)?.toIntOrNull()
            val every = Regex(" every (\\d+|other) (day|week|month|year)s? ").find(words)
            val interval = when (every?.groupValues?.get(1)) {
                null -> 1
                "other" -> 2
                else -> every.groupValues[1].toInt().coerceAtLeast(1)
            }
            val days = WEEKDAY_NAMES.filterKeys { Regex(" ${it}s? ").containsMatchIn(words) }.values.toSet()
            val monthDay = Regex(" (?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th) ").find(words)?.groupValues?.get(1)?.toIntOrNull()

            val rule = when {
                " weekday" in words || " weekdays " in words -> RepeatRule(Freq.WEEKLY, byDay = WORKING_DAYS)
                " weekend" in words -> RepeatRule(Freq.WEEKLY, byDay = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                " fortnight" in words || " biweekly " in words -> RepeatRule(Freq.WEEKLY, interval = 2, byDay = days)
                days.isNotEmpty() -> RepeatRule(Freq.WEEKLY, interval = interval, byDay = days)
                every?.groupValues?.get(2) == "day" || Regex(" (?:daily|every ?day|each day|roz|rozana|har din) ").containsMatchIn(words) ->
                    RepeatRule(Freq.DAILY, interval = interval)
                every?.groupValues?.get(2) == "week" || Regex(" (?:weekly|every week|har hafte) ").containsMatchIn(words) ->
                    RepeatRule(Freq.WEEKLY, interval = interval)
                every?.groupValues?.get(2) == "month" || Regex(" (?:monthly|every month|har mahine) ").containsMatchIn(words) ->
                    RepeatRule(Freq.MONTHLY, interval = interval, byMonthDay = monthDay)
                every?.groupValues?.get(2) == "year" || Regex(" (?:yearly|annually|every year|har saal) ").containsMatchIn(words) ->
                    RepeatRule(Freq.YEARLY, interval = interval)
                else -> return null
            }
            return rule.copy(count = count)
        }
    }
}
