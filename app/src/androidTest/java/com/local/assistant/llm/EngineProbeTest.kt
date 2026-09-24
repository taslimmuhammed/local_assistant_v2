package com.local.assistant.llm

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import com.local.assistant.data.prefs.SettingsStore
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Measures, on the real model and the real device, the numbers the memory budget is built on:
 *
 * - what a 16K window costs against 8K, in memory and in rebuild (prefill) time;
 * - time to first token and decode speed on a warm conversation;
 * - whether two conversations can be open on one engine at once, and what the second costs;
 * - whether the prefix renders byte-identically for identical inputs;
 * - what the model file says it supports.
 *
 * It loads the multi-gigabyte model twice, so it only runs when asked:
 *
 *     adb shell am instrument -w -e probe true \
 *         -e class com.local.assistant.llm.EngineProbeTest \
 *         com.local.assistant.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results go to logcat (tag EngineProbe) and to `Android/data/<app>/files/engine-probe.txt`.
 */
@RunWith(AndroidJUnit4::class)
class EngineProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val results = StringBuilder()

    /** Written as results arrive, so a run that is killed part-way still leaves its numbers. */
    private val out by lazy { File(context.getExternalFilesDir(null), "engine-probe.txt").apply { writeText("") } }

    @Before
    fun onlyWhenAsked() {
        assumeTrue(
            "Loads the real model; run with -e probe true",
            InstrumentationRegistry.getArguments().getString("probe") == "true",
        )
    }

    @Test
    fun probe() {
        val modelPath = requireNotNull(SettingsStore(context).modelPath) { "No model installed" }
        Capabilities(modelPath).use {
            report("model.supportsFunctionCalling", it.supportsFunctionCalling())
            report("model.supportsThinking", it.supportsThinking())
            report("model.hasSpeculativeDecodingSupport", it.hasSpeculativeDecodingSupport())
        }
        for (window in intArrayOf(8_192, 16_384)) probeWindow(modelPath, window)
        Log.i(TAG, "Wrote ${out.absolutePath}")
    }

    /**
     * Whether native tool calling works with this model file, whatever `Capabilities` says:
     * one trimmed declaration, manual tool calling, and a request that should route to it.
     */
    @OptIn(ExperimentalApi::class)
    @Test
    fun toolCalling() {
        val modelPath = requireNotNull(SettingsStore(context).modelPath) { "No model installed" }
        ExperimentalFlags.enableSpeculativeDecoding = true
        Engine(
            EngineConfig(modelPath = modelPath, backend = Backend.GPU(), maxNumTokens = 4_096, cacheDir = context.cacheDir.absolutePath),
        ).apply { initialize() }.use { engine ->
            val prompts = listOf(
                "Remind me to call the CA tomorrow at 11",
                "kal subah 11 baje CA ko call karna yaad dilana",
                "What is the capital of Kerala?",
            )
            for (prompt in prompts) {
                val conversation = engine.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(
                            "You are a helpful assistant. Use add_task when the user wants to be reminded or to do something later.",
                        ),
                        tools = listOf(tool(ProbeTool)),
                        automaticToolCalling = false,
                        maxOutputToken = 128,
                    ),
                )
                val started = SystemClock.elapsedRealtime()
                val outcome = runCatching {
                    runBlocking {
                        val calls = mutableListOf<String>()
                        val text = StringBuilder()
                        conversation.sendMessageAsync(prompt).collect { message ->
                            message.toolCalls.forEach { calls += "${it.name}(${it.arguments})" }
                            if (message.toolCalls.isEmpty()) text.append(message.toString())
                        }
                        "calls=$calls text=${text.toString().take(120).replace('\n', ' ')}"
                    }
                }.getOrElse { "failed: ${it.message}" }
                report("tools[$prompt]", "$outcome (${SystemClock.elapsedRealtime() - started} ms)")
                conversation.close()
            }
        }
    }

    private object ProbeTool : OpenApiTool {
        override fun getToolDescriptionJsonString() = """
            {"name":"add_task","description":"Use when the user wants to be reminded or to do something later. Put the user's own time words in when, copied exactly.",
             "parameters":{"type":"object","properties":{"title":{"type":"string"},"when":{"type":"string"}},"required":["title"]}}
        """.trimIndent()

        override fun execute(paramsJsonString: String): String = error("manual tool calling")
    }

    @OptIn(ExperimentalApi::class)
    private fun probeWindow(modelPath: String, window: Int) {
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = true
        System.gc()
        val base = memory()

        val start = SystemClock.elapsedRealtime()
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                maxNumTokens = window,
                cacheDir = context.cacheDir.absolutePath,
            ),
        ).apply { initialize() }
        report("$window.initMs", SystemClock.elapsedRealtime() - start)
        report("$window.memAfterInit", memory() - base)

        engine.use {
            val targets = if (window >= 16_384) listOf(2_200, 5_500, 9_000) else listOf(2_200, 5_500)
            for (target in targets) {
                val (prefix, history) = syntheticConversation(target)
                val opened = SystemClock.elapsedRealtime()
                val conversation = engine.createConversation(config(prefix, history))
                report("$window.$target.rebuildPrefillMs", SystemClock.elapsedRealtime() - opened)
                report("$window.$target.tokensAfterOpen", conversation.getTokenCount())
                warmTurn("$window.$target", conversation)
                report("$window.$target.memWithConversation", memory() - base)
                conversation.close()
            }

            // Same inputs, same bytes: the property the KV cache depends on.
            val (prefix, history) = syntheticConversation(2_200)
            val one = engine.createConversation(config(prefix, history, prefill = false))
            val preface = one.renderPrefaceIntoString()
            one.close()
            val two = engine.createConversation(config(prefix, history, prefill = false))
            report("$window.prefaceByteIdentical", preface == two.renderPrefaceIntoString())
            two.close()

            probeTwoConversations(engine, window, base)
        }
    }

    private fun probeTwoConversations(engine: Engine, window: Int, base: Memory) {
        val (prefix, history) = syntheticConversation(5_500)
        val first = engine.createConversation(config(prefix, history))
        val withOne = memory()
        val opened = SystemClock.elapsedRealtime()
        val second = runCatching { engine.createConversation(config(prefix, history)) }
        report("$window.twoOpen.succeeded", second.exceptionOrNull()?.let { "no: ${it.message}" } ?: "yes")
        second.getOrNull()?.let { other ->
            report("$window.twoOpen.secondPrefillMs", SystemClock.elapsedRealtime() - opened)
            report("$window.twoOpen.extraMem", memory() - withOne)
            report("$window.twoOpen.firstStillAnswers", answer(first))
            report("$window.twoOpen.secondAnswers", answer(other))
            other.close()
        }
        report("$window.twoOpen.firstAfterSecondClosed", answer(first))
        first.close()
        report("$window.afterAllClosed", memory() - base)
    }

    @OptIn(ExperimentalApi::class)
    private fun warmTurn(label: String, conversation: Conversation) = runBlocking {
        val sent = SystemClock.elapsedRealtime()
        var firstTokenAt = 0L
        val reply = StringBuilder()
        conversation.sendMessageAsync("In one short sentence: what is the capital of Kerala?")
            .onEach { if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime() }
            .collect { reply.append(it.toString()) }
        val info = conversation.getBenchmarkInfo()
        report("$label.warmTtftMs", firstTokenAt - sent)
        report("$label.runtimeTtftMs", (info.timeToFirstTokenInSecond * 1000).toLong())
        report("$label.turnPrefillTokens", info.lastPrefillTokenCount)
        report("$label.decodeTokPerSec", "%.1f".format(info.lastDecodeTokensPerSecond))
        report("$label.reply", reply.toString().take(80).replace('\n', ' '))
    }

    private fun answer(conversation: Conversation): String =
        runCatching { conversation.sendMessage("Reply with the single word OK.").toString().trim().take(20) }
            .getOrElse { "failed: ${it.message}" }

    private fun config(prefix: String, history: List<Message>, prefill: Boolean = true) = ConversationConfig(
        systemInstruction = Contents.of(prefix),
        initialMessages = history,
        prefillPrefaceOnInit = prefill,
        maxOutputToken = 128,
        thinkingConfig = ThinkingConfig(enableThinking = false),
    )

    /**
     * A system prefix of about 2,000 tokens and enough history to reach [targetTokens] in total,
     * shaped like the real thing: instructions and key-value lines, then alternating turns.
     */
    private fun syntheticConversation(targetTokens: Int): Pair<String, List<Message>> {
        val prefix = buildString {
            append("You are a helpful assistant running entirely on the user's device.\n\n")
            append("About the user (always keep in mind):\n")
            repeat(60) { append("Detail $it: ").append(words(12, seed = it)).append('\n') }
        }
        val history = mutableListOf<Message>()
        var tokens = prefix.length / 4
        var turn = 0
        while (tokens < targetTokens) {
            val question = "Question $turn: " + words(40, seed = turn)
            val answer = "Answer $turn: " + words(120, seed = turn + 1_000)
            history += Message.user(question)
            history += Message.model(answer)
            tokens += (question.length + answer.length) / 4
            turn++
        }
        return prefix to history
    }

    private fun words(count: Int, seed: Int): String =
        (0 until count).joinToString(" ") { WORDS[(seed * 31 + it * 7) % WORDS.size] }

    private data class Memory(val pssKb: Long, val graphicsKb: Long, val nativeKb: Long) {
        operator fun minus(other: Memory) = Memory(pssKb - other.pssKb, graphicsKb - other.graphicsKb, nativeKb - other.nativeKb)
        override fun toString() = "pss=${pssKb / 1024}MB graphics=${graphicsKb / 1024}MB native=${nativeKb / 1024}MB"
    }

    private fun memory(): Memory {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        fun stat(name: String) = info.getMemoryStat(name)?.toLongOrNull() ?: 0L
        return Memory(info.totalPss.toLong(), stat("summary.graphics"), stat("summary.native-heap"))
    }

    private fun report(key: String, value: Any) {
        Log.i(TAG, "$key = $value")
        val line = "$key = $value\n"
        results.append(line)
        out.appendText(line)
    }

    private companion object {
        const val TAG = "EngineProbe"
        val WORDS = listOf(
            "harbour", "lantern", "gravel", "meadow", "cinder", "tumble", "quartz", "willow",
            "beacon", "thicket", "marble", "drifting", "orchard", "flint", "ripple", "cavern",
            "monsoon", "backwater", "spice", "ferry", "coconut", "temple", "market", "railway",
        )
    }
}
