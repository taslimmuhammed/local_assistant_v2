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

    /** Numbers copied from the question, with and without the repetition penalty. */
    @Test
    fun numbersAreCopiedExactly() = runBlocking {
        enabled()
        check(container.llmBackend.ensureReady())
        val settings = container.settings
        val original = settings.repetitionPenalty
        val questions = InstrumentationRegistry.getArguments().getString("question")?.let { listOf(it) }
            ?: listOf(
                "whats 25 * 2", "what is root of 25", "what is 25+25", "what's 1234 plus 4321",
                // Not only a sum: these go to the model, and its expression is grounded.
                "split 3450 between 4 people", "I spent 1250 on food and 875 on travel, what's the total?",
            )
        val penalties = listOf(original)
        try {
            for (penalty in penalties) {
                settings.repetitionPenalty = penalty
                for (question in questions) {
                    val chat = container.chatRepository.createChat("numbers eval")
                    try {
                        val (reply, _) = turn(chat, question)
                        val calls = container.chatRepository.messagesFor(chat).filter { it.role == Role.TOOL }.map { it.text.substringAfter("\"arguments\":").substringBefore(",\"result\"") }
                        report("penalty $penalty | $question -> ${calls.joinToString()} | ${reply.take(120).replace('\n', ' ')}")
                    } finally {
                        container.chatRepository.deleteChat(chat)
                    }
                }
            }
        } finally {
            settings.repetitionPenalty = original
        }
        Unit
    }

    /** Where do the digits go? The app's backend, stripped down step by step. */
    @Test
    fun numbersProbe() = runBlocking {
        enabled()
        val backend = container.llmBackend
        check(backend.ensureReady())
        suspend fun ask(system: String, tools: List<String>, text: String): String = container.conversations.withModelFree {
            backend.openChat(com.local.assistant.llm.ChatSpec(systemPrefix = system, history = emptyList(), toolDeclarations = tools, maxOutputTokens = 120)).use { chat ->
                val out = StringBuilder()
                chat.send(text).collect { event ->
                    when (event) {
                        is com.local.assistant.llm.GenEvent.TextDelta -> out.append(event.text)
                        is com.local.assistant.llm.GenEvent.ToolCalls -> out.append("CALL ${event.calls}")
                        is com.local.assistant.llm.GenEvent.Error -> out.append("ERROR ${event.cause.message}")
                        else -> Unit
                    }
                }
                out.toString().replace('\n', ' ').take(140)
            }
        }
        val instructions = com.local.assistant.memory.prompt.Instructions.render("You are a helpful assistant.", withTools = true)
        val tools = com.local.assistant.memory.tools.ToolCatalog.declarations(web = false)
        for (q in listOf("what's 256 multiplied by 4", "what's square root of 25")) {
            report("app instructions+tools | $q -> ${ask(instructions, tools, q)}")
            report("bare, no tools | $q -> ${ask("You are a helpful assistant.", emptyList(), q)}")
        }
        report("echo | ${ask("You are a helpful assistant.", emptyList(), "Repeat exactly, nothing else: 256 1234 4321 25 98450")}")
        report("complete echo | ${backend.complete("You are a helpful assistant.", "Repeat exactly, nothing else: 256 1234 4321 25 98450", maxTokens = 60).replace('\n', ' ')}")
        Unit
    }

    /** Which part of the real system prompt makes the model drop digits? */
    @Test
    fun numbersBisect() = runBlocking {
        enabled()
        val backend = container.llmBackend
        check(backend.ensureReady())
        val memory = container.memoryRepository
        val budget = com.local.assistant.memory.prompt.MemoryBudget.EIGHT_K
        val zone = java.time.ZoneId.systemDefault()
        val assembler = com.local.assistant.memory.prompt.PromptAssembler(budget, container.tokenEstimator, zone)
        val instructions = com.local.assistant.memory.prompt.Instructions.render(container.settings.systemPrompt, withTools = true)
        val core = memory.coreFacts()
        val summary = memory.latestSessionSummary()?.let { com.local.assistant.memory.prompt.SummaryBlock(com.local.assistant.memory.prompt.SummaryBlock.Kind.LAST_SESSION, it) }
        val agenda = memory.agenda(System.currentTimeMillis(), budget.agendaItems)
        val tools = com.local.assistant.memory.tools.ToolCatalog.declarations(web = false)
        val now = "[Now: " + java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm", java.util.Locale.ENGLISH).format(java.time.ZonedDateTime.now()) + "]\n"
        fun prefix(withCore: Boolean, withSummary: Boolean, withAgenda: Boolean) = assembler.buildPrefix(
            com.local.assistant.memory.prompt.PrefixInputs(
                instructions, if (withCore) core else emptyList(), summary.takeIf { withSummary }, java.time.LocalDate.now(zone), if (withAgenda) agenda else emptyList(),
            ),
        ).text
        suspend fun ask(system: String, text: String, prefill: Boolean, sampling: com.local.assistant.llm.Sampling = com.local.assistant.llm.Sampling.CHAT): String = container.conversations.withModelFree {
            backend.openChat(com.local.assistant.llm.ChatSpec(systemPrefix = system, history = emptyList(), toolDeclarations = tools, sampling = sampling, maxOutputTokens = 60, prefillOnOpen = prefill)).use { chat ->
                val out = StringBuilder()
                chat.send(text).collect { event ->
                    when (event) {
                        is com.local.assistant.llm.GenEvent.TextDelta -> out.append(event.text)
                        is com.local.assistant.llm.GenEvent.ToolCalls -> out.append(event.calls.joinToString { it.arguments.toString() })
                        is com.local.assistant.llm.GenEvent.Error -> out.append("ERROR ${event.cause.message?.take(80)}")
                        else -> Unit
                    }
                }
                out.toString().replace('\n', ' ').take(if (InstrumentationRegistry.getArguments().getString("variants") == "speed") 40 else 70)
            }
        }
        report("full prefix:\n" + prefix(true, true, true).takeLast(900))
        val variants = listOf(
            "full, prefilled" to Triple(prefix(true, true, true), true, now),
            "full, not prefilled" to Triple(prefix(true, true, true), false, now),
            "full, no Now line" to Triple(prefix(true, true, true), true, ""),
            "no core" to Triple(prefix(false, true, true), true, now),
            "no summary" to Triple(prefix(true, false, true), true, now),
            "no agenda" to Triple(prefix(true, true, false), true, now),
            "instructions only" to Triple(prefix(false, false, false), true, now),
        )
        val full = prefix(true, true, true)
        val bare = prefix(false, false, false)
        // Same length as the full prompt, no digits: is it the numbers or the length?
        var padded = bare
        val filler = "\nBe warm and plain in tone; avoid jargon, keep answers focused, and ask when something is unclear."
        while (container.tokenEstimator.estimate(padded) < container.tokenEstimator.estimate(full)) padded += filler
        val mode = InstrumentationRegistry.getArguments().getString("variants")
        val questions = listOf("what is 25+25" to "25+25", "what's 256 multiplied by 4" to "256*4", "what's 1234 plus 4321" to "1234+4321")
        if (mode == "mtp") {
            suspend fun score(label: String) {
                check(backend.ensureReady())
                var right = 0
                val wrong = mutableListOf<String>()
                for (seed in 0..2) for ((q, expected) in questions) {
                    val got = ask(full, now + q, true, com.local.assistant.llm.Sampling.CHAT.copy(seed = seed))
                    if (got.replace(" ", "").contains(expected)) right++ else wrong += got
                }
                report("$label (${container.llmService.state.value}): $right/9 exact | wrong: ${wrong.joinToString(" ; ")}")
            }
            val settings = container.settings
            val cpu = InstrumentationRegistry.getArguments().getString("cpu") == "true"
            try {
                if (cpu) settings.useGpu = false else settings.useSpeculativeDecoding = false
                container.conversations.withModelFree { container.llmService.unload() }
                score(if (cpu) "CPU" else "MTP off")
            } finally {
                settings.useGpu = true
                settings.useSpeculativeDecoding = true
                container.conversations.withModelFree { container.llmService.unload() }
                check(backend.ensureReady())
                report("restored: ${container.llmService.state.value}")
            }
            return@runBlocking
        }
        if (mode == "threshold") {
            val bareInstructions = prefix(false, false, false)
            val filler = "\nBe warm and plain in tone; avoid jargon, keep answers focused, and ask when something is unclear."
            val probes = listOf("what is 25+25" to "25+25", "set an alarm for 6:35" to "6:35", "remind me to call the CA tomorrow at 11:45" to "11:45")
            suspend fun sweep(label: String, padsList: List<Int>) {
                check(backend.ensureReady())
                for (pads in padsList) {
                    val system = bareInstructions + filler.repeat(pads)
                    var right = 0
                    var tokens: Int? = null
                    for ((q, expected) in probes) {
                        val got = container.conversations.withModelFree {
                            backend.openChat(com.local.assistant.llm.ChatSpec(systemPrefix = system, history = emptyList(), toolDeclarations = tools, maxOutputTokens = 40, prefillOnOpen = true)).use { chat ->
                                if (tokens == null) tokens = chat.tokenCount()
                                val out = StringBuilder()
                                chat.send(now + q).collect { e -> if (e is com.local.assistant.llm.GenEvent.ToolCalls) out.append(e.calls.joinToString { it.arguments.toString() }) else if (e is com.local.assistant.llm.GenEvent.TextDelta) out.append(e.text) }
                                out.toString()
                            }
                        }
                        if (got.replace(" ", "").contains(expected)) right++
                    }
                    report("$label pads $pads: prefix $tokens tokens: $right/${probes.size}")
                }
            }
            val sizes = (InstrumentationRegistry.getArguments().getString("sizes") ?: "8192").split(',').map { it.trim().toInt() }
            val settings = container.settings
            try {
                for (size in sizes) {
                    settings.manualContextTokens = if (size == 8192) 0 else size
                    container.conversations.withModelFree { container.llmService.unload() }
                    sweep("engine $size", listOf(4, 6, 7, 8, 9, 12))
                }
            } finally {
                settings.manualContextTokens = 0
                container.conversations.withModelFree { container.llmService.unload() }
                check(backend.ensureReady())
                report("restored: ${container.llmService.state.value}")
            }
            return@runBlocking
        }
        if (mode == "position") {
            val bareInstructions = prefix(false, false, false)
            val filler = "\nBe warm and plain in tone; avoid jargon, keep answers focused, and ask when something is unclear."
            val probes = listOf("what is 25+25" to "25+25", "what's 1234 plus 4321" to "1234+4321", "set an alarm for 6:35" to "6:35", "remind me to call the CA tomorrow at 11:45" to "11:45", "call 98450 12345" to "9845012345")
            for (pads in listOf(0, 10, 20, 40, 80)) {
                val system = bareInstructions + filler.repeat(pads)
                var right = 0
                var tokens: Int? = null
                val wrong = mutableListOf<String>()
                for ((q, expected) in probes) {
                    val got = container.conversations.withModelFree {
                        backend.openChat(com.local.assistant.llm.ChatSpec(systemPrefix = system, history = emptyList(), toolDeclarations = tools, maxOutputTokens = 60, prefillOnOpen = true)).use { chat ->
                            if (tokens == null) tokens = chat.tokenCount()
                            val out = StringBuilder()
                            chat.send(now + q).collect { e -> if (e is com.local.assistant.llm.GenEvent.ToolCalls) out.append(e.calls.joinToString { it.arguments.toString() }) else if (e is com.local.assistant.llm.GenEvent.TextDelta) out.append(e.text) }
                            out.toString()
                        }
                    }
                    if (got.replace(" ", "").contains(expected)) right++ else wrong += got.take(50)
                }
                report("pads $pads: prefix $tokens real tokens: $right/${probes.size} | ${wrong.joinToString(" ; ")}")
            }
            // And the real full prompt, for its real size.
            val fullTokens = container.conversations.withModelFree {
                backend.openChat(com.local.assistant.llm.ChatSpec(systemPrefix = full, history = emptyList(), toolDeclarations = tools, maxOutputTokens = 10, prefillOnOpen = true)).use { it.tokenCount() }
            }
            report("full prompt: $fullTokens real tokens")
            return@runBlocking
        }
        if (mode == "speed") {
            suspend fun measure(label: String) {
                check(backend.ensureReady())
                val loadStarted = System.currentTimeMillis()
                for ((q, _) in questions.take(2) + listOf("write a short paragraph about the monsoon in Kerala" to "")) {
                    val started = System.currentTimeMillis()
                    val got = ask(full, now + q, true)
                    val stats = container.llmService.lastGenerationStats.value
                    report("$label | ${System.currentTimeMillis() - started} ms total | ttft ${stats?.timeToFirstTokenMs} ms | prefill ${stats?.prefillTokens} tok | decode ${stats?.decodeTokens} tok at ${stats?.tokensPerSecond?.let { "%.1f".format(it) }} tok/s | $got")
                }
            }
            val settings = container.settings
            measure("GPU+MTP")
            try {
                settings.useGpu = false
                container.conversations.withModelFree { container.llmService.unload() }
                measure("CPU")
            } finally {
                settings.useGpu = true
                container.conversations.withModelFree { container.llmService.unload() }
                check(backend.ensureReady())
                report("restored: ${container.llmService.state.value}")
            }
            return@runBlocking
        }
        if (mode == "scope" || mode == "reorder") {
            val bareInstructions = prefix(false, false, false)
            val dynamic = full.removePrefix(bareInstructions).trim()
            val system = if (mode == "reorder") dynamic + "\n\n" + bareInstructions else full
            val probes = listOf(
                "what is 25+25" to "25+25",
                "what's 256 multiplied by 4" to "256*4",
                "what's 1234 plus 4321" to "1234+4321",
                "remind me to call the CA tomorrow at 11:45" to "11:45",
                "set an alarm for 6:35" to "6:35",
                "call 98450 12345" to "98450 12345",
                "remind me on 17 October to renew the car insurance" to "17",
                "what's 18% of 2450" to "2450",
                "what's 3450 divided by 4" to "3450",
                "what's 99 times 7" to "=99*7",
                "what's 12 plus 30" to "12",
            )
            var right = 0
            for ((q, expected) in probes) {
                val got = ask(system, now + q, true)
                val flat = "=" + got.replace(" ", "").substringAfter("=")
                val ok = if (expected.startsWith("=")) flat.startsWith(expected) else got.replace(" ", "").contains(expected.replace(" ", ""))
                if (ok) right++
                report("${if (ok) "OK  " else "MISS"} $q -> $got")
            }
            report("$mode: $right/${probes.size}")
            return@runBlocking
        }
        if (mode == "temperature") {
            for (temperature in listOf(1.0, 0.7, 0.4, 0.2)) {
                var right = 0
                val wrong = mutableListOf<String>()
                for (seed in 0..2) for ((q, expected) in questions) {
                    val got = ask(full, now + q, true, com.local.assistant.llm.Sampling.CHAT.copy(temperature = temperature, seed = seed))
                    if (got.replace(" ", "").contains(expected)) right++ else wrong += got
                }
                report("t=$temperature: $right/9 exact | wrong: ${wrong.joinToString(" ; ")}")
            }
            return@runBlocking
        }
        val chosen = when (mode) {
            "length" -> listOf("instructions padded to full length" to Triple(padded, true, now), "full" to Triple(full, true, now))
            "full" -> listOf("full" to Triple(full, true, now))
            else -> variants
        }
        for ((name, v) in chosen) {
            val (system, prefill, nowLine) = v
            val answers = listOf("what is 25+25", "what's 256 multiplied by 4", "what's 1234 plus 4321").map { ask(system, nowLine + it, prefill) }
            report("$name | ${answers.joinToString(" || ")}")
        }
        Unit
    }
}
