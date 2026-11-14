package com.local.assistant.chat

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.llm.VoiceClips
import com.local.assistant.memory.tools.ChatToolLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The chat that went to token soup on 14 Nov 2026, replayed through the app's own [ChatSender] in
 * a hidden chat (deleted after): two typed turns, then voice and typed questions. Each reply is
 * judged by whether it reads as sentences; only the made-up question's reply is written out, so
 * nothing from the phone's photos or messages reaches the report.
 *
 * Clips under the app's external files, `voice-input/known/<id>.wav` (16 kHz mono): `messages`
 * ("Do you have any latest messages?") and `capital` ("What is the capital of Australia?").
 *
 *     adb shell am instrument -w -e eval voicereplay -e class com.local.assistant.chat.VoiceChatReplayTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class VoiceChatReplayTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer
    private val out by lazy { File(context.getExternalFilesDir(null), "voice-chat-replay.txt").apply { writeText("") } }

    /** [clip]: sent as a recording with [text] as its words, as the app sends a voice message. */
    private class Turn(val text: String, val clip: String? = null, val madeUp: Boolean = false)

    private val turns = listOf(
        Turn("find my beach videos"),
        Turn("hello"),
        Turn("do you have any latest messages", clip = "messages"),
        Turn("what is the capital of Australia", clip = "capital", madeUp = true),
        Turn("can you read messages?"),
        Turn("do you have any latest messages", clip = "messages"),
        Turn("what is the capital of Australia", clip = "capital", madeUp = true),
        Turn("What is the capital of France?", madeUp = true),
    )

    @Test
    fun replay() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval voicereplay", InstrumentationRegistry.getArguments().getString("eval") == "voicereplay")
        val clips = File(context.getExternalFilesDir(null), "voice-input/known")
        // "-e speak true": as the chat screen does with Answer out loud on, the natural voice is
        // loaded when a recording starts and reads each voice reply, while the next turn goes.
        val speak = InstrumentationRegistry.getArguments().getString("speak") == "true"
        if (speak) report("natural voice: ${container.speaker.naturalVoiceInUse()}")
        // "-e away true": after the second turn the photo model closes, as memory search lets it
        // go when the app leaves the screen, and the next question opens it again.
        val away = InstrumentationRegistry.getArguments().getString("away") == "true"
        var chatId: Long? = null
        try {
            for ((index, turn) in turns.withIndex()) {
                if (away && index == 2) {
                    container.photoModel.unload()
                    report("photo model closed")
                }
                if (speak && turn.clip != null) {
                    container.speaker.prepare()
                    // The user speaking: the voice loads meanwhile.
                    kotlinx.coroutines.delay(3_000)
                }
                val attachment = turn.clip?.let { id ->
                    // A copy: the app deletes a recording with its message.
                    val copy = File(context.filesDir, "attachments/replay-$id.wav").apply { parentFile?.mkdirs() }
                    File(clips, "$id.wav").copyTo(copy, overwrite = true)
                    ChatSender.Attachment(copy.absolutePath, AttachmentKind.AUDIO, VoiceClips.durationMs(copy))
                }
                val before = chatId?.let { id -> container.chatRepository.messagesFor(id).maxOfOrNull { it.id } } ?: 0L
                val started = System.currentTimeMillis()
                val (chat, reply) = send(chatId, turn.text, attachment)
                chatId = chat
                val tools = container.chatRepository.messagesFor(chat)
                    .filter { it.role == Role.TOOL && it.id > before }
                    .mapNotNull { ChatToolLog.parse(it.text)?.tool }
                val sentences = readsAsSentences(reply)
                report(
                    "${if (sentences) "ok     " else "GARBAGE"} ${if (turn.clip != null) "voice" else "typed"} \"${turn.text}\" in " +
                        "${System.currentTimeMillis() - started} ms, tools $tools, ${reply.length} chars" +
                        if (turn.madeUp || !sentences) ": ${reply.take(if (turn.madeUp) 160 else 60).replace('\n', ' ')}" else "",
                )
                if (speak && turn.clip != null) {
                    reply.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.forEach(container.speaker::speak)
                    kotlinx.coroutines.delay(2_000)
                }
            }
        } finally {
            if (speak) container.speaker.stop()
            chatId?.let {
                container.chatRepository.deleteChat(it)
                container.conversations.forget(it)
            }
        }
    }

    /** Words as a person writes them, not token soup ("840tsetUauthorx205619i aponitimiCrap.M"). */
    private fun readsAsSentences(reply: String): Boolean {
        val words = reply.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        val plain = words.count { it.matches(Regex("[\"'(*-]*[A-Za-z][a-z'’]*[.,!?:;)\"*]*|[\"'(]*\\d[\\d,.:%]*[.,!?)]*")) }
        return plain >= words.size * 0.8
    }

    private suspend fun send(chatId: Long?, text: String, attachment: ChatSender.Attachment?): Pair<Long, String> {
        val created = CompletableDeferred<Long>()
        val finished = CompletableDeferred<String>()
        val started = container.chatSender.send(
            chatId = chatId,
            text = text,
            attachment = attachment,
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
        )
        check(started) { "the sender was busy" }
        val chat = chatId ?: withTimeout(30_000) { created.await() }
        return chat to withTimeout(240_000) { finished.await() }
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        out.appendText("$line\n")
    }

    private companion object {
        const val TAG = "VoiceChatReplay"
    }
}
