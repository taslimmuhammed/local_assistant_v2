package com.local.assistant.gallery

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.data.db.Role
import com.local.assistant.memory.tools.ChatToolLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Photo questions asked one after another in one chat, through the app's own [ChatSender], as
 * someone asks them: whether every turn calls find_photos, or a later one answers from the turns
 * before. Uses the phone's real photos, people and memory; each round's chat is hidden and
 * deleted afterwards.
 *
 *     adb shell am instrument -w -e eval photochat [-e rounds 3] [-e warm <photo search>] -e class com.local.assistant.gallery.PhotoChatEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PhotoChatEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer

    /** "-e questions a|b|c" for other sequences. */
    private val questions = InstrumentationRegistry.getArguments().getString("questions")?.split('|')
        ?: listOf(
            "show me all the photos of me with shiyas",
            "show me all the photos of shiyas",
            "show me all the photos of taslim",
        )

    @Test
    fun everyQuestionCallsTheTool() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in: -e eval photochat", args.getString("eval") == "photochat")
        val rounds = args.getString("rounds")?.toInt() ?: 1
        // "-e warm sunset": a search from the Photos screen first, so the photo model is loaded
        // as it is after using that screen.
        args.getString("warm")?.let { query -> report("warm: \"$query\" -> ${container.galleryIndex.search(query)?.size} found") }
        var asked = 0
        var called = 0
        repeat(rounds) { round ->
            var chatId: Long? = null
            try {
                for (question in questions) {
                    val created = CompletableDeferred<Long>()
                    val finished = CompletableDeferred<String>()
                    val before = chatId?.let { id -> container.chatRepository.messagesFor(id).maxOfOrNull { it.id } } ?: 0L
                    val started = container.chatSender.send(
                        chatId = chatId,
                        text = question,
                        attachment = null,
                        owner = this@PhotoChatEvalTest,
                        hidden = true,
                        listener = object : ChatSender.Listener {
                            override fun onChatCreated(chatId: Long) {
                                created.complete(chatId)
                            }

                            override fun onFinished(reply: String, failure: String?) {
                                finished.complete(reply + (failure?.let { " [failed: $it]" } ?: ""))
                            }
                        },
                    )
                    assertTrue("the sender was busy", started)
                    if (chatId == null) chatId = withTimeout(30_000) { created.await() }
                    val reply = withTimeout(180_000) { finished.await() }
                    val calls = container.chatRepository.messagesFor(chatId!!)
                        .filter { it.role == Role.TOOL && it.id > before }
                        .mapNotNull { ChatToolLog.parse(it.text) }
                        .map { "${it.tool}(${it.arguments}) -> ${it.result.take(60)}" }
                    asked++
                    if (calls.any { it.startsWith("find_photos") }) called++
                    report("round $round ${if (calls.isEmpty()) "NO CALL" else "called "} \"$question\": $calls | ${reply.take(400).replace('\n', ' ')}")
                }
            } finally {
                chatId?.let {
                    container.chatRepository.deleteChat(it)
                    container.conversations.forget(it)
                }
            }
        }
        report("find_photos called on $called of $asked turns")
        assertTrue("$called of $asked turns called find_photos", called == asked)
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        File(context.getExternalFilesDir(null), "photo-chat-eval.txt").appendText("$line\n")
    }

    private companion object {
        const val TAG = "PhotoChatEval"
    }
}
