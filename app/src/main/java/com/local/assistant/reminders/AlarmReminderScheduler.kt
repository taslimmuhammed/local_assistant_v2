package com.local.assistant.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import com.local.assistant.memory.tools.ReminderScheduler

/**
 * Reminders on AlarmManager. Exact when the user has allowed exact alarms — from Android 14 an
 * app targeting it is not granted that by default — otherwise `setAndAllowWhileIdle`, which still
 * fires in Doze but may be a few minutes late; the chip says so.
 */
class AlarmReminderScheduler(private val context: Context) : ReminderScheduler {

    private val alarms = requireNotNull(context.getSystemService<AlarmManager>())

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /**
     * A notification already showing for this reminder is about a time that no longer holds —
     * it was moved, finished, cancelled or undone — so it goes too, whenever the alarm changes.
     */
    override fun schedule(taskId: Long, dueAt: Long) {
        ReminderNotifications.cancel(context, taskId)
        set(dueAt, pendingIntent(taskId, dueAt))
    }

    override fun cancel(taskId: Long) {
        ReminderNotifications.cancel(context, taskId)
        alarms.cancel(pendingIntent(taskId, dueAt = 0))
    }

    override fun scheduleEvent(eventId: Long, alertAt: Long, startsAt: Long) =
        set(alertAt, eventIntent(eventId, startsAt))

    override fun cancelEvent(eventId: Long) {
        ReminderNotifications.cancelEvent(context, eventId)
        alarms.cancel(eventIntent(eventId, startsAt = 0))
    }

    private fun set(at: Long, intent: PendingIntent) {
        try {
            if (canScheduleExact()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        } catch (e: SecurityException) {
            // The exact-alarm permission can be withdrawn between the check and the call.
            Log.w(TAG, "Exact alarm refused; falling back", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        }
    }

    /** Events go to their own receiver, so an event and a task with the same id never collide. */
    private fun eventIntent(eventId: Long, startsAt: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        eventId.toInt(),
        Intent(context, EventReminderReceiver::class.java)
            .putExtra(EventReminderReceiver.EXTRA_EVENT_ID, eventId)
            .putExtra(EventReminderReceiver.EXTRA_STARTS_AT, startsAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** One alarm per task: the request code is the task, so rescheduling replaces it. */
    private fun pendingIntent(taskId: Long, dueAt: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        taskId.toInt(),
        Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(ReminderReceiver.EXTRA_DUE_AT, dueAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val TAG = "AlarmReminderScheduler"
    }
}
