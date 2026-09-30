package com.local.assistant.assist

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.data.db.AttachmentKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How long the assistant takes to start answering, measured the way the overlay sends: from the
 * message leaving to the first word of the reply appearing, through the app's own [ChatSender]
 * with the real prompt. Each case is one situation the overlay meets. The engine's own timing
 * lines (LlmService "Loaded … in", ConversationManager "Opened chat … prefilled in") split
 * the totals into their parts.
 *
 *     adb shell am instrument -w -e eval latency \
 *         -e class com.local.assistant.assist.AssistLatencyEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Uses the routing eval's clip 28 ("what's the capital of Kerala?": no tool, a short answer).
 * Test chats are deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class AssistLatencyEvalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer
    private val made = mutableListOf<Long>()

    @Test
    fun firstWord() = runBlocking<Unit> {
        assumeTrue("opt-in: -e eval latency", InstrumentationRegistry.getArguments().getString("eval") == "latency")
        // Pushed with adb to the app's external files, or, where Android won't let the app read
        // what the shell put there, written with run-as into its own files.
        val clip = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
            .map { File(it, "routing-voice/28.wav") }
            .firstOrNull { it.isFile } ?: File(context.filesDir, "routing-voice/28.wav")
        assumeTrue("missing clip ${clip.path}", clip.isFile)
        try {
            // Without any preparation, as the overlay first shipped.
            // The model not in memory: what a cached, trimmed app met on every power button press.
            container.llmService.unload()
            turn("A cold engine, new chat, voice", chatId = null, clip = clip)
            // The model loaded, but every opening is a new chat, so the prompt is read again.
            turn("B warm engine, new chat, voice", chatId = null, clip = clip)
            // Typed: what the audio itself costs.
            turn("C warm engine, new chat, typed", chatId = null, text = "what's the capital of Kerala?")

            // With the next new chat's conversation prepared (ConversationManager.prepareFresh).
            // Kept ready and idle: prepared in the background before the power button is held.
            container.conversations.prepareFresh().join()
            turn("D warm engine, prepared beforehand, voice", chatId = null, clip = clip)
            // Prepared as the overlay opens, while the user talks (~4 s including the pause
            // that ends it), with the model loaded.
            container.conversations.prepareFresh()
            delay(TALKING_MS)
            turn("E warm engine, prepared as it opens, voice after ${TALKING_MS / 1000} s", chatId = null, clip = clip)
            // The same with the model not loaded: keep-ready off, or the app was killed.
            container.llmService.unload()
            container.conversations.prepareFresh()
            delay(TALKING_MS)
            val chat = turn("F cold engine, prepared as it opens, voice after ${TALKING_MS / 1000} s", chatId = null, clip = clip)
            // A follow-up while the overlay is open: the conversation is already there.
            turn("G same chat, follow-up voice", chatId = chat, clip = clip)

            // Right after the model is downloaded or imported: the setup screen waits while it
            // loads and the first chat's prompt is read (AppContainer.prepareFirstChat).
            container.llmService.unload()
            val setupStarted = SystemClock.elapsedRealtime()
            container.prepareFirstChat()
            report("H setup screen after a new model: ${SystemClock.elapsedRealtime() - setupStarted} ms")
            turn("H first message after setup, typed", chatId = null, text = "what's the capital of Kerala?")

            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            report("memory with the model loaded: PSS ${memory.totalPss / 1024} MB, graphics ${memory.getMemoryStat("summary.graphics").toInt() / 1024} MB")
        } finally {
            for (id in made) {
                container.chatRepository.deleteChat(id)
                container.conversations.forget(id)
            }
            container.attachmentStore.pruneExcept(container.chatRepository.attachmentPaths())
        }
    }

    private suspend fun turn(label: String, chatId: Long?, clip: File? = null, text: String = ""): Long = coroutineScope {
        val attachment = clip?.let { source ->
            val copy = container.attachmentStore.newAudioFile().also { source.copyTo(it, overwrite = true) }
            ChatSender.Attachment(copy.absolutePath, AttachmentKind.AUDIO, 2_000)
        }
        val created = CompletableDeferred<Long>()
        val finished = CompletableDeferred<Pair<String, String?>>()
        val started = SystemClock.elapsedRealtime()
        var firstWordMs = -1L
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            container.chatSender.live.first { it != null && it.text.isNotEmpty() }
            firstWordMs = SystemClock.elapsedRealtime() - started
        }
        val sent = container.chatSender.send(
            chatId = chatId,
            text = text,
            attachment = attachment,
            owner = this@AssistLatencyEvalTest,
            listener = object : ChatSender.Listener {
                override fun onChatCreated(chatId: Long) {
                    created.complete(chatId)
                }

                override fun onFinished(reply: String, failure: String?) {
                    finished.complete(reply to failure)
                }
            },
        )
        check(sent) { "the sender was busy" }
        val id = chatId ?: withTimeout(30_000) { created.await() }.also { made += it }
        val (reply, failure) = withTimeout(240_000) { finished.await() }
        val totalMs = SystemClock.elapsedRealtime() - started
        watcher.cancel()
        report("$label: first word $firstWordMs ms, whole reply $totalMs ms — ${failure ?: reply.take(80).replace('\n', ' ')}")
        id
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        File(context.getExternalFilesDir(null), "assist-latency.txt").appendText("$line\n")
    }

    private companion object {
        const val TAG = "AssistLatency"

        /** Asking a short question, and the quiet that ends it. */
        const val TALKING_MS = 4_000L
    }
}
