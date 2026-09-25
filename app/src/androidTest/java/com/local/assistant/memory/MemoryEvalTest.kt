package com.local.assistant.memory

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.local.assistant.appContainer
import com.local.assistant.data.db.MessageEntity
import com.local.assistant.data.db.Role
import com.local.assistant.memory.extract.ExtractedItem
import com.local.assistant.memory.extract.ExtractionInput
import com.local.assistant.memory.extract.ExtractionParser
import com.local.assistant.memory.extract.ExtractionPrompt
import com.local.assistant.memory.prompt.TurnEvent
import com.local.assistant.memory.summary.Summarizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId

/**
 * Phase 4 with the real model on this phone: extraction's JSON and its discipline, summary
 * quality and cost, and compaction end to end through the real conversation manager.
 *
 *     adb shell am instrument -w -e eval memory -e class com.local.assistant.memory.MemoryEvalTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Writes to the app's database only in [compactionFoldsALongChat], in a chat it deletes again.
 */
@RunWith(AndroidJUnit4::class)
class MemoryEvalTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.appContainer
    private val out = StringBuilder()

    private fun report(line: String) {
        out.appendLine(line)
        android.util.Log.i("MemoryEval", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("eval", line) })
        File(instrumentation.targetContext.getExternalFilesDir(null), "memory-eval.txt").appendText(line + "\n")
    }

    private fun enabled() = assumeTrue("opt-in: -e eval memory", InstrumentationRegistry.getArguments().getString("eval") == "memory")

    /** (message, what should come out: type:subject.attribute, or nothing). */
    private val labelled = listOf(
        "my dentist is Dr. Rao near Indiranagar" to setOf("fact:user.dentist"),
        "my sister Anjali just moved to Hyderabad" to setOf("fact:sister.name", "fact:sister.city"),
        "Amma ka birthday 12 March ko hai" to setOf("fact:mother.birthday"),
        "I'm vegetarian, no eggs either" to setOf("fact:user.diet"),
        "please always answer me in short points" to setOf("fact:user.pref.reply_style"),
        "I have a job interview at Infosys on Friday at 3" to setOf("event"),
        "need to submit the GST return by the 20th" to setOf("task"),
        "what's the capital of Australia?" to emptySet(),
        "ugh I'm so tired today" to emptySet(),
        "if I moved to Canada would I need a visa?" to emptySet(),
        "my friend says Bengaluru traffic is the worst in India" to emptySet(),
        "yeah right, I just love waiting in queues" to emptySet(),
        "can you write an email to my landlord about the leak" to emptySet(),
        "mujhe kal subah 7 baje gym jana hai" to setOf("task"),
    )

    @Test
    fun extractionFindsFactsAndLeavesTheRestAlone() = runBlocking {
        enabled()
        val backend = container.llmBackend
        check(backend.ensureReady())
        val now = System.currentTimeMillis()
        val inputs = labelled.mapIndexed { i, (text, _) ->
            ExtractionInput(MessageEntity(id = 1_000L + i, chatId = 1, role = Role.USER, text = text, createdAt = now), null)
        }
        val prompt = ExtractionPrompt.render(inputs, ZoneId.systemDefault())
        val started = System.currentTimeMillis()
        val raw = container.conversations.withModelFree {
            backend.complete(ExtractionPrompt.SYSTEM, prompt, maxTokens = 1_500)
        }
        val ms = System.currentTimeMillis() - started
        val items = ExtractionParser.parse(raw)
        report("extraction: ${labelled.size} messages in $ms ms, reply ${raw.length} chars, parsed=${items != null}")
        report("raw: ${raw.take(1_500)}")
        assertNotNull("the reply parses", items)

        val byMessage = items!!.groupBy { (it.sourceMessageId - 1_000L).toInt() }
        var correctNegatives = 0
        var negatives = 0
        var foundPositives = 0
        var positives = 0
        labelled.forEachIndexed { i, (text, expected) ->
            val got = byMessage[i].orEmpty().map { item ->
                when (item.type) {
                    ExtractedItem.Type.FACT -> "fact:${com.local.assistant.memory.core.FactKeys.subject(item.subject.orEmpty())}.${com.local.assistant.memory.core.FactKeys.attribute(item.attribute.orEmpty())}"
                    ExtractedItem.Type.TASK -> "task"
                    ExtractedItem.Type.EVENT -> "event"
                }
            }.toSet()
            if (expected.isEmpty()) {
                negatives++
                if (got.isEmpty()) correctNegatives++
            } else {
                positives++
                if (got.isNotEmpty()) foundPositives++
            }
            report("${if (expected.isEmpty() == got.isEmpty()) "✓" else "✗"} \"$text\" → $got (expected ${expected.ifEmpty { setOf("nothing") }})")
        }
        report("negatives left alone: $correctNegatives/$negatives; positives found: $foundPositives/$positives")
        assertTrue("at most one negative may leak", correctNegatives >= negatives - 1)
    }

    @Test
    fun summariesAreShortAndInTheUsersWords() = runBlocking {
        enabled()
        val backend = container.llmBackend
        check(backend.ensureReady())
        val summarizer = Summarizer(backend, container.tokenEstimator)
        var id = 1L
        fun m(role: Role, text: String) = MessageEntity(id = id++, chatId = 1, role = role, text = text, createdAt = 0)
        val turns = listOf(
            m(Role.USER, "I want to plan a trip to Gokarna with Anjali for the second weekend of October"),
            m(Role.ASSISTANT, "Great! Gokarna is lovely in October. Do you want beach stays or something near the temple?"),
            m(Role.USER, "beach stays, and we need to book train tickets from Bengaluru, remind me to do that by Friday"),
            m(Role.ASSISTANT, "Noted. Kudle Beach and Om Beach have good stays. I'll remind you to book the train by Friday."),
            m(Role.USER, "also my budget is around 15k for two"),
            m(Role.ASSISTANT, "15,000 for two works for a 3-day trip with a mid-range stay."),
        )
        var started = System.currentTimeMillis()
        val session = container.conversations.withModelFree { summarizer.session(turns, capTokens = 200) }
        report("session summary in ${System.currentTimeMillis() - started} ms: $session")
        started = System.currentTimeMillis()
        val rolling = container.conversations.withModelFree {
            summarizer.rolling("The user is planning a trip to Gokarna.", turns.takeLast(2), capTokens = 300)
        }
        report("rolling summary in ${System.currentTimeMillis() - started} ms: $rolling")
        assertNotNull(session)
        assertNotNull(rolling)
        assertTrue(session!!.contains("Gokarna", ignoreCase = true))
    }

    @Test
    fun compactionFoldsALongChat() = runBlocking {
        enabled()
        val chats = container.chatRepository
        val chatId = chats.createChat("Compaction eval")
        try {
            // Twelve long turns: well over the 8K profile's history cap, so the rebuild drops the
            // oldest and compaction has something to fold.
            repeat(12) { i ->
                chats.addMessage(chatId, Role.USER, "Question $i about the renovation: " + "we are comparing tiles, paint and plumbing quotes for the kitchen and bathroom. ".repeat(6))
                chats.addMessage(chatId, Role.ASSISTANT, "Answer $i: " + "compare the per-square-foot prices, warranty and labour included in each quote. ".repeat(6))
            }
            val question = "Which quote did we lean towards for the kitchen tiles?"
            val userId = chats.addMessage(chatId, Role.USER, question)
            val reply = StringBuilder()
            var started = System.currentTimeMillis()
            container.turnRunner.run(chatId, userId, question).collect { if (it is TurnEvent.Text) reply.append(it.delta) }
            val replyId = chats.addMessage(chatId, Role.ASSISTANT, reply.toString())
            container.turnRunner.finish(chatId, userId, replyId, completed = true)
            report("turn with dropped history: ${System.currentTimeMillis() - started} ms, dropped ${container.conversations.droppedFromContext.value} messages")

            // Compaction runs after 30 s of quiet; the summary and rebuild take a while more.
            started = System.currentTimeMillis()
            var summary: String? = null
            while (System.currentTimeMillis() - started < 180_000 && summary == null) {
                delay(2_000)
                summary = chats.chat(chatId)?.rollingSummary
            }
            report("compaction after ${(System.currentTimeMillis() - started) / 1000} s: ${summary?.let { "\"${it.take(400)}\"" } ?: "none"}")
            report("covers up to message ${chats.chat(chatId)?.rollingUptoMessageId}; context now ${container.conversations.contextUsage.value}")
            assertNotNull("a rolling summary was written", summary)
        } finally {
            chats.deleteChat(chatId)
        }
    }

    /** A card with more on it than a one-line description would keep. */
    private fun wifiCard(file: File) {
        val bitmap = android.graphics.Bitmap.createBitmap(1024, 640, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 52f }
        listOf("HOME WIFI", "Network: Home_5G", "Password: kochi2026", "Router: TP-Link Archer C6", "Admin PIN: 4417", "Support: 1800-209-4455")
            .forEachIndexed { i, line -> canvas.drawText(line, 60f, 100f + i * 90f, paint) }
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
    }

    /** Runs one user turn the way the chat screen does and returns the reply and any chips. */
    private suspend fun turn(chatId: Long, text: String, image: String? = null): Pair<String, List<com.local.assistant.memory.tools.MemoryChip>> {
        val chats = container.chatRepository
        val userId = chats.addMessage(
            chatId, Role.USER, text,
            attachmentPath = image, attachmentKind = image?.let { com.local.assistant.data.db.AttachmentKind.IMAGE },
        )
        val reply = StringBuilder()
        val chips = mutableListOf<com.local.assistant.memory.tools.MemoryChip>()
        val attachment = image?.let { com.local.assistant.llm.PromptAttachment(it, com.local.assistant.data.db.AttachmentKind.IMAGE) }
        container.turnRunner.run(chatId, userId, text, attachment).collect { event ->
            when (event) {
                is TurnEvent.Text -> reply.append(event.delta)
                is TurnEvent.Memory -> chips += event.chip
                else -> Unit
            }
        }
        val replyId = chats.addMessage(chatId, Role.ASSISTANT, reply.toString())
        container.turnRunner.finish(chatId, userId, replyId, completed = true)
        return reply.toString() to chips
    }

    private fun ownLog(tag: String): List<String> =
        Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "$tag:I")).inputStream.bufferedReader().readLines()

    @Test
    fun aSavedImageIsLookedAtAgainWhenAskedAbout() = runBlocking {
        enabled()
        check(container.llmBackend.ensureReady())
        val chats = container.chatRepository
        val context = instrumentation.targetContext
        val image = File(File(context.filesDir, "attachments").apply { mkdirs() }, "eval-wifi-card.jpg").also(::wifiCard)
        val first = chats.createChat("Saved image eval")
        var second = 0L
        var noteId: Long? = null
        try {
            var started = System.currentTimeMillis()
            val (saidOnSave, chips) = turn(first, "remember this", image.path)
            report("save turn: ${System.currentTimeMillis() - started} ms, chips ${chips.map { "${it.kind}:${it.label}:${it.detail}" }}")
            report("reply: ${saidOnSave.take(300)}")
            val note = container.memoryControls.observeSavedImages().first().firstOrNull { it.imagePath != null && it.createdAt >= started }
            report("saved: ${note?.let { "“${it.title}”: ${it.details}" } ?: "nothing"}")
            assertNotNull("remember_image was called", note)
            noteId = note!!.id

            started = System.currentTimeMillis()
            container.embeddingQueue.drainNow()
            report("embedded in ${System.currentTimeMillis() - started} ms")

            // A new chat: the image is not in its history, so only recall can bring it back.
            second = chats.createChat("Saved image eval, later")
            val questions = listOf(
                "what's the admin PIN on the wifi card I saved?",
                "what's the support number on my router card?",
                // Not in any note: only looking at the image again answers it.
                "what colour is the background of that wifi card?",
            )
            for (question in questions) {
                Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
                started = System.currentTimeMillis()
                val (answer, _) = turn(second, question)
                val attached = ownLog("ConversationManager").lastOrNull { "aved image" in it }
                report("Q: $question → ${System.currentTimeMillis() - started} ms, ${attached?.substringAfter(": ") ?: "no saved image recalled"}")
                report("A: ${answer.take(300)}")
            }
            val answers = chats.messagesFor(second).filter { it.role == Role.ASSISTANT }.map { it.text }
            assertTrue("the PIN is read back", "4417" in answers[0])
            assertTrue("the support number is read back", answers[1].filter { it.isDigit() }.contains("18002094455"))
            assertTrue("the image itself is looked at", answers[2].contains("white", ignoreCase = true))
        } finally {
            noteId?.let { container.savedImages.delete(it) }
            chats.deleteChat(first)
            if (second != 0L) chats.deleteChat(second)
            image.delete()
        }
        Unit
    }
}
