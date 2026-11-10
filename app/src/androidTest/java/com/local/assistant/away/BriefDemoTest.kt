package com.local.assistant.away

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.local.assistant.R
import com.local.assistant.appContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The brief on the phone without notification access: made-up conversations go in as the
 * listener would put them, the people who wrote get a Reply button that this test answers, and
 * the brief opens and stays until it is closed (or ten minutes pass). Then everything it put in
 * is deleted.
 *
 *     adb shell am instrument -w -e demo brief \
 *         -e class com.local.assistant.away.BriefDemoTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Replies "sent" from the brief land in this test and are logged (tag BriefDemo). The demo lines
 * are marked as already briefed, so if the run is cut short they don't pop up at the next unlock.
 */
@RunWith(AndroidJUnit4::class)
class BriefDemoTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer

    @Test
    fun brief() = runBlocking<Unit> {
        assumeTrue("opt-in: -e demo brief", InstrumentationRegistry.getArguments().getString("demo") == "brief")
        assumeTrue("turn on While you were away first", container.settings.awayBrief)
        val away = container.awayBrief
        val received = CopyOnWriteArrayList<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY)?.toString() ?: return
                received += text
                Log.i(TAG, "Reply arrived for ${intent.getStringExtra(EXTRA_WHO)}: \"$text\"")
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_REPLY), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val now = System.currentTimeMillis()
            val lines = demo(now).flatMap { NotificationLines.read(it, context.packageName, emptySet(), now) }
                .map { it.copy(briefedAt = now) }
            away.capture(lines)
            withTimeout(10_000) { away.observe(newOnly = false).first { !it.isEmpty } }

            // Reply buttons for the people who wrote, answered here instead of in WhatsApp.
            lines.filter { it.kind == Kind.MESSAGE.name }.map { it.conversationKey to it.title }.distinct().forEachIndexed { i, (key, who) ->
                away.replies.remember(key, ReplyActions.Handle(reply = replyButton(i, who), callBack = null, open = null, notificationKey = "demo|$key"))
            }

            context.startActivity(BriefActivity.intent(context, newOnly = false))
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val until = SystemClock.elapsedRealtime() + HOLD_MS
            var seen = false
            while (SystemClock.elapsedRealtime() < until) {
                val open = InstrumentationRegistry.getInstrumentation().runOnMainSyncReturning {
                    Stage.values().filter { it != Stage.DESTROYED }.any { stage -> monitor.getActivitiesInStage(stage).any { it is BriefActivity } }
                }
                if (open) seen = true else if (seen) break
                SystemClock.sleep(500)
            }
            // The tick's moment, and any reply still counting down when it closed.
            SystemClock.sleep(2_000)
            Log.i(TAG, "Brief closed; ${received.size} replies arrived")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            away.forgetAll()
        }
    }

    private fun replyButton(index: Int, who: String): Notification.Action {
        val intent = Intent(ACTION_REPLY).setPackage(context.packageName).putExtra(EXTRA_WHO, who)
        val pending = PendingIntent.getBroadcast(context, index, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification_assistant), "Reply", pending)
            .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    /** Made-up people and apps, a few hours of an evening. */
    private fun demo(now: Long): List<SeenNotification> {
        val min = 60_000L
        fun chat(who: String, vararg said: Pair<Long, String>, group: String? = null, id: Int) = SeenNotification(
            packageName = WHATSAPP, appLabel = "WhatsApp", key = "0|$WHATSAPP|$id|demo|0", id = id, tag = "demo",
            postedAt = now - said.minOf { it.first } * min, category = "msg", ongoing = false, groupSummary = false, importance = 4,
            title = group ?: who, text = said.last().second, bigText = null,
            messages = said.map { (ago, text) ->
                val sender = if (group != null) text.substringBefore(": ") else who
                SeenNotification.Message(sender, if (group != null) text.substringAfter(": ") else text, now - ago * min)
            },
            conversationTitle = group, isGroup = group != null, shortcutId = "demo-${group ?: who}",
        )
        fun app(pkg: String, label: String, title: String, text: String, ago: Long, id: Int) = SeenNotification(
            packageName = pkg, appLabel = label, key = "0|$pkg|$id|demo|0", id = id, tag = "demo", postedAt = now - ago * min,
            category = null, ongoing = false, groupSummary = false, importance = 3, title = title, text = text, bigText = null,
        )
        return listOf(
            chat("Arjun Mehta", 95L to "Hey! Are we still on for dinner tomorrow at 8?", 40L to "Let me know, I'll book the table", id = 101),
            chat("Priya", 25L to "Bhai aaj ki class ke notes bhej dena", 24L to "kal test hai", id = 102),
            SeenNotification(
                packageName = WHATSAPP, appLabel = "WhatsApp", key = "0|$WHATSAPP|103|demo|0", id = 103, tag = "demo",
                postedAt = now - 70 * min, category = "missed_call", ongoing = false, groupSummary = false, importance = 4,
                title = "Amma", text = "Missed voice call", bigText = null,
            ),
            chat("", 120L to "Rohan: who's ordering pizza tonight?", 118L to "Kabir: lol not me", 60L to "Rohan: 😂", group = "Flat 302", id = 104),
            app("in.amazon.mShop.android.shopping", "Amazon", "Delivered", "Your package with Bluetooth earbuds was delivered", 50, 105),
            app("in.swiggy.android", "Swiggy", "Hungry?", "Flat 50% off on your next 3 orders. Order now!", 30, 106),
            app("com.application.zomato", "Zomato", "Weekend treat", "Free delivery on orders above 199 today only", 10, 107),
        )
    }

    private companion object {
        const val TAG = "BriefDemo"
        const val WHATSAPP = "com.whatsapp"
        const val ACTION_REPLY = "com.local.assistant.test.DEMO_REPLY"
        const val EXTRA_WHO = "who"
        const val REPLY_KEY = "demo_reply"
        const val HOLD_MS = 10 * 60_000L
    }
}

private fun <T> android.app.Instrumentation.runOnMainSyncReturning(block: () -> T): T {
    var result: T? = null
    runOnMainSync { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
