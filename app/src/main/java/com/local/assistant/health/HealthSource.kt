package com.local.assistant.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The phone's health data, read only: on Android, Health Connect ([HealthConnectSource]). Kept
 * this small, and free of Android types, so [HealthTools] can be tested on the JVM.
 *
 * Every read is of what is already there: nothing is written, and every app's data the user has
 * allowed comes back, with the package of the app that recorded it.
 */
interface HealthSource {

    enum class Availability {
        AVAILABLE,

        /** Older Android without the Health Connect app. */
        NOT_INSTALLED,

        /** Health Connect is there but too old for this app: an update from the Play Store fixes it. */
        NEEDS_UPDATE,
    }

    fun availability(): Availability

    /** The health permissions the user has given the app. */
    suspend fun granted(): Set<String>

    /** A sum over a time, as Health Connect works it out (one app's steps not counted twice beside another's). */
    enum class Total { STEPS, DISTANCE_METERS, TOTAL_KCAL, ACTIVE_KCAL }

    /** [value] summed from what [origins] (app packages) recorded. */
    data class Amount(val value: Double, val origins: Set<String>)

    /** [total] from [start] to [end]; null when nothing was recorded then. */
    suspend fun total(total: Total, start: Instant, end: Instant): Amount?

    /** [total] for each of [days] in [zone] that has any, by day. */
    suspend fun daily(total: Total, days: List<LocalDate>, zone: ZoneId): Map<LocalDate, Amount>

    data class HeartRate(val average: Long, val lowest: Long, val highest: Long, val readings: Long, val origins: Set<String>)

    /** Heart rate from [start] to [end]; null when there's none. */
    suspend fun heartRate(start: Instant, end: Instant): HeartRate?

    /** One reading: heart rate, or a day's resting heart rate. */
    data class Reading(val at: Instant, val bpm: Long, val origin: String)

    /** The newest heart-rate reading from [start] to [end]. */
    suspend fun latestHeartRate(start: Instant, end: Instant): Reading?

    /** Resting heart rate recorded from [start] to [end], oldest first. */
    suspend fun restingHeartRate(start: Instant, end: Instant): List<Reading>

    enum class Stage { AWAKE, OUT_OF_BED, ASLEEP, LIGHT, DEEP, REM, UNKNOWN }

    data class StageSpan(val start: Instant, val end: Instant, val stage: Stage)

    data class SleepSession(val start: Instant, val end: Instant, val stages: List<StageSpan>, val origin: String)

    /** Sleep sessions that end from [start] to [end]. */
    suspend fun sleep(start: Instant, end: Instant): List<SleepSession>

    /** [type] in words ("running", "strength training"); [title] as the app named it, if it did. */
    data class Workout(val start: Instant, val end: Instant, val type: String, val title: String?, val origin: String)

    /** Workouts that start from [start] to [end], oldest first. */
    suspend fun exercise(start: Instant, end: Instant): List<Workout>

    /** When the newest [metric] data from [start] to [end] was recorded; null when there's none. */
    suspend fun newest(metric: HealthMetric, start: Instant, end: Instant): Instant?
}
