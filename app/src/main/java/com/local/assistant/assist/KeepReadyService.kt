package com.local.assistant.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.local.assistant.R

/**
 * Keeps the app's process — and with it the loaded model — alive in the background, so holding
 * the power button gets an answer in about a second instead of after a ~15 s cold start.
 *
 * Android keeps a background app only as long as it likes, and OxygenOS freezes cached apps and
 * kills them early; a foreground service, with its notification, is the sanctioned way to stay.
 * This service only holds the process up. The model itself is loaded by `LlmService` and, while
 * the setting is on, not given back when the app goes to the background.
 */
class KeepReadyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val talk = PendingIntent.getActivity(
            this,
            0,
            Intent(Intent.ACTION_ASSIST).setClass(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_assistant)
            .setContentTitle("Assistant is ready")
            .setContentText("Hold the power button to talk. Tap to talk now.")
            .setContentIntent(talk)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        // Not sticky: if the system still kills the app for memory, it doesn't fight back by
        // restarting it; the next time the app or the overlay opens, this starts again.
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "KeepReadyService"
        private const val CHANNEL = "assistant_ready"
        private const val NOTIFICATION_ID = 7001

        /** Only works while the app is in the foreground, which is when it is called. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, KeepReadyService::class.java)) }
                .onFailure { Log.w(TAG, "Could not start keeping the assistant ready", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepReadyService::class.java))
        }

        private fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "Assistant ready", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while the assistant is kept loaded for the power button. Turn it off in the app's settings."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
