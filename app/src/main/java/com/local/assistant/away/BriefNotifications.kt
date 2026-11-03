package com.local.assistant.away

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.local.assistant.R

/**
 * The brief as a quiet notification, when it can't open by itself: the app isn't the phone's
 * assistant, a call is on, or the user turned the pop-up off. Tapping it opens the brief.
 */
object BriefNotifications {

    private const val CHANNEL = "away_brief"
    private const val ID = 4_201

    fun post(context: Context, brief: Brief) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            BriefActivity.intent(context, newOnly = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val waiting = brief.needs.size
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_assistant)
            .setContentTitle(if (waiting == 1) "While you were away · 1 needs you" else "While you were away · $waiting need you")
            .setContentText(brief.headline ?: "Tap to see who wrote and called.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(brief.headline ?: "Tap to see who wrote and called."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ID, notification) }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL, "While you were away", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Who wrote and called while the phone was locked, when the brief can't open by itself"
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
