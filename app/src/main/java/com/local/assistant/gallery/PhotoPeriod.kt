package com.local.assistant.gallery

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * When photos were taken, as the user says it: "last December", "2023", "this year", "3 weeks
 * ago". Always a stretch of the past, unlike [com.local.assistant.memory.tools.WhenResolver]'s
 * moments for reminders. Worked out here rather than by the model, which gets date arithmetic
 * subtly wrong.
 *
 * A month without a year is the most recent one that has begun ("December" in October means last
 * December); a weekday is the last one before today. "N years ago" is that whole calendar year.
 */
object PhotoPeriod {

    /** From [start] (inclusive) to [end] (exclusive), epoch millis in [now]'s zone. */
    data class Range(val start: Long, val end: Long) {
        operator fun contains(at: Long): Boolean = at in start until end
    }

    /** Null when [phrase] says no time the photos could be from. */
    fun parse(phrase: String, now: ZonedDateTime): Range? {
        val text = " " + phrase.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val today = now.toLocalDate()
        val zone = now.zone

        fun days(from: LocalDate, until: LocalDate) = Range(
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            until.atStartOfDay(zone).toInstant().toEpochMilli(),
        )

        fun upToNow(from: LocalDate) = Range(from.atStartOfDay(zone).toInstant().toEpochMilli(), now.toInstant().toEpochMilli() + 1)

        fun month(m: YearMonth) = days(m.atDay(1), m.plusMonths(1).atDay(1))

        fun year(y: Int) = days(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1))

        fun week(start: LocalDate) = days(start, start.plusWeeks(1))

        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        // "since" runs to now; "from last december" means during it.
        val since = " since " in text

        // "since 2023", "since March": from then until now.
        if (since) {
            YEAR.find(text)?.let { y -> if (MONTH_NAMES.none { it.key in text.split(' ') }) return upToNow(LocalDate.of(y.value.toInt(), 1, 1)) }
        }

        // An explicit day: "25 dec 2023", "december 25", "25 december".
        DAY_MONTH.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = MONTH_NAMES[m.groupValues[2]] ?: return@let
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            dayOf(day, month, year, today)?.let { return days(it, it.plusDays(1)) }
        }
        MONTH_DAY.find(text)?.let { m ->
            val month = MONTH_NAMES[m.groupValues[1]] ?: return@let
            val day = m.groupValues[2].toInt()
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            dayOf(day, month, year, today)?.let { return days(it, it.plusDays(1)) }
        }

        when {
            " today " in text -> return days(today, today.plusDays(1))
            " yesterday " in text -> return days(today.minusDays(1), today)
        }

        // "last 30 days", "past 2 weeks": a stretch ending now.
        LAST_N.find(text)?.let { m ->
            val n = amount(m.groupValues[2]) ?: return@let
            val from = when (m.groupValues[3]) {
                "day" -> today.minusDays(n - 1)
                "week" -> today.minusWeeks(n)
                "month" -> today.minusMonths(n)
                else -> today.minusYears(n)
            }
            return upToNow(from)
        }

        // "3 days ago", "two months ago", "a year ago": that day, week, month or year.
        AGO.find(text)?.let { m ->
            val n = amount(m.groupValues[1]) ?: return@let
            return when (m.groupValues[2]) {
                "day" -> today.minusDays(n).let { days(it, it.plusDays(1)) }
                "week" -> week(weekStart.minusWeeks(n))
                "month" -> month(YearMonth.from(today).minusMonths(n))
                else -> year(today.year - n.toInt())
            }
        }

        // "this week", "last month", "this year"…
        RELATIVE.find(text)?.let { m ->
            val back = if (m.groupValues[1] == "this") 0L else 1L
            return when (m.groupValues[2]) {
                "week" -> week(weekStart.minusWeeks(back))
                "month" -> month(YearMonth.from(today).minusMonths(back))
                else -> year(today.year - back.toInt())
            }
        }

        // "last sunday", "on monday": the last such day before today.
        WEEKDAY.find(text)?.let { m ->
            val day = DayOfWeek.valueOf(m.groupValues[2].uppercase(Locale.ROOT))
            val date = today.with(TemporalAdjusters.previous(day))
            return days(date, date.plusDays(1))
        }

        // A month, with or without a year: "december 2023", "last december", "march".
        MONTH.find(text)?.let { m ->
            val monthNumber = MONTH_NAMES[m.groupValues[2]] ?: return@let
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            val ym = if (year != null) {
                YearMonth.of(year, monthNumber)
            } else {
                val thisYear = YearMonth.of(today.year, monthNumber)
                // "this march" in October still means this year's; a month not begun yet, last year's.
                if (thisYear.isAfter(YearMonth.from(today)) || m.groupValues[1] == "last" && thisYear == YearMonth.from(today)) thisYear.minusYears(1) else thisYear
            }
            return if (since) upToNow(ym.atDay(1)) else month(ym)
        }

        YEAR.find(text)?.let { return year(it.value.trim().toInt()) }
        return null
    }

    /**
     * [text] without its time words, for a query that came with a date in it ("beach 2023" →
     * "beach"); blank when it was only a date.
     */
    fun withoutTime(text: String): String =
        text.split(Regex("\\s+")).filter { word ->
            // "25th" is a number; "last" keeps its "st".
            val w = word.lowercase(Locale.ROOT).trim(',', '.', '!', '?').replace(Regex("^(\\d+)(st|nd|rd|th)$"), "$1")
            w !in TIME_WORDS && w !in MONTH_NAMES && w !in NUMBERS && w.toIntOrNull() == null &&
                DayOfWeek.entries.none { it.name.equals(w, ignoreCase = true) }
        }.joinToString(" ")

    private val TIME_WORDS = setOf(
        "today", "yesterday", "this", "last", "past", "previous", "since", "from", "in", "on", "ago",
        "day", "days", "week", "weeks", "month", "months", "year", "years", "of",
    )

    private fun dayOf(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? = runCatching {
        if (year != null) return@runCatching LocalDate.of(year, month, day)
        val thisYear = LocalDate.of(today.year, month, day)
        if (thisYear.isAfter(today)) thisYear.minusYears(1) else thisYear
    }.getOrNull()

    private fun amount(word: String): Long? = word.toLongOrNull() ?: NUMBERS[word]

    private val NUMBERS = mapOf(
        "a" to 1L, "an" to 1L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L,
        "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L, "couple" to 2L, "few" to 3L,
    )

    private val MONTH_NAMES: Map<String, Int> = buildMap {
        Month.entries.forEach { m ->
            val name = m.name.lowercase(Locale.ROOT)
            put(name, m.value)
            put(name.take(3), m.value)
        }
        put("sept", 9)
    }

    private val MONTH_WORDS = MONTH_NAMES.keys.sortedByDescending { it.length }.joinToString("|")

    private val YEAR = Regex("(?<=\\s)(19[7-9]\\d|20\\d\\d)(?=\\s)")
    private val DAY_MONTH = Regex("\\s(\\d{1,2})(?:st|nd|rd|th)?\\s(?:of\\s)?($MONTH_WORDS)\\s(?:(\\d{4})\\s)?")
    private val MONTH_DAY = Regex("\\s($MONTH_WORDS)\\s(\\d{1,2})(?:st|nd|rd|th)?\\s(?:(\\d{4})\\s)?")
    private val MONTH = Regex("\\s(?:(last|this|in|from|since)\\s)?($MONTH_WORDS)\\s(?:(\\d{4})\\s)?")
    private val LAST_N = Regex("\\s(last|past)\\s(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|couple|few)\\s(?:of\\s)?(day|week|month|year)s?\\s")
    private val AGO = Regex("\\s(\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|couple|few)\\s(?:of\\s)?(day|week|month|year)s?\\sago\\s")
    private val RELATIVE = Regex("\\s(this|last|past|previous)\\s(week|month|year)\\s")
    private val WEEKDAY = Regex("\\s(last|on|this)?\\s?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s")
}
