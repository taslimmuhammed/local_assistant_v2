package com.local.assistant.memory.prompt

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.data.db.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instant commands ([com.local.assistant.memory.tools.InstantCommands]) through the app's own turn,
 * on the phone: a question, the flashlight on and off without the model, then a question about it.
 * The last should carry on in the same conversation (told of the two in a line, not rebuilt) and
 * know the flashlight is off. The flashlight is the one switch a test can flip and put back.
 *
 *     adb shell am instrument -w -e eval instant \
 *         -e class com.local.assistant.memory.prompt.InstantCommandEvalTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Uses a chat of its own and deletes it; writes to files/instant-eval.txt in external storage.
 */
@RunWith(AndroidJUnit4::class)
class InstantCommandEvalTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.appContainer

    private fun report(line: String) {
        android.util.Log.i("InstantEval", line)
        File(instrumentation.targetContext.getExternalFilesDir(null), "instant-eval.txt").appendText(line + "\n")
    }

    @Test
    fun instantCommandsInAChat() = runBlocking {
        assumeTrue("opt-in: -e eval instant", InstrumentationRegistry.getArguments().getString("eval") == "instant")
        check(container.llmBackend.ensureReady())
        report("--- instant commands ${java.util.Date()}")
        val chatId = container.chatRepository.createChat("Instant commands eval")
        try {
            for (text in listOf("what's the capital of Kerala?", "turn on the flashlight", "turn off the flashlight", "is the flashlight on or off now? answer in a few words")) {
                val userId = container.chatRepository.addMessage(chatId, Role.USER, text)
                val timing = TurnTimings.begin("eval")
                val reply = ShownReply()
                val chips = mutableListOf<String>()
                val started = SystemClock.elapsedRealtime()
                var firstWord: Long? = null
                container.turnRunner.run(chatId, userId, text).collect { e ->
                    if (e is TurnEvent.Memory) chips += e.chip.label
                    if (reply.add(e) && firstWord == null) firstWord = SystemClock.elapsedRealtime() - started
                }
                val replyId = container.chatRepository.addMessage(chatId, Role.ASSISTANT, reply.toString())
                container.turnRunner.finish(chatId, userId, replyId, completed = true)
                val conversation = timing.conversation
                TurnTimings.end(timing, "done")
                report("\"$text\": ${conversation.ifEmpty { "?" }}, first word $firstWord ms, chips $chips | ${reply.toString().replace('\n', ' ').take(120)}")
            }
        } finally {
            container.chatRepository.deleteChat(chatId)
            container.conversations.forget(chatId)
        }
        Unit
    }
}
