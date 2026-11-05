package com.local.assistant.health

import com.local.assistant.gallery.PhotoPeriod
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * When health data is asked about, as the user says it: "this morning", "last night", "in the
 * last 2 hours", "at 3 pm", and the days, weeks and months [PhotoPeriod] reads ("yesterday",
 * "last week", "on Monday", "3 Nov"). Worked out here rather than by the model, which gets date
 * arithmetic subtly wrong. Always the past: a stretch running into the future ends now.
 */
object HealthPeriod {

    /**
     * From [start] to [end] (end exclusive, never after now). [night]: it is a night ("last
     * night", "monday night"), whose sleep is the one woken from the next morning. [latest]: no
     * time was given, or "now": the newest reading is what's wanted.
     */
    data class Span(
        val start: ZonedDateTime,
        val end: ZonedDateTime,
        val words: String,
        val night: Boolean = false,
        val latest: Boolean = false,
    ) {
        /** The days whose sleep this covers, each the morning it was woken from. */
        fun wakeDays(): List<LocalDate> {
            if (latest) return listOf(end.toLocalDate())
            if (night) return listOf(start.toLocalDate().plusDays(1))
            val last = end.minusNanos(1).toLocalDate()
            return generateSequence(start.toLocalDate()) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
        }

        /** Whole days, each from midnight: a day's own steps, distance or calories. */
        fun days(): List<LocalDate> {
            val last = end.minusNanos(1).toLocalDate()
            return generateSequence(start.toLocalDate()) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
        }

        val startInstant: Instant get() = start.toInstant()
        val endInstant: Instant get() = end.toInstant()
    }

    /** The parts of a day, as people mean them: morning 05:00–12:00 and so on; a night runs into the next day. */
    enum class Part(val from: LocalTime, val hours: Long) {
        MORNING(LocalTime.of(5, 0), 7),
        AFTERNOON(LocalTime.of(12, 0), 5),
        EVENING(LocalTime.of(17, 0), 4),
        NIGHT(LocalTime.of(21, 0), 9),
    }

    /**
     * [phrase] as a span of the past; a [Span.latest] one for no phrase or "now". Null when it says
     * no time health data could be from, or only the future ("tomorrow").
     */
    fun parse(phrase: String?, now: ZonedDateTime): Span? {
        val words = phrase?.trim().orEmpty()
        val text = " " + words.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9: ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
        val today = now.toLocalDate()
        val zone = now.zone

        fun span(from: ZonedDateTime, until: ZonedDateTime, night: Boolean = false): Span? {
            val end = if (until.isAfter(now)) now else until
            return if (from.isBefore(end)) Span(from, end, words, night = night) else null
        }

        if (text.isBlank() || LATEST.containsMatchIn(text)) {
            return Span(today.atStartOfDay(zone), now, words, latest = true)
        }
        if (FUTURE.containsMatchIn(text)) return null

        // "last 2 hours", "past 30 minutes", "in the last hour": a stretch ending now.
        RECENT.find(text)?.let { m ->
            val n = m.groupValues[2].let { if (it.isEmpty()) 1L else amount(it) } ?: return@let
            val from = if (m.groupValues[3].startsWith("h")) now.minusHours(n) else now.minusMinutes(n)
            return span(from, now)
        }
        // "the past week": the 7 days up to now, not last calendar week.
        if (PAST_WEEK.containsMatchIn(text)) return span(today.minusDays(6).atStartOfDay(zone), now)

        // The day a part of it or a time of day is on: today unless another is said.
        fun dayIn(): LocalDate? = when {
            " yesterday " in text -> today.minusDays(1)
            " today " in text || " this " in text || " tonight " in text -> today
            else -> WEEKDAY.find(text)?.let { today.with(TemporalAdjusters.previousOrSame(DayOfWeek.valueOf(it.groupValues[1].uppercase(Locale.ROOT)))) }
                ?: PhotoPeriod.parse(text, now)?.takeIf { it.end - it.start <= DAY_MS + HOUR_MS }?.let {
                    Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate()
                }
        }

        // "since 9 am", "since morning": from then until now.
        SINCE_CLOCK.find(text)?.let { m ->
            val times = clock(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            return span(lastAt(times, null, now) ?: return@let, now)
        }

        // "at 3 pm", "around 9:30": the half hour either side.
        AT_CLOCK.find(text)?.let { m ->
            val times = clock(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            val moment = lastAt(times, dayIn(), now) ?: return@let
            return span(moment.minusMinutes(30), moment.plusMinutes(30))
        }

        // "this morning", "last night", "yesterday evening", "monday night", "tonight".
        PART.find(text)?.let { m ->
            val part = when (m.groupValues[1]) {
                "morning" -> Part.MORNING
                "afternoon" -> Part.AFTERNOON
                "evening" -> Part.EVENING
                else -> Part.NIGHT
            }
            val day = when {
                part == Part.NIGHT && LAST_NIGHT.containsMatchIn(text) -> today.minusDays(1)
                // "since morning": from its start until now.
                " since " in text -> today
                else -> dayIn() ?: today
            }
            val from = day.atTime(part.from).atZone(zone)
            // Asked in the small hours, "tonight" is the night already under way.
            val start = if (part == Part.NIGHT && from.isAfter(now) && day == today) from.minusDays(1) else from
            return if (" since " in text) span(start, now) else span(start, start.plusHours(part.hours), night = part == Part.NIGHT)
        }

        val range = PhotoPeriod.parse(text, now) ?: return null
        return span(Instant.ofEpochMilli(range.start).atZone(zone), Instant.ofEpochMilli(range.end).atZone(zone))
    }

    /** The times a clock reading can be: "3" is 03:00 or 15:00, "3 pm" only 15:00. */
    private fun clock(hours: String, minutes: String, meridiem: String): List<LocalTime> {
        val h = hours.toIntOrNull() ?: return emptyList()
        val m = minutes.toIntOrNull() ?: 0
        if (h > 23 || m > 59) return emptyList()
        return when {
            meridiem == "pm" && h in 1..12 -> listOf(LocalTime.of(if (h == 12) 12 else h + 12, m))
            meridiem == "am" && h in 1..12 -> listOf(LocalTime.of(if (h == 12) 0 else h, m))
            meridiem.isEmpty() && h in 1..11 -> listOf(LocalTime.of(h, m), LocalTime.of(h + 12, m))
            meridiem.isEmpty() -> listOf(LocalTime.of(h, m))
            else -> emptyList()
        }
    }

    /** The latest of [times] that has come: on [day] when one is said, else today or, failing that, yesterday. */
    private fun lastAt(times: List<LocalTime>, day: LocalDate?, now: ZonedDateTime): ZonedDateTime? {
        val days = day?.let { listOf(it) } ?: listOf(now.toLocalDate(), now.toLocalDate().minusDays(1))
        return days.firstNotNullOfOrNull { d ->
            times.map { d.atTime(it).atZone(now.zone) }.filter { !it.isAfter(now) }.maxOrNull()
                // A day said outright keeps its time even if that hour is later today.
                ?: if (day != null && d != now.toLocalDate()) times.map { d.atTime(it).atZone(now.zone) }.maxOrNull() else null
        }
    }

    private fun amount(word: String): Long? = word.toLongOrNull() ?: NUMBERS[word]

    private const val HOUR_MS = 60 * 60 * 1000L
    private const val DAY_MS = 24 * HOUR_MS

    private val NUMBERS = mapOf(
        "a" to 1L, "an" to 1L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L,
        "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L, "twelve" to 12L, "couple" to 2L, "few" to 3L,
        "fifteen" to 15L, "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L,
    )

    private const val AMOUNT = "\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|twelve|couple|few|fifteen|twenty|thirty|forty|fifty"

    /** No time, or the moment: "now", "right now", "currently", "my latest". */
    private val LATEST = Regex("^\\s*(?:right )?(?:now|currently|current|latest|recent|recently|just now|at the moment|at present|the latest|last reading|most recent)\\s*$")
    private val FUTURE = Regex("\\s(tomorrow|next (?:week|month|year|monday|tuesday|wednesday|thursday|friday|saturday|sunday))\\s")
    private val RECENT = Regex("\\s(last|past|previous|in the last|in the past)\\s(?:($AMOUNT)\\s)?(hours?|hrs?|minutes?|mins?)\\s")
    private val PAST_WEEK = Regex("\\s(?:the |this )?past week\\s")
    private val LAST_NIGHT = Regex("\\s(last|yesterday) night\\s|\\slast nite\\s")
    private val PART = Regex("\\s(morning|afternoon|evening|night|tonight|nite)\\s")
    private val WEEKDAY = Regex("\\s(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s")
    private val SINCE_CLOCK = Regex("\\ssince\\s(\\d{1,2})(?::(\\d{2}))?\\s?(am|pm)?\\s")
    private val AT_CLOCK = Regex("\\s(?:at|around|about)\\s(\\d{1,2})(?::(\\d{2}))?\\s?(am|pm)?\\s")
}
