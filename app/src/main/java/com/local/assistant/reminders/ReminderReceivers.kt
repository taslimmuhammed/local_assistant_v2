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
import com.local.assistant.appContainer
import com.local.assistant.memory.db.TaskStatus
import com.local.assistant.memory.tools.EventTimes
import com.local.assistant.memory.tools.TaskOps
import com.local.assistant.memory.tools.WhenDefaults
import kotlinx.coroutines.launch
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
 * Arriving at a place reminder's place, told by Play Services' geofences ([ACTION_FENCE]) or
 * Android's proximity alerts ([ACTION_NEAR]): the reminder rings, alarm-like, and stays open
 * until Done — "remind me next time" ([ACTION_NEXT_TIME]) just puts the notification away.
 */
class PlaceReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val arrived: List<Pair<Long, Int>> = when (intent.action) {
            ACTION_NEXT_TIME -> {
                intent.getLongExtra(ReminderReceiver.EXTRA_TASK_ID, -1L).takeIf { it > 0 }?.let { ReminderNotifications.cancel(context, it) }
                return
            }
            ACTION_FENCE -> {
                val event = GeofencingEvent.fromIntent(intent) ?: return
                if (event.hasError()) {
                    // Location turned off: Play Services has dropped every fence; the next time
                    // the app comes to the front sets them again.
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
            else -> return
        }
        async(context) {
            val container = context.appContainer
            val now = System.currentTimeMillis()
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
                ReminderNotifications.showPlace(context, task, task.place.spots.getOrNull(spot))
                container.memoryRepository.updateTask(task.copy(place = task.place.copy(rangAt = now), updatedAt = now))
            }
        }
    }

    companion object {
        private const val TAG = "PlaceReminders"
        const val ACTION_FENCE = "com.local.assistant.place.FENCE"
        const val ACTION_NEAR = "com.local.assistant.place.NEAR"
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
         * allowed all the time starts once it is.
         */
        suspend fun watchAll(container: com.local.assistant.AppContainer): Int {
            val tasks = container.memoryRepository.openTasks().filter { it.place != null }
            tasks.forEach { container.placeFences.watch(it.id, it.place!!) }
            return tasks.size
        }
    }
}
