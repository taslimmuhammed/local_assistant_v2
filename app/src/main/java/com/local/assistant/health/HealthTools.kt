package com.local.assistant.health

import com.local.assistant.health.HealthSource.Stage
import com.local.assistant.health.HealthSource.Total
import com.local.assistant.memory.tools.ActionResult
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.ToolError
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * health_data: the user's steps, distance, calories, heart rate, sleep and workouts, as the
 * phone's health apps (Fitbit, OHealth, a watch's app) keep them in Health Connect. Read only,
 * and only what the user has allowed: the first question about a kind asks for it then.
 *
 * The model gets compact facts to phrase, numbers worked out here (sums, averages, a night's
 * stages), with the time they cover and the app they came from. When nothing is there it gets
 * that in words, and whether anything at all is ("no heart-rate data on this phone" is not
 * "none this morning"), so it says so instead of guessing.
 *
 * Health data stays on the phone: what was read is never logged, and a web search carrying a
 * number just read out is refused ([inQuery]).
 */
class HealthTools(
    private val source: HealthSource,
    /** Asks the user for [permissions] on Health Connect's own screen; false when no screen can ask. */
    private val requestAccess: suspend (permissions: Set<String>) -> Boolean,
    /** An app's name from its package: "from Fitbit". */
    private val appName: (packageName: String) -> String = { it },
    /** The user's daily step goal, as set in Settings → Health data. */
    private val stepGoal: () -> Int = { DEFAULT_STEP_GOAL },
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) {

    /** Whether there is a Health Connect to read: health_data is declared to the model only then. */
    fun available(): Boolean = source.availability() == HealthSource.Availability.AVAILABLE

    internal suspend fun read(metricText: String?, whenText: String?): ActionResult {
        when (source.availability()) {
            HealthSource.Availability.NOT_INSTALLED -> throw ToolError(
                "This phone doesn't have Health Connect, where Android keeps health data, so there is nothing to read. Tell the user.",
            )
            HealthSource.Availability.NEEDS_UPDATE -> throw ToolError(
                "Health Connect on this phone needs an update from the Play Store before this app can read it. Tell the user.",
            )
            HealthSource.Availability.AVAILABLE -> Unit
        }
        val metric = HealthMetric.parse(metricText)
            ?: throw ToolError("metric must be steps, distance, calories, heart_rate, resting_heart_rate, sleep or exercise.")
        val now = now()
        val span = HealthPeriod.parse(whenText, now) ?: throw ToolError(
            if (whenText != null && FUTURE.containsMatchIn(whenText.lowercase(Locale.ROOT))) {
                "'$whenText' hasn't happened yet, so there is no health data from then. Tell the user."
            } else {
                "I couldn't tell when '$whenText' is. Ask the user for a day or a time of day."
            },
        )
        val allowed = access(metric)
        val numbers = mutableSetOf<String>()
        val done = when (metric) {
            HealthMetric.STEPS, HealthMetric.DISTANCE, HealthMetric.CALORIES -> totals(metric, span, allowed, now, numbers)
            HealthMetric.HEART_RATE -> heartRate(span, now, numbers)
            HealthMetric.RESTING_HEART_RATE -> restingHeartRate(span, now, numbers)
            HealthMetric.SLEEP -> sleep(span, now, numbers)
            HealthMetric.EXERCISE -> exercise(span, now, numbers)
        }
        remember(numbers, now)
        return done
    }

    /** The permissions of [metric] the user has allowed, asking first if none are. */
    private suspend fun access(metric: HealthMetric): Set<String> {
        var granted = source.granted() intersect metric.permissions
        if (granted.isEmpty()) {
            requestAccess(metric.permissions)
            granted = source.granted() intersect metric.permissions
        }
        if (granted.isEmpty()) {
            throw ToolError(
                "The user hasn't allowed this app to read their ${metric.noun} data, so nothing was read. Tell them they can " +
                    "allow it in this app's Settings, under Health data, and then ask again.",
            )
        }
        return granted
    }

    // ---- Steps, distance, calories: sums ----

    private suspend fun totals(metric: HealthMetric, asked: HealthPeriod.Span, allowed: Set<String>, now: ZonedDateTime, numbers: MutableSet<String>): ActionResult {
        // No time said: today so far.
        val span = if (asked.latest) asked.copy(words = "today") else asked
        val kinds = when (metric) {
            HealthMetric.STEPS -> listOf(Total.STEPS)
            HealthMetric.DISTANCE -> listOf(Total.DISTANCE_METERS)
            else -> listOfNotNull(
                Total.TOTAL_KCAL.takeIf { HealthMetric.TOTAL_CALORIES in allowed },
                Total.ACTIVE_KCAL.takeIf { HealthMetric.ACTIVE_CALORIES in allowed },
            )
        }
        val result = start(metric, period(span, now))
        val origins = mutableSetOf<String>()
        var chip: String? = null
        val days = span.days()
        val wholeDays = span.start == span.start.toLocalDate().atStartOfDay(span.start.zone)
        // The step goal is a day's: not for part of one ("this morning").
        val goal = stepGoal().takeIf { metric == HealthMetric.STEPS && wholeDays }
        for (kind in kinds) {
            val amount = source.total(kind, span.startInstant, span.endInstant) ?: continue
            origins += amount.origins
            val key = KEYS.getValue(kind)
            result[key] = value(kind, amount.value).also { numbers += numberWords(it) }
            if (goal != null) {
                result["daily_step_goal"] = goal
                if (days.size == 1) towardGoal(result, amount.value.roundToLong(), goal, today = days.single() == now.toLocalDate(), numbers)
            }
            if (chip == null) chip = shown(kind, amount.value) + if (goal != null && days.size == 1) " of ${shown(kind, goal.toDouble())}" else ""
            // Over several whole days, each day's own, and the average of those with any.
            if (days.size >= 2 && wholeDays) {
                val daily = source.daily(kind, days, now.zone)
                if (daily.isNotEmpty()) {
                    val average = daily.values.sumOf { it.value } / daily.size
                    result["${key}_daily_average"] = value(kind, average).also { numbers += numberWords(it) }
                    result["days_with_data"] = "${daily.size} of ${days.size}"
                    if (goal != null) result["days_step_goal_reached"] = "${daily.values.count { it.value >= goal }} of ${days.size}"
                    if (days.size <= MAX_LISTED_DAYS) {
                        result["${key}_by_day"] = days.joinToString(", ") { day ->
                            "${DAY.format(day)}: " + (daily[day]?.let { value(kind, it.value).also { v -> numbers += numberWords(v) }.toString() } ?: "nothing recorded")
                        }
                    } else {
                        daily.maxByOrNull { it.value.value }?.let { (day, best) ->
                            result["${key}_best_day"] = "${DAY.format(day)}: ${value(kind, best.value)}"
                        }
                    }
                }
            }
        }
        if (chip == null) {
            if (goal != null) result["daily_step_goal"] = goal
            return nothing(metric, span, now, result)
        }
        from(result, origins)
        historyNote(result, span, now)
        return ActionResult(result, MemoryChip(MemoryChip.Kind.HEALTH, metric.label, "$chip · ${short(span, now)}"))
    }

    /**
     * How a day's [steps] stand against the [goal]: still to walk today, short of it on a day
     * gone by, or past it. Worked out here, not left to the model's arithmetic.
     */
    private fun towardGoal(result: MutableMap<String, Any?>, steps: Long, goal: Int, today: Boolean, numbers: MutableSet<String>) {
        val gap = goal - steps
        result["step_goal_reached"] = gap <= 0
        result[
            when {
                gap <= 0 -> "steps_over_goal"
                today -> "steps_left_to_goal"
                else -> "steps_short_of_goal"
            },
        ] = abs(gap)
        numbers += abs(gap).toString()
    }

    // ---- Heart rate ----

    private suspend fun heartRate(span: HealthPeriod.Span, now: ZonedDateTime, numbers: MutableSet<String>): ActionResult {
        if (span.latest) {
            val result = start(HealthMetric.HEART_RATE, null)
            val latest = source.latestHeartRate(now.minusDays(LATEST_DAYS).toInstant(), now.toInstant())
                ?: return nothing(HealthMetric.HEART_RATE, span.copy(start = now.minusDays(LATEST_DAYS), words = "the last week"), now, result)
            result["latest_bpm"] = latest.bpm.also { numbers += it.toString() }
            result["latest_at"] = moment(latest.at, now)
            if (Duration.between(latest.at, now.toInstant()) > STALE) {
                result["note"] = "This is the newest reading there is, not one from now: say when it was taken."
            }
            val origins = mutableSetOf(latest.origin)
            source.heartRate(now.toLocalDate().atStartOfDay(now.zone).toInstant(), now.toInstant())?.let { today ->
                result["today"] = summary(today).also { numbers += numberWords(today) }
                origins += today.origins
            }
            from(result, origins)
            return ActionResult(result, MemoryChip(MemoryChip.Kind.HEALTH, HealthMetric.HEART_RATE.label, "${latest.bpm} bpm · ${moment(latest.at, now)}"))
        }
        val result = start(HealthMetric.HEART_RATE, period(span, now))
        val found = source.heartRate(span.startInstant, span.endInstant) ?: return nothing(HealthMetric.HEART_RATE, span, now, result)
        result["average_bpm"] = found.average
        result["lowest_bpm"] = found.lowest
        result["highest_bpm"] = found.highest
        result["readings"] = found.readings
        numbers += numberWords(found)
        source.latestHeartRate(span.startInstant, span.endInstant)?.let { result["last_reading"] = "${it.bpm} bpm at ${moment(it.at, now)}" }
        from(result, found.origins)
        historyNote(result, span, now)
        return ActionResult(
            result,
            MemoryChip(MemoryChip.Kind.HEALTH, HealthMetric.HEART_RATE.label, "avg ${found.average} · ${found.lowest}–${found.highest} bpm · ${short(span, now)}"),
        )
    }

    private suspend fun restingHeartRate(asked: HealthPeriod.Span, now: ZonedDateTime, numbers: MutableSet<String>): ActionResult {
        // No time said: the newest, from the last week.
        val span = if (asked.latest) asked.copy(start = now.minusDays(LATEST_DAYS), words = "the last week") else asked
        val result = start(HealthMetric.RESTING_HEART_RATE, if (asked.latest) null else period(span, now))
        // A day's resting rate is often written once the day is over: look at the whole day.
        val readings = source.restingHeartRate(span.startInstant, span.endInstant)
        if (readings.isEmpty()) return nothing(HealthMetric.RESTING_HEART_RATE, span, now, result)
        val newest = readings.maxBy { it.at }
        val chip: String
        if (asked.latest || readings.size == 1) {
            result["resting_bpm"] = newest.bpm
            result["on"] = DAY.format(newest.at.atZone(now.zone))
            chip = "${newest.bpm} bpm · ${DAY.format(newest.at.atZone(now.zone))}"
            numbers += newest.bpm.toString()
        } else {
            val average = readings.map { it.bpm }.average().roundToLong()
            result["average_bpm"] = average
            result["lowest_bpm"] = readings.minOf { it.bpm }
            result["highest_bpm"] = readings.maxOf { it.bpm }
            if (readings.size <= MAX_LISTED_DAYS) {
                result["by_day"] = readings.joinToString(", ") { "${DAY.format(it.at.atZone(now.zone))}: ${it.bpm}" }
            }
            numbers += readings.map { it.bpm.toString() } + average.toString()
            chip = "avg $average bpm · ${short(span, now)}"
        }
        from(result, readings.map { it.origin }.toSet())
        historyNote(result, span, now)
        return ActionResult(result, MemoryChip(MemoryChip.Kind.HEALTH, HealthMetric.RESTING_HEART_RATE.label, chip))
    }

    // ---- Sleep ----

    private suspend fun sleep(span: HealthPeriod.Span, now: ZonedDateTime, numbers: MutableSet<String>): ActionResult {
        // Each night is the morning it was woken from, as health apps show it: "last night" is
        // today's, whatever time it is asked.
        val wakeDays = span.wakeDays().takeLast(MAX_NIGHTS)
        val zone = now.zone
        val from = wakeDays.first().atStartOfDay(zone).minusHours(NIGHT_CUTOFF_HOURS)
        val until = wakeDays.last().plusDays(1).atStartOfDay(zone).minusHours(NIGHT_CUTOFF_HOURS).let { if (it.isAfter(now)) now else it }
        val nightWords = nightsWords(span, wakeDays, now)
        val result = start(HealthMetric.SLEEP, nightWords)
        val nights = nights(source.sleep(from.toInstant(), until.toInstant()), zone).filter { it.wakeDay in wakeDays }
        if (nights.isEmpty()) {
            return nothing(HealthMetric.SLEEP, span.copy(start = from, end = until, words = nightWords), now, result)
        }
        val chip: String
        if (nights.size == 1) {
            val night = nights.single()
            result["night"] = nightName(night.wakeDay)
            result["asleep"] = duration(night.asleep)
            result["went_to_sleep"] = moment(night.bedtime, now)
            result["woke_up"] = moment(night.woke, now)
            result["in_bed"] = duration(night.inBed)
            if (night.stages.isNotEmpty()) result["stages"] = stages(night.stages)
            if (night.sessions > 1) result["note"] = "Asleep counts all ${night.sessions} times they slept that day, naps too."
            numbers += numberWords(night)
            chip = "${duration(night.asleep)} · ${nightName(night.wakeDay)}"
        } else {
            val average = nights.map { it.asleep }.reduce(Duration::plus).dividedBy(nights.size.toLong())
            result["nights"] = "${nights.size} of ${wakeDays.size}"
            result["average_asleep"] = duration(average)
            result["shortest"] = nights.minBy { it.asleep }.let { "${duration(it.asleep)}, ${nightName(it.wakeDay)}" }
            result["longest"] = nights.maxBy { it.asleep }.let { "${duration(it.asleep)}, ${nightName(it.wakeDay)}" }
            if (nights.size <= MAX_LISTED_DAYS) {
                result["by_night"] = nights.joinToString(", ") { "${nightName(it.wakeDay)}: ${duration(it.asleep)}" }
            }
            nights.forEach { numbers += numberWords(it) }
            numbers += numberWords(average)
            chip = "avg ${duration(average)} · ${nights.size} nights"
        }
        from(result, nights.flatMap { it.origins }.toSet())
        historyNote(result, span.copy(start = from), now)
        return ActionResult(result, MemoryChip(MemoryChip.Kind.HEALTH, HealthMetric.SLEEP.label, chip))
    }

    /** What a span's nights are called: "last night", or the words asked with the nights named. */
    private fun nightsWords(span: HealthPeriod.Span, wakeDays: List<LocalDate>, now: ZonedDateTime): String = when {
        wakeDays.size == 1 && wakeDays.single() == now.toLocalDate() -> "last night (${nightName(wakeDays.single())})"
        wakeDays.size == 1 -> nightName(wakeDays.single())
        else -> listOfNotNull(span.words.takeIf { it.isNotBlank() }, "the nights before ${DAY.format(wakeDays.first())} to ${DAY.format(wakeDays.last())}").joinToString(", ")
    }

    // ---- Workouts ----

    private suspend fun exercise(asked: HealthPeriod.Span, now: ZonedDateTime, numbers: MutableSet<String>): ActionResult {
        val span = if (asked.latest) asked.copy(start = now.minusDays(LATEST_DAYS), words = "the last week") else asked
        val result = start(HealthMetric.EXERCISE, period(span, now))
        val workouts = distinctWorkouts(source.exercise(span.startInstant, span.endInstant))
        if (workouts.isEmpty()) return nothing(HealthMetric.EXERCISE, span, now, result)
        val total = workouts.map { Duration.between(it.start, it.end) }.reduce(Duration::plus)
        result["workouts"] = workouts.size
        result["total_time"] = duration(total)
        result["list"] = workouts.takeLast(MAX_WORKOUTS).joinToString("; ") { w ->
            listOfNotNull(moment(w.start, now), w.type, duration(Duration.between(w.start, w.end)), w.title?.takeIf { it.isNotBlank() && !it.equals(w.type, true) }?.let { "\"$it\"" })
                .joinToString(", ")
        }
        if (workouts.size > MAX_WORKOUTS) result["note"] = "Only the newest $MAX_WORKOUTS are listed."
        workouts.forEach { numbers += numberWords(Duration.between(it.start, it.end)) }
        numbers += numberWords(total)
        from(result, workouts.map { it.origin }.toSet())
        historyNote(result, span, now)
        val count = if (workouts.size == 1) workouts.single().type else "${workouts.size} workouts"
        return ActionResult(result, MemoryChip(MemoryChip.Kind.HEALTH, HealthMetric.EXERCISE.label, "$count · ${duration(total)} · ${short(span, now)}"))
    }

    // ---- Nothing there ----

    /**
     * Nothing for [span]: said so, and whether there's any of it at all lately, so "none this
     * morning" isn't taken for "none on this phone" or the other way round. It starts by saying
     * reading was allowed: told only "no app has saved any", the model answered "I don't have
     * access to your heart rate data" (5 Nov 2026), which sends the user to a setting that's on.
     */
    private suspend fun nothing(metric: HealthMetric, span: HealthPeriod.Span, now: ZonedDateTime, result: MutableMap<String, Any?>): ActionResult {
        val newest = source.newest(metric, now.minusDays(LOOKBACK_DAYS).toInstant(), now.toInstant())
        result["found"] = "nothing"
        result["note"] = if (newest == null) {
            "Reading was allowed, but no app on this phone has saved any ${metric.noun} data to Health Connect in the last $LOOKBACK_DAYS days. " +
                "Tell the user there is no ${metric.noun} data on the phone (not that you lack access), and never guess a number. " +
                "A watch or fitness app (Fitbit, OHealth and the like) puts its data there once it's turned on in that app's settings."
        } else {
            "Reading was allowed, but nothing was recorded for ${period(span, now)}. The newest ${metric.noun} data is from ${moment(newest, now)}. " +
                "Say so (not that you lack access), and never guess a number."
        }
        return ActionResult(result, null)
    }

    // ---- Keeping it on the phone ----

    /** Numbers just read out, and when each set stops counting. */
    private val reported = ArrayDeque<Pair<Long, Set<String>>>()

    private fun remember(numbers: Set<String>, now: ZonedDateTime) = synchronized(reported) {
        // A single digit is in too many searches to tell anything.
        val telling = numbers.filter { n -> n.count(Char::isDigit) >= 2 }.toSet()
        if (telling.isNotEmpty()) reported.addLast(now.toInstant().toEpochMilli() + KEEP_MS to telling)
    }

    /**
     * Whether [query] carries a number health_data read out lately ("is 52 bpm resting normal"):
     * a web search with it would send the user's health data off the phone.
     */
    fun inQuery(query: String): Boolean {
        val asked = NUMBER.findAll(query).map { it.value.replace(",", "") }.toSet()
        if (asked.isEmpty()) return false
        val nowMs = now().toInstant().toEpochMilli()
        return synchronized(reported) {
            reported.removeAll { it.first <= nowMs }
            reported.any { (_, numbers) -> asked.any { it in numbers } }
        }
    }

    // ---- Shaping results ----

    private fun start(metric: HealthMetric, period: String?): MutableMap<String, Any?> =
        linkedMapOf<String, Any?>("ok" to true, "metric" to metric.key).apply { if (period != null) put("period", period) }

    private fun from(result: MutableMap<String, Any?>, origins: Set<String>) {
        if (origins.isNotEmpty()) result["from"] = origins.map(::source).distinct().sorted().joinToString(", ")
    }

    /**
     * The app's name. The phone's own step counter writes as an app that isn't installed, a
     * package named com.android.healthconnect.phone.<id>, so it has no name to look up.
     */
    private fun source(origin: String): String =
        if (origin.startsWith(PHONE_ORIGIN)) "this phone" else appName(origin)

    /** Health Connect gives an app only the 30 days before it was first allowed in, unless it asks for more. */
    private fun historyNote(result: MutableMap<String, Any?>, span: HealthPeriod.Span, now: ZonedDateTime) {
        if (span.start.isBefore(now.minusDays(LOOKBACK_DAYS))) {
            val note = "Health Connect lets this app read only about the 30 days before it was first allowed in, so older data may be missing."
            result["note"] = listOfNotNull(result["note"] as String?, note).joinToString(" ")
        }
    }

    private fun period(span: HealthPeriod.Span, now: ZonedDateTime): String {
        val dates = dates(span, now)
        val words = span.words.trim()
        return if (words.isEmpty() || words.equals(dates, ignoreCase = true)) dates else "$words ($dates)"
    }

    /** For the chip: the user's words when short, the day otherwise. */
    private fun short(span: HealthPeriod.Span, now: ZonedDateTime): String {
        val words = span.words.trim()
        return if (words.isNotEmpty() && words.length <= 24) words else dates(span, now)
    }

    companion object {
        /** Each kind of sum's key in the result. */
        private val KEYS = mapOf(
            Total.STEPS to "steps",
            Total.DISTANCE_METERS to "distance_km",
            Total.TOTAL_KCAL to "calories_kcal",
            Total.ACTIVE_KCAL to "active_calories_kcal",
        )

        /** What the phone's own counting writes as, followed by an id. */
        private const val PHONE_ORIGIN = "com.android.healthconnect.phone"

        /** The daily step goal until the user sets their own in Settings → Health data. */
        const val DEFAULT_STEP_GOAL = 6_000

        /** What a step goal may be. */
        val STEP_GOALS = 500..100_000

        /** A step goal as typed in Settings ("8000", "8,000"); null when it isn't one. */
        fun parseStepGoal(typed: String): Int? =
            typed.filter(Char::isDigit).takeIf { it.length in 1..6 }?.toInt()?.takeIf { it in STEP_GOALS }

        /** How far back "is there any of it at all?" looks; Health Connect's own default reach too. */
        const val LOOKBACK_DAYS = 30L
        private const val LATEST_DAYS = 7L
        private const val MAX_LISTED_DAYS = 14
        private const val MAX_NIGHTS = 31
        private const val MAX_WORKOUTS = 10

        /** A reading older than this isn't "now". */
        private val STALE = Duration.ofHours(2)

        /** How long numbers read out keep a web search from carrying them. */
        private const val KEEP_MS = 60 * 60 * 1000L

        /** A sleep ending after 18:00 belongs to the next day's night. */
        private const val NIGHT_CUTOFF_HOURS = 6L

        private val FUTURE = Regex("\\b(tomorrow|next)\\b")
        private val NUMBER = Regex("\\d+(?:[.,]\\d+)*")

        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        /** A sum as the model gets it: whole steps and kcal, km to two places. */
        internal fun value(kind: Total, amount: Double): Number = when (kind) {
            Total.DISTANCE_METERS -> Math.round(amount / 10.0) / 100.0
            else -> amount.roundToLong()
        }

        /** A sum as the chip shows it: "8,423", "6.24 km", "2,150 kcal". */
        internal fun shown(kind: Total, amount: Double): String = when (kind) {
            Total.STEPS -> String.format(Locale.ENGLISH, "%,d", amount.roundToLong())
            Total.DISTANCE_METERS -> if (amount < 1000) "${amount.roundToLong()} m" else String.format(Locale.ENGLISH, "%.2f km", amount / 1000)
            Total.TOTAL_KCAL, Total.ACTIVE_KCAL -> String.format(Locale.ENGLISH, "%,d kcal", amount.roundToLong()) +
                if (kind == Total.ACTIVE_KCAL) " active" else ""
        }

        /** "7 h 5 min", "45 min". */
        internal fun duration(d: Duration): String {
            val minutes = d.toMinutes()
            return when {
                minutes < 60 -> "$minutes min"
                minutes % 60 == 0L -> "${minutes / 60} h"
                else -> "${minutes / 60} h ${minutes % 60} min"
            }
        }

        /** "today 10:31", "yesterday 21:14", "Tue 3 Nov 08:12". */
        internal fun moment(at: Instant, now: ZonedDateTime): String {
            val time = at.atZone(now.zone)
            return when (time.toLocalDate()) {
                now.toLocalDate() -> "today ${TIME.format(time)}"
                now.toLocalDate().minusDays(1) -> "yesterday ${TIME.format(time)}"
                else -> DAY_TIME.format(time)
            }
        }

        /** The night woken from on [wakeDay]: "Wed 4 to Thu 5 Nov". */
        internal fun nightName(wakeDay: LocalDate): String {
            val before = wakeDay.minusDays(1)
            val first = if (before.month == wakeDay.month) DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH).format(before) else DAY.format(before)
            return "$first to ${DAY.format(wakeDay)}"
        }

        /** The time [span] covers, in dates: "Thu 5 Nov, up to 10:42", "Mon 2 Nov to Sun 8 Nov", "Thu 5 Nov 05:00 to 12:00". */
        internal fun dates(span: HealthPeriod.Span, now: ZonedDateTime): String {
            val start = span.start
            val end = span.end
            val fromMidnight = start == start.toLocalDate().atStartOfDay(start.zone)
            val toMidnight = end == end.toLocalDate().atStartOfDay(end.zone)
            val untilNow = end == now
            val lastDay = (if (toMidnight) end.minusDays(1) else end).toLocalDate()
            return when {
                fromMidnight && (toMidnight || untilNow) && start.toLocalDate() == lastDay ->
                    DAY.format(start) + if (untilNow) ", up to ${TIME.format(end)}" else ""
                fromMidnight && (toMidnight || untilNow) ->
                    "${DAY.format(start)} to ${DAY.format(lastDay)}" + if (untilNow) ", up to ${TIME.format(end)}" else ""
                start.toLocalDate() == end.toLocalDate() -> "${DAY.format(start)} ${TIME.format(start)} to ${TIME.format(end)}"
                else -> "${DAY_TIME.format(start)} to ${DAY_TIME.format(end)}"
            }
        }

        /** Heart rate over a time, in words. */
        internal fun summary(heart: HealthSource.HeartRate): String =
            "average ${heart.average}, lowest ${heart.lowest}, highest ${heart.highest} bpm, from ${heart.readings} readings"

        /** A night's stages, longest kind of sleep first, awake last. */
        internal fun stages(stages: Map<Stage, Duration>): String =
            stages.entries
                .sortedWith(compareBy<Map.Entry<Stage, Duration>> { it.key == Stage.AWAKE || it.key == Stage.OUT_OF_BED }.thenByDescending { it.value })
                .joinToString(", ") { (stage, d) -> "${STAGE_WORDS.getValue(stage)} ${duration(d)}" }

        private val STAGE_WORDS = mapOf(
            Stage.DEEP to "deep", Stage.LIGHT to "light", Stage.REM to "REM", Stage.ASLEEP to "asleep (no stage)",
            Stage.AWAKE to "awake", Stage.OUT_OF_BED to "out of bed", Stage.UNKNOWN to "unknown",
        )

        private val ASLEEP = setOf(Stage.ASLEEP, Stage.LIGHT, Stage.DEEP, Stage.REM)

        /** One night's sleep: every session woken from on [wakeDay], naps too. */
        internal data class Night(
            val wakeDay: LocalDate,
            val asleep: Duration,
            val inBed: Duration,
            val bedtime: Instant,
            val woke: Instant,
            val stages: Map<Stage, Duration>,
            val origins: Set<String>,
            val sessions: Int,
        )

        /**
         * [sessions] as nights, oldest first. Two apps recording the same sleep (a watch's app and
         * the phone's) overlap: only the longer is kept. Time asleep is the sleep stages' when an
         * app records stages (Fitbit does), else the whole session.
         */
        internal fun nights(sessions: List<HealthSource.SleepSession>, zone: ZoneId): List<Night> {
            val kept = mutableListOf<HealthSource.SleepSession>()
            for (s in sessions.sortedByDescending { Duration.between(it.start, it.end) }) {
                if (kept.none { it.start < s.end && s.start < it.end }) kept += s
            }
            return kept.groupBy { it.end.atZone(zone).plusHours(NIGHT_CUTOFF_HOURS).toLocalDate() }
                .map { (day, group) ->
                    val main = group.maxBy { Duration.between(it.start, it.end) }
                    val stages = group.flatMap { it.stages }
                        .filter { it.stage != Stage.UNKNOWN }
                        .groupBy { it.stage }
                        .mapValues { (_, spans) -> spans.map { Duration.between(it.start, it.end) }.reduce(Duration::plus) }
                    val asleep = group.map { s ->
                        val staged = s.stages.filter { it.stage in ASLEEP }
                        if (staged.isEmpty()) Duration.between(s.start, s.end) else staged.map { Duration.between(it.start, it.end) }.reduce(Duration::plus)
                    }.reduce(Duration::plus)
                    Night(
                        wakeDay = day,
                        asleep = asleep,
                        inBed = group.map { Duration.between(it.start, it.end) }.reduce(Duration::plus),
                        bedtime = main.start,
                        woke = main.end,
                        // A night recorded without stages, or with only "asleep", has none worth listing.
                        stages = stages.takeIf { s -> s.keys.any { it in setOf(Stage.LIGHT, Stage.DEEP, Stage.REM) } }.orEmpty(),
                        origins = group.map { it.origin }.toSet(),
                        sessions = group.size,
                    )
                }
                .sortedBy { it.wakeDay }
        }

        /** The same workout recorded by two apps, kept once. */
        internal fun distinctWorkouts(workouts: List<HealthSource.Workout>): List<HealthSource.Workout> {
            val kept = mutableListOf<HealthSource.Workout>()
            for (w in workouts.sortedBy { it.start }) {
                val twin = kept.any { k ->
                    k.type == w.type && k.origin != w.origin && k.start < w.end && w.start < k.end
                }
                if (!twin) kept += w
            }
            return kept
        }

        /** The numbers a reading puts in front of the model, as a search query would spell them. */
        private fun numberWords(value: Number): Set<String> = buildSet {
            add(if (value is Double && value % 1.0 != 0.0) value.toString() else value.toLong().toString())
            if (value is Double) add(String.format(Locale.ENGLISH, "%.1f", value))
        }

        private fun numberWords(heart: HealthSource.HeartRate): Set<String> =
            setOf(heart.average, heart.lowest, heart.highest).map { it.toString() }.toSet()

        private fun numberWords(d: Duration): Set<String> =
            setOf((d.toMinutes() / 60).toString(), (d.toMinutes() % 60).toString(), d.toMinutes().toString()).filter { it.length >= 2 }.toSet()

        private fun numberWords(night: Night): Set<String> = numberWords(night.asleep) + numberWords(night.inBed) +
            night.stages.values.flatMap { numberWords(it) }
    }
}
