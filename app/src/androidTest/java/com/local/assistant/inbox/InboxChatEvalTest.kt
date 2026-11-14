package com.local.assistant.inbox

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.data.db.Role
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.ToolCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * check_inbox end to end on the phone: two made-up messages from a made-up sender are kept, asked
 * about through the app's own [ChatSender] in hidden chats, then deleted. The phone's real
 * messages are only counted; a reply or result is written out only when it is about the made-up
 * ones, so nobody's words reach the report.
 *
 *     adb shell am instrument -w -e eval inboxchat -e class com.local.assistant.inbox.InboxChatEvalTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class InboxChatEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer

    private class Case(val asked: String, val wants: String)

    private val cases = listOf(
        Case("what did Zorvath say about the quasarfest?", "Friday"),
        Case("any messages from Zorvath?", "Zorvath"),
        Case("did Zorvath send the hall booking?", "Hall 4"),
    )

    @Test
    fun askedAboutKeptMessages() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval inboxchat", InstrumentationRegistry.getArguments().getString("eval") == "inboxchat")
        report("kept: ${container.inbox.count()} (${container.inbox.count(InboxMessage.Source.SMS)} SMS) from ${container.inbox.apps().size} apps; check_inbox offered: ${container.inboxReady()}")
        assumeTrue("messages are kept, with notification access or SMS", container.inboxReady())
        val now = System.currentTimeMillis()
        val made = listOf(
            message("The quasarfest is on Friday at 7, bring the tickets", now - 3 * HOUR),
            message("Booked Hall 4 for the quasarfest, paid the advance", now - 26 * HOUR),
        )
        container.inbox.keepSms(made, smsApp = null)
        var passed = 0
        try {
            for (case in cases) if (ask(case)) passed++
        } finally {
            container.inbox.deleteApp(PACKAGE)
            report("$passed of ${cases.size} passed")
        }
        assertTrue("$passed of ${cases.size} passed", passed == cases.size)
    }

    private suspend fun ask(case: Case): Boolean {
        var chatId: Long? = null
        try {
            val created = CompletableDeferred<Long>()
            val finished = CompletableDeferred<String>()
            val started = System.currentTimeMillis()
            assertTrue(
                "the sender was busy",
                container.chatSender.send(
                    chatId = null,
                    text = case.asked,
                    attachment = null,
                    owner = this,
                    hidden = true,
                    listener = object : ChatSender.Listener {
                        override fun onChatCreated(chatId: Long) {
                            created.complete(chatId)
                        }

                        override fun onFinished(reply: String, failure: String?) {
                            finished.complete(reply + (failure?.let { " [failed: $it]" } ?: ""))
                        }
                    },
                ),
            )
            chatId = withTimeout(30_000) { created.await() }
            val reply = withTimeout(240_000) { finished.await() }
            val took = System.currentTimeMillis() - started
            val calls = container.chatRepository.messagesFor(chatId).filter { it.role == Role.TOOL }.mapNotNull { ChatToolLog.parse(it.text) }
            val inbox = calls.filter { it.tool == ToolCatalog.CHECK_INBOX }
            val ok = inbox.isNotEmpty() && case.wants in reply
            // Only what is about the made-up messages is written out.
            val shown = calls.joinToString { call ->
                val lines = runCatching { com.google.gson.Gson().fromJson(call.result, Map::class.java)["messages"] as? List<*> }.getOrNull().orEmpty()
                val onlyMadeUp = call.tool == ToolCatalog.CHECK_INBOX && lines.isNotEmpty() && lines.all { "Zorvath" in it.toString() }
                val result = if (onlyMadeUp) call.result.take(400) else "(${lines.size} messages, ${call.result.length} chars, not shown)"
                "${call.tool}(${call.arguments}) -> $result"
            }
            val said = if ("Zorvath" in reply || "quasarfest" in reply.lowercase() || "Hall 4" in reply) reply.take(300).replace('\n', ' ') else "(reply of ${reply.length} chars, not about the test messages)"
            report("${if (ok) "PASS" else "FAIL"} \"${case.asked}\" in $took ms: $shown | $said")
            return ok
        } finally {
            chatId?.let {
                container.chatRepository.deleteChat(it)
                container.conversations.forget(it)
            }
        }
    }

    private fun message(text: String, at: Long) = InboxMessage(
        source = InboxMessage.Source.SMS.name,
        dedupeKey = "la-test|$text",
        packageName = PACKAGE,
        appLabel = "SMS",
        conversationKey = "Zorvath",
        title = "Zorvath",
        sender = "Zorvath",
        fromMe = false,
        text = text,
        at = at,
        keptAt = at,
    )

    private fun report(line: String) {
        Log.i(TAG, line)
        File(context.getExternalFilesDir(null), "inbox-chat-eval.txt").appendText("$line\n")
    }

    private companion object {
        const val TAG = "InboxChatEval"
        /** Not a real app's: what's kept under it is deleted afterwards, and nothing else is. */
        const val PACKAGE = "la.test.inbox"
        const val HOUR = 3_600_000L
    }
}
