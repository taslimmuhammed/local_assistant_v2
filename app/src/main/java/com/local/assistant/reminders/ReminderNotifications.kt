package com.local.assistant.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.local.assistant.MainActivity
import com.local.assistant.R
import com.local.assistant.memory.db.EventEntity
import com.local.assistant.memory.db.TaskEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The reminder notification: the task's title, with Done and Snooze 1 h. */
object ReminderNotifications {

    /**
     * A channel's sound cannot be changed once it exists, so the musical one is a new channel;
     * the first, which played the plain notification ding, is removed.
     */
    private const val CHANNEL = "reminder_alerts"
    private const val FIRST_CHANNEL = "reminders"

    /**
     * Reminders play the phone's alarm tone — music, rather than a ding that is easy to miss —
     * on the notification stream, so silent and vibrate mode are still respected. The user can
     * pick another tone for the channel in the system's notification settings.
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(FIRST_CHANNEL)
        val tone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val channel = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders and event alerts you asked the assistant for"
            setSound(
                tone,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
        }
        manager.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, task: TaskEntity) {
        if (!canPost(context)) return
        val id = task.id.toInt()
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(task.title)
            .setContentText("Reminder")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .apply { task.dueAt?.let { setWhen(it).setShowWhen(true) } }
            .addAction(0, "Done", action(context, task.id, ReminderActionReceiver.ACTION_DONE))
            .addAction(0, "Snooze 1 h", action(context, task.id, ReminderActionReceiver.ACTION_SNOOZE))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission withdrawn since the check; nothing to show it with.
        }
    }

    /** "Dentist" — "In 30 minutes · 5:00 PM", or "Today" for an all-day event. */
    fun showEvent(context: Context, event: EventEntity, startsAt: Long) {
        if (!canPost(context)) return
        val id = EVENT_IDS + event.id.toInt()
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val time = Instant.ofEpochMilli(startsAt).atZone(ZoneId.systemDefault())
        val text = if (event.allDay) "Today" else "In 30 minutes · ${TIME.format(time)}"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(event.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setWhen(startsAt)
            .setShowWhen(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission withdrawn since the check.
        }
    }

    /** Event notifications are numbered apart from task ones. */
    private const val EVENT_IDS = 1 shl 30
    private val TIME = DateTimeFormatter.ofPattern("h:mm a")

    fun cancel(context: Context, taskId: Long) {
        NotificationManagerCompat.from(context).cancel(taskId.toInt())
    }

    fun cancelEvent(context: Context, eventId: Long) {
        NotificationManagerCompat.from(context).cancel(EVENT_IDS + eventId.toInt())
    }

    private fun action(context: Context, taskId: Long, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        taskId.toInt(),
        Intent(context, ReminderActionReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
