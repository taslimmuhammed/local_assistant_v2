package com.local.assistant.assist

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.data.db.AttachmentKind
import com.local.assistant.data.db.Role
import com.local.assistant.llm.VoiceClips
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
 * A voice question sent the way the assistant overlay sends it — written down by the phone's
 * recogniser, through the app's own [ChatSender], with the real instructions, profile, memory
 * and tools — must end in the tool call, not in a reply that only says it was done. Read-only
 * tools only, so nothing is set on the phone; each test chat is deleted afterwards.
 *
 * Since 4 Nov 2026 a voice message up to 30 s goes as its recording too (ContextWindow.heard):
 * "eighteen percent of two thousand four hundred and fifty", which the recogniser writes as
 * "200450", must come out 441, from the recording and not corrected back to the words. The same
 * clip with silence to past 30 s goes as the words alone: "200450" then stands.
 *
 * Opt-in, since it loads the model. The clips are the routing eval's (see RoutingEvalTest) and the
 * voice input eval's (see VoiceInputEvalTest); a case whose clip isn't on the phone is skipped:
 *
 *     adb shell am instrument -w -e eval overlay \
 *         -e class com.local.assistant.assist.OverlayToolsEvalTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OverlayToolsEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer

    /** A clip under the app's external files, the tool its request needs ([ANY]: whichever, or none), and what the reply must say. */
    private class Case(val clip: String, val tool: String?, val says: String? = null, val padToMs: Long? = null)

    private val cases = listOf(
        Case("routing-voice/19.wav", "get_upcoming"), // "what's coming up this week?"
        Case("routing-voice/50.wav", "web_lookup"), // "what's the weather in Kochi today?"
        Case("voice-input/known/percent.wav", "calculate", says = "441"),
        Case("voice-input/known/munnar-photos.wav", "find_photos"),
        Case("voice-input/known/capital.wav", null, says = "Canberra"),
        // Past the 30 s limit: the words alone, which say 200450 (a bare sum is answered without the model).
        Case("voice-input/known/percent.wav", ANY, says = "36081", padToMs = 31_000),
    )

    @Test
    fun voiceQuestionsCallTheirTools() = runBlocking {
        assumeTrue("opt-in: -e eval overlay", InstrumentationRegistry.getArguments().getString("eval") == "overlay")
        val files = context.getExternalFilesDir(null)
        val misses = mutableListOf<String>()
        var ran = 0
        for (case in cases) {
            val source = File(files, case.clip)
            if (!source.isFile) {
                report("SKIP ${case.clip}: not on the phone")
                continue
            }
            ran++
            val clip = container.attachmentStore.newAudioFile().also { copy ->
                if (case.padToMs == null) source.copyTo(copy, overwrite = true) else padded(source, copy, case.padToMs)
            }
            // As the overlay sends it: the recogniser's words, the recording and its length.
            val (words, _) = VoiceClips.transcribe(container.transcriber, clip)
            val durationMs = VoiceClips.durationMs(clip)
            val name = "${case.clip}${case.padToMs?.let { " to ${it / 1000} s" }.orEmpty()}"
            val created = CompletableDeferred<Long>()
            val finished = CompletableDeferred<Pair<String, String?>>()
            val started = container.chatSender.send(
                chatId = null,
                text = words.orEmpty(),
                attachment = ChatSender.Attachment(clip.absolutePath, AttachmentKind.AUDIO, durationMs),
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
                val ok = (case.tool == ANY || if (case.tool == null) tools.isEmpty() else case.tool in tools) &&
                    (case.says == null || case.says in reply.replace(",", "")) && !listed && failure == null
                report("${if (ok) "OK  " else "MISS"} $name (${durationMs} ms, heard \"$words\"): tools=$tools listed=$listed reply=${reply.take(160).replace('\n', ' ')}${failure?.let { " failure=$it" } ?: ""}")
                if (!ok) misses += "$name expected ${case.tool} saying ${case.says} and no listing, got $tools, listed=$listed"
            } finally {
                container.chatRepository.deleteChat(chatId)
                container.conversations.forget(chatId)
                container.attachmentStore.delete(clip.absolutePath)
            }
        }
        assumeTrue("no clips on the phone", ran > 0)
        assertTrue(misses.joinToString("; "), misses.isEmpty())
    }

    /** [source] with silence after it, to [toMs] long. */
    private fun padded(source: File, copy: File, toMs: Long) {
        val pcm = VoiceClips.pcmOf(source.readBytes())
        val samples = pcm + ByteArray(maxOf(0, (toMs * 32).toInt() - pcm.size))
        copy.writeBytes(wavHeader(samples.size) + samples)
    }

    /** A 16 kHz mono 16-bit WAV header for [dataBytes] of samples. */
    private fun wavHeader(dataBytes: Int): ByteArray = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataBytes)
    }.array()

    private fun report(line: String) {
        Log.i(TAG, line)
        File(context.getExternalFilesDir(null), "overlay-tools-eval.txt").appendText("$line\n")
    }

    private companion object {
        const val TAG = "OverlayToolsEval"
        const val ANY = "*"
    }
}
