package com.local.assistant.memory.tools

import com.local.assistant.location.GeoPoint
import com.local.assistant.memory.db.MemoryStore
import com.local.assistant.memory.db.PlaceDistance
import com.local.assistant.memory.db.PlaceSpot
import com.local.assistant.memory.db.TaskEntity
import com.local.assistant.memory.db.TaskPlace
import com.local.assistant.memory.db.TaskStatus

/**
 * A reminder changed by hand on the Reminders screen: its words, and the time of one that rings
 * at a time, or where a place one's pins are and how close to them it rings ("within 250 m").
 * Its place stays the same place: a new one is a new reminder, asked for in a chat. What can't be
 * done is said to the user, and nothing is half-changed.
 */
class ReminderEdits(
    private val store: MemoryStore,
    private val reminders: ReminderScheduler,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The town nearest a point, from the list in the APK: no lookup online. */
    private val townAt: suspend (GeoPoint) -> String? = { null },
) {

    sealed interface Outcome {
        data class Saved(val task: TaskEntity) : Outcome

        /** Nothing changed, and [why], said to the user. */
        data class Refused(val why: String) : Outcome
    }

    /**
     * [task] with the words [title] and, unless it rings at a place, the time [at] (null for none:
     * a to-do). A place reminder takes [place]'s pins and sizes instead, or rings as it did when
     * that's null.
     */
    suspend fun edit(task: TaskEntity, title: String, at: Long?, place: TaskPlace? = null): Outcome {
        val words = title.trim()
        if (words.isEmpty()) return Outcome.Refused("Say what to be reminded of.")
        val now = clock()
        val open = task.copy(title = words, status = TaskStatus.OPEN, completedAt = null, updatedAt = now)
        val was = task.place
        val edited = if (was != null) {
            val now = place?.takeUnless { it == was }
            if (now == null) {
                open
            } else {
                problem(was, now)?.let { return Outcome.Refused(it) }
                // Its name, and when it last rang, are the place's own. Each pin is matched with the
                // one it was nearest: one of several kept may not be the first.
                val spots = now.spots.map { spot -> relabelled(spot, was.spots.minBy { it.point().metresTo(spot.point()) }) }
                open.copy(place = was.copy(spots = spots, distance = now.distance))
            }
        } else {
            // A time left as it was may have passed (it rang); a new one may not.
            if (at != null && at != task.dueAt && at <= now) {
                return Outcome.Refused("That time has already passed. Pick a later one.")
            }
            // A rule repeats from a time: with no time there is nothing to repeat.
            open.copy(dueAt = at, repeatRule = task.repeatRule.takeIf { at != null })
        }
        store.updateTask(edited)
        ring(edited)
        return Outcome.Saved(edited)
    }

    /** Why [now] can't replace [was], said to the user; null when it can. */
    private fun problem(was: TaskPlace, now: TaskPlace): String? {
        val reach = now.distance
        val before = reach?.beforeArriving == true
        return when {
            now.spots.isEmpty() -> "A place reminder needs a pin. Delete it instead."
            now.spots.size > was.spots.size -> "Pins can be moved or dropped here, not added."
            (reach != null && reach.metres > PlaceDistance.MAX_M) || (!before && now.spots.any { it.radiusM > PlaceDistance.MAX_M }) ->
                "A place reminder can ring at most ${PlaceDistance.MAX_M / 1000} km out."
            (reach != null && reach.metres < PlaceDistance.MIN_RADIUS_M) || now.spots.any { it.radiusM < PlaceDistance.MIN_RADIUS_M } ->
                "Pick at least ${PlaceDistance.MIN_RADIUS_M} m: a smaller circle misses arrivals."
            now.spots.any { it.lat !in -90.0..90.0 || it.lon !in -180.0..180.0 } -> "That pin is off the map."
            else -> null
        }
    }

    /**
     * A pin moved off where it was is no longer on the street it was found on ("Karaparmba Road"),
     * so it's named by the nearest town instead. A nudge onto the right building keeps the street.
     */
    private suspend fun relabelled(spot: PlaceSpot, old: PlaceSpot): PlaceSpot {
        val at = spot.point()
        return spot.copy(label = if (at.metresTo(old.point()) <= SAME_STREET_M) old.label else townAt(at))
    }

    private fun PlaceSpot.point() = GeoPoint(lat, lon)

    /** [task] as it was before an edit: its row, and how it rings. */
    suspend fun putBack(task: TaskEntity) {
        store.updateTask(task)
        ring(task)
    }

    private fun ring(task: TaskEntity) {
        val due = task.dueAt
        when {
            task.status != TaskStatus.OPEN -> reminders.cancel(task.id)
            task.place != null -> reminders.schedulePlace(task.id, task.place)
            due != null && due > clock() -> reminders.schedule(task.id, due)
            else -> reminders.cancel(task.id)
        }
    }

    private companion object {
        /** How far a pin can move and still be on the street it was found on. */
        const val SAME_STREET_M = 300.0
    }
}
