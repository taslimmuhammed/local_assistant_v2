package com.local.assistant.health

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.local.assistant.health.HealthSource.Amount
import com.local.assistant.health.HealthSource.Stage
import com.local.assistant.health.HealthSource.Total
import com.local.assistant.memory.tools.ToolError
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * [HealthSource] on Health Connect, Android's own store for health data: built into Android 14
 * and later, an app from the Play Store before. Sums go through its aggregation, which counts
 * steps a watch and the phone both recorded once.
 *
 * Nothing it reads is logged: a failure is logged by its kind only.
 */
class HealthConnectSource(private val context: Context) : HealthSource {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    override fun availability(): HealthSource.Availability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthSource.Availability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthSource.Availability.NEEDS_UPDATE
        else -> HealthSource.Availability.NOT_INSTALLED
    }

    override suspend fun granted(): Set<String> = guarded { client.permissionController.getGrantedPermissions() }

    override suspend fun total(total: Total, start: Instant, end: Instant): Amount? = guarded {
        val metric = metricOf(total)
        val result = client.aggregate(AggregateRequest(setOf(metric), TimeRangeFilter.between(start, end)))
        amount(total, result)
    }

    override suspend fun daily(total: Total, days: List<LocalDate>, zone: ZoneId): Map<LocalDate, Amount> = guarded {
        if (days.isEmpty()) return@guarded emptyMap()
        val metric = metricOf(total)
        client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(metric),
                timeRangeFilter = TimeRangeFilter.between(days.first().atStartOfDay(), days.last().plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        ).mapNotNull { group -> amount(total, group.result)?.let { group.startTime.toLocalDate() to it } }.toMap()
    }

    override suspend fun heartRate(start: Instant, end: Instant): HealthSource.HeartRate? = guarded {
        val result = client.aggregate(
            AggregateRequest(
                setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX, HeartRateRecord.MEASUREMENTS_COUNT),
                TimeRangeFilter.between(start, end),
            ),
        )
        val count = result[HeartRateRecord.MEASUREMENTS_COUNT] ?: 0L
        val average = result[HeartRateRecord.BPM_AVG]
        if (count == 0L || average == null) {
            null
        } else {
            HealthSource.HeartRate(
                average = average,
                lowest = result[HeartRateRecord.BPM_MIN] ?: average,
                highest = result[HeartRateRecord.BPM_MAX] ?: average,
                readings = count,
                origins = result.dataOrigins.map { it.packageName }.toSet(),
            )
        }
    }

    override suspend fun latestHeartRate(start: Instant, end: Instant): HealthSource.Reading? = guarded {
        // Newest records first; a record is a run of samples, so its newest sample in the time.
        val records = client.readRecords(
            ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(start, end), ascendingOrder = false, pageSize = 5),
        ).records
        records.flatMap { record -> record.samples.map { it to record.metadata.dataOrigin.packageName } }
            .filter { (sample, _) -> !sample.time.isBefore(start) && sample.time.isBefore(end) }
            .maxByOrNull { (sample, _) -> sample.time }
            ?.let { (sample, origin) -> HealthSource.Reading(sample.time, sample.beatsPerMinute, origin) }
    }

    override suspend fun restingHeartRate(start: Instant, end: Instant): List<HealthSource.Reading> = guarded {
        all(RestingHeartRateRecord::class, start, end).map { HealthSource.Reading(it.time, it.beatsPerMinute, it.metadata.dataOrigin.packageName) }
    }

    override suspend fun sleep(start: Instant, end: Instant): List<HealthSource.SleepSession> = guarded {
        // Read from a day earlier: a session is found by when it ends, and a long one starts well before.
        all(SleepSessionRecord::class, start.minusSeconds(DAY_SECONDS), end)
            .filter { !it.endTime.isBefore(start) && it.endTime.isBefore(end) }
            .map { record ->
                HealthSource.SleepSession(
                    start = record.startTime,
                    end = record.endTime,
                    stages = record.stages.map { HealthSource.StageSpan(it.startTime, it.endTime, stageOf(it.stage)) },
                    origin = record.metadata.dataOrigin.packageName,
                )
            }
    }

    override suspend fun exercise(start: Instant, end: Instant): List<HealthSource.Workout> = guarded {
        all(ExerciseSessionRecord::class, start, end)
            .filter { !it.startTime.isBefore(start) }
            .map { record ->
                HealthSource.Workout(
                    start = record.startTime,
                    end = record.endTime,
                    type = ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[record.exerciseType]
                        ?.replace('_', ' ')
                        ?.takeIf { it != "other workout" } ?: "workout",
                    title = record.title,
                    origin = record.metadata.dataOrigin.packageName,
                )
            }
    }

    override suspend fun newest(metric: HealthMetric, start: Instant, end: Instant): Instant? = guarded {
        val type: KClass<out Record> = when (metric) {
            HealthMetric.STEPS -> StepsRecord::class
            HealthMetric.DISTANCE -> DistanceRecord::class
            HealthMetric.CALORIES -> TotalCaloriesBurnedRecord::class
            HealthMetric.HEART_RATE -> HeartRateRecord::class
            HealthMetric.RESTING_HEART_RATE -> RestingHeartRateRecord::class
            HealthMetric.SLEEP -> SleepSessionRecord::class
            HealthMetric.EXERCISE -> ExerciseSessionRecord::class
        }
        val newest = newestOf(type, start, end)
        // Calories may be kept as active only.
        if (metric == HealthMetric.CALORIES) listOfNotNull(newest, runCatching { newestOf(ActiveCaloriesBurnedRecord::class, start, end) }.getOrNull()).maxOrNull() else newest
    }

    private suspend fun newestOf(type: KClass<out Record>, start: Instant, end: Instant): Instant? =
        client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(start, end), ascendingOrder = false, pageSize = 1))
            .records.firstOrNull()?.let(::timeOf)

    /** Every record of [type] from [start] to [end], oldest first, page by page. */
    private suspend fun <T : Record> all(type: KClass<T>, start: Instant, end: Instant): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val page = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageSize = PAGE, pageToken = token))
            out += page.records
            token = page.pageToken
        } while (token != null && out.size < MAX_RECORDS)
        return out
    }

    private fun metricOf(total: Total): AggregateMetric<*> = when (total) {
        Total.STEPS -> StepsRecord.COUNT_TOTAL
        Total.DISTANCE_METERS -> DistanceRecord.DISTANCE_TOTAL
        Total.TOTAL_KCAL -> TotalCaloriesBurnedRecord.ENERGY_TOTAL
        Total.ACTIVE_KCAL -> ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
    }

    /**
     * [total] from an aggregation; null when no app recorded any. Total calories are worked out
     * even with nothing recorded (from a resting rate Health Connect assumes), so an amount with
     * no app behind it counts as none.
     */
    private fun amount(total: Total, result: AggregationResult): Amount? {
        val origins = result.dataOrigins.map { it.packageName }.toSet()
        if (origins.isEmpty()) return null
        val value = when (total) {
            Total.STEPS -> result[StepsRecord.COUNT_TOTAL]?.toDouble()
            Total.DISTANCE_METERS -> result[DistanceRecord.DISTANCE_TOTAL]?.inMeters
            Total.TOTAL_KCAL -> result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
            Total.ACTIVE_KCAL -> result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
        } ?: return null
        return Amount(value, origins)
    }

    private fun timeOf(record: Record): Instant? = when (record) {
        is StepsRecord -> record.endTime
        is DistanceRecord -> record.endTime
        is TotalCaloriesBurnedRecord -> record.endTime
        is ActiveCaloriesBurnedRecord -> record.endTime
        is HeartRateRecord -> record.samples.maxOfOrNull { it.time } ?: record.endTime
        is RestingHeartRateRecord -> record.time
        is SleepSessionRecord -> record.endTime
        is ExerciseSessionRecord -> record.endTime
        else -> null
    }

    private fun stageOf(stage: Int): Stage = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> Stage.AWAKE
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> Stage.OUT_OF_BED
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> Stage.ASLEEP
        SleepSessionRecord.STAGE_TYPE_LIGHT -> Stage.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> Stage.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> Stage.REM
        else -> Stage.UNKNOWN
    }

    /**
     * A read, with Health Connect's refusals turned into what the model can tell the user. It
     * refuses reads by an app not in front ("in the background"), and a revoked permission.
     */
    private suspend fun <T> guarded(read: suspend () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        Log.w(TAG, "Health Connect refused a read: ${e.javaClass.simpleName}")
        throw ToolError("Health Connect didn't let the app read that: access may have been turned off. Tell the user they can allow it in this app's Settings, under Health data.")
    } catch (e: IllegalStateException) {
        Log.w(TAG, "Health Connect couldn't be read: ${e.javaClass.simpleName}")
        throw ToolError("Health Connect couldn't be read just now (it only answers while this app is open). Tell the user to try again in a moment.")
    } catch (e: android.os.RemoteException) {
        Log.w(TAG, "Health Connect couldn't be reached: ${e.javaClass.simpleName}")
        throw ToolError("Health Connect couldn't be reached just now. Tell the user to try again in a moment.")
    }

    companion object {
        private const val TAG = "HealthConnect"
        private const val PAGE = 500
        private const val MAX_RECORDS = 5_000
        private const val DAY_SECONDS = 24 * 60 * 60L

        /**
         * Opens Health Connect's settings, where App permissions lists this app and the user
         * turns each kind on or off; Android's page for the app when that can't be opened. The
         * page straight at this app (MANAGE_HEALTH_PERMISSIONS) is for system apps only: started
         * from here, Android refused it and the app closed (5 Nov 2026).
         */
        fun openManageAccess(context: Context) {
            try {
                context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                context.startActivity(appInfoIntent(context))
            } catch (e: SecurityException) {
                context.startActivity(appInfoIntent(context))
            }
        }

        private fun appInfoIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
    }
}
