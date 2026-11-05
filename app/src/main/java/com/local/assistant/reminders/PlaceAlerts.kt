package com.local.assistant.reminders

import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskStatus

/** What decides a place reminder's ringing, apart from Android noticing the arrival. */
object PlaceAlerts {

    /** Android's own cap: an app may watch at most 100 geofences at once. */
    const val MAX_FENCES = 100

    /**
     * After ringing, how long another arrival at the same reminder stays quiet: a fix wandering
     * back and forth across the edge of the circle, or a second branch on the same drive.
     */
    const val QUIET_MS = 30 * 60_000L

    /** Spot [spot] of reminder [taskId], as the geofence is named: "place:42:0". */
    fun fenceId(taskId: Long, spot: Int): String = "$PREFIX$taskId:$spot"

    /** The reminder and spot a geofence named by [fenceId] is for; null for any other name. */
    fun spotOf(fenceId: String): Pair<Long, Int>? {
        val parts = fenceId.removePrefix(PREFIX).takeIf { it != fenceId }?.split(':') ?: return null
        val taskId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val spot = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return taskId to spot
    }

    /** Whether arriving now rings [task]: still open, still a place reminder, and not rung just now. */
    fun shouldRing(task: TaskEntity, now: Long): Boolean {
        val place = task.place ?: return false
        if (task.status != TaskStatus.OPEN) return false
        val rang = place.rangAt ?: return true
        return now - rang >= QUIET_MS
    }

    private const val PREFIX = "place:"
}
