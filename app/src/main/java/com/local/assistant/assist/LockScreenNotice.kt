package com.local.assistant.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.local.assistant.MainActivity
import com.local.assistant.R

/**
 * Left when the power button is held on the lock screen while talking there is turned off: how to
 * turn it on, and what that exposes.
 *
 * Tapping it opens the app's settings, which needs the phone unlocked. There is deliberately no
 * "Turn on" button, which would let whoever holds the locked phone switch it on.
 */
object LockScreenNotice {

    private const val TAG = "LockScreenNotice"
    private const val CHANNEL = "assistant_lock_screen"
    private const val NOTIFICATION_ID = 7002

    const val TITLE = "Talk to the assistant on the lock screen?"
    const val RISK =
        "Anyone holding your phone could then ask it what it remembers about you, saved cards and PINs included, " +
            "and have it call or message your contacts, all without unlocking."

    fun post(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Assistant on the lock screen", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "How to talk to the assistant without unlocking, when you try it with that turned off."
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "It's off, so the assistant opens only after you unlock. " +
            "You can turn on “Use on the lock screen” in the app's Settings → Assistant. $RISK"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_assistant)
            .setContentTitle(TITLE)
            .setContentText("It's off. Tap to turn it on in Settings, and read what that exposes.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            // Nothing private in it, so it can be read where it is needed: on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        // Without the notification permission this shows nothing; the panel says the same.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Could not post the lock-screen notice", it) }
    }
}
