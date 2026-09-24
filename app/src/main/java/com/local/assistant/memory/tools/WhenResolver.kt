package com.local.assistant.memory.tools

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * When a phrase resolved to. [dateOnly] means no time was said and a default filled it in;
 * [daySaid] that the phrase named a day, rather than only a time ("at 3") or a moment ("in an
 * hour"). Moving an existing reminder "to 3" keeps its own day, which needs to know that.
 */
data class ResolvedWhen(val at: ZonedDateTime, val dateOnly: Boolean, val daySaid: Boolean = true)

/** The times words like "morning" stand for. Configurable; the chip always shows the result. */
data class WhenDefaults(
    val morning: LocalTime = LocalTime.of(9, 0),
    val afternoon: LocalTime = LocalTime.of(14, 0),
    val evening: LocalTime = LocalTime.of(18, 0),
    val night: LocalTime = LocalTime.of(21, 0),
    /** A date with no time at all, like "on Friday": early enough to act on the same day. */
    val dateOnly: LocalTime = LocalTime.of(8, 0),
)

/**
 * Turns the user's own time words into a moment, deterministically.
 *
 * The model copies what the user said ("tomorrow at 11", "kal subah 11 baje") and this does the
 * arithmetic, because date maths is exactly what a 4B model gets subtly wrong. Everything is
 * relative to [now] in its zone, so it follows the device.
 *
 * Rules worth knowing:
 * - A bare hour without am/pm: 7–11 is morning, 12 is noon, 1–6 is afternoon; 13–23 is 24-hour.
 * - A time with no day that has already passed today means the next occurrence.
 * - A weekday means the next one after today; "kal" means tomorrow (it is a reminder).
 * - A day with no time gets [WhenDefaults.dateOnly] and is reported as [ResolvedWhen.dateOnly].
 *
 * Returns null for a phrase with nothing time-like in it.
 */
class WhenResolver(private val defaults: WhenDefaults = WhenDefaults()) {

    fun resolve(phrase: String, now: ZonedDateTime): ResolvedWhen? {
        var text = " " + normalise(phrase) + " "
        // Read before "tonight" is consumed as a day word below: it is also a part of the day.
        val tonight = TONIGHT.containsMatchIn(text)

        if (HALF_HOUR.containsMatchIn(text)) {
            return ResolvedWhen(now.plusMinutes(30).withSecond(0).withNano(0), dateOnly = false, daySaid = true)
        }

        // "in 2 hours", "30 minute mein": a moment, not a day and a time.
        RELATIVE_DURATION.find(text)?.let { match ->
            val amount = amountOf(match.groupValues[1]) ?: return@let
            val unit = match.groupValues[2]
            val duration = when {
                unit.startsWith("min") -> Duration.ofMinutes(amount)
                unit.startsWith("h") || unit.startsWith("ghant") -> Duration.ofHours(amount)
                else -> null
            }
            if (duration != null) return ResolvedWhen(now.plus(duration).withSecond(0).withNano(0), dateOnly = false, daySaid = true)
        }

        var date: LocalDate? = null
        var dayWasSaid = false
        val today = now.toLocalDate()

        RELATIVE_DAYS.find(text)?.let { match ->
            val amount = amountOf(match.groupValues[1]) ?: return@let
            val unit = match.groupValues[2]
            date = if (unit.startsWith("week") || unit.startsWith("hafte")) today.plusWeeks(amount) else today.plusDays(amount)
            dayWasSaid = true
            text = text.replace(match.value, " ")
        }

        if (date == null) {
            parseExplicitDate(text, today)?.let { (found, span) ->
                date = found
                dayWasSaid = true
                text = text.replace(span, " ")
            }
        }

        if (date == null) {
            for ((pattern, offset) in DAY_WORDS) {
                val match = pattern.find(text) ?: continue
                date = today.plusDays(offset)
                dayWasSaid = true
                text = text.replace(match.value, " ")
                break
            }
        }

        if (date == null) {
            WEEKDAY.find(text)?.let { match ->
                val day = WEEKDAYS.getValue(match.groupValues[2])
                val thisWeek = match.groupValues[1].trim() == "this"
                date = if (thisWeek && today.dayOfWeek == day) today else today.with(TemporalAdjusters.next(day))
                dayWasSaid = true
                text = text.replace(match.value, " ")
            }
        }

        if (date == null) {
            NEXT_PERIOD.find(text)?.let { match ->
                date = when (match.groupValues[1].ifEmpty { match.groupValues[2] }) {
                    "week", "hafte" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    "month", "mahine" -> today.withDayOfMonth(1).plusMonths(1)
                    else -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
                }
                dayWasSaid = true
                text = text.replace(match.value, " ")
            }
        }

        val part = partOfDay(text) ?: if (tonight) Part.NIGHT else null

        val clock = parseClock(text, part)

        if (date == null && clock == null && part == null) return null

        var day = date ?: today
        val time: LocalTime
        var nextDay = false
        when {
            clock != null -> {
                time = clock.time
                nextDay = clock.nextDay
            }
            part != null -> time = part.default(defaults)
            else -> time = defaults.dateOnly
        }
        if (nextDay) day = day.plusDays(1)

        var at = day.atTime(time).atZone(now.zone)
        // A time with no day that has already gone means the next time it comes round.
        if (!dayWasSaid && !at.isAfter(now)) at = at.plusDays(1)
        return ResolvedWhen(at, dateOnly = clock == null && part == null, daySaid = dayWasSaid)
    }

    private enum class Part {
        MORNING, AFTERNOON, EVENING, NIGHT;

        fun default(defaults: WhenDefaults): LocalTime = when (this) {
            MORNING -> defaults.morning
            AFTERNOON -> defaults.afternoon
            EVENING -> defaults.evening
            NIGHT -> defaults.night
        }
    }

    private data class Clock(val time: LocalTime, val nextDay: Boolean = false)

    private fun partOfDay(text: String): Part? = when {
        MORNING.containsMatchIn(text) -> Part.MORNING
        AFTERNOON.containsMatchIn(text) -> Part.AFTERNOON
        EVENING.containsMatchIn(text) -> Part.EVENING
        NIGHT.containsMatchIn(text) -> Part.NIGHT
        else -> null
    }

    private fun parseClock(text: String, part: Part?): Clock? {
        if (NOON.containsMatchIn(text)) return Clock(LocalTime.NOON)
        if (MIDNIGHT.containsMatchIn(text)) return Clock(LocalTime.MIDNIGHT, nextDay = true)

        val marked = CLOCK_WITH_MARKER.find(text)
        val match = marked ?: BARE_HOUR.find(text) ?: return null
        val hour = match.groups["h"]!!.value.toInt()
        val minute = match.groups["m"]?.value?.toInt() ?: 0
        if (hour > 23 || minute > 59) return null
        // Only the marked pattern has an am/pm group; asking the other for it throws.
        val meridiem = marked?.groups?.get("ap")?.value?.firstOrNull()

        return when {
            meridiem == 'a' -> Clock(LocalTime.of(if (hour == 12) 0 else hour, minute))
            meridiem == 'p' -> Clock(LocalTime.of(if (hour == 12) 12 else hour + 12, minute))
            hour == 0 || hour > 12 -> Clock(LocalTime.of(hour, minute))
            part == Part.MORNING -> Clock(LocalTime.of(if (hour == 12) 0 else hour, minute))
            part == Part.AFTERNOON || part == Part.EVENING -> Clock(LocalTime.of(if (hour == 12) 12 else hour + 12, minute))
            part == Part.NIGHT -> when (hour) {
                12 -> Clock(LocalTime.MIDNIGHT, nextDay = true)
                in 1..4 -> Clock(LocalTime.of(hour, minute), nextDay = true)
                else -> Clock(LocalTime.of(hour + 12, minute))
            }
            // Bare hour: 7–11 morning, 12 noon, 1–6 afternoon.
            hour in 7..11 || hour == 12 -> Clock(LocalTime.of(hour, minute))
            else -> Clock(LocalTime.of(hour + 12, minute))
        }
    }

    /** "2026-10-01", "1/10", "25th", "25 oct", "oct 25", with the matched text to remove. */
    private fun parseExplicitDate(text: String, today: LocalDate): Pair<LocalDate, String>? {
        ISO_DATE.find(text)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()?.let { it to m.value }
        }
        NUMERIC_DATE.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            return dated(today, day, month, year)?.let { it to m.value }
        }
        DAY_MONTH.find(text)?.let { m ->
            val month = MONTHS[m.groupValues[2]] ?: return@let
            return dated(today, m.groupValues[1].toInt(), month, m.groupValues[3].toIntOrNull())?.let { it to m.value }
        }
        MONTH_DAY.find(text)?.let { m ->
            val month = MONTHS[m.groupValues[1]] ?: return@let
            return dated(today, m.groupValues[2].toInt(), month, m.groupValues[3].toIntOrNull())?.let { it to m.value }
        }
        ORDINAL_DAY.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            var candidate = runCatching { today.withDayOfMonth(day) }.getOrNull()
            if (candidate == null || candidate.isBefore(today)) {
                candidate = runCatching { today.plusMonths(1).withDayOfMonth(day) }.getOrNull()
            }
            return candidate?.let { it to m.value }
        }
        return null
    }

    /** A day and month with no year means the next time that date comes round. */
    private fun dated(today: LocalDate, day: Int, month: Int, year: Int?): LocalDate? {
        if (month !in 1..12) return null
        val sameYear = runCatching { LocalDate.of(year ?: today.year, month, day) }.getOrNull() ?: return null
        return if (year == null && sameYear.isBefore(today)) sameYear.plusYears(1) else sameYear
    }

    private fun amountOf(word: String): Long? = word.toLongOrNull() ?: NUMBER_WORDS[word]

    private fun normalise(phrase: String): String =
        phrase.lowercase(Locale.ROOT)
            .replace(Regex("[,;!?]"), " ")
            .replace(Regex("(\\d)\\.(\\d{2})"), "$1:$2")
            .replace(Regex("\\s+"), " ")
            .trim()

    private companion object {
        val NUMBER_WORDS = mapOf(
            "a" to 1L, "an" to 1L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L,
            "six" to 6L, "ten" to 10L, "fifteen" to 15L, "twenty" to 20L, "thirty" to 30L, "half" to 0L,
            "ek" to 1L, "do" to 2L, "teen" to 3L, "char" to 4L, "paanch" to 5L,
        )
        const val AMOUNT = "(\\d+|a|an|one|two|three|four|five|six|ten|fifteen|twenty|thirty|ek|do|teen|char|paanch)"

        /** "in 2 hours", "after 30 minutes", "2 ghante mein", "30 min baad". */
        val RELATIVE_DURATION = Regex(
            " (?:in |after )?$AMOUNT (minutes?|mins?|hours?|hrs?|ghante?|ghanton) ?(?:mein|me|baad|later|from now)?",
        )

        /** "in 3 days", "2 din baad", "in a week". */
        val RELATIVE_DAYS = Regex(" (?:in |after )$AMOUNT (days?|weeks?)| $AMOUNT (din|hafte) (?:mein|me|baad)")

        val DAY_WORDS: List<Pair<Regex, Long>> = listOf(
            Regex(" (?:the )?day after tomorrow | overmorrow | parso(?:n)? ") to 2L,
            Regex(" (?:tomorrow|tmrw|tmr|tomorow|kal) ") to 1L,
            Regex(" (?:today|aaj|tonight) ") to 0L,
        )

        val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
            fun put(day: DayOfWeek, vararg names: String) = names.forEach { put(it, day) }
            put(DayOfWeek.MONDAY, "monday", "mon", "somvar", "somwar")
            put(DayOfWeek.TUESDAY, "tuesday", "tue", "tues", "mangalvar", "mangalwar")
            put(DayOfWeek.WEDNESDAY, "wednesday", "wed", "budhvar", "budhwar")
            put(DayOfWeek.THURSDAY, "thursday", "thu", "thurs", "guruvar", "guruwar", "brihaspativar")
            put(DayOfWeek.FRIDAY, "friday", "fri", "shukravar", "shukrawar")
            put(DayOfWeek.SATURDAY, "saturday", "sat", "shanivar", "shaniwar")
            put(DayOfWeek.SUNDAY, "sunday", "sun", "ravivar", "raviwar", "itvaar", "itwar")
        }
        val WEEKDAY = Regex(" (?:on )?(next |this |coming )?(${WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|")}) (?:ko )?")

        /** "next week" is the coming Monday, "next month" its first day, a weekend its Saturday. */
        val NEXT_PERIOD = Regex(" (?:next |agle )(week|month|hafte|mahine) | (?:this |next )?(weekend) ")

        val MONTHS: Map<String, Int> = buildMap {
            Month.entries.forEach { month ->
                val name = month.name.lowercase(Locale.ROOT)
                put(name, month.value)
                put(name.take(3), month.value)
            }
            put("sept", 9)
        }
        val MONTH_NAMES = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

        val ISO_DATE = Regex(" (\\d{4})-(\\d{1,2})-(\\d{1,2}) ")
        /** Day first, as written in India: 1/10 is the first of October. */
        val NUMERIC_DATE = Regex(" (\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))? ")
        val DAY_MONTH = Regex(" (?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th)? (?:of )?($MONTH_NAMES)(?: (\\d{4}))? ")
        val MONTH_DAY = Regex(" (?:on )?($MONTH_NAMES) (\\d{1,2})(?:st|nd|rd|th)?(?: (\\d{4}))? ")
        val ORDINAL_DAY = Regex(" (?:on )?(?:the )?(\\d{1,2})(?:st|nd|rd|th) ")

        val TONIGHT = Regex(" tonight ")
        val HALF_HOUR = Regex(" (?:in )?half an hour | aadha ghanta ")
        val MORNING = Regex(" (?:morning|subah|savere|sawere) ")
        val AFTERNOON = Regex(" (?:afternoon|dopahar|dopehar) ")
        val EVENING = Regex(" (?:evening|shaam|sham|evng) ")
        val NIGHT = Regex(" (?:night|raat|rat) ")
        val NOON = Regex(" noon ")
        val MIDNIGHT = Regex(" midnight ")

        /** A number that says it is a time: "11am", "5 pm", "11 baje", "5 o'clock". */
        val CLOCK_WITH_MARKER = Regex(
            " (?:at |@ ?)?(?<h>\\d{1,2})(?::(?<m>\\d{2}))? ?(?:(?<ap>am|pm|a\\.m\\.|p\\.m\\.)|baje|bje|o'?clock)(?= )",
        )

        /** A plain number standing alone, or after "at": "at 11", "11:30", "shaam 7". */
        val BARE_HOUR = Regex(" (?:at |@ ?)?(?<h>\\d{1,2})(?::(?<m>\\d{2}))?(?= )")
    }
}
