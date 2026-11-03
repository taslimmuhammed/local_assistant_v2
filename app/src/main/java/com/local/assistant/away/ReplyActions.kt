package com.local.assistant.away

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The buttons on the notifications behind the brief: a chat's Reply, a missed call's Call back,
 * and the notification itself, which opens the chat.
 *
 * A reply goes out the way a watch or a car sends one: the chat app's own Reply button, with
 * the text filled in where the user would have typed it. So it leaves as the user, from the
 * user's own account, and the app shows it in the chat like any other.
 *
 * A button is a PendingIntent, which can't be written to a database; these live as long as the
 * process does. [MessageListener] fills them in again from the notifications still showing when
 * it reconnects, so after the app was killed only chats whose notification has gone lose theirs.
 */
class ReplyActions {

    data class Handle(
        val reply: Notification.Action?,
        val callBack: Notification.Action?,
        val open: PendingIntent?,
        val notificationKey: String,
    )

    sealed interface Outcome {
        data object Sent : Outcome
        /** The chat's notification had no Reply button, or it has gone with the app's process. */
        data object NoButton : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private val handles = ConcurrentHashMap<String, Handle>()

    private val _version = MutableStateFlow(0)

    /** Goes up whenever a button arrives or leaves, so the brief can show what it can do. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /**
     * Keeps [handle]'s buttons for [conversationKey]. A newer notification without a Reply
     * button (a summary, say) doesn't take away the one already known.
     */
    fun remember(conversationKey: String, handle: Handle) {
        handles.merge(conversationKey, handle) { old, new ->
            Handle(
                reply = new.reply ?: old.reply,
                callBack = new.callBack ?: old.callBack,
                open = new.open ?: old.open,
                notificationKey = new.notificationKey,
            )
        }
        _version.value++
    }

    fun canReply(conversationKey: String): Boolean = handles[conversationKey]?.reply != null

    fun canCallBack(conversationKey: String): Boolean = handles[conversationKey]?.callBack != null

    fun canOpen(conversationKey: String): Boolean = handles[conversationKey]?.open != null

    /** Sends [text] through the chat's own Reply button. */
    fun reply(context: Context, conversationKey: String, text: String): Outcome {
        val action = handles[conversationKey]?.reply ?: return Outcome.NoButton
        return try {
            send(context, action.actionIntent, fill(action, text), foreground = false)
            Outcome.Sent
        } catch (e: PendingIntent.CanceledException) {
            // The chat app took its button back (the chat was opened, the app restarted).
            handles.computeIfPresent(conversationKey) { _, old -> old.copy(reply = null) }
            _version.value++
            Outcome.NoButton
        } catch (e: RuntimeException) {
            Log.w(TAG, "Reply didn't go", e)
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Presses the missed call's own Call back. From a screen in front, which may start a call. */
    fun callBack(context: Context, conversationKey: String): Boolean =
        press(context, handles[conversationKey]?.callBack?.actionIntent)

    /** Opens the chat, as tapping its notification would. */
    fun open(context: Context, conversationKey: String): Boolean = press(context, handles[conversationKey]?.open)

    fun clear() {
        handles.clear()
        _version.value++
    }

    private fun press(context: Context, intent: PendingIntent?): Boolean {
        intent ?: return false
        return runCatching { send(context, intent, Intent(), foreground = true) }
            .onFailure { Log.w(TAG, "Button didn't work", it) }
            .isSuccess
    }

    companion object {
        private const val TAG = "ReplyActions"

        /** Notification.Action.SEMANTIC_ACTION_REPLY and SEMANTIC_ACTION_CALL (API 28). */
        private const val SEMANTIC_REPLY = 1
        private const val SEMANTIC_CALL = 10

        private val CALL_WORDS = Regex("\\bcall(\\s+back)?\\b", RegexOption.IGNORE_CASE)

        /** [notification]'s button for typing an answer, if it has one; a watch's copy counts too. */
        fun replyAction(notification: Notification): Notification.Action? {
            val actions = allActions(notification)
            val typed = actions.filter { action -> action.remoteInputs.orEmpty().any { it.allowFreeFormInput } }
            return typed.firstOrNull { Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.semanticAction == SEMANTIC_REPLY }
                ?: typed.firstOrNull()
        }

        /** [notification]'s "Call back", if it has one: a missed call's, not a chat's video call. */
        fun callBackAction(notification: Notification): Notification.Action? {
            val actions = allActions(notification).filter { it.remoteInputs.isNullOrEmpty() }
            return actions.firstOrNull { Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && it.semanticAction == SEMANTIC_CALL }
                ?: actions.firstOrNull { action ->
                    val title = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
                    CALL_WORDS.containsMatchIn(title) && "video" !in title
                }
        }

        private fun allActions(notification: Notification): List<Notification.Action> =
            notification.actions.orEmpty().toList() + Notification.WearableExtender(notification).actions

        /** The intent a Reply button expects: [text] in each of its free-text fields. */
        internal fun fill(action: Notification.Action, text: String): Intent {
            val inputs = action.remoteInputs.orEmpty()
            val results = Bundle()
            inputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, text) }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            return intent
        }

        /**
         * Sends another app's PendingIntent. [foreground]: it may open a screen, which Android 14+
         * allows only when the sender (a screen of ours, in view) says so.
         */
        private fun send(context: Context, intent: PendingIntent, fillIn: Intent, foreground: Boolean) {
            val options = if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else {
                null
            }
            intent.send(context, 0, fillIn, null, null, null, options)
        }
    }
}
