package com.local.assistant.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.LocationResult
import com.local.assistant.appContainer
import com.local.assistant.memory.db.TaskStatus
import com.local.assistant.memory.tools.EventTimes
import com.local.assistant.memory.tools.TaskOps
import com.local.assistant.memory.tools.WhenDefaults
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.time.ZonedDateTime

/** Runs [block] off the main thread while keeping the broadcast alive until it finishes. */
private fun BroadcastReceiver.async(context: Context, block: suspend () -> Unit) {
    val pending = goAsync()
    context.appContainer.appScope.launch {
        try {
            block()
        } catch (e: Exception) {
            Log.w("Reminders", "Reminder work failed", e)
        } finally {
            pending.finish()
        }
    }
}

/** An alarm went off: show the reminder, if it is still due at this time. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L).takeIf { it > 0 } ?: return
        val dueAt = intent.getLongExtra(EXTRA_DUE_AT, -1L)
        async(context) {
            val container = context.appContainer
            val task = container.memoryRepository.task(taskId) ?: return@async
            // Done, cancelled or moved since this alarm was set: stay quiet.
            if (task.status != TaskStatus.OPEN || task.dueAt != dueAt) return@async
            ReminderNotifications.show(context, task)
            // A one-off reminder is over once it has rung: off the lists and the agenda. Snooze
            // brings it back; a repeating one waits for Done, which moves it to its next time.
            if (task.repeatRule == null) {
                container.memoryRepository.updateTask(TaskOps.complete(task, ZonedDateTime.now()))
                container.conversations.prefixMayHaveChanged()
            }
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_DUE_AT = "due_at"
    }
}

/**
 * An event alert went off: say so, if the event still starts when the alert was set for, and set
 * the alert for a repeating event's next occurrence.
 */
class EventReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L).takeIf { it > 0 } ?: return
        val startsAt = intent.getLongExtra(EXTRA_STARTS_AT, -1L)
        async(context) {
            val container = context.appContainer
            val event = container.memoryRepository.event(eventId) ?: return@async
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            // Moved or deleted since: stay quiet. A repeating event's start is its next one.
            if (EventTimes.nextStart(event, now - EventTimes.ALERT_LEAD.toMillis(), zone) == startsAt) {
                ReminderNotifications.showEvent(context, event, startsAt)
            }
            EventTimes.nextAlert(event, now, zone, WhenDefaults().dateOnly)
                ?.let { container.reminderScheduler.scheduleEvent(eventId, it.at, it.startsAt) }
        }
    }

    companion object {
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_STARTS_AT = "starts_at"
    }
}

/** The notification's own buttons. Done moves a repeating reminder on; Snooze puts it off an hour. */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(ReminderReceiver.EXTRA_TASK_ID, -1L).takeIf { it > 0 } ?: return
        val action = intent.action ?: return
        ReminderNotifications.cancel(context, taskId)
        async(context) {
            val container = context.appContainer
            val task = container.memoryRepository.task(taskId) ?: return@async
            val now = ZonedDateTime.now()
            val updated = when (action) {
                ACTION_DONE -> TaskOps.complete(task, now)
                ACTION_SNOOZE -> TaskOps.snooze(task, now)
                else -> return@async
            }
            container.memoryRepository.updateTask(updated)
            val due = updated.dueAt
            if (updated.status == TaskStatus.OPEN && due != null && due > System.currentTimeMillis()) {
                container.reminderScheduler.schedule(taskId, due)
            } else {
                container.reminderScheduler.cancel(taskId)
            }
            // The agenda in the conversation's prefix is now out of date.
            container.conversations.prefixMayHaveChanged()
        }
    }

    companion object {
        const val ACTION_DONE = "com.local.assistant.reminder.DONE"
        const val ACTION_SNOOZE = "com.local.assistant.reminder.SNOOZE"
    }
}

/**
 * Alarms do not survive a reboot, and a clock or time-zone change moves when they should fire.
 * Every open reminder still in the future is set again from the database.
 */
class RescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        async(context) {
            val container = context.appContainer
            val now = System.currentTimeMillis()
            val tasks = container.memoryRepository.openTasks().filter { (it.dueAt ?: 0L) > now }
            tasks.forEach { container.reminderScheduler.schedule(it.id, it.dueAt!!) }
            val zone = ZoneId.systemDefault()
            val alerts = container.memoryRepository.eventsBetween(now, Long.MAX_VALUE).mapNotNull { event ->
                EventTimes.nextAlert(event, now, zone, WhenDefaults().dateOnly)?.let { event.id to it }
            }
            alerts.forEach { (id, alert) -> container.reminderScheduler.scheduleEvent(id, alert.at, alert.startsAt) }
            val places = PlaceReminderReceiver.watchAll(container)
            Log.i("Reminders", "Rescheduled ${tasks.size} reminders, ${alerts.size} event alerts and $places place reminders after ${intent.action}")
        }
    }
}

/**
 * Arriving at a place reminder's place, told by Play Services' geofences ([ACTION_FENCE]), a fix
 * the app asked for itself ([ACTION_LOOK], see [PlaceLookout]) or Android's proximity alerts
 * ([ACTION_NEAR]): the reminder rings as an alarm, and is over, like a one-off reminder at a time. Dismiss ([ACTION_DISMISS]) stops the ringing; "Next time"
 * ([ACTION_NEXT_TIME]) also opens the reminder again, for the next arrival.
 */
class PlaceReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val arrived: List<Pair<Long, Int>> = when (intent.action) {
            ACTION_DISMISS, ACTION_NEXT_TIME -> {
                val taskId = intent.getLongExtra(ReminderReceiver.EXTRA_TASK_ID, -1L).takeIf { it > 0 } ?: return
                ReminderNotifications.cancel(context, taskId)
                if (intent.action == ACTION_NEXT_TIME) async(context) { nextTime(context, taskId) }
                return
            }
            ACTION_FENCE -> {
                val event = GeofencingEvent.fromIntent(intent) ?: return
                if (event.hasError()) {
                    // Location turned off: Play Services has dropped every fence. They are set
                    // again when it comes back on (KeepReadyService), or the app next opens.
                    Log.w(TAG, "Geofence error ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
                    return
                }
                if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
                event.triggeringGeofences.orEmpty().mapNotNull { PlaceAlerts.spotOf(it.requestId) }
            }
            ACTION_NEAR -> {
                if (!intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) return
                listOfNotNull(intent.data?.toString()?.let(PlaceAlerts::spotOf))
            }
            ACTION_LOOK -> {
                val location = LocationResult.extractResult(intent)?.lastLocation ?: return
                async(context) { ring(context, look(context, PlaceLookout.fixOf(location))) }
                return
            }
            else -> return
        }
        async(context) { ring(context, arrived) }
    }

    /**
     * A fix [PlaceLookout] asked for: the reminders it says the user has arrived at, and how soon
     * to look again, from how near the nearest place now is.
     */
    private suspend fun look(context: Context, fix: PlacePace.Fix): List<Pair<Long, Int>> {
        val container = context.appContainer
        val lookout = PlaceLookout(context)
        val tasks = container.memoryRepository.openTasks().filter { it.place != null }
        if (tasks.isEmpty()) {
            lookout.stop()
            return emptyList()
        }
        val arrived = mutableListOf<Pair<Long, Int>>()
        for (task in tasks) {
            val spots = task.place!!.spots
            when (val look = PlacePace.look(fix, spots, lookout.seenOutside(task.id, spots))) {
                is PlacePace.Look.Arrived -> arrived += task.id to look.spot
                PlacePace.Look.Outside -> lookout.markOutside(task.id, spots)
                PlacePace.Look.Neither -> Unit
            }
        }
        val edge = tasks.minOf { PlacePace.toEdge(fix, it.place!!.spots) }
        val still = PlacePace.still(lookout.previous(), fix)
        lookout.remember(fix)
        lookout.keepTo(PlacePace.next(edge, still))
        Log.i(TAG, "Fix ±${fix.accuracyM.toInt()} m${fix.speedMps?.let { ", ${(it * 3.6).toInt()} km/h" } ?: ""}, ${edge.toInt()} m from the nearest circle${if (still) ", still" else ""}")
        return arrived
    }

    /** The user arrived at these reminders' spots: each still open rings, once. */
    private suspend fun ring(context: Context, arrived: List<Pair<Long, Int>>) {
        if (arrived.isEmpty()) return
        // A geofence and a fix of its own can tell the same arrival at once.
        arriving.withLock {
            val container = context.appContainer
            val now = System.currentTimeMillis()
            var rang = false
            for ((taskId, spot) in arrived.distinctBy { it.first }) {
                val task = container.memoryRepository.task(taskId)
                if (task == null || task.status != TaskStatus.OPEN || task.place == null) {
                    // Finished or deleted since: its fences are left over.
                    container.placeFences.stopWatching(taskId)
                    continue
                }
                if (!PlaceAlerts.shouldRing(task, now)) {
                    Log.i(TAG, "Reminder $taskId rang ${(now - (task.place.rangAt ?: now)) / 60_000} min ago; quiet")
                    continue
                }
                Log.i(TAG, "Arrived near ${task.place.name} (spot $spot): ringing reminder $taskId")
                // Over once it rings, as a one-off at a time is: it isn't left on the lists to be
                // removed by hand, and its other places stop watching. "Next time" brings it back.
                val over = PlaceAlerts.rang(task, now)
                container.memoryRepository.updateTask(over)
                container.placeFences.stopWatching(taskId)
                ReminderNotifications.showPlace(context, over, task.place.spots.getOrNull(spot))
                rang = true
            }
            // The agenda in the conversation's prefix is now out of date.
            if (rang) container.conversations.prefixMayHaveChanged()
        }
    }

    /** "Next time": the reminder open again, watching its places for the next arrival. */
    private suspend fun nextTime(context: Context, taskId: Long) {
        val container = context.appContainer
        val task = container.memoryRepository.task(taskId) ?: return
        val reopened = PlaceAlerts.nextTime(task, System.currentTimeMillis()) ?: return
        container.memoryRepository.updateTask(reopened)
        container.reminderScheduler.schedulePlace(taskId, reopened.place!!)
        container.conversations.prefixMayHaveChanged()
        Log.i(TAG, "Reminder $taskId open again, for the next time near ${reopened.place.name}")
    }

    companion object {
        private const val TAG = "PlaceReminders"
        private val arriving = Mutex()
        const val ACTION_FENCE = "com.local.assistant.place.FENCE"
        const val ACTION_NEAR = "com.local.assistant.place.NEAR"
        const val ACTION_LOOK = "com.local.assistant.place.LOOK"
        const val ACTION_DISMISS = "com.local.assistant.place.DISMISS"
        const val ACTION_NEXT_TIME = "com.local.assistant.place.NEXT_TIME"

        /** The one intent every Play Services geofence calls; Play Services adds which fired. */
        fun fenceIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, PlaceReminderReceiver::class.java).setAction(ACTION_FENCE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        /**
         * Watches every open place reminder again: geofences don't survive a reboot, Play
         * Services drops them when location is turned off, and one saved before location was
         * allowed all the time starts once it is. One no longer open (finished or deleted some
         * other way) stops, so it doesn't keep the app awake for nothing.
         */
        suspend fun watchAll(container: com.local.assistant.AppContainer): Int {
            val tasks = container.memoryRepository.openTasks().filter { it.place != null }
            tasks.forEach { container.placeFences.watch(it.id, it.place!!) }
            val open = tasks.map { it.id }.toSet()
            container.placeFences.watched().filter { it !in open }.forEach(container.placeFences::stopWatching)
            return tasks.size
        }
    }
}
