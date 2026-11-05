package com.local.assistant.health

import com.local.assistant.health.HealthSource.Amount
import com.local.assistant.health.HealthSource.Stage
import com.local.assistant.health.HealthSource.StageSpan
import com.local.assistant.health.HealthSource.Total
import com.local.assistant.memory.tools.MemoryChip
import com.local.assistant.memory.tools.ToolError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HealthToolsTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    /** Thursday 5 November 2026, 10:42. */
    private var now = ZonedDateTime.of(2026, 11, 5, 10, 42, 0, 0, zone)

    private fun at(d: Int, h: Int, m: Int = 0): Instant = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toInstant()

    private val fitbit = "com.fitbit.FitbitMobile"

    /** Health Connect as a test sets it up: what's allowed, and what each read returns. */
    private inner class FakeSource : HealthSource {
        var availability = HealthSource.Availability.AVAILABLE
        var granted = HealthMetric.ALL_PERMISSIONS
        val totals = mutableMapOf<Total, (Instant, Instant) -> Amount?>()
        val dailies = mutableMapOf<Total, Map<LocalDate, Amount>>()
        var heart: (Instant, Instant) -> HealthSource.HeartRate? = { _, _ -> null }
        var latest: (Instant, Instant) -> HealthSource.Reading? = { _, _ -> null }
        var resting = listOf<HealthSource.Reading>()
        var sessions = listOf<HealthSource.SleepSession>()
        var workouts = listOf<HealthSource.Workout>()
        var newest: Instant? = null
        val reads = mutableListOf<String>()

        override fun availability() = availability
        override suspend fun granted() = granted
        override suspend fun total(total: Total, start: Instant, end: Instant): Amount? {
            reads += "total $total $start $end"
            return totals[total]?.invoke(start, end)
        }
        override suspend fun daily(total: Total, days: List<LocalDate>, zone: ZoneId) = dailies[total].orEmpty().filterKeys { it in days }
        override suspend fun heartRate(start: Instant, end: Instant) = heart(start, end)
        override suspend fun latestHeartRate(start: Instant, end: Instant) = latest(start, end)
        override suspend fun restingHeartRate(start: Instant, end: Instant) = resting.filter { it.at >= start && it.at < end }
        override suspend fun sleep(start: Instant, end: Instant): List<HealthSource.SleepSession> {
            reads += "sleep $start $end"
            return sessions.filter { it.end >= start && it.end < end }
        }
        override suspend fun exercise(start: Instant, end: Instant) = workouts.filter { it.start >= start && it.start < end }
        override suspend fun newest(metric: HealthMetric, start: Instant, end: Instant) = newest
    }

    private val source = FakeSource()
    private val asked = mutableListOf<Set<String>>()
    private var allowOnAsk = true
    private var stepGoal = HealthTools.DEFAULT_STEP_GOAL

    private val tools = HealthTools(
        source,
        requestAccess = { permissions ->
            asked += permissions
            if (allowOnAsk) source.granted = source.granted + permissions
            allowOnAsk
        },
        appName = { if (it == fitbit) "Fitbit" else it },
        stepGoal = { stepGoal },
        now = { now },
    )

    private fun read(metric: String?, whenText: String? = null) = runBlocking { tools.read(metric, whenText) }

    private fun error(metric: String?, whenText: String? = null): String = try {
        read(metric, whenText)
        fail("expected a ToolError")
        ""
    } catch (e: ToolError) {
        e.message.orEmpty()
    }

    // ---- Steps, distance, calories ----

    @Test
    fun `steps today come with the time they cover, the app, and a chip`() {
        source.totals[Total.STEPS] = { start, end ->
            assertEquals(at(5, 0), start)
            assertEquals(now.toInstant(), end)
            Amount(8423.0, setOf(fitbit))
        }
        val done = read("steps", "today")
        assertEquals(true, done.result["ok"])
        assertEquals(8423L, done.result["steps"])
        assertEquals("today (Thu 5 Nov, up to 10:42)", done.result["period"])
        assertEquals("Fitbit", done.result["from"])
        assertEquals(MemoryChip.Kind.HEALTH, done.chip!!.kind)
        assertEquals("Steps", done.chip!!.label)
        assertEquals("8,423 of 6,000 · today", done.chip!!.detail)
    }

    // ---- The step goal ----

    @Test
    fun `today's steps say how many are left to the goal`() {
        source.totals[Total.STEPS] = { _, _ -> Amount(2260.0, setOf(fitbit)) }
        val done = read("steps", null)
        assertEquals(6000, done.result["daily_step_goal"])
        assertEquals(false, done.result["step_goal_reached"])
        assertEquals(3740L, done.result["steps_left_to_goal"])
        assertEquals("2,260 of 6,000 · today", done.chip!!.detail)
        // What's left is the user's own reading: a web search with it is refused.
        assertTrue(tools.inQuery("walk 3740 steps"))
    }

    @Test
    fun `past the goal says by how much`() {
        source.totals[Total.STEPS] = { _, _ -> Amount(7100.0, setOf(fitbit)) }
        val done = read("steps", "today")
        assertEquals(true, done.result["step_goal_reached"])
        assertEquals(1100L, done.result["steps_over_goal"])
        assertFalse(done.result.containsKey("steps_left_to_goal"))
    }

    @Test
    fun `a day gone by was short of the user's own goal`() {
        stepGoal = 8000
        source.totals[Total.STEPS] = { _, _ -> Amount(5000.0, setOf(fitbit)) }
        val done = read("steps", "yesterday")
        assertEquals(8000, done.result["daily_step_goal"])
        assertEquals(false, done.result["step_goal_reached"])
        assertEquals(3000L, done.result["steps_short_of_goal"])
        assertFalse(done.result.containsKey("steps_left_to_goal"))
        assertEquals("5,000 of 8,000 · yesterday", done.chip!!.detail)
    }

    @Test
    fun `part of a day has no goal`() {
        source.totals[Total.STEPS] = { _, _ -> Amount(900.0, setOf(fitbit)) }
        val done = read("steps", "this morning")
        assertFalse(done.result.containsKey("daily_step_goal"))
        assertFalse(done.result.containsKey("steps_left_to_goal"))
        assertEquals("900 · this morning", done.chip!!.detail)
    }

    @Test
    fun `several days count the days the goal was reached`() {
        source.totals[Total.STEPS] = { _, _ -> Amount(14000.0, setOf(fitbit)) }
        source.dailies[Total.STEPS] = mapOf(
            LocalDate.of(2026, 11, 3) to Amount(9000.0, setOf(fitbit)),
            LocalDate.of(2026, 11, 4) to Amount(5000.0, setOf(fitbit)),
        )
        val done = read("steps", "this week")
        assertEquals(6000, done.result["daily_step_goal"])
        assertEquals("1 of 4", done.result["days_step_goal_reached"])
        assertFalse(done.result.containsKey("steps_left_to_goal"))
    }

    @Test
    fun `no steps yet today still gives the goal`() {
        val done = read("steps", "today")
        assertEquals("nothing", done.result["found"])
        assertEquals(6000, done.result["daily_step_goal"])
    }

    @Test
    fun `a step goal as typed in settings`() {
        assertEquals(8000, HealthTools.parseStepGoal("8000"))
        assertEquals(8000, HealthTools.parseStepGoal("8,000"))
        assertNull(HealthTools.parseStepGoal(""))
        assertNull(HealthTools.parseStepGoal("50"))
        assertNull(HealthTools.parseStepGoal("1000000"))
    }

    @Test
    fun `steps for other things have no goal`() {
        source.totals[Total.DISTANCE_METERS] = { _, _ -> Amount(3000.0, setOf(fitbit)) }
        assertFalse(read("distance", "today").result.containsKey("daily_step_goal"))
    }

    @Test
    fun `the phone's own step counter is named as the phone`() {
        source.totals[Total.STEPS] = { _, _ -> Amount(260.0, setOf("com.android.healthconnect.phone.ja49b1d9e890136c1b4f3eaa7b035b190")) }
        assertEquals("this phone", read("steps", "today").result["from"])
    }

    @Test
    fun `no time said is today so far`() {
        source.totals[Total.STEPS] = { _, _ -> Amount(1200.0, setOf(fitbit)) }
        val done = read("step count", null)
        assertEquals(1200L, done.result["steps"])
        assertEquals("today (Thu 5 Nov, up to 10:42)", done.result["period"])
    }

    @Test
    fun `several days come day by day, a day without any said so`() {
        source.totals[Total.STEPS] = { _, _ -> Amount(21000.0, setOf(fitbit)) }
        source.dailies[Total.STEPS] = mapOf(
            LocalDate.of(2026, 11, 3) to Amount(9000.0, setOf(fitbit)),
            LocalDate.of(2026, 11, 4) to Amount(12000.0, setOf(fitbit)),
        )
        val done = read("steps", "this week")
        assertEquals(21000L, done.result["steps"])
        assertEquals(10500L, done.result["steps_daily_average"])
        assertEquals("2 of 4", done.result["days_with_data"])
        assertEquals(
            "Mon 2 Nov: nothing recorded, Tue 3 Nov: 9000, Wed 4 Nov: 12000, Thu 5 Nov: nothing recorded",
            done.result["steps_by_day"],
        )
        assertEquals("this week (Mon 2 Nov to Thu 5 Nov, up to 10:42)", done.result["period"])
    }

    @Test
    fun `distance in km and calories as total and active`() {
        source.totals[Total.DISTANCE_METERS] = { _, _ -> Amount(6237.0, setOf(fitbit)) }
        val distance = read("distance", "yesterday")
        assertEquals(6.24, distance.result["distance_km"])
        assertEquals("6.24 km · yesterday", distance.chip!!.detail)

        source.totals[Total.TOTAL_KCAL] = { _, _ -> Amount(2150.4, setOf(fitbit)) }
        source.totals[Total.ACTIVE_KCAL] = { _, _ -> Amount(410.0, setOf("com.other")) }
        val calories = read("calories burned", "today")
        assertEquals(2150L, calories.result["calories_kcal"])
        assertEquals(410L, calories.result["active_calories_kcal"])
        assertEquals("Fitbit, com.other", calories.result["from"])
        assertEquals("2,150 kcal · today", calories.chip!!.detail)
    }

    @Test
    fun `only the calories allowed are read`() {
        source.granted = setOf(HealthMetric.ACTIVE_CALORIES)
        source.totals[Total.TOTAL_KCAL] = { _, _ -> fail("total calories aren't allowed"); null }
        source.totals[Total.ACTIVE_KCAL] = { _, _ -> Amount(300.0, setOf(fitbit)) }
        val done = read("calories")
        assertEquals(300L, done.result["active_calories_kcal"])
        assertFalse(done.result.containsKey("calories_kcal"))
        assertTrue(asked.isEmpty())
    }

    // ---- Nothing there ----

    @Test
    fun `nothing then, but some lately, says when the newest is from`() {
        source.newest = at(3, 21, 14)
        val done = read("steps", "this morning")
        assertEquals("nothing", done.result["found"])
        assertFalse(done.result.containsKey("steps"))
        assertNull(done.chip)
        val note = done.result["note"] as String
        assertTrue(note, "nothing was recorded for this morning (Thu 5 Nov 05:00 to 10:42)" in note)
        assertTrue(note, note.startsWith("Reading was allowed"))
        assertTrue(note, "Tue 3 Nov 21:14" in note)
        assertTrue(note, "never guess" in note)
    }

    @Test
    fun `nothing at all says there is none on the phone`() {
        val done = read("heart_rate", "this morning")
        assertEquals("nothing", done.result["found"])
        val note = done.result["note"] as String
        assertTrue(note, "no app on this phone has saved any heart-rate data" in note)
        // Read as "no access" otherwise.
        assertTrue(note, note.startsWith("Reading was allowed"))
        assertTrue(note, "not that you lack access" in note)
    }

    // ---- Access ----

    @Test
    fun `asks for only what is read, then reads`() {
        source.granted = emptySet()
        source.totals[Total.STEPS] = { _, _ -> Amount(500.0, setOf(fitbit)) }
        val done = read("steps", "today")
        assertEquals(listOf(setOf("android.permission.health.READ_STEPS")), asked)
        assertEquals(500L, done.result["steps"])
    }

    @Test
    fun `not allowed says where to allow it, and reads nothing`() {
        source.granted = setOf("android.permission.health.READ_STEPS")
        allowOnAsk = false
        val message = error("sleep", "last night")
        assertTrue(message, "hasn't allowed this app to read their sleep data" in message)
        assertTrue(message, "Settings, under Health data" in message)
        assertTrue(source.reads.isEmpty())
    }

    @Test
    fun `no health connect, or an old one, is said`() {
        source.availability = HealthSource.Availability.NOT_INSTALLED
        assertTrue("doesn't have Health Connect" in error("steps"))
        source.availability = HealthSource.Availability.NEEDS_UPDATE
        assertTrue("needs an update" in error("steps"))
    }

    @Test
    fun `an unknown metric or time is asked about`() {
        assertTrue("metric must be" in error("blood sugar"))
        assertTrue("hasn't happened yet" in error("steps", "tomorrow"))
        assertTrue("couldn't tell when" in error("steps", "blah"))
    }

    // ---- Heart rate ----

    @Test
    fun `heart rate now is the newest reading, with today's range`() {
        source.latest = { _, _ -> HealthSource.Reading(at(5, 10, 31), 72, fitbit) }
        source.heart = { start, _ ->
            assertEquals(at(5, 0), start)
            HealthSource.HeartRate(78, 61, 112, 412, setOf(fitbit))
        }
        val done = read("heart rate")
        assertEquals(72L, done.result["latest_bpm"])
        assertEquals("today 10:31", done.result["latest_at"])
        assertEquals("average 78, lowest 61, highest 112 bpm, from 412 readings", done.result["today"])
        assertFalse(done.result.containsKey("note"))
        assertEquals("72 bpm · today 10:31", done.chip!!.detail)
    }

    @Test
    fun `an old newest reading is said to be old`() {
        source.latest = { _, _ -> HealthSource.Reading(at(4, 21, 5), 64, fitbit) }
        val done = read("heart_rate", "now")
        assertEquals("yesterday 21:05", done.result["latest_at"])
        assertTrue((done.result["note"] as String).contains("not one from now"))
    }

    @Test
    fun `heart rate over a time is its average and range`() {
        source.heart = { start, end ->
            assertEquals(at(5, 5), start)
            assertEquals(now.toInstant(), end)
            HealthSource.HeartRate(81, 58, 131, 240, setOf(fitbit))
        }
        source.latest = { _, _ -> HealthSource.Reading(at(5, 10, 40), 77, fitbit) }
        val done = read("heart_rate", "this morning")
        assertEquals(81L, done.result["average_bpm"])
        assertEquals(58L, done.result["lowest_bpm"])
        assertEquals(131L, done.result["highest_bpm"])
        assertEquals("77 bpm at today 10:40", done.result["last_reading"])
        assertEquals("avg 81 · 58–131 bpm · this morning", done.chip!!.detail)
    }

    @Test
    fun `resting heart rate is the day's, or each day's over several`() {
        source.resting = listOf(
            HealthSource.Reading(at(3, 0), 60, fitbit),
            HealthSource.Reading(at(4, 0), 62, fitbit),
            HealthSource.Reading(at(5, 0), 58, fitbit),
        )
        val latest = read("resting heart rate")
        assertEquals(58L, latest.result["resting_bpm"])
        assertEquals("Thu 5 Nov", latest.result["on"])

        val week = read("resting_heart_rate", "this week")
        assertEquals(60L, week.result["average_bpm"])
        assertEquals("Tue 3 Nov: 60, Wed 4 Nov: 62, Thu 5 Nov: 58", week.result["by_day"])
    }

    // ---- Sleep ----

    private fun stage(from: Instant, minutes: Long, stage: Stage) = StageSpan(from, from.plusSeconds(minutes * 60), stage)

    /** 23:40 to 07:10 with stages: 40 min awake in all, 6 h 50 min asleep. */
    private val night = run {
        val start = at(4, 23, 40)
        var t = start
        val stages = listOf(
            30L to Stage.LIGHT, 60L to Stage.DEEP, 20L to Stage.AWAKE, 90L to Stage.REM,
            120L to Stage.LIGHT, 20L to Stage.AWAKE, 110L to Stage.LIGHT,
        ).map { (minutes, s) -> stage(t, minutes, s).also { t = it.end } }
        HealthSource.SleepSession(start, t, stages, fitbit)
    }

    @Test
    fun `last night is the sleep woken from this morning, in stages`() {
        source.sessions = listOf(night)
        val done = read("sleep", "last night")
        assertEquals("Wed 4 to Thu 5 Nov", done.result["night"])
        assertEquals("6 h 50 min", done.result["asleep"])
        assertEquals("7 h 30 min", done.result["in_bed"])
        assertEquals("yesterday 23:40", done.result["went_to_sleep"])
        assertEquals("today 07:10", done.result["woke_up"])
        assertEquals("light 4 h 20 min, REM 1 h 30 min, deep 1 h, awake 40 min", done.result["stages"])
        assertEquals("6 h 50 min · Wed 4 to Thu 5 Nov", done.chip!!.detail)
        // Found by when it ended: from 18:00 the evening before to now.
        assertEquals("sleep ${at(4, 18)} ${now.toInstant()}", source.reads.single())
    }

    @Test
    fun `no time said for sleep is last night too`() {
        source.sessions = listOf(night)
        assertEquals("6 h 50 min", read("how did I sleep", null).result["asleep"])
        assertEquals("last night (Wed 4 to Thu 5 Nov)", read("sleep", "today").result["period"])
    }

    @Test
    fun `the same night from two apps counts once, and a nap adds to the day`() {
        val copy = HealthSource.SleepSession(at(4, 23, 50), at(5, 7, 0), emptyList(), "com.other")
        val nap = HealthSource.SleepSession(at(4, 14, 0), at(4, 14, 40), emptyList(), fitbit)
        val nights = HealthTools.nights(listOf(copy, night, nap), zone)
        assertEquals(2, nights.size)
        val thursday = nights.last()
        assertEquals(LocalDate.of(2026, 11, 5), thursday.wakeDay)
        assertEquals(setOf(fitbit), thursday.origins)
        assertEquals(Duration.ofMinutes(410), thursday.asleep)
        // The nap is the day before's: it ended before 18:00 on Wednesday.
        assertEquals(LocalDate.of(2026, 11, 4), nights.first().wakeDay)
        assertEquals(Duration.ofMinutes(40), nights.first().asleep)
    }

    @Test
    fun `several nights are averaged`() {
        val earlier = HealthSource.SleepSession(at(3, 23, 0), at(4, 6, 0), emptyList(), fitbit)
        source.sessions = listOf(earlier, night)
        val done = read("sleep", "this week")
        assertEquals("2 of 4", done.result["nights"])
        assertEquals("6 h 55 min", done.result["average_asleep"])
        assertEquals("Tue 3 to Wed 4 Nov: 7 h, Wed 4 to Thu 5 Nov: 6 h 50 min", done.result["by_night"])
        assertEquals("6 h 50 min, Wed 4 to Thu 5 Nov", done.result["shortest"])
    }

    // ---- Workouts ----

    @Test
    fun `workouts are listed with their type and time, a copy from another app once`() {
        source.workouts = listOf(
            HealthSource.Workout(at(5, 7, 2), at(5, 7, 34), "running", "Morning run", fitbit),
            HealthSource.Workout(at(5, 7, 3), at(5, 7, 33), "running", null, "com.other"),
            HealthSource.Workout(at(3, 18, 0), at(3, 18, 45), "strength training", null, fitbit),
        )
        val done = read("workouts")
        assertEquals(2, done.result["workouts"])
        assertEquals("1 h 17 min", done.result["total_time"])
        assertEquals(
            "Tue 3 Nov 18:00, strength training, 45 min; today 07:02, running, 32 min, \"Morning run\"",
            done.result["list"],
        )
        assertEquals("2 workouts · 1 h 17 min · the last week", done.chip!!.detail)
    }

    // ---- Keeping it on the phone ----

    @Test
    fun `a web search with a number just read out is caught, for an hour`() {
        source.resting = listOf(HealthSource.Reading(at(5, 0), 52, fitbit))
        read("resting_heart_rate")
        assertTrue(tools.inQuery("is a resting heart rate of 52 bpm normal"))
        assertFalse(tools.inQuery("normal resting heart rate for adults"))
        assertFalse(tools.inQuery("weather in Kochi"))
        now = now.plusMinutes(61)
        assertFalse(tools.inQuery("is a resting heart rate of 52 bpm normal"))
    }

    @Test
    fun `steps written with a comma are caught too`() {
        source.totals[Total.STEPS] = { _, _ -> Amount(8423.0, setOf(fitbit)) }
        read("steps")
        assertTrue(tools.inQuery("is 8,423 steps a day enough"))
    }

    // ---- Words ----

    @Test
    fun `metrics are read loosely`() {
        assertEquals(HealthMetric.STEPS, HealthMetric.parse("steps"))
        assertEquals(HealthMetric.STEPS, HealthMetric.parse("step count"))
        assertEquals(HealthMetric.HEART_RATE, HealthMetric.parse("heart-rate"))
        assertEquals(HealthMetric.HEART_RATE, HealthMetric.parse("pulse"))
        assertEquals(HealthMetric.RESTING_HEART_RATE, HealthMetric.parse("resting heart rate"))
        assertEquals(HealthMetric.SLEEP, HealthMetric.parse("how long I slept"))
        assertEquals(HealthMetric.DISTANCE, HealthMetric.parse("how far I walked"))
        assertEquals(HealthMetric.CALORIES, HealthMetric.parse("kcal"))
        assertEquals(HealthMetric.EXERCISE, HealthMetric.parse("workouts"))
        assertNull(HealthMetric.parse("blood sugar"))
        assertNull(HealthMetric.parse(null))
    }

    @Test
    fun `durations and spans in words`() {
        assertEquals("45 min", HealthTools.duration(Duration.ofMinutes(45)))
        assertEquals("7 h", HealthTools.duration(Duration.ofHours(7)))
        assertEquals("7 h 5 min", HealthTools.duration(Duration.ofMinutes(425)))
        assertEquals("850 m", HealthTools.shown(Total.DISTANCE_METERS, 850.0))
        assertEquals("Sat 31 Oct to Sun 1 Nov", HealthTools.nightName(LocalDate.of(2026, 11, 1)))
        val lastNight = HealthPeriod.parse("last night", now)!!
        assertEquals("Wed 4 Nov 21:00 to Thu 5 Nov 06:00", HealthTools.dates(lastNight, now))
        val lastWeek = HealthPeriod.parse("last week", now)!!
        assertEquals("Mon 26 Oct to Sun 1 Nov", HealthTools.dates(lastWeek, now))
    }
}
