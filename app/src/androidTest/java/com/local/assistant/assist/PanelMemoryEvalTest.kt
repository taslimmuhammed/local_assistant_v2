package com.local.assistant.assist

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.chat.ChatSender
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Whether what was just said in the app is known in the power-button panel: a short chat in the
 * app, then the panel's kind of chat (new, hidden) asks about it, and so does a new chat in the
 * app, to tell the panel apart from any new chat. Through the app's own [ChatSender]; every chat
 * it makes is deleted afterwards.
 *
 *     adb shell am instrument -w -e eval panelmemory [-e wait 5] -e class com.local.assistant.assist.PanelMemoryEvalTest#whatWasJustSaidInTheApp \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * [spokenQuestions] asks the user's real memory by voice, as the panel does: 16 kHz mono WAVs
 * pushed to files/panel-voice/ in external storage, one new panel chat each.
 *
 *     adb shell am instrument -w -e eval panelvoice -e class com.local.assistant.assist.PanelMemoryEvalTest#spokenQuestions \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PanelMemoryEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer
    private val made = ArrayList<Long>()

    @Test
    fun whatWasJustSaidInTheApp() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in: -e eval panelmemory", args.getString("eval") == "panelmemory")
        val waitSeconds = args.getString("wait")?.toLong() ?: 5
        try {
            var app: Long? = null
            for (said in SAID) app = ask(app, said, hidden = false).first
            // As someone would: put the phone down a moment, then hold the power button.
            delay(waitSeconds * 1000)
            for ((label, hidden) in listOf("panel" to true, "new app chat" to false)) {
                var chat: Long? = null
                for (question in QUESTIONS) {
                    val (id, reply) = ask(chat, question, hidden)
                    chat = id
                    report("$label: \"$question\" -> ${reply.take(200).replace('\n', ' ')}")
                }
            }
        } finally {
            for (id in made) {
                container.chatRepository.deleteChat(id)
                container.conversations.forget(id)
            }
        }
    }

    @Test
    fun spokenQuestions() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval panelvoice", InstrumentationRegistry.getArguments().getString("eval") == "panelvoice")
        val clips = File(context.getExternalFilesDir(null), "panel-voice").listFiles { f -> f.extension == "wav" }.orEmpty().sortedBy { it.name }
        val attachments = File(context.filesDir, "attachments").apply { mkdirs() }
        try {
            for (clip in clips) {
                // Where the panel's recordings go, so the chat can read it as it reads theirs.
                val copy = File(attachments, "eval-${clip.name}").also { clip.copyTo(it, overwrite = true) }
                val durationMs = (copy.length() - WAV_HEADER) * 1000 / BYTES_PER_SECOND
                val (id, reply) = ask(null, "", hidden = true, attachment = ChatSender.Attachment(copy.path, com.local.assistant.data.db.AttachmentKind.AUDIO, durationMs))
                val calls = container.chatRepository.messagesFor(id)
                    .filter { it.role == com.local.assistant.data.db.Role.TOOL }
                    .mapNotNull { com.local.assistant.memory.tools.ChatToolLog.parse(it.text) }
                    .map { "${it.tool}(${it.arguments}) remembered: ${"remembered" in it.result}" }
                report("${clip.name}: $calls -> ${reply.take(300).replace('\n', ' ')}")
            }
        } finally {
            for (id in made) {
                container.chatRepository.deleteChat(id)
                container.conversations.forget(id)
            }
            attachments.listFiles { f -> f.name.startsWith("eval-") }?.forEach { it.delete() }
        }
    }

    private suspend fun ask(chatId: Long?, text: String, hidden: Boolean, attachment: ChatSender.Attachment? = null): Pair<Long, String> {
        val created = CompletableDeferred<Long>()
        val finished = CompletableDeferred<String>()
        val started = container.chatSender.send(
            chatId = chatId,
            text = text,
            attachment = attachment,
            owner = this,
            hidden = hidden,
            listener = object : ChatSender.Listener {
                override fun onChatCreated(chatId: Long) {
                    created.complete(chatId)
                }

                override fun onFinished(reply: String, failure: String?) {
                    finished.complete(reply + (failure?.let { " [failed: $it]" } ?: ""))
                }
            },
        )
        check(started) { "the sender was busy" }
        val id = chatId ?: withTimeout(30_000) { created.await() }.also { made += it }
        return id to withTimeout(180_000) { finished.await() }
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        File(context.getExternalFilesDir(null), "panel-memory-eval.txt").appendText("$line\n")
    }

    private companion object {
        const val TAG = "PanelMemoryEval"
        const val WAV_HEADER = 44L
        const val BYTES_PER_SECOND = 32_000L

        /** Made up, so nothing of the user's is touched. */
        val SAID = listOf(
            "I'm choosing between two laptops for work, a ThinkPad X1 Carbon and a MacBook Air. Which has the better keyboard?",
            "OK, I've decided, I'll buy the ThinkPad on Friday.",
        )

        val QUESTIONS = listOf(
            "which laptop did I decide on?",
            "when am I buying it?",
        )
    }
}
