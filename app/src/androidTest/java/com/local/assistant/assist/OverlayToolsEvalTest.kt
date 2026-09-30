package com.local.assistant.assist

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.memory.tools.ChatToolLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A voice question sent the way the assistant overlay sends it — through the app's own
 * [ChatSender], with the real instructions, profile, memory and tools — must end in the tool
 * call, not in a reply that only says it was done. Read-only tools only, so nothing is set on the
 * phone; each test chat is deleted afterwards.
 *
 * Opt-in, since it loads the model. The clips are the routing eval's (see RoutingEvalTest):
 *
 *     adb shell am instrument -w -e eval overlay \
 *         -e class com.local.assistant.assist.OverlayToolsEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OverlayToolsEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer

    /** The routing eval's clip index, and the tool its request needs. */
    private val cases = listOf(
        19 to "get_upcoming", // "what's coming up this week?"
        50 to "web_lookup", // "what's the weather in Kochi today?"
    )

    @Test
    fun voiceQuestionsCallTheirTools() = runBlocking {
        assumeTrue("opt-in: -e eval overlay", InstrumentationRegistry.getArguments().getString("eval") == "overlay")
        val clips = File(context.getExternalFilesDir(null), "routing-voice")
        val misses = mutableListOf<String>()
        for ((index, expected) in cases) {
            val source = File(clips, "$index.wav")
            assumeTrue("missing clip ${source.path}", source.isFile)
            val clip = container.attachmentStore.newAudioFile().also { source.copyTo(it, overwrite = true) }
            val created = CompletableDeferred<Long>()
            val finished = CompletableDeferred<Pair<String, String?>>()
            val started = container.chatSender.send(
                chatId = null,
                text = "",
                attachment = ChatSender.Attachment(clip.absolutePath, AttachmentKind.AUDIO, 2_000),
                owner = this@OverlayToolsEvalTest,
                // As the overlay sends: a chat kept out of the list.
                hidden = true,
                listener = object : ChatSender.Listener {
                    override fun onChatCreated(chatId: Long) {
                        created.complete(chatId)
                    }

                    override fun onFinished(reply: String, failure: String?) {
                        finished.complete(reply to failure)
                    }
                },
            )
            assertTrue("the sender was busy", started)
            val chatId = withTimeout(30_000) { created.await() }
            try {
                val (reply, failure) = withTimeout(180_000) { finished.await() }
                val tools = container.chatRepository.messagesFor(chatId)
                    .filter { it.role == Role.TOOL }
                    .mapNotNull { ChatToolLog.parse(it.text)?.tool }
                val listed = container.chatRepository.observeChats().first().any { it.id == chatId }
                val ok = expected in tools && !listed
                report("${if (ok) "OK  " else "MISS"} clip $index: tools=$tools listed=$listed reply=${reply.take(160).replace('\n', ' ')}${failure?.let { " failure=$it" } ?: ""}")
                if (!ok) misses += "clip $index expected $expected and no listing, got $tools, listed=$listed"
            } finally {
                container.chatRepository.deleteChat(chatId)
                container.conversations.forget(chatId)
                container.attachmentStore.delete(clip.absolutePath)
            }
        }
        assertTrue(misses.joinToString("; "), misses.isEmpty())
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        File(context.getExternalFilesDir(null), "overlay-tools-eval.txt").appendText("$line\n")
    }

    private companion object {
        const val TAG = "OverlayToolsEval"
    }
}
