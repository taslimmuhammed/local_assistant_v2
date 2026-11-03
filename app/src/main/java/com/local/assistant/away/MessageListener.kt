package com.local.assistant.away

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.local.assistant.appContainer

/**
 * Reads the phone's notifications for the brief, once the user has allowed notification access
 * (Settings → While you were away). Only when the brief is turned on; nothing leaves the phone.
 *
 * It also keeps time for the brief: the screen going off starts the user's time away, the next
 * unlock ends it. Both are only delivered to a receiver registered while running, and the system
 * keeps this service running for as long as access is allowed.
 */
class MessageListener : NotificationListenerService() {

    private var screen: BroadcastReceiver? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        val away = appContainer.awayBrief
        screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> away.onScreenOff()
                    Intent.ACTION_USER_PRESENT -> away.onUserPresent()
                }
            }
        }.also {
            ContextCompat.registerReceiver(
                this,
                it,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        // What's showing now: its lines (already stored ones are skipped) and, after the app was
        // killed, the buttons to answer them.
        if (appContainer.settings.awayBrief) {
            runCatching { activeNotifications }.getOrNull()?.forEach(::take)
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        screen?.let { runCatching { unregisterReceiver(it) } }
        screen = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (!appContainer.settings.awayBrief) return
        take(sbn, rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        if (!appContainer.settings.awayBrief) return
        // Read in its app, tapped, or swiped away: the user has seen it. Removed for other
        // reasons (a snooze, a timeout, the channel turned off) it stays in the brief.
        if (reason in SEEN_REASONS) appContainer.awayBrief.onRemoved(sbn.key)
    }

    private fun take(sbn: StatusBarNotification, rankingMap: RankingMap? = currentRanking) {
        val container = appContainer
        val seen = runCatching { read(sbn, rankingMap) }
            .onFailure { Log.w(TAG, "Couldn't read a notification from ${sbn.packageName}", it) }
            .getOrNull() ?: return
        val lines = NotificationLines.read(seen, packageName, container.settings.awayIgnoredApps, System.currentTimeMillis())
        if (lines.isEmpty()) return
        val notification = sbn.notification
        val reply = ReplyActions.replyAction(notification)
        val callBack = if (lines.first().kind == Kind.CALL.name) ReplyActions.callBackAction(notification) else null
        container.awayBrief.replies.remember(
            lines.first().conversationKey,
            ReplyActions.Handle(reply = reply, callBack = callBack, open = notification.contentIntent, notificationKey = sbn.key),
        )
        container.awayBrief.capture(lines)
    }

    private fun read(sbn: StatusBarNotification, rankingMap: RankingMap?): SeenNotification {
        val notification = sbn.notification
        val extras = notification.extras
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val me = style?.user
        val messages = style?.messages.orEmpty().mapNotNull { message ->
            val text = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val person = message.person
            val mine = person == null ||
                (me?.key != null && person.key == me.key) ||
                (me?.name != null && person.name?.toString() == me.name?.toString())
            SeenNotification.Message(sender = if (mine) null else person?.name?.toString() ?: "Someone", text = text, at = message.timestamp)
        }
        val importance = rankingMap?.let { map ->
            Ranking().takeIf { map.getRanking(sbn.key, it) }?.importance
        }
        return SeenNotification(
            packageName = sbn.packageName,
            appLabel = label(sbn.packageName),
            key = sbn.key,
            id = sbn.id,
            tag = sbn.tag,
            postedAt = sbn.postTime,
            category = notification.category,
            ongoing = notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0,
            groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            importance = importance,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty(),
            messages = messages,
            conversationTitle = style?.conversationTitle?.toString(),
            isGroup = style?.isGroupConversation == true,
            shortcutId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) notification.shortcutId else null,
        )
    }

    /** The app's name as its icon shows it; apps with a launcher icon are visible to this one. */
    private fun label(packageName: String): String =
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrElse { packageName.substringAfterLast('.').replaceFirstChar(Char::titlecase) }

    companion object {
        private const val TAG = "MessageListener"

        /** The system has the listener bound: notification access is allowed. */
        @Volatile
        var connected: Boolean = false
            private set

        private val SEEN_REASONS = setOf(
            REASON_CLICK,
            REASON_CANCEL,
            REASON_CANCEL_ALL,
            REASON_APP_CANCEL,
            REASON_APP_CANCEL_ALL,
        )

        /** Whether the user has allowed this app's notification access. */
        fun allowed(context: Context): Boolean =
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
}
