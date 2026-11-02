package com.local.assistant.gallery

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.data.db.Role
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.ToolRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Asking to see photos late in a chat, as on 1 Nov 2026: after a photo question, a photo search
 * and a reply the app held back, "Could you show me the photos of me attending wedding" got "I
 * found 0 photos" with no search, and asked again, "I don't have any photos", while a new chat
 * found 3. The chat's earlier exchanges are stored as they were (so the conversation is rebuilt
 * from them, as it was), then the requests go through the app's own sender with the user's real
 * memory, in hidden chats deleted afterwards. Scored by whether find_photos ran.
 *
 *     adb shell am instrument -w -e eval photorequests [-e rounds 3] -e class com.local.assistant.gallery.PhotoRequestsEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results go to logcat (tag PhotoRequestsEval) and `Android/data/<app>/files/photo-requests-eval.txt`.
 */
@RunWith(AndroidJUnit4::class)
class PhotoRequestsEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val out by lazy { File(context.getExternalFilesDir(null), "photo-requests-eval.txt").apply { writeText("") } }

    /** The chat's first three exchanges, as stored: question, tool call if any, reply (the allotment memo's details left out). */
    private val before = listOf(
        Triple(
            "what was my result in BTech",
            ToolRecord("find_photos", mapOf("query" to "BTech result"), """{"ok":true,"found":17,"answered":"from the best-matching photo, read on its own"}"""),
            "This document is an \"Allotment Memo\" for the Office of the Commissioner for Entrance Examinations, " +
                "Kerala. It does not show a BTech result; it shows an allotment to Computer Sc. & Engineering (CS) at Model Engineering College, Thrikkakkara (MDL).",
        ),
        Triple(
            "Could you show photos of me watching sunset",
            ToolRecord("find_photos", mapOf("person" to "me", "query" to "sunset"), """{"ok":true,"found":3,"people":["taslim"]}"""),
            "I found 3 photos of you watching the sunset.",
        ),
        Triple("Could you show photos of me at everything", null, "Sorry, I didn't actually do that: nothing was sent, set or saved."),
    )

    private val asked = listOf(
        "Could you show me the photos of me attending wedding",
        "Could you show all the photos of wedding",
    )

    @Test
    fun photoRequestsLateInAChatSearch() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Sends real chat turns; run with -e eval photorequests", args.getString("eval") == "photorequests")
        val rounds = args.getString("rounds")?.toInt() ?: 3
        val container = context.appContainer
        var searched = 0
        var total = 0
        repeat(rounds) { round ->
            val chatId = container.chatRepository.createChat(hidden = true)
            try {
                val log = ChatToolLog(container.chatRepository)
                for ((question, call, answer) in before) {
                    container.chatRepository.addMessage(chatId, Role.USER, question)
                    call?.let { log.record(chatId, it) }
                    container.chatRepository.addMessage(chatId, Role.ASSISTANT, answer)
                }
                for (question in asked) {
                    // "-e rebuild each": each request in a conversation rebuilt from what is stored.
                    if (args.getString("rebuild") == "each") container.conversations.forget(chatId)
                    val from = container.chatRepository.messagesFor(chatId).maxOf { it.id }
                    val finished = CompletableDeferred<String>()
                    val started = System.currentTimeMillis()
                    Log.i(TAG, "sending: $question")
                    check(container.chatSender.send(chatId, question, null, this@PhotoRequestsEvalTest, hidden = true, listener = object : ChatSender.Listener {
                        override fun onFinished(reply: String, failure: String?) { finished.complete(reply + (failure?.let { " [failed: $it]" } ?: "")) }
                    })) { "the sender was busy" }
                    val reply = withTimeout(180_000) { finished.await() }
                    val tools = container.chatRepository.messagesFor(chatId).filter { it.role == Role.TOOL && it.id > from }
                        .mapNotNull { ChatToolLog.parse(it.text)?.tool }
                    total++
                    if ("find_photos" in tools) searched++
                    report("round $round | ${if ("find_photos" in tools) "SEARCHED" else "no search"} | %.1f s | tools $tools | $question".format((System.currentTimeMillis() - started) / 1000.0) + " | ${reply.replace("\n", " ").take(200)}")
                }
            } finally {
                container.chatRepository.deleteChat(chatId)
                container.conversations.forget(chatId)
            }
        }
        report("find_photos ran on $searched of $total requests")
    }

    /**
     * Tool calls replayed into rebuilt conversations, beyond photos: one chat through the app's
     * own sender, rebuilt before every message, with tools that only look things up (nothing set,
     * sent or called). Each message should still call its tool, and none fail.
     *
     *     adb shell am instrument -w -e eval replaymix -e class com.local.assistant.gallery.PhotoRequestsEvalTest \
     *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun otherToolsReplayed() = runBlocking<Unit> {
        assumeTrue("Sends real chat turns; run with -e eval replaymix", InstrumentationRegistry.getArguments().getString("eval") == "replaymix")
        val container = context.appContainer
        val asks = listOf(
            "what did I tell you about my CA?" to "search_memory",
            "what's coming up this week?" to "get_upcoming",
            "show me my sunset photos" to "find_photos",
            "what's coming up next week?" to "get_upcoming",
            "show me my beach photos" to "find_photos",
            "what did I tell you about my dentist?" to "search_memory",
        )
        val chatId = container.chatRepository.createChat(hidden = true)
        var right = 0
        try {
            for ((question, tool) in asks) {
                container.conversations.forget(chatId)
                val from = container.chatRepository.messagesFor(chatId).maxOfOrNull { it.id } ?: 0L
                val finished = CompletableDeferred<String>()
                val started = System.currentTimeMillis()
                // The steps the screen is shown while the reply is worked on, in order.
                val steps = java.util.Collections.synchronizedList(ArrayList<String>())
                val watching = launch(kotlinx.coroutines.Dispatchers.Default) {
                    container.chatSender.live.collect { live -> live?.step?.let { if (steps.lastOrNull() != it) steps += it } }
                }
                check(container.chatSender.send(chatId, question, null, this@PhotoRequestsEvalTest, hidden = true, listener = object : ChatSender.Listener {
                    override fun onFinished(reply: String, failure: String?) { finished.complete(reply + (failure?.let { " [failed: $it]" } ?: "")) }
                })) { "the sender was busy" }
                val reply = withTimeout(180_000) { finished.await() }
                watching.cancel()
                report("  steps: ${steps.joinToString(" → ")}")
                val tools = container.chatRepository.messagesFor(chatId).filter { it.role == Role.TOOL && it.id > from }.mapNotNull { ChatToolLog.parse(it.text)?.tool }
                if (tool in tools) right++
                report("${if (tool in tools) "OK  " else "MISS"} | %.1f s | tools $tools | $question".format((System.currentTimeMillis() - started) / 1000.0) +
                    " | ${Regex("\\d(?:[^A-Za-z\\d]{0,4}\\d){4,}").replace(reply.replace("\n", " ").take(160), "[#]")}")
            }
        } finally {
            container.chatRepository.deleteChat(chatId)
            container.conversations.forget(chatId)
        }
        report("its tool on $right of ${asks.size}")
    }

    /**
     * Deletes a chat made by hand while checking the screen: only one titled exactly [title] and
     * begun after [since] (ms), never by title alone, since titles repeat.
     *
     *     adb shell am instrument -w -e eval deletechat -e title "show me my sunset photos" -e since 1793... \
     *         -e class com.local.assistant.gallery.PhotoRequestsEvalTest com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun deleteCheckChat() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in: -e eval deletechat", args.getString("eval") == "deletechat")
        val title = requireNotNull(args.getString("title"))
        val since = requireNotNull(args.getString("since")).toLong()
        val container = context.appContainer
        val matching = container.chatRepository.observeChats().first().filter { it.title == title && it.createdAt >= since }
        report("matching chats: ${matching.map { it.id }}")
        check(matching.size == 1) { "expected exactly one chat, found ${matching.size}" }
        container.chatRepository.deleteChat(matching.single().id)
        container.conversations.forget(matching.single().id)
        report("deleted chat ${matching.single().id}")
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        out.appendText("$line\n")
    }

    private companion object {
        const val TAG = "PhotoRequestsEval"
    }
}
