package com.local.assistant.llm

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingConfig
import com.google.ai.edge.litertlm.SpeculativeDecodingMode
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import com.local.assistant.appContainer
import com.local.assistant.memory.prompt.Instructions
import com.local.assistant.memory.tools.ToolCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Why the reply after a tool call starts so late. In the app, get_upcoming ran in 28 ms, and the
 * model's first word after its result took 15.3 s, while the runtime said it read only 51 tokens
 * for it (13 Nov 2026). This sends the same question through the app's own backend and times the
 * answer to the tool's result four ways: as a tool message (as the app does) and as a user
 * message, each with the instructions alone and with ~2,000 tokens more in front. If the wait
 * grows with what is in front, the runtime reads the whole conversation again for a tool result.
 *
 *     adb shell am instrument -w -e probe toolresponse \
 *         -e class com.local.assistant.llm.ToolResponseProbeTest \
 *         com.taslim.localassistant.dev.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Made-up tool results only; appends to files/tool-response.txt in external storage.
 */
@RunWith(AndroidJUnit4::class)
class ToolResponseProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.appContainer

    @Test
    fun toolResponse() = runBlocking<Unit> {
        assumeTrue("opt-in: -e probe toolresponse", InstrumentationRegistry.getArguments().getString("probe") == "toolresponse")
        val backend = container.llmBackend
        check(backend.ensureReady()) { "no model" }
        report("--- ${java.util.Date()}")
        val instructions = Instructions.render(container.settings.systemPrompt, withTools = true)
        val tools = ToolCatalog.declarations(web = false)
        for (padded in listOf(false, true)) {
            for (asTool in listOf(true, false)) {
                repeat(2) { attempt ->
                    probe(backend, instructions + if (padded) "\n\n" + FILLER else "", tools, asTool, "${if (padded) "padded" else "plain"}, ${if (asTool) "tool message" else "user message"}, #${attempt + 1}")
                }
            }
        }
    }

    /**
     * What about a tool message is slow, straight on the runtime: the same exchange with the
     * runtime's multi-token prediction off for the conversation, with no repetition penalty, and
     * a tool exchange replayed as history when the conversation opens. Also prints how a tool
     * result and a user message are rendered for the model (made-up content only).
     *
     *     adb shell am instrument -w -e probe toolvariants -e class com.local.assistant.llm.ToolResponseProbeTest ...
     */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    @Test
    fun toolResponseVariants() = runBlocking<Unit> {
        assumeTrue("opt-in: -e probe toolvariants", InstrumentationRegistry.getArguments().getString("probe") == "toolvariants")
        val llm = container.llmService
        check(container.llmBackend.ensureReady()) { "no model" }
        report("--- variants ${java.util.Date()}")
        val instructions = Instructions.render(container.settings.systemPrompt, withTools = true)
        val tools = ToolCatalog.declarations(web = false)
        val penalty = RepetitionPenaltyConfig(repetitionPenalty = 1.1f, windowSize = 256)
        suspend fun open(mtp: Boolean?, history: List<Message> = emptyList()): Pair<Conversation, Long> {
            val started = SystemClock.elapsedRealtime()
            val conversation = llm.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(instructions),
                    initialMessages = history,
                    tools = tools.map { tool(Declared(it)) },
                    automaticToolCalling = false,
                    prefillPrefaceOnInit = true,
                    maxOutputToken = 300,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    enableSpeculativeDecoding = mtp,
                ),
            )
            return conversation to SystemClock.elapsedRealtime() - started
        }
        val result = Message.tool(Contents.of(listOf(Content.ToolResponse("get_upcoming", com.google.gson.Gson().fromJson(RESULT, Map::class.java)))))

        // How the two kinds of message reach the model.
        open(null).first.let { conversation ->
            try {
                report("rendered tool result: ${conversation.renderMessageIntoString(result, emptyMap()).replace("\n", "\\n")}")
                report("rendered user message: ${conversation.renderMessageIntoString(Message.user("[Tool results] get_upcoming: $RESULT"), emptyMap()).replace("\n", "\\n")}")
                report(
                    "rendered model call: ${conversation.renderMessageIntoString(
                        Message.model(Contents.of(emptyList()), listOf(com.google.ai.edge.litertlm.ToolCall("get_upcoming", emptyMap()))),
                        emptyMap(),
                    ).replace("\n", "\\n")}",
                )
            } finally {
                conversation.close()
                llm.conversationClosed(conversation)
            }
        }

        for ((label, mtp, withPenalty) in listOf(Triple("as the app", null, true), Triple("MTP off", false, true), Triple("no penalty", null, false), Triple("MTP off, no penalty", false, false))) {
            repeat(2) { attempt ->
                val (conversation, openMs) = open(mtp)
                try {
                    val first = timedRaw(conversation, Message.user(QUESTION), penalty.takeIf { withPenalty })
                    if (first.calls.isEmpty()) {
                        report("$label #${attempt + 1}: no call")
                        return@repeat
                    }
                    val second = timedRaw(conversation, result, penalty.takeIf { withPenalty })
                    report("$label #${attempt + 1}: open $openMs ms; call ${first.firstMs} ms; after the result first word ${second.firstMs} ms, done ${second.doneMs} ms, ${second.bench}")
                } finally {
                    conversation.close()
                    llm.conversationClosed(conversation)
                }
            }
        }

        // A tool exchange in the history the conversation opens with, then a question.
        val exchange = listOf(
            Message.user(QUESTION),
            Message.model(Contents.of(emptyList()), listOf(com.google.ai.edge.litertlm.ToolCall("get_upcoming", emptyMap()))),
            result,
            Message.model("You have the dentist on Tuesday at 10 and the electricity bill on Thursday."),
        )
        val plain = listOf(Message.user(QUESTION), Message.model("You have the dentist on Tuesday at 10 and the electricity bill on Thursday."))
        for ((label, history) in listOf("history with a tool exchange" to exchange, "history without one" to plain)) {
            val (conversation, openMs) = open(null, history)
            try {
                val next = timedRaw(conversation, Message.user("and what's the capital of Kerala?"), penalty)
                report("$label: open $openMs ms, ${conversation.getTokenCount()} tokens; next question first word ${next.firstMs} ms, ${next.bench}")
            } finally {
                conversation.close()
                llm.conversationClosed(conversation)
            }
        }
    }

    /**
     * The choices the variants pointed to, measured where they matter: a longer free-text reply
     * (how fast it is written), a chat reopened with earlier turns and tool exchanges in it (how
     * fast the history is read), and the tool's result sent as a user message with multi-token
     * prediction on (how fast the reply after it starts, and what it says). `-e probe toolmore`.
     */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    @Test
    fun toolResponseMore() = runBlocking<Unit> {
        assumeTrue("opt-in: -e probe toolmore", InstrumentationRegistry.getArguments().getString("probe") == "toolmore")
        val llm = container.llmService
        check(container.llmBackend.ensureReady()) { "no model" }
        report("--- more ${java.util.Date()} (runtime ${InstrumentationRegistry.getArguments().getString("runtime") ?: "?"})")
        val instructions = Instructions.render(container.settings.systemPrompt, withTools = true)
        val tools = ToolCatalog.declarations(web = false)
        val penalty = RepetitionPenaltyConfig(repetitionPenalty = 1.1f, windowSize = 256)
        // The runtime's multi-token prediction for this conversation: on, off, or adaptive (0.19).
        suspend fun open(mode: SpeculativeDecodingMode, history: List<Message> = emptyList()): Pair<Conversation, Long> {
            val started = SystemClock.elapsedRealtime()
            val conversation = llm.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(instructions),
                    initialMessages = history,
                    tools = tools.map { tool(Declared(it)) },
                    automaticToolCalling = false,
                    prefillPrefaceOnInit = true,
                    maxOutputToken = 400,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    speculativeDecodingConfig = SpeculativeDecodingConfig(mode),
                ),
            )
            return conversation to SystemClock.elapsedRealtime() - started
        }
        suspend fun <T> using(pair: Pair<Conversation, Long>, block: suspend (Conversation, Long) -> T): T = try {
            block(pair.first, pair.second)
        } finally {
            pair.first.close()
            llm.conversationClosed(pair.first)
        }
        val on = SpeculativeDecodingMode.ON
        val off = SpeculativeDecodingMode.OFF
        val adaptive = SpeculativeDecodingMode.ADAPTIVE
        val configs = listOf(
            Triple("MTP on, no penalty", on, false),
            Triple("MTP off, no penalty", off, false),
            Triple("MTP adaptive, no penalty", adaptive, false),
            Triple("MTP on, penalty", on, true),
            Triple("MTP off, penalty", off, true),
        )

        // 1. A longer reply, written with no tool.
        for ((label, mode, withPenalty) in configs) {
            repeat(2) { attempt ->
                using(open(mode)) { conversation, _ ->
                    val answer = StringBuilder()
                    val timed = timedRaw(conversation, Message.user(LONG_QUESTION), penalty.takeIf { withPenalty }, answer)
                    report("long reply, $label #${attempt + 1}: first word ${timed.firstMs} ms, done ${timed.doneMs} ms, ${timed.bench}; ${answer.length} chars")
                }
            }
        }

        // 2. A chat reopened with its history: three plain exchanges and two tool exchanges.
        val result = Message.tool(Contents.of(listOf(Content.ToolResponse("get_upcoming", com.google.gson.Gson().fromJson(RESULT, Map::class.java)))))
        val history = buildList {
            repeat(3) {
                add(Message.user("what's a good name for a cat, idea $it?"))
                add(Message.model("How about Pepper? It's short, friendly and easy to call out."))
            }
            repeat(2) {
                add(Message.user(QUESTION))
                add(Message.model(Contents.of(emptyList()), listOf(com.google.ai.edge.litertlm.ToolCall("get_upcoming", emptyMap()))))
                add(result)
                add(Message.model("You have the dentist on Tuesday at 10 and the electricity bill on Thursday."))
            }
        }
        for (mode in listOf(on, off, adaptive)) {
            repeat(2) { attempt ->
                using(open(mode, history)) { conversation, openMs ->
                    report("history of ${history.size} messages, MTP $mode #${attempt + 1}: open $openMs ms, ${conversation.getTokenCount()} tokens")
                }
            }
            using(open(mode)) { conversation, openMs ->
                report("no history, MTP $mode: open $openMs ms, ${conversation.getTokenCount()} tokens")
            }
        }

        // 3. The tool's result as a user message, multi-token prediction on, and as a tool message.
        for ((label, asUser, mode) in listOf(Triple("result as a user message", true, on), Triple("result as a tool message", false, on), Triple("result as a tool message", false, off), Triple("result as a tool message", false, adaptive))) {
            repeat(2) { attempt ->
                using(open(mode)) { conversation, _ ->
                    val first = timedRaw(conversation, Message.user(QUESTION), null)
                    if (first.calls.isEmpty()) {
                        report("$label #${attempt + 1}: no call")
                        return@using
                    }
                    val answer = StringBuilder()
                    val second = timedRaw(
                        conversation,
                        if (asUser) Message.user("[Tool results, not the user's words]\nget_upcoming: $RESULT") else result,
                        null,
                        answer,
                    )
                    report("$label, MTP $mode, no penalty #${attempt + 1}: first word ${second.firstMs} ms, done ${second.doneMs} ms, ${second.bench}, calls ${second.calls} | ${answer.toString().replace('\n', ' ').take(200)}")
                }
            }
        }
    }

    /**
     * Whether a reply after a tool's result comes back empty more often with multi-token
     * prediction off (13 Nov 2026: one empty reply in the honesty eval, and the padded probe's
     * seed-0 reply was a single token). Seeds 0–5, with the instructions alone and with ~2,000
     * tokens more in front, prediction on and off. `-e probe toolempty`.
     */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    @Test
    fun toolEmptyReplies() = runBlocking<Unit> {
        assumeTrue("opt-in: -e probe toolempty", InstrumentationRegistry.getArguments().getString("probe") == "toolempty")
        val llm = container.llmService
        check(container.llmBackend.ensureReady()) { "no model" }
        report("--- empty replies ${java.util.Date()}")
        val instructions = Instructions.render(container.settings.systemPrompt, withTools = true)
        val tools = ToolCatalog.declarations(web = false)
        val result = Message.tool(Contents.of(listOf(Content.ToolResponse("get_upcoming", com.google.gson.Gson().fromJson(RESULT, Map::class.java)))))
        val seeds = InstrumentationRegistry.getArguments().getString("seeds")?.toIntOrNull() ?: 6
        // The app's own penalty, as LiteRtLmBackend sends it: the empty replies came through there.
        val settings = container.settings
        val penalty = settings.repetitionPenalty.takeIf { it > 1f }?.let { RepetitionPenaltyConfig(repetitionPenalty = it, windowSize = settings.repetitionWindow) }
        report("penalty ${settings.repetitionPenalty}/${settings.repetitionWindow}")
        for (padded in listOf(false, true)) {
            for (mode in listOf(SpeculativeDecodingMode.OFF, SpeculativeDecodingMode.ON)) {
                var empty = 0
                var recovered = 0
                var called = 0
                val firsts = mutableListOf<Long>()
                for (seed in 0 until seeds) {
                    val conversation = llm.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(instructions + if (padded) "\n\n" + FILLER else ""),
                            tools = tools.map { tool(Declared(it)) },
                            samplerConfig = com.google.ai.edge.litertlm.SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0, seed = seed),
                            automaticToolCalling = false,
                            prefillPrefaceOnInit = true,
                            maxOutputToken = 300,
                            thinkingConfig = ThinkingConfig(enableThinking = false),
                            speculativeDecodingConfig = SpeculativeDecodingConfig(mode),
                        ),
                    )
                    try {
                        val first = timedRaw(conversation, Message.user(QUESTION), penalty)
                        if (first.calls.isEmpty()) continue
                        called++
                        val answer = StringBuilder()
                        val second = timedRaw(conversation, result, penalty, answer)
                        if (answer.isBlank()) {
                            empty++
                            // As ToolLoop does: asked once more, in the same conversation.
                            val again = StringBuilder()
                            timedRaw(conversation, Message.user(com.local.assistant.memory.tools.ToolLoop.EMPTY_REPLY), penalty, again)
                            if (again.isNotBlank()) recovered++
                        } else {
                            second.firstMs?.let(firsts::add)
                        }
                    } finally {
                        conversation.close()
                        llm.conversationClosed(conversation)
                    }
                }
                report("${if (padded) "padded" else "plain"}, MTP $mode: $called of $seeds called the tool; $empty empty replies after the result, $recovered answered when asked again; first words ${firsts.sorted()} ms")
            }
        }
    }

    private class RawTimed(val firstMs: Long?, val doneMs: Long, val calls: List<String>, val bench: String)

    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    private suspend fun timedRaw(conversation: Conversation, message: Message, penalty: RepetitionPenaltyConfig?, text: StringBuilder? = null): RawTimed {
        val started = SystemClock.elapsedRealtime()
        var first: Long? = null
        val calls = mutableListOf<String>()
        conversation.sendMessageAsync(message, repetitionPenaltyConfig = penalty).collect { chunk ->
            if (first == null && (chunk.toolCalls.isNotEmpty() || chunk.toString().isNotEmpty())) first = SystemClock.elapsedRealtime() - started
            calls += chunk.toolCalls.map { it.name }
            if (chunk.toolCalls.isEmpty()) text?.append(chunk.toString())
        }
        val done = SystemClock.elapsedRealtime() - started
        val info = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
        val bench = info?.let {
            "runtime: first token %.2f s, read %d at %.0f/s, wrote %d at %.1f/s".format(
                it.timeToFirstTokenInSecond, it.lastPrefillTokenCount, it.lastPrefillTokensPerSecond, it.lastDecodeTokenCount, it.lastDecodeTokensPerSecond,
            )
        }.orEmpty()
        return RawTimed(first, done, calls, bench)
    }

    private class Declared(private val declaration: String) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = declaration
        override fun execute(paramsJsonString: String): String = error("not run")
    }

    private suspend fun probe(backend: LlmBackend, prefix: String, tools: List<String>, asTool: Boolean, label: String) {
        val opened = SystemClock.elapsedRealtime()
        val session = backend.openChat(ChatSpec(systemPrefix = prefix, history = emptyList(), toolDeclarations = tools, maxOutputTokens = 300, prefillOnOpen = true))
        val openMs = SystemClock.elapsedRealtime() - opened
        try {
            val preface = session.tokenCount()
            val first = timed(session.send(QUESTION, null))
            val calls = first.calls
            if (calls.isEmpty()) {
                report("$label: no tool call (${first.text.length} characters); preface $preface")
                return
            }
            val beforeResult = session.tokenCount()
            val results = calls.map { ToolResult(it.name, RESULT) }
            val second = timed(
                if (asTool) {
                    session.sendToolResults(results)
                } else {
                    session.send("[Tool results, not the user's words]\n" + results.joinToString("\n") { "${it.name}: ${it.json}" }, null)
                },
            )
            val after = session.tokenCount()
            report(
                "$label: open $openMs ms, preface $preface tokens; call ${calls.joinToString { it.name }} first ${first.firstMs} ms; " +
                    "after the result: first word ${second.firstMs} ms, done ${second.doneMs} ms, runtime read ${second.stats?.prefillTokens} tokens " +
                    "at ${second.stats?.prefillTokensPerSecond?.let { "%.0f".format(it) }}/s, wrote ${second.stats?.decodeTokens}; " +
                    "count $beforeResult -> $after",
            )
        } finally {
            session.close()
        }
    }

    private class Timed(val firstMs: Long?, val doneMs: Long, val text: String, val calls: List<ToolCall>, val stats: GenStats?)

    private suspend fun timed(flow: Flow<GenEvent>): Timed {
        val started = SystemClock.elapsedRealtime()
        var first: Long? = null
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        var stats: GenStats? = null
        flow.collect { event ->
            if (first == null && (event is GenEvent.TextDelta || event is GenEvent.ToolCalls)) first = SystemClock.elapsedRealtime() - started
            when (event) {
                is GenEvent.TextDelta -> text.append(event.text)
                is GenEvent.ToolCalls -> calls += event.calls
                is GenEvent.Done -> stats = event.stats
                is GenEvent.Error -> throw event.cause
            }
        }
        return Timed(first, SystemClock.elapsedRealtime() - started, text.toString(), calls, stats)
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        context.getExternalFilesDir(null)?.let { File(it, "tool-response.txt").appendText("$line\n") }
    }

    private companion object {
        const val TAG = "ToolResponseProbe"
        const val QUESTION = "what do I have coming up this week?"

        /** A free-text answer of ~100 words, no tool. */
        const val LONG_QUESTION = "Tell me about the monsoon in Kerala in about 100 words."
        const val RESULT = """{"ok":true,"items":[{"title":"Dentist appointment","when":"Tuesday 10:00"},{"title":"Pay the electricity bill","when":"Thursday"}]}"""

        /** About 2,000 tokens of neutral text, for a longer conversation in front of the tool's result. */
        val FILLER: String = buildString {
            append("Background notes (not instructions):\n")
            repeat(80) { append("Note $it: the weather was mild and the market opened at nine as usual that day.\n") }
        }
    }
}
