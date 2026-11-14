package com.local.assistant.chat

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.llm.VoiceClips
import com.local.assistant.memory.tools.ChatToolLog
import com.local.assistant.memory.tools.GarbledText
import com.local.assistant.memory.tools.ToolSteps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The chat that went to token soup on 14 Nov 2026, replayed through the app's own [ChatSender] in
 * a hidden chat (deleted after): two typed turns, then voice and typed questions. Each reply is
 * judged by [GarbledText]; only the made-up questions' replies are written out, so nothing from
 * the phone's photos or messages reaches the report. Each line also says how long the reply's
 * opening was held before it could be read aloud: from its first word on screen to the first
 * that stood.
 *
 * Clips under the app's external files, `voice-input/known/<id>.wav` (16 kHz mono): `messages`
 * ("Do you have any latest messages?") and `capital` ("What is the capital of Australia?").
 *
 *     adb shell am instrument -w -e eval voicereplay [-e speak true] [-e away true] [-e soup 4] \
 *         -e class com.local.assistant.chat.VoiceChatReplayTest \
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

    /** What the screen showed of one reply as it came. */
    private class Watched {
        var firstShown: Long? = null
        var firstStood: Long? = null
        var wordsWhenStood = 0
        var restarted = false
        var soupSayable = false
    }

    @Test
    fun replay() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("opt-in: -e eval voicereplay", args.getString("eval") == "voicereplay")
        val clips = File(context.getExternalFilesDir(null), "voice-input/known")
        // "-e speak true": as the chat screen does with Answer out loud on, the natural voice is
        // loaded when a recording starts and reads each voice reply, while the next turn goes.
        val speak = args.getString("speak") == "true"
        if (speak) report("natural voice: ${container.speaker.naturalVoiceInUse()}")
        // "-e away true": after the second turn the photo model closes, as memory search lets it
        // go when the app leaves the screen, and the next question opens it again.
        val away = args.getString("away") == "true"
        // "-e soup N": turn N's first reply comes out as made-up soup instead of the model's: it
        // should be taken back unspoken, the model loaded again, and the turn answered.
        val soupAt = args.getString("soup")?.toIntOrNull()
        var chatId: Long? = null
        try {
            for ((index, turn) in turns.withIndex()) {
                if (away && index == 2) {
                    container.photoModel.unload()
                    report("photo model closed")
                }
                if (index + 1 == soupAt) container.llmService.soupNextReply = SOUP
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
                val started = SystemClock.elapsedRealtime()
                val watched = Watched()
                val watching = launch {
                    container.chatSender.live.collect { live ->
                        live ?: return@collect
                        val now = SystemClock.elapsedRealtime() - started
                        if (live.step == ToolSteps.RESTARTING) watched.restarted = true
                        if (SOUP_MARK in live.sayable) watched.soupSayable = true
                        if (live.text.isNotBlank() && watched.firstShown == null) watched.firstShown = now
                        if (live.sayable.isNotBlank() && watched.firstStood == null) {
                            watched.firstStood = now
                            watched.wordsWhenStood = live.sayable.split(Regex("\\s+")).count { it.isNotBlank() }
                        }
                    }
                }
                val (chat, reply) = send(chatId, turn.text, attachment)
                watching.cancel()
                chatId = chat
                val messages = container.chatRepository.messagesFor(chat).filter { it.id > before }
                val tools = messages.filter { it.role == Role.TOOL }.mapNotNull { ChatToolLog.parse(it.text)?.tool }
                val stored = messages.lastOrNull { it.role == Role.ASSISTANT }?.text.orEmpty()
                val fine = GarbledText.verdict(reply, final = true) == GarbledText.Verdict.FINE &&
                    GarbledText.verdict(stored, final = true) == GarbledText.Verdict.FINE && SOUP_MARK !in stored
                val held = watched.firstStood?.let { stood -> watched.firstShown?.let { stood - it } }
                report(
                    "${if (fine) "ok     " else "GARBAGE"} ${if (turn.clip != null) "voice" else "typed"} \"${turn.text}\" in " +
                        "${SystemClock.elapsedRealtime() - started} ms, tools $tools, ${reply.length} chars, " +
                        "held ${held ?: "-"} ms (${watched.wordsWhenStood} words)" +
                        (if (index + 1 == soupAt) ", soup: restarted ${watched.restarted}, soup sayable ${watched.soupSayable}, soup stored ${SOUP_MARK in stored}" else "") +
                        if (turn.madeUp || !fine) ": ${reply.take(if (turn.madeUp) 160 else 60).replace('\n', ' ')}" else "",
                )
                if (speak && turn.clip != null) {
                    reply.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }.forEach(container.speaker::speak)
                    kotlinx.coroutines.delay(2_000)
                }
            }
        } finally {
            container.llmService.soupNextReply = null
            if (speak) container.speaker.stop()
            chatId?.let {
                container.chatRepository.deleteChat(it)
                container.conversations.forget(it)
            }
        }
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

        /** Made up in the style of what the phone gave on 14 Nov 2026. */
        const val SOUP_MARK = "840tsetUauthorx205619i"
        const val SOUP = "\"$SOUP_MARK aponitimiCrap.M WMWWCOMXIai /isdevincr 링Tı Microsoft Sound***passou setzuL6yCblue and so on for a while"
    }
}
