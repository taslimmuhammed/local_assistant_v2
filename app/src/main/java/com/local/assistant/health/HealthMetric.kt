package com.local.assistant.health

import java.util.Locale

private const val READ = "android.permission.health.READ_"

/**
 * What health_data reads, and the Health Connect permissions each needs: only these are asked
 * for, and only when that kind is first asked about (or from Settings → Health data).
 *
 * [key] is what the model sends as metric; [noun] names the data in a sentence ("no heart-rate
 * data"); [label] heads the chip under the reply.
 */
enum class HealthMetric(val key: String, val noun: String, val label: String, val permissions: Set<String>) {
    STEPS("steps", "step", "Steps", setOf(READ + "STEPS")),
    DISTANCE("distance", "distance", "Distance", setOf(READ + "DISTANCE")),
    /** Total burned, and active where an app keeps that instead (Fitbit keeps total, others active). */
    CALORIES("calories", "calorie", "Calories", setOf(READ + "TOTAL_CALORIES_BURNED", READ + "ACTIVE_CALORIES_BURNED")),
    HEART_RATE("heart_rate", "heart-rate", "Heart rate", setOf(READ + "HEART_RATE")),
    RESTING_HEART_RATE("resting_heart_rate", "resting heart-rate", "Resting heart rate", setOf(READ + "RESTING_HEART_RATE")),
    SLEEP("sleep", "sleep", "Sleep", setOf(READ + "SLEEP")),
    EXERCISE("exercise", "workout", "Workouts", setOf(READ + "EXERCISE")),
    ;

    companion object {
        /** Every permission the app may ask for, as the manifest declares them. */
        val ALL_PERMISSIONS: Set<String> = entries.flatMap { it.permissions }.toSet()

        const val TOTAL_CALORIES = READ + "TOTAL_CALORIES_BURNED"
        const val ACTIVE_CALORIES = READ + "ACTIVE_CALORIES_BURNED"

        /**
         * The metric the model meant, taken loosely: it may send the key, the user's word for it
         * ("pulse", "workouts", "walking") or a phrase ("step count"). Null when it names none.
         */
        fun parse(text: String?): HealthMetric? {
            val t = text?.lowercase(Locale.ROOT)?.replace('_', ' ')?.replace('-', ' ')?.trim().orEmpty()
            if (t.isEmpty()) return null
            entries.firstOrNull { it.key == t.replace(' ', '_') }?.let { return it }
            return when {
                "resting" in t -> RESTING_HEART_RATE
                Regex("\\b(heart|pulse|bpm|hr)\\b").containsMatchIn(t) -> HEART_RATE
                Regex("\\b(sleep|slept|sleeping|nap)").containsMatchIn(t) -> SLEEP
                Regex("\\b(calorie|calories|kcal|energy|burn|burned|burnt)\\b").containsMatchIn(t) -> CALORIES
                Regex("\\b(distance|km|kilometres?|kilometers?|miles?|far)\\b").containsMatchIn(t) -> DISTANCE
                Regex("\\b(steps?|walk|walked|walking)\\b").containsMatchIn(t) -> STEPS
                Regex("\\b(exercise|exercises|workouts?|training|run|runs|running|ran|gym|cycling|activity|activities|sessions?)\\b").containsMatchIn(t) -> EXERCISE
                else -> null
            }
        }
    }
}
